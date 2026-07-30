package com.virin.visionquiz.cram

import kotlin.math.roundToInt

object CramLocalContentRenderer {

    fun renderReport(result: CramAnalysisResult): String {
        return buildString {
            appendLine("# 本地题库冲刺分析")
            appendLine()
            appendLine("> 本页只根据已导入题库做可复核统计。答案字母、关键词相关性等不是知识规律，只能作为完全不会时的低可信参考。")
            appendLine()
            appendLine("## 先看结论")
            appendLine()
            appendLine("- 共 **${result.totalQuestionCount} 题**，已拆成 **${result.modules.size} 个模块**。")
            result.moduleCoverages.firstOrNull { it.requestedTopModuleCount == 5 }?.let {
                appendLine("- 先攻 Top ${it.actualModuleCount} 模块，可覆盖约 **${percent(it.coverageOfBank)}%** 的题库。")
            }
            appendLine("- 三天计划覆盖 **${result.threeDayPlan.uniqueQuestionCount} 道不重复题**，自测抽取 **${result.selfTest.actualQuestionCount} 题**。")
            if (result.unreferencedQuestionCount > 0) {
                appendLine("- 有 **${result.unreferencedQuestionCount} 题**没有模块/出处信息，已按题型纳入兜底分析。")
            }
            appendLine()
            appendLine("## 优先模块")
            appendLine()
            result.modules.take(12).forEachIndexed { index, module ->
                val typeText = module.typeCounts
                    .sortedByDescending { it.questionCount }
                    .joinToString("、") { "${it.type.displayName}${it.questionCount}" }
                appendLine(
                    "${index + 1}. **${module.displayName}**：${module.questionCount} 题，" +
                        "覆盖 ${percent(module.ratioOfBank)}%${if (typeText.isNotBlank()) "；$typeText" else ""}"
                )
            }
            appendLine()
            appendLine("## 数字与时限")
            appendLine()
            val reliableNumbers = result.numericFactSummaries
                .filter { it.correctOrSupportedCount > 0 && it.incorrectCount == 0 }
                .sortedWith(
                    compareByDescending<NumericFactSummary> { it.correctOrSupportedCount }
                        .thenByDescending { it.occurrenceCount }
                )
                .take(24)
            if (reliableNumbers.isEmpty()) {
                appendLine("- 本地分析没有提取到可直接背诵的可靠数字；请以题库自带解析和 AI 分块核对为准。")
            } else {
                reliableNumbers.forEach { fact ->
                    val context = fact.contexts.firstOrNull()
                        ?.replace(Regex("""\s+"""), " ")
                        ?.take(120)
                        .orEmpty()
                    appendLine(
                        "- **${fact.normalizedValue}${fact.unit}**（${fact.category.displayName}）" +
                            "：${context.ifBlank { "请结合题干核对适用场景" }}；" +
                            "支持 ${fact.correctOrSupportedCount} 次；题号 ${fact.quizIds.take(8).joinToString("、")}"
                    )
                }
            }
            val conflictingNumbers = result.numericFactSummaries
                .filter { it.correctOrSupportedCount > 0 && it.incorrectCount > 0 }
                .take(12)
            if (conflictingNumbers.isNotEmpty()) {
                appendLine()
                appendLine("### 数字冲突，禁止直接背")
                conflictingNumbers.forEach { fact ->
                    appendLine(
                        "- **${fact.normalizedValue}${fact.unit}**：同一语境同时出现支持与错误陈述；" +
                            "请核对题号 ${fact.quizIds.take(8).joinToString("、")}。"
                    )
                }
            }
            appendLine()
            appendLine("## 判断题关键词观察")
            appendLine()
            appendLine("- ${result.judgement.usagePolicy}")
            result.judgement.keywords
                .filter { it.reliability != EvidenceReliability.INSUFFICIENT }
                .take(12)
                .forEach { keyword ->
                    val tendency = when (keyword.majorityOutcome) {
                        JudgementOutcome.TRUE -> "在当前题库中更常出现在正确陈述"
                        JudgementOutcome.FALSE -> "在当前题库中更常出现在错误陈述"
                        JudgementOutcome.UNKNOWN -> "没有稳定倾向"
                    }
                    appendLine(
                        "- **${keyword.keyword}**：$tendency；样本 ${keyword.knownOutcomeCount}，" +
                            "经验命中 ${percent(keyword.empiricalAccuracy)}%，可信度 ${keyword.reliability.displayLabel()}"
                    )
                }
            appendLine()
            appendLine("## 多选题保分提醒")
            appendLine()
            appendLine("- 多选共 **${result.multipleChoice.questionCount} 题**，平均选择 **${formatOneDecimal(result.multipleChoice.averageSelectedOptionCount)} 项**。")
            appendLine("- 先逐项核对 **主体、适用条件、完整并列清单、强弱措辞**；答案项数分布不能替代知识判断。")
            result.multipleChoice.selectionCountDistribution.forEach {
                appendLine("- 当前题库选 ${it.selectedOptionCount} 项：${it.questionCount} 题（${percent(it.ratio)}%），仅作描述统计。")
            }
            appendLine()
            appendLine("## 三天安排")
            appendLine()
            result.threeDayPlan.days.forEach { day ->
                appendLine("### 第${day.day}天 · ${day.title}")
                appendLine()
                appendLine("- 重点：${day.focus}")
                appendLine("- 练习：${day.quizIds.size} 题；其中新题 ${day.newQuizIds.size}、回看 ${day.reviewQuizIds.size}")
                if (day.modules.isNotEmpty()) {
                    appendLine("- 模块：${day.modules.joinToString("、") { it.displayName }}")
                }
                appendLine()
            }
            appendLine("## 重复题与冲突核对")
            appendLine()
            if (result.duplicateGroups.isEmpty()) {
                appendLine("- 未发现完全相同题干的重复组。")
            } else {
                result.duplicateGroups.take(20).forEach {
                    appendLine("- 同题出现 ${it.questionCount} 次：题号 ${it.quizIds.joinToString("、")}")
                }
            }
            appendLine()
            appendLine("## 30题自测")
            appendLine()
            appendLine("- 已按模块、题型与优先级抽取 **${result.selfTest.actualQuestionCount} 题**。")
            result.selfTest.sections.forEach { section ->
                appendLine("- ${section.type.displayName}：${section.quizIds.size} 题")
            }
            if (result.warnings.isNotEmpty()) {
                appendLine()
                appendLine("## 数据提醒")
                appendLine()
                result.warnings.forEach { appendLine("- $it") }
            }
        }.trim()
    }

