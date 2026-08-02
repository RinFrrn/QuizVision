package com.virin.visionquiz.quizstudy

import com.virin.visionquiz.dao.ReviewCard
import com.virin.visionquiz.dao.ReviewCardState
import com.virin.visionquiz.dao.ReviewRating
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * FSRS-6 based scheduler with one short learning step and one relearning step.
 *
 * Stability and difficulty are the source of truth. The legacy interval/ease fields are retained
 * so existing databases and UI code remain compatible while old cards migrate lazily.
 */
object SpacedRepetitionScheduler {
    const val CURRENT_SCHEDULER_VERSION = 6
    const val DEFAULT_DESIRED_RETENTION = 0.9

    private const val MAX_INTERVAL_DAYS = 36_500.0
    private const val MILLIS_PER_DAY = 86_400_000L
    private const val ONE_MINUTE_IN_DAYS = 1.0 / 1_440.0
    private const val SIX_MINUTES_IN_DAYS = 6.0 / 1_440.0
    private const val TEN_MINUTES_IN_DAYS = 10.0 / 1_440.0
    private const val THIRTY_MINUTES_IN_DAYS = 30.0 / 1_440.0
    private const val MIN_STABILITY = 0.001
    private const val MIN_DIFFICULTY = 1.0
    private const val MAX_DIFFICULTY = 10.0
    private const val DEFAULT_EASE_FACTOR = 2.5

    // Official FSRS-6 default parameters.
    private val w = doubleArrayOf(
        0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194,
        0.001, 1.8722, 0.1666, 0.796, 1.4835, 0.0614, 0.2629,
        1.6483, 0.6014, 1.8729, 0.5425, 0.0912, 0.0658, 0.1542
    )

    fun schedule(
        card: ReviewCard,
        rating: ReviewRating,
        now: Long = System.currentTimeMillis(),
        desiredRetention: Double = DEFAULT_DESIRED_RETENTION
    ): ReviewCard {
        val normalized = normalizeCard(card)
        val next = calculateCandidates(normalized, now, desiredRetention).getValue(rating)
        val wasReview = ReviewCardState.fromValue(normalized.state) == ReviewCardState.REVIEW
        return normalized.copy(
            dueAt = addIntervalSafely(now, next.intervalDays),
            intervalDays = next.intervalDays,
            easeFactor = sanitizeEase(card.easeFactor),
            reviewCount = card.reviewCount + 1,
            lapseCount = card.lapseCount + if (wasReview && rating == ReviewRating.FORGOT) 1 else 0,
            lastReviewedAt = now,
            state = next.state.value,
            stability = next.stability,
            difficulty = next.difficulty,
            schedulerVersion = CURRENT_SCHEDULER_VERSION
        )
    }

    fun previewNextIntervals(
        card: ReviewCard,
        now: Long = System.currentTimeMillis(),
        desiredRetention: Double = DEFAULT_DESIRED_RETENTION
    ): Map<ReviewRating, Double> {
        return calculateCandidates(normalizeCard(card), now, desiredRetention)
            .mapValues { it.value.intervalDays }
    }

    internal fun elapsedDays(card: ReviewCard, now: Long): Double {
        val lastReviewedAt = card.lastReviewedAt
        if (lastReviewedAt != null) {
            return ((now - lastReviewedAt).coerceAtLeast(0L)).toDouble() / MILLIS_PER_DAY
        }
        return if (card.reviewCount > 0) sanitizeInterval(card.intervalDays) else 0.0
    }

