package com.virin.visionquiz.quizlist

import com.virin.visionquiz.dao.Quiz
import java.util.Locale

enum class QuizSearchMode(val label: String) {
    FUZZY("近似"),
    KEYWORD("关键词")
}

data class KeywordSearchScope(
    val includePrompt: Boolean = true,
    val includeAnswers: Boolean = true
) {
    val hasAny: Boolean
        get() = includePrompt || includeAnswers

    fun label(): String {
        return when {
            includePrompt && includeAnswers -> "题目+答案"
            includePrompt -> "题目"
            includeAnswers -> "答案"
            else -> "未选择范围"
        }
    }
}

object QuizKeywordSearch {
    fun matches(quiz: Quiz, query: String, scope: KeywordSearchScope): Boolean {
        val normalizedQuery = normalize(query)
        if (normalizedQuery.isBlank() || !scope.hasAny) return false

        return (scope.includePrompt && containsNormalized(quiz.prompt, normalizedQuery)) ||
            (scope.includeAnswers && quiz.options.any { containsNormalized(it, normalizedQuery) })
    }

    fun findRanges(text: String, query: String): List<IntRange> {
        val normalizedQuery = normalize(query)
        if (text.isBlank() || normalizedQuery.isBlank()) return emptyList()

        val normalizedText = normalizeWithMap(text)
        val source = normalizedText.text
        val ranges = mutableListOf<IntRange>()
        var startIndex = source.indexOf(normalizedQuery)
        while (startIndex >= 0) {
            val endIndex = startIndex + normalizedQuery.length - 1
            val originalStart = normalizedText.originalIndexes[startIndex]
            val originalEnd = normalizedText.originalIndexes[endIndex]
            ranges += originalStart..originalEnd
            startIndex = source.indexOf(normalizedQuery, startIndex + normalizedQuery.length)
        }
        return ranges
    }

    fun normalize(text: String): String {
        return text.trim()
            .map(::normalizeHalfWidthChar)
            .joinToString("")
            .lowercase(Locale.ROOT)
    }

    private fun containsNormalized(text: String, normalizedQuery: String): Boolean {
        return normalize(text).contains(normalizedQuery)
    }

    private fun normalizeWithMap(text: String): NormalizedText {
        val normalized = StringBuilder()
        val originalIndexes = mutableListOf<Int>()
        text.forEachIndexed { index, ch ->
            val lower = normalizeHalfWidthChar(ch).toString().lowercase(Locale.ROOT)
            lower.forEach { normalizedChar ->
                normalized.append(normalizedChar)
                originalIndexes += index
            }
        }
        return NormalizedText(normalized.toString(), originalIndexes)
    }

    private fun normalizeHalfWidthChar(ch: Char): Char {
        return when (ch.code) {
            12288 -> ' '
            in 65281..65374 -> (ch.code - 65248).toChar()
            else -> ch
        }
    }

    private data class NormalizedText(
        val text: String,
        val originalIndexes: List<Int>
    )
}