    fun renderQuickCard(result: CramAnalysisResult): String {
        return buildString {
            appendLine("# 考前20分钟速记卡")
            appendLine()
            appendLine("## 先扫模块")
            result.modules.take(8).forEach {
                appendLine("- **${it.displayName}**：${it.questionCount}题 / 覆盖${percent(it.ratioOfBank)}%")
            }
            appendLine()
            appendLine("## 再扫数字")
            result.numericFactSummaries
                .filter {
                    it.correctOrSupportedCount > 0 &&
                        it.incorrectCount == 0 &&
                        it.contexts.isNotEmpty()
                }
                .sortedByDescending { it.correctOrSupportedCount }
                .take(15)
                .forEach {
                    appendLine(
                        "- **${it.normalizedValue}${it.unit}** · " +
                            "${it.contexts.first().replace(Regex("""\s+"""), " ").take(88)} · " +
                            "题号${it.quizIds.take(5).joinToString("、")}"
                    )
                }
            appendLine()
            appendLine("## 最后默念")
            appendLine("- 判断题：先找主体，再看数字、时限、条件，最后看强弱词。")
            appendLine("- 多选题：逐项验证，不用“固定选几个”代替知识。")
            appendLine("- 看见“应当/可以、以上/以下、中止/终止”立即停一下核对。")
            appendLine("- 不会的题先排除主体错、条件错、数字错；字母分布只作最后兜底。")
        }.trim()
    }

    fun extractQuickCard(aiReport: String): String? {
        if (aiReport.isBlank()) return null
        val heading = Regex("(?m)^##\\s+考前20分钟口令\\s*$").find(aiReport) ?: return null
        val nextHeading = Regex("(?m)^##\\s+").find(aiReport, heading.range.last + 1)
        val endExclusive = nextHeading?.range?.first ?: aiReport.length
        return aiReport.substring(heading.range.first, endExclusive).trim().takeIf(String::isNotBlank)
    }

    private fun EvidenceReliability.displayLabel(): String = when (this) {
        EvidenceReliability.HIGH -> "较高"
        EvidenceReliability.MEDIUM -> "中等"
        EvidenceReliability.LOW -> "较低"
        EvidenceReliability.INSUFFICIENT -> "不足"
    }

    private fun percent(value: Double): Int = (value.coerceIn(0.0, 1.0) * 100).roundToInt()

    private fun formatOneDecimal(value: Double): String = "%.1f".format(value)
}
