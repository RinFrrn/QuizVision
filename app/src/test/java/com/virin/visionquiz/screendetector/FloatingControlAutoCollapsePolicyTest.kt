package com.virin.visionquiz.screendetector

import org.junit.Assert.assertEquals
import org.junit.Test

class FloatingControlAutoCollapsePolicyTest {
    @Test
    fun runningNonInteractiveStatesCollapseAfterDelay() {
        val decision = FloatingControlAutoCollapsePolicy.decide(
            enabled = true,
            detectionState = ScreenDetectorSession.DetectionState.RUNNING,
            assistanceState = ScreenDetectorSession.AssistanceState(
                isActive = true,
                phase = ScreenDetectorSession.AssistancePhase.WAITING_NEW_PAGE,
                pageDirection = ScreenDetectorSession.PageDirection.LEFT,
                indicator = ScreenDetectorSession.AssistanceIndicator.WAITING
            )
        )

        assertEquals(
            FloatingControlAutoCollapsePolicy.Decision.COLLAPSE_AFTER_DELAY,
            decision
        )
    }

    @Test
    fun manualDirectionChoiceKeepsControlExpanded() {
        val decision = FloatingControlAutoCollapsePolicy.decide(
            enabled = true,
            detectionState = ScreenDetectorSession.DetectionState.RUNNING,
            assistanceState = ScreenDetectorSession.AssistanceState(
                isActive = true,
                phase = ScreenDetectorSession.AssistancePhase.WAITING_MANUAL_PAGE,
                pageDirection = null
            )
        )

        assertEquals(FloatingControlAutoCollapsePolicy.Decision.EXPAND, decision)
    }

    @Test
    fun errorsAndPausedDetectionKeepControlExpanded() {
        val errorDecision = FloatingControlAutoCollapsePolicy.decide(
            enabled = true,
            detectionState = ScreenDetectorSession.DetectionState.RUNNING,
            assistanceState = ScreenDetectorSession.AssistanceState(
                isActive = true,
                indicator = ScreenDetectorSession.AssistanceIndicator.ERROR
            )
        )
        val pausedDecision = FloatingControlAutoCollapsePolicy.decide(
            enabled = true,
            detectionState = ScreenDetectorSession.DetectionState.PAUSED,
            assistanceState = ScreenDetectorSession.AssistanceState()
        )

        assertEquals(FloatingControlAutoCollapsePolicy.Decision.EXPAND, errorDecision)
        assertEquals(FloatingControlAutoCollapsePolicy.Decision.EXPAND, pausedDecision)
    }

    @Test
    fun disabledPolicyNeverChangesCurrentExpansion() {
        val decision = FloatingControlAutoCollapsePolicy.decide(
            enabled = false,
            detectionState = ScreenDetectorSession.DetectionState.PAUSED,
            assistanceState = ScreenDetectorSession.AssistanceState(
                indicator = ScreenDetectorSession.AssistanceIndicator.ERROR
            )
        )

        assertEquals(FloatingControlAutoCollapsePolicy.Decision.NONE, decision)
    }

    @Test
    fun onlyCollapsedScreenOcrControlIsHiddenDuringScan() {
        val activeScan = ScreenDetectorSession.ScreenScanState(
            generation = 3,
            hideResults = true
        )

        assertEquals(
            true,
            FloatingControlAutoCollapsePolicy.shouldHideCollapsedControlDuringScan(
                isCollapsed = true,
                mode = ScreenDetectorSession.DetectionMode.SCREEN_OCR,
                screenScanState = activeScan
            )
        )
        assertEquals(
            false,
            FloatingControlAutoCollapsePolicy.shouldHideCollapsedControlDuringScan(
                isCollapsed = false,
                mode = ScreenDetectorSession.DetectionMode.SCREEN_OCR,
                screenScanState = activeScan
            )
        )
        assertEquals(
            false,
            FloatingControlAutoCollapsePolicy.shouldHideCollapsedControlDuringScan(
                isCollapsed = true,
                mode = ScreenDetectorSession.DetectionMode.ACCESSIBILITY,
                screenScanState = activeScan
            )
        )
        assertEquals(
            false,
            FloatingControlAutoCollapsePolicy.shouldHideCollapsedControlDuringScan(
                isCollapsed = true,
                mode = ScreenDetectorSession.DetectionMode.SCREEN_OCR,
                screenScanState = activeScan.copy(hideResults = false)
            )
        )
    }
}
