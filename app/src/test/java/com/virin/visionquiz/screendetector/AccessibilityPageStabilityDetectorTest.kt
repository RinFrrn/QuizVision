package com.virin.visionquiz.screendetector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityPageStabilityDetectorTest {

    @Test
    fun identicalContentAndSmallPositionChangesAreStable() {
        val candidate = signature(
            textRegions = listOf(region("题目", 40, 100, 700, 180))
        )
        val confirmation = signature(
            textRegions = listOf(region("题目", 43, 103, 703, 183))
        )

        val result = AccessibilityPageStabilityDetector.compare(candidate, confirmation)

        assertEquals(AccessibilityPageStabilityDetector.Decision.STABLE, result.decision)
        assertEquals(0, result.changedRegionCount)
    }

    @Test
    fun smallTimerChangeIsIgnored() {
        val candidate = signature(
            textRegions = listOf(
                region("题目正文", 40, 100, 700, 260),
                region("00:10", 850, 30, 1030, 80)
            )
        )
        val confirmation = signature(
            textRegions = listOf(
                region("题目正文", 40, 100, 700, 260),
                region("00:09", 850, 30, 1030, 80)
            )
        )

        val result = AccessibilityPageStabilityDetector.compare(candidate, confirmation)

        assertTrue(result.isStable)
        assertTrue(
            result.changedAreaRatio <=
                AccessibilityPageStabilityDetector.MAX_IGNORED_CHANGE_AREA_RATIO
        )
    }

    @Test
    fun mainContentChangeRequiresAnotherCandidate() {
        val candidate = signature(
            textRegions = listOf(region("第一道题", 40, 100, 1000, 500))
        )
        val confirmation = signature(
            textRegions = listOf(region("第二道题", 40, 100, 1000, 500))
        )

        val result = AccessibilityPageStabilityDetector.compare(candidate, confirmation)

        assertEquals(AccessibilityPageStabilityDetector.Decision.CHANGED, result.decision)
    }

    @Test
    fun scrollingPositionsRequireAnotherCandidate() {
        val candidate = signature(
            textRegions = listOf(
                region("题目", 40, 500, 900, 650),
                region("A. 选项", 40, 700, 900, 800)
            )
        )
        val confirmation = signature(
            textRegions = listOf(
                region("题目", 40, 250, 900, 400),
                region("A. 选项", 40, 450, 900, 550)
            )
        )

        val result = AccessibilityPageStabilityDetector.compare(candidate, confirmation)

        assertEquals(AccessibilityPageStabilityDetector.Decision.CHANGED, result.decision)
    }

    @Test
    fun dangerousActionAndGeometryChangesAreNotStable() {
        val candidate = signature(
            textRegions = listOf(region("题目", 40, 100, 700, 180))
        )
        val dangerousConfirmation = signature(
            textRegions = candidate.textRegions,
            dangerousRegions = listOf(bounds(900, 2200, 1040, 2260))
        )
        val resizedConfirmation = candidate.copy(width = 1200)

        assertEquals(
            AccessibilityPageStabilityDetector.Decision.CHANGED,
            AccessibilityPageStabilityDetector.compare(
                candidate,
                dangerousConfirmation
            ).decision
        )
        assertEquals(
            AccessibilityPageStabilityDetector.Decision.GEOMETRY_CHANGED,
            AccessibilityPageStabilityDetector.compare(
                candidate,
                resizedConfirmation
            ).decision
        )
    }

    @Test
    fun schedulingWaitsThenPublishesOrRecollects() {
        val candidate = signature(
            textRegions = listOf(region("题目", 40, 100, 700, 180))
        )
        val stableConfirmation = candidate.copy()
        val changedConfirmation = signature(
            textRegions = listOf(region("另一道题", 40, 100, 1000, 500))
        )

        val waiting = AccessibilityPageStabilityDetector.evaluate(
            candidate,
            stableConfirmation,
            candidateAtMs = 1_000L,
            nowMs = 1_099L
        )
        val publish = AccessibilityPageStabilityDetector.evaluate(
            candidate,
            stableConfirmation,
            candidateAtMs = 1_000L,
            nowMs = 1_100L
        )
        val recollect = AccessibilityPageStabilityDetector.evaluate(
            candidate,
            changedConfirmation,
            candidateAtMs = 1_000L,
            nowMs = 1_100L
        )

        assertEquals(
            AccessibilityPageStabilityDetector.PublishDecision.WAIT_FOR_CONFIRMATION,
            waiting.decision
        )
        assertEquals(1L, waiting.remainingDelayMs)
        assertEquals(
            AccessibilityPageStabilityDetector.PublishDecision.PUBLISH,
            publish.decision
        )
        assertEquals(
            AccessibilityPageStabilityDetector.PublishDecision.RECOLLECT,
            recollect.decision
        )
    }

    private fun signature(
        textRegions: List<AccessibilityPageStabilityDetector.TextRegion>,
        dangerousRegions: List<AccessibilityPageStabilityDetector.Bounds> = emptyList()
    ) = AccessibilityPageStabilityDetector.Signature(
        width = 1080,
        height = 2400,
        textRegions = textRegions,
        dangerousRegions = dangerousRegions
    )

    private fun region(
        text: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ) = AccessibilityPageStabilityDetector.TextRegion(
        text = text,
        bounds = bounds(left, top, right, bottom)
    )

    private fun bounds(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ) = AccessibilityPageStabilityDetector.Bounds(left, top, right, bottom)
}
