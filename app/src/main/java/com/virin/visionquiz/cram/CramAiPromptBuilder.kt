package com.virin.visionquiz.cram

import com.virin.visionquiz.ai.AiConfig
import com.virin.visionquiz.ai.AiPrompt
import com.virin.visionquiz.dao.Quiz
import com.virin.visionquiz.dao.inferredUiType
import java.security.MessageDigest

data class CramQuestionChunk(
    val moduleLabel: String,
    val moduleKey: String,
    val partIndex: Int,
    val partCount: Int,
    val quizzes: List<Quiz>
) {
    val cacheKey: String
        get() = "${moduleKey}_$partIndex"
}

object CramAiPromptBuilder {
    private const val PROMPT_VERSION = "cram-ai-v3"
    private const val DEFAULT_MODULE = "未分类考点"
    private const val MAX_QUESTIONS_PER_CHUNK = 24
    private const val MAX_CHARS_PER_CHUNK = 22_000
    private const val MAX_MODULE_SUMMARY_CHARS = 3_600
    private const val MAX_SYNTHESIS_INPUT_CHARS = 18_000

    fun chunkQuestions(
        quizzes: List<Quiz>,
        maxQuestions: Int = MAX_QUESTIONS_PER_CHUNK,
        maxChars: Int = MAX_CHARS_PER_CHUNK
    ): List<CramQuestionChunk> {
        require(maxQuestions > 0)
        require(maxChars > 0)
        if (quizzes.isEmpty()) return emptyList()

        val groups = quizzes
            .groupBy(::moduleLabel)
            .entries
            .sortedWith(
                compareByDescending<Map.Entry<String, List<Quiz>>> { it.value.size }
                    .thenBy { it.key }
            )

        return groups.flatMap { (label, moduleQuizzes) ->
            val rawParts = mutableListOf<MutableList<Quiz>>()
            var current = mutableListOf<Quiz>()
            var currentChars = 0
            moduleQuizzes
                .sortedWith(compareBy<Quiz> { it.sourceRow ?: Int.MAX_VALUE }.thenBy { it.id })
                .forEach { quiz ->
                    val estimatedChars = compactQuestion(quiz).length
                    val mustSplit = current.isNotEmpty() &&
                        (current.size >= maxQuestions || currentChars + estimatedChars > maxChars)
                    if (mustSplit) {
                        rawParts += current
                        current = mutableListOf()
                        currentChars = 0
                    }
                    current += quiz
                    currentChars += estimatedChars
                }
            if (current.isNotEmpty()) rawParts += current

            val moduleKey = stableKey(label)
            rawParts.mapIndexed { index, part ->
                CramQuestionChunk(
                    moduleLabel = label,
                    moduleKey = moduleKey,
                    partIndex = index + 1,
                    partCount = rawParts.size,
                    quizzes = part
                )
            }
        }
    }

