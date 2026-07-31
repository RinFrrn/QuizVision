package com.virin.visionquiz.vision.questiondetector

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Builds question candidates that ML Kit cannot produce when one visual paragraph is split into
 * several text blocks.
 *
 * This class deliberately works with the small, platform-independent [OcrOptionLocator.Bounds]
 * value type so its geometry rules can be covered by local JVM tests.
 */
internal object OcrQuestionCandidateBuilder {

    data class Line(
        val text: String,
        val bounds: OcrOptionLocator.Bounds,
        val order: Int,
        val blockIndex: Int
    )

    data class Candidate(
        val text: String,
        val bounds: OcrOptionLocator.Bounds,
        val startOrder: Int,
        val endOrder: Int,
        val blockIndices: Set<Int>,
        val visualLineCount: Int
    )

    fun build(lines: List<Line>): List<Candidate> {
        val sourceLines = lines
            .asSequence()
            .filter { it.text.isNotBlank() }
            .filter { it.bounds.width > 0 && it.bounds.height > 0 }
            .distinctBy(::sourceLineKey)
            .toList()
        if (sourceLines.size < 2) {
            return emptyList()
        }

        val blockRanks = buildBlockRanks(sourceLines)
        val visualLines = buildVisualLines(sourceLines)
        if (visualLines.isEmpty()) {
            return emptyList()
        }

        val uniqueCandidates = linkedMapOf<String, Candidate>()
        visualLines.forEachIndexed { startIndex, firstLine ->
            if (isOptionLine(firstLine.text)) {
                return@forEachIndexed
            }

            var combinedBounds = firstLine.bounds
            val combinedText = StringBuilder(firstLine.text)
            val includedBlocks = linkedSetOf<Int>().apply {
                addAll(firstLine.blockIndices)
            }
            var endOrder = firstLine.endOrder
            var previousLine = firstLine

            for (
                endIndex in startIndex until
                    min(visualLines.size, startIndex + MAX_VISUAL_LINE_COUNT)
            ) {
                val currentLine = visualLines[endIndex]
                if (endIndex > startIndex) {
                    if (
                        isOptionLine(currentLine.text) ||
                        isQuestionStartLine(currentLine.text)
                    ) {
                        break
                    }
                    if (!areAdjacentQuestionLines(previousLine, currentLine)) {
                        break
                    }
                    combinedText.append('\n').append(currentLine.text)
                    combinedBounds = union(combinedBounds, currentLine.bounds)
                    includedBlocks.addAll(currentLine.blockIndices)
                    endOrder = max(endOrder, currentLine.endOrder)
                    previousLine = currentLine
                }

                if (
                    includedBlocks.size < MIN_BLOCK_COUNT ||
                    !areContiguousBlocks(includedBlocks, blockRanks)
                ) {
                    continue
                }

                val candidate = Candidate(
                    text = combinedText.toString(),
                    bounds = combinedBounds,
                    startOrder = firstLine.startOrder,
                    endOrder = endOrder,
                    blockIndices = includedBlocks.toSet(),
                    visualLineCount = endIndex - startIndex + 1
                )
                uniqueCandidates.putIfAbsent(candidateKey(candidate), candidate)
            }
        }
        return uniqueCandidates.values.toList()
    }

    private fun buildVisualLines(lines: List<Line>): List<VisualLine> {
        val groups = mutableListOf<MutableList<Line>>()
        lines.sortedWith(SOURCE_READING_ORDER).forEach { line ->
            val matchingGroup = groups
                .asReversed()
                .firstOrNull { group ->
                    val groupBounds = union(group.map(Line::bounds))
                    canShareVisualLine(groupBounds, line.bounds)
                }
            if (matchingGroup == null) {
                groups.add(mutableListOf(line))
            } else {
                matchingGroup.add(line)
            }
        }

        return groups
            .map(::toVisualLine)
            .sortedWith(
                compareBy<VisualLine> { it.bounds.top }
                    .thenBy { it.bounds.left }
                    .thenBy { it.startOrder }
            )
    }

