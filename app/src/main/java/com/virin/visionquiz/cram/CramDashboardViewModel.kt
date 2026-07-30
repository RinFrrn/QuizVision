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
import com.virin.visionquiz.quizlist.quizcontent.QuizContentExtras
import com.virin.visionquiz.quizlist.quizcontent.QuizContentMemoryPoint
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

enum class CramPriorityGroupingMode {
    UNAVAILABLE,
    KNOWLEDGE_MODULES,
    MIXED,
    QUESTION_TYPE_FALLBACK
}

data class CramPriorityModuleUi(
    val id: String,
    val title: String,
    val questionCount: Int,
    val rank: Int = 0,
    val coveragePercent: Int? = null,
    val reason: String = "",
    val isFallback: Boolean = false,
    val typeSummary: String = "",
    val numericFactCount: Int = 0,
    val sourceReferenceCount: Int = 0,
    val quizIds: List<Int> = emptyList()
)

data class CramMnemonicUi(
    val id: String,
    val title: String,
    val numberChain: String,
    val explanation: String,
    val quizIds: List<Int> = emptyList()
)

internal fun cramMnemonicMemoryPointId(mnemonicId: String): String {
    return "cram-mnemonic-$mnemonicId"
}

internal fun buildCramMnemonicQuizContentExtras(
    pointsWithQuizIds: List<Pair<QuizContentMemoryPoint, List<Int>>>
): QuizContentExtras {
    val pointsByQuizId = linkedMapOf<Int, MutableList<QuizContentMemoryPoint>>()
    pointsWithQuizIds.forEach { (memoryPoint, quizIds) ->
        quizIds.forEach { quizId ->
            val points = pointsByQuizId.getOrPut(quizId, ::mutableListOf)
            if (points.none { it.id == memoryPoint.id }) {
                points += memoryPoint
            }
        }
    }
    return QuizContentExtras(
        memoryPointsByQuizId = pointsByQuizId.mapValues { (_, points) -> points.toList() },
        showMemoryPointEmptyState = true
    )
}

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
    val priorityGroupingMode: CramPriorityGroupingMode =
        CramPriorityGroupingMode.UNAVAILABLE,
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

internal fun resolveCramPriorityGroupingMode(
    modules: List<CramPriorityModuleUi>
): CramPriorityGroupingMode {
    if (modules.isEmpty()) return CramPriorityGroupingMode.UNAVAILABLE
    val fallbackCount = modules.count(CramPriorityModuleUi::isFallback)
    return when (fallbackCount) {
        0 -> CramPriorityGroupingMode.KNOWLEDGE_MODULES
        modules.size -> CramPriorityGroupingMode.QUESTION_TYPE_FALLBACK
        else -> CramPriorityGroupingMode.MIXED
    }
}

internal fun buildCramPriorityModules(
    modules: List<ModuleStat>,
    identities: List<CramQuestionIdentity>,
    priorities: List<QuestionPriority>,
    availableDatabaseIds: Set<Int>
): List<CramPriorityModuleUi> {
    val storedQuizIdByAnalysisId = identities.associate {
        it.analysisQuizId to it.storedQuizId
    }
    val priorityScoreByQuizId = priorities.associate {
        it.quizId to it.score
    }
    return modules.mapIndexed { index, module ->
        val isFallback = module.sourceReferences.isEmpty()
        val orderedQuizIds = module.quizIds
            .distinct()
            .sortedWith(
                compareByDescending<Int> { priorityScoreByQuizId[it] ?: 0.0 }
                    .thenBy { it }
            )
            .mapNotNull(storedQuizIdByAnalysisId::get)
            .filter { it > 0 && it in availableDatabaseIds }
            .distinct()
        CramPriorityModuleUi(
            id = module.key,
            title = module.displayName,
            questionCount = module.questionCount,
            rank = index + 1,
            coveragePercent = (module.ratioOfBank.coerceIn(0.0, 1.0) * 100)
                .roundToInt(),
            reason = if (isFallback) {
                "题库未标模块，按题型自动分组"
            } else {
                "按题库的答案依据归入该模块"
            },
            isFallback = isFallback,
            typeSummary = module.typeCounts.joinToString(" · ") {
                "${it.type.displayName} ${it.questionCount}题"
            },
            numericFactCount = module.numericFactCount,
            sourceReferenceCount = module.sourceReferences.size,
            quizIds = orderedQuizIds
        )
    }
}

