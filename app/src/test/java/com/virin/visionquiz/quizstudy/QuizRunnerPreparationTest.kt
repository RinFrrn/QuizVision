package com.virin.visionquiz.quizstudy

import com.virin.visionquiz.dao.PracticeSession
import com.virin.visionquiz.dao.Quiz
import com.virin.visionquiz.dao.QuizStudyMode
import com.virin.visionquiz.dao.QuizUiType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuizRunnerPreparationTest {

    @Test
    fun reviewPreparationUsesSelectedOrderAndFiltersUnsupportedQuizzes() {
        val prepared = prepareQuizRunnerSession(
            source = listOf(
                quiz(id = 1),
                unsupportedQuiz(id = 2),
                quiz(id = 3)
            ),
            selectedIds = listOf(3, 2, 1),
            restoredOrderIds = emptyList(),
            mode = QuizStudyMode.REVIEW
        )

        assertEquals(listOf(3, 1), prepared.quizzes.map { it.id })
        assertEquals(listOf(1, 3), prepared.supportedQuizSource.map { it.id })
    }

    @Test
    fun restoredOrderTakesPriorityForReviewPreparation() {
        val prepared = prepareQuizRunnerSession(
            source = listOf(quiz(id = 1), quiz(id = 2), quiz(id = 3)),
            selectedIds = listOf(1, 2, 3),
            restoredOrderIds = listOf(3, 1),
            mode = QuizStudyMode.REVIEW
        )

        assertEquals(listOf(3, 1), prepared.quizzes.map { it.id })
    }

    @Test
    fun practicePreparationRestoresStoredSessionState() {
        val prepared = prepareQuizRunnerSession(
            source = listOf(quiz(id = 1), quiz(id = 2), quiz(id = 3)),
            selectedIds = emptyList(),
            restoredOrderIds = emptyList(),
            mode = QuizStudyMode.ORDERED_PRACTICE,
            practiceSession = PracticeSession(
                id = 9,
                libraryId = 1,
                mode = QuizStudyMode.ORDERED_PRACTICE.value,
                quizOrder = "3,1",
                currentIndex = 1,
                currentSelection = "0,1",
                practiceAnswers = "3:0,1|1:0",
                practiceResults = "3:1|1:0",
                recordedQuizIds = "1,3",
                answerVisible = true,
                startedAt = 1_000L,
                updatedAt = 3_000L
            ),
            existingTimerStartedAt = 0L,
            nowMillis = 5_000L
        )

        val restore = requireNotNull(prepared.practiceRestore)
        assertEquals(listOf(3, 1), prepared.quizzes.map { it.id })
        assertEquals(9, restore.sessionId)
        assertEquals(1, restore.currentIndex)
        assertEquals(setOf(0, 1), restore.currentSelection)
        assertEquals(true, restore.answerVisible)
        assertEquals(mapOf(3 to setOf(0, 1), 1 to setOf(0)), restore.practiceAnswers)
        assertEquals(mapOf(3 to true, 1 to false), restore.practiceAnswerResults)
        assertEquals(setOf(1, 3), restore.recordedPracticeQuizIds)
        assertEquals(3_000L, restore.timerStartedAt)
        assertEquals(3_000L, restore.sessionStartedAt)
    }

    @Test
    fun freshPracticePreparationUsesSupportedOrderedSourceAndEmptyState() {
        val prepared = prepareQuizRunnerSession(
            source = listOf(quiz(id = 1), unsupportedQuiz(id = 2), quiz(id = 3)),
            selectedIds = emptyList(),
            restoredOrderIds = emptyList(),
            mode = QuizStudyMode.ORDERED_PRACTICE,
            practiceSession = null,
            nowMillis = 7_000L
        )

        val restore = requireNotNull(prepared.practiceRestore)
        assertEquals(listOf(1, 3), prepared.quizzes.map { it.id })
        assertEquals(0, restore.sessionId)
        assertEquals(0, restore.currentIndex)
        assertEquals(emptySet<Int>(), restore.currentSelection)
        assertEquals(false, restore.answerVisible)
        assertEquals(emptyMap<Int, Set<Int>>(), restore.practiceAnswers)
        assertEquals(emptyMap<Int, Boolean>(), restore.practiceAnswerResults)
        assertEquals(emptySet<Int>(), restore.recordedPracticeQuizIds)
        assertEquals(7_000L, restore.sessionStartedAt)
        assertNull(restore.timerStartedAt)
    }

    private fun quiz(id: Int): Quiz {
        return Quiz(
            id = id,
            prompt = "Question $id",
            options = listOf("A", "B", "C", "D"),
            answer = setOf(0),
            isMultipleChoice = false,
            libraryId = 1
        )
    }

    private fun unsupportedQuiz(id: Int): Quiz {
        return Quiz(
            id = id,
            prompt = "Unsupported $id",
            options = listOf("Subjective"),
            answer = setOf(0),
            isMultipleChoice = false,
            questionType = QuizUiType.SUBJECTIVE.label,
            libraryId = 1
        )
    }
}
