package com.virin.visionquiz.vision.questiondetector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrDisplayMatchSelectorTest {

    @Test
    fun sameQuestionPositionKeepsOneStrongestCandidate() {
        val fullQuestion = candidate(
            value = "full-question",
            identity = "correct",
            bounds = bounds(20, 100, 900, 260),
            score = 0.98
        )
        val nestedFalseMatch = candidate(
            value = "nested-false-match",
            identity = "wrong",
            bounds = bounds(40, 120, 420, 175),
            score = 1.0
        )

        val selected = OcrDisplayMatchSelector.select(listOf(fullQuestion, nestedFalseMatch))

        assertEquals(listOf("full-question"), selected.map { it.value })
    }

    @Test
    fun distinctQuestionPositionsAreBothRetained() {
        val first = candidate("first", "1", bounds(20, 100, 900, 240), 0.96)
        val second = candidate("second", "2", bounds(20, 360, 900, 500), 0.94)

        val selected = OcrDisplayMatchSelector.select(listOf(first, second))

        assertEquals(setOf("first", "second"), selected.mapTo(mutableSetOf()) { it.value })
        assertFalse(OcrDisplayMatchSelector.sameVisualPosition(first.bounds, second.bounds))
    }

    @Test
    fun completeAnswerEvidenceCanBreakCloseCrossFrameMatchTie() {
        val firstScan = candidate(
            value = "first-scan",
            identity = "same",
            bounds = bounds(20, 100, 900, 260),
            score = 0.97
        )
        val secondScan = candidate(
            value = "second-scan",
            identity = "same",
            bounds = bounds(24, 104, 904, 264),
            score = 0.96,
            locatedAnswers = 3,
            expectedAnswers = 3
        )

        val selected = OcrDisplayMatchSelector.select(listOf(firstScan, secondScan))

        assertEquals(listOf("second-scan"), selected.map { it.value })
        assertTrue(OcrDisplayMatchSelector.sameVisualPosition(firstScan.bounds, secondScan.bounds))
    }

    private fun candidate(
        value: String,
        identity: String,
        bounds: OcrOptionLocator.Bounds,
        score: Double,
        locatedAnswers: Int = 0,
        expectedAnswers: Int = 0
    ) = OcrDisplayMatchSelector.Candidate(
        value = value,
        identity = identity,
        bounds = bounds,
        score = score,
        locatedAnswerCount = locatedAnswers,
        expectedAnswerCount = expectedAnswers
    )

    private fun bounds(left: Int, top: Int, right: Int, bottom: Int) =
        OcrOptionLocator.Bounds(left, top, right, bottom)
}
