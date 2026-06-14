package com.virin.visionquiz.dao

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.virin.visionquiz.util.convertNumToChar

@Entity(indices = [Index(value = ["name"])])
data class QuizLibrary(
    @PrimaryKey(autoGenerate = true) val id: Int = 0, // 添加 autoGenerate = true，设置默认值为0
    val name: String, @ColumnInfo(name = "quiz_count") val quizCount: Int
)

@Entity(indices = [Index(value = ["library_id"])])
data class Quiz(
    @PrimaryKey(autoGenerate = true) val id: Int = 0, // 添加 autoGenerate = true，设置默认值为0
    val prompt: String, // 题目
    val options: List<String>, // 选项
    val answer: Set<Int>, // 答案，多选题可以有多个答案，例如 setOf(0, 1)
    @ColumnInfo(name = "is_multiple_choice") val isMultipleChoice: Boolean, // 是否为多选题
    @ColumnInfo(name = "question_type") val questionType: String? = null,
    @ColumnInfo(name = "library_id") val libraryId: Int
) {
    init {
        if (answer.isEmpty()) {
            throw IllegalArgumentException("Answer is empty")
        }
        // 答案数字必须是选项数字的子集
        if (answer.any { it !in options.indices }) {
            throw IllegalArgumentException("Answer contains invalid option index")
        }
        // 多选题至少有两个选项
        if (isMultipleChoice && options.size < 2) {
            throw IllegalArgumentException("Multiple-choice question must have at least two options")
        }
        // 多选题答案数量不能超过选项数量
        if (isMultipleChoice && answer.size > options.size) {
            throw IllegalArgumentException("Multiple-choice question answer count exceeds option count")
        }
    }

    fun isCorrectAnswer(userAnswer: Set<Int>): Boolean {
        // 答案与用户答案必须完全一致
        return answer == userAnswer
    }
}

@Entity(
    indices = [
        Index(value = ["quiz_id"], unique = true),
        Index(value = ["library_id"])
    ]
)
data class QuizFavorite(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(name = "quiz_id") val quizId: Int,
    @ColumnInfo(name = "library_id") val libraryId: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)

@Entity(indices = [Index(value = ["library_id"])])
data class ExamSession(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(name = "library_id") val libraryId: Int,
    @ColumnInfo(name = "started_at") val startedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "ended_at") val endedAt: Long? = null,
    @ColumnInfo(name = "total_count") val totalCount: Int = 0,
    @ColumnInfo(name = "correct_count") val correctCount: Int = 0,
    @ColumnInfo(name = "is_completed") val isCompleted: Boolean = false
)

@Entity(
    indices = [
        Index(value = ["quiz_id"]),
        Index(value = ["library_id"]),
        Index(value = ["exam_session_id"])
    ]
)
data class QuizAnswerRecord(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(name = "quiz_id") val quizId: Int,
    @ColumnInfo(name = "library_id") val libraryId: Int,
    val mode: String,
    @ColumnInfo(name = "selected_answer") val selectedAnswer: Set<Int>,
    @ColumnInfo(name = "is_correct") val isCorrect: Boolean,
    @ColumnInfo(name = "answered_at") val answeredAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "exam_session_id") val examSessionId: Int? = null
)

@Entity(
    indices = [
        Index(value = ["library_id"]),
        Index(value = ["library_id", "mode"], unique = true)
    ]
)
data class PracticeSession(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(name = "library_id") val libraryId: Int,
    val mode: String,
    @ColumnInfo(name = "quiz_order") val quizOrder: String,
    @ColumnInfo(name = "current_index") val currentIndex: Int = 0,
    @ColumnInfo(name = "current_selection") val currentSelection: String = "",
    @ColumnInfo(name = "practice_answers") val practiceAnswers: String = "",
    @ColumnInfo(name = "practice_results") val practiceResults: String = "",
    @ColumnInfo(name = "recorded_quiz_ids") val recordedQuizIds: String = "",
    @ColumnInfo(name = "answer_visible") val answerVisible: Boolean = false,
    @ColumnInfo(name = "started_at") val startedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    indices = [
        Index(value = ["quiz_id", "type"], unique = true),
        Index(value = ["library_id"])
    ]
)
data class AiExplanationCache(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(name = "quiz_id") val quizId: Int,
    @ColumnInfo(name = "library_id") val libraryId: Int,
    val type: String,
    val fingerprint: String,
    val content: String,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)

