package com.virin.visionquiz.cram

import com.virin.visionquiz.dao.Quiz
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sqrt

/**
 * Deterministic, side-effect-free analysis of one question bank.
 *
 * It intentionally does not infer legal/subject-matter truth from answer-letter
 * frequency. All outputs are suitable for caching and can be generated offline.
 */
object LocalQuestionBankAnalyzer {

    fun analyze(
        quizzes: List<Quiz>,
        config: CramAnalysisConfig = CramAnalysisConfig()
    ): CramAnalysisResult {
        val normalizedConfig = config.normalized()
        val identities = buildIdentities(quizzes)
        val resolved = quizzes.mapIndexed { index, quiz ->
            val identity = identities[index]
            val type = classify(quiz)
            ResolvedQuiz(
                quiz = quiz,
                id = identity.analysisQuizId,
                inputIndex = index,
                type = type,
                module = resolveModule(quiz.reference) ?: fallbackModule(type),
                normalizedReference = normalizeDisplayText(quiz.reference.orEmpty())
                    .takeIf(String::isNotBlank)
            )
        }

        val questionTypes = buildQuestionTypeStats(resolved)
        val answerDistributions = buildAnswerDistributions(resolved)
        val multipleChoice = buildMultipleChoiceStats(resolved)
        val judgement = buildJudgementAnalysis(resolved)
        val numericFacts = resolved
            .flatMap(::extractFacts)
            .sortedWith(
                compareBy<NumericFact> { it.quizId }
                    .thenBy { it.source.ordinal }
                    .thenBy { it.category.ordinal }
                    .thenBy { it.rawText }
            )
        val numericFactSummaries = buildNumericFactSummaries(numericFacts)
        val duplicateGroups = buildDuplicateGroups(resolved)
        val moduleCounts = resolved
            .mapNotNull { it.module?.key }
            .groupingBy { it }
            .eachCount()
        val priorities = buildPriorities(
            quizzes = resolved,
            numericFacts = numericFacts,
            duplicateGroups = duplicateGroups,
            moduleCounts = moduleCounts,
            wrongQuizIds = normalizedConfig.wrongQuizIds.toSet()
        )
        val modules = buildModuleStats(
            quizzes = resolved,
            numericFacts = numericFacts,
            priorities = priorities
        )
        val referencedQuizIds = resolved
            .filter { it.normalizedReference != null }
            .map { it.id }
            .toSet()
        val referencedCount = referencedQuizIds.size
        val coverages = buildCoverageStats(
            modules = modules,
            totalQuestionCount = quizzes.size,
            referencedQuestionCount = referencedCount,
            referencedQuizIds = referencedQuizIds,
            cutoffs = normalizedConfig.coverageCutoffs
        )
        val selfTest = buildSelfTest(
            quizzes = resolved,
            priorities = priorities,
            duplicateGroups = duplicateGroups,
            requestedSize = normalizedConfig.selfTestSize
        )
        val plan = buildThreeDayPlan(
            quizzes = resolved,
            priorities = priorities,
            modules = modules,
            numericFacts = numericFacts,
            selfTest = selfTest,
            wrongQuizIds = normalizedConfig.wrongQuizIds.toSet(),
            dailyLimit = normalizedConfig.dailyQuestionLimit
        )

        return CramAnalysisResult(
            totalQuestionCount = quizzes.size,
            identities = identities,
            questionTypes = questionTypes,
            answerDistributions = answerDistributions,
            modules = modules,
            moduleCoverages = coverages,
            referencedQuestionCount = referencedCount,
            unreferencedQuestionCount = quizzes.size - referencedCount,
            multipleChoice = multipleChoice,
            judgement = judgement,
            numericFacts = numericFacts,
            numericFactSummaries = numericFactSummaries,
            duplicateGroups = duplicateGroups,
            priorities = priorities,
            threeDayPlan = plan,
            selfTest = selfTest,
            warnings = buildWarnings(
                quizzes = quizzes,
                config = config,
                identities = identities,
                referencedCount = referencedCount,
                judgement = judgement,
                selfTest = selfTest
            )
        )
    }

    private fun CramAnalysisConfig.normalized(): CramAnalysisConfig = copy(
        coverageCutoffs = coverageCutoffs.filter { it > 0 }.distinct().sorted()
            .ifEmpty { listOf(5, 10, 15) },
        dailyQuestionLimit = dailyQuestionLimit.coerceAtLeast(0),
        selfTestSize = selfTestSize.coerceAtLeast(0),
        wrongQuizIds = wrongQuizIds.distinct()
    )

    private fun buildIdentities(quizzes: List<Quiz>): List<CramQuestionIdentity> {
        val positiveIdCounts = quizzes
            .map { it.id }
            .filter { it > 0 }
            .groupingBy { it }
            .eachCount()
        return quizzes.mapIndexed { index, quiz ->
            val canUseStoredId = quiz.id > 0 && positiveIdCounts[quiz.id] == 1
            CramQuestionIdentity(
                analysisQuizId = if (canUseStoredId) quiz.id else -(index + 1),
                storedQuizId = quiz.id,
                inputIndex = index,
                sourceRow = quiz.sourceRow
            )
        }
    }

    private fun classify(quiz: Quiz): CramQuestionType {
        val declared = normalizeCompact(quiz.questionType.orEmpty())
        return when {
            "判断" in declared || "是非" in declared ||
                looksLikeJudgementOptions(quiz.options) -> CramQuestionType.JUDGEMENT
            "填空" in declared -> CramQuestionType.FILL_BLANK
            "主观" in declared || "简答" in declared || "问答" in declared ->
                CramQuestionType.SUBJECTIVE
            "多选" in declared || "不定项" in declared ||
                quiz.isMultipleChoice || quiz.answer.size > 1 -> CramQuestionType.MULTIPLE_CHOICE
            "单选" in declared || "选择" in declared || quiz.answer.size == 1 ->
                CramQuestionType.SINGLE_CHOICE
            else -> CramQuestionType.UNKNOWN
        }
    }

