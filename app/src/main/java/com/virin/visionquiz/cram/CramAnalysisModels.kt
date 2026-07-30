package com.virin.visionquiz.cram

import com.google.gson.Gson

/**
 * Tunable, UI-independent inputs for a local question-bank analysis.
 *
 * [wrongQuizIds] is deliberately optional: callers that have practice history can
 * make the plan adaptive, while import previews can still run the same analyzer.
 */
data class CramAnalysisConfig(
    val coverageCutoffs: List<Int> = listOf(5, 10, 15),
    val dailyQuestionLimit: Int = 60,
    val selfTestSize: Int = 30,
    val wrongQuizIds: List<Int> = emptyList()
)

enum class CramQuestionType(val displayName: String) {
    JUDGEMENT("判断"),
    SINGLE_CHOICE("单选"),
    MULTIPLE_CHOICE("多选"),
    FILL_BLANK("填空"),
    SUBJECTIVE("主观"),
    UNKNOWN("其他")
}

enum class NumericFactCategory(val displayName: String) {
    TIME_LIMIT("时限"),
    MONEY("金额"),
    MULTIPLE("倍数"),
    PERCENTAGE("比例"),
    MEASUREMENT("容量或计量"),
    COUNT("数量"),
    GENERAL_NUMBER("其他数字")
}

enum class NumericFactSource(val displayName: String) {
    PROMPT("题干"),
    CORRECT_OPTION("正确选项"),
    EXPLANATION("解析")
}

/**
 * A number in a false judgement statement is explicitly marked INCORRECT so a
 * caller never accidentally renders that distractor as a fact to memorize.
 */
enum class FactTruthStatus {
    CORRECT,
    INCORRECT,
    CONTEXT_ONLY,
    SUPPORTING_EVIDENCE
}

enum class JudgementOutcome {
    TRUE,
    FALSE,
    UNKNOWN
}

enum class EvidenceReliability {
    HIGH,
    MEDIUM,
    LOW,
    INSUFFICIENT
}

enum class CramPriorityLevel {
    CRITICAL,
    HIGH,
    MEDIUM,
    LOW
}

data class CramQuestionIdentity(
    val analysisQuizId: Int,
    val storedQuizId: Int,
    val inputIndex: Int,
    val sourceRow: Int?
)

data class QuestionTypeStat(
    val type: CramQuestionType,
    val questionCount: Int,
    val ratio: Double,
    val quizIds: List<Int>
)

data class AnswerPatternStat(
    val answerLetters: String,
    val questionCount: Int,
    val ratioWithinType: Double
)

data class AnswerPositionStat(
    val optionIndex: Int,
    val optionLetter: String,
    val selectedByQuestionCount: Int,
    val questionHitRate: Double,
    val shareOfAllSelectedPositions: Double
)

/**
 * These are descriptive distributions only. [isKnowledgeRule] is always false:
 * answer letters are never promoted into a purported subject-matter rule.
 */
data class AnswerDistributionStat(
    val type: CramQuestionType,
    val questionCount: Int,
    val patterns: List<AnswerPatternStat>,
    val positions: List<AnswerPositionStat>,
    val isKnowledgeRule: Boolean = false,
    val usagePolicy: String = ANSWER_DISTRIBUTION_USAGE_POLICY
)

data class ModuleTypeCount(
    val type: CramQuestionType,
    val questionCount: Int
)

data class ModuleStat(
    val key: String,
    val displayName: String,
    val questionCount: Int,
    val ratioOfBank: Double,
    val quizIds: List<Int>,
    val sourceReferences: List<String>,
    val typeCounts: List<ModuleTypeCount>,
    val numericFactCount: Int,
    val averagePriorityScore: Double
)

data class ModuleCoverageStat(
    val requestedTopModuleCount: Int,
    val actualModuleCount: Int,
    val moduleKeys: List<String>,
    val coveredQuestionCount: Int,
    val coverageOfBank: Double,
    val coverageOfReferencedQuestions: Double
)

data class MultiSelectionCountStat(
    val selectedOptionCount: Int,
    val questionCount: Int,
    val ratio: Double
)

data class MultipleChoiceStats(
    val questionCount: Int,
    val selectionCountDistribution: List<MultiSelectionCountStat>,
    val selectsAllOptionsCount: Int,
    val selectsAllOptionsRatio: Double,
    val selectsThreeOrMoreCount: Int,
    val selectsThreeOrMoreRatio: Double,
    val averageSelectedOptionCount: Double
)

