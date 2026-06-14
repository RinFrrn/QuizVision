package com.virin.visionquiz.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.virin.visionquiz.R
import com.virin.visionquiz.dao.QuizDatabase
import com.virin.visionquiz.quizentry.QuizEntryActivity
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SimilarQuizAnalysisService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var processorJob: Job? = null
    private var analysisJob: Deferred<QuizSimilarityAnalysis>? = null
    private var currentLibraryId: Int? = null
    private var currentLibraryName: String = "题库"
    private val cancelledLibraryId = AtomicInteger(0)
    @Volatile
    private var latestStartId: Int = 0

    override fun onCreate() {
        super.onCreate()
        SimilarQuizStore.initialize(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        startForeground(NOTIFICATION_ID, buildNotification("准备分析相似题目", 0, 0))
        when (intent?.action) {
            ACTION_CANCEL -> {
                val targetId = intent.getIntExtra(EXTRA_LIBRARY_ID, 0)
                if (targetId > 0 && SimilarQuizStore.currentTaskId(this) == targetId) {
                    cancelledLibraryId.set(targetId)
                    analysisJob?.cancel()
                } else if (targetId > 0) {
                    SimilarQuizStore.cancelAnalysis(this, targetId)
                }
            }
        }
        ensureProcessorRunning()
        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureProcessorRunning() {
        if (processorJob?.isActive == true) return
        processorJob = serviceScope.launch {
            processQueue()
        }
    }

    private suspend fun processQueue() {
        while (serviceScope.isActive) {
            val libraryId = SimilarQuizStore.claimNext(this) ?: break
            currentLibraryId = libraryId
            if (cancelledLibraryId.get() != libraryId) {
                cancelledLibraryId.set(0)
            }
            try {
                analyzeLibrary(libraryId)
            } catch (cancelled: CancellationException) {
                if (isCancellationRequested(libraryId)) {
                    SimilarQuizStore.finishWithoutResult(
                        this,
                        libraryId,
                        SimilarQuizStore.Status.CANCELLED
                    )
                    updateNotification("已取消 $currentLibraryName", 0, 0)
                    continue
                }
                throw cancelled
            } catch (error: Throwable) {
                SimilarQuizStore.finishWithoutResult(
                    this,
                    libraryId,
                    SimilarQuizStore.Status.FAILED,
                    error.message?.takeIf { it.isNotBlank() } ?: "分析失败"
                )
                updateNotification("$currentLibraryName 分析失败", 0, 0)
            } finally {
                currentLibraryId = null
                analysisJob = null
                cancelledLibraryId.compareAndSet(libraryId, 0)
            }
        }

        if (!SimilarQuizStore.hasPendingTasks(this)) {
            showCompletionNotification()
            if (!stopSelfResult(latestStartId)) {
                processorJob = null
                ensureProcessorRunning()
            }
        }
    }

    private suspend fun analyzeLibrary(libraryId: Int) {
        ensureNotCancelled(libraryId)
        val database = QuizDatabase.getInstance(this)
        val library = database.categoryDao().getQuizLibraryByIdOrNull(libraryId)
            ?: error("题库已删除")
        currentLibraryName = library.name
        val quizzes = database.questionDao().getQuizsByCategoryOnce(libraryId)
        ensureNotCancelled(libraryId)

        val startFingerprint = SimilarQuizStore.fingerprint(quizzes)
        val index = withContext(Dispatchers.Default) { QuizSimilarityIndex(quizzes) }
        ensureNotCancelled(libraryId)
        SimilarQuizStore.updateRunningProgress(this, libraryId, 0, index.size)
        updateNotification("$currentLibraryName · 0 / ${index.size}", 0, index.size)

        var lastNotificationAt = 0L
        analysisJob = serviceScope.async(Dispatchers.Default) {
            index.analyzeAll { current, total ->
                ensureNotCancelled(libraryId)
                SimilarQuizStore.updateRunningProgress(
                    this@SimilarQuizAnalysisService,
                    libraryId,
                    current,
                    total
                )
                val now = System.currentTimeMillis()
                if (current == total || now - lastNotificationAt >= NOTIFICATION_UPDATE_INTERVAL_MS) {
                    lastNotificationAt = now
                    updateNotification(
                        "$currentLibraryName · $current / $total",
                        current,
                        total
                    )
                }
            }
        }
        val analysis = analysisJob!!.await()
        ensureNotCancelled(libraryId)

        val latestLibrary = database.categoryDao().getQuizLibraryByIdOrNull(libraryId)
            ?: error("题库已删除")
        val latestQuizzes = database.questionDao().getQuizsByCategoryOnce(libraryId)
        if (latestLibrary.id != libraryId ||
            SimilarQuizStore.fingerprint(latestQuizzes) != startFingerprint
        ) {
            error("题库内容已变化，请重新分析")
        }

        val idsByQuiz = analysis.resultsByQuizId.mapValues { (_, results) ->
            results.map { it.quiz.id }
        }
        SimilarQuizStore.completeAnalysis(this, libraryId, idsByQuiz, startFingerprint)
        updateNotification(
            "$currentLibraryName · 分析完成",
            index.size,
            index.size
        )
    }

    private fun isCancellationRequested(libraryId: Int): Boolean {
        return cancelledLibraryId.get() == libraryId
    }

    private fun ensureNotCancelled(libraryId: Int) {
        if (isCancellationRequested(libraryId)) {
            throw CancellationException("用户取消")
        }
    }

    private fun updateNotification(text: String, current: Int, total: Int) {
        NotificationManagerCompat.from(this).notify(
            NOTIFICATION_ID,
            buildNotification(text, current, total)
        )
    }

    private fun buildNotification(text: String, current: Int, total: Int): Notification {
        val queued = SimilarQuizStore.queuedCount(this)
        val contentText = if (queued > 0) "$text · 等待 $queued 个" else text
        val cancelLibraryId = currentLibraryId ?: 0
        val cancelIntent = PendingIntent.getService(
            this,
            REQUEST_CANCEL,
            cancelIntent(this, cancelLibraryId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val contentIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN_APP,
            Intent(this, QuizEntryActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.icon_document_search_24px)
            .setContentTitle("相似题分析")
            .setContentText(contentText)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(total, current, total <= 0)
            .addAction(R.drawable.round_close_24, "取消当前", cancelIntent)
            .build()
    }

    private fun showCompletionNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(false)
        }
        NotificationManagerCompat.from(this).notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.icon_document_search_24px)
                .setContentTitle("相似题分析")
                .setContentText("分析队列已结束")
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(
                    PendingIntent.getActivity(
                        this,
                        REQUEST_OPEN_APP,
                        Intent(this, QuizEntryActivity::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
                .build()
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "相似题分析",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "显示题库相似题分析进度"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
        )
    }

    companion object {
        private const val ACTION_START =
            "com.virin.visionquiz.action.START_SIMILAR_ANALYSIS"
        private const val ACTION_CANCEL =
            "com.virin.visionquiz.action.CANCEL_SIMILAR_ANALYSIS"
        private const val EXTRA_LIBRARY_ID = "library_id"
        private const val CHANNEL_ID = "similar_quiz_analysis"
        private const val NOTIFICATION_ID = 2407
        private const val REQUEST_CANCEL = 2408
        private const val REQUEST_OPEN_APP = 2409
        private const val NOTIFICATION_UPDATE_INTERVAL_MS = 500L

        fun startIntent(context: Context): Intent {
            return Intent(context, SimilarQuizAnalysisService::class.java).apply {
                action = ACTION_START
            }
        }

        fun cancelIntent(context: Context, libraryId: Int): Intent {
            return Intent(context, SimilarQuizAnalysisService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_LIBRARY_ID, libraryId)
            }
        }
    }
}
