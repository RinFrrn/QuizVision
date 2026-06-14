package com.virin.visionquiz.util

import com.virin.visionquiz.dao.Quiz
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SimilarityUtilTest {
    private fun quiz(
        id: Int,
        prompt: String,
        options: List<String> = listOf("正确", "错误"),
        answer: Set<Int> = setOf(0),
        isMultipleChoice: Boolean = false,
        libraryId: Int = 1
    ) = Quiz(
        id = id,
        prompt = prompt,
        options = options,
        answer = answer,
        isMultipleChoice = isMultipleChoice,
        questionType = if (options == listOf("正确", "错误")) "判断" else null,
        libraryId = libraryId
    )

    @Test
    fun matchesSimilarKeywordOverlap() {
        val current = quiz(1, "安全帽佩戴要求有哪些？", listOf("必须佩戴", "可以不佩戴"))
        val similar = quiz(2, "安全帽佩戴规范是什么？", listOf("必须佩戴", "可以不佩戴"))
        val unrelated = quiz(3, "会计凭证有哪些分类？", listOf("收款凭证", "付款凭证"))

        val result = findSimilarQuizzes(current, listOf(current, similar, unrelated))

        assertEquals(1, result.size)
        assertEquals(2, result.first().id)
    }

    @Test
    fun excludesCurrentQuizAndUnsupportedTypes() {
        val current = quiz(1, "灭火器检查周期是多少？")
        val similar = quiz(2, "灭火器维护周期是多少？")
        val unsupported = quiz(3, "简述灭火器维护流程", listOf("自由作答"))

        val result = findSimilarQuizzes(current, listOf(current, similar, unsupported))

        assertTrue(result.none { it.id == current.id })
        assertTrue(result.none { it.id == unsupported.id })
    }

    @Test
    fun recallsDifferentSentencesSharingSf6() {
        val current = quiz(1, "SF6 断路器发生泄漏时应采取哪些措施？")
        val similar = quiz(2, "进入充有SF6气体的设备区域前需要先通风。")
        val unrelated = quiz(3, "验电器使用前应检查外观和试验日期。")

        val result = findSimilarQuizResults(current, listOf(current, similar, unrelated))

        assertEquals(listOf(2), result.map { it.quiz.id })
        assertTrue(result.first().matchedTerms.any { it.equals("sf6", ignoreCase = true) })
    }

    @Test
    fun recallsChineseTechnicalPhraseAcrossDifferentSentences() {
        val current = quiz(1, "电流互感器二次侧为什么不得开路？")
        val similar = quiz(2, "运行中的电流互感器二次回路应保持闭合。")
        val unrelated = quiz(3, "电压互感器二次侧不得短路。")

        val result = findSimilarQuizResults(current, listOf(current, similar, unrelated))

        assertEquals(
            result.joinToString { "${it.quiz.id}:${it.score}:${it.matchedTerms}" },
            2,
            result.first().quiz.id
        )
        assertTrue(result.first().matchedTerms.any { it.contains("电流互感器") })
    }

    @Test
    fun ignoresJudgementOptionsAndAnswerLetters() {
        val current = quiz(1, "绝缘手套使用前需要进行充气检查。", answer = setOf(0))
        val unrelated = quiz(2, "财务凭证应当按照日期顺序装订。", answer = setOf(0))

        val result = findSimilarQuizResults(current, listOf(current, unrelated))

        assertTrue(result.isEmpty())
    }

    @Test
    fun requiredKeywordIgnoresCaseAndFullWidthCharacters() {
        val current = quiz(1, "断路器检修有哪些安全要求？")
        val fullWidthMatch = quiz(2, "ＳＦ６断路器气室检漏应使用专用仪器。")
        val unrelated = quiz(3, "真空断路器需要检查触头磨损。")

        val result = findSimilarQuizResults(
            current,
            listOf(current, fullWidthMatch, unrelated),
            requiredKeywords = "sf6",
            maxResults = MAX_SIMILAR_QUIZ_RESULTS
        )

        assertEquals(listOf(2), result.map { it.quiz.id })
    }

    @Test
    fun requiredKeywordsUseAndMatchingAcrossPromptAndOptions() {
        val current = quiz(1, "设备巡视要求是什么？")
        val both = quiz(
            2,
            "SF6设备巡视时应检查哪些项目？",
            listOf("检查电流互感器外观", "无需记录")
        )
        val onlyOne = quiz(3, "SF6设备巡视时应检查压力。")

        val result = findSimilarQuizResults(
            current,
            listOf(current, both, onlyOne),
            requiredKeywords = "SF6 电流互感器",
            maxResults = MAX_SIMILAR_QUIZ_RESULTS
        )

        assertEquals(listOf(2), result.map { it.quiz.id })
    }

    @Test
    fun blankRequiredKeywordsUseAutomaticSimilarity() {
        val current = quiz(1, "电流互感器二次侧为什么不得开路？")
        val similar = quiz(2, "运行中的电流互感器二次回路应保持闭合。")

        val automatic = findSimilarQuizResults(current, listOf(current, similar))
        val blankQuery = findSimilarQuizResults(
            current,
            listOf(current, similar),
            requiredKeywords = "   "
        )

        assertEquals(automatic, blankQuery)
    }

    @Test
    fun automaticSimilarityDefaultsToHighestScoringTwentyResults() {
        val current = quiz(1, "SF6断路器设备巡视检查要求")
        val candidates = (2..26).map { id ->
            quiz(id, "SF6断路器设备巡视检查要求附加项目$id")
        }
        val allResults = findSimilarQuizResults(
            currentQuiz = current,
            candidates = listOf(current) + candidates,
            maxResults = Int.MAX_VALUE
        )

        val defaultResults = findSimilarQuizResults(
            currentQuiz = current,
            candidates = listOf(current) + candidates
        )

        assertEquals(25, allResults.size)
        assertEquals(MAX_SIMILAR_QUIZ_RESULTS, defaultResults.size)
        assertEquals(allResults.take(MAX_SIMILAR_QUIZ_RESULTS), defaultResults)
    }

    @Test
    fun automaticSimilarityReturnsAllResultsWhenBelowLimit() {
        val current = quiz(1, "电流互感器二次回路检查要求")
        val candidates = (2..8).map { id ->
            quiz(id, "电流互感器二次回路检查项目$id")
        }

        val result = findSimilarQuizResults(current, listOf(current) + candidates)

        assertEquals(candidates.size, result.size)
        assertEquals(candidates.map { it.id }.toSet(), result.map { it.quiz.id }.toSet())
    }

    @Test
    fun batchAnalysisMatchesExhaustiveResultsAndEvaluatesPairsOnce() = runBlocking {
        val quizzes = (1..12).map { id ->
            quiz(id, "SF6断路器设备巡视检查项目$id")
        }
        val index = QuizSimilarityIndex(quizzes)

        val analysis = index.analyzeAll()

        assertEquals(quizzes.size, analysis.featureExtractionCount)
        assertEquals(quizzes.size * (quizzes.size - 1) / 2, analysis.pairEvaluationCount)
        quizzes.forEach { current ->
            assertEquals(
                index.findSimilarExhaustiveForTesting(current),
                analysis.resultsByQuizId[current.id]
            )
        }
    }

    @Test
    fun batchAnalysisKeepsHighestTwentyWithStableIdTieBreak() = runBlocking {
        val quizzes = (1..26).map { id ->
            quiz(id, "SF6断路器设备巡视检查要求")
        }
        val index = QuizSimilarityIndex(quizzes)

        val analysis = index.analyzeAll()
        val results = analysis.resultsByQuizId.getValue(1)

        assertEquals(MAX_SIMILAR_QUIZ_RESULTS, results.size)
        assertEquals((2..21).toList(), results.map { it.quiz.id })
        assertEquals(index.findSimilarExhaustiveForTesting(quizzes.first()), results)
    }

    @Test
    fun batchAnalysisHandlesEmptyLibrary() = runBlocking {
        val analysis = QuizSimilarityIndex(emptyList()).analyzeAll()

        assertTrue(analysis.resultsByQuizId.isEmpty())
        assertEquals(0, analysis.featureExtractionCount)
        assertEquals(0, analysis.pairEvaluationCount)
        assertEquals(0, analysis.skippedPairCount)
    }

    @Test
    fun batchAnalysisSkipsPairsWithoutSharedFeatures() = runBlocking {
        val quizzes = listOf(
            quiz(1, "SF6断路器气室泄漏"),
            quiz(2, "电流互感器二次开路"),
            quiz(3, "会计凭证装订保管期限")
        )

        val analysis = QuizSimilarityIndex(quizzes).analyzeAll()

        assertEquals(0, analysis.pairEvaluationCount)
        assertEquals(3, analysis.skippedPairCount)
        assertTrue(analysis.resultsByQuizId.values.all { it.isEmpty() })
    }

    @Test
    fun batchAnalysisHonorsCancellationFromProgressCallback() {
        val quizzes = (1..30).map { id ->
            quiz(id, "电流互感器二次回路检查项目$id")
        }

        try {
            runBlocking {
                QuizSimilarityIndex(quizzes).analyzeAll { current, _ ->
                    if (current == 2) throw CancellationException("cancel test")
                }
            }
            fail("Expected CancellationException")
        } catch (_: CancellationException) {
            // Expected.
        }
    }
}
