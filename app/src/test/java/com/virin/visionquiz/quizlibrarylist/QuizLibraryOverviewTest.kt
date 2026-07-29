package com.virin.visionquiz.quizlibrarylist

import com.virin.visionquiz.dao.QuizLibrary
import com.virin.visionquiz.dao.QuizAnswerRecord
import org.junit.Assert.assertEquals
import org.junit.Test

class QuizLibraryOverviewTest {

    @Test
    fun buildQuizLibraryOverview_sumsRealLibraryAndReviewData() {
        val items = listOf(
            QuizLibraryWithReviewCount(
                library = QuizLibrary(id = 1, name = "英语", quizCount = 120),
                reviewCount = 8,
                todayLearnedCount = 3
            ),
            QuizLibraryWithReviewCount(
                library = QuizLibrary(id = 2, name = "网络", quizCount = 80),
                reviewCount = 0,
                todayLearnedCount = 4
            ),
            QuizLibraryWithReviewCount(
                library = QuizLibrary(id = 3, name = "教资", quizCount = 200),
                reviewCount = 12,
                todayLearnedCount = 5
            )
        )

        assertEquals(
            QuizLibraryOverview(
                libraryCount = 3,
                totalQuestionCount = 400,
                dueReviewCount = 20,
                dueLibraryCount = 2,
                todayLearnedCount = 12
            ),
            buildQuizLibraryOverview(items)
        )
    }

    @Test
    fun buildQuizLibraryOverview_handlesEmptyLibraryList() {
        assertEquals(
            QuizLibraryOverview(
                libraryCount = 0,
                totalQuestionCount = 0,
                dueReviewCount = 0,
                dueLibraryCount = 0,
                todayLearnedCount = 0
            ),
            buildQuizLibraryOverview(emptyList())
        )
    }

    @Test
    fun calculateMasteryPercent_usesLatestAnswerForEachQuestion() {
        val records = listOf(
            answerRecord(id = 1, quizId = 1, isCorrect = true, answeredAt = 100),
            answerRecord(id = 2, quizId = 1, isCorrect = false, answeredAt = 200),
            answerRecord(id = 3, quizId = 2, isCorrect = true, answeredAt = 150),
            answerRecord(id = 4, quizId = 3, isCorrect = true, answeredAt = 180)
        )

        assertEquals(50, calculateMasteryPercent(records, totalQuestionCount = 4))
    }

    @Test
    fun calculateMasteryPercent_handlesEmptyAndZeroQuestionLibraries() {
        assertEquals(0, calculateMasteryPercent(emptyList(), totalQuestionCount = 10))
        assertEquals(
            0,
            calculateMasteryPercent(
                listOf(answerRecord(id = 1, quizId = 1, isCorrect = true, answeredAt = 100)),
                totalQuestionCount = 0
            )
        )
    }

    @Test
    fun calculateTodayLearnedCount_countsOnlyRecordsFromToday() {
        val todayStart = 1_000L
        val records = listOf(
            answerRecord(id = 1, quizId = 1, isCorrect = true, answeredAt = 999),
            answerRecord(id = 2, quizId = 1, isCorrect = false, answeredAt = 1_000),
            answerRecord(id = 3, quizId = 2, isCorrect = true, answeredAt = 1_500)
        )

        assertEquals(2, calculateTodayLearnedCount(records, todayStart))
    }

    private fun answerRecord(
        id: Int,
        quizId: Int,
        isCorrect: Boolean,
        answeredAt: Long
    ) = QuizAnswerRecord(
        id = id,
        quizId = quizId,
        libraryId = 1,
        mode = "test",
        selectedAnswer = setOf(0),
        isCorrect = isCorrect,
        answeredAt = answeredAt
    )
}
