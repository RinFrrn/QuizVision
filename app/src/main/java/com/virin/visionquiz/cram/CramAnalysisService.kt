package com.virin.visionquiz.cram

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
import androidx.core.content.ContextCompat
import com.virin.visionquiz.R
import com.virin.visionquiz.ai.AiConfigStore
import com.virin.visionquiz.ai.AiHttpException
import com.virin.visionquiz.ai.OpenAiCompatibleClient
import com.virin.visionquiz.dao.LibraryInsightCache
import com.virin.visionquiz.dao.QuizDatabase
import com.virin.visionquiz.quizentry.QuizEntryActivity
import java.io.IOException
import java.util.concurrent.TimeUnit
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
import okhttp3.OkHttpClient

class CramAnalysisService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cancelRequested = AtomicBoolean(false)
    private val progressStore by lazy { CramAnalysisProgressStore(this) }
    private var analysisJob: Job? = null
    @Volatile private var currentLibraryId: Int = 0
    @Volatile private var currentRunId: Long = 0L
    @Volatile private var currentStopStartId: Int = 0
    private var nextRunId: Long = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                currentStopStartId = startId
                val targetLibraryId = intent.getIntExtra(EXTRA_LIBRARY_ID, 0)
                val cancelled = cancelAnalysis(targetLibraryId)
                if (!cancelled && targetLibraryId > 0) {
                    markInterruptedProgress(targetLibraryId)
                }
                if (!cancelled && analysisJob?.isActive != true) {
                    stopSelfResult(startId)
                }
            }
            ACTION_START -> {
                val libraryId = intent.getIntExtra(EXTRA_LIBRARY_ID, 0)
                if (libraryId <= 0) {
                    stopSelfResult(startId)
                    return START_NOT_STICKY
                }
                if (analysisJob?.isActive == true && currentLibraryId == libraryId) {
                    currentStopStartId = startId
                    return START_NOT_STICKY
                }
                val runId = ++nextRunId
                currentRunId = runId
                currentStopStartId = startId
                currentLibraryId = libraryId
                activeLibraryId = libraryId
                cancelRequested.set(false)
                val forceRefresh = intent.getBooleanExtra(EXTRA_FORCE_REFRESH, false)
                val dailyMinutes = intent.getIntExtra(
                    EXTRA_DAILY_MINUTES,
                    DEFAULT_DAILY_MINUTES
                )
                val authorizedDestinationSignature = intent.getStringExtra(
                    EXTRA_AUTHORIZED_DESTINATION_SIGNATURE
                ).orEmpty()
                startForeground(NOTIFICATION_ID, buildNotification("正在准备本地分析", 0, 0))
                analysisJob?.cancel()
                analysisJob = serviceScope.launch {
                    runAnalysis(
                        libraryId = libraryId,
                        forceRefresh = forceRefresh,
                        dailyMinutes = dailyMinutes,
                        authorizedDestinationSignature = authorizedDestinationSignature,
                        runId = runId
                    )
                }
            }
            else -> {
                if (analysisJob?.isActive != true) stopSelfResult(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cancelRequested.set(true)
        activeLibraryId = 0
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun runAnalysis(
        libraryId: Int,
        forceRefresh: Boolean,
        dailyMinutes: Int,
        authorizedDestinationSignature: String,
        runId: Long
    ) {
        try {
            updateProgress(
                libraryId = libraryId,
                stage = CramAnalysisStage.PREPARING,
                completed = 0,
                total = 0,
                message = "正在生成本地题库画像",
                runId = runId
            )
            val database = QuizDatabase.getInstance(this)
            val quizDao = database.questionDao()
            val libraryDao = database.categoryDao()
            val cacheDao = database.libraryInsightCacheDao()
            val library = libraryDao.getQuizLibraryByIdOrNull(libraryId)
                ?: throw IllegalStateException("题库不存在")
            val quizzes = quizDao.getQuizsByCategoryOnce(libraryId)
            if (quizzes.isEmpty()) throw IllegalStateException("题库为空")

            val studyPack = CramAnalysisRepository(this).loadStudyPack(
                libraryId = libraryId,
                forceLocal = forceRefresh,
                dailyMinutes = dailyMinutes
            ) ?: throw IllegalStateException("无法生成本地题库分析")
            ensureNotCancelled(runId)

            val config = AiConfigStore(this).read()
            if (!config.isComplete()) {
                throw IllegalStateException("本地分析已完成；如需 AI 深度速记，请先在设置中完成 AI 配置")
            }
            ensureAiDestinationAuthorized(
                libraryId = libraryId,
                config = config,
                authorizedDestinationSignature = authorizedDestinationSignature
            )

            val chunks = CramAiPromptBuilder.chunkQuestions(quizzes)
            if (chunks.isEmpty()) throw IllegalStateException("没有可分析的题目")
            val totalSteps = chunks.size + 1
            val summaries = ArrayList<Pair<String, String>>(chunks.size)
            var finishedChunks = 0
            var failedChunks = 0
            var consecutiveFailedChunks = 0
            val client = OpenAiCompatibleClient()

            chunks.forEach { chunk ->
                ensureNotCancelled(runId)
                val label = if (chunk.partCount > 1) {
                    "${chunk.moduleLabel}（${chunk.partIndex}/${chunk.partCount}）"
                } else {
                    chunk.moduleLabel
                }
                updateProgress(
                    libraryId = libraryId,
                    stage = CramAnalysisStage.ANALYZING_CHUNKS,
                    completed = finishedChunks,
                    total = totalSteps,
                    message = "正在分析 $label",
                    runId = runId
                )
                val prompt = CramAiPromptBuilder.buildModulePrompt(config, chunk)
                val fingerprint = CramAiPromptBuilder.fingerprint(prompt, config)
                val cached = cacheDao.getCache(
                    libraryId,
                    CramCacheType.MODULE_ANALYSIS,
                    chunk.cacheKey
                )
                val content = if (
                    cached?.fingerprint == fingerprint &&
                    cached.content.isNotBlank()
                ) {
                    cached.content
                } else {
                    runCatching {
                        completeWithRetry(
                            client = client,
                            configStore = config,
                            prompt = prompt,
                            maxTokens = MODULE_MAX_TOKENS,
                            libraryId = libraryId,
                            authorizedDestinationSignature = authorizedDestinationSignature,
                            runId = runId
                        )
                    }.onFailure { error ->
                        if (error is CancellationException) throw error
                        if (error is AiHttpException) throw error
                        if (!isRetryableAiError(error)) throw error
                        Log.w(TAG, "Chunk ${chunk.cacheKey} failed", error)
                    }.getOrNull()?.also { generated ->
                        val now = System.currentTimeMillis()
                        cacheDao.upsertCache(
                            LibraryInsightCache(
                                id = cached?.id ?: 0,
                                libraryId = libraryId,
                                type = CramCacheType.MODULE_ANALYSIS,
                                subKey = chunk.cacheKey,
                                fingerprint = fingerprint,
                                content = generated,
                                createdAt = cached?.createdAt ?: now,
                                updatedAt = now
                            )
                        )
                    }
                }
                if (content.isNullOrBlank()) {
                    failedChunks++
                    consecutiveFailedChunks++
                    if (consecutiveFailedChunks >= MAX_CONSECUTIVE_CHUNK_FAILURES) {
                        throw IOException(
                            "连续 $consecutiveFailedChunks 个分块分析失败，已暂停以避免重复请求；" +
                                "成功分块和旧总稿均已保留"
                        )
                    }
                } else {
                    consecutiveFailedChunks = 0
                    summaries += label to content
                }
                finishedChunks++
                updateProgress(
                    libraryId = libraryId,
                    stage = CramAnalysisStage.ANALYZING_CHUNKS,
                    completed = finishedChunks,
                    total = totalSteps,
                    message = "已完成 $finishedChunks/${chunks.size} 个题库分块",
                    runId = runId
                )
            }

            ensureNotCancelled(runId)
            if (summaries.isEmpty()) {
                throw IOException("AI 分块均未成功；已保留本地冲刺分析，请稍后重试")
            }

            updateProgress(
                libraryId = libraryId,
                stage = CramAnalysisStage.SYNTHESIZING,
                completed = chunks.size,
                total = totalSteps,
                message = "正在合成3天冲刺总稿",
                runId = runId
            )
            ensureLocalPlanUnchanged(
                cacheDao = cacheDao,
                libraryId = libraryId,
                expectedFingerprint = studyPack.localFingerprint
            )
            val finalPrompt = CramAiPromptBuilder.buildFinalReportPrompt(
                config = config,
                libraryName = library.name,
                questionCount = quizzes.size,
                localSummary = studyPack.localMarkdown,
                moduleSummaries = summaries,
                incompleteChunkCount = failedChunks
            )
            val finalFingerprint = buildFinalReportCacheFingerprint(
                localFingerprint = studyPack.localFingerprint,
                promptFingerprint = CramAiPromptBuilder.fingerprint(finalPrompt, config)
            )
            val existingReport = cacheDao.getCache(
                libraryId,
                CramCacheType.FINAL_REPORT,
                CramCacheSubKey.MAIN
            )
            val report = if (!forceRefresh &&
                existingReport?.fingerprint == finalFingerprint &&
                existingReport.content.isNotBlank()
            ) {
                existingReport.content
            } else {
                completeWithRetry(
                    client = OpenAiCompatibleClient(buildCramHttpClient()),
                    configStore = config,
                    prompt = finalPrompt,
                    maxTokens = FINAL_MAX_TOKENS,
                    libraryId = libraryId,
                    authorizedDestinationSignature = authorizedDestinationSignature,
                    runId = runId
                ).also { generated ->
                    ensureLocalPlanUnchanged(
                        cacheDao = cacheDao,
                        libraryId = libraryId,
                        expectedFingerprint = studyPack.localFingerprint
                    )
                    val now = System.currentTimeMillis()
                    cacheDao.upsertCache(
                        LibraryInsightCache(
                            id = existingReport?.id ?: 0,
                            libraryId = libraryId,
                            type = CramCacheType.FINAL_REPORT,
                            subKey = CramCacheSubKey.MAIN,
                            fingerprint = finalFingerprint,
                            content = generated,
                            createdAt = existingReport?.createdAt ?: now,
                            updatedAt = now
                        )
                    )
                }
            }
            if (report.isBlank()) throw IOException("AI 返回了空的冲刺总稿")

            updateProgress(
                libraryId = libraryId,
                stage = CramAnalysisStage.COMPLETED,
                completed = totalSteps,
                total = totalSteps,
                message = if (failedChunks == 0) {
                    "冲刺总稿已生成"
                } else {
                    "冲刺总稿已生成，$failedChunks 个分块待下次补全"
                },
                runId = runId
            )
            if (isCurrentRun(runId)) {
                showFinishedNotification(
                    if (failedChunks == 0) {
                        "3天冲刺总稿已生成"
                    } else {
                        "总稿已生成，部分分块可稍后补全"
                    }
                )
            }
        } catch (error: CancellationException) {
            if (!isLibraryBeingDeleted(libraryId)) {
                updateProgress(
                    libraryId = libraryId,
                    stage = CramAnalysisStage.CANCELLED,
                    completed = progressStore.read(libraryId).completedSteps,
                    total = progressStore.read(libraryId).totalSteps,
                    message = "分析已取消，已完成的分块会保留",
                    notify = isCurrentRun(runId),
                    runId = runId
                )
            }
        } catch (error: Exception) {
            Log.w(TAG, "Cram analysis failed", error)
            if (!isLibraryBeingDeleted(libraryId)) {
                updateProgress(
                    libraryId = libraryId,
                    stage = CramAnalysisStage.FAILED,
                    completed = progressStore.read(libraryId).completedSteps,
                    total = progressStore.read(libraryId).totalSteps,
                    message = "冲刺分析未完成",
                    error = error.message?.take(240).orEmpty(),
                    notify = isCurrentRun(runId),
                    runId = runId
                )
                if (isCurrentRun(runId)) {
                    showFinishedNotification(error.message?.take(100) ?: "冲刺分析未完成")
                }
            }
        } finally {
            if (isCurrentRun(runId)) {
                analysisJob = null
                activeLibraryId = 0
                stopSelfResult(currentStopStartId)
            }
        }
    }

    private suspend fun completeWithRetry(
        client: OpenAiCompatibleClient,
        configStore: com.virin.visionquiz.ai.AiConfig,
        prompt: com.virin.visionquiz.ai.AiPrompt,
        maxTokens: Int,
        libraryId: Int,
        authorizedDestinationSignature: String,
        runId: Long
    ): String {
        var lastError: Throwable? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            ensureNotCancelled(runId)
            ensureAiDestinationAuthorized(
                libraryId = libraryId,
                config = configStore,
                authorizedDestinationSignature = authorizedDestinationSignature
            )
            try {
                return client.complete(configStore, prompt, maxTokens)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (!isRetryableAiError(error)) throw error
                lastError = error
                if (attempt < MAX_ATTEMPTS - 1) {
                    delay(RETRY_DELAYS_MS[attempt])
                }
            }
        }
        throw lastError ?: IOException("AI 请求失败")
    }

    private fun ensureAiDestinationAuthorized(
        libraryId: Int,
        config: com.virin.visionquiz.ai.AiConfig,
        authorizedDestinationSignature: String
    ) {
        val actualSignature = config.dataSharingDestinationSignature()
        if (
            authorizedDestinationSignature.isBlank() ||
            actualSignature != authorizedDestinationSignature ||
            !CramAiConsentStore.matches(this, libraryId, authorizedDestinationSignature)
        ) {
            throw IllegalStateException("AI 配置或外发授权已变化，请返回冲刺页重新确认")
        }
    }

    private fun cancelAnalysis(targetLibraryId: Int): Boolean {
        val activeJob = analysisJob?.takeIf { it.isActive }
        if (
            targetLibraryId > 0 &&
            targetLibraryId == currentLibraryId &&
            activeJob != null
        ) {
            cancelRequested.set(true)
            activeJob.cancel()
            return true
        }
        return false
    }

    private fun markInterruptedProgress(libraryId: Int) {
        val previous = progressStore.read(libraryId)
        progressStore.write(
            CramAnalysisProgress(
                libraryId = libraryId,
                stage = CramAnalysisStage.CANCELLED,
                completedSteps = previous.completedSteps,
                totalSteps = previous.totalSteps,
                message = "上次分析已中断，可继续生成"
            )
        )
    }

    private fun ensureNotCancelled(runId: Long) {
        if (
            runId != currentRunId ||
            cancelRequested.get() ||
            !serviceScope.isActive
        ) {
            throw CancellationException()
        }
    }

    private fun isCurrentRun(runId: Long): Boolean = runId == currentRunId

    private fun isRetryableAiError(error: Throwable): Boolean {
        return when (error) {
            is AiHttpException -> {
                error.statusCode == 408 ||
                    error.statusCode == 425 ||
                    error.statusCode == 429 ||
                    error.statusCode in 500..599
            }
            is IOException -> true
            else -> false
        }
    }

    private fun buildCramHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .callTimeout(300, TimeUnit.SECONDS)
            .build()
    }

    private suspend fun ensureLocalPlanUnchanged(
        cacheDao: com.virin.visionquiz.dao.LibraryInsightCacheDao,
        libraryId: Int,
        expectedFingerprint: String
    ) {
        val currentFingerprint = cacheDao.getCache(
            libraryId = libraryId,
            type = CramCacheType.LOCAL_ANALYSIS,
            subKey = CramCacheSubKey.MAIN
        )?.fingerprint
        if (currentFingerprint != expectedFingerprint) {
            throw IllegalStateException("学习时长或题库已变化，请重新生成冲刺总稿")
        }
    }

    private fun updateProgress(
        libraryId: Int,
        stage: CramAnalysisStage,
        completed: Int,
        total: Int,
        message: String,
        error: String = "",
        notify: Boolean = true,
        runId: Long? = null
    ) {
        if (
            runId != null &&
            !isCurrentRun(runId) &&
            currentLibraryId == libraryId
        ) {
            return
        }
        progressStore.write(
            CramAnalysisProgress(
                libraryId = libraryId,
                stage = stage,
                completedSteps = completed,
                totalSteps = total,
                message = message,
                errorMessage = error
            )
        )
        if (notify && (runId == null || isCurrentRun(runId))) {
            updateNotification(message, completed, total)
        }
    }

    private fun updateNotification(text: String, current: Int, total: Int) {
        try {
            NotificationManagerCompat.from(this).notify(
                NOTIFICATION_ID,
                buildNotification(text, current, total)
            )
        } catch (_: SecurityException) {
        }
    }

    private fun buildNotification(text: String, current: Int, total: Int): Notification {
        val openIntent = PendingIntent.getActivity(
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
            cancelIntent(this, currentLibraryId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.icon_science_24px)
            .setContentTitle("3天冲刺分析")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(total, current, total <= 0)
            .addAction(R.drawable.round_close_24, "取消", cancelIntent)
            .build()
    }

    private fun showFinishedNotification(text: String) {
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
                    .setContentTitle("3天冲刺分析")
                    .setContentText(text)
                    .setContentIntent(
                        PendingIntent.getActivity(
                            this,
                            REQUEST_OPEN,
                            Intent(this, QuizEntryActivity::class.java),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                    )
                    .setAutoCancel(true)
                    .build()
            )
        } catch (_: SecurityException) {
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "3天冲刺分析",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "显示题库分块分析和冲刺总稿生成进度"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
        )
    }

    companion object {
        private const val TAG = "CramAnalysisService"
        private const val ACTION_START = "com.virin.visionquiz.action.START_CRAM_ANALYSIS"
        private const val ACTION_CANCEL = "com.virin.visionquiz.action.CANCEL_CRAM_ANALYSIS"
        private const val EXTRA_LIBRARY_ID = "library_id"
        private const val EXTRA_FORCE_REFRESH = "force_refresh"
        private const val EXTRA_DAILY_MINUTES = "daily_minutes"
        private const val EXTRA_AUTHORIZED_DESTINATION_SIGNATURE =
            "authorized_destination_signature"
        private const val CHANNEL_ID = "cram_analysis"
        private const val NOTIFICATION_ID = 3601
        private const val REQUEST_OPEN = 3602
        private const val REQUEST_CANCEL = 3603
        private const val MODULE_MAX_TOKENS = 2600
        private const val FINAL_MAX_TOKENS = 4800
        private const val MAX_ATTEMPTS = 3
        private const val MAX_CONSECUTIVE_CHUNK_FAILURES = 2
        private val RETRY_DELAYS_MS = longArrayOf(1_500L, 4_000L)
        @Volatile private var activeLibraryId: Int = 0
        private val librariesBeingDeleted = java.util.concurrent.ConcurrentHashMap
            .newKeySet<Int>()

        fun start(
            context: Context,
            libraryId: Int,
            forceRefresh: Boolean = false,
            dailyMinutes: Int = DEFAULT_DAILY_MINUTES,
            authorizedDestinationSignature: String
        ) {
            require(authorizedDestinationSignature.isNotBlank())
            val intent = Intent(context, CramAnalysisService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_LIBRARY_ID, libraryId)
                putExtra(EXTRA_FORCE_REFRESH, forceRefresh)
                putExtra(EXTRA_DAILY_MINUTES, dailyMinutes)
                putExtra(
                    EXTRA_AUTHORIZED_DESTINATION_SIGNATURE,
                    authorizedDestinationSignature
                )
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: Context, libraryId: Int) {
            context.startService(cancelIntent(context, libraryId))
        }

        fun cancelForLibraryDeletion(context: Context, libraryId: Int) {
            if (libraryId <= 0) return
            librariesBeingDeleted += libraryId
            if (activeLibraryId == libraryId) {
                context.stopService(Intent(context, CramAnalysisService::class.java))
            }
            CramAnalysisProgressStore(context).clear(libraryId)
        }

        private fun isLibraryBeingDeleted(libraryId: Int): Boolean {
            return libraryId in librariesBeingDeleted
        }

        private fun cancelIntent(context: Context, libraryId: Int): Intent {
            return Intent(context, CramAnalysisService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_LIBRARY_ID, libraryId)
            }
        }
    }
}