    private fun looksLikeJudgementOptions(options: List<String>): Boolean {
        if (options.size != 2) return false
        val outcomes = options.map(::judgementOptionOutcome).toSet()
        return JudgementOutcome.TRUE in outcomes && JudgementOutcome.FALSE in outcomes
    }

    private fun buildQuestionTypeStats(quizzes: List<ResolvedQuiz>): List<QuestionTypeStat> =
        CramQuestionType.entries.map { type ->
            val ids = quizzes.filter { it.type == type }.map { it.id }
            QuestionTypeStat(
                type = type,
                questionCount = ids.size,
                ratio = ratio(ids.size, quizzes.size),
                quizIds = ids
            )
        }

    private fun buildAnswerDistributions(
        quizzes: List<ResolvedQuiz>
    ): List<AnswerDistributionStat> = CramQuestionType.entries.map { type ->
        val typed = quizzes.filter { it.type == type }
        val patternCounts = typed
            .map { resolved ->
                resolved.quiz.answer.sorted()
                    .joinToString(separator = "", transform = ::optionLetter)
            }
            .groupingBy { it }
            .eachCount()
        val patterns = patternCounts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { (letters, count) ->
                AnswerPatternStat(
                    answerLetters = letters,
                    questionCount = count,
                    ratioWithinType = ratio(count, typed.size)
                )
            }
        val maxOptionCount = typed.maxOfOrNull { it.quiz.options.size } ?: 0
        val allSelectedCount = typed.sumOf { it.quiz.answer.size }
        val positions = (0 until maxOptionCount).map { optionIndex ->
            val count = typed.count { optionIndex in it.quiz.answer }
            AnswerPositionStat(
                optionIndex = optionIndex,
                optionLetter = optionLetter(optionIndex),
                selectedByQuestionCount = count,
                questionHitRate = ratio(count, typed.size),
                shareOfAllSelectedPositions = ratio(count, allSelectedCount)
            )
        }
        AnswerDistributionStat(
            type = type,
            questionCount = typed.size,
            patterns = patterns,
            positions = positions
        )
    }

    private fun buildMultipleChoiceStats(quizzes: List<ResolvedQuiz>): MultipleChoiceStats {
        val multiple = quizzes.filter { it.type == CramQuestionType.MULTIPLE_CHOICE }
        val distribution = multiple
            .groupingBy { it.quiz.answer.size }
            .eachCount()
            .entries
            .sortedBy { it.key }
            .map { (selectionCount, questionCount) ->
                MultiSelectionCountStat(
                    selectedOptionCount = selectionCount,
                    questionCount = questionCount,
                    ratio = ratio(questionCount, multiple.size)
                )
            }
        val all = multiple.count {
            it.quiz.options.isNotEmpty() && it.quiz.answer.size == it.quiz.options.size
        }
        val threeOrMore = multiple.count { it.quiz.answer.size >= 3 }
        return MultipleChoiceStats(
            questionCount = multiple.size,
            selectionCountDistribution = distribution,
            selectsAllOptionsCount = all,
            selectsAllOptionsRatio = ratio(all, multiple.size),
            selectsThreeOrMoreCount = threeOrMore,
            selectsThreeOrMoreRatio = ratio(threeOrMore, multiple.size),
            averageSelectedOptionCount = average(multiple.map { it.quiz.answer.size })
        )
    }

    private fun buildJudgementAnalysis(quizzes: List<ResolvedQuiz>): JudgementAnalysis {
        val judgementQuestions = quizzes.filter { it.type == CramQuestionType.JUDGEMENT }
        val outcomes = judgementQuestions.associate { it.id to judgementOutcome(it.quiz) }
        val known = outcomes.values.filter { it != JudgementOutcome.UNKNOWN }
        val keywordStats = JUDGEMENT_KEYWORDS.mapNotNull { keyword ->
            val matching = judgementQuestions.filter { keyword in it.quiz.prompt }
            if (matching.isEmpty()) return@mapNotNull null
            val matchingOutcomes = matching.mapNotNull { quiz ->
                outcomes[quiz.id]?.takeIf { it != JudgementOutcome.UNKNOWN }
            }
            val trueCount = matchingOutcomes.count { it == JudgementOutcome.TRUE }
            val falseCount = matchingOutcomes.count { it == JudgementOutcome.FALSE }
            val majority = when {
                trueCount > falseCount -> JudgementOutcome.TRUE
                falseCount > trueCount -> JudgementOutcome.FALSE
                else -> JudgementOutcome.UNKNOWN
            }
            val accuracy = if (matchingOutcomes.isEmpty()) {
                0.0
            } else {
                maxOf(trueCount, falseCount).toDouble() / matchingOutcomes.size
            }
            val confidence = if (matchingOutcomes.isEmpty()) {
                0.0
            } else {
                accuracy * min(1.0, sqrt(matchingOutcomes.size / 20.0))
            }
            JudgementKeywordStat(
                keyword = keyword,
                occurrenceCount = matching.size,
                knownOutcomeCount = matchingOutcomes.size,
                trueStatementCount = trueCount,
                falseStatementCount = falseCount,
                majorityOutcome = majority,
                empiricalAccuracy = decimal(accuracy),
                confidenceScore = decimal(confidence),
                reliability = keywordReliability(matchingOutcomes.size, accuracy),
                quizIds = matching.map { it.id }
            )
        }.sortedWith(
            compareByDescending<JudgementKeywordStat> { it.confidenceScore }
                .thenByDescending { it.occurrenceCount }
                .thenBy { it.keyword }
        )
        return JudgementAnalysis(
            questionCount = judgementQuestions.size,
            knownOutcomeCount = known.size,
            trueStatementCount = known.count { it == JudgementOutcome.TRUE },
            falseStatementCount = known.count { it == JudgementOutcome.FALSE },
            keywords = keywordStats
        )
    }

