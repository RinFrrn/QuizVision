package com.virin.visionquiz.ai

import com.virin.visionquiz.dao.Quiz
import com.virin.visionquiz.dao.inferredUiType
import java.net.URI
import java.security.MessageDigest
import java.util.UUID

enum class AiExplanationType(val value: String, val label: String) {
    QUICK_REVIEW("quick_review", "快速复盘"),
    DETAILED_ANALYSIS("detailed_analysis", "详细解析"),
    ANALYSIS("analysis", "解析"),
    TECHNIQUE("technique", "技巧"),
    MNEMONIC("mnemonic", "口诀")
}

data class AiConfig(
    val enabled: Boolean,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val quickReviewPrompt: String = AiPromptBuilder.DEFAULT_QUICK_REVIEW_PROMPT,
    val analysisPrompt: String,
    val techniquePrompt: String,
    val mnemonicPrompt: String,
    val profileId: String = "",
    val profileName: String = ""
) {
    fun promptFor(type: AiExplanationType): String = when (type) {
        AiExplanationType.QUICK_REVIEW -> quickReviewPrompt
        AiExplanationType.DETAILED_ANALYSIS -> analysisPrompt
        AiExplanationType.ANALYSIS -> analysisPrompt
        AiExplanationType.TECHNIQUE -> techniquePrompt
        AiExplanationType.MNEMONIC -> mnemonicPrompt
    }

    fun isComplete(): Boolean {
        return enabled && baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
    }
}

enum class AiTestStatus {
    NOT_TESTED,
    SUCCESS,
    FAILURE
}

data class AiTestResult(
    val status: AiTestStatus = AiTestStatus.NOT_TESTED,
    val testedAt: Long = 0L,
    val durationMillis: Long = 0L,
    val message: String = "",
    val configFingerprint: String = ""
)

data class AiProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val testResult: AiTestResult = AiTestResult()
) {
    fun connectionFingerprint(): String {
        val apiKeyHash = sha256(apiKey.trim())
        return sha256(
            listOf(baseUrl.trim(), model.trim(), apiKeyHash).joinToString("\u001f")
        )
    }

    fun isTestResultStale(): Boolean {
        return testResult.status != AiTestStatus.NOT_TESTED &&
            testResult.configFingerprint != connectionFingerprint()
    }

    fun isComplete(): Boolean {
        return baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
    }
}

