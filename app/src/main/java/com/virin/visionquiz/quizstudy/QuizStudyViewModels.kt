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

data class AiRequestKey(
    val quizId: Int,
    val type: AiExplanationType
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

class QuizLibraryFeaturesViewModel(application: Application, private val libraryId: Int) :
    AndroidViewModel(application) {

    private val repository: QuizRepository = QuizRepositoryImpl(application)

    val library: MutableLiveData<QuizLibrary?> = MutableLiveData()
    val quizList: LiveData<List<Quiz>> = repository.getQuizListByLibraryId(libraryId)
    val answerRecords: LiveData<List<QuizAnswerRecord>> =
        repository.getAnswerRecordsByLibraryId(libraryId)
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

    private fun buildAnswerStats(records: List<QuizAnswerRecord>): LibraryAnswerStats {
        val todayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
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
    private val _aiStates =
        MutableLiveData<Map<AiRequestKey, AiExplanationUiState>>(emptyMap())

    val quizList: LiveData<List<Quiz>> = repository.getQuizListByLibraryId(libraryId)
    val favoriteQuizIds: LiveData<List<Int>> = repository.getFavoriteQuizIdsByLibraryId(libraryId)
    val answerRecords: LiveData<List<QuizAnswerRecord>> = repository.getAnswerRecordsByLibraryId(libraryId)
    val examSessionId: MutableLiveData<Int?> = MutableLiveData(null)
    val aiStates: LiveData<Map<AiRequestKey, AiExplanationUiState>> = _aiStates
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
        val key = AiRequestKey(quiz.id, type)
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
                updateAiState(
                    key,
                    AiExplanationUiState.Success(it.content, it.fromCache)
                )
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

    fun clearAiUiStates() {
        aiJobs.values.forEach { it.cancel() }
        aiJobs.clear()
        synchronized(aiStateLock) {
            aiStateMap.clear()
        }
        _aiStates.value = emptyMap()
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
