package com.virin.visionquiz.util

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.virin.visionquiz.dao.Quiz
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

internal const val SIMILAR_QUIZ_ALGORITHM_VERSION = 3

internal fun isCurrentSimilarAnalysis(hasStoredData: Boolean, storedVersion: Int): Boolean {
    return hasStoredData && storedVersion == SIMILAR_QUIZ_ALGORITHM_VERSION
}

internal data class SimilarQuizQueueState(
    val currentLibraryId: Int?,
    val queuedLibraryIds: List<Int>
)

internal fun enqueueSimilarQuizTask(
    state: SimilarQuizQueueState,
    libraryId: Int
): Pair<SimilarQuizQueueState, Boolean> {
    if (state.currentLibraryId == libraryId || libraryId in state.queuedLibraryIds) {
        return state to false
    }
    return state.copy(queuedLibraryIds = state.queuedLibraryIds + libraryId) to true
}

internal fun cancelQueuedSimilarQuizTask(
    state: SimilarQuizQueueState,
    libraryId: Int
): SimilarQuizQueueState {
    return state.copy(queuedLibraryIds = state.queuedLibraryIds - libraryId)
}

internal fun claimNextSimilarQuizTask(
    state: SimilarQuizQueueState
): Pair<SimilarQuizQueueState, Int?> {
    state.currentLibraryId?.let { return state to it }
    val next = state.queuedLibraryIds.firstOrNull() ?: return state to null
    return SimilarQuizQueueState(
        currentLibraryId = next,
        queuedLibraryIds = state.queuedLibraryIds.drop(1)
    ) to next
}

object SimilarQuizStore {
    private const val PREFS_NAME = "similar_quiz_store"
    private const val KEY_QUEUE = "analysis_queue"
    private const val KEY_CURRENT = "analysis_current"
    private const val KEY_PROGRESS = "analysis_progress"

    enum class Status {
        QUEUED,
        RUNNING,
        COMPLETED,
        CANCELLED,
        FAILED
    }

    data class Progress(
        val libraryId: Int,
        val current: Int = 0,
        val total: Int = 0,
        val status: Status = Status.QUEUED,
        val message: String? = null
    ) {
        val done: Boolean
            get() = status == Status.COMPLETED ||
                status == Status.CANCELLED ||
                status == Status.FAILED

        val text: String
            get() = when (status) {
                Status.QUEUED -> "等待分析"
                Status.RUNNING -> "分析中 $current / $total"
                Status.COMPLETED -> "已完成"
                Status.CANCELLED -> "已取消"
                Status.FAILED -> message ?: "分析失败"
            }

        val percent: Int
            get() = if (total <= 0) 0 else current * 100 / total
    }

    private data class PersistedProgress(
        val libraryId: Int,
        val current: Int,
        val total: Int,
        val status: String,
        val message: String?
    )

