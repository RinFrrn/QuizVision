package com.virin.visionquiz.cram

import com.virin.visionquiz.dao.Quiz
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalQuestionBankAnalyzerTest {

    @Test
    fun analyze_buildsTypeAnswerModuleAndMultipleChoiceStatistics() {
        val quizzes = listOf(
            judgement(
                id = 1,
                prompt = "任何用户均可以无条件中止供电。",
                correct = false,
                reference = "《供电规则》第一条"
            ),
            judgement(
                id = 2,
                prompt = "供电企业应当按约定通知用户。",
                correct = true,
                reference = "《供电规则》第二条"
            ),
            quiz(
                id = 3,
                prompt = "负责审批的主体是？",
                options = listOf("甲", "乙", "丙", "丁"),
                answer = setOf(1),
                type = "单选",
                reference = "《供电规则》第三条"
            ),
            quiz(
                id = 4,
                prompt = "下列哪些属于完整条件？",
                options = listOf("甲", "乙", "丙", "丁"),
                answer = setOf(0, 2, 3),
                type = "多选",
                reference = "《供电规则》第四条"
            ),
            quiz(
                id = 5,
                prompt = "应同时完成哪些事项？",
                options = listOf("甲", "乙", "丙"),
                answer = setOf(0, 1, 2),
                type = "多选",
                reference = "《收费办法》第一条"
            )
        )

        val result = LocalQuestionBankAnalyzer.analyze(
            quizzes,
            CramAnalysisConfig(coverageCutoffs = listOf(1, 2), dailyQuestionLimit = 2)
        )

        assertEquals(2, result.type(CramQuestionType.JUDGEMENT).questionCount)
        assertEquals(1, result.type(CramQuestionType.SINGLE_CHOICE).questionCount)
        assertEquals(2, result.type(CramQuestionType.MULTIPLE_CHOICE).questionCount)
        assertEquals(4, result.modules.first().questionCount)
        assertEquals("《供电规则》", result.modules.first().displayName)
        assertEquals(0.8, result.moduleCoverages.first().coverageOfBank, 0.0001)
        assertEquals(1.0, result.moduleCoverages.last().coverageOfBank, 0.0001)

        assertEquals(2, result.multipleChoice.questionCount)
        assertEquals(1, result.multipleChoice.selectsAllOptionsCount)
        assertEquals(0.5, result.multipleChoice.selectsAllOptionsRatio, 0.0001)
        assertEquals(2, result.multipleChoice.selectsThreeOrMoreCount)
        assertEquals(3.0, result.multipleChoice.averageSelectedOptionCount, 0.0001)

        val multiDistribution = result.answerDistributions
            .first { it.type == CramQuestionType.MULTIPLE_CHOICE }
        assertFalse(multiDistribution.isKnowledgeRule)
        assertTrue(multiDistribution.usagePolicy.contains("不能当作知识规律"))
        assertEquals(listOf("ABC", "ACD"), multiDistribution.patterns.map { it.answerLetters }.sorted())
    }

    @Test
    fun judgementKeywordStats_areEmpiricalAndNeverStandaloneRules() {
        val quizzes = (1..6).map { id ->
            judgement(
                id = id,
                prompt = if (id <= 5) "所有用户都必须执行第${id}项。" else "一般应按合同办理。",
                correct = id == 5,
                reference = "判断规则"
            )
        }

        val result = LocalQuestionBankAnalyzer.analyze(quizzes)
        val all = result.judgement.keywords.first { it.keyword == "所有" }

        assertEquals(5, all.knownOutcomeCount)
        assertEquals(1, all.trueStatementCount)
        assertEquals(4, all.falseStatementCount)
        assertEquals(JudgementOutcome.FALSE, all.majorityOutcome)
        assertEquals(0.8, all.empiricalAccuracy, 0.0001)
        assertEquals(EvidenceReliability.LOW, all.reliability)
        assertFalse(all.safeAsStandaloneRule)
        assertTrue(result.judgement.usagePolicy.contains("不能脱离"))
    }

    @Test
    fun explicitFillBlankType_winsOverMultipleAnswerHeuristic() {
        val fillBlank = quiz(
            id = 9,
            prompt = "请填写两个空。",
            options = listOf("甲", "乙"),
            answer = setOf(0, 1),
            type = "填空",
            reference = "填空规则"
        )

        val result = LocalQuestionBankAnalyzer.analyze(listOf(fillBlank))

        assertEquals(1, result.type(CramQuestionType.FILL_BLANK).questionCount)
        assertEquals(0, result.type(CramQuestionType.MULTIPLE_CHOICE).questionCount)
    }

    @Test
    fun missingReferences_buildsQuestionTypeFallbackModulesWithoutClaimingSources() {
        val quizzes = listOf(
            judgement(
                id = 1,
                prompt = "所有用户都必须执行。",
                correct = false,
                reference = null
            ),
            judgement(
                id = 2,
                prompt = "一般应当按约定办理。",
                correct = true,
                reference = null
            ),
            quiz(
                id = 3,
                prompt = "负责办理的主体是？",
                options = listOf("甲", "乙", "丙", "丁"),
                answer = setOf(1),
                type = "单选",
                reference = null
            ),
            quiz(
                id = 4,
                prompt = "下列哪些属于完整条件？",
                options = listOf("甲", "乙", "丙", "丁"),
                answer = setOf(0, 2),
                type = "多选",
                reference = null
            )
        )

        val result = LocalQuestionBankAnalyzer.analyze(
            quizzes,
            CramAnalysisConfig(coverageCutoffs = listOf(5), dailyQuestionLimit = 4)
        )

        assertEquals(0, result.referencedQuestionCount)
        assertEquals(4, result.unreferencedQuestionCount)
        assertEquals(3, result.modules.size)
        assertEquals(
            setOf("判断题·规则辨析", "单选题·主体与条件", "多选题·清单组合"),
            result.modules.map { it.displayName }.toSet()
        )
        assertEquals(1.0, result.moduleCoverages.single().coverageOfBank, 0.0001)
        assertEquals(0.0, result.moduleCoverages.single().coverageOfReferencedQuestions, 0.0001)
        assertTrue(result.priorities.none { "有答案依据" in it.reasons })
        assertTrue(
            result.threeDayPlan.days
                .flatMap { it.modules }
                .none { it.displayName == "未标注答案依据" }
        )
    }

    @Test
    fun numericExtraction_marksFalseStatementAsIncorrect_andNormalizesChineseNumbers() {
        val quizzes = listOf(
            judgement(
                id = 10,
                prompt = "供电企业必须在5个工作日内答复。",
                correct = false,
                reference = "《办理规则》第十条"
            ),
            quiz(
                id = 11,
                prompt = "正确的处罚金额是？",
                options = listOf("1万元", "3万元", "5万元"),
                answer = setOf(1),
                type = "单选",
                reference = "《处罚办法》第十二条",
                explanation = "最高按三至五倍处理，比例为百分之十，期限为十二日。"
            )
        )

        val result = LocalQuestionBankAnalyzer.analyze(quizzes)
        val falseTime = result.numericFacts.first {
            it.quizId == 10 && it.category == NumericFactCategory.TIME_LIMIT
        }
        assertEquals("5", falseTime.normalizedValue)
        assertEquals("工作日", falseTime.unit)
        assertEquals(FactTruthStatus.INCORRECT, falseTime.truthStatus)

        assertNotNull(result.fact(11, NumericFactCategory.MONEY, "3", "万元"))
        assertNotNull(result.fact(11, NumericFactCategory.MULTIPLE, "3-5", "倍"))
        assertNotNull(result.fact(11, NumericFactCategory.PERCENTAGE, "10", "%"))
        assertNotNull(result.fact(11, NumericFactCategory.TIME_LIMIT, "12", "日"))
        assertTrue(
            result.numericFactSummaries
                .first { it.category == NumericFactCategory.TIME_LIMIT && it.normalizedValue == "5" }
                .incorrectCount > 0
        )
    }

    @Test
    fun identicalNumbersRemainSeparatedByTheirRuleContext() {
        val result = LocalQuestionBankAnalyzer.analyze(
            listOf(
                quiz(
                    id = 12,
                    prompt = "政府批复后应在多久公布有序用电方案？",
                    options = listOf("3个工作日", "5个工作日"),
                    answer = setOf(0),
                    type = "单选",
                    reference = "《规则甲》"
                ),
                quiz(
                    id = 13,
                    prompt = "低压用户书面通知供电方案的期限是多久？",
                    options = listOf("3个工作日", "7个工作日"),
                    answer = setOf(0),
                    type = "单选",
                    reference = "《规则乙》"
                )
            )
        )

        val threeDayFacts = result.numericFactSummaries.filter {
            it.category == NumericFactCategory.TIME_LIMIT &&
                it.normalizedValue == "3" &&
                it.correctOrSupportedCount > 0
        }
        assertEquals(2, threeDayFacts.size)
        assertTrue(threeDayFacts.any { summary ->
            summary.contexts.any { it.contains("政府批复") }
        })
        assertTrue(threeDayFacts.any { summary ->
            summary.contexts.any { it.contains("低压用户") }
        })
    }

    @Test
    fun normalizedDuplicateDetection_acceptsPunctuationWhitespaceAndOptionReordering() {
        val first = quiz(
            id = 21,
            prompt = "1. 供电方 应当通知！",
            options = listOf("是", "否"),
            answer = setOf(0),
            type = "判断",
            reference = "规则",
            sourceRow = 8
        )
        val second = quiz(
            id = 22,
            prompt = "供电方应当通知",
            options = listOf("否", "是"),
            answer = setOf(1),
            type = "判断",
            reference = "规则",
            sourceRow = 18
        )
        val differentAnswer = quiz(
            id = 23,
            prompt = "供电方应当通知",
            options = listOf("否", "是"),
            answer = setOf(0),
            type = "判断",
            reference = "规则",
            sourceRow = 28
        )

        val result = LocalQuestionBankAnalyzer.analyze(listOf(first, second, differentAnswer))

        assertEquals(1, result.duplicateGroups.size)
        assertEquals(listOf(21, 22), result.duplicateGroups.single().quizIds)
        assertEquals(listOf(8, 18), result.duplicateGroups.single().sourceRows)
        assertEquals(24, result.duplicateGroups.single().fingerprint.length)
    }

    @Test
    fun priorityPlanAndSelfTest_areDeterministicStratifiedAndBounded() {
        val quizzes = buildList {
            repeat(15) { index ->
                add(
                    judgement(
                        id = index + 1,
                        prompt = "用户应当遵守判断规则${letter(index)}。",
                        correct = index % 2 == 0,
                        reference = if (index < 10) "《高频规则》第${index + 1}条" else "《判断规则》"
                    )
                )
                add(
                    quiz(
                        id = 101 + index,
                        prompt = "单选考点${letter(index)}",
                        options = listOf("甲", "乙", "丙", "丁"),
                        answer = setOf(index % 4),
                        type = "单选",
                        reference = if (index < 10) "《高频规则》" else "《单选规则》"
                    )
                )
                add(
                    quiz(
                        id = 201 + index,
                        prompt = "多选考点${letter(index)}",
                        options = listOf("甲", "乙", "丙", "丁"),
                        answer = setOf(0, 1, 2),
                        type = "多选",
                        reference = if (index < 10) "《高频规则》" else "《多选规则》"
                    )
                )
            }
        }
        val config = CramAnalysisConfig(
            dailyQuestionLimit = 8,
            selfTestSize = 30,
            wrongQuizIds = listOf(115)
        )

        val first = LocalQuestionBankAnalyzer.analyze(quizzes, config)
        val second = LocalQuestionBankAnalyzer.analyze(quizzes, config)

        assertEquals(first.selfTest.quizIds, second.selfTest.quizIds)
        assertEquals(30, first.selfTest.actualQuestionCount)
        assertEquals(30, first.selfTest.quizIds.distinct().size)
        assertEquals(
            mapOf(
                CramQuestionType.JUDGEMENT to 10,
                CramQuestionType.SINGLE_CHOICE to 10,
                CramQuestionType.MULTIPLE_CHOICE to 10
            ),
            first.selfTest.sections.associate { it.type to it.quizIds.size }
        )
        assertEquals(3, first.threeDayPlan.days.size)
        assertTrue(first.threeDayPlan.days.all { it.quizIds.size <= 8 })
        assertTrue(
            first.priorities.first { it.quizId == 115 }.reasons.contains("历史错题")
        )
        assertTrue(115 in first.threeDayPlan.days[2].quizIds)
    }

    @Test
    fun jsonRoundTripAndEmptyBank_areSafe() {
        val empty = LocalQuestionBankAnalyzer.analyze(
            emptyList(),
            CramAnalysisConfig(dailyQuestionLimit = -1, selfTestSize = 30)
        )
        assertEquals(0, empty.totalQuestionCount)
        assertEquals(3, empty.threeDayPlan.days.size)
        assertTrue(empty.threeDayPlan.days.all { it.quizIds.isEmpty() })
        assertTrue(empty.warnings.any { it.contains("题库为空") })

        val result = LocalQuestionBankAnalyzer.analyze(
            listOf(judgement(0, "必须按规定办理。", true, null))
        )
        val decoded = CramAnalysisJson.decode(CramAnalysisJson.encode(result))
        assertEquals(result, decoded)
        assertEquals(-1, result.identities.single().analysisQuizId)
        assertTrue(result.warnings.any { it.contains("临时 ID") })
    }

    private fun CramAnalysisResult.type(type: CramQuestionType): QuestionTypeStat =
        questionTypes.first { it.type == type }

    private fun CramAnalysisResult.fact(
        quizId: Int,
        category: NumericFactCategory,
        value: String,
        unit: String
    ): NumericFact? = numericFacts.firstOrNull {
        it.quizId == quizId &&
            it.category == category &&
            it.normalizedValue == value &&
            it.unit == unit
    }

    private fun judgement(
        id: Int,
        prompt: String,
        correct: Boolean,
        reference: String?
    ): Quiz = quiz(
        id = id,
        prompt = prompt,
        options = listOf("正确", "错误"),
        answer = setOf(if (correct) 0 else 1),
        type = "判断",
        reference = reference
    )

    private fun quiz(
        id: Int,
        prompt: String,
        options: List<String>,
        answer: Set<Int>,
        type: String,
        reference: String?,
        explanation: String? = null,
        sourceRow: Int? = null
    ): Quiz = Quiz(
        id = id,
        prompt = prompt,
        options = options,
        answer = answer,
        isMultipleChoice = type == "多选",
        questionType = type,
        libraryId = 1,
        explanation = explanation,
        reference = reference,
        sourceRow = sourceRow
    )

    private fun letter(index: Int): Char = '甲' + (index % 10)
}