    private fun calculateCandidates(
        card: ReviewCard,
        now: Long,
        desiredRetention: Double
    ): Map<ReviewRating, Candidate> {
        val raw = ReviewRating.values().associateWith { rating ->
            calculateCandidate(card, rating, now, desiredRetention)
        }.toMutableMap()

        // Guard against surprising button order caused by extreme/custom histories.
        val hard = raw.getValue(ReviewRating.HARD)
        val good = raw.getValue(ReviewRating.GOOD)
        val easy = raw.getValue(ReviewRating.EASY)
        var orderedGood = good
        var orderedEasy = easy
        if (hard.state == ReviewCardState.REVIEW && good.state == ReviewCardState.REVIEW) {
            orderedGood = good.copy(intervalDays = max(good.intervalDays, hard.intervalDays))
        }
        if (orderedGood.state == ReviewCardState.REVIEW && easy.state == ReviewCardState.REVIEW) {
            orderedEasy = easy.copy(intervalDays = max(easy.intervalDays, orderedGood.intervalDays))
        }
        raw[ReviewRating.GOOD] = orderedGood.bounded()
        raw[ReviewRating.EASY] = orderedEasy.bounded()
        return raw
    }

    private fun calculateCandidate(
        card: ReviewCard,
        rating: ReviewRating,
        now: Long,
        desiredRetention: Double
    ): Candidate {
        val state = ReviewCardState.fromValue(card.state)
        val elapsed = elapsedDays(card, now)
        val ratingValue = rating.value.toDouble()
        val currentDifficulty = sanitizeDifficulty(card.difficulty)
        val difficulty = if (state == ReviewCardState.NEW) {
            initialDifficulty(ratingValue)
        } else {
            nextDifficulty(currentDifficulty, ratingValue)
        }
        val stability = when {
            state == ReviewCardState.NEW || card.stability <= 0.0 -> initialStability(rating)
            elapsed < 1.0 -> nextShortTermStability(card.stability, rating)
            rating == ReviewRating.FORGOT -> nextForgetStability(
                difficulty = currentDifficulty,
                stability = card.stability,
                retrievability = retrievability(elapsed, card.stability)
            )
            else -> nextRecallStability(
                difficulty = currentDifficulty,
                stability = card.stability,
                retrievability = retrievability(elapsed, card.stability),
                rating = rating
            )
        }.coerceIn(MIN_STABILITY, MAX_INTERVAL_DAYS)

        val intervalFromMemory = intervalFromStability(stability, desiredRetention)
        val (nextState, nextInterval) = when (state) {
            ReviewCardState.NEW -> when (rating) {
                ReviewRating.FORGOT -> ReviewCardState.LEARNING to ONE_MINUTE_IN_DAYS
                ReviewRating.HARD -> ReviewCardState.LEARNING to SIX_MINUTES_IN_DAYS
                ReviewRating.GOOD -> ReviewCardState.REVIEW to max(1.0, intervalFromMemory)
                ReviewRating.EASY -> ReviewCardState.REVIEW to max(4.0, intervalFromMemory)
            }
            ReviewCardState.LEARNING -> when (rating) {
                ReviewRating.FORGOT -> ReviewCardState.LEARNING to ONE_MINUTE_IN_DAYS
                ReviewRating.HARD -> ReviewCardState.LEARNING to SIX_MINUTES_IN_DAYS
                ReviewRating.GOOD -> ReviewCardState.REVIEW to max(1.0, intervalFromMemory)
                ReviewRating.EASY -> ReviewCardState.REVIEW to max(4.0, intervalFromMemory)
            }
            ReviewCardState.REVIEW -> when (rating) {
                ReviewRating.FORGOT -> ReviewCardState.RELEARNING to TEN_MINUTES_IN_DAYS
                ReviewRating.HARD,
                ReviewRating.GOOD,
                ReviewRating.EASY -> ReviewCardState.REVIEW to max(1.0, intervalFromMemory)
            }
            ReviewCardState.RELEARNING -> when (rating) {
                ReviewRating.FORGOT -> ReviewCardState.RELEARNING to TEN_MINUTES_IN_DAYS
                ReviewRating.HARD -> ReviewCardState.RELEARNING to THIRTY_MINUTES_IN_DAYS
                ReviewRating.GOOD -> ReviewCardState.REVIEW to max(1.0, intervalFromMemory)
                ReviewRating.EASY -> ReviewCardState.REVIEW to max(4.0, intervalFromMemory)
            }
        }
        return Candidate(
            state = nextState,
            intervalDays = nextInterval,
            stability = stability,
            difficulty = difficulty
        ).bounded()
    }