    private val gson = Gson()
    private val lock = Any()
    private val _progress = MutableLiveData<Map<Int, Progress>>(emptyMap())
    val progress: LiveData<Map<Int, Progress>> = _progress
    private val memoryCache = ConcurrentHashMap<Int, Map<Int, List<Int>>>()
    private var progressSnapshot: Map<Int, Progress> = emptyMap()
    private var lastProgressPersistAt = 0L
    @Volatile
    private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            progressSnapshot = restoreProgress(context)
            _progress.postValue(progressSnapshot)
            initialized = true
        }
    }

    fun enqueueAnalysis(context: Context, libraryId: Int): Boolean {
        initialize(context)
        val added = synchronized(lock) {
            val state = SimilarQuizQueueState(
                currentLibraryId = currentLibraryId(context).takeIf { it > 0 },
                queuedLibraryIds = readQueue(context)
            )
            val (updated, wasAdded) = enqueueSimilarQuizTask(state, libraryId)
            if (wasAdded) {
                writeQueueState(context, updated)
                updateProgressLocked(
                    context,
                    Progress(libraryId = libraryId, status = Status.QUEUED)
                )
            }
            wasAdded
        }
        startService(context)
        return added
    }

    fun cancelAnalysis(context: Context, libraryId: Int) {
        initialize(context)
        val isCurrent = synchronized(lock) {
            if (currentLibraryId(context) == libraryId) {
                true
            } else {
                val state = SimilarQuizQueueState(
                    currentLibraryId = null,
                    queuedLibraryIds = readQueue(context)
                )
                val updated = cancelQueuedSimilarQuizTask(state, libraryId)
                val removed = updated != state
                if (removed) {
                    writeQueue(context, updated.queuedLibraryIds)
                    updateProgressLocked(
                        context,
                        Progress(libraryId = libraryId, status = Status.CANCELLED)
                    )
                }
                false
            }
        }
        if (isCurrent) {
            ContextCompat.startForegroundService(
                context.applicationContext,
                SimilarQuizAnalysisService.cancelIntent(context, libraryId)
            )
        }
    }

    fun isPending(context: Context, libraryId: Int): Boolean {
        initialize(context)
        return synchronized(lock) {
            currentLibraryId(context) == libraryId || libraryId in readQueue(context)
        }
    }

    fun getSimilarQuizIds(context: Context, libraryId: Int, quizId: Int): List<Int> {
        if (!hasAnalysis(context, libraryId)) {
            memoryCache.remove(libraryId)
            return emptyList()
        }
        val map = memoryCache.getOrPut(libraryId) { readResultMap(context, libraryId) }
        return map[quizId].orEmpty()
    }

    fun hasAnalysis(context: Context, libraryId: Int): Boolean {
        val prefs = prefs(context)
        return isCurrentSimilarAnalysis(
            hasStoredData = prefs.contains(resultKey(libraryId)),
            storedVersion = prefs.getInt(versionKey(libraryId), 0)
        )
    }

    fun clear(context: Context, libraryId: Int) {
        prefs(context)
            .edit()
            .remove(resultKey(libraryId))
            .remove(versionKey(libraryId))
            .remove(fingerprintKey(libraryId))
            .apply()
        memoryCache.remove(libraryId)
    }

    internal fun claimNext(context: Context): Int? {
        initialize(context)
        return synchronized(lock) {
            val state = SimilarQuizQueueState(
                currentLibraryId = currentLibraryId(context).takeIf { it > 0 },
                queuedLibraryIds = readQueue(context)
            )
            val (updated, next) = claimNextSimilarQuizTask(state)
            if (next != null) {
                writeQueueState(context, updated)
                updateProgressLocked(
                    context,
                    Progress(libraryId = next, status = Status.RUNNING)
                )
            }
            next
        }
    }

    internal fun updateRunningProgress(
        context: Context,
        libraryId: Int,
        current: Int,
        total: Int
    ) {
        synchronized(lock) {
            if (currentLibraryId(context) != libraryId) return
            updateProgressLocked(
                context,
                Progress(
                    libraryId = libraryId,
                    current = current,
                    total = total,
                    status = Status.RUNNING
                )
            )
        }
    }

    internal fun completeAnalysis(
        context: Context,
        libraryId: Int,
        results: Map<Int, List<Int>>,
        fingerprint: String
    ) {
        synchronized(lock) {
            val json = gson.toJson(results)
            prefs(context).edit()
                .putString(resultKey(libraryId), json)
                .putInt(versionKey(libraryId), SIMILAR_QUIZ_ALGORITHM_VERSION)
                .putString(fingerprintKey(libraryId), fingerprint)
                .remove(KEY_CURRENT)
                .commit()
            memoryCache[libraryId] = results
            val total = progressSnapshot[libraryId]?.total ?: results.size
            updateProgressLocked(
                context,
                Progress(
                    libraryId = libraryId,
                    current = total,
                    total = total,
                    status = Status.COMPLETED
                )
            )
        }
    }

    internal fun finishWithoutResult(
        context: Context,
        libraryId: Int,
        status: Status,
        message: String? = null
    ) {
        require(status == Status.CANCELLED || status == Status.FAILED)
        synchronized(lock) {
            if (currentLibraryId(context) == libraryId) {
                prefs(context).edit().remove(KEY_CURRENT).commit()
            }
            val previous = progressSnapshot[libraryId]
            updateProgressLocked(
                context,
                Progress(
                    libraryId = libraryId,
                    current = previous?.current ?: 0,
                    total = previous?.total ?: 0,
                    status = status,
                    message = message
                )
            )
        }
    }

    internal fun queuedCount(context: Context): Int {
        return synchronized(lock) { readQueue(context).size }
    }

    internal fun currentTaskId(context: Context): Int {
        return synchronized(lock) { currentLibraryId(context) }
    }

    internal fun hasPendingTasks(context: Context): Boolean {
        return synchronized(lock) {
            currentLibraryId(context) > 0 || readQueue(context).isNotEmpty()
        }
    }

    internal fun fingerprint(quizzes: List<Quiz>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        quizzes.sortedBy { it.id }.forEach { quiz ->
            digest.update(quiz.id.toString().toByteArray())
            digest.update(0.toByte())
            digest.update(quiz.prompt.toByteArray())
            digest.update(0.toByte())
            quiz.options.forEach {
                digest.update(it.toByteArray())
                digest.update(0.toByte())
            }
            quiz.answer.sorted().forEach {
                digest.update(it.toString().toByteArray())
                digest.update(0.toByte())
            }
            digest.update(quiz.questionType.orEmpty().toByteArray())
            digest.update(0.toByte())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun startService(context: Context) {
        ContextCompat.startForegroundService(
            context.applicationContext,
            SimilarQuizAnalysisService.startIntent(context)
        )
    }

    private fun updateProgressLocked(context: Context, value: Progress) {
        val snapshot = progressSnapshot.toMutableMap()
        snapshot[value.libraryId] = value
        progressSnapshot = snapshot
        _progress.postValue(snapshot)
        val now = System.currentTimeMillis()
        if (value.status != Status.RUNNING ||
            value.current == value.total ||
            now - lastProgressPersistAt >= PROGRESS_PERSIST_INTERVAL_MS
        ) {
            lastProgressPersistAt = now
            writeProgress(context, snapshot)
        }
    }

    private fun readProgress(context: Context): Map<Int, Progress> {
        val json = prefs(context).getString(KEY_PROGRESS, null) ?: return emptyMap()
        return runCatching {
            val type = object : TypeToken<List<PersistedProgress>>() {}.type
            gson.fromJson<List<PersistedProgress>>(json, type).associate { item ->
                val status = runCatching { Status.valueOf(item.status) }.getOrDefault(Status.FAILED)
                item.libraryId to Progress(
                    libraryId = item.libraryId,
                    current = item.current,
                    total = item.total,
                    status = status,
                    message = item.message
                )
            }
        }.getOrDefault(emptyMap())
    }

    private fun restoreProgress(context: Context): Map<Int, Progress> {
        val current = currentLibraryId(context).takeIf { it > 0 }
        val queued = readQueue(context).toSet()
        val restored = readProgress(context).toMutableMap()

        restored.replaceAll { libraryId, value ->
            when {
                libraryId == current -> value.copy(status = Status.RUNNING)
                libraryId in queued -> value.copy(status = Status.QUEUED)
                value.status == Status.RUNNING || value.status == Status.QUEUED -> {
                    if (hasAnalysis(context, libraryId)) {
                        value.copy(
                            current = value.total,
                            status = Status.COMPLETED,
                            message = null
                        )
                    } else {
                        value.copy(
                            status = Status.FAILED,
                            message = "分析任务已中断"
                        )
                    }
                }
                else -> value
            }
        }
        current?.let { libraryId ->
            restored.putIfAbsent(
                libraryId,
                Progress(libraryId = libraryId, status = Status.RUNNING)
            )
        }
        queued.forEach { libraryId ->
            restored.putIfAbsent(
                libraryId,
                Progress(libraryId = libraryId, status = Status.QUEUED)
            )
        }
        writeProgress(context, restored)
        return restored
    }

    private fun writeProgress(context: Context, values: Map<Int, Progress>) {
        val persisted = values.values.map {
            PersistedProgress(
                libraryId = it.libraryId,
                current = it.current,
                total = it.total,
                status = it.status.name,
                message = it.message
            )
        }
        prefs(context).edit().putString(KEY_PROGRESS, gson.toJson(persisted)).apply()
    }

    private fun readQueue(context: Context): List<Int> {
        val json = prefs(context).getString(KEY_QUEUE, null) ?: return emptyList()
        return runCatching {
            val type = object : TypeToken<List<Int>>() {}.type
            gson.fromJson<List<Int>>(json, type)
        }.getOrDefault(emptyList())
    }

    private fun writeQueue(context: Context, queue: List<Int>) {
        prefs(context).edit().putString(KEY_QUEUE, gson.toJson(queue)).commit()
    }

    private fun writeQueueState(context: Context, state: SimilarQuizQueueState) {
        prefs(context).edit()
            .putInt(KEY_CURRENT, state.currentLibraryId ?: 0)
            .putString(KEY_QUEUE, gson.toJson(state.queuedLibraryIds))
            .commit()
    }

    private fun currentLibraryId(context: Context): Int {
        return prefs(context).getInt(KEY_CURRENT, 0)
    }

    private fun readResultMap(context: Context, libraryId: Int): Map<Int, List<Int>> {
        val json = prefs(context).getString(resultKey(libraryId), null) ?: return emptyMap()
        return runCatching {
            val type = object : TypeToken<Map<Int, List<Int>>>() {}.type
            gson.fromJson<Map<Int, List<Int>>>(json, type)
        }.getOrDefault(emptyMap())
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun resultKey(libraryId: Int) = "lib_$libraryId"
    private fun versionKey(libraryId: Int) = "lib_${libraryId}_version"
    private fun fingerprintKey(libraryId: Int) = "lib_${libraryId}_fingerprint"

    private const val PROGRESS_PERSIST_INTERVAL_MS = 1_000L
}
