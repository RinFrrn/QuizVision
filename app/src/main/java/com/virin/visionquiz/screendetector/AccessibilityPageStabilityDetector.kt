package com.virin.visionquiz.screendetector

import kotlin.math.abs
import kotlin.math.max

object AccessibilityPageStabilityDetector {

    const val MAX_IGNORED_CHANGE_AREA_RATIO = 0.015
    const val MIN_CONFIRMATION_DELAY_MS = 100L
    private const val MAX_IGNORED_CHANGED_REGIONS = 3
    private const val MIN_POSITION_TOLERANCE_PX = 4
    private const val POSITION_TOLERANCE_RATIO = 0.004

    data class Bounds(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    ) {
        val area: Long
            get() = (right - left).coerceAtLeast(0).toLong() *
                (bottom - top).coerceAtLeast(0).toLong()
    }

    data class TextRegion(
        val text: String,
        val bounds: Bounds
    )

    data class Signature(
        val width: Int,
        val height: Int,
        val textRegions: List<TextRegion>,
        val dangerousRegions: List<Bounds>
    )

    enum class Decision {
        STABLE,
        CHANGED,
        GEOMETRY_CHANGED
    }

    data class Comparison(
        val decision: Decision,
        val changedAreaRatio: Double,
        val changedRegionCount: Int
    ) {
        val isStable: Boolean
            get() = decision == Decision.STABLE
    }

    enum class PublishDecision {
        WAIT_FOR_CONFIRMATION,
        RECOLLECT,
        PUBLISH
    }

    data class Evaluation(
        val decision: PublishDecision,
        val remainingDelayMs: Long = 0L,
        val comparison: Comparison? = null
    )

    fun evaluate(
        candidate: Signature,
        confirmation: Signature,
        candidateAtMs: Long,
        nowMs: Long
    ): Evaluation {
        val remainingDelayMs =
            MIN_CONFIRMATION_DELAY_MS - (nowMs - candidateAtMs)
        if (remainingDelayMs > 0L) {
            return Evaluation(
                decision = PublishDecision.WAIT_FOR_CONFIRMATION,
                remainingDelayMs = remainingDelayMs
            )
        }
        val comparison = compare(candidate, confirmation)
        return Evaluation(
            decision = if (comparison.isStable) {
                PublishDecision.PUBLISH
            } else {
                PublishDecision.RECOLLECT
            },
            comparison = comparison
        )
    }

    fun compare(candidate: Signature, confirmation: Signature): Comparison {
        if (candidate.width != confirmation.width ||
            candidate.height != confirmation.height ||
            candidate.width <= 0 ||
            candidate.height <= 0
        ) {
            return Comparison(Decision.GEOMETRY_CHANGED, 1.0, Int.MAX_VALUE)
        }

        val tolerance = max(
            MIN_POSITION_TOLERANCE_PX,
            (max(candidate.width, candidate.height) * POSITION_TOLERANCE_RATIO).toInt()
        )
        val changedBounds = mutableListOf<Bounds>()
        collectChangedTextBounds(
            candidate.textRegions,
            confirmation.textRegions,
            tolerance,
            changedBounds
        )
        val changedTextRegionCount = changedBounds.size
        collectChangedBounds(
            candidate.dangerousRegions,
            confirmation.dangerousRegions,
            tolerance,
            changedBounds
        )
        val dangerousRegionsChanged = changedBounds.size > changedTextRegionCount

        val screenArea = candidate.width.toLong() * candidate.height.toLong()
        val changedArea = unionArea(
            bounds = changedBounds,
            screenWidth = candidate.width,
            screenHeight = candidate.height
        )
        val changedAreaRatio = if (screenArea == 0L) {
            1.0
        } else {
            changedArea.toDouble() / screenArea
        }
        val isStable = !dangerousRegionsChanged &&
            changedBounds.size <= MAX_IGNORED_CHANGED_REGIONS &&
            changedAreaRatio <= MAX_IGNORED_CHANGE_AREA_RATIO
        return Comparison(
            decision = if (isStable) Decision.STABLE else Decision.CHANGED,
            changedAreaRatio = changedAreaRatio,
            changedRegionCount = changedBounds.size
        )
    }

