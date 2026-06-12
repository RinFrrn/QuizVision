package com.virin.visionquiz.vision.questiondetector

import com.virin.visionquiz.dao.QuizManager
import kotlin.math.abs

internal object OcrOptionLocator {

    data class Bounds(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    ) {
        val width: Int get() = (right - left).coerceAtLeast(0)
        val height: Int get() = (bottom - top).coerceAtLeast(0)
        val area: Int get() = width * height
    }

    data class TextCandidate(
        val text: String,
        val bounds: Bounds,
        val order: Int
    )

    data class QuestionMatch(
        val options: List<String>,
        val answerIndices: Set<Int>,
        val bounds: Bounds,
        val startOrder: Int,
        val endOrder: Int
    )

    data class Result(
        val optionBounds: List<Bounds>,
        val answerBounds: List<Bounds>
    )

    fun locate(
        question: QuestionMatch,
        candidates: List<TextCandidate>,
        nextQuestionStartOrder: Int?,
        imageHeight: Int
    ): Result {
        if (question.options.isEmpty() || imageHeight <= 0) {
            return Result(emptyList(), emptyList())
        }
        val maxBottom = question.bounds.bottom + imageHeight * MAX_VERTICAL_SPAN_RATIO
        val boundedCandidates = candidates
            .asSequence()
            .filter { it.order > question.endOrder }
            .filter { nextQuestionStartOrder == null || it.order < nextQuestionStartOrder }
            .filter { it.bounds.top >= question.bounds.top }
            .filter { it.bounds.top <= maxBottom }
            .filter { it.bounds.height <= imageHeight * MAX_RECT_HEIGHT_RATIO }
            .sortedWith(compareBy<TextCandidate> { it.order }.thenBy { it.bounds.left })
            .toList()
        val searchCandidates = if (nextQuestionStartOrder == null) {
            boundedCandidates.take(MAX_SEARCH_CANDIDATE_COUNT)
        } else {
            boundedCandidates
        }

        val allOptions = locateTexts(
            question.options.mapIndexed { index, text -> IndexedOption(index, text) },
            searchCandidates
        )
        if (allOptions.size == question.options.size) {
            val answers = question.answerIndices
                .sorted()
                .mapNotNull(allOptions::getOrNull)
            return Result(allOptions, answers)
        }

        val answerOptions = question.answerIndices
            .sorted()
            .mapNotNull { index ->
                question.options.getOrNull(index)?.let { IndexedOption(index, it) }
            }
        return Result(
            optionBounds = emptyList(),
            answerBounds = locateTexts(answerOptions, searchCandidates)
        )
    }

    private fun locateTexts(
        options: List<IndexedOption>,
        candidates: List<TextCandidate>
    ): List<Bounds> {
        val usedOrders = mutableSetOf<Int>()
        val usedBounds = mutableSetOf<Bounds>()
        return options.mapNotNull { option ->
            val normalizedOption = normalizeOptionText(option.text)
            if (normalizedOption.isBlank()) {
                return@mapNotNull null
            }
            candidates
                .asSequence()
                .filterNot { it.order in usedOrders }
                .filterNot { candidate ->
                    usedBounds.any { used -> used.intersects(candidate.bounds) }
                }
                .mapNotNull { candidate ->
                    val score = candidateScore(candidate.text, normalizedOption)
                        ?: return@mapNotNull null
                    CandidateMatch(
                        candidate = candidate,
                        selectedBounds = candidate.bounds,
                        score = score,
                        optionPrefixRank =
                            if (OPTION_PREFIX_REGEX.containsMatchIn(candidate.text.trim())) 0 else 1,
                        normalizedLengthDifference = abs(
                            normalizeOptionText(candidate.text).length - normalizedOption.length
                        )
                    )
                }
                .plus(
                    candidates.asSequence()
                        .filterNot { it.order in usedOrders }
                        .filterNot { candidate ->
                            usedBounds.any { used -> used.intersects(candidate.bounds) }
                        }
                        .mapNotNull { candidate ->
                            buildPrefixAnchoredMatch(
                                optionIndex = option.index,
                                candidate = candidate,
                                candidates = candidates
                            )
                        }
                )
                .minWithOrNull(
                    compareBy<CandidateMatch> { it.score }
                        .thenBy { it.optionPrefixRank }
                        .thenBy { it.normalizedLengthDifference }
                        .thenBy { it.candidate.bounds.area }
                        .thenBy { it.candidate.order }
                        .thenBy { it.candidate.bounds.left }
                )
                ?.also {
                    usedOrders += it.candidate.order
                    usedBounds += it.selectedBounds
                }
                ?.selectedBounds
        }
    }