    private fun keywordReliability(knownCount: Int, accuracy: Double): EvidenceReliability = when {
        knownCount < 5 -> EvidenceReliability.INSUFFICIENT
        knownCount >= 20 && accuracy >= 0.80 -> EvidenceReliability.HIGH
        knownCount >= 10 && accuracy >= 0.70 -> EvidenceReliability.MEDIUM
        else -> EvidenceReliability.LOW
    }

    private fun judgementOutcome(quiz: Quiz): JudgementOutcome {
        if (quiz.answer.size != 1) return JudgementOutcome.UNKNOWN
        val selected = quiz.answer.first()
        return quiz.options.getOrNull(selected)?.let(::judgementOptionOutcome)
            ?: JudgementOutcome.UNKNOWN
    }

    private fun judgementOptionOutcome(option: String): JudgementOutcome {
        val normalized = normalizeCompact(option)
            .replace(Regex("""^[a-z][.、:]?"""), "")
        return when (normalized) {
            "正确", "对", "是", "√", "true", "t", "yes" -> JudgementOutcome.TRUE
            "错误", "错", "否", "×", "false", "f", "no" -> JudgementOutcome.FALSE
            else -> when {
                normalized.endsWith("正确") && !normalized.endsWith("不正确") ->
                    JudgementOutcome.TRUE
                normalized.endsWith("错误") || normalized.endsWith("不正确") ->
                    JudgementOutcome.FALSE
                else -> JudgementOutcome.UNKNOWN
            }
        }
    }

    private fun extractFacts(resolved: ResolvedQuiz): List<NumericFact> {
        val promptTruth = when {
            resolved.type != CramQuestionType.JUDGEMENT -> FactTruthStatus.CONTEXT_ONLY
            judgementOutcome(resolved.quiz) == JudgementOutcome.TRUE -> FactTruthStatus.CORRECT
            judgementOutcome(resolved.quiz) == JudgementOutcome.FALSE -> FactTruthStatus.INCORRECT
            else -> FactTruthStatus.CONTEXT_ONLY
        }
        val sources = mutableListOf(
            FactText(
                text = resolved.quiz.prompt,
                source = NumericFactSource.PROMPT,
                truthStatus = promptTruth
            )
        )
        resolved.quiz.answer.sorted().forEach { answerIndex ->
            resolved.quiz.options.getOrNull(answerIndex)
                ?.takeIf(String::isNotBlank)
                ?.let { answer ->
                    sources += FactText(
                        text = answer,
                        source = NumericFactSource.CORRECT_OPTION,
                        truthStatus = FactTruthStatus.CORRECT,
                        contextPrefix = "${resolved.quiz.prompt.trim().take(MAX_FACT_PROMPT_CONTEXT)}｜正确选项："
                    )
                }
        }
        resolved.quiz.explanation
            ?.takeIf(String::isNotBlank)
            ?.let { explanation ->
                sources += FactText(
                    text = explanation,
                    source = NumericFactSource.EXPLANATION,
                    truthStatus = FactTruthStatus.SUPPORTING_EVIDENCE,
                    contextPrefix = "${resolved.quiz.prompt.trim().take(MAX_FACT_PROMPT_CONTEXT)}｜解析："
                )
            }
        return sources.flatMap { source -> extractFacts(resolved.id, source) }
    }

    private fun extractFacts(quizId: Int, source: FactText): List<NumericFact> {
        val occupied = mutableListOf<IntRange>()
        val matches = mutableListOf<FactMatch>()
        FACT_PATTERNS.forEach { pattern ->
            pattern.regex.findAll(source.text).forEach { match ->
                val range = match.range
                if (occupied.none { existing -> rangesOverlap(existing, range) } &&
                    !isLeadingQuestionNumber(source.text, match)
                ) {
                    occupied += range
                    matches += FactMatch(
                        category = pattern.category,
                        range = range,
                        raw = match.value
                    )
                }
            }
        }
        return matches.sortedBy { it.range.first }.map { match ->
            val normalized = normalizeFactValue(match.raw, match.category)
            NumericFact(
                quizId = quizId,
                category = match.category,
                rawText = match.raw.trim(),
                normalizedValue = normalized.first,
                unit = normalized.second,
                context = (source.contextPrefix + contextAround(source.text, match.range))
                    .take(MAX_FACT_CONTEXT_LENGTH),
                source = source.source,
                truthStatus = source.truthStatus
            )
        }
    }

    private fun rangesOverlap(first: IntRange, second: IntRange): Boolean =
        first.first <= second.last && second.first <= first.last

    private fun isLeadingQuestionNumber(text: String, match: MatchResult): Boolean {
        if (match.value.any(Char::isLetter)) return false
        val prefix = text.substring(0, match.range.first)
        val suffix = text.substring(match.range.last + 1)
        return prefix.isBlank() && suffix.firstOrNull() in setOf('.', '．', '、', ')', '）')
    }

    private fun normalizeFactValue(
        raw: String,
        category: NumericFactCategory
    ): Pair<String, String> {
        val compact = Normalizer.normalize(raw, Normalizer.Form.NFKC)
            .replace(Regex("""\s+"""), "")
        if (category == NumericFactCategory.MONEY) {
            MONEY_UNITS.firstOrNull(compact::endsWith)?.let { unit ->
                return normalizeNumericExpression(compact.removeSuffix(unit)) to unit
            }
        }
        if (category == NumericFactCategory.COUNT) {
            COUNT_UNITS.firstOrNull(compact::endsWith)?.let { unit ->
                return normalizeNumericExpression(compact.removeSuffix(unit)) to unit
            }
        }
        if (compact.startsWith("百分之")) {
            return normalizeNumericExpression(compact.removePrefix("百分之")) to "%"
        }
        if (compact.startsWith("千分之")) {
            return normalizeNumericExpression(compact.removePrefix("千分之")) to "‰"
        }
        val numberMatches = NUMBER_TOKEN_REGEX.findAll(compact).toList()
        val numberMatch = numberMatches.firstOrNull()
            ?: return compact to ""
        val rangeSecond = numberMatches.getOrNull(1)?.takeIf { second ->
            compact.substring(numberMatch.range.last + 1, second.range.first)
                .matches(NUMERIC_RANGE_CONNECTOR_REGEX)
        }
        val value = if (rangeSecond != null) {
            "${normalizeNumber(numberMatch.value)}-${normalizeNumber(rangeSecond.value)}"
        } else {
            normalizeNumber(numberMatch.value)
        }
        var unit = if (rangeSecond != null) {
            compact.substring(rangeSecond.range.last + 1)
        } else {
            compact.removeRange(numberMatch.range)
        }
        unit = when (category) {
            NumericFactCategory.PERCENTAGE -> unit.replace("％", "%")
            NumericFactCategory.TIME_LIMIT -> unit.removePrefix("个")
            else -> unit
        }
        return value to unit
    }

