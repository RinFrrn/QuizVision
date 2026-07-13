package com.virin.visionquiz.quizstudy

import com.virin.visionquiz.dao.ReviewCard
import com.virin.visionquiz.dao.ReviewRating
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

object SpacedRepetitionScheduler {
    private const val MIN_EASE_FACTOR = 1.3
    private const val MAX_EASE_FACTOR = 3.0
    private const val DEFAULT_EASE_FACTOR = 2.5
    private const val MAX_INTERVAL_DAYS = 36_500.0
    private const val MILLIS_PER_DAY = 86_400_000L
    private const val ONE_MINUTE_IN_DAYS = 1.0 / 1_440.0
    private const val SIX_MINUTES_IN_DAYS = 6.0 / 1_440.0

    fun schedule(
        card: ReviewCard,
        rating: ReviewRating,
        now: Long = System.currentTimeMillis()
    ): ReviewCard {
        val currentInterval = sanitizeInterval(card.intervalDays)
        val currentEase = sanitizeEase(card.easeFactor)
        val isGraduated = currentInterval >= 1.0
        val (nextInterval, nextEase, nextLapseCount) = when (rating) {
            ReviewRating.FORGOT -> Triple(
                ONE_MINUTE_IN_DAYS,
                if (isGraduated) max(MIN_EASE_FACTOR, currentEase - 0.2) else currentEase,
                if (isGraduated) card.lapseCount + 1 else card.lapseCount
            )
            ReviewRating.HARD -> Triple(
                if (currentInterval == 0.0) SIX_MINUTES_IN_DAYS
                else max(1.0, currentInterval * 1.2),
                if (isGraduated) max(MIN_EASE_FACTOR, currentEase - 0.15) else currentEase,
                card.lapseCount
            )
            ReviewRating.GOOD -> Triple(
                when {
                    currentInterval == 0.0 -> 1.0
                    currentInterval < 1.0 -> 1.0
                    else -> currentInterval * currentEase
                },
                currentEase,
                card.lapseCount
            )
            ReviewRating.EASY -> Triple(
                if (currentInterval == 0.0) 4.0
                else currentInterval * currentEase * 1.3,
                min(MAX_EASE_FACTOR, currentEase + 0.15),
                card.lapseCount
            )
        }
        val boundedNextInterval = nextInterval.coerceIn(ONE_MINUTE_IN_DAYS, MAX_INTERVAL_DAYS)

        return card.copy(
            dueAt = addIntervalSafely(now, boundedNextInterval),
            intervalDays = boundedNextInterval,
            easeFactor = nextEase,
            reviewCount = card.reviewCount + 1,
            lapseCount = nextLapseCount,
            lastReviewedAt = now
        )
    }

    fun pickNewCards(
        allQuizIds: List<Int>,
        existingCardQuizIds: Collection<Int>,
        limit: Int
    ): List<Int> {
        if (limit <= 0) return emptyList()
        val existing = existingCardQuizIds.toSet()
        return allQuizIds
            .filterNot { it in existing }
            .sorted()
            .take(limit)
    }

    fun buildReviewSession(
        dueCards: List<ReviewCard>,
        newQuizIds: List<Int>,
        newCardLimit: Int
    ): List<Int> {
        return (dueCards.sortedBy { it.dueAt }.map { it.quizId } + newQuizIds.take(newCardLimit))
            .distinct()
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

    private fun sanitizeInterval(intervalDays: Double): Double {
        return intervalDays.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
    }

    private fun sanitizeEase(easeFactor: Double): Double {
        return easeFactor
            .takeIf { it.isFinite() && it > 0.0 }
            ?.coerceIn(MIN_EASE_FACTOR, MAX_EASE_FACTOR)
            ?: DEFAULT_EASE_FACTOR
    }

    /**
     * Preview the next interval days for each rating, given a card's current state.
     * Returns a map of ReviewRating -> interval in days.
     */
    fun previewNextIntervals(card: ReviewCard): Map<ReviewRating, Double> {
        val currentInterval = sanitizeInterval(card.intervalDays)
        val currentEase = sanitizeEase(card.easeFactor)
        return mapOf(
            ReviewRating.FORGOT to ONE_MINUTE_IN_DAYS,
            ReviewRating.HARD to if (currentInterval == 0.0) SIX_MINUTES_IN_DAYS
                else max(1.0, currentInterval * 1.2),
            ReviewRating.GOOD to when {
                currentInterval == 0.0 -> 1.0
                currentInterval < 1.0 -> 1.0
                else -> currentInterval * currentEase
            },
            ReviewRating.EASY to if (currentInterval == 0.0) 4.0
                else currentInterval * currentEase * 1.3
        )
    }

    /**
     * Format an interval in days into a concise human-readable Chinese string.
     * Examples: "1分钟", "6分钟", "1小时", "3小时", "1天", "4天", "2周", "3个月", "1年"
     */
    fun formatReviewInterval(intervalDays: Double): String {
        val minutes = intervalDays * 24 * 60
        return when {
            minutes < 1.0 -> "<1分钟"
            minutes < 60.0 -> "${minutes.toInt()}分钟"
            minutes < 1_440.0 -> {
                val hours = minutes / 60
                if (hours < 2) "1小时" else "${hours.toInt()}小时"
            }
            intervalDays < 7.0 -> {
                val days = intervalDays.toInt()
                if (days < 1) "1天" else "${days}天"
            }
            intervalDays < 30.0 -> {
                val weeks = (intervalDays / 7).toInt()
                if (weeks < 1) "1周" else "${weeks}周"
            }
            intervalDays < 365.0 -> {
                val months = (intervalDays / 30).toInt()
                if (months < 1) "1个月" else "${months}个月"
            }
            else -> {
                val years = (intervalDays / 365).toInt()
                if (years < 1) "1年" else "${years}年"
            }
        }
    }

}
