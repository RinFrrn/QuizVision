package com.virin.visionquiz.cram

import com.virin.visionquiz.ai.AiConfig
import com.virin.visionquiz.dao.Quiz
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CramAiPromptBuilderTest {

    @Test
    fun chunksAreStableAndRespectQuestionLimit() {
        val quizzes = (1..5).map { index ->
            quiz(
                id = index,
                prompt = "第${index}题，某主体应当在3日内处理。",
                reference = "模块甲",
                sourceRow = index + 10
            )
        }

        val chunks = CramAiPromptBuilder.chunkQuestions(
            quizzes = quizzes,
            maxQuestions = 2,
            maxChars = 100_000
        )

        assertEquals(3, chunks.size)
        assertEquals(listOf(2, 2, 1), chunks.map { it.quizzes.size })
        assertEquals(listOf(1, 2, 3), chunks.map { it.partIndex })
        assertEquals(3, chunks.first().partCount)
        assertEquals(3, chunks.map { it.cacheKey }.distinct().size)
    }

    @Test
    fun modulePromptCarriesSourceAnswerAndImportedEvidence() {
        val chunk = CramAiPromptBuilder.chunkQuestions(
            listOf(
                quiz(
                    id = 7,
                    prompt = "处理期限是多久？",
                    reference = "《期限规则》第三条",
                    sourceRow = 88,
                    explanation = "应在3日内完成。"
                )
            )
        ).single()

        val prompt = CramAiPromptBuilder.buildModulePrompt(config(), chunk)

        assertTrue(prompt.user.contains("[题#88"))
        assertTrue(prompt.user.contains("标准答案：A"))
        assertTrue(prompt.user.contains("题库依据：《期限规则》第三条"))
        assertTrue(prompt.user.contains("题库备注/解析：应在3日内完成。"))
        assertTrue(prompt.user.contains("答案字母分布"))
        assertTrue(prompt.user.contains("不能替代知识判断"))
    }

    @Test
    fun fingerprintChangesWithQuestionContentButNeverUsesApiKey() {
        val first = CramAiPromptBuilder.buildModulePrompt(
            config(),
            CramAiPromptBuilder.chunkQuestions(listOf(quiz(1, "甲题"))).single()
        )
        val second = CramAiPromptBuilder.buildModulePrompt(
            config(),
            CramAiPromptBuilder.chunkQuestions(listOf(quiz(1, "乙题"))).single()
        )

        val firstFingerprint = CramAiPromptBuilder.fingerprint(first, config())
        val secondFingerprint = CramAiPromptBuilder.fingerprint(second, config())

        assertNotEquals(firstFingerprint, secondFingerprint)
        assertFalse(firstFingerprint.contains("secret-key"))
        assertEquals(64, firstFingerprint.length)
    }

    @Test
    fun finalPromptDemandsCompleteCramSectionsAndFlagsPartialInput() {
        val prompt = CramAiPromptBuilder.buildFinalReportPrompt(
            config = config(),
            libraryName = "普考题库",
            questionCount = 657,
            localSummary = "本地统计",
            moduleSummaries = listOf("模块甲" to "模块摘要"),
            incompleteChunkCount = 2
        )

        assertTrue(prompt.user.contains("# 3天及格冲刺总纲"))
        assertTrue(prompt.user.contains("## 考前20分钟口令"))
        assertTrue(prompt.user.contains("## 完全不会时的最后策略"))
        assertTrue(prompt.user.contains("[题#88、题#89]"))
        assertTrue(prompt.user.contains("[ID:28660、ID:28661]"))
        assertTrue(prompt.user.contains("禁止裸数字和数字范围"))
        assertTrue(prompt.user.contains("有 2 个分块分析失败"))
    }

    @Test
    fun finalPromptRepresentsEverySuccessfulChunkUnderTheSharedBudget() {
        val summaries = (1..300).map { index ->
            ("超长模块名称${index}_" + "甲".repeat(90)) to
                ("摘要${index}_" + "乙".repeat(900))
        }

        val prompt = CramAiPromptBuilder.buildFinalReportPrompt(
            config = config(),
            libraryName = "大题库",
            questionCount = 657,
            localSummary = "本地统计",
            moduleSummaries = summaries
        )

        assertTrue(prompt.user.contains("已纳入全部 300 个成功分块"))
        assertTrue(prompt.user.contains("[1]"))
        assertTrue(prompt.user.contains("[300]"))
    }

    @Test
    fun fortyChunkCompressionKeepsLateSectionsInsteadOfOnlyThePrefix() {
        val summaries = (1..40).map { index ->
            "模块$index" to """
                ### 高频母规则
                规则标$index ${"甲".repeat(300)}
                ### 数字与时限
                数字标$index ${"乙".repeat(300)}
                ### 主体与条件
                主体标$index ${"丙".repeat(300)}
                ### 易错陷阱
                陷阱标$index ${"丁".repeat(300)}
                ### 统计观察
                统计标$index ${"戊".repeat(300)}
                ### 题号索引
                索引标$index ${"己".repeat(300)}
            """.trimIndent()
        }

        val prompt = CramAiPromptBuilder.buildFinalReportPrompt(
            config = config(),
            libraryName = "普考题库",
            questionCount = 657,
            localSummary = "本地统计",
            moduleSummaries = summaries
        )

        assertTrue(prompt.user.contains("规则标40"))
        assertTrue(prompt.user.contains("数字标40"))
        assertTrue(prompt.user.contains("陷阱标40"))
        assertTrue(prompt.user.contains("索引标40"))
    }

    @Test
    fun quickCardExtractionStopsAtNextSecondLevelHeading() {
        val report = """
            # 总稿
            ## 考前20分钟口令
            - 主体、条件、数字
            ## 完全不会时的最后策略
            - 先排除
        """.trimIndent()

        val quickCard = CramLocalContentRenderer.extractQuickCard(report)

        assertEquals("## 考前20分钟口令\n- 主体、条件、数字", quickCard)
    }

    private fun config() = AiConfig(
        enabled = true,
        baseUrl = "https://example.test/v1",
        apiKey = "secret-key",
        model = "test-model",
        analysisPrompt = "分析",
        techniquePrompt = "技巧",
        mnemonicPrompt = "口诀"
    )

    private fun quiz(
        id: Int,
        prompt: String,
        reference: String = "模块",
        sourceRow: Int? = id,
        explanation: String? = null
    ) = Quiz(
        id = id,
        prompt = prompt,
        options = listOf("正确答案", "错误答案"),
        answer = setOf(0),
        isMultipleChoice = false,
        questionType = "单选",
        libraryId = 1,
        explanation = explanation,
        reference = reference,
        sourceRow = sourceRow
    )
}