    private fun normalizeNumericExpression(raw: String): String {
        val compact = raw.replace(Regex("""\s+"""), "")
        val matches = NUMBER_TOKEN_REGEX.findAll(compact).toList()
        if (matches.size >= 2) {
            val connector = compact.substring(matches[0].range.last + 1, matches[1].range.first)
            if (connector.matches(NUMERIC_RANGE_CONNECTOR_REGEX)) {
                return "${normalizeNumber(matches[0].value)}-${normalizeNumber(matches[1].value)}"
            }
        }
        return normalizeNumber(compact)
    }

    private fun normalizeNumber(raw: String): String {
        val compact = raw.trim().replace("两", "二").replace("〇", "零")
        if (compact == "半") return "0.5"
        return compact.toBigDecimalOrNull()
            ?.stripTrailingZeros()
            ?.toPlainString()
            ?: chineseInteger(compact)?.toString()
            ?: compact
    }

    private fun chineseInteger(text: String): Long? {
        if (text.isBlank() || text.any { it !in CHINESE_NUMBER_CHARS }) return null
        if (text.all { it in CHINESE_DIGITS }) {
            return text.map { CHINESE_DIGIT_VALUES.getValue(it) }
                .joinToString("")
                .toLongOrNull()
        }
        var total = 0L
        var section = 0L
        var number = 0L
        text.forEach { char ->
            val digit = CHINESE_DIGIT_VALUES[char]
            if (digit != null) {
                number = digit
            } else {
                val unit = CHINESE_UNIT_VALUES[char] ?: return null
                if (unit < 10_000) {
                    if (number == 0L) number = 1L
                    section += number * unit
                    number = 0L
                } else {
                    section += number
                    total += section * unit
                    section = 0L
                    number = 0L
                }
            }
        }
        return total + section + number
    }

    private fun contextAround(text: String, range: IntRange): String {
        val start = (range.first - CONTEXT_RADIUS).coerceAtLeast(0)
        val endExclusive = (range.last + 1 + CONTEXT_RADIUS).coerceAtMost(text.length)
        return text.substring(start, endExclusive)
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun buildNumericFactSummaries(facts: List<NumericFact>): List<NumericFactSummary> =
        facts.groupBy {
            "${it.category.name}|${it.normalizedValue}|${it.unit}|" +
                numericContextSignature(it.context)
        }
            .map { (groupKey, grouped) ->
                NumericFactSummary(
                    key = sha256(groupKey),
                    category = grouped.first().category,
                    normalizedValue = grouped.first().normalizedValue,
                    unit = grouped.first().unit,
                    contexts = grouped.map { it.context }.filter(String::isNotBlank).distinct().take(3),
                    occurrenceCount = grouped.size,
                    correctOrSupportedCount = grouped.count {
                        it.truthStatus == FactTruthStatus.CORRECT ||
                            it.truthStatus == FactTruthStatus.SUPPORTING_EVIDENCE
                    },
                    incorrectCount = grouped.count {
                        it.truthStatus == FactTruthStatus.INCORRECT
                    },
                    quizIds = grouped.map { it.quizId }.distinct().sorted()
                )
            }
            .sortedWith(
                compareByDescending<NumericFactSummary> { it.occurrenceCount }
                    .thenBy { it.category.ordinal }
                    .thenBy { it.normalizedValue }
                    .thenBy { it.unit }
            )

    private fun numericContextSignature(context: String): String {
        return Normalizer.normalize(context, Normalizer.Form.NFKC)
            .lowercase()
            .replace(NUMBER_TOKEN_REGEX, "#")
            .replace(PUNCTUATION_SYMBOL_SPACE_REGEX, "")
            .take(MAX_FACT_CONTEXT_LENGTH)
    }

    private fun buildDuplicateGroups(
        quizzes: List<ResolvedQuiz>
    ): List<DuplicateQuestionGroup> = quizzes
        .groupBy(::duplicateSignature)
        .values
        .filter { it.size > 1 }
        .map { duplicates ->
            val signature = duplicateSignature(duplicates.first())
            DuplicateQuestionGroup(
                fingerprint = sha256(signature),
                normalizedPrompt = normalizeForDuplicate(duplicates.first().quiz.prompt),
                quizIds = duplicates.map { it.id }.sorted(),
                sourceRows = duplicates.mapNotNull { it.quiz.sourceRow }.sorted(),
                questionCount = duplicates.size
            )
        }
        .sortedWith(
            compareByDescending<DuplicateQuestionGroup> { it.questionCount }
                .thenBy { it.quizIds.firstOrNull() ?: Int.MAX_VALUE }
        )

    private fun duplicateSignature(resolved: ResolvedQuiz): String {
        val quiz = resolved.quiz
        val options = quiz.options.map(::normalizeForDuplicate).sorted()
        val correctAnswers = quiz.answer.mapNotNull(quiz.options::getOrNull)
            .map(::normalizeForDuplicate)
            .sorted()
        return listOf(
            resolved.type.name,
            normalizeForDuplicate(quiz.prompt),
            options.joinToString("|"),
            correctAnswers.joinToString("|")
        ).joinToString("::")
    }

    private fun normalizeForDuplicate(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .replace(LEADING_QUESTION_NUMBER_REGEX, "")
            .replace(PUNCTUATION_SYMBOL_SPACE_REGEX, "")

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }.take(24)
    }

