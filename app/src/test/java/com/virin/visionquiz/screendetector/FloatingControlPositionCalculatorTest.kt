package com.virin.visionquiz.screendetector

import org.junit.Assert.assertEquals
import org.junit.Test

class FloatingControlPositionCalculatorTest {

    @Test
    fun calculatesRangesInsideUsableWindow() {
        val ranges = FloatingControlPositionCalculator.calculateRanges(
            availableWidth = 1280,
            availableHeight = 2569,
            controlWidth = 620,
            controlHeight = 238,
            horizontalPadding = 13,
            verticalPadding = 25
        )

        assertEquals(13..647, ranges.horizontal)
        assertEquals(25..2306, ranges.vertical)
    }

    @Test
    fun clampsEveryPositionToSafeRanges() {
        val ranges = FloatingControlPositionCalculator.PositionRanges(
            horizontal = 13..647,
            vertical = 25..2306
        )

        assertEquals(
            FloatingControlPositionCalculator.Position(13, 2306),
            FloatingControlPositionCalculator.clamp(-100, 3000, ranges)
        )
    }

    @Test
    fun initialPositionIsNearLowerLeft() {
        val verticalRange = 25..2306

        assertEquals(
            1895,
            FloatingControlPositionCalculator.initialY(verticalRange, 0.82f)
        )
    }

    @Test
    fun oversizedControlFallsBackToAvailableOrigin() {
        val ranges = FloatingControlPositionCalculator.calculateRanges(
            availableWidth = 300,
            availableHeight = 200,
            controlWidth = 400,
            controlHeight = 250,
            horizontalPadding = 12,
            verticalPadding = 24
        )

        assertEquals(0..0, ranges.horizontal)
        assertEquals(0..0, ranges.vertical)
    }
}
