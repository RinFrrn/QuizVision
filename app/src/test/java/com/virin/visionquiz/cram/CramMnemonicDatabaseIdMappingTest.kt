package com.virin.visionquiz.cram

import com.virin.visionquiz.quizlist.quizcontent.QuizContentMemoryPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CramMnemonicDatabaseIdMappingTest {

    @Test
    fun mapsTemporaryAnalysisIdsBeforeOpeningStoredQuizzes() {
        val resolved = resolveCramMnemonicDatabaseIds(
            analysisQuizIds = listOf(-1, 12, 999, -1),
            storedQuizIdByAnalysisId = mapOf(
                -1 to 101,
                12 to 102,
                999 to 404
            ),
            availableDatabaseIds = setOf(101, 102)
        )

        assertEquals(listOf(101, 102), resolved)
    }

    @Test
    fun supportsOlderResultsWhoseStoredIdWasAlreadyUsedDirectly() {
        val resolved = resolveCramMnemonicDatabaseIds(
            analysisQuizIds = listOf(12, 13),
            storedQuizIdByAnalysisId = emptyMap(),
            availableDatabaseIds = setOf(12)
        )

        assertEquals(listOf(12), resolved)
    }

    @Test
    fun keepsEveryVisibleMnemonicSoTheClickedThirteenthCardCanBePreferred() {
        val memoryPoints = (1..16).map { index ->
            QuizContentMemoryPoint(
                id = "mnemonic-$index",
                sourceLabel = "数字速记",
                cue = "$index 天"
            ) to listOf(101)
        }

        val extras = buildCramMnemonicQuizContentExtras(memoryPoints)

        assertEquals(16, extras.memoryPointsByQuizId.getValue(101).size)
        assertTrue(extras.memoryPointsByQuizId.getValue(101).any { it.id == "mnemonic-13" })
    }
}