internal fun resolveCramMnemonicDatabaseIds(
    analysisQuizIds: List<Int>,
    storedQuizIdByAnalysisId: Map<Int, Int>,
    availableDatabaseIds: Set<Int>
): List<Int> {
    return analysisQuizIds
        .mapNotNull { analysisQuizId ->
            storedQuizIdByAnalysisId[analysisQuizId]
                ?: analysisQuizId.takeIf { it in availableDatabaseIds }
        }
        .filter { it > 0 && it in availableDatabaseIds }
        .distinct()
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
    private var quizReferenceIndex = CramQuizReferenceIndex(emptyList())
    private var mnemonicQuizContentExtras = QuizContentExtras()
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
                quizReferenceIndex = CramQuizReferenceIndex(emptyList())
                mnemonicQuizContentExtras = QuizContentExtras()
                updateState {
                    it.copy(
                        questionCount = 0,
                        aiConfigured = aiConfigured,
                        analysisPhase = CramAnalysisPhase.NOT_STARTED,
                        analysisMessage = "题库中暂无可分析题目",
                        analysisProgress = null,
                        content = CramDashboardContentUi()
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

    internal fun hasQuizReference(target: CramQuizReferenceTarget): Boolean {
        return quizReferenceIndex.contains(target)
    }

    internal fun quizSheetSelection(
        target: CramQuizReferenceTarget
    ): CramQuizSheetSelection? {
        return quizReferenceIndex.selection(target)
    }

    internal fun reportQuizSheetSelection(
        clickedReference: CramQuizReferenceContext,
        reportReferences: List<CramQuizReferenceContext>
    ): CramQuizSheetSelection? {
        val selection = quizReferenceIndex.selection(clickedReference.target) ?: return null
        return selection.copy(
            extras = quizReferenceIndex.extrasFor(
                contexts = reportReferences,
                preferredMemoryPointId = clickedReference.memoryPoint?.id,
                showMemoryPointEmptyState = true
            )
        )
    }

    internal fun mnemonicQuizSheetSelection(
        target: CramQuizReferenceTarget,
        preferredMemoryPointId: String
    ): CramQuizSheetSelection? {
        val selection = quizReferenceIndex.selection(target) ?: return null
        return selection.copy(
            extras = mnemonicQuizContentExtras.copy(
                preferredMemoryPointId = preferredMemoryPointId,
                showMemoryPointEmptyState = true
            )
        )
    }

    internal fun priorityModuleQuizSheetSelection(
        moduleId: String
    ): CramQuizSheetSelection? {
        val quizIds = _state.value
            ?.content
            ?.priorityModules
            ?.firstOrNull { it.id == moduleId }
            ?.quizIds
            .orEmpty()
        return quizReferenceIndex.selectionForDatabaseIds(quizIds)
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
        quizReferenceIndex = CramQuizReferenceIndex(pack.quizzes)
        val targetDayNumber = planDayForDaysRemaining(
            _state.value?.daysRemaining ?: DEFAULT_CRAM_DAYS
        )
        val firstDay = pack.analysis.threeDayPlan.days
            .firstOrNull { it.day == targetDayNumber }
            ?: pack.analysis.threeDayPlan.days.firstOrNull()
        val priorityModules = buildCramPriorityModules(
            modules = pack.analysis.modules,
            identities = pack.analysis.identities,
            priorities = pack.analysis.priorities,
            availableDatabaseIds = pack.quizzes
                .asSequence()
                .map { it.id }
                .filter { it > 0 }
                .toSet()
        )
        val priorityGroupingMode = resolveCramPriorityGroupingMode(priorityModules)
        val storedQuizIdByAnalysisId = pack.analysis.identities.associate {
            it.analysisQuizId to it.storedQuizId
        }
        val availableDatabaseIds = pack.quizzes
            .asSequence()
            .map { it.id }
            .filter { it > 0 }
            .toSet()
        val resolvedMnemonicFacts = pack.analysis.numericFactSummaries
            .asSequence()
            .filter {
                it.correctOrSupportedCount > 0 &&
                    it.incorrectCount == 0 &&
                    it.contexts.any(String::isNotBlank)
            }
            .sortedWith(
                compareByDescending<NumericFactSummary> { it.correctOrSupportedCount }
                    .thenByDescending { it.occurrenceCount }
            )
            .take(MAX_UI_NUMERIC_FACTS)
            .map { fact ->
                val allQuizIds = resolveCramMnemonicDatabaseIds(
                    analysisQuizIds = fact.quizIds,
                    storedQuizIdByAnalysisId = storedQuizIdByAnalysisId,
                    availableDatabaseIds = availableDatabaseIds
                )
                val memoryContext = fact.contexts.first(String::isNotBlank)
                    .replace(Regex("""\s+"""), " ")
                    .trim()
                val ui = CramMnemonicUi(
                    id = fact.key,
                    title = memoryContext.take(48),
                    numberChain = "${fact.normalizedValue}${fact.unit}",
                    explanation = "${fact.category.displayName} · " +
                        "题库支持 ${fact.correctOrSupportedCount} 次",
                    quizIds = allQuizIds.take(6)
                )
                Triple(
                    ui,
                    allQuizIds,
                    QuizContentMemoryPoint(
                        id = cramMnemonicMemoryPointId(ui.id),
                        sourceLabel = "数字速记",
                        cue = ui.numberChain,
                        context = memoryContext,
                        supportingText = ui.explanation
                    )
                )
            }
            .toList()
        val mnemonics = resolvedMnemonicFacts.map { it.first }
        mnemonicQuizContentExtras = buildCramMnemonicQuizContentExtras(
            resolvedMnemonicFacts.map { (_, allQuizIds, memoryPoint) ->
                memoryPoint to allQuizIds
            }
        )
        val quickCardMarkdown = pack.quickCardMarkdown
        val content = CramDashboardContentUi(
            libraryName = pack.libraryName,
            todayTitle = firstDay?.let { "第${it.day}天 · ${it.title}" } ?: "今日任务",
            todaySummary = firstDay?.focus.orEmpty(),
            todayCompletedCount = pack.todayCompletedCount,
            todayQuizIds = pack.todayQuizIds,
            priorityModules = priorityModules,
            priorityGroupingMode = priorityGroupingMode,
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
