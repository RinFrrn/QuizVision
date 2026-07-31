package com.virin.visionquiz.vision.questiondetector

import com.virin.visionquiz.util.AnswerOptionTextMatcher
import com.virin.visionquiz.util.OptionCandidateAssignment

/**
 * Scores nearby OCR option text as supporting evidence for a question match.
 *
 * Short options are exact-match only (enforced by [AnswerOptionTextMatcher]) and at least two
 * distinct short options must match before they contribute any evidence. This prevents a common
 * one- or two-character word from rescuing an unrelated question.
 */
internal object OcrOptionSupportScorer {

    fun score(
        options: List<String>,
        nearbyTexts: List<String>,
        minMatchScore: Double,
        allowShortOptions: Boolean
    ): Double {
        if (options.isEmpty() || nearbyTexts.isEmpty()) {
            return 0.0
        }

        val normalizedOptions = options.map(AnswerOptionTextMatcher::normalizeOptionText)
        val parsedCandidates = nearbyTexts.mapIndexed { candidateIndex, text ->
            ParsedCandidate(
                index = candidateIndex,
                text = text,
                explicitOptionIndex = parseOptionIndex(text),
                compactOptionIndex = parseCompactOptionIndex(text)
            )
        }
        val trustedCompactLabels = parsedCandidates
            .mapNotNull(ParsedCandidate::compactOptionIndex)
            .distinct()
            .sorted()
            .let { labels ->
                labels.size >= MIN_TRUSTED_COMPACT_LABEL_COUNT &&
                    labels.first() == 0 &&
                    labels.zipWithNext().all { (first, second) -> second == first + 1 }
            }
        val candidates = parsedCandidates.map { candidate ->
            val useCompactLabel =
                candidate.explicitOptionIndex == null &&
                    candidate.compactOptionIndex != null &&
                    trustedCompactLabels
            NearbyCandidate(
                index = candidate.index,
                text = if (useCompactLabel) {
                    candidate.text.trimStart().drop(1).trimStart()
                } else {
                    candidate.text
                },
                optionIndex = candidate.explicitOptionIndex
                    ?: candidate.compactOptionIndex.takeIf { useCompactLabel }
            )
        }
        val matchesByOption = normalizedOptions.mapIndexed { optionIndex, normalizedOption ->
            val isShortOption = normalizedOption.length < MIN_LONG_OPTION_LENGTH
            if (
                normalizedOption.isBlank() ||
                (!allowShortOptions && normalizedOption.length < MIN_LONG_OPTION_LENGTH)
            ) {
                return@mapIndexed emptyList()
            }
            candidates.mapNotNull { candidate ->
                if (
                    candidate.optionIndex != null &&
                    candidate.optionIndex != optionIndex
                ) {
                    return@mapNotNull null
                }
                if (isShortOption && candidate.optionIndex == null) {
                    return@mapNotNull null
                }
                val rank = AnswerOptionTextMatcher.candidateScore(
                    candidateText = candidate.text,
                    normalizedOption = normalizedOption,
                    minMatchScore = minMatchScore
                ) ?: return@mapNotNull null
                OptionEvidence(
                    candidateIndex = candidate.index,
                    rank = rank,
                    isShort = isShortOption
                )
            }
        }

        val assignment = OptionCandidateAssignment.solve(
            candidatesByOption = matchesByOption,
            comparator = compareBy<OptionEvidence> { it.rank }.thenBy { it.candidateIndex },
            conflicts = { first, second -> first.candidateIndex == second.candidateIndex }
        )
        val matched = assignment.filterNotNull().let { assigned ->
            if (assigned.count(OptionEvidence::isShort) == 1) {
                assigned.filterNot(OptionEvidence::isShort)
            } else {
                assigned
            }
        }
        if (matched.isEmpty()) {
            return 0.0
        }
        return when {
            matched.size >= 3 -> 0.10
            matched.size == 2 -> 0.07
            else -> 0.03
        }
    }

    private data class OptionEvidence(
        val candidateIndex: Int,
        val rank: Int,
        val isShort: Boolean
    )

    private data class NearbyCandidate(
        val index: Int,
        val text: String,
        val optionIndex: Int?
    )

    private data class ParsedCandidate(
        val index: Int,
        val text: String,
        val explicitOptionIndex: Int?,
        val compactOptionIndex: Int?
    )

    private fun parseOptionIndex(text: String): Int? {
        return OPTION_LABEL_REGEX.find(text)
            ?.groupValues
            ?.get(1)
            ?.singleOrNull()
            ?.let(::optionIndex)
    }

    private fun parseCompactOptionIndex(text: String): Int? {
        return COMPACT_OPTION_LABEL_REGEX.find(text)
            ?.groupValues
            ?.get(1)
            ?.singleOrNull()
            ?.let(::optionIndex)
    }

    private fun optionIndex(label: Char): Int {
        val halfWidthLabel = when (label.code) {
            in 65313..65320, in 65345..65352 -> (label.code - 65248).toChar()
            else -> label
        }
        return halfWidthLabel.uppercaseChar() - 'A'
    }

    const val MIN_LONG_OPTION_LENGTH = 5

    private const val MIN_TRUSTED_COMPACT_LABEL_COUNT = 2
    private val OPTION_LABEL_REGEX =
        Regex("""^\s*([A-Ha-hＡ-Ｈａ-ｈ])(?:[、.．:：)）]\s*|\s+)""")
    private val COMPACT_OPTION_LABEL_REGEX =
        Regex("""^\s*([A-Ha-hＡ-Ｈａ-ｈ])(?=[\u3400-\u9FFF])""")
}
