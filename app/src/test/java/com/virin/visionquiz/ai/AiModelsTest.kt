package com.virin.visionquiz.ai

import com.virin.visionquiz.dao.Quiz
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiModelsTest {
    private val quiz = Quiz(
        id = 1,
        prompt = "下列哪项正确？",
        options = listOf("选项甲", "选项乙"),
        answer = setOf(1),
        isMultipleChoice = false,
        questionType = "单选",
        libraryId = 2
    )
    private val config = AiConfig(
        enabled = true,
        baseUrl = "https://api.openai.com",
        apiKey = "secret",
        model = "test-model",
        analysisPrompt = AiPromptBuilder.DEFAULT_ANALYSIS_PROMPT,
        techniquePrompt = AiPromptBuilder.DEFAULT_TECHNIQUE_PROMPT,
        mnemonicPrompt = AiPromptBuilder.DEFAULT_MNEMONIC_PROMPT
    )

    @Test
    fun promptContainsStructuredQuestionContext() {
        val prompt = AiPromptBuilder.build(
            quiz,
            AiExplanationType.ANALYSIS,
            config.analysisPrompt,
            setOf(0)
        )

        assertTrue(prompt.user.contains("题型：单选"))
        assertTrue(prompt.user.contains("A. 选项甲"))
        assertTrue(prompt.user.contains("标准答案：B"))
        assertTrue(prompt.user.contains("用户答案：A"))
        assertTrue(prompt.user.contains("作答结果：错误"))
    }

    @Test
    fun systemPromptUsesCrossDomainRoleAndSafetyBoundaries() {
        assertTrue(AiPromptBuilder.SYSTEM_PROMPT.contains("跨学科"))
        assertTrue(AiPromptBuilder.SYSTEM_PROMPT.contains("学科、行业或职业场景"))
        assertTrue(AiPromptBuilder.SYSTEM_PROMPT.contains("法律、医学、安全、财务"))
        assertTrue(AiPromptBuilder.SYSTEM_PROMPT.contains("Markdown 正文"))
        assertTrue(AiPromptBuilder.SYSTEM_PROMPT.contains("禁止 HTML、表格、图片、外部链接、LaTeX 公式"))
        assertTrue(AiPromptBuilder.SYSTEM_PROMPT.contains("禁止用代码围栏包裹全文"))
        assertFalse(AiPromptBuilder.SYSTEM_PROMPT.contains("语文"))
        assertTrue(AiPromptBuilder.DEFAULT_MNEMONIC_PROMPT.contains("不适合口诀时"))
    }

    @Test
    fun eachExplanationTypeHasARequiredMarkdownStructure() {
        val quickReview = AiPromptBuilder.outputFormat(AiExplanationType.QUICK_REVIEW)
        assertHeadings(
            quickReview,
            "### 结论",
            "### 关键区分",
            "### 记忆点"
        )

        val detailed = AiPromptBuilder.outputFormat(AiExplanationType.DETAILED_ANALYSIS)
        assertHeadings(
            detailed,
            "### 结论",
            "### 核心依据",
            "### 选项分析",
            "### 作答复盘"
        )

        val analysis = AiPromptBuilder.outputFormat(AiExplanationType.ANALYSIS)
        assertHeadings(
            analysis,
            "### 结论",
            "### 核心依据",
            "### 选项分析",
            "### 作答复盘"
        )

        val technique = AiPromptBuilder.outputFormat(AiExplanationType.TECHNIQUE)
        assertHeadings(
            technique,
            "### 关键信息",
            "### 解题步骤",
            "### 常见陷阱",
            "### 迁移方法"
        )

        val mnemonic = AiPromptBuilder.outputFormat(AiExplanationType.MNEMONIC)
        assertHeadings(
            mnemonic,
            "### 记忆要点",
            "### 对应关系",
            "### 适用边界"
        )

        val extension = AiPromptBuilder.outputFormat(AiExplanationType.QUESTION_EXTENSION)
        assertHeadings(
            extension,
            "### 原题要点",
            "### 变式问题",
            "### 拓展建议"
        )

        val similar = AiPromptBuilder.outputFormat(AiExplanationType.SIMILAR_ANALYSIS)
        assertHeadings(
            similar,
            "### 考点归纳",
            "### 对比题",
            "### 辨析要点"
        )

        val existingSimilar = AiPromptBuilder.outputFormat(AiExplanationType.EXISTING_SIMILAR_ANALYSIS)
        assertHeadings(
            existingSimilar,
            "### 考点关系",
            "### 题目对照",
            "### 混淆点",
            "### 做题抓手"
        )
        assertTrue(existingSimilar.contains("**关键差异**"))
        assertTrue(existingSimilar.contains("**答案影响**"))
    }

    @Test
    fun existingSimilarAnalysisPromptIncludesSimilarQuestionContext() {
        val similar = Quiz(
            id = 3,
            prompt = "下列哪项容易混淆？",
            options = listOf("相似选项甲", "相似选项乙"),
            answer = setOf(0),
            isMultipleChoice = false,
            questionType = "单选",
            libraryId = 2
        )
        val prompt = AiPromptBuilder.buildExistingSimilarAnalysis(
            quiz = quiz,
            similarQuizzes = listOf(similar),
            selectedAnswer = setOf(0)
        )

        assertTrue(prompt.user.contains("任务：相似题辨析"))
        assertTrue(prompt.user.contains("不要构造新题"))
        assertTrue(prompt.user.contains("候选相似题会以压缩摘要形式全部给出"))
        assertTrue(prompt.user.contains("自行选择最能体现考点关系和差异的题目"))
        assertTrue(prompt.user.contains("相同点、关键差异、答案影响"))
        assertTrue(prompt.system.contains("必须用 Markdown 粗体标记"))
        assertTrue(prompt.user.contains("**关键差异**"))
        assertTrue(prompt.user.contains("### 考点关系"))
        assertTrue(prompt.user.contains("### 题目对照"))
        assertTrue(prompt.user.contains("当前题："))
        assertTrue(prompt.user.contains("题干：下列哪项正确？"))
        assertTrue(prompt.user.contains("相似题："))
        assertTrue(prompt.user.contains("题干：下列哪项容易混淆？"))
        assertTrue(prompt.user.contains("A. 相似选项甲"))
        assertTrue(prompt.user.contains("标准答案：A"))
    }

    @Test
    fun generatedPromptIncludesMandatoryStructureAfterCustomTaskPrompt() {
        val prompt = AiPromptBuilder.build(
            quiz,
            AiExplanationType.ANALYSIS,
            "这是用户自定义要求。",
            setOf(0)
        )

        assertTrue(prompt.user.contains("这是用户自定义要求。"))
        assertTrue(prompt.user.contains("输出格式（必须严格遵守"))
        assertTrue(prompt.user.contains("### 选项分析"))
    }

    @Test
    fun markdownContractUsesSecondFingerprintVersion() {
        val prompt = AiPromptBuilder.build(
            quiz,
            AiExplanationType.ANALYSIS,
            config.analysisPrompt,
            setOf(0)
        )
        val current = prompt.fingerprint(config, AiExplanationType.ANALYSIS)
        val legacy = legacyFingerprint(prompt, config, AiExplanationType.ANALYSIS)

        assertEquals("v4", AiPrompt.FINGERPRINT_VERSION)
        assertNotEquals(legacy, current)
    }

    @Test
    fun quickReviewPromptIsShortAndFocused() {
        val prompt = AiPromptBuilder.build(
            quiz,
            AiExplanationType.QUICK_REVIEW,
            config.quickReviewPrompt,
            setOf(0)
        )

        assertTrue(prompt.user.contains("任务：快速复盘"))
        assertTrue(prompt.user.contains("### 关键区分"))
        assertTrue(prompt.user.contains("### 记忆点"))
        assertTrue(prompt.user.contains("**粗体**"))
        assertTrue(AiPromptBuilder.DEFAULT_QUICK_REVIEW_PROMPT.contains("80–180 字"))
        assertTrue(AiPromptBuilder.DEFAULT_QUICK_REVIEW_PROMPT.contains("**粗体**"))
        assertTrue(AiPromptBuilder.DEFAULT_QUICK_REVIEW_PROMPT.contains("不要逐项展开全部选项"))
    }

    @Test
    fun missingSelectionIsMarkedUnanswered() {
        val prompt = AiPromptBuilder.build(
            quiz,
            AiExplanationType.TECHNIQUE,
            config.techniquePrompt,
            null
        )
        assertTrue(prompt.user.contains("用户答案：未作答"))
        assertTrue(prompt.user.contains("作答结果：未作答"))
    }

    @Test
    fun fingerprintChangesWithPromptModelAndAnswer() {
        val first = AiPromptBuilder.build(
            quiz,
            AiExplanationType.ANALYSIS,
            config.analysisPrompt,
            setOf(0)
        )
        val same = AiPromptBuilder.build(
            quiz,
            AiExplanationType.ANALYSIS,
            config.analysisPrompt,
            setOf(0)
        )
        val otherAnswer = AiPromptBuilder.build(
            quiz,
            AiExplanationType.ANALYSIS,
            config.analysisPrompt,
            setOf(1)
        )
        assertEquals(
            first.fingerprint(config, AiExplanationType.ANALYSIS),
            same.fingerprint(config, AiExplanationType.ANALYSIS)
        )
        assertNotEquals(
            first.fingerprint(config, AiExplanationType.ANALYSIS),
            otherAnswer.fingerprint(config, AiExplanationType.ANALYSIS)
        )
        assertNotEquals(
            first.fingerprint(config, AiExplanationType.ANALYSIS),
            first.fingerprint(config.copy(model = "other"), AiExplanationType.ANALYSIS)
        )
        assertEquals(
            first.fingerprint(config, AiExplanationType.ANALYSIS),
            first.fingerprint(
                config.copy(profileId = "other-profile", profileName = "其他"),
                AiExplanationType.ANALYSIS
            )
        )
    }

    @Test
    fun endpointValidationRestrictsCleartextHosts() {
        assertTrue(AiEndpointValidator.buildEndpoint("https://example.com").isSuccess)
        assertEquals(
            "https://example.com/v1/chat/completions",
            AiEndpointValidator.buildEndpoint("https://example.com").getOrThrow()
        )
        assertEquals(
            "https://example.com/v1/chat/completions",
            AiEndpointValidator.buildEndpoint("https://example.com/v1").getOrThrow()
        )
        assertTrue(AiEndpointValidator.buildEndpoint("http://127.0.0.1:8080").isSuccess)
        assertTrue(AiEndpointValidator.buildEndpoint("http://192.168.1.5:8080").isSuccess)
        assertTrue(AiEndpointValidator.buildEndpoint("http://10.0.2.2:8080").isSuccess)
        assertFalse(AiEndpointValidator.buildEndpoint("http://example.com").isSuccess)
        assertFalse(AiEndpointValidator.buildEndpoint("http://8.8.8.8").isSuccess)
        assertFalse(AiEndpointValidator.buildEndpoint("https://example.com?key=value").isSuccess)
    }

    @Test
    fun modelEndpointSupportsBaseAndCompletionUrls() {
        assertEquals(
            "https://example.com/v1/models",
            AiEndpointValidator.buildModelsEndpoint("https://example.com").getOrThrow()
        )
        assertEquals(
            "https://example.com/v1/models",
            AiEndpointValidator.buildModelsEndpoint("https://example.com/v1").getOrThrow()
        )
        assertEquals(
            "https://example.com/v1/models",
            AiEndpointValidator.buildModelsEndpoint(
                "https://example.com/v1/chat/completions"
            ).getOrThrow()
        )
        assertEquals(
            "https://example.com/api/v1/models",
            AiEndpointValidator.buildModelsEndpoint(
                "https://example.com/api/v1/chat/completions"
            ).getOrThrow()
        )
    }

    @Test
    fun profileTestResultBecomesStaleWhenConnectionChanges() {
        val profile = AiProfile(
            name = "测试",
            baseUrl = "https://example.com",
            apiKey = "secret",
            model = "model"
        )
        val tested = profile.copy(
            testResult = AiTestResult(
                status = AiTestStatus.SUCCESS,
                testedAt = 1L,
                durationMillis = 100L,
                message = "ok",
                configFingerprint = profile.connectionFingerprint()
            )
        )

        assertFalse(tested.isTestResultStale())
        assertTrue(tested.copy(model = "other").isTestResultStale())
        assertTrue(tested.copy(apiKey = "changed").isTestResultStale())
    }

    private fun assertHeadings(content: String, vararg headings: String) {
        var previousIndex = -1
        headings.forEach { heading ->
            val index = content.indexOf(heading)
            assertTrue("Missing heading: $heading", index >= 0)
            assertTrue("Heading order is invalid: $heading", index > previousIndex)
            previousIndex = index
        }
    }

    private fun legacyFingerprint(
        prompt: AiPrompt,
        config: AiConfig,
        type: AiExplanationType
    ): String {
        val raw = listOf(
            "v1",
            config.baseUrl.trim(),
            config.model.trim(),
            type.value,
            prompt.system,
            prompt.user
        ).joinToString("\u001f")
        return MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
