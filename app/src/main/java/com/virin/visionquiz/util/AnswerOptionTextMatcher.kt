package com.virin.visionquiz.util

import com.virin.visionquiz.dao.QuizManager
import kotlin.math.max

internal object AnswerOptionTextMatcher {
    const val MATCH_EXACT = 0
    const val MATCH_CONTAINS_OPTION = 1
    const val MATCH_OPTION_CONTAINS_CANDIDATE = 2
    const val MATCH_FUZZY = 3
    const val MATCH_PREFIX_ANCHOR = 4

    private const val MIN_CONTAINS_OPTION_LENGTH = 2
    private const val SHORT_OPTION_EXACT_MATCH_MAX_LENGTH = 4
    private const val MIN_FUZZY_OPTION_LENGTH = 5
    private const val MIN_FUZZY_SCORE = 0.78
    private const val MIN_FUZZY_SCORE_FOR_SHORT_TEXT = 0.84
    private val WHITESPACE_REGEX = Regex("\\s+")
    private val OPTION_PREFIX_REGEX = Regex("^[A-Ha-h](?:[、.．)）]\\s*|\\s+)")

    fun candidateScore(candidateText: String, normalizedOption: String): Int? {
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
        if (isFuzzyOptionMatch(normalizedCandidate, normalizedOption)) {
            return MATCH_FUZZY
        }
        return null
    }

    fun normalizeOptionText(text: String): String {
        return QuizManager.normalizeAnswerText(
            OPTION_PREFIX_REGEX.replace(
                text.replace(WHITESPACE_REGEX, " ").trim(),
                ""
            )
        )
    }

    private fun isFuzzyOptionMatch(candidate: String, option: String): Boolean {
        if (candidate.length < MIN_FUZZY_OPTION_LENGTH || option.length < MIN_FUZZY_OPTION_LENGTH) {
            return false
        }
        val maxLength = max(candidate.length, option.length)
        val minScore = if (maxLength <= 8) {
            MIN_FUZZY_SCORE_FOR_SHORT_TEXT
        } else {
            MIN_FUZZY_SCORE
        }
        return editSimilarity(candidate, option) >= minScore
    }

    private fun editSimilarity(left: String, right: String): Double {
        val maxLength = max(left.length, right.length)
        if (maxLength == 0) {
            return 0.0
        }
        val distance = DamerauLevenshteinDistance.computeDamerauLevenshteinDistance(left, right)
        return 1.0 - distance.toDouble() / maxLength
    }
}