    fun buildModulePrompt(config: AiConfig, chunk: CramQuestionChunk): AiPrompt {
        return AiPrompt(
            system = buildSystemPrompt(config),
            user = buildString {
                appendLine("任务：分析题库模块「${chunk.moduleLabel}」")
                appendLine("这是该模块第 ${chunk.partIndex}/${chunk.partCount} 批，共 ${chunk.quizzes.size} 题。")
                appendLine()
                appendLine("严格要求：")
                appendLine("1. 只依据下方题目及题库自带依据/备注归纳，不得补造法规条文、数字或出处。")
                appendLine("2. 把同一知识点的判断、单选、多选变形合并成可迁移的“母规则”。")
                appendLine("3. 数字、时限、金额、倍数、主体、前置条件、例外和后果必须保留限定语。")
                appendLine("4. 题库自身没有给出正确说法时，只指出陷阱，不得自行脑补完整规则。")
                appendLine("5. 答案字母分布、最长选项、绝对词等只能放在“统计观察”，不得冒充知识规律。")
                appendLine("6. 每条母规则或数字事实后附支持它的 [题#源序号]；没有源序号时用 [ID:数据库题号]。")
                appendLine()
                appendLine("输出格式（标题与顺序必须保持）：")
                appendLine("### 汇总胶囊")
                appendLine("- 总计不超过 300 字，必须各用一行覆盖：母规则、数字/时限、主体/条件、陷阱、题号。")
                appendLine("- 即使某项没有可靠内容，也写“无可靠项”，不得省略任何一行。")
                appendLine("### 高频母规则")
                appendLine("- 4–10 条，每条先写结论，再写限定条件与题号索引。")
                appendLine("### 数字与时限")
                appendLine("- 按“场景 → 数字 → 条件/后果”写；没有可靠数字就写“本批无可靠数字事实”。")
                appendLine("### 主体与条件")
                appendLine("- 区分谁负责、何时适用、例外是什么。")
                appendLine("### 易错陷阱")
                appendLine("- 重点写主体偷换、数字偷换、条件增删、强弱措辞和多选漏项。")
                appendLine("### 统计观察")
                appendLine("- 只报告本批可复核的现象，并明确“不能替代知识判断”。")
                appendLine("### 题号索引")
                appendLine("- 用“知识点：题号”压缩列出。")
                appendLine()
                appendLine("题目材料：")
                chunk.quizzes.forEach { quiz ->
                    appendLine(compactQuestion(quiz))
                }
            }
        )
    }

    fun buildFinalReportPrompt(
        config: AiConfig,
        libraryName: String,
        questionCount: Int,
        localSummary: String,
        moduleSummaries: List<Pair<String, String>>,
        incompleteChunkCount: Int = 0
    ): AiPrompt {
        val summaryText = boundedModuleSummaries(moduleSummaries)
        return AiPrompt(
            system = buildSystemPrompt(config),
            user = buildString {
                appendLine("任务：把题库分批分析结果合成为一份“3天及格冲刺总稿”。")
                appendLine("题库：$libraryName；题量：$questionCount。")
                if (incompleteChunkCount > 0) {
                    appendLine("注意：有 $incompleteChunkCount 个分块分析失败，必须在开头明确标注报告并非全量。")
                }
                appendLine()
                appendLine("总目标：面向当前不足 50 分、3 天后考试的用户，优先保住高覆盖、易混淆、可快速记忆的得分点。")
                appendLine("不要平均讲解全部模块，不要用空泛鼓励占篇幅。不得根据答案字母规律猜题。")
                appendLine("合并重复规则；有冲突时列出冲突题号并提醒核对，不得擅自选边。")
                appendLine("题号引用必须原样保留为 [题#源序号] 或 [ID:数据库题号]；不得简写成 [纯数字]。")
                appendLine()
                appendLine("输出格式（标题与顺序必须保持）：")
                appendLine("# 3天及格冲刺总纲")
                appendLine("先用 4–7 行告诉用户三天最应该做什么、放弃什么。")
                appendLine("## 第一优先级：先背这些母规则")
                appendLine("给出覆盖面最高的 12–20 条，附题号索引。")
                appendLine("## 数字与时限速记")
                appendLine("用短句和数字链整理；每条保留场景、条件、单位和题号。")
                appendLine("## 主体、条件与处理后果")
                appendLine("用成组对照整理最容易偷换的主体和条件。")
                appendLine("## 高频陷阱")
                appendLine("给出看到什么词就核对什么条件的可执行清单。")
                appendLine("## 多选保分法")
                appendLine("只写基于题库知识的清单完整性与条件核对方法，禁止宣称固定答案个数。")
                appendLine("## 3天安排")
                appendLine("按第1天建骨架、第2天补数字与多选、第3天只做回忆和错题安排，给出每轮建议题量。")
                appendLine("## 考前20分钟口令")
                appendLine("压缩为可快速默念的短句，最多 30 条，准确优先于押韵。")
                appendLine("## 完全不会时的最后策略")
                appendLine("把可靠排除法与弱统计观察明确分开，并强调统计不能替代知识。")
                appendLine("## 题号索引")
                appendLine("按主题列出最值得回看的源题号。")
                appendLine()
                appendLine("本地统计摘要：")
                appendLine(localSummary.take(MAX_LOCAL_SUMMARY_CHARS))
                appendLine()
                appendLine("各模块分批分析：")
                appendLine("以下已纳入全部 ${moduleSummaries.size} 个成功分块；每块内容按总上下文预算等额压缩。")
                append(summaryText)
            }
        )
    }

