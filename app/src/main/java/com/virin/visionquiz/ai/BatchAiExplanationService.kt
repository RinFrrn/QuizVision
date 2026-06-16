package com.virin.visionquiz.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.virin.visionquiz.R
import com.virin.visionquiz.dao.Quiz
import com.virin.visionquiz.dao.QuizDatabase
import com.virin.visionquiz.quizentry.QuizEntryActivity
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class BatchAiExplanationService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var batchJob: Job? = null
    private val cancelRequested = AtomicBoolean(false)
    @Volatile private var currentLibraryId: Int = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                val targetId = intent.getIntExtra(EXTRA_LIBRARY_ID, 0)
                if (targetId > 0 && targetId == currentLibraryId) {
                    cancelRequested.set(true)
                    batchJob?.cancel()
                }
            }
            ACTION_START -> {
                val libraryId = intent.getIntExtra(EXTRA_LIBRARY_ID, 0)
                if (libraryId <= 0) {
                    stopSelf()
                    return START_STICKY
                }
                if (batchJob?.isActive == true && currentLibraryId == libraryId) {
                    return START_STICKY
                }
                cancelRequested.set(false)
                currentLibraryId = libraryId
                startForeground(NOTIFICATION_ID, buildNotification("准备生成解析", 0, 0))
                batchJob?.cancel()
                batchJob = serviceScope.launch { runBatch(libraryId, startId) }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        cancelRequested.set(true)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun runBatch(libraryId: Int, startId: Int) {
        val database = QuizDatabase.getInstance(this)
        val cacheDao = database.aiExplanationCacheDao()
        val quizDao = database.questionDao()
        val repository = AiExplanationRepository(this)
        val configStore = AiConfigStore(this)
        val config = configStore.read()

        if (!config.isComplete()) {
            updateNotification("AI 配置不完整，请先配置", 0, 0)
            stopSelfSafely(startId)
            return
        }

        val allQuizzes = quizDao.getQuizsByCategoryOnce(libraryId)
        if (allQuizzes.isEmpty()) {
            updateNotification("题库为空", 0, 0)
            stopSelfSafely(startId)
            return
        }

        val type = AiExplanationType.QUICK_REVIEW
        val toProcess = mutableListOf<Quiz>()
        var skipped = 0
        for (quiz in allQuizzes) {
            if (cancelRequested.get()) break
            val existing = cacheDao.getCache(quiz.id, type.value)
            if (existing != null) {
                skipped++
            } else {
                toProcess.add(quiz)
            }
        }

        if (toProcess.isEmpty()) {
            showCompletionNotification(
                "全部已有解析，无需生成",
                allQuizzes.size,
                0,
                skipped
            )
            stopSelfSafely(startId)
            return
        }

        updateNotification("开始生成 0/${toProcess.size}", 0, toProcess.size)

        val semaphore = Semaphore(CONCURRENCY)
        var completed = 0
        var failed = 0

        for (quiz in toProcess) {
            if (cancelRequested.get() || !serviceScope.isActive) break
            semaphore.withPermit {
                try {
                    val prompt = AiPromptBuilder.build(
                        quiz = quiz,
                        type = type,
                        taskPrompt = config.promptFor(type),
                        selectedAnswer = null
                    )
                    repository.getOrGenerate(
                        quizId = quiz.id,
                        libraryId = quiz.libraryId,
                        type = type,
                        config = config,
                        prompt = prompt,
                        forceRefresh = false
                    )
                    completed++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to generate for quiz ${quiz.id}: ${e.message}")
                    failed++
                }
            }
            val total = toProcess.size
            updateNotification(
                "正在生成解析 ${completed + failed}/$total",
                completed + failed,
                total
            )
            if (INTER_BATCH_DELAY_MS > 0) {
                delay(INTER_BATCH_DELAY_MS)
            }
        }

        val wasCancelled = cancelRequested.get()
        val message = if (wasCancelled) {
            "已取消 · 已生成 $completed 条"
        } else {
            "生成完成 $completed 条${if (failed > 0) "，失败 $failed 条" else ""}"
        }
        showCompletionNotification(message, allQuizzes.size, completed, skipped)
        stopSelfSafely(startId)
    }

    private fun stopSelfSafely(startId: Int) {
        if (!stopSelfResult(startId)) {
            stopSelf()
        }
    }

    private fun updateNotification(text: String, current: Int, total: Int) {
        try {
            NotificationManagerCompat.from(this)
                .notify(NOTIFICATION_ID, buildNotification(text, current, total))
        } catch (_: SecurityException) { }
    }

    private fun buildNotification(text: String, current: Int, total: Int): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            Intent(this, QuizEntryActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancelIntent = PendingIntent.getService(
            this,
            REQUEST_CANCEL,
            Intent(this, BatchAiExplanationService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_LIBRARY_ID, currentLibraryId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.icon_science_24px)
            .setContentTitle("AI 解析生成")
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(total, current, total <= 0)
            .addAction(R.drawable.round_close_24, "取消", cancelIntent)
            .build()
    }

    private fun showCompletionNotification(
        text: String,
        totalInLibrary: Int,
        generated: Int,
        skipped: Int
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(false)
        }
        try {
            NotificationManagerCompat.from(this).notify(
                NOTIFICATION_ID,
                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.icon_science_24px)
                    .setContentTitle("AI 解析生成")
                    .setContentText(text)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .setContentIntent(
                        PendingIntent.getActivity(
                            this,
                            REQUEST_OPEN,
                            Intent(this, QuizEntryActivity::class.java),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                    )
                    .build()
            )
        } catch (_: SecurityException) { }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "AI 解析生成",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "显示 AI 解析批量生成进度"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
        )
    }

    companion object {
        private const val TAG = "BatchAiExplanation"
        private const val ACTION_START =
            "com.virin.visionquiz.action.START_BATCH_AI_EXPLANATION"
        private const val ACTION_CANCEL =
            "com.virin.visionquiz.action.CANCEL_BATCH_AI_EXPLANATION"
        private const val EXTRA_LIBRARY_ID = "library_id"
        private const val CHANNEL_ID = "batch_ai_explanation"
        private const val NOTIFICATION_ID = 3501
        private const val REQUEST_CANCEL = 3502
        private const val REQUEST_OPEN = 3503
        private const val CONCURRENCY = 3
        private const val INTER_BATCH_DELAY_MS = 500L

        fun start(context: Context, libraryId: Int): Intent {
            return Intent(context, BatchAiExplanationService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_LIBRARY_ID, libraryId)
            }
        }

        fun cancel(context: Context, libraryId: Int): Intent {
            return Intent(context, BatchAiExplanationService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_LIBRARY_ID, libraryId)
            }
        }
    }
}
