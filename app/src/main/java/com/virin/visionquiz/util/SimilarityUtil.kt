package com.virin.visionquiz.util

import com.virin.visionquiz.dao.Quiz
import com.virin.visionquiz.dao.isSupportedStudyType
import java.util.Locale
import java.util.PriorityQueue
import kotlin.coroutines.coroutineContext
import kotlin.math.min
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield

data class QuizSimilarityResult(
    val quiz: Quiz,
    val score: Double,
    val matchedTerms: List<String>
)

data class QuizSimilarityAnalysis(
    val resultsByQuizId: Map<Int, List<QuizSimilarityResult>>,
    val featureExtractionCount: Int,
    val pairEvaluationCount: Int,
    val skippedPairCount: Int
)

const val MAX_SIMILAR_QUIZ_RESULTS = 20

class QuizSimilarityIndex(candidates: List<Quiz>) {
    private val indexed = candidates
        .asSequence()
        .filter { it.isSupportedStudyType() }
        .distinctBy { it.id }
        .map { IndexedQuiz(it, extractFeatures(it)) }
        .toList()
    private val byQuizId = indexed.associateBy { it.quiz.id }
    private val featurePostings = buildFeaturePostings(indexed)

    val size: Int
        get() = indexed.size

    fun findSimilar(
        currentQuiz: Quiz,
        requiredKeywords: String = "",
        maxResults: Int = MAX_SIMILAR_QUIZ_RESULTS
    ): List<QuizSimilarityResult> {
        if (maxResults <= 0) return emptyList()

        val keywords = parseRequiredKeywords(requiredKeywords)
        val current = byQuizId[currentQuiz.id]
            ?.takeIf { it.quiz == currentQuiz }
            ?: IndexedQuiz(currentQuiz, extractFeatures(currentQuiz))
        val source = if (keywords.isEmpty()) {
            automaticCandidateIndexes(current)
                .asSequence()
                .map(indexed::get)
        } else {
            indexed.asSequence().filter { containsAllKeywords(it.features, keywords) }
        }

        return source
            .filter { it.quiz.id != currentQuiz.id }
            .map { candidate ->
                scoreSimilarity(current.features, candidate.features, candidate.quiz, keywords)
            }
            .filter { keywords.isNotEmpty() || it.score >= MIN_AUTOMATIC_SCORE }
            .sortedWith(BEST_RESULT_COMPARATOR)
            .take(maxResults)
            .toList()
    }

    internal fun findSimilarExhaustiveForTesting(
        currentQuiz: Quiz,
        maxResults: Int = MAX_SIMILAR_QUIZ_RESULTS
    ): List<QuizSimilarityResult> {
        val current = byQuizId[currentQuiz.id]
            ?.takeIf { it.quiz == currentQuiz }
            ?: IndexedQuiz(currentQuiz, extractFeatures(currentQuiz))
        return indexed.asSequence()
            .filter { it.quiz.id != currentQuiz.id }
            .map { scoreSimilarity(current.features, it.features, it.quiz, emptyList()) }
            .filter { it.score >= MIN_AUTOMATIC_SCORE }
            .sortedWith(BEST_RESULT_COMPARATOR)
            .take(maxResults)
            .toList()
    }

