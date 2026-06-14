package com.virin.visionquiz.quizstudy

import com.virin.visionquiz.dao.Quiz
import com.virin.visionquiz.dao.QuizUiType
import com.virin.visionquiz.dao.ReviewCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewStatsTest {
    @Test
    fun buildReviewStatsCountsDueReviewedCardsAndLapses() {
        val stats = buildReviewStats(
            cards = listOf(
                card(quizId = 1, dueAt = NOW - 1, lastReviewedAt = TODAY_START + 1, lapseCount = 2),
                card(quizId = 2, dueAt = NOW + 1, lastReviewedAt = TODAY_START - 1, lapseCount = 1),
                card(quizId = 3, dueAt = NOW, lastReviewedAt = null, lapseCount = 3)
            ),
            now = NOW,
            todayStart = TODAY_START
        )

        assertEquals(2, stats.dueToday)
        assertEquals(1, stats.reviewedToday)
        assertEquals(3, stats.totalCards)
        assertEquals(6, stats.totalLapses)
    }

    @Test
    fun buildReviewStatsIncludesCardsReviewedExactlyAtTodayStart() {
        val stats = buildReviewStats(
            cards = listOf(
                card(quizId = 1, dueAt = NOW + 10_000L, lastReviewedAt = TODAY_START)
            ),
            now = NOW,
            todayStart = TODAY_START
        )

        assertEquals(0, stats.dueToday)
        assertEquals(1, stats.reviewedToday)
        assertEquals(1, stats.totalCards)
        assertEquals(0, stats.totalLapses)
    }

    @Test
    fun buildReviewEntryStateCountsSupportedNewLearningCardsUpToLimit() {
        val state = buildReviewEntryState(
            quizzes = listOf(
                quiz(id = 1, type = QuizUiType.SINGLE_CHOICE),
                quiz(id = 2, type = QuizUiType.MULTIPLE_CHOICE, isMultipleChoice = true, answer = setOf(0, 1)),
                quiz(id = 3, type = QuizUiType.JUDGEMENT, options = listOf("正确", "错误")),
                quiz(id = 4, type = QuizUiType.SUBJECTIVE, options = listOf("A")),
                quiz(id = 5, type = QuizUiType.SINGLE_CHOICE)
            ),
            reviewQuizIds = listOf(2),
            reviewStats = ReviewStats(dueToday = 3),
            newCardLimit = 2
        )

        assertEquals("开始学习", state.title)
        assertEquals("待复习 3 题 · 待学习 2 题", state.description)
        assertEquals(3, state.dueReviewCount)
        assertEquals(2, state.newLearningCount)
        assertTrue(state.hasPendingWork)
    }

    @Test
    fun buildReviewEntryStateReturnsZeroNewLearningWhenLimitIsZero() {
        val state = buildReviewEntryState(
            quizzes = listOf(quiz(id = 1, type = QuizUiType.SINGLE_CHOICE)),
            reviewQuizIds = emptyList(),
            reviewStats = ReviewStats(),
            newCardLimit = 0
        )

        assertEquals("待复习 0 题 · 待学习 0 题", state.description)
        assertEquals(0, state.newLearningCount)
        assertFalse(state.hasPendingWork)
    }

    private fun card(
        quizId: Int,
        dueAt: Long,
        lastReviewedAt: Long?,
        lapseCount: Int = 0
    ): ReviewCard {
        return ReviewCard(
            quizId = quizId,
            libraryId = 7,
            dueAt = dueAt,
            lapseCount = lapseCount,
            lastReviewedAt = lastReviewedAt
        )
    }

    private fun quiz(
        id: Int,
        type: QuizUiType,
        options: List<String> = listOf("A", "B", "C"),
        answer: Set<Int> = setOf(0),
        isMultipleChoice: Boolean = false
    ): Quiz {
        return Quiz(
            id = id,
            prompt = "Question $id",
            options = options,
            answer = answer,
            isMultipleChoice = isMultipleChoice,
            questionType = type.label,
            libraryId = 7
        )
    }

    private companion object {
        private const val TODAY_START = 1_700_000_000_000L
        private const val NOW = TODAY_START + 12 * 60 * 60 * 1_000L
    }
}