    private fun buildPriorities(
        quizzes: List<ResolvedQuiz>,
        numericFacts: List<NumericFact>,
        duplicateGroups: List<DuplicateQuestionGroup>,
        moduleCounts: Map<String, Int>,
        wrongQuizIds: Set<Int>
    ): List<QuestionPriority> {
        val factCountByQuiz = numericFacts.groupingBy { it.quizId }.eachCount()
        val duplicateSizeByQuiz = duplicateGroups.flatMap { group ->
            group.quizIds.map { it to group.questionCount }
        }.toMap()
        val maxModuleSize = moduleCounts.values.maxOrNull()?.coerceAtLeast(1) ?: 1
        return quizzes.map { resolved ->
            val reasons = mutableListOf<String>()
            var score = 0.0
            val moduleSize = resolved.module?.key?.let(moduleCounts::get).orZero()
            if (moduleSize > 0) {
                val moduleScore = 35.0 * moduleSize / maxModuleSize
                score += moduleScore
                reasons += "高频模块（$moduleSize 题）"
            }
            val factCount = factCountByQuiz[resolved.id].orZero()
            if (factCount > 0) {
                score += min(20.0, factCount * 5.0)
                reasons += "含 $factCount 个数字/时限考点"
            }
            when (resolved.type) {
                CramQuestionType.MULTIPLE_CHOICE -> {
                    score += 12.0
                    reasons += "多选题，漏选风险高"
                }
                CramQuestionType.JUDGEMENT -> {
                    score += 6.0
                    reasons += "判断题，适合训练陷阱识别"
                }
                CramQuestionType.FILL_BLANK -> {
                    score += 5.0
                    reasons += "需要主动回忆"
                }
                else -> Unit
            }
            val duplicateSize = duplicateSizeByQuiz[resolved.id].orZero()
            if (duplicateSize > 1) {
                score += min(10.0, (duplicateSize - 1) * 4.0)
                reasons += "同一规则重复出现 $duplicateSize 次"
            }
            if (!resolved.quiz.explanation.isNullOrBlank()) {
                score += 5.0
                reasons += "有解析可核对"
            }
            if (resolved.normalizedReference != null) {
                score += 4.0
                reasons += "有答案依据"
            }
            if (resolved.id in wrongQuizIds || resolved.quiz.id in wrongQuizIds) {
                score += 22.0
                reasons += "历史错题"
            }
            val bounded = decimal(score.coerceIn(0.0, 100.0))
            QuestionPriority(
                quizId = resolved.id,
                score = bounded,
                level = when {
                    bounded >= 75 -> CramPriorityLevel.CRITICAL
                    bounded >= 55 -> CramPriorityLevel.HIGH
                    bounded >= 35 -> CramPriorityLevel.MEDIUM
                    else -> CramPriorityLevel.LOW
                },
                moduleKey = resolved.module?.key,
                reasons = reasons
            )
        }.sortedWith(
            compareByDescending<QuestionPriority> { it.score }
                .thenBy { it.quizId }
        )
    }

    private fun buildModuleStats(
        quizzes: List<ResolvedQuiz>,
        numericFacts: List<NumericFact>,
        priorities: List<QuestionPriority>
    ): List<ModuleStat> {
        val factsByQuiz = numericFacts.groupingBy { it.quizId }.eachCount()
        val priorityByQuiz = priorities.associateBy { it.quizId }
        return quizzes.filter { it.module != null }
            .groupBy { it.module!!.key }
            .map { (_, grouped) ->
                val module = grouped.first().module!!
                ModuleStat(
                    key = module.key,
                    displayName = module.displayName,
                    questionCount = grouped.size,
                    ratioOfBank = ratio(grouped.size, quizzes.size),
                    quizIds = grouped.map { it.id }.sorted(),
                    sourceReferences = grouped.mapNotNull { it.normalizedReference }
                        .distinct()
                        .sorted(),
                    typeCounts = CramQuestionType.entries.mapNotNull { type ->
                        grouped.count { it.type == type }
                            .takeIf { it > 0 }
                            ?.let { ModuleTypeCount(type, it) }
                    },
                    numericFactCount = grouped.sumOf { factsByQuiz[it.id].orZero() },
                    averagePriorityScore = decimal(
                        grouped.mapNotNull { priorityByQuiz[it.id]?.score }
                            .averageOrZero()
                    )
                )
            }
            .sortedWith(
                compareByDescending<ModuleStat> { it.questionCount }
                    .thenByDescending { it.averagePriorityScore }
                    .thenBy { it.displayName }
            )
    }

    private fun buildCoverageStats(
        modules: List<ModuleStat>,
        totalQuestionCount: Int,
        referencedQuestionCount: Int,
        referencedQuizIds: Set<Int>,
        cutoffs: List<Int>
    ): List<ModuleCoverageStat> = cutoffs.map { cutoff ->
        val selected = modules.take(cutoff)
        val covered = selected.sumOf { it.questionCount }
        val coveredReferenced = selected.sumOf { module ->
            module.quizIds.count(referencedQuizIds::contains)
        }
        ModuleCoverageStat(
            requestedTopModuleCount = cutoff,
            actualModuleCount = selected.size,
            moduleKeys = selected.map { it.key },
            coveredQuestionCount = covered,
            coverageOfBank = ratio(covered, totalQuestionCount),
            coverageOfReferencedQuestions = ratio(
                coveredReferenced,
                referencedQuestionCount
            )
        )
    }