/**
 * Empirical keyword correlation in this particular bank. It is not a semantic
 * truth rule: even HIGH means "worth noticing", never "answer from the keyword".
 */
data class JudgementKeywordStat(
    val keyword: String,
    val occurrenceCount: Int,
    val knownOutcomeCount: Int,
    val trueStatementCount: Int,
    val falseStatementCount: Int,
    val majorityOutcome: JudgementOutcome,
    val empiricalAccuracy: Double,
    val confidenceScore: Double,
    val reliability: EvidenceReliability,
    val quizIds: List<Int>,
    val safeAsStandaloneRule: Boolean = false
)

data class JudgementAnalysis(
    val questionCount: Int,
    val knownOutcomeCount: Int,
    val trueStatementCount: Int,
    val falseStatementCount: Int,
    val keywords: List<JudgementKeywordStat>,
    val usagePolicy: String = JUDGEMENT_KEYWORD_USAGE_POLICY
)

data class NumericFact(
    val quizId: Int,
    val category: NumericFactCategory,
    val rawText: String,
    val normalizedValue: String,
    val unit: String,
    val context: String,
    val source: NumericFactSource,
    val truthStatus: FactTruthStatus
)

data class NumericFactSummary(
    val key: String,
    val category: NumericFactCategory,
    val normalizedValue: String,
    val unit: String,
    val contexts: List<String> = emptyList(),
    val occurrenceCount: Int,
    val correctOrSupportedCount: Int,
    val incorrectCount: Int,
    val quizIds: List<Int>
)

data class DuplicateQuestionGroup(
    val fingerprint: String,
    val normalizedPrompt: String,
    val quizIds: List<Int>,
    val sourceRows: List<Int>,
    val questionCount: Int
)

data class QuestionPriority(
    val quizId: Int,
    val score: Double,
    val level: CramPriorityLevel,
    val moduleKey: String?,
    val reasons: List<String>
)

data class CramPlanModule(
    val key: String,
    val displayName: String,
    val questionCount: Int,
    val containsReviewQuestions: Boolean
)

data class CramDayPlan(
    val day: Int,
    val title: String,
    val focus: String,
    val modules: List<CramPlanModule>,
    val quizIds: List<Int>,
    val newQuizIds: List<Int>,
    val reviewQuizIds: List<Int>
)

data class ThreeDayCramPlan(
    val days: List<CramDayPlan>,
    val uniqueQuestionCount: Int,
    val totalPracticeCount: Int,
    val omittedQuestionCount: Int
)

data class SelfTestSection(
    val type: CramQuestionType,
    val quizIds: List<Int>
)

data class SelfTestSelection(
    val requestedQuestionCount: Int,
    val actualQuestionCount: Int,
    val quizIds: List<Int>,
    val sections: List<SelfTestSection>,
    val selectionPolicy: String
)

data class CramAnalysisResult(
    val schemaVersion: Int = 1,
    val totalQuestionCount: Int,
    val identities: List<CramQuestionIdentity>,
    val questionTypes: List<QuestionTypeStat>,
    val answerDistributions: List<AnswerDistributionStat>,
    val modules: List<ModuleStat>,
    val moduleCoverages: List<ModuleCoverageStat>,
    val referencedQuestionCount: Int,
    val unreferencedQuestionCount: Int,
    val multipleChoice: MultipleChoiceStats,
    val judgement: JudgementAnalysis,
    val numericFacts: List<NumericFact>,
    val numericFactSummaries: List<NumericFactSummary>,
    val duplicateGroups: List<DuplicateQuestionGroup>,
    val priorities: List<QuestionPriority>,
    val threeDayPlan: ThreeDayCramPlan,
    val selfTest: SelfTestSelection,
    val warnings: List<String>
)

/** Stable JSON helpers for Room/file/network caches without Android dependencies. */
object CramAnalysisJson {
    private val gson = Gson()

    fun encode(result: CramAnalysisResult): String = gson.toJson(result)

    fun decode(json: String): CramAnalysisResult =
        gson.fromJson(json, CramAnalysisResult::class.java)
}

const val ANSWER_DISTRIBUTION_USAGE_POLICY =
    "答案字母分布只描述当前题库，不能当作知识规律；仅可在完全不会时作为低可信兜底。"

const val JUDGEMENT_KEYWORD_USAGE_POLICY =
    "关键词仅反映当前题库中的相关性，不能脱离主体、数字、时限和适用条件直接判断正误。"
