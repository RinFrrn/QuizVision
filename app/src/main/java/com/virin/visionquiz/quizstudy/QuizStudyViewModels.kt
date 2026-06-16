package com.virin.visionquiz.quizstudy

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.virin.visionquiz.ai.AiConfigStore
import com.virin.visionquiz.ai.AiExplanationRepository
import com.virin.visionquiz.ai.AiExplanationType
import com.virin.visionquiz.ai.AiPromptBuilder
import com.virin.visionquiz.dao.ExamSession
import com.virin.visionquiz.dao.PracticeSession
import com.virin.visionquiz.dao.Quiz
import com.virin.visionquiz.dao.QuizAnswerRecord
import com.virin.visionquiz.dao.QuizLibrary
import com.virin.visionquiz.dao.QuizStudyMode
import com.virin.visionquiz.dao.QuizUiType
import com.virin.visionquiz.dao.ReviewCard
import com.virin.visionquiz.dao.ReviewRating
import com.virin.visionquiz.dao.inferredUiType
import com.virin.visionquiz.dao.isSupportedStudyType
import com.virin.visionquiz.quizlibrarylist.QuizRepository
import com.virin.visionquiz.quizlibrarylist.QuizRepositoryImpl
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class SupportedQuizStats(
    val total: Int = 0,
    val singleChoice: Int = 0,
    val multipleChoice: Int = 0,
    val judgement: Int = 0
)

data class LibraryAnswerStats(
    val todayAnswered: Int = 0,
    val totalAnswered: Int = 0,
    val todayWrong: Int = 0,
    val totalWrong: Int = 0,
    val accuracyPercent: Int? = null
)

data class ReviewStats(
    val dueToday: Int = 0,
    val reviewedToday: Int = 0,
    val totalCards: Int = 0,
    val totalLapses: Int = 0
)

data class ReviewEntryState(
    val dueReviewCount: Int = 0,
    val newLearningCount: Int = 0
) {
    val title: String
        get() = "开始学习"
    val description: String
        get() = "待复习 $dueReviewCount 题 · 待学习 $newLearningCount 题"
    val hasPendingWork: Boolean
        get() = dueReviewCount + newLearningCount > 0
}

data class AiRequestKey(
    val quizId: Int,
    val type: AiExplanationType,
    val subKey: String? = null
)

sealed class AiExplanationUiState {
    data object Idle : AiExplanationUiState()
    data object Loading : AiExplanationUiState()
    data object ConfigurationRequired : AiExplanationUiState()
    data class Streaming(val content: String) : AiExplanationUiState()
    data class Success(
        val content: String,
        val fromCache: Boolean
    ) : AiExplanationUiState()
    data class Error(
        val message: String,
        val partialContent: String = ""
    ) : AiExplanationUiState()
}

internal fun AiExplanationUiState?.isAiRequestInProgress(): Boolean {
    return this is AiExplanationUiState.Loading || this is AiExplanationUiState.Streaming
}