    private fun collectChangedTextBounds(
        first: List<TextRegion>,
        second: List<TextRegion>,
        tolerance: Int,
        outChangedBounds: MutableList<Bounds>
    ) {
        val unmatchedSecond = second.indices.toMutableSet()
        val unmatchedFirst = mutableListOf<TextRegion>()
        first.forEach { region ->
            val matchIndex = unmatchedSecond.firstOrNull { index ->
                region.text == second[index].text &&
                    region.bounds.isNear(second[index].bounds, tolerance)
            }
            if (matchIndex == null) {
                unmatchedFirst.add(region)
            } else {
                unmatchedSecond.remove(matchIndex)
            }
        }

        val remainingSecond = unmatchedSecond.map(second::get).toMutableList()
        unmatchedFirst.forEach { region ->
            val replacementIndex = remainingSecond.indexOfFirst {
                region.bounds.isNear(it.bounds, tolerance)
            }
            if (replacementIndex >= 0) {
                outChangedBounds.add(
                    region.bounds.union(remainingSecond.removeAt(replacementIndex).bounds)
                )
            } else {
                outChangedBounds.add(region.bounds)
            }
        }
        remainingSecond.forEach { outChangedBounds.add(it.bounds) }
    }

    private fun collectChangedBounds(
        first: List<Bounds>,
        second: List<Bounds>,
        tolerance: Int,
        outChangedBounds: MutableList<Bounds>
    ) {
        val unmatchedSecond = second.toMutableList()
        first.forEach { bounds ->
            val matchIndex = unmatchedSecond.indexOfFirst { bounds.isNear(it, tolerance) }
            if (matchIndex >= 0) {
                unmatchedSecond.removeAt(matchIndex)
            } else {
                outChangedBounds.add(bounds)
            }
        }
        unmatchedSecond.forEach(outChangedBounds::add)
    }

    private fun Bounds.isNear(other: Bounds, tolerance: Int): Boolean {
        return abs(left - other.left) <= tolerance &&
            abs(top - other.top) <= tolerance &&
            abs(right - other.right) <= tolerance &&
            abs(bottom - other.bottom) <= tolerance
    }

    private fun Bounds.union(other: Bounds): Bounds {
        return Bounds(
            left = minOf(left, other.left),
            top = minOf(top, other.top),
            right = maxOf(right, other.right),
            bottom = maxOf(bottom, other.bottom)
        )
    }

    private fun unionArea(bounds: List<Bounds>, screenWidth: Int, screenHeight: Int): Long {
        val clipped = bounds.mapNotNull { item ->
            val left = item.left.coerceIn(0, screenWidth)
            val right = item.right.coerceIn(0, screenWidth)
            val top = item.top.coerceIn(0, screenHeight)
            val bottom = item.bottom.coerceIn(0, screenHeight)
            if (left >= right || top >= bottom) null else Bounds(left, top, right, bottom)
        }
        if (clipped.isEmpty()) return 0L

        val xCoordinates = clipped.flatMap { listOf(it.left, it.right) }.distinct().sorted()
        var area = 0L
        for (index in 0 until xCoordinates.lastIndex) {
            val xStart = xCoordinates[index]
            val xEnd = xCoordinates[index + 1]
            if (xStart == xEnd) continue
            val yIntervals = clipped
                .asSequence()
                .filter { it.left < xEnd && it.right > xStart }
                .map { it.top to it.bottom }
                .sortedBy { it.first }
                .toList()
            var coveredHeight = 0L
            var currentTop: Int? = null
            var currentBottom = 0
            yIntervals.forEach { (top, bottom) ->
                val activeTop = currentTop
                if (activeTop == null) {
                    currentTop = top
                    currentBottom = bottom
                } else if (top <= currentBottom) {
                    currentBottom = maxOf(currentBottom, bottom)
                } else {
                    coveredHeight += currentBottom - activeTop
                    currentTop = top
                    currentBottom = bottom
                }
            }
            currentTop?.let { coveredHeight += currentBottom - it }
            area += (xEnd - xStart).toLong() * coveredHeight
        }
        return area
    }
}