private fun sha256(value: String): String {
    return MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

data class AiPrompt(
    val system: String,
    val user: String
) {
    fun fingerprint(config: AiConfig, type: AiExplanationType): String {
        val raw = listOf(
            FINGERPRINT_VERSION,
            config.baseUrl.trim(),
            config.model.trim(),
            type.value,
            system,
            user
        ).joinToString("\u001f")
        return MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    companion object {
        internal const val FINGERPRINT_VERSION = "v3"
    }
}

object AiPromptBuilder {
    const val SYSTEM_PROMPT =
        "你是一名严谨的跨学科题目解析助手。先识别题目涉及的学科、行业或职业场景，" +
            "再使用该领域通行的概念、术语和推理方式进行解释。标准答案由应用提供，" +
            "不得擅自修改或另行判定答案。仅基于题干、选项、标准答案、用户作答及可靠的一般知识分析，" +
            "不要假设题目未提供的条件。若信息不足、题意有歧义，或标准答案可能与常识、规范存在冲突，" +
            "应明确说明，但仍以标准答案为主完成解释。涉及法律、医学、安全、财务等高风险或时效性内容时，" +
            "说明结论可能受地区、版本、时间和具体情境限制，不编造条款、数据、标准或出处。" +
            "使用简体中文输出 Markdown 正文，表达准确、清晰、简洁。仅使用标题、段落、粗体、" +
            "有序或无序列表、引用和行内代码；禁止 HTML、表格、图片、外部链接、LaTeX 公式，" +
            "禁止用代码围栏包裹全文，也不要添加问候语、免责声明式开场或与当前任务无关的内容。" +
            "公式使用普通文本或行内代码表示，复杂对比使用分节列表。"

    const val DEFAULT_ANALYSIS_PROMPT =
        "先指出题目所属领域和核心知识点，再给出结论并解释标准答案成立的依据。" +
            "结合该领域的概念、规则、原理、计算过程或实务场景，逐项说明各选项正确或错误的关键原因；" +
            "若用户答错，指出其判断偏差和容易混淆之处。信息不足时明确分析边界，不补造题目条件。" +
            "控制在 200–500 字。"

    const val DEFAULT_QUICK_REVIEW_PROMPT =
        "用最短时间帮助用户完成本题复盘。答错时直接指出用户答案与标准答案之间的关键判断差异和具体误区；" +
            "答对时强调最容易混淆的边界条件。最后给出一个准确、可迁移的记忆点。" +
            "不要逐项展开全部选项，不要重复题干，控制在 80–180 字。"

    const val DEFAULT_TECHNIQUE_PROMPT =
        "根据题目所属领域提炼关键词、限定条件和判断依据，给出 2–4 步可执行的分析、计算、排除或核验方法。" +
            "说明该领域常见的概念混淆、条件遗漏或实务陷阱，并总结可迁移到同类题目的判断框架。" +
            "不要使用只适用于其他学科的套路。控制在 120–300 字。"

    const val DEFAULT_MNEMONIC_PROMPT =
        "根据题目所属领域提炼准确、可复用的记忆要点。适合口诀时，先给出不超过 24 个汉字的简短口诀，" +
            "再解释其与知识点的对应关系；不适合口诀时，改用对照、分类、顺序或关键条件帮助记忆。" +
            "不得为了押韵或简化而歪曲概念、遗漏关键例外或编造事实。控制在 80–180 字。"

    fun build(
        quiz: Quiz,
        type: AiExplanationType,
        taskPrompt: String,
        selectedAnswer: Set<Int>?
    ): AiPrompt {
        val standard = formatAnswer(quiz.answer)
        val userAnswer = selectedAnswer?.takeIf { it.isNotEmpty() }?.let(::formatAnswer) ?: "未作答"
        val result = when {
            selectedAnswer == null || selectedAnswer.isEmpty() -> "未作答"
            quiz.isCorrectAnswer(selectedAnswer) -> "正确"
            else -> "错误"
        }
        val options = quiz.options.mapIndexed { index, option ->
            "${answerLetter(index)}. $option"
        }.joinToString("\n")
        return AiPrompt(
            system = SYSTEM_PROMPT,
            user = buildString {
                appendLine("任务：${type.label}")
                appendLine(taskPrompt.trim())
                appendLine()
                appendLine("输出格式（必须严格遵守，标题文字和顺序不得改变）：")
                appendLine(outputFormat(type))
                appendLine()
                appendLine("题型：${quiz.inferredUiType().label}")
                appendLine("题干：${quiz.prompt}")
                appendLine("选项：")
                appendLine(options)
                appendLine("标准答案：$standard")
                appendLine("用户答案：$userAnswer")
                append("作答结果：$result")
            }
        )
    }

    internal fun outputFormat(type: AiExplanationType): String = when (type) {
        AiExplanationType.QUICK_REVIEW ->
            """
            ### 结论
            用一两句话给出标准答案成立的核心理由。
            ### 关键区分
            指出用户判断与正确思路的关键差异；答对时说明最容易混淆的边界。
            ### 记忆点
            给出一条准确、简短且可迁移的记忆方法。
            """.trimIndent()
        AiExplanationType.DETAILED_ANALYSIS,
        AiExplanationType.ANALYSIS ->
            """
            ### 结论
            用简洁段落给出结论，并用**粗体**标出核心知识点。
            ### 核心依据
            说明规则、原理、计算过程或实务依据。
            ### 选项分析
            使用无序列表逐项分析；没有选项时说明题目关键判断点。
            ### 作答复盘
            结合用户作答指出正确思路、具体误区或未作答时的建议。
            """.trimIndent()
        AiExplanationType.TECHNIQUE ->
            """
            ### 关键信息
            使用无序列表提炼关键词、限定条件和判断依据。
            ### 解题步骤
            使用有序列表给出 2–4 步可执行方法。
            ### 常见陷阱
            使用无序列表说明易错点。
            ### 迁移方法
            总结可复用于同类题目的判断框架。
            """.trimIndent()
        AiExplanationType.MNEMONIC ->
            """
            ### 记忆要点
            给出准确、简短的口诀或其他记忆方式。
            ### 对应关系
            使用无序列表解释记忆内容与知识点的对应关系。
            ### 适用边界
            说明例外、限制或容易误用的情形。
            """.trimIndent()
    }

    private fun formatAnswer(answer: Set<Int>): String {
        return answer.sorted().joinToString("") { answerLetter(it).toString() }
    }

    private fun answerLetter(index: Int): Char = ('A'.code + index).toChar()
}

object AiEndpointValidator {
    fun buildEndpoint(baseUrl: String): Result<String> = runCatching {
        val trimmed = validateBaseUrl(baseUrl)
        when {
            trimmed.endsWith("/chat/completions") -> trimmed
            trimmed.endsWith("/v1") -> "$trimmed/chat/completions"
            else -> "$trimmed/v1/chat/completions"
        }
    }

    fun buildModelsEndpoint(baseUrl: String): Result<String> = runCatching {
        val trimmed = validateBaseUrl(baseUrl)
        when {
            trimmed.endsWith("/chat/completions") ->
                trimmed.removeSuffix("/chat/completions") + "/models"
            trimmed.endsWith("/v1") -> "$trimmed/models"
            else -> "$trimmed/v1/models"
        }
    }

    private fun validateBaseUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        require(trimmed.isNotBlank()) { "请填写 API 地址" }
        val uri = URI(trimmed)
        require(uri.scheme == "https" || uri.scheme == "http") { "API 地址仅支持 HTTP 或 HTTPS" }
        require(!uri.host.isNullOrBlank()) { "API 地址格式不正确" }
        require(uri.rawQuery == null && uri.rawFragment == null) { "API 地址不能包含查询参数或片段" }
        if (uri.scheme == "http") {
            require(isAllowedHttpHost(uri.host)) { "HTTP 仅允许 localhost、回环地址或局域网私有 IPv4" }
        }
        return trimmed
    }

    internal fun isAllowedHttpHost(host: String): Boolean {
        val normalized = host.lowercase().removePrefix("[").removeSuffix("]")
        if (normalized == "localhost" || normalized == "::1" || normalized == "10.0.2.2") return true
        val parts = normalized.split(".").mapNotNull { it.toIntOrNull() }
        if (parts.size != 4 || parts.any { it !in 0..255 }) return false
        return parts[0] == 127 ||
            parts[0] == 10 ||
            (parts[0] == 192 && parts[1] == 168) ||
            (parts[0] == 172 && parts[1] in 16..31)
    }
}
