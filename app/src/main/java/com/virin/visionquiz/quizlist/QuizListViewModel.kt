package com.virin.visionquiz.quizlist

import android.app.Application
import androidx.lifecycle.*
import com.virin.visionquiz.dao.Quiz
import com.virin.visionquiz.dao.QuizUiType
import com.virin.visionquiz.dao.QuizLibrary
import com.virin.visionquiz.dao.QuizManager
import com.virin.visionquiz.dao.inferredUiType
import com.virin.visionquiz.quizlibrarylist.QuizRepository
import com.virin.visionquiz.quizlibrarylist.QuizRepositoryImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class QuizTypeStats(
    val total: Int = 0,
    val singleChoice: Int = 0,
    val multipleChoice: Int = 0,
    val judgement: Int = 0,
    val fillBlank: Int = 0,
    val subjective: Int = 0
)

class QuizListViewModel(application: Application, libraryId: Int) : AndroidViewModel(application) {

    private val repository: QuizRepository = QuizRepositoryImpl(application)
    private val searchQuery = MutableLiveData("")
    val currentSearchQuery: LiveData<String> = searchQuery
    private val searchMode = MutableLiveData(QuizSearchMode.KEYWORD)
    val currentSearchMode: LiveData<QuizSearchMode> = searchMode
    private val keywordScope = MutableLiveData(KeywordSearchScope())
    val currentKeywordScope: LiveData<KeywordSearchScope> = keywordScope
    val selectedTypeFilter: MutableLiveData<QuizUiType?> = MutableLiveData(null)
    private var filterJob: Job? = null
    private var cachedSearchSource: List<Quiz>? = null
    private var cachedSearchType: QuizUiType? = null
    private var cachedSearchIndex: QuizManager.QuizMatchIndex? = null

    val library: MutableLiveData<QuizLibrary?> = MutableLiveData()
    val quizList: LiveData<List<Quiz>> = repository.getQuizListByLibraryId(libraryId)
    val quizTypeStats: LiveData<QuizTypeStats> = MediatorLiveData<QuizTypeStats>().apply {
        addSource(quizList) { list ->
            value = buildQuizTypeStats(list ?: emptyList())
        }
    }

    val displayQuizList: LiveData<List<Quiz>> = MediatorLiveData<List<Quiz>>().apply {
        fun update() {
            value = applyTypeFilter(quizList.value ?: emptyList(), selectedTypeFilter.value)
        }

        addSource(quizList) { update() }
        addSource(selectedTypeFilter) { update() }
    }

    val filteredQuizList: LiveData<List<Quiz>?> = MediatorLiveData<List<Quiz>?>().apply {
        fun update() {
            filterJob?.cancel()
            val query = searchQuery.value.orEmpty()
            if (query.isBlank()) {
                value = null
                return
            }

            val quizzes = quizList.value ?: emptyList()
            val selectedType = selectedTypeFilter.value
            val mode = searchMode.value ?: QuizSearchMode.KEYWORD
            val scope = keywordScope.value ?: KeywordSearchScope()
            filterJob = viewModelScope.launch {
                delay(250)
                val filtered = withContext(Dispatchers.Default) {
                    when (mode) {
                        QuizSearchMode.FUZZY -> {
                            val searchIndex = getSearchIndex(quizzes, selectedType)
                            QuizManager.matchQuiz(
                                query,
                                searchIndex,
                                minScore = QuizManager.SEARCH_MIN_MATCH_SCORE,
                                maxResults = Int.MAX_VALUE
                            ).map { pair -> pair.first }
                        }

                        QuizSearchMode.KEYWORD -> {
                            applyTypeFilter(quizzes, selectedType)
                                .filter { quiz -> QuizKeywordSearch.matches(quiz, query, scope) }
                        }
                    }
                }
                value = filtered
            }
        }

        addSource(quizList) { update() }
        addSource(searchQuery) { update() }
        addSource(searchMode) { update() }
        addSource(keywordScope) { update() }
        addSource(selectedTypeFilter) { update() }
    }

