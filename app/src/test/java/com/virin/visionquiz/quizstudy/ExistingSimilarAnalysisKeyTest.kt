package com.virin.visionquiz.quizstudy

import com.virin.visionquiz.ai.AiExplanationType
import com.virin.visionquiz.dao.Quiz
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ExistingSimilarAnalysisKeyTest {
    @Test
    fun aiRequestKeyChangesWhenSimilarQuestionSetChanges() {
        val first = quiz(1, "第一道相似题")
        val second = quiz(2, "第二道相似题")

        val firstKey = AiRequestKey(
            quizId = 10,
            type = AiExplanationType.EXISTING_SIMILAR_ANALYSIS,
            subKey = existingSimilarAnalysisSubKey(listOf(first))
        )
        val secondKey = AiRequestKey(
            quizId = 10,
            type = AiExplanationType.EXISTING_SIMILAR_ANALYSIS,
            subKey = existingSimilarAnalysisSubKey(listOf(first, second))
        )

        assertNotEquals(firstKey, secondKey)
        assertEquals(firstKey.subKey, existingSimilarAnalysisSubKey(listOf(first)))
    }

    private fun quiz(id: Int, prompt: String): Quiz {
        return Quiz(
            id = id,
            prompt = prompt,
            options = listOf("正确", "错误"),
            answer = setOf(0),
            isMultipleChoice = false,
            libraryId = 1
        )
    }
}