    suspend fun analyzeAll(
        maxResults: Int = MAX_SIMILAR_QUIZ_RESULTS,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }
    ): QuizSimilarityAnalysis {
        if (indexed.isEmpty() || maxResults <= 0) {
            return QuizSimilarityAnalysis(
                resultsByQuizId = indexed.associate { it.quiz.id to emptyList() },
                featureExtractionCount = indexed.size,
                pairEvaluationCount = 0,
                skippedPairCount = indexed.size * (indexed.size - 1) / 2
            )
        }

        val heaps = Array(indexed.size) {
            PriorityQueue<QuizSimilarityResult>(maxResults, WORST_RESULT_COMPARATOR)
        }
        var evaluatedPairs = 0
        var possiblePairs = 0

        indexed.indices.forEach { leftIndex ->
            coroutineContext.ensureActive()
            val left = indexed[leftIndex]
            val candidateIndexes = automaticCandidateIndexes(left)
                .asSequence()
                .filter { it > leftIndex }
                .sorted()
                .toList()
            possiblePairs += candidateIndexes.size

            candidateIndexes.forEachIndexed { pairOffset, rightIndex ->
                if (pairOffset % CANCELLATION_CHECK_INTERVAL == 0) {
                    coroutineContext.ensureActive()
                }
                val right = indexed[rightIndex]
                val rightForLeft = scoreSimilarity(
                    left.features,
                    right.features,
                    right.quiz,
                    emptyList()
                )
                evaluatedPairs++
                if (rightForLeft.score >= MIN_AUTOMATIC_SCORE) {
                    offerTopResult(heaps[leftIndex], rightForLeft, maxResults)
                    offerTopResult(
                        heaps[rightIndex],
                        QuizSimilarityResult(
                            quiz = left.quiz,
                            score = rightForLeft.score,
                            matchedTerms = rightForLeft.matchedTerms
                        ),
                        maxResults
                    )
                }
            }

            onProgress(leftIndex + 1, indexed.size)
            if (leftIndex % YIELD_INTERVAL == 0) yield()
        }

        val results = indexed.indices.associate { index ->
            indexed[index].quiz.id to heaps[index].toList().sortedWith(BEST_RESULT_COMPARATOR)
        }
        val totalPairs = indexed.size * (indexed.size - 1) / 2
        return QuizSimilarityAnalysis(
            resultsByQuizId = results,
            featureExtractionCount = indexed.size,
            pairEvaluationCount = evaluatedPairs,
            skippedPairCount = (totalPairs - possiblePairs).coerceAtLeast(0)
        )
    }

    private fun automaticCandidateIndexes(current: IndexedQuiz): Set<Int> {
        val candidates = linkedSetOf<Int>()
        current.features.matchFeatures.forEach { feature ->
            featurePostings[feature]?.forEach(candidates::add)
        }
        return candidates
    }
}

fun findSimilarQuizResults(
    currentQuiz: Quiz,
    candidates: List<Quiz>,
    requiredKeywords: String = "",
    maxResults: Int = MAX_SIMILAR_QUIZ_RESULTS
): List<QuizSimilarityResult> {
    return QuizSimilarityIndex(candidates).findSimilar(
        currentQuiz = currentQuiz,
        requiredKeywords = requiredKeywords,
        maxResults = maxResults
    )
}

fun findSimilarQuizzes(
    currentQuiz: Quiz,
    candidates: List<Quiz>,
    maxResults: Int = MAX_SIMILAR_QUIZ_RESULTS
): List<Quiz> {
    return findSimilarQuizResults(currentQuiz, candidates, maxResults = maxResults)
        .map(QuizSimilarityResult::quiz)
}

private data class IndexedQuiz(
    val quiz: Quiz,
    val features: QuizFeatures
)

private data class QuizFeatures(
    val promptFeatures: Set<String>,
    val optionFeatures: Set<String>,
    val exactTerms: Set<String>,
    val normalizedFullText: String
) {
    val matchFeatures: Set<String> = buildSet {
        addAll(promptFeatures)
        addAll(optionFeatures)
        addAll(exactTerms)
    }
}

private fun buildFeaturePostings(indexed: List<IndexedQuiz>): Map<String, IntArray> {
    val mutable = mutableMapOf<String, MutableList<Int>>()
    indexed.forEachIndexed { index, item ->
        item.features.matchFeatures.forEach { feature ->
            mutable.getOrPut(feature) { mutableListOf() }.add(index)
        }
    }
    return mutable.mapValues { (_, indexes) -> indexes.toIntArray() }
}

