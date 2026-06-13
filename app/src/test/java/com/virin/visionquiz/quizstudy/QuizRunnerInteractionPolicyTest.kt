package com.virin.visionquiz.quizstudy

import com.virin.visionquiz.dao.QuizStudyMode
import com.virin.visionquiz.dao.QuizUiType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuizRunnerInteractionPolicyTest {
    @Test
    fun singleChoiceReplacesPreviousSelection() {
        assertEquals(
            setOf(2),
            QuizRunnerInteractionPolicy.nextSelection(
                type = QuizUiType.SINGLE_CHOICE,
                current = setOf(0),
                optionIndex = 2
            )
        )
    }

    @Test
    fun multipleChoiceTogglesOnlyTappedOption() {
        assertEquals(
            setOf(0, 2),
            QuizRunnerInteractionPolicy.nextSelection(
                type = QuizUiType.MULTIPLE_CHOICE,
                current = setOf(0),
                optionIndex = 2
            )
        )
        assertEquals(
            setOf(0),
            QuizRunnerInteractionPolicy.nextSelection(
                type = QuizUiType.MULTIPLE_CHOICE,
                current = setOf(0, 2),
                optionIndex = 2
            )
        )
    }

    @Test
    fun answerVisibilityDependsOnModeSubmissionAndReview() {
        assertTrue(
            QuizRunnerInteractionPolicy.isAnswerVisible(
                QuizStudyMode.ORDERED_PRACTICE,
                reviewMode = false,
                quizId = 7,
                submittedPracticeQuizIds = setOf(7)
            )
        )
        assertFalse(
            QuizRunnerInteractionPolicy.isAnswerVisible(
                QuizStudyMode.EXAM,
                reviewMode = false,
                quizId = 7,
                submittedPracticeQuizIds = setOf(7)
            )
        )
        assertTrue(
            QuizRunnerInteractionPolicy.isAnswerVisible(
                QuizStudyMode.EXAM,
                reviewMode = true,
                quizId = 7,
                submittedPracticeQuizIds = emptySet()
            )
        )
    }
}
