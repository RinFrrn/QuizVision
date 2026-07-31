package com.virin.visionquiz.vision.questiondetector

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Assigns one geometry-based reading order to OCR lines before question and option processing.
 *
 * ML Kit usually returns blocks in reading order, but does not guarantee it. Keeping this ranking
 * separate from the source enumeration prevents a geometrically rebuilt question from using a
 * different order than the option locator.
 */
internal object OcrReadingOrder {

    data class Key(
        val blockIndex: Int,
        val lineIndex: Int
    )

    data class Item(
        val key: Key,
        val bounds: OcrOptionLocator.Bounds?,
        val sourceOrder: Int
    )

    fun assign(items: List<Item>): Map<Key, Int> {
        val positionedByTop = items
            .asSequence()
            .filter { it.bounds != null }
            .sortedWith(
                compareBy<Item> { it.bounds!!.top }
                    .thenBy { it.bounds!!.left }
                    .thenBy { it.bounds!!.bottom }
                    .thenBy { it.bounds!!.right }
                    .thenBy { it.sourceOrder }
            )
            .toList()
        val rows = mutableListOf<Row>()
        positionedByTop.forEach { item ->
            val bounds = requireNotNull(item.bounds)
            val row = rows.asReversed().firstOrNull {
                sharesVisualRow(it.bounds, bounds)
            }
            if (row == null) {
                rows.add(Row(mutableListOf(item), bounds))
            } else {
                row.items.add(item)
                row.bounds = row.bounds.union(bounds)
            }
        }
        val positioned = rows
            .sortedWith(compareBy<Row> { it.bounds.top }.thenBy { it.bounds.left })
            .flatMap { row ->
                row.items.sortedWith(
                    compareBy<Item> { it.bounds!!.left }
                        .thenBy { it.bounds!!.top }
                        .thenBy { it.sourceOrder }
                )
            }
        val unpositioned = items
            .asSequence()
            .filter { it.bounds == null }
            .sortedBy(Item::sourceOrder)
            .toList()

        return (positioned + unpositioned)
            .mapIndexed { order, item -> item.key to order }
            .toMap()
    }

    private fun sharesVisualRow(
        first: OcrOptionLocator.Bounds,
        second: OcrOptionLocator.Bounds
    ): Boolean {
        val overlap = min(first.bottom, second.bottom) - max(first.top, second.top)
        val minimumHeight = min(first.height, second.height).coerceAtLeast(1)
        val maximumHeight = max(first.height, second.height).coerceAtLeast(1)
        val centerDistance = abs(
            (first.top + first.bottom) / 2f - (second.top + second.bottom) / 2f
        )
        return overlap.toFloat() / minimumHeight >= MIN_VERTICAL_OVERLAP_RATIO ||
            centerDistance <= maximumHeight * MAX_CENTER_OFFSET_RATIO
    }

    private fun OcrOptionLocator.Bounds.union(
        other: OcrOptionLocator.Bounds
    ): OcrOptionLocator.Bounds {
        return OcrOptionLocator.Bounds(
            left = min(left, other.left),
            top = min(top, other.top),
            right = max(right, other.right),
            bottom = max(bottom, other.bottom)
        )
    }

    private data class Row(
        val items: MutableList<Item>,
        var bounds: OcrOptionLocator.Bounds
    )

    private const val MIN_VERTICAL_OVERLAP_RATIO = 0.35f
    private const val MAX_CENTER_OFFSET_RATIO = 0.40f
}
