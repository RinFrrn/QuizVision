package com.virin.visionquiz.util

import com.virin.visionquiz.dao.Quiz
import com.virin.visionquiz.dao.QuizUiType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnswerDisplayTest {

    @Test
    fun selectAllAnswerHint_returnsHintForMultipleChoiceSelectingEveryOption() {
        val quiz = quiz(
            options = listOf("A", "B", "C"),
            answer = setOf(0, 1, 2),
            isMultipleChoice = true,
            questionType = QuizUiType.MULTIPLE_CHOICE.label
        )

        assertEquals("全选", quiz.selectAllAnswerHint())
    }

    @Test
    fun selectAllAnswerHint_returnsNullForPartialMultipleChoiceAnswer() {
        val quiz = quiz(
            options = listOf("A", "B", "C"),
            answer = setOf(0, 2),
            isMultipleChoice = true,
            questionType = QuizUiType.MULTIPLE_CHOICE.label
        )

        assertNull(quiz.selectAllAnswerHint())
    }

    @Test
    fun selectAllAnswerHint_returnsNullForNonMultipleChoiceSelectingEveryOption() {
        val quiz = quiz(
            prompt = "____和____",
            options = listOf("答案一", "答案二"),
            answer = setOf(0, 1),
            isMultipleChoice = true,
            questionType = QuizUiType.FILL_BLANK.label
        )

        assertNull(quiz.selectAllAnswerHint())
    }

    private fun quiz(
        prompt: String = "测试题",
        options: List<String>,
        answer: Set<Int>,
        isMultipleChoice: Boolean,
        questionType: String
    ) = Quiz(
        prompt = prompt,
        options = options,
        answer = answer,
        isMultipleChoice = isMultipleChoice,
        questionType = questionType,
        libraryId = 1
    )
}
