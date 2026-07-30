package com.virin.visionquiz.cram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CramPriorityModuleMappingTest {

    @Test
    fun mapsAnalysisIdsOrdersByPriorityAndFiltersUnavailableQuestions() {
        val module = ModuleStat(
            key = "__type__multiple_choice",
            displayName = "多选题·清单组合",
            questionCount = 6,
            ratioOfBank = 0.42,
            quizIds = listOf(-1, 9, 8, 404, -2, 777),
            sourceReferences = emptyList(),
            typeCounts = listOf(
                ModuleTypeCount(CramQuestionType.MULTIPLE_CHOICE, 6)
            ),
            numericFactCount = 7,
            averagePriorityScore = 66.0
        )
        val result = buildCramPriorityModules(
            modules = listOf(module),
            identities = listOf(
                CramQuestionIdentity(-1, 42, 0, null),
                CramQuestionIdentity(-2, 42, 1, null),
                CramQuestionIdentity(8, 8, 2, null),
                CramQuestionIdentity(9, 9, 3, null),
                CramQuestionIdentity(404, 404, 4, null)
            ),
            priorities = listOf(
                priority(quizId = -1, score = 50.0),
                priority(quizId = -2, score = 40.0),
                priority(quizId = 8, score = 80.0),
                priority(quizId = 9, score = 80.0),
                priority(quizId = 404, score = 100.0),
                priority(quizId = 777, score = 120.0)
            ),
            availableDatabaseIds = setOf(8, 9, 42, 777)
        ).single()

        assertEquals(listOf(8, 9, 42), result.quizIds)
        assertEquals(1, result.rank)
        assertEquals(42, result.coveragePercent)
        assertEquals("多选 6题", result.typeSummary)
        assertEquals(7, result.numericFactCount)
        assertTrue(result.isFallback)
        assertTrue(result.reason.contains("按题型自动分组"))
    }

    @Test
    fun knowledgeModuleKeepsItsExplicitSourceMeaning() {
        val result = buildCramPriorityModules(
            modules = listOf(
                ModuleStat(
                    key = "供电规则",
                    displayName = "《供电规则》",
                    questionCount = 1,
                    ratioOfBank = 1.0,
                    quizIds = listOf(7),
                    sourceReferences = listOf("《供电规则》第七条"),
                    typeCounts = listOf(
                        ModuleTypeCount(CramQuestionType.SINGLE_CHOICE, 1)
                    ),
                    numericFactCount = 0,
                    averagePriorityScore = 30.0
                )
            ),
            identities = listOf(CramQuestionIdentity(7, 7, 0, 8)),
            priorities = listOf(priority(quizId = 7, score = 30.0)),
            availableDatabaseIds = setOf(7)
        ).single()

        assertFalse(result.isFallback)
        assertEquals(1, result.sourceReferenceCount)
        assertEquals(listOf(7), result.quizIds)
        assertTrue(result.reason.contains("答案依据"))
    }

    private fun priority(
        quizId: Int,
        score: Double
    ): QuestionPriority {
        return QuestionPriority(
            quizId = quizId,
            score = score,
            level = CramPriorityLevel.MEDIUM,
            moduleKey = null,
            reasons = emptyList()
        )
    }
}