    private fun normalizeCard(card: ReviewCard): ReviewCard {
        if (card.schedulerVersion >= CURRENT_SCHEDULER_VERSION) {
            val state = ReviewCardState.fromValue(card.state)
            return card.copy(
                state = state.value,
                stability = when {
                    state == ReviewCardState.NEW -> card.stability.coerceAtLeast(0.0)
                    card.stability.isFinite() && card.stability > 0.0 -> card.stability
                    else -> max(MIN_STABILITY, sanitizeInterval(card.intervalDays))
                },
                difficulty = sanitizeDifficulty(card.difficulty)
            )
        }

        val legacyInterval = sanitizeInterval(card.intervalDays)
        val legacyState = when {
            card.reviewCount == 0 && legacyInterval == 0.0 -> ReviewCardState.NEW
            legacyInterval >= 1.0 -> ReviewCardState.REVIEW
            card.lapseCount > 0 -> ReviewCardState.RELEARNING
            else -> ReviewCardState.LEARNING
        }
        val ease = sanitizeEase(card.easeFactor)
        val migratedDifficulty = (
            MAX_DIFFICULTY - ((ease - 1.3) / (3.0 - 1.3)) * 7.0
        ).coerceIn(MIN_DIFFICULTY, MAX_DIFFICULTY)
        return card.copy(
            state = legacyState.value,
            stability = if (legacyState == ReviewCardState.NEW) {
                0.0
            } else {
                max(MIN_STABILITY, legacyInterval)
            },
            difficulty = migratedDifficulty,
            schedulerVersion = CURRENT_SCHEDULER_VERSION
        )
    }

    private fun initialStability(rating: ReviewRating): Double = w[rating.value - 1]

    private fun initialDifficulty(rating: Double): Double {
        return initialDifficultyRaw(rating)
            .coerceIn(MIN_DIFFICULTY, MAX_DIFFICULTY)
    }

    private fun initialDifficultyRaw(rating: Double): Double {
        return w[4] - exp(w[5] * (rating - 1.0)) + 1.0
    }

    private fun nextDifficulty(current: Double, rating: Double): Double {
        val difficulty = sanitizeDifficulty(current)
        val delta = -w[6] * (rating - 3.0)
        val damped = delta * (MAX_DIFFICULTY - difficulty) / 9.0
        val adjusted = difficulty + damped
        val meanReverted = w[7] * initialDifficultyRaw(4.0) + (1.0 - w[7]) * adjusted
        return meanReverted.coerceIn(MIN_DIFFICULTY, MAX_DIFFICULTY)
    }

    private fun nextRecallStability(
        difficulty: Double,
        stability: Double,
        retrievability: Double,
        rating: ReviewRating
    ): Double {
        val hardPenalty = if (rating == ReviewRating.HARD) w[15] else 1.0
        val easyBonus = if (rating == ReviewRating.EASY) w[16] else 1.0
        val growth = exp(w[8]) *
            (11.0 - difficulty) *
            stability.pow(-w[9]) *
            (exp(w[10] * (1.0 - retrievability)) - 1.0) *
            hardPenalty * easyBonus
        return stability * (growth + 1.0)
    }

    private fun nextForgetStability(
        difficulty: Double,
        stability: Double,
        retrievability: Double
    ): Double {
        val forgotten = w[11] *
            difficulty.pow(-w[12]) *
            ((stability + 1.0).pow(w[13]) - 1.0) *
            exp(w[14] * (1.0 - retrievability))
        val shortTermLimit = stability / exp(w[17] * w[18])
        return min(shortTermLimit, forgotten.coerceAtLeast(MIN_STABILITY))
    }

    private fun nextShortTermStability(stability: Double, rating: ReviewRating): Double {
        val next = stability *
            exp(w[17] * (rating.value - 3.0 + w[18])) *
            stability.pow(-w[19])
        return if (rating == ReviewRating.GOOD || rating == ReviewRating.EASY) {
            max(stability, next)
        } else {
            next
        }
    }

