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
    private const val MILLIS_PER_DAY = 86_400_000L
    private const val ONE_MINUTE_IN_DAYS = 1.0 / 1_440.0
    private const val SIX_MINUTES_IN_DAYS = 6.0 / 1_440.0

    fun schedule(
        card: ReviewCard,
        rating: ReviewRating,
        now: Long = System.currentTimeMillis()
    ): ReviewCard {
        val currentInterval = card.intervalDays
        val currentEase = card.easeFactor.takeIf { it > 0.0 } ?: DEFAULT_EASE_FACTOR
        val (nextInterval, nextEase, nextLapseCount) = when (rating) {
            ReviewRating.FORGOT -> Triple(
                ONE_MINUTE_IN_DAYS,
                max(MIN_EASE_FACTOR, currentEase - 0.2),
                card.lapseCount + 1
            )
            ReviewRating.HARD -> Triple(
                if (currentInterval == 0.0) SIX_MINUTES_IN_DAYS
                else max(1.0, currentInterval * 1.2),
                max(MIN_EASE_FACTOR, currentEase - 0.15),
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

        return card.copy(
            dueAt = now + intervalToMillis(nextInterval),
            intervalDays = nextInterval,
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
        return (intervalDays * MILLIS_PER_DAY)
            .roundToLong()
            .coerceAtLeast(60_000L)
    }
}
