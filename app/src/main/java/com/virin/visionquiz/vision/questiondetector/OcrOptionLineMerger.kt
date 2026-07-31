package com.virin.visionquiz.vision.questiondetector

import kotlin.math.max
import kotlin.math.min

/**
 * Rejoins an OCR option label and its text when ML Kit returns them as separate lines/blocks.
 */
internal object OcrOptionLineMerger {

    fun merge(
        candidates: List<OcrOptionLocator.TextCandidate>
    ): List<OcrOptionLocator.TextCandidate> {
        if (candidates.size < 2) {
            return candidates
        }

        val sorted = candidates.sortedWith(
            compareBy<OcrOptionLocator.TextCandidate> { it.order }
                .thenBy { it.bounds.left }
        )
        val consumed = mutableSetOf<Int>()
        val result = mutableListOf<OcrOptionLocator.TextCandidate>()
        sorted.forEachIndexed { index, label ->
            if (index in consumed) {
                return@forEachIndexed
            }
            if (!STANDALONE_OPTION_LABEL_REGEX.matches(label.text.trim())) {
                result.add(label)
                return@forEachIndexed
            }

            val textIndex = sorted.indices
                .asSequence()
                .filter { it !in consumed && it != index }
                .filter { sorted[it].order > label.order }
                .filter {
                    !STANDALONE_OPTION_LABEL_REGEX.matches(sorted[it].text.trim())
                }
                .filter { sorted[it].bounds.left >= label.bounds.right }
                .filter {
                    verticalOverlapRatio(label.bounds, sorted[it].bounds) >=
                        MIN_VERTICAL_OVERLAP_RATIO
                }
                .filter {
                    sorted[it].bounds.left - label.bounds.right <=
                        label.bounds.height * MAX_HORIZONTAL_GAP_HEIGHT_MULTIPLIER
                }
                .minWithOrNull(
                    compareBy<Int> { sorted[it].bounds.left - label.bounds.right }
                        .thenBy { sorted[it].order }
                )
            if (textIndex == null) {
                result.add(label)
                return@forEachIndexed
            }

            val text = sorted[textIndex]
            consumed.add(textIndex)
            result.add(
                OcrOptionLocator.TextCandidate(
                    text = "${label.text.trim()} ${text.text.trim()}",
                    bounds = label.bounds.union(text.bounds),
                    order = label.order
                )
            )
        }
        return result.sortedWith(
            compareBy<OcrOptionLocator.TextCandidate> { it.order }
                .thenBy { it.bounds.left }
        )
    }

    private fun verticalOverlapRatio(
        first: OcrOptionLocator.Bounds,
        second: OcrOptionLocator.Bounds
    ): Float {
        val overlap = (min(first.bottom, second.bottom) - max(first.top, second.top))
            .coerceAtLeast(0)
        return overlap.toFloat() / min(first.height, second.height).coerceAtLeast(1)
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

    private const val MIN_VERTICAL_OVERLAP_RATIO = 0.50f
    private const val MAX_HORIZONTAL_GAP_HEIGHT_MULTIPLIER = 4
    private val STANDALONE_OPTION_LABEL_REGEX =
        Regex("""^[A-Ha-hＡ-Ｈａ-ｈ](?:[、.．:：)）])?$""")
}
