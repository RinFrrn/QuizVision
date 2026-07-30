package com.virin.visionquiz.quizlist.quizcontent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuizContentMemoryPointTest {

    @Test
    fun putsTheClickedMemoryPointFirstEvenWhenItIsThirteenth() {
        val points = (1..16).map { index ->
            QuizContentMemoryPoint(
                id = "memory-$index",
                sourceLabel = "数字速记",
                cue = "$index 天"
            )
        }
        val extras = QuizContentExtras(
            memoryPointsByQuizId = mapOf(101 to points),
            preferredMemoryPointId = "memory-13",
            showMemoryPointEmptyState = true
        )

        val ordered = orderedQuizContentMemoryPoints(101, extras)

        assertEquals(16, ordered.size)
        assertEquals("memory-13", ordered.first().id)
    }

    @Test
    fun ordinaryQuizEntryHasNoMemoryPointsOrEmptyState() {
        val extras = QuizContentExtras()

        assertTrue(orderedQuizContentMemoryPoints(101, extras).isEmpty())
        assertTrue(!extras.showMemoryPointEmptyState)
    }
}