    private fun buildPrefixAnchoredMatch(
        optionIndex: Int,
        candidate: TextCandidate,
        candidates: List<TextCandidate>
    ): CandidateMatch? {
        val expectedLabel = ('A'.code + optionIndex).toChar()
        val trimmedText = candidate.text.trim()
        val prefixedMatch =
            PREFIX_WITH_CONTENT_REGEX.matchEntire(trimmedText)
                ?: COMPACT_CJK_PREFIX_REGEX.matchEntire(trimmedText)
        if (prefixedMatch != null) {
            val label = prefixedMatch.groupValues[1].uppercase().single()
            val content = prefixedMatch.groupValues[2]
            if (
                label != expectedLabel ||
                EMBEDDED_OPTION_PREFIX_REGEX.containsMatchIn(content)
            ) {
                return null
            }
            return CandidateMatch(
                candidate = candidate,
                selectedBounds = candidate.bounds,
                score = MATCH_EXPECTED_PREFIX,
                optionPrefixRank = 0,
                normalizedLengthDifference = 0
            )
        }

        val standaloneMatch = STANDALONE_LABEL_REGEX.matchEntire(trimmedText) ?: return null
        val label = standaloneMatch.groupValues[1].uppercase().single()
        if (label != expectedLabel) {
            return null
        }
        val adjacent = candidates
            .asSequence()
            .filter { it.order > candidate.order }
            .filter { it.order - candidate.order <= MAX_ADJACENT_ORDER_DISTANCE }
            .filter { it.bounds.left >= candidate.bounds.right }
            .filter { verticalOverlapRatio(candidate.bounds, it.bounds) >= MIN_VERTICAL_OVERLAP_RATIO }
            .filter {
                it.bounds.left - candidate.bounds.right <=
                    candidate.bounds.height * MAX_HORIZONTAL_GAP_HEIGHT_MULTIPLIER
            }
            .minWithOrNull(
                compareBy<TextCandidate> { it.order }
                    .thenBy { it.bounds.left - candidate.bounds.right }
                    .thenBy { it.bounds.area }
            ) ?: return null
        return CandidateMatch(
            candidate = candidate,
            selectedBounds = candidate.bounds.union(adjacent.bounds),
            score = MATCH_EXPECTED_PREFIX,
            optionPrefixRank = 0,
            normalizedLengthDifference = 0
        )
    }

    private fun verticalOverlapRatio(first: Bounds, second: Bounds): Float {
        val overlap = (minOf(first.bottom, second.bottom) - maxOf(first.top, second.top))
            .coerceAtLeast(0)
        val minHeight = minOf(first.height, second.height).coerceAtLeast(1)
        return overlap.toFloat() / minHeight
    }

    private fun Bounds.union(other: Bounds): Bounds {
        return Bounds(
            left = minOf(left, other.left),
            top = minOf(top, other.top),
            right = maxOf(right, other.right),
            bottom = maxOf(bottom, other.bottom)
        )
    }

    private fun Bounds.intersects(other: Bounds): Boolean {
        return left < other.right &&
            other.left < right &&
            top < other.bottom &&
            other.top < bottom
    }

    private fun candidateScore(candidateText: String, normalizedOption: String): Int? {
        val normalizedCandidate = normalizeOptionText(candidateText)
        if (normalizedCandidate.isBlank()) {
            return null
        }
        if (normalizedCandidate == normalizedOption) {
            return MATCH_EXACT
        }
        if (normalizedOption.length <= SHORT_OPTION_EXACT_MATCH_MAX_LENGTH) {
            return null
        }
        if (
            normalizedCandidate.length < MIN_CONTAINS_OPTION_LENGTH ||
            normalizedOption.length < MIN_CONTAINS_OPTION_LENGTH
        ) {
            return null
        }
        if (normalizedCandidate.contains(normalizedOption)) {
            return MATCH_CONTAINS_OPTION
        }
        if (
            normalizedOption.contains(normalizedCandidate) &&
            normalizedCandidate.length * 2 >= normalizedOption.length
        ) {
            return MATCH_OPTION_CONTAINS_CANDIDATE
        }
        return null
    }

    private fun normalizeOptionText(text: String): String {
        return QuizManager.normalizeAnswerText(
            OPTION_PREFIX_REGEX.replace(
                text.replace(WHITESPACE_REGEX, " ").trim(),
                ""
            )
        )
    }

    private data class CandidateMatch(
        val candidate: TextCandidate,
        val selectedBounds: Bounds,
        val score: Int,
        val optionPrefixRank: Int,
        val normalizedLengthDifference: Int
    )

    private data class IndexedOption(
        val index: Int,
        val text: String
    )

    private const val MAX_SEARCH_CANDIDATE_COUNT = 24
    private const val MAX_ADJACENT_ORDER_DISTANCE = 8
    private const val MAX_HORIZONTAL_GAP_HEIGHT_MULTIPLIER = 4
    private const val MIN_VERTICAL_OVERLAP_RATIO = 0.5f
    private const val MIN_CONTAINS_OPTION_LENGTH = 2
    private const val SHORT_OPTION_EXACT_MATCH_MAX_LENGTH = 4
    private const val MAX_RECT_HEIGHT_RATIO = 0.18f
    private const val MAX_VERTICAL_SPAN_RATIO = 0.5f
    private const val MATCH_EXACT = 0
    private const val MATCH_CONTAINS_OPTION = 1
    private const val MATCH_OPTION_CONTAINS_CANDIDATE = 2
    private const val MATCH_EXPECTED_PREFIX = 3
    private val WHITESPACE_REGEX = Regex("\\s+")
    private val OPTION_PREFIX_REGEX = Regex("^[A-Ha-h](?:[、.．)）]\\s*|\\s+)")
    private val PREFIX_WITH_CONTENT_REGEX =
        Regex("^\\s*([A-Ha-h])(?:[、.．)）:：]\\s*|\\s+)(.+?)\\s*$")
    private val COMPACT_CJK_PREFIX_REGEX =
        Regex("^\\s*([A-Ha-h])([\\u3400-\\u9FFF].*?)\\s*$")
    private val STANDALONE_LABEL_REGEX =
        Regex("^\\s*([A-Ha-h])(?:[、.．)）:：])?\\s*$")
    private val EMBEDDED_OPTION_PREFIX_REGEX =
        Regex("(?:^|\\s)[A-Ha-h](?:[、.．)）:：]\\s*)")
}