internal fun startOfDayMillis(now: Long = System.currentTimeMillis()): Long {
    return Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

internal fun buildAnswerStats(
    records: List<QuizAnswerRecord>,
    todayStart: Long = startOfDayMillis()
): LibraryAnswerStats {
    val todayRecords = records.filter { it.answeredAt >= todayStart }
    val totalAnswered = records.size
    val totalCorrect = records.count { it.isCorrect }
    return LibraryAnswerStats(
        todayAnswered = todayRecords.size,
        totalAnswered = totalAnswered,
        todayWrong = todayRecords.count { it.isCorrect.not() },
        totalWrong = records.count { it.isCorrect.not() },
        accuracyPercent = if (totalAnswered == 0) {
            null
        } else {
            ((totalCorrect * 100.0) / totalAnswered).roundToInt()
        }
    )
}

internal fun buildReviewStats(
    cards: List<ReviewCard>,
    now: Long = System.currentTimeMillis(),
    todayStart: Long = startOfDayMillis(now)
): ReviewStats {
    return ReviewStats(
        dueToday = cards.count { it.dueAt <= now },
        reviewedToday = cards.count { card -> card.lastReviewedAt?.let { it >= todayStart } == true },
        totalCards = cards.size,
        totalLapses = cards.sumOf { it.lapseCount }
    )
}

internal fun buildReviewEntryState(
    quizzes: List<Quiz>,
    reviewQuizIds: List<Int>,
    reviewStats: ReviewStats,
    newCardLimit: Int
): ReviewEntryState {
    val existingReviewQuizIds = reviewQuizIds.toSet()
    val availableNewLearningCount = if (newCardLimit <= 0) {
        0
    } else {
        quizzes.count { quiz ->
            quiz.isSupportedStudyType() && quiz.id !in existingReviewQuizIds
        }.coerceAtMost(newCardLimit)
    }
    return ReviewEntryState(
        dueReviewCount = reviewStats.dueToday,
        newLearningCount = availableNewLearningCount
    )
}

internal fun shouldAutoRequestQuickReview(
    answerShown: Boolean,
    isCorrect: Boolean,
    isFavorite: Boolean
): Boolean {
    return answerShown && (!isCorrect || isFavorite)
}

internal fun buildActiveWrongQuizIds(records: List<QuizAnswerRecord>): Set<Int> {
    val states = linkedMapOf<Int, WrongQuizState>()
    records.sortedWith(compareBy<QuizAnswerRecord> { it.answeredAt }.thenBy { it.id })
        .forEach { record ->
            val state = states.getOrPut(record.quizId) { WrongQuizState() }
            if (record.isCorrect) {
                if (state.isWrong) {
                    state.correctStreak++
                    if (state.correctStreak >= WRONG_CLEAR_CORRECT_STREAK) {
                        state.isWrong = false
                        state.correctStreak = 0
                    }
                }
            } else {
                state.isWrong = true
                state.correctStreak = 0
            }
        }
    return states.filterValues { it.isWrong }.keys
}

private data class WrongQuizState(
    var isWrong: Boolean = false,
    var correctStreak: Int = 0
)

private const val WRONG_CLEAR_CORRECT_STREAK = 3

fun parseContextualSuggestions(content: String): List<String> {
    return content.lines()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .map { it.replaceFirst("^[0-9]+\\.[ \\t]*".toRegex(), "").trim() }
        .filter { it.isNotBlank() }
        .take(3)
}

data class AiExplanationProgress(
    val total: Int = 0,
    val cached: Int = 0,
    val isGenerating: Boolean = false
) {
    val progressPercent: Int
        get() = if (total > 0) (cached * 100 / total) else 0
    val description: String
        get() = if (isGenerating) {
            "生成中 $cached/$total"
        } else if (cached > 0) {
            "已缓存 $cached/$total"
        } else {
            "批量为题库生成快速复盘解析"
        }
}

class QuizLibraryFeaturesViewModel(application: Application, private val libraryId: Int) :
    AndroidViewModel(application) {

    private val repository: QuizRepository = QuizRepositoryImpl(application)
    private val newReviewCardLimit = QuizStudySettings.readNewReviewCardsPerSession(application)
    private var latestQuizList: List<Quiz> = emptyList()
    private var latestReviewQuizIds: List<Int> = emptyList()
    private var latestReviewStats: ReviewStats = ReviewStats()

    val library: MutableLiveData<QuizLibrary?> = MutableLiveData()
    val quizList: LiveData<List<Quiz>> = repository.getQuizListByLibraryId(libraryId)
    val answerRecords: LiveData<List<QuizAnswerRecord>> =
        repository.getAnswerRecordsByLibraryId(libraryId)
    val dueReviewCount: LiveData<Int> = repository.getDueReviewCardCount(libraryId)
    val reviewStats: LiveData<ReviewStats> = repository.getReviewStatsByLibraryId(libraryId)
    val reviewQuizIds: LiveData<List<Int>> = repository.getReviewQuizIdsByLibraryId(libraryId)
    val stats: LiveData<SupportedQuizStats> = MediatorLiveData<SupportedQuizStats>().apply {
        addSource(quizList) { quizzes ->
            value = buildSupportedStats(quizzes.orEmpty())
        }
    }
    val answerStats: LiveData<LibraryAnswerStats> = MediatorLiveData<LibraryAnswerStats>().apply {
        addSource(answerRecords) { records ->
            value = buildAnswerStats(records.orEmpty())
        }
    }
    val reviewEntryState: LiveData<ReviewEntryState> = MediatorLiveData<ReviewEntryState>().apply {
        value = buildReviewEntryState(
            quizzes = latestQuizList,
            reviewQuizIds = latestReviewQuizIds,
            reviewStats = latestReviewStats,
            newCardLimit = newReviewCardLimit
        )
        addSource(quizList) { quizzes ->
            latestQuizList = quizzes.orEmpty()
            value = buildCurrentReviewEntryState()
        }
        addSource(reviewQuizIds) { ids ->
            latestReviewQuizIds = ids.orEmpty()
            value = buildCurrentReviewEntryState()
        }
        addSource(reviewStats) { stats ->
            latestReviewStats = stats ?: ReviewStats()
            value = buildCurrentReviewEntryState()
        }
    }

    val aiExplanationProgress: LiveData<AiExplanationProgress> = 
        MediatorLiveData<AiExplanationProgress>().apply {
            addSource(quizList) { quizzes ->
                val total = quizzes?.size ?: 0
                value = AiExplanationProgress(total = total, cached = 0, isGenerating = false)
                viewModelScope.launch {
                    val cached = repository.countByLibraryAndType(libraryId, AiExplanationType.QUICK_REVIEW.value)
                    value = AiExplanationProgress(total = total, cached = cached, isGenerating = false)
                }
            }
        }
    
    private var isGeneratingAiExplanation = false
    
    fun setAiExplanationGenerating(generating: Boolean) {
        isGeneratingAiExplanation = generating
        val current = aiExplanationProgress.value ?: AiExplanationProgress()
        (aiExplanationProgress as MutableLiveData).value = current.copy(isGenerating = generating)
    }
    
    fun refreshAiExplanationProgress() {
        viewModelScope.launch {
            val total = quizList.value?.size ?: 0
            val cached = repository.countByLibraryAndType(libraryId, AiExplanationType.QUICK_REVIEW.value)
            (aiExplanationProgress as MutableLiveData).value = 
                AiExplanationProgress(total = total, cached = cached, isGenerating = isGeneratingAiExplanation)
        }
    }

    init {
        viewModelScope.launch {
            library.value = repository.getQuizLibraryById(libraryId)
        }
    }

    fun updateQuizLibrary(quizLibrary: QuizLibrary) {
        viewModelScope.launch {
            repository.updateQuizLibrary(quizLibrary)
            library.value = repository.getQuizLibraryById(quizLibrary.id)
        }
    }

    fun deleteQuizLibrary(quizLibrary: QuizLibrary) {
        viewModelScope.launch {
            repository.deleteQuizLibrary(quizLibrary)
        }
    }

    suspend fun buildReviewQuizList(): List<Int> {
        val limit = QuizStudySettings.readNewReviewCardsPerSession(getApplication())
        return repository.buildReviewQuizList(libraryId, limit)
    }

    private fun buildCurrentReviewEntryState(): ReviewEntryState {
        return buildReviewEntryState(
            quizzes = latestQuizList,
            reviewQuizIds = latestReviewQuizIds,
            reviewStats = latestReviewStats,
            newCardLimit = newReviewCardLimit
        )
    }

    private fun buildSupportedStats(quizzes: List<Quiz>): SupportedQuizStats {
        var single = 0
        var multiple = 0
        var judgement = 0
        quizzes.forEach { quiz ->
            when (quiz.inferredUiType()) {
                QuizUiType.SINGLE_CHOICE -> single++
                QuizUiType.MULTIPLE_CHOICE -> multiple++
                QuizUiType.JUDGEMENT -> judgement++
                QuizUiType.FILL_BLANK,
                QuizUiType.SUBJECTIVE -> Unit
            }
        }
        return SupportedQuizStats(
            total = single + multiple + judgement,
            singleChoice = single,
            multipleChoice = multiple,
            judgement = judgement
        )
    }

    companion object {
        fun factory(application: Application, libraryId: Int): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(QuizLibraryFeaturesViewModel::class.java)) {
                        return requireNotNull(
                            modelClass.cast(QuizLibraryFeaturesViewModel(application, libraryId))
                        )
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
        }
    }
}

