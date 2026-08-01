package com.virin.visionquiz.quizstudy

import com.virin.visionquiz.dao.QuizAnswerRecord
import org.junit.Assert.assertEquals
import org.junit.Test

class QuizLibraryAnswerStatsTest {

    @Test
    fun buildAnswerStats_separatesAttemptAccuracyFromQuestionMastery() {
        val records = listOf(
            answerRecord(id = 1, quizId = 1, isCorrect = true, answeredAt = 100),
            answerRecord(id = 2, quizId = 1, isCorrect = false, answeredAt = 200),
            answerRecord(id = 3, quizId = 2, isCorrect = true, answeredAt = 150)
        )

        val stats = buildAnswerStats(
            records = records,
            todayStart = 0,
            totalQuestionCount = 4
        )

        assertEquals(67, stats.accuracyPercent)
        assertEquals(25, stats.masteryPercent)
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
