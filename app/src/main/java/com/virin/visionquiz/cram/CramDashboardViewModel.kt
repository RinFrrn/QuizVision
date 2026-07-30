package com.virin.visionquiz.cram

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.virin.visionquiz.ai.AiConfigStore
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

enum class CramAnalysisPhase {
    NOT_STARTED,
    REQUESTED,
    ANALYZING,
    READY,
    FAILED
}

enum class CramPracticeEntry {
    TODAY_TASK,
    SELF_TEST
}

data class CramPriorityModuleUi(
    val id: String,
    val title: String,
    val questionCount: Int,
    val coveragePercent: Int? = null,
    val reason: String = ""
)

data class CramMnemonicUi(
    val id: String,
    val title: String,
    val numberChain: String,
    val explanation: String
)

/**
 * UI-shaped analysis output. The local analyzer / AI repository can map its
 * persisted domain model into this object without coupling the dashboard to
 * storage or transport details.
 */
data class CramDashboardContentUi(
    val libraryName: String = "",
    val todayTitle: String = "今日任务",
    val todaySummary: String = "",
    val todayCompletedCount: Int = 0,
    val todayQuizIds: List<Int> = emptyList(),
    val priorityModules: List<CramPriorityModuleUi> = emptyList(),
    val mnemonics: List<CramMnemonicUi> = emptyList(),
    val quickCardPreview: String? = null,
    val quickCardMarkdown: String = "",
    val quickCardAvailable: Boolean = false,
    val localReportMarkdown: String = "",
    val aiReportMarkdown: String? = null,
    val selfTestQuizIds: List<Int> = emptyList()
)

data class CramDashboardUiState(
    val libraryId: Int,
    val examDateEpochDay: Long,
    val dailyMinutes: Int,
    val daysRemaining: Int,
    val questionCount: Int = 0,
    val aiConfigured: Boolean = false,
    val aiDataSharingConsentGranted: Boolean = false,
    val analysisPhase: CramAnalysisPhase = CramAnalysisPhase.NOT_STARTED,
    val analysisMessage: String = "还没有生成冲刺分析",
    val analysisProgress: Float? = null,
    val analysisRequestVersion: Int = 0,
    val generatedAtMillis: Long? = null,
    val content: CramDashboardContentUi = CramDashboardContentUi()
) {
    val isAnalysisInProgress: Boolean
        get() = analysisPhase == CramAnalysisPhase.REQUESTED ||
            analysisPhase == CramAnalysisPhase.ANALYZING
}

internal const val DEFAULT_CRAM_DAYS = 3
internal const val DEFAULT_DAILY_MINUTES = 60
internal const val MIN_DAILY_MINUTES = 15
internal const val MAX_DAILY_MINUTES = 240
internal const val DAILY_MINUTES_STEP = 15

internal fun calculateCramDaysRemaining(
    todayEpochDay: Long,
    examDateEpochDay: Long
): Int {
    return (examDateEpochDay - todayEpochDay)
        .coerceAtLeast(0L)
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()
}

internal fun normalizeCramDailyMinutes(minutes: Int): Int {
    val clamped = minutes.coerceIn(MIN_DAILY_MINUTES, MAX_DAILY_MINUTES)
    return (((clamped + DAILY_MINUTES_STEP / 2) / DAILY_MINUTES_STEP) * DAILY_MINUTES_STEP)
        .coerceIn(MIN_DAILY_MINUTES, MAX_DAILY_MINUTES)
}

internal fun buildInitialCramDashboardState(
    libraryId: Int,
    todayEpochDay: Long,
    storedExamDateEpochDay: Long?,
    storedDailyMinutes: Int?
): CramDashboardUiState {
    val examDateEpochDay = storedExamDateEpochDay
        ?.takeIf { it >= todayEpochDay }
        ?: (todayEpochDay + DEFAULT_CRAM_DAYS)
    val dailyMinutes = normalizeCramDailyMinutes(storedDailyMinutes ?: DEFAULT_DAILY_MINUTES)
    return CramDashboardUiState(
        libraryId = libraryId,
        examDateEpochDay = examDateEpochDay,
        dailyMinutes = dailyMinutes,
        daysRemaining = calculateCramDaysRemaining(todayEpochDay, examDateEpochDay)
    )
}

