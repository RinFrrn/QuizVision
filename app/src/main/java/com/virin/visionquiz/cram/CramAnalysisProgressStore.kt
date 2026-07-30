package com.virin.visionquiz.cram

import android.content.Context
import android.content.Intent
import com.google.gson.Gson
import java.util.UUID

enum class CramAnalysisStage {
    IDLE,
    PREPARING,
    ANALYZING_CHUNKS,
    SYNTHESIZING,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class CramAnalysisProgress(
    val libraryId: Int,
    val stage: CramAnalysisStage = CramAnalysisStage.IDLE,
    val completedSteps: Int = 0,
    val totalSteps: Int = 0,
    val message: String = "",
    val errorMessage: String = "",
    val ownerProcessToken: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
) {
    val isRunning: Boolean
        get() = stage == CramAnalysisStage.PREPARING ||
            stage == CramAnalysisStage.ANALYZING_CHUNKS ||
            stage == CramAnalysisStage.SYNTHESIZING

    val progressFraction: Float
        get() = if (totalSteps <= 0) {
            0f
        } else {
            (completedSteps.toFloat() / totalSteps.toFloat()).coerceIn(0f, 1f)
        }
}

class CramAnalysisProgressStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    fun read(libraryId: Int): CramAnalysisProgress {
        val json = prefs.getString(key(libraryId), null) ?: return CramAnalysisProgress(libraryId)
        val progress = runCatching {
            gson.fromJson(json, CramAnalysisProgress::class.java)
        }.getOrNull()?.takeIf { it.libraryId == libraryId } ?: CramAnalysisProgress(libraryId)
        if (progress.isRunning && progress.ownerProcessToken != PROCESS_TOKEN) {
            val interrupted = progress.copy(
                stage = CramAnalysisStage.CANCELLED,
                message = "上次分析因应用进程中断，可继续生成",
                errorMessage = "",
                ownerProcessToken = null,
                updatedAt = System.currentTimeMillis()
            )
            prefs.edit().putString(key(libraryId), gson.toJson(interrupted)).apply()
            return interrupted
        }
        return progress
    }

    fun write(progress: CramAnalysisProgress) {
        require(progress.libraryId > 0) { "libraryId must be positive" }
        val normalized = progress.copy(
            ownerProcessToken = PROCESS_TOKEN.takeIf { progress.isRunning },
            updatedAt = System.currentTimeMillis()
        )
        prefs.edit().putString(key(progress.libraryId), gson.toJson(normalized)).apply()
        appContext.sendBroadcast(
            Intent(ACTION_PROGRESS_CHANGED).apply {
                setPackage(appContext.packageName)
                putExtra(EXTRA_LIBRARY_ID, progress.libraryId)
            }
        )
    }

    fun clear(libraryId: Int) {
        prefs.edit().remove(key(libraryId)).apply()
        appContext.sendBroadcast(
            Intent(ACTION_PROGRESS_CHANGED).apply {
                setPackage(appContext.packageName)
                putExtra(EXTRA_LIBRARY_ID, libraryId)
            }
        )
    }

    private fun key(libraryId: Int): String = "library_$libraryId"

    companion object {
        const val ACTION_PROGRESS_CHANGED =
            "com.virin.visionquiz.action.CRAM_ANALYSIS_PROGRESS_CHANGED"
        const val EXTRA_LIBRARY_ID = "library_id"
        private const val PREFS_NAME = "cram_analysis_progress"
        private val PROCESS_TOKEN = UUID.randomUUID().toString()
    }
}