class QuizRunnerViewModel(application: Application, private val libraryId: Int) :
    AndroidViewModel(application) {

    private val repository: QuizRepository = QuizRepositoryImpl(application)
    private val aiRepository = AiExplanationRepository(application)
    private val aiConfigStore = AiConfigStore(application)
    private val aiJobs = ConcurrentHashMap<AiRequestKey, Job>()
    private val aiStateLock = Any()
    private val aiStateMap = mutableMapOf<AiRequestKey, AiExplanationUiState>()
    private val practiceReviewBaselines = ConcurrentHashMap<Int, ReviewCard>()
    private val practiceReviewSchedulingQuizIds = ConcurrentHashMap.newKeySet<Int>()
    private val pendingPracticeReviewRatings = ConcurrentHashMap<Int, ReviewRating>()
    private val practiceReviewRatingMap = ConcurrentHashMap<Int, ReviewRating>()
    private val _aiStates =
        MutableLiveData<Map<AiRequestKey, AiExplanationUiState>>(emptyMap())
    private val _practiceReviewRatings =
        MutableLiveData<Map<Int, ReviewRating>>(emptyMap())
    private val similarQuizCache = ConcurrentHashMap<Int, List<Quiz>>()
    private val reviewCardCache = ConcurrentHashMap<Int, ReviewCard>()

    val quizList: LiveData<List<Quiz>> = repository.getQuizListByLibraryId(libraryId)
    val favoriteQuizIds: LiveData<List<Int>> = repository.getFavoriteQuizIdsByLibraryId(libraryId)
    val answerRecords: LiveData<List<QuizAnswerRecord>> = repository.getAnswerRecordsByLibraryId(libraryId)
    val examSessionId: MutableLiveData<Int?> = MutableLiveData(null)
    val aiStates: LiveData<Map<AiRequestKey, AiExplanationUiState>> = _aiStates
    val practiceReviewRatings: LiveData<Map<Int, ReviewRating>> = _practiceReviewRatings
    var examStartedAt: Long? = null
        private set

    fun toggleFavorite(quiz: Quiz, isFavorite: Boolean) {
        viewModelScope.launch {
            repository.setQuizFavorite(quiz, isFavorite)
        }
    }

    fun requestAiExplanation(
        quiz: Quiz,
        type: AiExplanationType,
        selectedAnswer: Set<Int>?,
        forceRefresh: Boolean = false
    ) {
        requestAiExplanationInternal(
            quiz = quiz,
            type = type,
            selectedAnswer = selectedAnswer,
            forceRefresh = forceRefresh,
            subKey = null
        )
    }

    fun clearAiUiStates() {
        aiJobs.values.forEach { it.cancel() }
        aiJobs.clear()
        synchronized(aiStateLock) {
            aiStateMap.clear()
        }
        _aiStates.value = emptyMap()
    }

    fun requestContextualSuggestions(
        quiz: Quiz,
        selectedAnswer: Set<Int>?
    ) {
        requestAiExplanationInternal(
            quiz = quiz,
            type = AiExplanationType.CONTEXTUAL_SUGGESTIONS,
            selectedAnswer = selectedAnswer,
            forceRefresh = false,
            subKey = null
        )
    }

    fun requestContextualQa(
        quiz: Quiz,
        suggestionIndex: Int,
        suggestionText: String,
        selectedAnswer: Set<Int>?
    ) {
        val key = AiRequestKey(quiz.id, AiExplanationType.CONTEXTUAL_QA, "$suggestionIndex")
        val config = aiConfigStore.read()
        if (!config.isComplete()) {
            updateAiState(key, AiExplanationUiState.ConfigurationRequired)
            return
        }
        if (aiJobs[key]?.isActive == true) return
        aiJobs.remove(key)?.cancel()
        updateAiState(key, AiExplanationUiState.Loading)
        aiJobs[key] = viewModelScope.launch(Dispatchers.IO) {
            var latestPartialContent = ""
            val prompt = AiPromptBuilder.build(
                quiz = quiz,
                type = AiExplanationType.CONTEXTUAL_QA,
                taskPrompt = config.promptFor(AiExplanationType.CONTEXTUAL_QA) +
                    "\n\n用户选择的学习建议：${suggestionText}",
                selectedAnswer = selectedAnswer
            )
            val result = runCatching {
                aiRepository.getOrGenerate(
                    quizId = quiz.id,
                    libraryId = quiz.libraryId,
                    type = AiExplanationType.CONTEXTUAL_QA,
                    config = config,
                    prompt = prompt,
                    forceRefresh = false,
                    onPartialContent = { content ->
                        latestPartialContent = content
                        updateAiState(key, AiExplanationUiState.Streaming(content))
                    }
                )
            }
            result.onSuccess {
                updateAiState(key, AiExplanationUiState.Success(it.content, it.fromCache))
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) return@onFailure
                updateAiState(
                    key,
                    AiExplanationUiState.Error(
                        message = it.message ?: "AI 请求失败",
                        partialContent = latestPartialContent
                    )
                )
            }
            aiJobs.remove(key)
        }
    }

    fun clearContextualStates(quizId: Int) {
        synchronized(aiStateLock) {
            val keysToRemove = aiStateMap.keys.filter {
                it.quizId == quizId && it.type in setOf(
                    AiExplanationType.CONTEXTUAL_SUGGESTIONS,
                    AiExplanationType.CONTEXTUAL_QA
                )
            }
            keysToRemove.forEach { key ->
                aiJobs.remove(key)?.cancel()
                aiStateMap.remove(key)
            }
        }
        _aiStates.value = synchronized(aiStateLock) { aiStateMap.toMap() }
    }

    private fun requestAiExplanationInternal(
        quiz: Quiz,
        type: AiExplanationType,
        selectedAnswer: Set<Int>?,
        forceRefresh: Boolean,
        subKey: String?
    ) {
        val key = AiRequestKey(quiz.id, type, subKey)
        val config = aiConfigStore.read()
        if (!config.isComplete()) {
            updateAiState(key, AiExplanationUiState.ConfigurationRequired)
            return
        }
        if (aiJobs[key]?.isActive == true && !forceRefresh) return
        aiJobs.remove(key)?.cancel()
        updateAiState(key, AiExplanationUiState.Loading)
        aiJobs[key] = viewModelScope.launch(Dispatchers.IO) {
            var latestPartialContent = ""
            val prompt = AiPromptBuilder.build(
                quiz = quiz,
                type = type,
                taskPrompt = config.promptFor(type),
                selectedAnswer = selectedAnswer
            )
            val result = runCatching {
                aiRepository.getOrGenerate(
                    quizId = quiz.id,
                    libraryId = quiz.libraryId,
                    type = type,
                    config = config,
                    prompt = prompt,
                    forceRefresh = forceRefresh,
                    onPartialContent = { content ->
                        latestPartialContent = content
                        updateAiState(key, AiExplanationUiState.Streaming(content))
                    }
                )
            }
            result.onSuccess {
                updateAiState(key, AiExplanationUiState.Success(it.content, it.fromCache))
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) return@onFailure
                updateAiState(
                    key,
                    AiExplanationUiState.Error(
                        message = it.message ?: "AI 请求失败",
                        partialContent = latestPartialContent
                    )
                )
            }
            aiJobs.remove(key)
        }
    }

    private fun updateAiState(key: AiRequestKey, state: AiExplanationUiState) {
        val snapshot = synchronized(aiStateLock) {
            aiStateMap[key] = state
            aiStateMap.toMap()
        }
        _aiStates.postValue(snapshot)
    }

    fun recordPracticeAnswer(
        quiz: Quiz,
        selectedAnswer: Set<Int>,
        isCorrect: Boolean,
        mode: QuizStudyMode
    ) {
        viewModelScope.launch {
            repository.insertAnswerRecord(
                QuizAnswerRecord(
                    quizId = quiz.id,
                    libraryId = quiz.libraryId,
                    mode = mode.value,
                    selectedAnswer = selectedAnswer,
                    isCorrect = isCorrect
                )
            )
        }
    }

    fun scheduleReview(
        quizId: Int,
        rating: ReviewRating,
        onScheduled: () -> Unit = {}
    ) {
        viewModelScope.launch {
            repository.scheduleReview(quizId, libraryId, rating)
            onScheduled()
        }
    }

    fun schedulePracticeReviewRating(quizId: Int, rating: ReviewRating) {
        updatePracticeReviewRating(quizId, rating)
        val baseline = practiceReviewBaselines[quizId]
        if (baseline == null && quizId in practiceReviewSchedulingQuizIds) {
            pendingPracticeReviewRatings[quizId] = rating
            return
        }
        practiceReviewSchedulingQuizIds.add(quizId)
        viewModelScope.launch {
            val result = repository.scheduleReviewFromBaseline(
                quizId = quizId,
                libraryId = libraryId,
                rating = rating,
                baseline = baseline
            )
            practiceReviewBaselines.putIfAbsent(quizId, result.baseline)
            practiceReviewSchedulingQuizIds.remove(quizId)
            val pending = pendingPracticeReviewRatings.remove(quizId)
            if (pending != null && pending != rating) {
                schedulePracticeReviewRating(quizId, pending)
            } else {
                updatePracticeReviewRating(quizId, rating)
            }
        }
    }

    private fun updatePracticeReviewRating(quizId: Int, rating: ReviewRating) {
        practiceReviewRatingMap[quizId] = rating
        _practiceReviewRatings.value = practiceReviewRatingMap.toMap()
    }

    fun ensureExamSession(totalCount: Int) {
        if (examSessionId.value != null) return
        viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            examStartedAt = startedAt
            val id = repository.insertExamSession(
                ExamSession(
                    libraryId = libraryId,
                    startedAt = startedAt,
                    totalCount = totalCount
                )
            ).toInt()
            examSessionId.value = id
        }
    }

    fun restoreExamSession(sessionId: Int, startedAt: Long?) {
        if (examSessionId.value == null) {
            examSessionId.value = sessionId
        }
        if (examStartedAt == null) {
            examStartedAt = startedAt
        }
    }

    fun finishExam(quizzes: List<Quiz>, answers: Map<Int, Set<Int>>, sessionId: Int) {
        viewModelScope.launch {
            val records = quizzes.map { quiz ->
                val selected = answers[quiz.id].orEmpty()
                QuizAnswerRecord(
                    quizId = quiz.id,
                    libraryId = quiz.libraryId,
                    mode = QuizStudyMode.EXAM.value,
                    selectedAnswer = selected,
                    isCorrect = quiz.isCorrectAnswer(selected),
                    examSessionId = sessionId
                )
            }
            repository.insertAnswerRecords(records)
            repository.updateExamSession(
                ExamSession(
                    id = sessionId,
                    libraryId = libraryId,
                    startedAt = examStartedAt ?: System.currentTimeMillis(),
                    endedAt = System.currentTimeMillis(),
                    totalCount = records.size,
                    correctCount = records.count { it.isCorrect },
                    isCompleted = true
                )
            )
        }
    }

    fun loadPracticeSession(mode: QuizStudyMode, onLoaded: (PracticeSession?) -> Unit) {
        viewModelScope.launch {
            onLoaded(repository.getPracticeSession(libraryId, mode.value))
        }
    }

    suspend fun getPracticeSession(mode: QuizStudyMode): PracticeSession? {
        return repository.getPracticeSession(libraryId, mode.value)
    }

    fun savePracticeSession(session: PracticeSession) {
        viewModelScope.launch {
            repository.upsertPracticeSession(session)
        }
    }

    fun resetPracticeSession(mode: QuizStudyMode, onDone: () -> Unit) {
        viewModelScope.launch {
            repository.deletePracticeSession(libraryId, mode.value)
            onDone()
        }
    }

    fun findSimilarQuizzes(currentQuiz: Quiz): List<Quiz> {
        return similarQuizCache.getOrPut(currentQuiz.id) {
            com.virin.visionquiz.util.findSimilarQuizzes(
                currentQuiz = currentQuiz,
                candidates = quizList.value.orEmpty()
            )
        }
    }

    suspend fun getQuizListByIds(ids: List<Int>): List<Quiz> {
        return repository.getQuizListByIds(ids)
    }

    suspend fun loadReviewCardsForQuizIds(quizIds: List<Int>) {
        quizIds.forEach { quizId ->
            val card = repository.getReviewCardByQuizId(quizId)
            if (card != null) {
                reviewCardCache[quizId] = card
            }
        }
    }

    fun getReviewCardForQuiz(quizId: Int): ReviewCard? {
        return reviewCardCache[quizId]
    }

    companion object {
        fun factory(application: Application, libraryId: Int): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(QuizRunnerViewModel::class.java)) {
                        return requireNotNull(
                            modelClass.cast(QuizRunnerViewModel(application, libraryId))
                        )
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
        }
    }
}

