package com.virin.visionquiz.util

import com.virin.visionquiz.dao.Quiz
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimilarQuizStoreTest {
    @Test
    fun rejectsMissingAndOldAnalysisVersions() {
        assertFalse(isCurrentSimilarAnalysis(hasStoredData = false, storedVersion = 0))
        assertFalse(isCurrentSimilarAnalysis(hasStoredData = true, storedVersion = 1))
        assertFalse(isCurrentSimilarAnalysis(hasStoredData = true, storedVersion = 2))
    }

    @Test
    fun acceptsCurrentAnalysisVersion() {
        assertTrue(
            isCurrentSimilarAnalysis(
                hasStoredData = true,
                storedVersion = SIMILAR_QUIZ_ALGORITHM_VERSION
            )
        )
    }

    @Test
    fun queueDeduplicatesCurrentAndQueuedLibraries() {
        val initial = SimilarQuizQueueState(
            currentLibraryId = 1,
            queuedLibraryIds = listOf(2)
        )

        assertFalse(enqueueSimilarQuizTask(initial, 1).second)
        assertFalse(enqueueSimilarQuizTask(initial, 2).second)
        val (updated, added) = enqueueSimilarQuizTask(initial, 3)

        assertTrue(added)
        assertEquals(listOf(2, 3), updated.queuedLibraryIds)
    }

    @Test
    fun queueCancellationOnlyRemovesRequestedQueuedLibrary() {
        val initial = SimilarQuizQueueState(
            currentLibraryId = 1,
            queuedLibraryIds = listOf(2, 3)
        )

        val updated = cancelQueuedSimilarQuizTask(initial, 2)

        assertEquals(1, updated.currentLibraryId)
        assertEquals(listOf(3), updated.queuedLibraryIds)
    }

    @Test
    fun queueClaimsLibrariesStrictlyInOrder() {
        val initial = SimilarQuizQueueState(
            currentLibraryId = null,
            queuedLibraryIds = listOf(10, 20)
        )

        val (claimed, libraryId) = claimNextSimilarQuizTask(initial)

        assertEquals(10, libraryId)
        assertEquals(10, claimed.currentLibraryId)
        assertEquals(listOf(20), claimed.queuedLibraryIds)
        assertEquals(claimed to 10, claimNextSimilarQuizTask(claimed))
    }

    @Test
    fun fingerprintChangesWhenQuizContentChanges() {
        val original = quiz(1, "SF6断路器检查")
        val changed = original.copy(prompt = "SF6断路器压力检查")

        assertFalse(
            SimilarQuizStore.fingerprint(listOf(original)) ==
                SimilarQuizStore.fingerprint(listOf(changed))
        )
    }

    private fun quiz(id: Int, prompt: String) = Quiz(
        id = id,
        prompt = prompt,
        options = listOf("正确", "错误"),
        answer = setOf(0),
        isMultipleChoice = false,
        questionType = "判断",
        libraryId = 1
    )
}