    private fun retrievability(elapsedDays: Double, stability: Double): Double {
        val safeStability = stability.coerceAtLeast(MIN_STABILITY)
        val factor = 0.9.pow(-1.0 / w[20]) - 1.0
        return (1.0 + factor * elapsedDays.coerceAtLeast(0.0) / safeStability)
            .pow(-w[20])
            .coerceIn(0.0, 1.0)
    }

    private fun intervalFromStability(stability: Double, desiredRetention: Double): Double {
        val retention = desiredRetention.coerceIn(0.7, 0.99)
        val factor = 0.9.pow(-1.0 / w[20]) - 1.0
        val raw = stability / factor * (retention.pow(-1.0 / w[20]) - 1.0)
        return raw.roundToLong().toDouble().coerceAtLeast(1.0)
    }

    fun pickNewCards(
        allQuizIds: List<Int>,
        existingCardQuizIds: Collection<Int>,
        limit: Int
    ): List<Int> {
        if (limit <= 0) return emptyList()
        val existing = existingCardQuizIds.toSet()
        return allQuizIds.filterNot { it in existing }.sorted().take(limit)
    }

    fun buildReviewSession(
        dueCards: List<ReviewCard>,
        newQuizIds: List<Int>,
        newCardLimit: Int
    ): List<Int> {
        return (dueCards.sortedBy { it.dueAt }.map { it.quizId } + newQuizIds.take(newCardLimit))
            .distinct()
    }

    fun formatReviewInterval(intervalDays: Double): String {
        val minutes = intervalDays * 24 * 60
        return when {
            minutes < 1.0 -> "<1分钟"
            minutes < 60.0 -> "${minutes.roundToLong().coerceAtLeast(1)}分钟"
            minutes < 1_440.0 -> {
                val hours = minutes / 60
                if (hours < 2) "1小时" else "${hours.toInt()}小时"
            }
            intervalDays < 7.0 -> "${intervalDays.toInt().coerceAtLeast(1)}天"
            intervalDays < 30.0 -> "${(intervalDays / 7).toInt().coerceAtLeast(1)}周"
            intervalDays < 365.0 -> "${(intervalDays / 30).toInt().coerceAtLeast(1)}个月"
            else -> "${(intervalDays / 365).toInt().coerceAtLeast(1)}年"
        }
    }

    private fun intervalToMillis(intervalDays: Double): Long {
        return (sanitizeInterval(intervalDays).coerceAtMost(MAX_INTERVAL_DAYS) * MILLIS_PER_DAY)
            .roundToLong()
            .coerceAtLeast(60_000L)
    }

    private fun addIntervalSafely(now: Long, intervalDays: Double): Long {
        val intervalMillis = intervalToMillis(intervalDays)
        return if (now > Long.MAX_VALUE - intervalMillis) Long.MAX_VALUE else now + intervalMillis
    }

    private fun sanitizeInterval(value: Double): Double {
        return value.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
    }

    private fun sanitizeDifficulty(value: Double): Double {
        return value.takeIf { it.isFinite() && it > 0.0 }
            ?.coerceIn(MIN_DIFFICULTY, MAX_DIFFICULTY)
            ?: initialDifficulty(ReviewRating.GOOD.value.toDouble())
    }

    private fun sanitizeEase(value: Double): Double {
        return value.takeIf { it.isFinite() && it > 0.0 }
            ?.coerceIn(1.3, 3.0)
            ?: DEFAULT_EASE_FACTOR
    }

    private data class Candidate(
        val state: ReviewCardState,
        val intervalDays: Double,
        val stability: Double,
        val difficulty: Double
    ) {
        fun bounded(): Candidate = copy(
            intervalDays = intervalDays.coerceIn(ONE_MINUTE_IN_DAYS, MAX_INTERVAL_DAYS),
            stability = stability.coerceIn(MIN_STABILITY, MAX_INTERVAL_DAYS),
            difficulty = difficulty.coerceIn(MIN_DIFFICULTY, MAX_DIFFICULTY)
        )
    }
}