class ExamConfigViewModel(application: Application, private val libraryId: Int) :
    AndroidViewModel(application) {

    private val repository: QuizRepository = QuizRepositoryImpl(application)
    private val selectedSource = MutableLiveData(ExamQuestionSource.ALL)
    private var allQuizzes: List<Quiz> = emptyList()
    private var favoriteIds: Set<Int> = emptySet()
    private var answerRecords: List<QuizAnswerRecord> = emptyList()
    private val _examQuizzes = MediatorLiveData<List<Quiz>>()

    val quizList: LiveData<List<Quiz>> = repository.getQuizListByLibraryId(libraryId)
    val favoriteQuizIds: LiveData<List<Int>> = repository.getFavoriteQuizIdsByLibraryId(libraryId)
    val answerRecordList: LiveData<List<QuizAnswerRecord>> =
        repository.getAnswerRecordsByLibraryId(libraryId)
    val examSource: LiveData<ExamQuestionSource> = selectedSource
    val examQuizzes: LiveData<List<Quiz>> = _examQuizzes
    val stats: LiveData<SupportedQuizStats> = MediatorLiveData<SupportedQuizStats>().apply {
        addSource(_examQuizzes) { quizzes ->
            value = buildSupportedStats(quizzes.orEmpty())
        }
    }

    init {
        _examQuizzes.addSource(quizList) { quizzes ->
            allQuizzes = quizzes.orEmpty()
            refreshExamQuizzes()
        }
        _examQuizzes.addSource(favoriteQuizIds) { ids ->
            favoriteIds = ids.orEmpty().toSet()
            refreshExamQuizzes()
        }
        _examQuizzes.addSource(answerRecordList) { records ->
            answerRecords = records.orEmpty()
            refreshExamQuizzes()
        }
        _examQuizzes.addSource(selectedSource) {
            refreshExamQuizzes()
        }
    }

    fun setExamSource(source: ExamQuestionSource) {
        if (selectedSource.value != source) {
            selectedSource.value = source
        }
    }

    private fun refreshExamQuizzes() {
        val supported = allQuizzes.filter { it.isSupportedStudyType() }
        val selected = when (selectedSource.value ?: ExamQuestionSource.ALL) {
            ExamQuestionSource.ALL -> supported
            ExamQuestionSource.FAVORITES -> supported.filter { it.id in favoriteIds }
            ExamQuestionSource.WRONG -> {
                val wrongIds = buildActiveWrongQuizIds(answerRecords)
                supported.filter { it.id in wrongIds }
            }
        }
        _examQuizzes.value = selected
    }

    private fun buildSupportedStats(quizzes: List<Quiz>): SupportedQuizStats {
        return SupportedQuizStats(
            total = quizzes.size,
            singleChoice = quizzes.count { it.inferredUiType() == QuizUiType.SINGLE_CHOICE },
            multipleChoice = quizzes.count { it.inferredUiType() == QuizUiType.MULTIPLE_CHOICE },
            judgement = quizzes.count { it.inferredUiType() == QuizUiType.JUDGEMENT }
        )
    }

    companion object {
        fun factory(application: Application, libraryId: Int): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(ExamConfigViewModel::class.java)) {
                        return requireNotNull(
                            modelClass.cast(ExamConfigViewModel(application, libraryId))
                        )
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
        }
    }
}