    private fun buildSelfTest(
        quizzes: List<ResolvedQuiz>,
        priorities: List<QuestionPriority>,
        duplicateGroups: List<DuplicateQuestionGroup>,
        requestedSize: Int
    ): SelfTestSelection {
        val target = min(requestedSize, quizzes.size)
        val priorityById = priorities.associateBy { it.quizId }
        val nonRepresentativeDuplicateIds = duplicateGroups
            .flatMap { it.quizIds.drop(1) }
            .toSet()
        val preferredPool = quizzes.filter { it.id !in nonRepresentativeDuplicateIds }
        val selected = mutableListOf<ResolvedQuiz>()
        val preferredTypes = listOf(
            CramQuestionType.JUDGEMENT,
            CramQuestionType.SINGLE_CHOICE,
            CramQuestionType.MULTIPLE_CHOICE
        )
        val baseQuota = if (preferredTypes.isEmpty()) 0 else target / preferredTypes.size
        val extra = if (preferredTypes.isEmpty()) 0 else target % preferredTypes.size
        preferredTypes.forEachIndexed { index, type ->
            val quota = baseQuota + if (index < extra) 1 else 0
            val pool = preferredPool.filter { it.type == type && it !in selected }
            selected += selectDiverse(pool, quota, priorityById, selected)
        }
        if (selected.size < target) {
            val preferredRemainder = preferredPool.filter { it !in selected }
            selected += selectDiverse(
                preferredRemainder,
                target - selected.size,
                priorityById,
                selected
            )
        }
        if (selected.size < target) {
            val duplicateFallback = quizzes.filter { it !in selected }
            selected += selectDiverse(
                duplicateFallback,
                target - selected.size,
                priorityById,
                selected
            )
        }
        val ids = selected.map { it.id }
        return SelfTestSelection(
            requestedQuestionCount = requestedSize,
            actualQuestionCount = ids.size,
            quizIds = ids,
            sections = CramQuestionType.entries.mapNotNull { type ->
                ids.filter { id -> selected.first { it.id == id }.type == type }
                    .takeIf { it.isNotEmpty() }
                    ?.let { SelfTestSection(type, it) }
            },
            selectionPolicy = SELF_TEST_SELECTION_POLICY
        )
    }

    private fun selectDiverse(
        pool: List<ResolvedQuiz>,
        count: Int,
        priorityById: Map<Int, QuestionPriority>,
        alreadySelected: List<ResolvedQuiz>,
        extraScore: (ResolvedQuiz) -> Double = { 0.0 }
    ): List<ResolvedQuiz> {
        if (count <= 0 || pool.isEmpty()) return emptyList()
        val remaining = pool.distinctBy { it.id }.toMutableList()
        val result = mutableListOf<ResolvedQuiz>()
        val moduleHits = (alreadySelected)
            .groupingBy { it.module?.key ?: UNREFERENCED_MODULE_KEY }
            .eachCount()
            .toMutableMap()
        while (result.size < count && remaining.isNotEmpty()) {
            val chosen = remaining.maxWithOrNull(
                compareBy<ResolvedQuiz> {
                    val moduleKey = it.module?.key ?: UNREFERENCED_MODULE_KEY
                    priorityById[it.id].orZeroScore() + extraScore(it) -
                        moduleHits[moduleKey].orZero() * MODULE_DIVERSITY_PENALTY
                }.thenBy { -it.inputIndex }
            ) ?: break
            result += chosen
            remaining.remove(chosen)
            val moduleKey = chosen.module?.key ?: UNREFERENCED_MODULE_KEY
            moduleHits[moduleKey] = moduleHits[moduleKey].orZero() + 1
        }
        return result
    }

    private fun buildThreeDayPlan(
        quizzes: List<ResolvedQuiz>,
        priorities: List<QuestionPriority>,
        modules: List<ModuleStat>,
        numericFacts: List<NumericFact>,
        selfTest: SelfTestSelection,
        wrongQuizIds: Set<Int>,
        dailyLimit: Int
    ): ThreeDayCramPlan {
        val priorityById = priorities.associateBy { it.quizId }
        val quizById = quizzes.associateBy { it.id }
        val factCountByQuiz = numericFacts.groupingBy { it.quizId }.eachCount()
        val topModuleKeys = modules.take(TOP_MODULES_FOR_DAY_ONE).map { it.key }.toSet()

        val dayOnePrimary = quizzes.filter { it.module?.key in topModuleKeys }
        val dayOne = selectDiverse(
            dayOnePrimary,
            dailyLimit,
            priorityById,
            emptyList()
        ).toMutableList()
        if (dayOne.size < min(dailyLimit, quizzes.size)) {
            dayOne += selectDiverse(
                quizzes.filter { it !in dayOne },
                dailyLimit - dayOne.size,
                priorityById,
                dayOne
            )
        }

        val dayOneIds = dayOne.map { it.id }.toSet()
        val dayTwoPool = quizzes.filter { it.id !in dayOneIds }
        val dayTwo = selectDiverse(
            pool = dayTwoPool,
            count = dailyLimit,
            priorityById = priorityById,
            alreadySelected = dayOne,
            extraScore = { resolved ->
                factCountByQuiz[resolved.id].orZero() * 6.0 +
                    if (resolved.type == CramQuestionType.MULTIPLE_CHOICE) 5.0 else 0.0
            }
        )
        val learnedIds = (dayOne + dayTwo).map { it.id }.toSet()

        val wrong = quizzes.filter {
            it.id in wrongQuizIds || it.quiz.id in wrongQuizIds
        }.sortedByDescending { priorityById[it.id].orZeroScore() }
        val selfTestQuizzes = selfTest.quizIds.mapNotNull(quizById::get)
        val dayThreeCandidates = (wrong + selfTestQuizzes +
            (dayOne + dayTwo).sortedByDescending { priorityById[it.id].orZeroScore() } +
            quizzes.sortedByDescending { priorityById[it.id].orZeroScore() })
            .distinctBy { it.id }
        val dayThree = dayThreeCandidates.take(dailyLimit)

        val plans = listOf(
            buildDay(
                day = 1,
                title = "高频模块打底",
                focus = "先掌握覆盖题量最高的模块，建立主体—条件—后果框架。",
                selected = dayOne,
                previouslySeen = emptySet()
            ),
            buildDay(
                day = 2,
                title = "数字与多选攻坚",
                focus = "优先训练时限、金额、倍数和多选完整清单，再补充新模块。",
                selected = dayTwo,
                previouslySeen = dayOneIds
            ),
            buildDay(
                day = 3,
                title = "高频自测与错题回收",
                focus = "用高频自测检验记忆，集中复习历史错题和前两天高优先级题。",
                selected = dayThree,
                previouslySeen = learnedIds
            )
        )
        val allIds = plans.flatMap { it.quizIds }
        return ThreeDayCramPlan(
            days = plans,
            uniqueQuestionCount = allIds.distinct().size,
            totalPracticeCount = allIds.size,
            omittedQuestionCount = (quizzes.size - allIds.distinct().size).coerceAtLeast(0)
        )
    }

