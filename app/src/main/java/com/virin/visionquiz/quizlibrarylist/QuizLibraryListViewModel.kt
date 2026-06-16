package com.virin.visionquiz.quizlibrarylist

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.virin.visionquiz.R
import com.virin.visionquiz.ai.AiExplanationType
import com.virin.visionquiz.dao.Quiz
import com.virin.visionquiz.dao.QuizLibrary
import com.virin.visionquiz.util.await
import com.virin.visionquiz.util.showProgressAlertDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class QuizLibraryWithReviewCount(
    val library: QuizLibrary,
    val reviewCount: Int,
    val aiExplanationProgress: AiExplanationProgress = AiExplanationProgress()
)

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
            ""
        }
}

class QuizLibraryListViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: QuizRepository = QuizRepositoryImpl(application)

    // Selection state
    private val _isSelectionMode = MutableLiveData(false)
    val isSelectionMode: LiveData<Boolean> = _isSelectionMode

    private val _selectedIds = MutableLiveData<Set<Int>>(emptySet())
    val selectedIds: LiveData<Set<Int>> = _selectedIds

    fun enterSelectionMode(libraryId: Int? = null) {
        _isSelectionMode.value = true
        _selectedIds.value = if (libraryId != null) setOf(libraryId) else emptySet()
    }

    fun toggleSelection(libraryId: Int) {
        val current = _selectedIds.value.orEmpty()
        if (libraryId in current) {
            val updated = current - libraryId
            if (updated.isEmpty()) {
                exitSelectionMode()
            } else {
                _selectedIds.value = updated
            }
        } else {
            _selectedIds.value = current + libraryId
        }
    }

    fun exitSelectionMode() {
        _isSelectionMode.value = false
        _selectedIds.value = emptySet()
    }

    fun getSelectedLibraries(): List<QuizLibrary> {
        val ids = _selectedIds.value.orEmpty()
        return quizLibraryList.value.orEmpty().filter { it.id in ids }
    }

    // Sort state
    data class SortConfig(val sortBy: String = "default", val ascending: Boolean = true)
    private val _sortConfig = MutableLiveData(SortConfig())
    fun setSortConfig(sortBy: String, ascending: Boolean) {
        _sortConfig.value = SortConfig(sortBy, ascending)
    }

    val quizLibraryList: LiveData<List<QuizLibrary>> = repository.getQuizLibraryList()
    
    val librariesWithReviewCount: LiveData<List<QuizLibraryWithReviewCount>> = 
        MediatorLiveData<List<QuizLibraryWithReviewCount>>().apply {
            addSource(quizLibraryList) { libraries ->
                value = libraries?.map { library ->
                    QuizLibraryWithReviewCount(library, 0)
                } ?: emptyList()
                libraries?.forEach { library ->
                    val reviewCountLiveData = repository.getDueReviewCardCount(library.id)
                    addSource(reviewCountLiveData) { count ->
                        val currentList = value.orEmpty().toMutableList()
                        val index = currentList.indexOfFirst { it.library.id == library.id }
                        if (index >= 0) {
                            currentList[index] = currentList[index].copy(reviewCount = count ?: 0)
                        } else {
                            currentList.add(QuizLibraryWithReviewCount(library, count ?: 0))
                        }
                        value = currentList
                    }
                    // Fetch AI explanation progress
                    viewModelScope.launch {
                        val total = repository.getQuizCountByLibraryId(library.id)
                        val cached = repository.countByLibraryAndType(library.id, AiExplanationType.QUICK_REVIEW.value)
                        val currentList = value.orEmpty().toMutableList()
                        val index = currentList.indexOfFirst { it.library.id == library.id }
                        val progress = AiExplanationProgress(total = total, cached = cached)
                        if (index >= 0) {
                            currentList[index] = currentList[index].copy(aiExplanationProgress = progress)
                        } else {
                            currentList.add(QuizLibraryWithReviewCount(library, 0, progress))
                        }
                        value = currentList
                    }
                }
            }
        }

    val sortedLibrariesWithReviewCount: LiveData<List<QuizLibraryWithReviewCount>> =
        MediatorLiveData<List<QuizLibraryWithReviewCount>>().apply {
            addSource(librariesWithReviewCount) { list -> value = applySort(list, _sortConfig.value ?: SortConfig()) }
            addSource(_sortConfig) { config -> value = applySort(librariesWithReviewCount.value.orEmpty(), config ?: SortConfig()) }
        }

    private fun applySort(list: List<QuizLibraryWithReviewCount>, config: SortConfig): List<QuizLibraryWithReviewCount> {
        val comparator: Comparator<QuizLibraryWithReviewCount> = when (config.sortBy) {
            "name" -> compareBy(nullsLast()) { it.library.name.lowercase(java.util.Locale.getDefault()) }
            "count" -> compareBy { it.library.quizCount }
            else -> compareBy { it.library.id }
        }
        return if (config.ascending) list.sortedWith(comparator) else list.sortedWith(comparator.reversed())
    }

    fun updateAiExplanationProgress(libraryId: Int, cached: Int, total: Int, isGenerating: Boolean) {
        val currentList = librariesWithReviewCount.value.orEmpty().toMutableList()
        val index = currentList.indexOfFirst { it.library.id == libraryId }
        if (index >= 0) {
            val progress = AiExplanationProgress(total = total, cached = cached, isGenerating = isGenerating)
            currentList[index] = currentList[index].copy(aiExplanationProgress = progress)
            (librariesWithReviewCount as MutableLiveData).value = currentList
        }
    }

    fun mergeQuizLibraries(context: Context, quizLibraryList: List<QuizLibrary>, newName: String) {
        viewModelScope.launch {
            val progressDialog =
                showProgressAlertDialog(context, "正在合并 ${quizLibraryList.size} 个题库")

            val uniqueName = generateUniqueLibraryName(newName)
            // 创建一个新的 QuizLibrary 条目，或者选择一个现有的作为合并后的目标
            var newLibrary = QuizLibrary(name = uniqueName, quizCount = 0)
            repository.insertQuizLibrary(newLibrary)

            newLibrary = repository.getQuizLibraryByName(uniqueName)
            val newLibraryId = newLibrary.id

            // 获取要合并的 QuizLibrary 条目列表
            val librariesToMerge = quizLibraryList

            val mergedQuizzes = mutableListOf<Quiz>()

            for (library in librariesToMerge) {
                // 查询与当前库相关联的 Quiz 条目
                val relatedQuizzes = repository.getQuizListByLibraryId(library.id).await()
                relatedQuizzes.map { quiz ->
                    // 复制 Quiz 条目并将 libraryId 更改为新的 QuizLibrary 的 id
                    quiz.copy(id = 0, libraryId = newLibraryId)
                }.apply {
                    mergedQuizzes.addAll(this)
                }
            }

            // 创建一个新数组来存储唯一的 Quiz 对象
            val uniqueQuizzes: MutableList<Quiz> = mutableListOf()

            for (quiz in mergedQuizzes) {
                // 检查当前的 Quiz 是否在新数组中已经存在
                val isDuplicate = uniqueQuizzes.any {
                    it.prompt == quiz.prompt && it.options == quiz.options
                }

                // 如果不是重复的 Quiz，将其添加到新数组
                if (!isDuplicate) {
                    uniqueQuizzes.add(quiz)
                }
            }

            repository.insertQuizzes(uniqueQuizzes)

            // 最后，更新新的 QuizLibrary 条目的 quizCount，以反映合并后的数量
            val mergedQuizCount = repository.getQuizCountByLibraryId(newLibraryId)
            repository.updateQuizLibrary(newLibrary.copy(quizCount = mergedQuizCount))

            delay(514) // 不然太快
            progressDialog.hide()

            val title = "成功合并 ${quizLibraryList.size} 个题库"
            val msg = "共导入 ${uniqueQuizzes.size} 题，删除重复项 ${mergedQuizzes.size - uniqueQuizzes.size} 个。"
            MaterialAlertDialogBuilder(context).setTitle(title)
                .setMessage(msg)
                .setPositiveButton(R.string.confirm) { dialog, which -> }.show()
        }
    }

    fun deleteQuizLibrary(quizLibrary: QuizLibrary) {
        viewModelScope.launch {
            repository.deleteQuizLibrary(quizLibrary)
        }
    }

    fun deleteQuizLibraries(quizLibraryList: List<QuizLibrary>) {
        viewModelScope.launch {
            quizLibraryList.reversed().forEach() { quizLibrary ->
                repository.deleteQuizLibrary(quizLibrary)
            }
        }
    }

    fun updateQuizLibrary(quizLibrary: QuizLibrary) {
        viewModelScope.launch {
            repository.updateQuizLibrary(quizLibrary)
        }
    }

    private suspend fun generateUniqueLibraryName(name: String): String {
        var newName = name
        var count = 1
        while (repository.getCategoryCountByName(newName) > 0) {
            newName = "$name (${count++})"
        }
        return newName
    }
}