    private fun toVisualLine(parts: List<Line>): VisualLine {
        val orderedParts = parts.sortedWith(compareBy<Line> { it.bounds.left }.thenBy { it.order })
        return VisualLine(
            text = orderedParts.joinToString(" ") { it.text.trim() },
            bounds = union(orderedParts.map(Line::bounds)),
            startOrder = orderedParts.minOf(Line::order),
            endOrder = orderedParts.maxOf(Line::order),
            blockIndices = orderedParts.mapTo(linkedSetOf(), Line::blockIndex)
        )
    }

    private fun canShareVisualLine(
        left: OcrOptionLocator.Bounds,
        right: OcrOptionLocator.Bounds
    ): Boolean {
        if (!haveComparableHeights(left, right)) {
            return false
        }
        val overlap = min(left.bottom, right.bottom) - max(left.top, right.top)
        val minimumHeight = min(left.height, right.height).coerceAtLeast(1)
        val maximumHeight = max(left.height, right.height).coerceAtLeast(1)
        val centerDistance = abs(
            (left.top + left.bottom) / 2f - (right.top + right.bottom) / 2f
        )
        val sharesRow =
            overlap.toFloat() / minimumHeight >= MIN_VERTICAL_OVERLAP_RATIO ||
                centerDistance <= maximumHeight * MAX_CENTER_OFFSET_RATIO
        if (!sharesRow) {
            return false
        }

        val horizontalGap = when {
            left.right < right.left -> right.left - left.right
            right.right < left.left -> left.left - right.right
            else -> 0
        }
        return horizontalGap <= maximumHeight * MAX_SAME_LINE_HORIZONTAL_GAP_RATIO
    }

    private fun areAdjacentQuestionLines(first: VisualLine, second: VisualLine): Boolean {
        if (!haveComparableHeights(first.bounds, second.bounds)) {
            return false
        }
        val referenceHeight = max(first.bounds.height, second.bounds.height).coerceAtLeast(1)
        val verticalGap = second.bounds.top - first.bounds.bottom
        if (verticalGap > referenceHeight * MAX_LINE_GAP_RATIO) {
            return false
        }
        if (second.bounds.top < first.bounds.top) {
            return false
        }

        val overlap = min(first.bounds.right, second.bounds.right) -
            max(first.bounds.left, second.bounds.left)
        val minimumWidth = min(first.bounds.width, second.bounds.width).coerceAtLeast(1)
        val hasHorizontalOverlap =
            overlap > 0 && overlap.toFloat() / minimumWidth >= MIN_HORIZONTAL_OVERLAP_RATIO
        val hasCompatibleIndent =
            abs(first.bounds.left - second.bounds.left) <=
                referenceHeight * MAX_LEFT_INDENT_RATIO
        return hasHorizontalOverlap || hasCompatibleIndent
    }

    private fun haveComparableHeights(
        first: OcrOptionLocator.Bounds,
        second: OcrOptionLocator.Bounds
    ): Boolean {
        val maximumHeight = max(first.height, second.height)
        if (maximumHeight <= 0) {
            return false
        }
        return min(first.height, second.height).toFloat() / maximumHeight >=
            MIN_LINE_HEIGHT_RATIO
    }

    private fun buildBlockRanks(lines: List<Line>): Map<Int, Int> {
        return lines
            .groupBy(Line::blockIndex)
            .map { (blockIndex, blockLines) ->
                val bounds = union(blockLines.map(Line::bounds))
                BlockPosition(
                    blockIndex = blockIndex,
                    top = bounds.top,
                    left = bounds.left,
                    order = blockLines.minOf(Line::order)
                )
            }
            .sortedWith(
                compareBy<BlockPosition> { it.top }
                    .thenBy { it.left }
                    .thenBy { it.order }
                    .thenBy { it.blockIndex }
            )
            .mapIndexed { rank, block -> block.blockIndex to rank }
            .toMap()
    }

    private fun areContiguousBlocks(
        blockIndices: Set<Int>,
        blockRanks: Map<Int, Int>
    ): Boolean {
        val ranks = blockIndices.mapNotNull(blockRanks::get).distinct()
        if (ranks.size != blockIndices.size || ranks.size < MIN_BLOCK_COUNT) {
            return false
        }
        return ranks.max() - ranks.min() + 1 == ranks.size
    }

