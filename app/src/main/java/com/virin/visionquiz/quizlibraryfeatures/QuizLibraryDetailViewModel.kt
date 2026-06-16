package com.virin.visionquiz.quizlibraryfeatures

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.virin.visionquiz.dao.QuizDatabase
import kotlinx.coroutines.launch

class QuizLibraryDetailViewModel(
    application: Application,
    private val libraryId: Int
) : AndroidViewModel(application) {

    private val cacheDao = QuizDatabase.getInstance(application).aiExplanationCacheDao()
    private val questionDao = QuizDatabase.getInstance(application).questionDao()

    private val _cachedQuickReviewCount = MutableLiveData(0)
    val cachedQuickReviewCount: LiveData<Int> = _cachedQuickReviewCount

    private val _totalQuizCount = MutableLiveData(0)
    val totalQuizCount: LiveData<Int> = _totalQuizCount

    fun refreshCacheStats() {
        viewModelScope.launch {
            val cached = cacheDao.countByLibraryAndType(libraryId, "quick_review")
            val total = questionDao.getQuizCountByLibraryId(libraryId)
            _cachedQuickReviewCount.postValue(cached)
            _totalQuizCount.postValue(total)
        }
    }

    companion object {
        fun factory(application: Application, libraryId: Int): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return QuizLibraryDetailViewModel(application, libraryId) as T
                }
            }
        }
    }
}
