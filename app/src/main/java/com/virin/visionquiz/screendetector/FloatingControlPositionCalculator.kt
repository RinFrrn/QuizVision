package com.virin.visionquiz.screendetector

object FloatingControlPositionCalculator {

    data class PositionRanges(
        val horizontal: IntRange,
        val vertical: IntRange
    )

    data class Position(
        val x: Int,
        val y: Int
    )

    fun calculateRanges(
        availableWidth: Int,
        availableHeight: Int,
        controlWidth: Int,
        controlHeight: Int,
        horizontalPadding: Int,
        verticalPadding: Int
    ): PositionRanges {
        return PositionRanges(
            horizontal = containedRange(
                availableSize = availableWidth,
                controlSize = controlWidth,
                padding = horizontalPadding
            ),
            vertical = containedRange(
                availableSize = availableHeight,
                controlSize = controlHeight,
                padding = verticalPadding
            )
        )
    }

    fun clamp(
        x: Int,
        y: Int,
        ranges: PositionRanges
    ): Position {
        return Position(
            x = x.coerceIn(ranges.horizontal),
            y = y.coerceIn(ranges.vertical)
        )
    }

    fun initialY(
        verticalRange: IntRange,
        ratio: Float
    ): Int {
        val boundedRatio = ratio.coerceIn(0f, 1f)
        return verticalRange.first +
            ((verticalRange.last - verticalRange.first) * boundedRatio).toInt()
    }

    private fun containedRange(
        availableSize: Int,
        controlSize: Int,
        padding: Int
    ): IntRange {
        val safeAvailableSize = availableSize.coerceAtLeast(0)
        val safeControlSize = controlSize.coerceAtLeast(0)
        val safePadding = padding.coerceAtLeast(0)
        val minimum = safePadding
        val maximum = safeAvailableSize - safeControlSize - safePadding
        return if (maximum >= minimum) {
            minimum..maximum
        } else {
            0..maxOf(0, safeAvailableSize - safeControlSize)
        }
    }
}
