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
    MNEMONIC("mnemonic", "口诀"),
    QUESTION_EXTENSION("question_extension", "举一反三"),
    SIMILAR_ANALYSIS("similar_analysis", "相似题分析"),
    EXISTING_SIMILAR_ANALYSIS("similar_existing_analysis", "相似题辨析"),
    CONTEXTUAL_SUGGESTIONS("contextual_suggestions", "上下文建议"),
    CONTEXTUAL_QA("contextual_qa", "上下文问答")
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
    val questionExtensionPrompt: String = AiPromptBuilder.DEFAULT_QUESTION_EXTENSION_PROMPT,
    val similarAnalysisPrompt: String = AiPromptBuilder.DEFAULT_SIMILAR_ANALYSIS_PROMPT,
    val contextualSuggestionsPrompt: String = AiPromptBuilder.DEFAULT_CONTEXTUAL_SUGGESTIONS_PROMPT,
    val contextualQaPrompt: String = AiPromptBuilder.DEFAULT_CONTEXTUAL_QA_PROMPT,
    val profileId: String = "",
    val profileName: String = ""
) {
    fun promptFor(type: AiExplanationType): String = when (type) {
        AiExplanationType.QUICK_REVIEW -> quickReviewPrompt
        AiExplanationType.DETAILED_ANALYSIS -> analysisPrompt
        AiExplanationType.ANALYSIS -> analysisPrompt
        AiExplanationType.TECHNIQUE -> techniquePrompt
        AiExplanationType.MNEMONIC -> mnemonicPrompt
        AiExplanationType.QUESTION_EXTENSION -> questionExtensionPrompt
        AiExplanationType.SIMILAR_ANALYSIS -> similarAnalysisPrompt
        AiExplanationType.EXISTING_SIMILAR_ANALYSIS -> similarAnalysisPrompt
        AiExplanationType.CONTEXTUAL_SUGGESTIONS -> contextualSuggestionsPrompt
        AiExplanationType.CONTEXTUAL_QA -> contextualQaPrompt
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
        internal const val FINGERPRINT_VERSION = "v4"
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
            "公式使用普通文本或行内代码表示，复杂对比使用分节列表。" +
            "正文中必须用 Markdown 粗体标记关键考点、限定条件、答案依据、易错词和结论性关键词。"

    const val DEFAULT_ANALYSIS_PROMPT =
        "先指出题目所属领域和核心知识点，再给出结论并解释标准答案成立的依据。" +
            "结合该领域的概念、规则、原理、计算过程或实务场景，逐项说明各选项正确或错误的关键原因；" +
            "若用户答错，指出其判断偏差和容易混淆之处。信息不足时明确分析边界，不补造题目条件。" +
            "用 **粗体** 标出核心考点、关键限定条件、答案依据和易错点。" +
            "控制在 200–500 字。"

    const val DEFAULT_QUICK_REVIEW_PROMPT =
        "用最短时间帮助用户完成本题复盘。答错时直接指出用户答案与标准答案之间的关键判断差异和具体误区；" +
            "答对时强调最容易混淆的边界条件。最后给出一个准确、可迁移的记忆点。" +
            "用 **粗体** 标出关键判断词、答案依据和记忆点。" +
            "不要逐项展开全部选项，不要重复题干，控制在 80–180 字。"

    const val DEFAULT_TECHNIQUE_PROMPT =
        "根据题目所属领域提炼关键词、限定条件和判断依据，给出 2–4 步可执行的分析、计算、排除或核验方法。" +
            "说明该领域常见的概念混淆、条件遗漏或实务陷阱，并总结可迁移到同类题目的判断框架。" +
            "用 **粗体** 标出关键词、限定条件和每步的判断抓手。" +
            "不要使用只适用于其他学科的套路。控制在 120–300 字。"

    const val DEFAULT_MNEMONIC_PROMPT =
        "根据题目所属领域提炼准确、可复用的记忆要点。适合口诀时，先给出不超过 24 个汉字的简短口诀，" +
            "再解释其与知识点的对应关系；不适合口诀时，改用对照、分类、顺序或关键条件帮助记忆。" +
            "用 **粗体** 标出口诀、关键条件和适用边界。" +
            "不得为了押韵或简化而歪曲概念、遗漏关键例外或编造事实。控制在 80–180 字。"

    const val DEFAULT_QUESTION_EXTENSION_PROMPT =
        "根据本题的核心知识点和解题思路，举一反三，延伸出 2–3 个与本题考查方向相同但情境或条件不同的变式问题。" +
            "每个变式问题需附简要说明，指出其考查侧重点和与原题的关键差异。" +
            "用 **粗体** 标出原题考点、变式条件和关键差异。" +
            "变式问题应具有实际练习价值，避免简单替换数字或无意义的文字修改。控制在 150–350 字。"

    const val DEFAULT_SIMILAR_ANALYSIS_PROMPT =
        "分析本题的核心考点和易混淆点，构造 2–3 道与本题考查知识点相似但容易做错的对比题。" +
            "每道对比题需给出题干、选项、正确答案，并简要说明其与原题的相似之处和容易出错的关键区别。" +
            "用 **粗体** 标出共同考点、差异条件、答案影响和易混淆词。" +
            "帮助用户识别同类题目中的细微差异，提高辨析能力。控制在 200–400 字。"

    const val DEFAULT_CONTEXTUAL_SUGGESTIONS_PROMPT =
        "根据本题的题干、选项、标准答案和用户作答情况，生成 3 个有助于深入理解本题知识点的学习建议。" +
            "每个建议应是一个具体的、可直接回答的问题或思考方向，与本题内容紧密相关。" +
            "建议应覆盖不同角度：如易错辨析、实际应用场景、相关知识点延伸等。" +
            "严格按以下格式输出，每行一条，不要添加其他内容：\n1. 建议内容\n2. 建议内容\n3. 建议内容"

    const val DEFAULT_CONTEXTUAL_QA_PROMPT =
        "用户正在学习一道题目，现针对以下学习建议进行深入探讨。请结合原题的知识点，" +
            "对用户的建议问题给出详细、准确的解答。解答应帮助用户加深理解，" +
            "可以引用相关概念、规则、实例进行说明。控制在 150–300 字。"

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

    fun buildExistingSimilarAnalysis(
        quiz: Quiz,
        similarQuizzes: List<Quiz>,
        selectedAnswer: Set<Int>?
    ): AiPrompt {
        val standard = formatAnswer(quiz.answer)
        val userAnswer = selectedAnswer?.takeIf { it.isNotEmpty() }?.let(::formatAnswer) ?: "未作答"
        val result = when {
            selectedAnswer == null || selectedAnswer.isEmpty() -> "未作答"
            quiz.isCorrectAnswer(selectedAnswer) -> "正确"
            else -> "错误"
        }
        return AiPrompt(
            system = SYSTEM_PROMPT,
            user = buildString {
                appendLine("任务：${AiExplanationType.EXISTING_SIMILAR_ANALYSIS.label}")
                appendLine("分析当前题与已检索到的相似题之间的考点关系、逐题差异和答案影响。")
                appendLine("只基于当前题和相似题内容进行辨析，不要构造新题，不要修改标准答案。")
                appendLine("候选相似题会以压缩摘要形式全部给出；请自行选择最能体现考点关系和差异的题目进行对照，不要机械按顺序选题。")
                appendLine("最多选择 3 道候选题展开对照，优先选择关系最清晰、差异最有教学价值的题。")
                appendLine("对每道被选中的相似题都要说明：相同点、关键差异、答案影响。")
                appendLine("若相似题之间差异很小，也要指出最可能导致误判的细节。")
                appendLine("控制在 260–520 字，必须完整写完四个标题，不要在句中结束。")
                appendLine()
                appendLine("输出格式（必须严格遵守，标题文字和顺序不得改变）：")
                appendLine(outputFormat(AiExplanationType.EXISTING_SIMILAR_ANALYSIS))
                appendLine()
                appendLine("当前题：")
                appendLine("题型：${quiz.inferredUiType().label}")
                appendLine("题干：${quiz.prompt}")
                appendLine("选项：")
                appendLine(formatOptions(quiz))
                appendLine("标准答案：$standard")
                appendLine("用户答案：$userAnswer")
                appendLine("作答结果：$result")
                appendLine()
                appendLine("相似题：")
                similarQuizzes.forEachIndexed { index, similar ->
                    appendLine("${index + 1}. 题型：${similar.inferredUiType().label}")
                    appendLine("题干：${similar.prompt.compactForPrompt(MAX_SIMILAR_PROMPT_CHARS)}")
                    appendLine("选项：")
                    appendLine(formatOptions(similar, maxOptionChars = MAX_SIMILAR_OPTION_CHARS))
                    appendLine("标准答案：${formatAnswer(similar.answer)}")
                    if (index < similarQuizzes.lastIndex) {
                        appendLine()
                    }
                }
            }
        )
    }

    internal fun outputFormat(type: AiExplanationType): String = when (type) {
        AiExplanationType.QUICK_REVIEW ->
            """
            ### 结论
            用一两句话给出标准答案成立的核心理由，并用**粗体**标出结论关键词。
            ### 关键区分
            指出用户判断与正确思路的关键差异；答对时说明最容易混淆的边界，用**粗体**标出限定条件。
            ### 记忆点
            给出一条准确、简短且可迁移的记忆方法，用**粗体**标出记忆抓手。
            """.trimIndent()
        AiExplanationType.DETAILED_ANALYSIS,
        AiExplanationType.ANALYSIS ->
            """
            ### 结论
            用简洁段落给出结论，并用**粗体**标出核心知识点。
            ### 核心依据
            说明规则、原理、计算过程或实务依据，并用**粗体**标出答案依据。
            ### 选项分析
            使用无序列表逐项分析；没有选项时说明题目关键判断点，用**粗体**标出关键排除词。
            ### 作答复盘
            结合用户作答指出正确思路、具体误区或未作答时的建议，用**粗体**标出误区。
            """.trimIndent()
        AiExplanationType.TECHNIQUE ->
            """
            ### 关键信息
            使用无序列表提炼关键词、限定条件和判断依据，并用**粗体**标出关键词。
            ### 解题步骤
            使用有序列表给出 2–4 步可执行方法，用**粗体**标出每步抓手。
            ### 常见陷阱
            使用无序列表说明易错点，用**粗体**标出易错词。
            ### 迁移方法
            总结可复用于同类题目的判断框架，用**粗体**标出适用边界。
            """.trimIndent()
        AiExplanationType.MNEMONIC ->
            """
            ### 记忆要点
            给出准确、简短的口诀或其他记忆方式，用**粗体**标出口诀或核心词。
            ### 对应关系
            使用无序列表解释记忆内容与知识点的对应关系，用**粗体**标出对应关键词。
            ### 适用边界
            说明例外、限制或容易误用的情形，用**粗体**标出限制条件。
            """.trimIndent()
        AiExplanationType.QUESTION_EXTENSION ->
            """
            ### 原题要点
            简要归纳原题的核心知识点和解题关键，用**粗体**标出原题考点。
            ### 变式问题
            使用有序列表给出 2–3 个变式问题，每个问题后附简要考查说明，用**粗体**标出变式条件。
            ### 拓展建议
            总结该类题目的拓展学习方向，用**粗体**标出迁移方向。
            """.trimIndent()
        AiExplanationType.SIMILAR_ANALYSIS ->
            """
            ### 考点归纳
            简要归纳本题的核心考点和易混淆点，用**粗体**标出考点。
            ### 对比题
            使用有序列表给出 2–3 道对比题，每题包含题干、选项、正确答案和关键区别说明，用**粗体**标出关键区别。
            ### 辨析要点
            总结识别此类题目细微差异的方法，用**粗体**标出判断抓手。
            """.trimIndent()
        AiExplanationType.EXISTING_SIMILAR_ANALYSIS ->
            """
            ### 考点关系
            用 2–4 个短标签概括共同考点，例如“**绝缘介质判断** / **故障现象识别** / **保护动作边界**”。
            ### 题目对照
            按最多 3 道被选中的相似题编号逐题说明：相似题 1：**相同点** / **关键差异** / **答案影响**。
            ### 混淆点
            说明哪些词、条件、对象或选项最容易导致把两题看成同一题，用**粗体**标出混淆词。
            ### 做题抓手
            给出 2–3 条可迁移的判断方法，用**粗体**标出核验动作。
            """.trimIndent()
        AiExplanationType.CONTEXTUAL_SUGGESTIONS ->
            """
            严格按以下格式输出，每行一条，不要添加标题或其他内容：
            1. 建议内容
            2. 建议内容
            3. 建议内容
            """.trimIndent()
        AiExplanationType.CONTEXTUAL_QA ->
            """
            ### 解答
            针对学习建议给出详细解答，结合原题知识点说明，用**粗体**标出关键概念。
            ### 要点总结
            用简洁的要点列表总结核心收获，用**粗体**标出重点。
            """.trimIndent()
    }

    private fun formatAnswer(answer: Set<Int>): String {
        return answer.sorted().joinToString("") { answerLetter(it).toString() }
    }

    private fun formatOptions(
        quiz: Quiz,
        maxOptionChars: Int = Int.MAX_VALUE
    ): String {
        return quiz.options.mapIndexed { index, option ->
            "${answerLetter(index)}. ${option.compactForPrompt(maxOptionChars)}"
        }.joinToString("\n")
    }

    private fun String.compactForPrompt(maxChars: Int): String {
        val compact = trim()
            .replace(Regex("\\s+"), " ")
        if (compact.length <= maxChars) return compact
        return compact.take(maxChars).trimEnd() + "..."
    }

    private fun answerLetter(index: Int): Char = ('A'.code + index).toChar()

    private const val MAX_SIMILAR_PROMPT_CHARS = 220
    private const val MAX_SIMILAR_OPTION_CHARS = 80
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
