package com.virin.visionquiz.quizstudy

import com.virin.visionquiz.dao.ReviewCard
import com.virin.visionquiz.dao.ReviewRating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpacedRepetitionSchedulerTest {
    @Test
    fun newGoodCardSchedulesForOneDay() {
        val scheduled = SpacedRepetitionScheduler.schedule(
            card = newCard(),
            rating = ReviewRating.GOOD,
            now = NOW
        )

        assertEquals(1.0, scheduled.intervalDays, 0.0001)
        assertEquals(NOW + DAY_MS, scheduled.dueAt)
        assertEquals(2.5, scheduled.easeFactor, 0.0001)
        assertEquals(1, scheduled.reviewCount)
    }

    @Test
    fun consecutiveGoodCardsGrowByEaseFactor() {
        val first = SpacedRepetitionScheduler.schedule(newCard(), ReviewRating.GOOD, NOW)
        val second = SpacedRepetitionScheduler.schedule(first, ReviewRating.GOOD, NOW)
        val third = SpacedRepetitionScheduler.schedule(second, ReviewRating.GOOD, NOW)

        assertEquals(1.0, first.intervalDays, 0.0001)
        assertEquals(2.5, second.intervalDays, 0.0001)
        assertEquals(6.25, third.intervalDays, 0.0001)
    }

    @Test
    fun forgotResetsToLearningIntervalAndLowersEase() {
        val scheduled = SpacedRepetitionScheduler.schedule(
            card = newCard(intervalDays = 5.0, easeFactor = 1.35, lapseCount = 2),
            rating = ReviewRating.FORGOT,
            now = NOW
        )

        assertEquals(1.0 / 1440.0, scheduled.intervalDays, 0.0001)
        assertEquals(NOW + 60_000L, scheduled.dueAt)
        assertEquals(1.3, scheduled.easeFactor, 0.0001)
        assertEquals(3, scheduled.lapseCount)
    }

    @Test
    fun easyIncreasesIntervalAndEaseFactor() {
        val scheduled = SpacedRepetitionScheduler.schedule(
            card = newCard(intervalDays = 2.0, easeFactor = 2.5),
            rating = ReviewRating.EASY,
            now = NOW
        )

        assertEquals(6.5, scheduled.intervalDays, 0.0001)
        assertEquals(2.65, scheduled.easeFactor, 0.0001)
    }

    @Test
    fun hardUsesShortLearningIntervalForNewCards() {
        val scheduled = SpacedRepetitionScheduler.schedule(
            card = newCard(),
            rating = ReviewRating.HARD,
            now = NOW
        )

        assertEquals(6.0 / 1440.0, scheduled.intervalDays, 0.0001)
        assertEquals(NOW + 360_000L, scheduled.dueAt)
        assertEquals(2.35, scheduled.easeFactor, 0.0001)
    }

    @Test
    fun reviewSessionKeepsDueCardsBeforeNewCards() {
        val ids = SpacedRepetitionScheduler.buildReviewSession(
            dueCards = listOf(
                newCard(quizId = 3, dueAt = NOW + 2),
                newCard(quizId = 1, dueAt = NOW + 1)
            ),
            newQuizIds = listOf(4, 5, 6),
            newCardLimit = 2
        )

        assertEquals(listOf(1, 3, 4, 5), ids)
        assertTrue(6 !in ids)
    }

    @Test
    fun reschedulingFromSameBaselineDoesNotDoubleCountReview() {
        val baseline = newCard(intervalDays = 1.0, easeFactor = 2.5, reviewCount = 4)

        val defaultScheduled = SpacedRepetitionScheduler.schedule(
            card = baseline,
            rating = ReviewRating.GOOD,
            now = NOW
        )
        val correctedScheduled = SpacedRepetitionScheduler.schedule(
            card = baseline,
            rating = ReviewRating.EASY,
            now = NOW
        )

        assertEquals(5, defaultScheduled.reviewCount)
        assertEquals(5, correctedScheduled.reviewCount)
    }

    private fun newCard(
        quizId: Int = 1,
        dueAt: Long = NOW,
        intervalDays: Double = 0.0,
        easeFactor: Double = 2.5,
        lapseCount: Int = 0,
        reviewCount: Int = 0
    ): ReviewCard {
        return ReviewCard(
            quizId = quizId,
            libraryId = 7,
            dueAt = dueAt,
            intervalDays = intervalDays,
            easeFactor = easeFactor,
            lapseCount = lapseCount,
            reviewCount = reviewCount
        )
    }

    private companion object {
        private const val NOW = 1_700_000_000_000L
        private const val DAY_MS = 86_400_000L
    }
}