    fun isOptionLine(text: String): Boolean {
        return OPTION_LINE_REGEX.containsMatchIn(text)
    }

    fun isQuestionStartLine(text: String): Boolean {
        return QUESTION_TYPE_START_REGEX.containsMatchIn(text) ||
            NUMBERED_QUESTION_START_REGEX.containsMatchIn(text)
    }

    private fun union(bounds: List<OcrOptionLocator.Bounds>): OcrOptionLocator.Bounds {
        require(bounds.isNotEmpty())
        return OcrOptionLocator.Bounds(
            left = bounds.minOf(OcrOptionLocator.Bounds::left),
            top = bounds.minOf(OcrOptionLocator.Bounds::top),
            right = bounds.maxOf(OcrOptionLocator.Bounds::right),
            bottom = bounds.maxOf(OcrOptionLocator.Bounds::bottom)
        )
    }

    private fun union(
        first: OcrOptionLocator.Bounds,
        second: OcrOptionLocator.Bounds
    ): OcrOptionLocator.Bounds {
        return OcrOptionLocator.Bounds(
            left = min(first.left, second.left),
            top = min(first.top, second.top),
            right = max(first.right, second.right),
            bottom = max(first.bottom, second.bottom)
        )
    }

    private fun sourceLineKey(line: Line): String {
        return listOf(
            line.blockIndex,
            line.order,
            line.bounds.left,
            line.bounds.top,
            line.bounds.right,
            line.bounds.bottom,
            line.text.trim()
        ).joinToString("#")
    }

    private fun candidateKey(candidate: Candidate): String {
        val normalizedText = candidate.text.replace(WHITESPACE_REGEX, "")
        return listOf(
            normalizedText,
            candidate.bounds.left,
            candidate.bounds.top,
            candidate.bounds.right,
            candidate.bounds.bottom
        ).joinToString("#")
    }

    private data class VisualLine(
        val text: String,
        val bounds: OcrOptionLocator.Bounds,
        val startOrder: Int,
        val endOrder: Int,
        val blockIndices: Set<Int>
    )

    private data class BlockPosition(
        val blockIndex: Int,
        val top: Int,
        val left: Int,
        val order: Int
    )

    private const val MIN_BLOCK_COUNT = 2
    private const val MAX_VISUAL_LINE_COUNT = 5
    private const val MIN_LINE_HEIGHT_RATIO = 0.50f
    private const val MIN_VERTICAL_OVERLAP_RATIO = 0.45f
    private const val MAX_CENTER_OFFSET_RATIO = 0.35f
    private const val MAX_SAME_LINE_HORIZONTAL_GAP_RATIO = 3.0f
    private const val MAX_LINE_GAP_RATIO = 2.5f
    private const val MIN_HORIZONTAL_OVERLAP_RATIO = 0.12f
    private const val MAX_LEFT_INDENT_RATIO = 2.5f

    private const val QUESTION_TYPE = "(?:单选题|多选题|判断题|填空题)"
    private val WHITESPACE_REGEX = Regex("\\s+")
    private val OPTION_LINE_REGEX = Regex(
        """^\s*[A-HＡ-Ｈ](?:\s*$|[\s.．、:：)）])""",
        RegexOption.IGNORE_CASE
    )
    private val QUESTION_TYPE_START_REGEX = Regex(
        """^\s*(?:[【\[]\s*$QUESTION_TYPE\s*[】\]]|$QUESTION_TYPE\s*[:：、.．]|$QUESTION_TYPE(?=\s*(?:第\s*)?[0-9０-９])|$QUESTION_TYPE\s*$)"""
    )
    private val NUMBERED_QUESTION_START_REGEX = Regex(
        """^\s*(?:(?:第\s*)?[0-9０-９]{1,4}\s*(?:题\s*)?[、.．:：]|第\s*[0-9０-９]{1,4}\s*题(?:\s|$)|[0-9０-９]{2,4}\s*[)）])"""
    )
    private val SOURCE_READING_ORDER =
        compareBy<Line> { it.bounds.top }
            .thenBy { it.bounds.left }
            .thenBy { it.order }
            .thenBy { it.blockIndex }
}
