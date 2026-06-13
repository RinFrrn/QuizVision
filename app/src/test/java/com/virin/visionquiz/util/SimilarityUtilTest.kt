package com.virin.visionquiz.util

import com.virin.visionquiz.dao.Quiz
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
}