class QuizCollectionViewModel(application: Application, private val libraryId: Int) :
    AndroidViewModel(application) {

    private val repository: QuizRepository = QuizRepositoryImpl(application)
    val quizList: LiveData<List<Quiz>> = repository.getQuizListByLibraryId(libraryId)
    val favoriteQuizIds: LiveData<List<Int>> = repository.getFavoriteQuizIdsByLibraryId(libraryId)
    val answerRecords: LiveData<List<QuizAnswerRecord>> = repository.getAnswerRecordsByLibraryId(libraryId)

    fun deleteHistoryByRange(startTime: Long, endTime: Long) {
        viewModelScope.launch {
            repository.deleteHistoryByRange(libraryId, startTime, endTime)
        }
    }

    companion object {
        fun factory(application: Application, libraryId: Int): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(QuizCollectionViewModel::class.java)) {
                        return requireNotNull(
                            modelClass.cast(QuizCollectionViewModel(application, libraryId))
                        )
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
        }
    }
}

class ExamHistoryViewModel(application: Application, private val libraryId: Int) :
    AndroidViewModel(application) {

    private val repository: QuizRepository = QuizRepositoryImpl(application)
    val examSessions: LiveData<List<ExamSession>> =
        repository.getCompletedExamSessionsByLibraryId(libraryId)

    companion object {
        fun factory(application: Application, libraryId: Int): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(ExamHistoryViewModel::class.java)) {
                        return requireNotNull(
                            modelClass.cast(ExamHistoryViewModel(application, libraryId))
                        )
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
        }
    }
}

class ExamDetailViewModel(
    application: Application,
    private val libraryId: Int,
    sessionId: Int
) : AndroidViewModel(application) {

    private val repository: QuizRepository = QuizRepositoryImpl(application)
    val quizList: LiveData<List<Quiz>> = repository.getQuizListByLibraryId(libraryId)
    val examSession: LiveData<ExamSession?> = repository.getExamSessionById(sessionId)
    val answerRecords: LiveData<List<QuizAnswerRecord>> =
        repository.getAnswerRecordsByExamSessionId(sessionId)

    companion object {
        fun factory(
            application: Application,
            libraryId: Int,
            sessionId: Int
        ): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(ExamDetailViewModel::class.java)) {
                        return requireNotNull(
                            modelClass.cast(
                                ExamDetailViewModel(application, libraryId, sessionId)
                            )
                        )
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
        }
    }
}