enum class QuizStudyMode(val value: String, val label: String) {
    ORDERED_PRACTICE("ordered_practice", "顺序背题"),
    RANDOM_PRACTICE("random_practice", "随机背题"),
    REVIEW("review", "间隔复习"),
    EXAM("exam", "模拟考试")
}

enum class QuizUiType(val label: String) {
    SINGLE_CHOICE("单选"),
    MULTIPLE_CHOICE("多选"),
    JUDGEMENT("判断"),
    FILL_BLANK("填空"),
    SUBJECTIVE("主观")
}

fun Quiz.answerOptionsString(): String {
    return this.answer.map { 'A' + it }.joinToString("")
}

fun Quiz.answerString(): String {
    return this.answer.sorted().map { convertNumToChar(it) }.joinToString("")
}

fun Quiz.optionsString(): List<String> {
    return this.options.mapIndexedNotNull { index, opt ->
        if (opt.isEmpty()) null
        else "${convertNumToChar(index)}. $opt"
    }
}

fun Quiz.typeString(): String {
    return questionType?.takeIf { it.isNotBlank() } ?: if (options.first() == "正确") "判断"
    else if (answer.size > 1) "多选"
    else "单选"
}

fun Quiz.isJudgementUiType(): Boolean {
    return options.size == 2 && options[0] == "正确" && options[1] == "错误"
}

private fun Quiz.answerCoversAllOptions(): Boolean {
    return options.isNotEmpty() && answer.size == options.size && answer.containsAll(options.indices.toSet())
}

private fun Quiz.hasBlankPlaceholder(): Boolean {
    return BLANK_PLACEHOLDER_REGEX.containsMatchIn(prompt)
}

private fun Quiz.blankPlaceholderCount(): Int {
    return BLANK_PLACEHOLDER_REGEX.findAll(prompt).count()
}

private fun Quiz.looksLikeFillBlankUiType(): Boolean {
    if (!answerCoversAllOptions() || !hasBlankPlaceholder()) {
        return false
    }

    val blankCount = blankPlaceholderCount()
    return when {
        options.size == 1 -> blankCount == 1
        blankCount > 0 -> blankCount == options.size
        else -> false
    }
}

fun Quiz.inferredUiType(): QuizUiType {
    return when (questionType) {
        QuizUiType.SINGLE_CHOICE.label -> QuizUiType.SINGLE_CHOICE
        QuizUiType.MULTIPLE_CHOICE.label -> QuizUiType.MULTIPLE_CHOICE
        QuizUiType.JUDGEMENT.label -> QuizUiType.JUDGEMENT
        QuizUiType.FILL_BLANK.label -> QuizUiType.FILL_BLANK
        QuizUiType.SUBJECTIVE.label -> QuizUiType.SUBJECTIVE
        else -> when {
            isJudgementUiType() -> QuizUiType.JUDGEMENT
            looksLikeFillBlankUiType() -> QuizUiType.FILL_BLANK
            options.size == 1 && answer == setOf(0) && !hasBlankPlaceholder() -> QuizUiType.SUBJECTIVE
            isMultipleChoice || answer.size > 1 -> QuizUiType.MULTIPLE_CHOICE
            else -> QuizUiType.SINGLE_CHOICE
        }
    }
}

fun Quiz.isSupportedStudyType(): Boolean {
    return when (inferredUiType()) {
        QuizUiType.SINGLE_CHOICE,
        QuizUiType.MULTIPLE_CHOICE,
        QuizUiType.JUDGEMENT -> true
        QuizUiType.FILL_BLANK,
        QuizUiType.SUBJECTIVE -> false
    }
}

private val BLANK_PLACEHOLDER_REGEX = Regex("""_{2,}|（\s*）|\(\s*\)|﹍{2,}""")
