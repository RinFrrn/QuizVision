package com.virin.visionquiz.util

import com.virin.visionquiz.dao.Quiz
import com.virin.visionquiz.dao.isSupportedStudyType

/**
 * Selects up to [maxResults] quizzes from [candidates] that are most similar
 * to [currentQuiz], based on keyword overlap from prompt, options, and answer text.
 * Only supported-study-type quizzes are considered; [currentQuiz] is excluded.
 */
fun findSimilarQuizzes(
    currentQuiz: Quiz,
    candidates: List<Quiz>,
    maxResults: Int = 3
): List<Quiz> {
    val currentKeywords = extractKeywords(currentQuiz)
    if (currentKeywords.isEmpty()) return emptyList()

    return candidates
        .filter { it.id != currentQuiz.id && it.isSupportedStudyType() }
        .map { candidate ->
            candidate to jaccardSimilarity(currentKeywords, extractKeywords(candidate))
        }
        .filter { it.second > 0.0 }
        .sortedByDescending { it.second }
        .take(maxResults)
        .map { it.first }
}

private fun extractKeywords(quiz: Quiz): Set<String> {
    val text = buildString {
        append(quiz.prompt)
        for (option in quiz.options) {
            append(' ')
            append(option)
        }
        append(' ')
        append(quiz.answer.sorted().joinToString("") { ('A' + it).toString() })
    }
    return tokenize(text)
}

private val SPLIT_REGEX = Regex("[\\s,;:!?.,\u3001\u3002\uFF0C\uFF0E\uFF1F\uFF01\uFF1B\uFF1A\uFF08\uFF09()\\[\\]\u3010\u3011]+")

private fun tokenize(text: String): Set<String> {
    return text.split(SPLIT_REGEX)
        .map { it.trim() }
        .filter { it.length >= 2 }
        .toSet()
}

private fun jaccardSimilarity(a: Set<String>, b: Set<String>): Double {
    if (a.isEmpty() || b.isEmpty()) return 0.0
    val intersection = a.intersect(b).size.toDouble()
    val union = a.union(b).size.toDouble()
    return if (union == 0.0) 0.0 else intersection / union
}
