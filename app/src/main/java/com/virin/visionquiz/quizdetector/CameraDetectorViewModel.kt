package com.virin.visionquiz.quizdetector

import android.app.Activity
import android.app.Application
import androidx.lifecycle.*
import com.virin.visionquiz.dao.Quiz
import com.virin.visionquiz.dao.QuizLibrary
import com.virin.visionquiz.quizlibrarylist.QuizRepository
import com.virin.visionquiz.quizlibrarylist.QuizRepositoryImpl
import kotlinx.coroutines.launch

class CameraDetectorViewModel(application: Application, libraryId: Int) :
    AndroidViewModel(application) {

    private val repository: QuizRepository = QuizRepositoryImpl(application)

    val library: MutableLiveData<QuizLibrary?> = MutableLiveData()
    val quizList: LiveData<List<Quiz>> = repository.getQuizListByLibraryId(libraryId)

    init {
        viewModelScope.launch {
            library.value = repository.getQuizLibraryById(libraryId)
        }
    }

    companion object {
        fun factory(application: Application, libraryId: Int): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(CameraDetectorViewModel::class.java)) {
                        return requireNotNull(
                            modelClass.cast(CameraDetectorViewModel(application, libraryId))
                        )
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
        }

        fun provider(activity: Activity, libraryId: Int): CameraDetectorViewModel {
            return ViewModelProvider(
                activity as ViewModelStoreOwner, CameraDetectorViewModel.factory(activity.application, libraryId)
            )[CameraDetectorViewModel::class.java]
        }

        fun provider(owner: ViewModelStoreOwner, application: Application, libraryId: Int): CameraDetectorViewModel {
            return ViewModelProvider(
                owner, CameraDetectorViewModel.factory(application, libraryId)
            )[CameraDetectorViewModel::class.java]
        }
    }
}
