package com.virin.visionquiz.quizlist

import com.virin.visionquiz.dao.Quiz
import com.virin.visionquiz.dao.QuizUiType
import org.junit.Assert.assertEquals
import org.junit.Test

class QuizListStatsTest {

    @Test
    fun buildQuizTypeStats_countsEachTypeWithinSearchResults() {
        val searchResults = listOf(
            quiz(1, QuizUiType.SINGLE_CHOICE),
            quiz(2, QuizUiType.SINGLE_CHOICE),
            quiz(3, QuizUiType.MULTIPLE_CHOICE),
            quiz(4, QuizUiType.JUDGEMENT)
        )

        assertEquals(
            QuizTypeStats(total = 4, singleChoice = 2, multipleChoice = 1, judgement = 1),
            buildQuizTypeStats(searchResults)
        )
    }

    @Test
    fun formatFilteredCount_keepsFilteredAndTotalCounts() {
        assertEquals("5(120)", formatFilteredCount(filtered = 5, total = 120))
        assertEquals("0(120)", formatFilteredCount(filtered = 0, total = 120))
    }

    @Test
    fun formatFilteredCount_showsOnlyTotalWhenSearchIsInactive() {
        assertEquals("120", formatFilteredCount(filtered = null, total = 120))
    }

    private fun quiz(id: Int, type: QuizUiType): Quiz {
        val options = when (type) {
            QuizUiType.JUDGEMENT -> listOf("正确", "错误")
            else -> listOf("A", "B", "C")
        }
        val answer = when (type) {
            QuizUiType.MULTIPLE_CHOICE -> setOf(0, 1)
            else -> setOf(0)
        }
        return Quiz(
            id = id,
            prompt = "题目$id",
            options = options,
            answer = answer,
            isMultipleChoice = type == QuizUiType.MULTIPLE_CHOICE,
            questionType = type.label,
            libraryId = 1
        )
    }
}