    init {
        viewModelScope.launch {
            library.value = repository.getQuizLibraryById(libraryId)
        }
    }

//    val mergedQuizList: LiveData<List<Pair<Quiz, Double>>?> =
//        Transformations.switchMap(filteredQuizList) { filteredList ->
//            if (filteredList.isNullOrEmpty()) {
//                this.quizList.value
//            } else {
//                filteredList
//            }
//        }

    fun questionFilter(text: String) {
        searchQuery.value = text
    }

    fun setSearchMode(mode: QuizSearchMode) {
        searchMode.value = mode
    }

    fun setKeywordPromptScope(includePrompt: Boolean) {
        val current = keywordScope.value ?: KeywordSearchScope()
        keywordScope.value = current.copy(includePrompt = includePrompt)
    }

    fun setKeywordAnswerScope(includeAnswers: Boolean) {
        val current = keywordScope.value ?: KeywordSearchScope()
        keywordScope.value = current.copy(includeAnswers = includeAnswers)
    }

    fun toggleTypeFilter(type: QuizUiType?) {
        selectedTypeFilter.value = if (selectedTypeFilter.value == type) {
            null
        } else {
            type
        }
    }

    fun clearFilters() {
        selectedTypeFilter.value = null
        searchQuery.value = ""
        searchMode.value = QuizSearchMode.KEYWORD
        keywordScope.value = KeywordSearchScope()
    }

    private fun applyTypeFilter(quizzes: List<Quiz>, selectedType: QuizUiType?): List<Quiz> {
        if (selectedType == null) {
            return quizzes
        }
        return quizzes.filter { it.inferredUiType() == selectedType }
    }

    private fun getSearchIndex(
        quizzes: List<Quiz>,
        selectedType: QuizUiType?
    ): QuizManager.QuizMatchIndex {
        val index = cachedSearchIndex
        if (cachedSearchSource === quizzes && cachedSearchType == selectedType && index != null) {
            return index
        }

        return synchronized(this) {
            val lockedIndex = cachedSearchIndex
            if (cachedSearchSource === quizzes && cachedSearchType == selectedType && lockedIndex != null) {
                lockedIndex
            } else {
                val baseList = applyTypeFilter(quizzes, selectedType)
                QuizManager.buildMatchIndex(baseList).also {
                    cachedSearchSource = quizzes
                    cachedSearchType = selectedType
                    cachedSearchIndex = it
                }
            }
        }
    }

    fun updateQuizLibrary(quizLibrary: QuizLibrary) {
        viewModelScope.launch {
            repository.updateQuizLibrary(quizLibrary)
            // update parameter...
            library.value = repository.getQuizLibraryById(quizLibrary.id)
        }
    }

    fun deleteQuizLibrary(quizLibrary: QuizLibrary) {
        viewModelScope.launch {
            repository.deleteQuizLibrary(quizLibrary)
        }
    }

    private fun buildQuizTypeStats(quizzes: List<Quiz>): QuizTypeStats {
        if (quizzes.isEmpty()) return QuizTypeStats()

        var singleChoice = 0
        var multipleChoice = 0
        var judgement = 0
        var fillBlank = 0
        var subjective = 0

        quizzes.forEach { quiz ->
            when (quiz.inferredUiType()) {
                QuizUiType.SINGLE_CHOICE -> singleChoice++
                QuizUiType.MULTIPLE_CHOICE -> multipleChoice++
                QuizUiType.JUDGEMENT -> judgement++
                QuizUiType.FILL_BLANK -> fillBlank++
                QuizUiType.SUBJECTIVE -> subjective++
            }
        }

        return QuizTypeStats(
            total = quizzes.size,
            singleChoice = singleChoice,
            multipleChoice = multipleChoice,
            judgement = judgement,
            fillBlank = fillBlank,
            subjective = subjective
        )
    }

    companion object {
        fun factory(application: Application, libraryId: Int): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(QuizListViewModel::class.java)) {
                        return requireNotNull(
                            modelClass.cast(QuizListViewModel(application, libraryId))
                        )
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
        }
    }
}
