package com.virin.visionquiz.util

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.virin.visionquiz.dao.Quiz
import com.virin.visionquiz.dao.isSupportedStudyType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Stores pre-computed similar quiz mappings per library.
 * Key: libraryId, Value: map of quizId -> list of similar quizIds (max 3).
 *
 * Also manages background analysis jobs and exposes per-library progress.
 */
object SimilarQuizStore {
    private const val PREFS_NAME = "similar_quiz_store"
    private const val MAX_RESULTS = 3

    data class Progress(val current: Int, val total: Int, val done: Boolean) {
        val text: String get() = if (done) "已完成" else "分析中 $current / $total"
        val percent: Int get() = if (total == 0) 0 else current * 100 / total
    }

    private val _progress = MutableLiveData<Map<Int, Progress>>(emptyMap())
    val progress: LiveData<Map<Int, Progress>> = _progress
    private val jobs = mutableMapOf<Int, Job>()
    private val scope = CoroutineScope(Dispatchers.IO)
    private val memoryCache = java.util.concurrent.ConcurrentHashMap<Int, Map<Int, List<Int>>>()

    fun getSimilarQuizIds(context: Context, libraryId: Int, quizId: Int): List<Int> {
        val map = memoryCache.getOrPut(libraryId) { readMap(context, libraryId) }
        return map[quizId].orEmpty()
    }

    fun hasAnalysis(context: Context, libraryId: Int): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .contains(key(libraryId))
    }

    fun isRunning(libraryId: Int): Boolean = jobs[libraryId]?.isActive == true

    /**
     * Start background analysis. Survives dialog/fragment dismissal.
     * If already running, does nothing.
     */
    fun startAnalysis(context: Context, libraryId: Int, quizzes: List<Quiz>) {
        if (isRunning(libraryId)) return
        val supported = quizzes.filter { it.isSupportedStudyType() }
        val total = supported.size
        updateProgress(libraryId, Progress(0, total, false))
        jobs[libraryId] = scope.launch {
            val result = mutableMapOf<Int, List<Int>>()
            supported.forEachIndexed { index, quiz ->
                val similar = findSimilarQuizzes(quiz, supported, MAX_RESULTS)
                result[quiz.id] = similar.map { it.id }
                if ((index + 1) % 10 == 0 || index + 1 == total) {
                    updateProgress(libraryId, Progress(index + 1, total, false))
                }
            }
            val json = Gson().toJson(result)
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(key(libraryId), json)
                .apply()
            memoryCache[libraryId] = result
            updateProgress(libraryId, Progress(total, total, true))
            jobs.remove(libraryId)
        }
    }

    fun clear(context: Context, libraryId: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(key(libraryId))
            .apply()
        memoryCache.remove(libraryId)
    }

    private fun updateProgress(libraryId: Int, progress: Progress) {
        val snapshot = _progress.value.orEmpty().toMutableMap()
        snapshot[libraryId] = progress
        _progress.postValue(snapshot)
    }

    private fun readMap(context: Context, libraryId: Int): Map<Int, List<Int>> {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(key(libraryId), null) ?: return emptyMap()
        return runCatching {
            val type = object : TypeToken<Map<Int, List<Int>>>() {}.type
            Gson().fromJson<Map<Int, List<Int>>>(json, type)
        }.getOrDefault(emptyMap())
    }

    private fun key(libraryId: Int) = "lib_${libraryId}"
}
