package com.virin.visionquiz.util

import android.content.Context
import org.json.JSONArray

data class ImportCandidateConfig(
    val promptHeaders: List<String> = ImportCandidateSettings.DEFAULT_PROMPT_HEADERS,
    val typeHeaders: List<String> = ImportCandidateSettings.DEFAULT_TYPE_HEADERS,
    val answerHeaders: List<String> = ImportCandidateSettings.DEFAULT_ANSWER_HEADERS,
    val optionPrefixes: List<String> = ImportCandidateSettings.DEFAULT_OPTION_PREFIXES,
    val analysisHeaders: List<String> = ImportCandidateSettings.DEFAULT_ANALYSIS_HEADERS,
    val singleChoiceTypes: List<String> = ImportCandidateSettings.DEFAULT_SINGLE_CHOICE_TYPES,
    val multipleChoiceTypes: List<String> = ImportCandidateSettings.DEFAULT_MULTIPLE_CHOICE_TYPES,
    val judgementTypes: List<String> = ImportCandidateSettings.DEFAULT_JUDGEMENT_TYPES,
    val fillBlankTypes: List<String> = ImportCandidateSettings.DEFAULT_FILL_BLANK_TYPES,
    val subjectiveTypes: List<String> = ImportCandidateSettings.DEFAULT_SUBJECTIVE_TYPES
)

object ImportCandidateSettings {
    private const val PREFS_NAME = "quiz_import_candidate_settings"

    private const val KEY_PROMPT_HEADERS = "prompt_headers"
    private const val KEY_TYPE_HEADERS = "type_headers"
    private const val KEY_ANSWER_HEADERS = "answer_headers"
    private const val KEY_OPTION_PREFIXES = "option_prefixes"
    private const val KEY_ANALYSIS_HEADERS = "analysis_headers"
    private const val KEY_SINGLE_CHOICE_TYPES = "single_choice_types"
    private const val KEY_MULTIPLE_CHOICE_TYPES = "multiple_choice_types"
    private const val KEY_JUDGEMENT_TYPES = "judgement_types"
    private const val KEY_FILL_BLANK_TYPES = "fill_blank_types"
    private const val KEY_SUBJECTIVE_TYPES = "subjective_types"

    val DEFAULT_PROMPT_HEADERS = listOf(
        "题干", "题目", "标题", "问题", "试题", "题目内容", "试题内容", "问题描述", "题干内容", "题目描述"
    )
    val DEFAULT_TYPE_HEADERS = listOf(
        "题型", "类型", "题目类型", "试题类型", "题目题型", "试题题型", "分类"
    )
    val DEFAULT_ANSWER_HEADERS = listOf(
        "正确答案", "答案", "标准答案", "参考答案", "正确选项", "选项答案", "解答", "题目答案", "试题答案"
    )
    val DEFAULT_OPTION_PREFIXES = listOf(
        "选项", "选择项", "可选项", "供选项", "试题选项"
    )
    val DEFAULT_ANALYSIS_HEADERS = listOf(
        "解析", "答案解析", "题目解析", "试题解析", "解析说明", "说明", "备注"
    )
    val DEFAULT_SINGLE_CHOICE_TYPES = listOf(
        "单选", "单选题", "单项选择", "单项选择题", "选择题"
    )
    val DEFAULT_MULTIPLE_CHOICE_TYPES = listOf(
        "多选", "多选题", "多项选择", "多项选择题", "不定项", "不定项选择题"
    )
    val DEFAULT_JUDGEMENT_TYPES = listOf(
        "判断", "判断题", "是非题", "对错题"
    )
    val DEFAULT_FILL_BLANK_TYPES = listOf(
        "填空", "填空题", "补空题"
    )
    val DEFAULT_SUBJECTIVE_TYPES = listOf(
        "简答", "简答题", "问答", "问答题", "主观", "主观题", "计算", "计算题"
    )

    fun load(context: Context): ImportCandidateConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return ImportCandidateConfig(
            promptHeaders = readList(prefs.getString(KEY_PROMPT_HEADERS, null), DEFAULT_PROMPT_HEADERS),
            typeHeaders = readList(prefs.getString(KEY_TYPE_HEADERS, null), DEFAULT_TYPE_HEADERS),
            answerHeaders = readList(prefs.getString(KEY_ANSWER_HEADERS, null), DEFAULT_ANSWER_HEADERS),
            optionPrefixes = readList(prefs.getString(KEY_OPTION_PREFIXES, null), DEFAULT_OPTION_PREFIXES),
            analysisHeaders = readList(prefs.getString(KEY_ANALYSIS_HEADERS, null), DEFAULT_ANALYSIS_HEADERS),
            singleChoiceTypes = readList(prefs.getString(KEY_SINGLE_CHOICE_TYPES, null), DEFAULT_SINGLE_CHOICE_TYPES),
            multipleChoiceTypes = readList(prefs.getString(KEY_MULTIPLE_CHOICE_TYPES, null), DEFAULT_MULTIPLE_CHOICE_TYPES),
            judgementTypes = readList(prefs.getString(KEY_JUDGEMENT_TYPES, null), DEFAULT_JUDGEMENT_TYPES),
            fillBlankTypes = readList(prefs.getString(KEY_FILL_BLANK_TYPES, null), DEFAULT_FILL_BLANK_TYPES),
            subjectiveTypes = readList(prefs.getString(KEY_SUBJECTIVE_TYPES, null), DEFAULT_SUBJECTIVE_TYPES)
        )
    }

    fun save(context: Context, settings: ImportCandidateConfig) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PROMPT_HEADERS, encodeList(settings.promptHeaders))
            .putString(KEY_TYPE_HEADERS, encodeList(settings.typeHeaders))
            .putString(KEY_ANSWER_HEADERS, encodeList(settings.answerHeaders))
            .putString(KEY_OPTION_PREFIXES, encodeList(settings.optionPrefixes))
            .putString(KEY_ANALYSIS_HEADERS, encodeList(settings.analysisHeaders))
            .putString(KEY_SINGLE_CHOICE_TYPES, encodeList(settings.singleChoiceTypes))
            .putString(KEY_MULTIPLE_CHOICE_TYPES, encodeList(settings.multipleChoiceTypes))
            .putString(KEY_JUDGEMENT_TYPES, encodeList(settings.judgementTypes))
            .putString(KEY_FILL_BLANK_TYPES, encodeList(settings.fillBlankTypes))
            .putString(KEY_SUBJECTIVE_TYPES, encodeList(settings.subjectiveTypes))
            .apply()
    }

    fun resetToDefault(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    private fun readList(rawJson: String?, defaults: List<String>): List<String> {
        if (rawJson.isNullOrBlank()) return defaults
        return runCatching {
            val array = JSONArray(rawJson)
            val items = (0 until array.length())
                .mapNotNull { index -> array.optString(index).trim().takeIf { it.isNotBlank() } }
            normalizeItems(items).ifEmpty { defaults }
        }.getOrDefault(defaults)
    }

    private fun encodeList(items: List<String>): String {
        val array = JSONArray()
        normalizeItems(items).forEach(array::put)
        return array.toString()
    }

    private fun normalizeItems(items: List<String>): List<String> {
        val seen = linkedSetOf<String>()
        items.map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { seen += it }
        return seen.toList()
    }
}
