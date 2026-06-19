package com.virin.visionquiz.vision.questiondetector

import com.virin.visionquiz.dao.QuizManager
import com.virin.visionquiz.util.AnswerOptionTextMatcher
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
        val answerBounds: List<Bounds>,
        val isAnswerPartiallyMatched: Boolean = false
    )

    fun locate(
        question: QuestionMatch,
        candidates: List<TextCandidate>,
        nextQuestionStartOrder: Int?,
        imageHeight: Int,
        minMatchScore: Double = QuizManager.DEFAULT_MIN_MATCH_SCORE
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
            searchCandidates,
            minMatchScore
        )
        if (allOptions.size == question.options.size) {
            val answers = question.answerIndices
                .sorted()
                .mapNotNull(allOptions::getOrNull)
            return Result(
                optionBounds = allOptions,
                answerBounds = answers,
                isAnswerPartiallyMatched = isPartialAnswerMatch(question, answers)
            )
        }

        val answerOptions = question.answerIndices
            .sorted()
            .mapNotNull { index ->
                question.options.getOrNull(index)?.let { IndexedOption(index, it) }
            }
        val answerBounds = locateTexts(answerOptions, searchCandidates, minMatchScore)
        return Result(
            optionBounds = emptyList(),
            answerBounds = answerBounds,
            isAnswerPartiallyMatched = isPartialAnswerMatch(question, answerBounds)
        )
    }

    private fun isPartialAnswerMatch(question: QuestionMatch, answerBounds: List<Bounds>): Boolean {
        return question.answerIndices.size > 1 &&
            answerBounds.isNotEmpty() &&
            answerBounds.size < question.answerIndices.size
    }

    private fun locateTexts(
        options: List<IndexedOption>,
        candidates: List<TextCandidate>,
        minMatchScore: Double
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
                    val score = candidateScore(candidate.text, normalizedOption, minMatchScore)
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
                            buildWrappedLineMatch(
                                candidate = candidate,
                                candidates = candidates,
                                normalizedOption = normalizedOption,
                                minMatchScore = minMatchScore
                            )
                        }
                )
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
                                candidates = candidates,
                                normalizedOption = normalizedOption,
                                minMatchScore = minMatchScore
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

    private fun buildWrappedLineMatch(
        candidate: TextCandidate,
        candidates: List<TextCandidate>,
        normalizedOption: String,
        minMatchScore: Double
    ): CandidateMatch? {
        var selectedBounds = candidate.bounds
        val combinedText = StringBuilder(candidate.text)
        var continuationCount = 0
        val nextLines = candidates
            .asSequence()
            .filter { it.order > candidate.order }
            .filter { it.order - candidate.order <= MAX_WRAPPED_LINE_ORDER_DISTANCE }
            .sortedWith(compareBy<TextCandidate> { it.order }.thenBy { it.bounds.top })
            .toList()
        for (nextLine in nextLines) {
            if (OPTION_PREFIX_REGEX.containsMatchIn(nextLine.text.trim())) {
                break
            }
            if (!isLikelyWrappedContinuation(selectedBounds, nextLine.bounds)) {
                continue
            }
            continuationCount++
            combinedText.append(' ').append(nextLine.text)
            selectedBounds = selectedBounds.union(nextLine.bounds)
            val normalizedCandidate = normalizeOptionText(combinedText.toString())
            val score = candidateScore(
                combinedText.toString(),
                normalizedOption,
                minMatchScore
            )
            if (score == null) {
                if (continuationCount >= MAX_WRAPPED_CONTINUATION_LINES) {
                    break
                }
                continue
            }
            return CandidateMatch(
                candidate = candidate,
                selectedBounds = selectedBounds,
                score = score,
                optionPrefixRank =
                    if (OPTION_PREFIX_REGEX.containsMatchIn(candidate.text.trim())) 0 else 1,
                normalizedLengthDifference = abs(normalizedCandidate.length - normalizedOption.length)
            )
        }
        return null
    }

    private fun isLikelyWrappedContinuation(first: Bounds, second: Bounds): Boolean {
        val verticalGap = second.top - first.bottom
        if (verticalGap < 0 || verticalGap > first.height * MAX_WRAPPED_VERTICAL_GAP_HEIGHT_MULTIPLIER) {
            return false
        }
        val leftDelta = abs(second.left - first.left)
        val rightDelta = abs(second.right - first.right)
        return leftDelta <= first.height * MAX_WRAPPED_LEFT_DELTA_HEIGHT_MULTIPLIER ||
            second.left in first.left..first.right ||
            rightDelta <= first.height * MAX_WRAPPED_RIGHT_DELTA_HEIGHT_MULTIPLIER
    }

    private fun buildPrefixAnchoredMatch(
        optionIndex: Int,
        candidate: TextCandidate,
        candidates: List<TextCandidate>,
        normalizedOption: String,
        minMatchScore: Double
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
            val score = if (normalizedOption.length <= MAX_BLIND_PREFIX_ANCHOR_OPTION_LENGTH) {
                MATCH_EXPECTED_PREFIX
            } else {
                candidateScore(trimmedText, normalizedOption, minMatchScore) ?: return null
            }
            return CandidateMatch(
                candidate = candidate,
                selectedBounds = candidate.bounds,
                score = score,
                optionPrefixRank = 0,
                normalizedLengthDifference = abs(
                    normalizeOptionText(trimmedText).length - normalizedOption.length
                )
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
        val combinedText = "$trimmedText ${adjacent.text}"
        val score = if (normalizedOption.length <= MAX_BLIND_PREFIX_ANCHOR_OPTION_LENGTH) {
            MATCH_EXPECTED_PREFIX
        } else {
            candidateScore(combinedText, normalizedOption, minMatchScore) ?: return null
        }
        return CandidateMatch(
            candidate = candidate,
            selectedBounds = candidate.bounds.union(adjacent.bounds),
            score = score,
            optionPrefixRank = 0,
            normalizedLengthDifference = abs(
                normalizeOptionText(combinedText).length - normalizedOption.length
            )
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

    private fun candidateScore(
        candidateText: String,
        normalizedOption: String,
        minMatchScore: Double
    ): Int? {
        return AnswerOptionTextMatcher.candidateScore(
            candidateText,
            normalizedOption,
            minMatchScore
        )
    }

    private fun normalizeOptionText(text: String): String {
        return AnswerOptionTextMatcher.normalizeOptionText(text)
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
    private const val MAX_WRAPPED_LINE_ORDER_DISTANCE = 2_200
    private const val MAX_WRAPPED_CONTINUATION_LINES = 2
    private const val MAX_HORIZONTAL_GAP_HEIGHT_MULTIPLIER = 4
    private const val MAX_WRAPPED_VERTICAL_GAP_HEIGHT_MULTIPLIER = 1.4f
    private const val MAX_WRAPPED_LEFT_DELTA_HEIGHT_MULTIPLIER = 2.5f
    private const val MAX_WRAPPED_RIGHT_DELTA_HEIGHT_MULTIPLIER = 2.5f
    private const val MIN_VERTICAL_OVERLAP_RATIO = 0.5f
    private const val MAX_BLIND_PREFIX_ANCHOR_OPTION_LENGTH = 4
    private const val MAX_RECT_HEIGHT_RATIO = 0.18f
    private const val MAX_VERTICAL_SPAN_RATIO = 0.5f
    private val MATCH_EXPECTED_PREFIX = AnswerOptionTextMatcher.MATCH_PREFIX_ANCHOR
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