    private fun buildDay(
        day: Int,
        title: String,
        focus: String,
        selected: List<ResolvedQuiz>,
        previouslySeen: Set<Int>
    ): CramDayPlan {
        val modules = selected
            .groupBy { it.module?.key ?: UNREFERENCED_MODULE_KEY }
            .map { (key, grouped) ->
                CramPlanModule(
                    key = key,
                    displayName = grouped.first().module?.displayName ?: "未标注答案依据",
                    questionCount = grouped.size,
                    containsReviewQuestions = grouped.any { it.id in previouslySeen }
                )
            }
            .sortedWith(
                compareByDescending<CramPlanModule> { it.questionCount }
                    .thenBy { it.displayName }
            )
        val ids = selected.map { it.id }
        return CramDayPlan(
            day = day,
            title = title,
            focus = focus,
            modules = modules,
            quizIds = ids,
            newQuizIds = ids.filterNot(previouslySeen::contains),
            reviewQuizIds = ids.filter(previouslySeen::contains)
        )
    }

    private fun resolveModule(reference: String?): ModuleIdentity? {
        val normalized = normalizeDisplayText(reference.orEmpty())
        if (normalized.isBlank()) return null
        val documentTitle = DOCUMENT_TITLE_REGEX.find(normalized)?.value
        val display = (documentTitle ?: normalized
            .substringBefore('；')
            .substringBefore(';')
            .replace(TRAILING_ARTICLE_REGEX, "")
            .trim(' ', '，', ',', '。', ':', '：'))
            .ifBlank { normalized }
            .take(MAX_MODULE_NAME_LENGTH)
        return ModuleIdentity(
            key = normalizeCompact(display),
            displayName = display
        )
    }

    /**
     * Older imports did not persist the "依据" column. Keep those libraries useful
     * by turning their declared/inferred question type into a deterministic module.
     * This is only an organizational fallback; it never pretends to be a source.
     */
    private fun fallbackModule(type: CramQuestionType): ModuleIdentity {
        val displayName = when (type) {
            CramQuestionType.JUDGEMENT -> "判断题·规则辨析"
            CramQuestionType.SINGLE_CHOICE -> "单选题·主体与条件"
            CramQuestionType.MULTIPLE_CHOICE -> "多选题·清单组合"
            CramQuestionType.FILL_BLANK -> "填空题·主动回忆"
            CramQuestionType.SUBJECTIVE -> "主观题·要点复述"
            CramQuestionType.UNKNOWN -> "其他题型·基础兜底"
        }
        return ModuleIdentity(
            key = "$FALLBACK_MODULE_PREFIX${type.name.lowercase(Locale.ROOT)}",
            displayName = displayName
        )
    }

    private fun buildWarnings(
        quizzes: List<Quiz>,
        config: CramAnalysisConfig,
        identities: List<CramQuestionIdentity>,
        referencedCount: Int,
        judgement: JudgementAnalysis,
        selfTest: SelfTestSelection
    ): List<String> = buildList {
        add(ANSWER_DISTRIBUTION_USAGE_POLICY)
        add(JUDGEMENT_KEYWORD_USAGE_POLICY)
        if (quizzes.isEmpty()) add("题库为空，分析结果仅包含空统计。")
        if (identities.any { it.analysisQuizId != it.storedQuizId }) {
            add("部分题目没有唯一正整数 ID，分析期间使用了负数临时 ID；入库后应重新分析。")
        }
        if (quizzes.isNotEmpty() && referencedCount < quizzes.size) {
            add("${quizzes.size - referencedCount} 道题未标注答案依据，不计入法规模块覆盖率。")
        }
        if (judgement.knownOutcomeCount < judgement.questionCount) {
            add("${judgement.questionCount - judgement.knownOutcomeCount} 道判断题无法识别正误选项，未用于关键词可信度计算。")
        }
        if (selfTest.actualQuestionCount < selfTest.requestedQuestionCount) {
            add("题量不足，自测题由 ${selfTest.requestedQuestionCount} 道缩减为 ${selfTest.actualQuestionCount} 道。")
        }
        if (config.dailyQuestionLimit < 0 || config.selfTestSize < 0 ||
            config.coverageCutoffs.any { it <= 0 }
        ) {
            add("配置中的负数或非正覆盖档位已自动修正。")
        }
    }

