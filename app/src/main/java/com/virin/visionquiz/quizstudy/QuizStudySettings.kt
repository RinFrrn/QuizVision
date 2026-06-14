package com.virin.visionquiz.quizstudy

import android.content.Context
import com.virin.visionquiz.dao.QuizStudyMode

enum class ExamQuestionSource(val value: String, val label: String) {
    ALL("all", "全部"),
    FAVORITES("favorites", "收藏"),
    WRONG("wrong", "错题");

    companion object {
        fun fromValue(value: String?): ExamQuestionSource {
            return values().firstOrNull { it.value == value } ?: ALL
        }
    }
}

data class ExamQuestionCounts(
    val singleChoice: Int = 10,
    val multipleChoice: Int = 10,
    val judgement: Int = 10
)

object QuizStudySettings {
    private const val PREFS_NAME = "quiz_study_settings"
    private const val KEY_OPTION_SHUFFLE_PREFIX = "option_shuffle_enabled"
    private const val KEY_PRACTICE_ANSWER_SOUND_ENABLED = "practice_answer_sound_enabled"
    private const val KEY_EXAM_QUESTION_SOURCE = "exam_question_source"
    private const val KEY_EXAM_SINGLE_COUNT = "exam_single_choice_count"
    private const val KEY_EXAM_MULTIPLE_COUNT = "exam_multiple_choice_count"
    private const val KEY_EXAM_JUDGEMENT_COUNT = "exam_judgement_count"
    private const val KEY_RUNNER_TEXT_SIZE_LEVEL = "quiz_runner_text_size"
    private const val KEY_NEW_REVIEW_CARDS_PER_SESSION = "new_review_cards_per_session"
    private const val DEFAULT_EXAM_QUESTION_COUNT = 10
    private const val DEFAULT_NEW_REVIEW_CARDS_PER_SESSION = 20

    fun readOptionShuffleEnabled(
        context: Context,
        libraryId: Int,
        mode: QuizStudyMode
    ): Boolean {
        return context.getSharedPreferences(PREFS_NAME, 0)
            .getBoolean(optionShuffleKey(libraryId, mode), false)
    }

    fun saveOptionShuffleEnabled(
        context: Context,
        libraryId: Int,
        mode: QuizStudyMode,
        enabled: Boolean
    ) {
        context.getSharedPreferences(PREFS_NAME, 0)
            .edit()
            .putBoolean(optionShuffleKey(libraryId, mode), enabled)
            .apply()
    }

    fun readPracticeAnswerSoundEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, 0)
            .getBoolean(KEY_PRACTICE_ANSWER_SOUND_ENABLED, true)
    }

    fun savePracticeAnswerSoundEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, 0)
            .edit()
            .putBoolean(KEY_PRACTICE_ANSWER_SOUND_ENABLED, enabled)
            .apply()
    }

    fun readRunnerTextSizeLevel(context: Context): String {
        val value = context.getSharedPreferences(PREFS_NAME, 0)
            .getString(KEY_RUNNER_TEXT_SIZE_LEVEL, "normal")
        return if (value in setOf("small", "normal", "large", "extra_large")) {
            value.orEmpty()
        } else {
            "normal"
        }
    }

    fun saveRunnerTextSizeLevel(context: Context, value: String) {
        val safeValue = if (value in setOf("small", "normal", "large", "extra_large")) {
            value
        } else {
            "normal"
        }
        context.getSharedPreferences(PREFS_NAME, 0)
            .edit()
            .putString(KEY_RUNNER_TEXT_SIZE_LEVEL, safeValue)
            .apply()
    }

    fun readExamQuestionSource(context: Context): ExamQuestionSource {
        return ExamQuestionSource.fromValue(
            context.getSharedPreferences(PREFS_NAME, 0)
                .getString(KEY_EXAM_QUESTION_SOURCE, ExamQuestionSource.ALL.value)
        )
    }

    fun saveExamQuestionSource(context: Context, source: ExamQuestionSource) {
        context.getSharedPreferences(PREFS_NAME, 0)
            .edit()
            .putString(KEY_EXAM_QUESTION_SOURCE, source.value)
            .apply()
    }

    fun readExamQuestionCounts(context: Context): ExamQuestionCounts {
        val prefs = context.getSharedPreferences(PREFS_NAME, 0)
        return ExamQuestionCounts(
            singleChoice = prefs.getInt(KEY_EXAM_SINGLE_COUNT, DEFAULT_EXAM_QUESTION_COUNT)
                .coerceAtLeast(0),
            multipleChoice = prefs.getInt(KEY_EXAM_MULTIPLE_COUNT, DEFAULT_EXAM_QUESTION_COUNT)
                .coerceAtLeast(0),
            judgement = prefs.getInt(KEY_EXAM_JUDGEMENT_COUNT, DEFAULT_EXAM_QUESTION_COUNT)
                .coerceAtLeast(0)
        )
    }

    fun saveExamQuestionCounts(context: Context, counts: ExamQuestionCounts) {
        context.getSharedPreferences(PREFS_NAME, 0)
            .edit()
            .putInt(KEY_EXAM_SINGLE_COUNT, counts.singleChoice.coerceAtLeast(0))
            .putInt(KEY_EXAM_MULTIPLE_COUNT, counts.multipleChoice.coerceAtLeast(0))
            .putInt(KEY_EXAM_JUDGEMENT_COUNT, counts.judgement.coerceAtLeast(0))
            .apply()
    }

    fun readNewReviewCardsPerSession(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, 0)
            .getInt(
                KEY_NEW_REVIEW_CARDS_PER_SESSION,
                DEFAULT_NEW_REVIEW_CARDS_PER_SESSION
            )
            .coerceAtLeast(0)
    }

    fun saveNewReviewCardsPerSession(context: Context, count: Int) {
        context.getSharedPreferences(PREFS_NAME, 0)
            .edit()
            .putInt(KEY_NEW_REVIEW_CARDS_PER_SESSION, count.coerceAtLeast(0))
            .apply()
    }

    private fun optionShuffleKey(libraryId: Int, mode: QuizStudyMode): String {
        return "${KEY_OPTION_SHUFFLE_PREFIX}_${libraryId}_${mode.value}"
    }
}