private fun extractFeatures(quiz: Quiz): QuizFeatures {
    val promptParts = extractTextFeatures(quiz.prompt)
    val optionParts = quiz.options
        .asSequence()
        .filterNot(::isLowInformationOption)
        .map(::extractTextFeatures)
        .toList()

    return QuizFeatures(
        promptFeatures = promptParts.features,
        optionFeatures = optionParts.flatMapTo(linkedSetOf()) { it.features },
        exactTerms = buildSet {
            addAll(promptParts.exactTerms)
            optionParts.forEach { addAll(it.exactTerms) }
        },
        normalizedFullText = normalizeForContains(
            buildString {
                append(quiz.prompt)
                quiz.options.filterNot(::isLowInformationOption).forEach {
                    append(' ')
                    append(it)
                }
            }
        )
    )
}

private data class TextFeatures(
    val features: Set<String>,
    val exactTerms: Set<String>
)

private fun extractTextFeatures(text: String): TextFeatures {
    val normalized = normalizeWidth(text).lowercase(Locale.ROOT)
    val features = linkedSetOf<String>()
    val exactTerms = linkedSetOf<String>()

    TOKEN_REGEX.findAll(normalized).forEach { match ->
        val token = match.value
        if (token.first().isLetterOrDigit() && token.none(::isCjkCharacter)) {
            if (token.length >= 2 && token !in STOP_TERMS) {
                features += token
                exactTerms += token
            }
            return@forEach
        }

        splitMeaningfulChineseRuns(token).forEach { cleaned ->
            if (cleaned.length >= 2) {
                for (size in 2..min(3, cleaned.length)) {
                    for (start in 0..cleaned.length - size) {
                        val gram = cleaned.substring(start, start + size)
                        if (gram !in STOP_FEATURES) features += gram
                    }
                }
                for (size in 4..min(MAX_EXACT_TERM_LENGTH, cleaned.length)) {
                    for (start in 0..cleaned.length - size) {
                        val phrase = cleaned.substring(start, start + size)
                        if (phrase !in STOP_TERMS) exactTerms += phrase
                    }
                }
            }
        }
    }

    return TextFeatures(features, exactTerms)
}

private fun scoreSimilarity(
    current: QuizFeatures,
    candidate: QuizFeatures,
    quiz: Quiz,
    requiredKeywords: List<String>
): QuizSimilarityResult {
    val promptScore = diceSimilarity(current.promptFeatures, candidate.promptFeatures)
    val optionScore = diceSimilarity(current.optionFeatures, candidate.optionFeatures)
    val fullTextScore = JaroWinklerDistance.computeJaroWinklerDistance(
        current.normalizedFullText,
        candidate.normalizedFullText
    )

    val sharedTerms = current.exactTerms
        .intersect(candidate.exactTerms)
        .filterNot { it in STOP_TERMS }
        .sortedWith(compareByDescending<String> { it.length }.thenBy { it })
    val distinctTerms = removeContainedTerms(sharedTerms)
    val exactTermBoost = distinctTerms.sumOf { term ->
        when {
            term.any(::isCjkCharacter) && term.length >= 4 ->
                ((term.length - 3) * 0.08).coerceAtMost(MAX_SINGLE_TERM_BOOST)
            term.length >= 3 -> 0.16
            else -> 0.1
        }
    }.coerceAtMost(MAX_EXACT_TERM_BOOST)

    val keywordBoost = if (requiredKeywords.isEmpty()) 0.0 else KEYWORD_MATCH_BOOST
    val score = (
        promptScore * PROMPT_WEIGHT +
            optionScore * OPTION_WEIGHT +
            fullTextScore * FULL_TEXT_WEIGHT +
            exactTermBoost +
            keywordBoost
        ).coerceAtMost(1.0)

    val matchedTerms = buildList {
        addAll(requiredKeywords)
        distinctTerms.forEach { if (it !in this) add(it) }
    }.take(MAX_MATCHED_TERMS)

    return QuizSimilarityResult(quiz, score, matchedTerms)
}

private fun offerTopResult(
    heap: PriorityQueue<QuizSimilarityResult>,
    result: QuizSimilarityResult,
    maxResults: Int
) {
    if (heap.size < maxResults) {
        heap.offer(result)
        return
    }
    val worst = heap.peek() ?: return
    if (BEST_RESULT_COMPARATOR.compare(result, worst) < 0) {
        heap.poll()
        heap.offer(result)
    }
}

