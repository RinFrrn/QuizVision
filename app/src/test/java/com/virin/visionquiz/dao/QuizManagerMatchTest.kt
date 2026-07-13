package com.virin.visionquiz.dao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuizManagerMatchTest {
    @Test
    fun invertedRetrievalKeepsStrongOcrMatch() {
        val target = quiz(1, "电流互感器二次回路不得开路")
        val unrelated = (2..1_001).map { id ->
            quiz(id, "会计凭证归档保管项目$id")
        }
        val index = QuizManager.buildMatchIndex(listOf(target) + unrelated)

        val results = QuizManager.matchQuiz(
            input = "电流互感器二次回路不得开璐",
            index = index
        )

        assertEquals(1, results.first().first.id)
        assertTrue(index.retrievalCandidateCount("电流互感器二次回路不得开璐") < 100)
    }

    @Test
    fun unlimitedSearchRetainsEveryPassingCandidate() {
        val quizzes = listOf(
            quiz(1, "SF6断路器气室检漏要求"),
            quiz(2, "SF6断路器气室泄漏检查要求"),
            quiz(3, "会计凭证装订期限")
        )

        val results = QuizManager.matchQuiz(
            input = "SF6断路器气室检漏要求",
            questions = quizzes,
            minScore = QuizManager.SEARCH_MIN_MATCH_SCORE,
            maxResults = Int.MAX_VALUE
        )

        assertEquals(listOf(1, 2), results.map { it.first.id })
    }

    @Test
    fun equalScoresUseStableQuizIdTieBreak() {
        val results = QuizManager.matchQuiz(
            input = "完全相同的题干内容",
            questions = listOf(
                quiz(3, "完全相同的题干内容"),
                quiz(1, "完全相同的题干内容"),
                quiz(2, "完全相同的题干内容")
            ),
            maxResults = 2
        )

        assertEquals(listOf(1, 2), results.map { it.first.id })
    }

    @Test
    fun compactRetrievalIndexHasBoundedStorageForLargeLibraries() {
        val quizzes = (1..5_000).map { id ->
            quiz(
                id,
                "大型题库中的安全生产技术检查要求${id}号以及电流互感器二次回路运行注意事项"
            )
        }

        val index = QuizManager.buildMatchIndex(quizzes)

        assertTrue(
            index.retrievalPostingCapacityForTesting() <=
                quizzes.size * QuizManager.MAX_RETRIEVAL_FEATURES_PER_QUIZ
        )
        assertEquals(
            4_321,
            QuizManager.matchQuiz(
                input = "安全生产技术检查要求4321号以及电流互感器二次回路运行注意事项",
                index = index
            ).first().first.id
        )
    }

    private fun quiz(id: Int, prompt: String) = Quiz(
        id = id,
        prompt = prompt,
        options = listOf("正确", "错误"),
        answer = setOf(0),
        isMultipleChoice = false,
        questionType = "判断",
        libraryId = 1
    )
}