    private fun normalizeDisplayText(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun normalizeCompact(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .replace(Regex("""\s+"""), "")

    private fun optionLetter(index: Int): String {
        if (index < 0) return "?"
        var value = index + 1
        val result = StringBuilder()
        while (value > 0) {
            value--
            result.append(('A'.code + value % 26).toChar())
            value /= 26
        }
        return result.reverse().toString()
    }

    private fun ratio(numerator: Int, denominator: Int): Double =
        if (denominator <= 0) 0.0 else decimal(numerator.toDouble() / denominator)

    private fun average(values: List<Int>): Double =
        if (values.isEmpty()) 0.0 else decimal(values.average())

    private fun List<Double>.averageOrZero(): Double =
        if (isEmpty()) 0.0 else average()

    private fun Int?.orZero(): Int = this ?: 0

    private fun QuestionPriority?.orZeroScore(): Double = this?.score ?: 0.0

    private fun decimal(value: Double): Double = round(value * 10_000.0) / 10_000.0

    private data class ResolvedQuiz(
        val quiz: Quiz,
        val id: Int,
        val inputIndex: Int,
        val type: CramQuestionType,
        val module: ModuleIdentity?,
        val normalizedReference: String?
    )

    private data class ModuleIdentity(
        val key: String,
        val displayName: String
    )

    private data class FactText(
        val text: String,
        val source: NumericFactSource,
        val truthStatus: FactTruthStatus,
        val contextPrefix: String = ""
    )

    private data class FactPattern(
        val category: NumericFactCategory,
        val regex: Regex
    )

    private data class FactMatch(
        val category: NumericFactCategory,
        val range: IntRange,
        val raw: String
    )

    private const val CONTEXT_RADIUS = 16
    private const val MAX_FACT_PROMPT_CONTEXT = 72
    private const val MAX_FACT_CONTEXT_LENGTH = 140
    private const val MAX_MODULE_NAME_LENGTH = 80
    private const val TOP_MODULES_FOR_DAY_ONE = 5
    private const val MODULE_DIVERSITY_PENALTY = 1.5
    private const val UNREFERENCED_MODULE_KEY = "__unreferenced__"
    private const val FALLBACK_MODULE_PREFIX = "__type__"
    private const val SELF_TEST_SELECTION_POLICY =
        "优先从判断、单选、多选各抽取约三分之一；同分时兼顾模块分散，并避开规范化重复题。"

    private val JUDGEMENT_KEYWORDS = listOf(
        "一律", "任何", "全部", "均", "必须", "不得", "严禁", "只能",
        "唯一", "完全", "所有", "无条件", "绝不", "始终", "必然", "一定",
        "应当", "可以", "一般", "原则上", "通常", "可能", "以上", "以下"
    )

    private const val NUMBER_TOKEN =
        """(?:\d+(?:\.\d+)?|[零〇一二两三四五六七八九十百千万亿半]+)"""
    private const val NUMBER_EXPRESSION =
        """$NUMBER_TOKEN(?:\s*(?:-|—|–|~|～|至|到)\s*$NUMBER_TOKEN)?"""
    private val NUMBER_TOKEN_REGEX = Regex(NUMBER_TOKEN)
    private val NUMERIC_RANGE_CONNECTOR_REGEX = Regex("""(?:-|—|–|~|～|至|到)""")
    private val MONEY_UNITS = listOf("亿元", "万元", "人民币", "元")
    private val COUNT_UNITS = listOf(
        "亿次", "万次", "亿户", "万户", "亿项", "万项", "亿种", "万种",
        "亿人", "万人", "亿家", "万家", "亿份", "万份", "亿台", "万台",
        "亿套", "万套", "亿个", "万个", "亿条", "万条", "亿笔", "万笔",
        "亿类", "万类", "次", "户", "项", "种", "人", "家", "份", "台",
        "套", "个", "条", "笔", "类"
    )
    private val FACT_PATTERNS = listOf(
        FactPattern(
            NumericFactCategory.PERCENTAGE,
            Regex("""(?:百分之|千分之)\s*$NUMBER_EXPRESSION""")
        ),
        FactPattern(
            NumericFactCategory.PERCENTAGE,
            Regex("""$NUMBER_EXPRESSION\s*(?:%|％|‰)""")
        ),
        FactPattern(
            NumericFactCategory.MONEY,
            Regex("""$NUMBER_EXPRESSION\s*(?:万|亿)?\s*(?:元|人民币)""")
        ),
        FactPattern(
            NumericFactCategory.TIME_LIMIT,
            Regex(
                """$NUMBER_EXPRESSION\s*(?:个\s*)?(?:工作日|自然日|小时|分钟|秒|个月|日|天|月|年|周|时)"""
            )
        ),
        FactPattern(
            NumericFactCategory.MULTIPLE,
            Regex("""$NUMBER_EXPRESSION\s*倍""")
        ),
        FactPattern(
            NumericFactCategory.MEASUREMENT,
            Regex(
                """$NUMBER_EXPRESSION\s*(?:kWh|KWH|千瓦时|kVA|KVA|千伏安|MVA|兆伏安|kV|KV|kv|千伏|kW|KW|kw|千瓦|MW|兆瓦|mA|毫安|伏安|VA|伏|V|瓦|度|安|A|平方米|平方千米|公里|千米|米)"""
            )
        ),
        FactPattern(
            NumericFactCategory.COUNT,
            Regex("""$NUMBER_EXPRESSION\s*(?:万|亿)?\s*(?:次|户|项|种|人|家|份|台|套|个|条|笔|类)""")
        ),
        FactPattern(
            NumericFactCategory.GENERAL_NUMBER,
            Regex("""\d+(?:\.\d+)?""")
        )
    )

    private val CHINESE_DIGIT_VALUES = mapOf(
        '零' to 0L,
        '一' to 1L,
        '二' to 2L,
        '三' to 3L,
        '四' to 4L,
        '五' to 5L,
        '六' to 6L,
        '七' to 7L,
        '八' to 8L,
        '九' to 9L
    )
    private val CHINESE_UNIT_VALUES = mapOf(
        '十' to 10L,
        '百' to 100L,
        '千' to 1_000L,
        '万' to 10_000L,
        '亿' to 100_000_000L
    )
    private val CHINESE_DIGITS = CHINESE_DIGIT_VALUES.keys
    private val CHINESE_NUMBER_CHARS = CHINESE_DIGITS + CHINESE_UNIT_VALUES.keys

    private val LEADING_QUESTION_NUMBER_REGEX =
        Regex("""^\s*(?:第?\d+\s*[.、)）]|[（(]\d+[)）])\s*""")
    private val PUNCTUATION_SYMBOL_SPACE_REGEX = Regex("""[\p{P}\p{S}\s]+""")
    private val DOCUMENT_TITLE_REGEX = Regex("""《[^》]{2,80}》""")
    private val TRAILING_ARTICLE_REGEX = Regex(
        """(?:第\s*[0-9零〇一二两三四五六七八九十百千万]+\s*(?:条|章|节).*)$"""
    )
}