internal fun cramCountdownLabel(daysRemaining: Int): String {
    return when (daysRemaining.coerceAtLeast(0)) {
        0 -> "今天考试"
        1 -> "明天考试"
        else -> "距考试 ${daysRemaining.coerceAtLeast(0)} 天"
    }
}

internal fun formatCramExamDate(epochDay: Long): String {
    return LocalDate.ofEpochDay(epochDay).format(
        DateTimeFormatter.ofPattern("M月d日 E", Locale.SIMPLIFIED_CHINESE)
    )
}

class CramDashboardViewModel(
    application: Application,
    private val libraryId: Int
) : AndroidViewModel(application) {

    private val preferences = application.getSharedPreferences(
        CramAiConsentStore.PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )
    private val aiConfigStore = AiConfigStore(application)
    private val repository = CramAnalysisRepository(application)
    private var refreshJob: Job? = null
    private val _state = MutableLiveData(loadInitialState())
    val state: LiveData<CramDashboardUiState> = _state
    private val progressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (
                intent?.getIntExtra(CramAnalysisProgressStore.EXTRA_LIBRARY_ID, 0) != libraryId
            ) {
                return
            }
            val progress = repository.readProgress(libraryId)
            applyProgress(progress)
            if (
                progress.stage == CramAnalysisStage.COMPLETED ||
                progress.stage == CramAnalysisStage.FAILED ||
                progress.stage == CramAnalysisStage.CANCELLED
            ) {
                refresh()
            }
        }
    }

    init {
        ContextCompat.registerReceiver(
            application,
            progressReceiver,
            IntentFilter(CramAnalysisProgressStore.ACTION_PROGRESS_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        refresh()
    }

    /**
     * Reloads only user-owned local configuration. Analysis content remains
     * intact and can be refreshed independently by the repository.
     */
    fun refresh(forceLocal: Boolean = false) {
        val current = requireNotNull(_state.value)
        val todayEpochDay = LocalDate.now().toEpochDay()
        val storedExam = readStoredExamDate()
            ?.takeIf { it >= todayEpochDay }
            ?: current.examDateEpochDay.coerceAtLeast(todayEpochDay)
        val storedMinutes = if (preferences.contains(dailyMinutesKey())) {
            preferences.getInt(dailyMinutesKey(), current.dailyMinutes)
        } else {
            current.dailyMinutes
        }
        _state.value = current.copy(
            examDateEpochDay = storedExam,
            dailyMinutes = normalizeCramDailyMinutes(storedMinutes),
            daysRemaining = calculateCramDaysRemaining(todayEpochDay, storedExam),
            aiDataSharingConsentGranted = hasAiDataSharingConsent()
        )
        if (forceLocal) {
            updateState { it.copy(analysisMessage = "正在刷新本地题库画像") }
        }
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            val dailyMinutes = _state.value?.dailyMinutes ?: DEFAULT_DAILY_MINUTES
            val snapshot = withContext(Dispatchers.IO) {
                val pack = repository.loadStudyPack(
                    libraryId = libraryId,
                    forceLocal = forceLocal,
                    dailyMinutes = dailyMinutes,
                    planDay = planDayForDaysRemaining(
                        _state.value?.daysRemaining ?: DEFAULT_CRAM_DAYS
                    )
                )
                val aiConfigured = aiConfigStore.read().isComplete()
                Triple(pack, aiConfigured, repository.readProgress(libraryId))
            }
            val (pack, aiConfigured, progress) = snapshot
            if (pack == null) {
                updateState {
                    it.copy(
                        questionCount = 0,
                        aiConfigured = aiConfigured,
                        analysisPhase = CramAnalysisPhase.NOT_STARTED,
                        analysisMessage = "题库中暂无可分析题目",
                        analysisProgress = null
                    )
                }
            } else {
                applyStudyPack(pack, aiConfigured)
                applyProgress(progress)
            }
        }
    }

    fun refreshLocal() = refresh(forceLocal = false)

    fun setExamDate(epochDay: Long) {
        val todayEpochDay = LocalDate.now().toEpochDay()
        val safeEpochDay = epochDay.coerceAtLeast(todayEpochDay)
        preferences.edit().putLong(examDateKey(), safeEpochDay).apply()
        updateState {
            it.copy(
                examDateEpochDay = safeEpochDay,
                daysRemaining = calculateCramDaysRemaining(todayEpochDay, safeEpochDay)
            )
        }
        refresh()
    }

    fun setDailyMinutes(minutes: Int) {
        val normalized = normalizeCramDailyMinutes(minutes)
        if (_state.value?.dailyMinutes == normalized) return
        preferences.edit().putInt(dailyMinutesKey(), normalized).apply()
        updateState { it.copy(dailyMinutes = normalized) }
        refresh(forceLocal = true)
    }

    /**
     * Emits a monotonically increasing request version for the analysis
     * coordinator. The repository can observe state, start work, and call the
     * running / success / failure methods below.
     */
    fun startAiAnalysis() {
        val authorizedDestinationSignature = currentAiConsentSignature()
        if (!CramAiConsentStore.matches(
                getApplication(),
                libraryId,
                authorizedDestinationSignature
            )
        ) {
            updateState {
                it.copy(
                    aiDataSharingConsentGranted = false,
                    analysisPhase = CramAnalysisPhase.READY,
                    analysisMessage = "AI 配置已变化，请重新确认题库外发"
                )
            }
            return
        }
        updateState {
            it.copy(
                analysisPhase = CramAnalysisPhase.REQUESTED,
                analysisMessage = "正在准备题库分析",
                analysisProgress = null,
                analysisRequestVersion = it.analysisRequestVersion + 1
            )
        }
        val forceRefresh = _state.value?.content?.aiReportMarkdown?.isNotBlank() == true
        CramAnalysisService.start(
            context = getApplication(),
            libraryId = libraryId,
            forceRefresh = forceRefresh,
            dailyMinutes = _state.value?.dailyMinutes ?: DEFAULT_DAILY_MINUTES,
            authorizedDestinationSignature = requireNotNull(
                authorizedDestinationSignature
            )
        )
    }

    fun startAnalysis() = startAiAnalysis()

    fun cancelAiAnalysis() {
        CramAnalysisService.cancel(getApplication(), libraryId)
        updateState {
            val hasContent = it.content.priorityModules.isNotEmpty() ||
                it.content.mnemonics.isNotEmpty() ||
                it.content.todayQuizIds.isNotEmpty() ||
                it.content.quickCardAvailable ||
                it.content.selfTestQuizIds.isNotEmpty()
            it.copy(
                analysisPhase = if (hasContent) {
                    CramAnalysisPhase.READY
                } else {
                    CramAnalysisPhase.NOT_STARTED
                },
                analysisMessage = if (hasContent) "已保留上次生成结果" else "分析已取消",
                analysisProgress = null
            )
        }
    }

    fun grantAiDataSharingConsent() {
        val signature = currentAiConsentSignature()
        if (signature == null) {
            updateState { it.copy(aiDataSharingConsentGranted = false) }
            return
        }
        CramAiConsentStore.grant(getApplication(), libraryId, signature)
        updateState { it.copy(aiDataSharingConsentGranted = true) }
    }

    fun markAnalysisRunning(progress: Float?, message: String) {
        updateState {
            it.copy(
                analysisPhase = CramAnalysisPhase.ANALYZING,
                analysisMessage = message,
                analysisProgress = progress?.coerceIn(0f, 1f)
            )
        }
    }

    fun applyAnalysisContent(
        content: CramDashboardContentUi,
        generatedAtMillis: Long = System.currentTimeMillis()
    ) {
        updateState {
            it.copy(
                analysisPhase = CramAnalysisPhase.READY,
                analysisMessage = "冲刺分析已生成",
                analysisProgress = 1f,
                generatedAtMillis = generatedAtMillis,
                content = content
            )
        }
    }

    fun markAnalysisFailed(message: String) {
        updateState {
            it.copy(
                analysisPhase = CramAnalysisPhase.FAILED,
                analysisMessage = message,
                analysisProgress = null
            )
        }
    }

    fun quizIdsFor(entry: CramPracticeEntry): IntArray {
        val content = _state.value?.content ?: return intArrayOf()
        return when (entry) {
            CramPracticeEntry.TODAY_TASK -> content.todayQuizIds
            CramPracticeEntry.SELF_TEST -> content.selfTestQuizIds
        }.distinct().toIntArray()
    }

    private fun loadInitialState(): CramDashboardUiState {
        val todayEpochDay = LocalDate.now().toEpochDay()
        return buildInitialCramDashboardState(
            libraryId = libraryId,
            todayEpochDay = todayEpochDay,
            storedExamDateEpochDay = readStoredExamDate(),
            storedDailyMinutes = if (preferences.contains(dailyMinutesKey())) {
                preferences.getInt(dailyMinutesKey(), DEFAULT_DAILY_MINUTES)
            } else {
                null
            }
        )
    }

    private fun readStoredExamDate(): Long? {
        return if (preferences.contains(examDateKey())) {
            preferences.getLong(examDateKey(), 0L)
        } else {
            null
        }
    }

    private fun hasAiDataSharingConsent(): Boolean {
        val currentSignature = currentAiConsentSignature() ?: return false
        return CramAiConsentStore.matches(getApplication(), libraryId, currentSignature)
    }

    private fun currentAiConsentSignature(): String? {
        return aiConfigStore.read().dataSharingDestinationSignature()
    }

    private fun updateState(transform: (CramDashboardUiState) -> CramDashboardUiState) {
        val current = _state.value ?: return
        _state.value = transform(current)
    }

    private fun examDateKey() = "library_${libraryId}_exam_date_epoch_day"

    private fun dailyMinutesKey() = "library_${libraryId}_daily_minutes"

    private fun applyStudyPack(pack: CramStudyPack, aiConfigured: Boolean) {
        val targetDayNumber = planDayForDaysRemaining(
            _state.value?.daysRemaining ?: DEFAULT_CRAM_DAYS
        )
        val firstDay = pack.analysis.threeDayPlan.days
            .firstOrNull { it.day == targetDayNumber }
            ?: pack.analysis.threeDayPlan.days.firstOrNull()
        val priorityModules = pack.analysis.modules.map { module ->
            CramPriorityModuleUi(
                id = module.key,
                title = module.displayName,
                questionCount = module.questionCount,
                coveragePercent = (module.ratioOfBank.coerceIn(0.0, 1.0) * 100).roundToInt()
            )
        }
        val mnemonics = pack.analysis.numericFactSummaries
            .asSequence()
            .filter {
                it.correctOrSupportedCount > 0 &&
                    it.incorrectCount == 0 &&
                    it.contexts.isNotEmpty()
            }
            .sortedWith(
                compareByDescending<NumericFactSummary> { it.correctOrSupportedCount }
                    .thenByDescending { it.occurrenceCount }
            )
            .take(MAX_UI_NUMERIC_FACTS)
            .map { fact ->
                CramMnemonicUi(
                    id = fact.key,
                    title = fact.contexts.first().replace(Regex("""\s+"""), " ").take(48),
                    numberChain = "${fact.normalizedValue}${fact.unit}",
                    explanation = buildString {
                        append("${fact.category.displayName} · 题库支持 ${fact.correctOrSupportedCount} 次")
                        if (fact.quizIds.isNotEmpty()) {
                            append(" · 题号 ")
                            append(fact.quizIds.take(6).joinToString("、"))
                        }
                    }
                )
            }
            .toList()
        val quickCardMarkdown = pack.quickCardMarkdown
        val content = CramDashboardContentUi(
            libraryName = pack.libraryName,
            todayTitle = firstDay?.let { "第${it.day}天 · ${it.title}" } ?: "今日任务",
            todaySummary = firstDay?.focus.orEmpty(),
            todayCompletedCount = pack.todayCompletedCount,
            todayQuizIds = pack.todayQuizIds,
            priorityModules = priorityModules,
            mnemonics = mnemonics,
            quickCardPreview = markdownPreview(quickCardMarkdown),
            quickCardMarkdown = quickCardMarkdown,
            quickCardAvailable = quickCardMarkdown.isNotBlank(),
            localReportMarkdown = pack.localMarkdown,
            aiReportMarkdown = pack.aiReportMarkdown,
            selfTestQuizIds = pack.selfTestQuizIds
        )
        updateState {
            it.copy(
                questionCount = pack.questionCount,
                aiConfigured = aiConfigured,
                analysisPhase = CramAnalysisPhase.READY,
                analysisMessage = if (pack.aiReportMarkdown.isNullOrBlank()) {
                    "本地题库画像已生成"
                } else {
                    "本地画像与 AI 冲刺总稿已生成"
                },
                analysisProgress = null,
                generatedAtMillis = pack.generatedAt,
                content = content
            )
        }
    }

    private fun applyProgress(progress: CramAnalysisProgress) {
        when {
            progress.isRunning -> markAnalysisRunning(
                progress = progress.progressFraction.takeIf { progress.totalSteps > 0 },
                message = progress.message.ifBlank { "正在分析题库" }
            )
            progress.stage == CramAnalysisStage.FAILED -> markAnalysisFailed(
                progress.errorMessage.ifBlank {
                    progress.message.ifBlank { "AI 分析未完成，已保留本地版" }
                }
            )
            progress.stage == CramAnalysisStage.CANCELLED -> cancelAiAnalysisStateOnly(
                progress.message.ifBlank { "分析已取消，已完成的分块会保留" }
            )
            progress.stage == CramAnalysisStage.COMPLETED -> updateState {
                it.copy(
                    analysisPhase = CramAnalysisPhase.READY,
                    analysisMessage = progress.message.ifBlank { "冲刺总稿已生成" },
                    analysisProgress = 1f
                )
            }
        }
    }

    private fun cancelAiAnalysisStateOnly(message: String) {
        updateState {
            it.copy(
                analysisPhase = if (it.content.localReportMarkdown.isNotBlank()) {
                    CramAnalysisPhase.READY
                } else {
                    CramAnalysisPhase.NOT_STARTED
                },
                analysisMessage = message,
                analysisProgress = null
            )
        }
    }

    override fun onCleared() {
        runCatching { getApplication<Application>().unregisterReceiver(progressReceiver) }
        super.onCleared()
    }

    companion object {
        private const val MAX_UI_NUMERIC_FACTS = 16

        fun factory(application: Application, libraryId: Int): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return CramDashboardViewModel(application, libraryId) as T
                }
            }
        }
    }
}

private fun markdownPreview(markdown: String): String? {
    return markdown.lineSequence()
        .map { line ->
            line.trim()
                .removePrefix("#")
                .trim()
                .replace("**", "")
        }
        .filter { it.isNotBlank() }
        .dropWhile { it.contains("考前20分钟") }
        .take(2)
        .joinToString(" · ")
        .take(120)
        .takeIf(String::isNotBlank)
}

internal fun planDayForDaysRemaining(daysRemaining: Int): Int = when {
    daysRemaining >= 3 -> 1
    daysRemaining == 2 -> 2
    else -> 3
}
