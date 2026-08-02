package com.virin.visionquiz.quizstudy

import com.virin.visionquiz.dao.ReviewCard
import com.virin.visionquiz.dao.ReviewCardState
import com.virin.visionquiz.dao.ReviewRating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpacedRepetitionSchedulerTest {
    @Test
    fun newGoodCardUsesFsrs6InitialMemoryState() {
        val scheduled = SpacedRepetitionScheduler.schedule(newCard(), ReviewRating.GOOD, NOW)

        assertEquals(2.0, scheduled.intervalDays, 0.0001)
        assertEquals(NOW + 2 * DAY_MS, scheduled.dueAt)
        assertEquals(2.3065, scheduled.stability, 0.0000001)
        assertEquals(2.118103970459016, scheduled.difficulty, 0.0000001)
        assertEquals(ReviewCardState.REVIEW.value, scheduled.state)
        assertEquals(SpacedRepetitionScheduler.CURRENT_SCHEDULER_VERSION, scheduled.schedulerVersion)
        assertEquals(1, scheduled.reviewCount)
    }

    @Test
    fun reviewAtDueDateMatchesOfficialFsrs6ReferenceVector() {
        val first = SpacedRepetitionScheduler.schedule(newCard(), ReviewRating.GOOD, NOW)

        val again = SpacedRepetitionScheduler.schedule(first, ReviewRating.FORGOT, first.dueAt)
        val hard = SpacedRepetitionScheduler.schedule(first, ReviewRating.HARD, first.dueAt)
        val good = SpacedRepetitionScheduler.schedule(first, ReviewRating.GOOD, first.dueAt)
        val easy = SpacedRepetitionScheduler.schedule(first, ReviewRating.EASY, first.dueAt)

        assertEquals(0.6075801062519337, again.stability, 0.0000001)
        assertEquals(10.0 / 1_440.0, again.intervalDays, 0.0000001)
        assertEquals(ReviewCardState.RELEARNING.value, again.state)
        assertEquals(7.513320366762569, hard.stability, 0.0000001)
        assertEquals(8.0, hard.intervalDays, 0.0001)
        assertEquals(10.964332335820698, good.stability, 0.0000001)
        assertEquals(11.0, good.intervalDays, 0.0001)
        assertEquals(18.52175418175859, easy.stability, 0.0000001)
        assertEquals(19.0, easy.intervalDays, 0.0001)
    }

    @Test
    fun forgotReviewCardEntersRelearningAndCountsLapse() {
        val scheduled = SpacedRepetitionScheduler.schedule(
            card = reviewCard(intervalDays = 5.0, stability = 5.0, lapseCount = 2),
            rating = ReviewRating.FORGOT,
            now = NOW
        )

        assertEquals(10.0 / 1_440.0, scheduled.intervalDays, 0.0001)
        assertEquals(NOW + 600_000L, scheduled.dueAt)
        assertEquals(ReviewCardState.RELEARNING.value, scheduled.state)
        assertEquals(3, scheduled.lapseCount)
    }

    @Test
    fun learningEasyGraduatesToAtLeastFourDays() {
        val learningCard = SpacedRepetitionScheduler.schedule(
            card = newCard(),
            rating = ReviewRating.FORGOT,
            now = NOW
        )

        val scheduled = SpacedRepetitionScheduler.schedule(
            card = learningCard,
            rating = ReviewRating.EASY,
            now = NOW + 60_000L
        )

        assertEquals(4.0, scheduled.intervalDays, 0.0001)
        assertEquals(ReviewCardState.REVIEW.value, scheduled.state)
    }

    @Test
    fun learningPreviewMatchesScheduleAndIsMonotonic() {
        val learningCard = ReviewCard(
            quizId = 1,
            libraryId = 7,
            dueAt = NOW,
            intervalDays = 1.0 / 1_440.0,
            reviewCount = 1,
            lastReviewedAt = NOW - 60_000L,
            state = ReviewCardState.LEARNING.value,
            stability = 0.212,
            difficulty = 6.4133,
            schedulerVersion = SpacedRepetitionScheduler.CURRENT_SCHEDULER_VERSION
        )
        val preview = SpacedRepetitionScheduler.previewNextIntervals(learningCard, NOW)
        val intervals = ReviewRating.values().map { rating ->
            val scheduled = SpacedRepetitionScheduler.schedule(learningCard, rating, NOW)
            assertEquals(scheduled.intervalDays, preview.getValue(rating), 0.0000001)
            scheduled.intervalDays
        }

        assertEquals(1.0 / 1_440.0, intervals[0], 0.0001)
        assertEquals(6.0 / 1_440.0, intervals[1], 0.0001)
        assertTrue(intervals[2] >= 1.0)
        assertTrue(intervals[3] >= 4.0)
        intervals.zipWithNext().forEach { (shorter, longer) -> assertTrue(shorter <= longer) }
    }

    @Test
    fun hardUsesShortLearningIntervalForNewCards() {
        val scheduled = SpacedRepetitionScheduler.schedule(newCard(), ReviewRating.HARD, NOW)

        assertEquals(6.0 / 1_440.0, scheduled.intervalDays, 0.0001)
        assertEquals(NOW + 360_000L, scheduled.dueAt)
        assertEquals(ReviewCardState.LEARNING.value, scheduled.state)
        assertEquals(1.2931, scheduled.stability, 0.0000001)
        assertEquals(0, scheduled.lapseCount)
    }

    @Test
    fun forgotDuringInitialLearningDoesNotCountAsMatureLapse() {
        val scheduled = SpacedRepetitionScheduler.schedule(newCard(), ReviewRating.FORGOT, NOW)

        assertEquals(0, scheduled.lapseCount)
        assertEquals(NOW + 60_000L, scheduled.dueAt)
        assertEquals(ReviewCardState.LEARNING.value, scheduled.state)
    }

    @Test
    fun overdueRecallProducesLongerIntervalThanOnTimeRecall() {
        val card = reviewCard(intervalDays = 5.0, stability = 5.0, lastReviewedAt = NOW)
        val onTime = SpacedRepetitionScheduler.schedule(card, ReviewRating.GOOD, NOW + 5 * DAY_MS)
        val overdue = SpacedRepetitionScheduler.schedule(card, ReviewRating.GOOD, NOW + 15 * DAY_MS)

        assertTrue(overdue.intervalDays > onTime.intervalDays)
        assertTrue(overdue.stability > onTime.stability)
    }

    @Test
    fun invalidAndHugeIntervalsAreSanitizedWithoutDueDateOverflow() {
        val invalid = SpacedRepetitionScheduler.schedule(
            card = newCard(intervalDays = Double.NaN, easeFactor = Double.POSITIVE_INFINITY),
            rating = ReviewRating.GOOD,
            now = NOW
        )
        val huge = SpacedRepetitionScheduler.schedule(
            card = newCard(intervalDays = Double.MAX_VALUE, easeFactor = 3.0, reviewCount = 3),
            rating = ReviewRating.EASY,
            now = Long.MAX_VALUE - 1_000L
        )

        assertEquals(2.0, invalid.intervalDays, 0.0001)
        assertEquals(2.5, invalid.easeFactor, 0.0001)
        assertEquals(36_500.0, huge.intervalDays, 0.0001)
        assertEquals(Long.MAX_VALUE, huge.dueAt)
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
        val baseline = reviewCard(intervalDays = 1.0, stability = 1.0, reviewCount = 4)

        val defaultScheduled = SpacedRepetitionScheduler.schedule(baseline, ReviewRating.GOOD, NOW)
        val correctedScheduled = SpacedRepetitionScheduler.schedule(baseline, ReviewRating.EASY, NOW)

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

    private fun reviewCard(
        intervalDays: Double,
        stability: Double,
        lapseCount: Int = 0,
        reviewCount: Int = 1,
        lastReviewedAt: Long? = NOW - (intervalDays * DAY_MS).toLong()
    ): ReviewCard {
        return ReviewCard(
            quizId = 1,
            libraryId = 7,
            dueAt = NOW,
            intervalDays = intervalDays,
            lapseCount = lapseCount,
            reviewCount = reviewCount,
            lastReviewedAt = lastReviewedAt,
            state = ReviewCardState.REVIEW.value,
            stability = stability,
            difficulty = 2.118103970459016,
            schedulerVersion = SpacedRepetitionScheduler.CURRENT_SCHEDULER_VERSION
        )
    }

    private companion object {
        private const val NOW = 1_700_000_000_000L
        private const val DAY_MS = 86_400_000L
    }
}