    fun fingerprint(prompt: AiPrompt, config: AiConfig): String {
        val raw = listOf(
            PROMPT_VERSION,
            config.baseUrl.trim(),
            config.model.trim(),
            prompt.system,
            prompt.user
        ).joinToString("\u001f")
        return sha256(raw)
    }

    fun quizFingerprint(quizzes: List<Quiz>): String {
        val raw = buildString {
            appendLine(PROMPT_VERSION)
            quizzes.sortedBy { it.id }.forEach { quiz ->
                append(quiz.id).append('\u001f')
                append(quiz.prompt).append('\u001f')
                append(quiz.options.joinToString("\u001e")).append('\u001f')
                append(quiz.answer.sorted().joinToString(",")).append('\u001f')
                append(quiz.questionType.orEmpty()).append('\u001f')
                append(quiz.explanation.orEmpty()).append('\u001f')
                append(quiz.reference.orEmpty()).append('\u001f')
                append(quiz.sourceRow ?: "").append('\n')
            }
        }
        return sha256(raw)
    }

    private fun buildSystemPrompt(config: AiConfig): String {
        return buildString {
            appendLine(config.cramAnalysisPrompt.trim())
            appendLine()
            appendLine("标准答案由应用提供，不得修改。题库依据优先级高于你的常识。")
            appendLine("若题库内容不足、冲突或可能过时，必须明确标注“需核对”，不得编造。")
            append("仅输出简体中文 Markdown 正文，不要输出 HTML、表格、外部链接或代码围栏。")
        }
    }

    private fun moduleLabel(quiz: Quiz): String {
        val reference = quiz.reference
            ?.trim()
            ?.replace(Regex("\\s+"), " ")
            .orEmpty()
        val referenceModule = if (reference.isBlank()) {
            null
        } else {
            DOCUMENT_TITLE_REGEX.find(reference)?.value
                ?: reference
                    .substringBefore('；')
                    .substringBefore(';')
                    .replace(TRAILING_ARTICLE_REGEX, "")
                    .trim(' ', '，', ',', '。', ':', '：')
                    .ifBlank { reference }
                    .take(80)
        }
        return referenceModule
            ?: quiz.questionType?.trim()?.takeIf { it.isNotBlank() }
            ?: DEFAULT_MODULE
    }

    private fun compactQuestion(quiz: Quiz): String {
        val sourceLabel = quiz.sourceRow?.let { "题#$it" } ?: "ID:${quiz.id}"
        return buildString {
            appendLine("[$sourceLabel｜${quiz.inferredUiType().label}]")
            appendLine("题干：${quiz.prompt.trim()}")
            quiz.options.forEachIndexed { index, option ->
                appendLine("${'A' + index}. ${option.trim()}")
            }
            appendLine(
                "标准答案：" + quiz.answer.sorted()
                    .joinToString(separator = "") { index -> ('A' + index).toString() }
            )
            quiz.reference?.trim()?.takeIf { it.isNotBlank() }?.let {
                appendLine("题库依据：$it")
            }
            quiz.explanation?.trim()?.takeIf { it.isNotBlank() }?.let {
                appendLine("题库备注/解析：$it")
            }
            appendLine()
        }
    }

