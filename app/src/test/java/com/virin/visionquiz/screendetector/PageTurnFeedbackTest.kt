package com.virin.visionquiz.screendetector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PageTurnFeedbackTest {
    @Test
    fun recognitionStatesUseWaitingIndicatorAndNeverClaimPageTurnFailed() {
        val recognitionOutcomes = listOf(
            PageTurnFeedback.Outcome.PAGE_MOVED,
            PageTurnFeedback.Outcome.GESTURE_COMPLETED,
            PageTurnFeedback.Outcome.RECOGNITION_DELAYED
        )

        recognitionOutcomes.forEach { outcome ->
            val feedback = PageTurnFeedback.forOutcome(outcome)

            assertEquals(
                ScreenDetectorSession.AssistanceIndicator.WAITING,
                feedback.indicator
            )
            assertFalse(feedback.statusText.contains("翻页未生效"))
        }
    }

    @Test
    fun onlyActionableFailuresUseErrorIndicator() {
        val failedGesture = PageTurnFeedback.forOutcome(
            PageTurnFeedback.Outcome.GESTURE_FAILED
        )
        val pageNotReady = PageTurnFeedback.forOutcome(
            PageTurnFeedback.Outcome.PAGE_NOT_READY
        )

        assertEquals(ScreenDetectorSession.AssistanceIndicator.ERROR, failedGesture.indicator)
        assertEquals(ScreenDetectorSession.AssistanceIndicator.ERROR, pageNotReady.indicator)
    }
}