private fun parseRequiredKeywords(query: String): List<String> {
    return normalizeWidth(query).trim()
        .split(WHITESPACE_REGEX)
        .map(::normalizeForContains)
        .filter { it.isNotBlank() }
        .distinct()
}

private fun containsAllKeywords(features: QuizFeatures, keywords: List<String>): Boolean {
    return keywords.all(features.normalizedFullText::contains)
}

private fun normalizeForContains(text: String): String {
    return buildString(text.length) {
        normalizeWidth(text).lowercase(Locale.ROOT).forEach { ch ->
            if (ch.isLetterOrDigit() || isCjkCharacter(ch)) append(ch)
        }
    }
}

private fun normalizeWidth(text: String): String {
    return text.map { ch ->
        when (ch.code) {
            12288 -> ' '
            in 65281..65374 -> (ch.code - 65248).toChar()
            else -> ch
        }
    }.joinToString("")
}

private fun splitMeaningfulChineseRuns(text: String): List<String> {
    var result = text
    STOP_PHRASES.forEach { phrase -> result = result.replace(phrase, " ") }
    return result.split(WHITESPACE_REGEX).filter { it.isNotBlank() }
}

private fun removeContainedTerms(terms: List<String>): List<String> {
    val selected = mutableListOf<String>()
    terms.forEach { term ->
        if (selected.none { it.contains(term) }) selected += term
    }
    return selected
}

private fun diceSimilarity(left: Set<String>, right: Set<String>): Double {
    if (left.isEmpty() || right.isEmpty()) return 0.0
    return 2.0 * left.count { it in right } / (left.size + right.size)
}

private fun isLowInformationOption(option: String): Boolean {
    return normalizeForContains(option) in LOW_INFORMATION_OPTIONS
}

private fun isCjkCharacter(ch: Char): Boolean {
    return ch in '\u4E00'..'\u9FFF' || ch in '\u3400'..'\u4DBF'
}

private const val PROMPT_WEIGHT = 0.58
private const val OPTION_WEIGHT = 0.08
private const val FULL_TEXT_WEIGHT = 0.12
private const val MAX_SINGLE_TERM_BOOST = 0.4
private const val MAX_EXACT_TERM_BOOST = 0.4
private const val KEYWORD_MATCH_BOOST = 0.08
private const val MIN_AUTOMATIC_SCORE = 0.16
private const val MAX_EXACT_TERM_LENGTH = 8
private const val MAX_MATCHED_TERMS = 5
private const val CANCELLATION_CHECK_INTERVAL = 128
private const val YIELD_INTERVAL = 8

private val BEST_RESULT_COMPARATOR =
    compareByDescending<QuizSimilarityResult> { it.score }.thenBy { it.quiz.id }
private val WORST_RESULT_COMPARATOR =
    compareBy<QuizSimilarityResult> { it.score }.thenByDescending { it.quiz.id }
private val TOKEN_REGEX = Regex("[a-z0-9]+|[\u3400-\u9fff]+")
private val WHITESPACE_REGEX = Regex("\\s+")
private val LOW_INFORMATION_OPTIONS = setOf("正确", "错误", "对", "错", "是", "否")
private val STOP_PHRASES = listOf(
    "下列",
    "关于",
    "以下",
    "说法",
    "正确的是",
    "错误的是",
    "不正确的是",
    "不符合的是",
    "符合的是",
    "有哪些",
    "是什么",
    "为什么",
    "多少",
    "如何"
)
private val STOP_TERMS = (
    STOP_PHRASES +
        LOW_INFORMATION_OPTIONS +
        listOf("选择", "选项", "题目", "答案", "其中", "属于", "可以", "应当")
    ).toSet()
private val STOP_FEATURES = buildSet {
    STOP_TERMS.filter { term -> term.any(::isCjkCharacter) }.forEach { term ->
        for (size in 2..min(3, term.length)) {
            for (start in 0..term.length - size) {
                add(term.substring(start, start + size))
            }
        }
    }
}
