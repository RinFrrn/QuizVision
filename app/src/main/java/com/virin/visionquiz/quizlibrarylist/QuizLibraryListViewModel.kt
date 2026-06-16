package com.virin.visionquiz.quizlibrarylist

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.virin.visionquiz.R
import com.virin.visionquiz.dao.Quiz
import com.virin.visionquiz.dao.QuizLibrary
import com.virin.visionquiz.util.await
import com.virin.visionquiz.util.showProgressAlertDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class QuizLibraryWithReviewCount(
    val library: QuizLibrary,
    val reviewCount: Int
)

class QuizLibraryListViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: QuizRepository = QuizRepositoryImpl(application)

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
                            currentList[index] = QuizLibraryWithReviewCount(library, count ?: 0)
                        } else {
                            currentList.add(QuizLibraryWithReviewCount(library, count ?: 0))
                        }
                        value = currentList
                    }
                }
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
