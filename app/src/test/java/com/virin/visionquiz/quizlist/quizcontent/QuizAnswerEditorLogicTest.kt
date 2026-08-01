package com.virin.visionquiz.quizlist.quizcontent

import com.virin.visionquiz.dao.Quiz
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class QuizAnswerEditorLogicTest {

    @Test
    fun choiceAnswerUpdatePreservesQuestionDataAndMultipleChoiceType() {
        val quiz = quiz(
            options = listOf("甲", "乙", "丙", "丁"),
            answer = setOf(0, 1),
            isMultipleChoice = true,
            questionType = "多选"
        )

        val updated = quiz.withEditedChoiceAnswer(setOf(0, 2, 3))

        assertEquals(setOf(0, 2, 3), updated.answer)
        assertEquals(quiz.options, updated.options)
        assertEquals(true, updated.isMultipleChoice)
        assertEquals("多选", updated.questionType)
        assertEquals(quiz.prompt, updated.prompt)
    }

    @Test
    fun textAnswerUpdateTrimsValuesAndSelectsEveryStoredAnswer() {
        val quiz = quiz(
            options = listOf("旧答案一", "旧答案二"),
            answer = setOf(0, 1),
            questionType = "填空"
        )

        val updated = quiz.withEditedTextAnswers(listOf(" 新答案一 ", "新答案二"))

        assertEquals(listOf("新答案一", "新答案二"), updated.options)
        assertEquals(setOf(0, 1), updated.answer)
        assertEquals("填空", updated.questionType)
    }

    @Test
    fun emptyChoiceOrBlankTextAnswerIsRejected() {
        val quiz = quiz(
            options = listOf("甲", "乙"),
            answer = setOf(0)
        )

        assertThrows(IllegalArgumentException::class.java) {
            quiz.withEditedChoiceAnswer(emptySet())
        }
        assertThrows(IllegalArgumentException::class.java) {
            quiz.withEditedTextAnswers(listOf(""))
        }
    }

    private fun quiz(
        options: List<String>,
        answer: Set<Int>,
        isMultipleChoice: Boolean = false,
        questionType: String? = null
    ) = Quiz(
        id = 7,
        prompt = "示例题目",
        options = options,
        answer = answer,
        isMultipleChoice = isMultipleChoice,
        questionType = questionType,
        libraryId = 3
    )
}
