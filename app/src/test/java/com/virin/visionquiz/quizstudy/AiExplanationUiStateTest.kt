package com.virin.visionquiz.quizstudy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiExplanationUiStateTest {
    @Test
    fun loadingAndStreamingAreInProgress() {
        assertTrue(AiExplanationUiState.Loading.isAiRequestInProgress())
        assertTrue(AiExplanationUiState.Streaming("partial").isAiRequestInProgress())
        assertFalse(AiExplanationUiState.Success("done", false).isAiRequestInProgress())
        assertFalse(AiExplanationUiState.Error("failed").isAiRequestInProgress())
    }

    @Test
    fun errorRetainsPartialContent() {
        val error = AiExplanationUiState.Error("connection lost", "partial markdown")

        assertEquals("partial markdown", error.partialContent)
    }
}