    private fun boundedModuleSummaries(
        moduleSummaries: List<Pair<String, String>>
    ): String {
        if (moduleSummaries.isEmpty()) return ""
        val fullHeadings = moduleSummaries.map { (label, _) ->
            "### 模块：${label.take(MAX_MODULE_LABEL_CHARS)}\n"
        }
        val fullHeadingChars = fullHeadings.sumOf(String::length)
        val headings = if (
            fullHeadingChars + moduleSummaries.size <= MAX_SYNTHESIS_INPUT_CHARS
        ) {
            fullHeadings
        } else {
            val perHeadingBudget = (
                (MAX_SYNTHESIS_INPUT_CHARS - moduleSummaries.size) /
                    moduleSummaries.size
                ).coerceAtLeast(MIN_COMPACT_HEADING_CHARS)
            moduleSummaries.mapIndexed { index, (label, _) ->
                val prefix = "[${index + 1}]"
                val labelBudget = (perHeadingBudget - prefix.length - 1).coerceAtLeast(0)
                "$prefix${label.take(labelBudget)}\n"
            }
        }
        val headingChars = headings.sumOf(String::length)
        val contentBudget = (
            MAX_SYNTHESIS_INPUT_CHARS - headingChars - moduleSummaries.size
            ).coerceAtLeast(0)
        val baseContentChars = contentBudget / moduleSummaries.size
        val extraContentBlocks = contentBudget % moduleSummaries.size
        val result = StringBuilder()
        moduleSummaries.forEachIndexed { index, (_, content) ->
            val allocation = (
                baseContentChars + if (index < extraContentBlocks) 1 else 0
                ).coerceAtMost(MAX_MODULE_SUMMARY_CHARS)
            result.append(headings[index])
            result.append(compactModuleSummary(content, allocation))
            result.append('\n')
        }
        return result.take(MAX_SYNTHESIS_INPUT_CHARS).toString()
    }

    private fun compactModuleSummary(content: String, maxChars: Int): String {
        if (maxChars <= 0 || content.isBlank()) return ""
        if (content.length <= maxChars) return content
        val sectionMatches = MODULE_SECTION_REGEX.findAll(content).toList()
        if (sectionMatches.isEmpty()) return content.take(maxChars)
        val sections = sectionMatches.mapIndexed { index, match ->
            val bodyStart = match.range.last + 1
            val bodyEnd = sectionMatches.getOrNull(index + 1)?.range?.first ?: content.length
            ModuleSummarySection(
                title = match.groupValues[1].trim(),
                body = content.substring(bodyStart, bodyEnd)
                    .replace(Regex("""\s+"""), " ")
                    .trim()
            )
        }
        sections.firstOrNull { it.title.contains("汇总胶囊") }?.let { capsule ->
            val compactCapsule = "【汇总胶囊】${capsule.body}"
            if (compactCapsule.length >= maxChars * CAPSULE_BUDGET_PERCENT / 100) {
                return compactCapsule.take(maxChars)
            }
        }
        val headings = sections.map { "【${it.title.take(MAX_COMPACT_SECTION_TITLE_CHARS)}】" }
        val headingChars = headings.sumOf(String::length)
        if (headingChars >= maxChars) return headings.joinToString("").take(maxChars)
        val bodyBudget = maxChars - headingChars
        val baseBodyChars = bodyBudget / sections.size
        val extraBodySections = bodyBudget % sections.size
        return buildString(maxChars) {
            sections.forEachIndexed { index, section ->
                append(headings[index])
                val allocation = baseBodyChars + if (index < extraBodySections) 1 else 0
                append(section.body.take(allocation))
            }
        }.take(maxChars)
    }

    private data class ModuleSummarySection(
        val title: String,
        val body: String
    )

    private fun stableKey(value: String): String = sha256(value).take(16)

    private fun sha256(value: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private val DOCUMENT_TITLE_REGEX = Regex("""《[^》]{2,80}》""")
    private val TRAILING_ARTICLE_REGEX = Regex(
        """(?:第\s*[0-9零〇一二两三四五六七八九十百千万]+\s*(?:条|章|节).*)$"""
    )
    private val MODULE_SECTION_REGEX = Regex("""(?m)^###\s+(.+?)\s*$""")

    private const val MAX_MODULE_LABEL_CHARS = 80
    private const val MIN_COMPACT_HEADING_CHARS = 8
    private const val MAX_COMPACT_SECTION_TITLE_CHARS = 8
    private const val CAPSULE_BUDGET_PERCENT = 70
    private const val MAX_LOCAL_SUMMARY_CHARS = 4_000
}
