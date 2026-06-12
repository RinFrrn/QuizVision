package com.virin.visionquiz.screendetector

import android.Manifest
import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.LiveData
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.color.MaterialColors
import com.virin.visionquiz.R
import com.virin.visionquiz.ScreenSource
import com.virin.visionquiz.dao.Quiz
import com.virin.visionquiz.preference.PreferenceUtils
import com.virin.visionquiz.util.PermissionManager
import com.virin.visionquiz.vision.questiondetector.OriginalRecognitionProcessor
import com.virin.visionquiz.vision.questiondetector.QuizRecognitionProcessor
import java.io.IOException

object ScreenDetectorController : ScreenDetectorSession.Controller {
    private const val TAG = "ScreenDetectorController"
    private const val MIN_QUESTIONS_FOR_ALIGNED_VERTICAL_SWIPE = 2

    private data class AnswerExecution(
        val targets: List<ScreenDetectorSession.AnswerTarget>,
        val points: List<android.graphics.Point>,
        val fingerprint: String,
        val contentFingerprint: String,
        val snapshotVersion: Int,
        val targetEndIndexes: List<Pair<String, Int>>
    )

    private val assistanceHandler = Handler(Looper.getMainLooper())
    private var hostActivity: FragmentActivity? = null
    private var cameraSource: ScreenSource? = null
    private var accessibilitySource: AccessibilityTextSource? = null
    private var libId = 0
    private var isDetectionRunning = false
    private var isDetectionPaused = false
    private var pendingStartRequest: StartRequest? = null
    private var pendingPermissionPrompt: PendingPermissionPrompt? = null
    private var permissionHostActivity: FragmentActivity? = null
    private var accessibilityDialog: Dialog? = null
    private var assistanceEnabled = false
    private var assistanceBusy = false
    private var accessibilityFloatingWindowEnabled = true
    private var accessibilityAnswerDotsOnlyEnabled = true
    private var assistanceGeneration = 0
    private var waitingForPageFingerprint: String? = null
    private var selectedPageAxis: QuizAccessibilityService.PageAxis? = null
    private var phaseBeforePause = ScreenDetectorSession.AssistancePhase.IDLE
    private var pendingAnswerPlan: AnswerExecution? = null
    private var pendingAnswerPointIndex = 0
    private var pendingAutoAdvanceRunnable: Runnable? = null
    private var pendingPageChangeTimeoutRunnable: Runnable? = null
    private val answeredQuestionFingerprints = mutableSetOf<String>()
    private var latestStableSnapshotVersion = 0

    fun startQuizDetection(
        activity: FragmentActivity,
        libraryId: Int,
        quizzes: LiveData<List<Quiz>>
    ) {
        pendingPermissionPrompt = null
        val request = StartRequest.Quiz(libraryId, quizzes)
        if (!ensureStartPermissions(activity, request)) {
            return
        }
        startInternal(activity, request)
    }

    fun startAccessibilityQuizDetection(
        activity: FragmentActivity,
        libraryId: Int,
        quizzes: LiveData<List<Quiz>>
    ) {
        pendingPermissionPrompt = null
        accessibilityFloatingWindowEnabled =
            PreferenceUtils.shouldShowAccessibilityFloatingControl(activity)
        accessibilityAnswerDotsOnlyEnabled =
            PreferenceUtils.shouldUseAccessibilitySimplifiedAnswerDisplay(activity)
        val request = StartRequest.AccessibilityQuiz(libraryId, quizzes)
        pendingStartRequest = request
        permissionHostActivity = activity
        pendingPermissionPrompt = PendingPermissionPrompt.ACCESSIBILITY
        showAccessibilityPermissionDialog(activity)
    }

    fun startProcessorTest(activity: FragmentActivity) {
        pendingPermissionPrompt = null
        val request = StartRequest.ProcessorTest
        if (!ensureStartPermissions(activity, request)) {
            return
        }
        startInternal(activity, request)
    }

    fun onHostResumed(activity: FragmentActivity) {
        val request = pendingStartRequest
        if (request == null) {
            clearSelectedPageDirection()
            return
        }
        if (request.requiresAccessibility) {
            permissionHostActivity = activity
            pendingPermissionPrompt = PendingPermissionPrompt.ACCESSIBILITY
            showAccessibilityPermissionDialog(activity)
            return
        }
        if (!ensureStartPermissions(activity, request)) {
            return
        }
        pendingStartRequest = null
        pendingPermissionPrompt = null
        permissionHostActivity = null
        startInternal(activity, request)
    }

    fun onAccessibilityServiceConnected() {
        val activity = permissionHostActivity ?: return
        if (pendingStartRequest?.requiresAccessibility != true ||
            pendingPermissionPrompt != PendingPermissionPrompt.ACCESSIBILITY
        ) {
            return
        }
        QuizAccessibilityService.instance?.returnFromAccessibilitySettings(
            targetPackageName = activity.packageName
        )
    }

    private fun startInternal(activity: FragmentActivity, request: StartRequest) {
        stopAndResetSources(release = true)
        stopOverlayService()

        hostActivity = activity
        permissionHostActivity = null
        libId = request.libraryId
        ScreenDetectorSession.attachController(this)
        ScreenDetectorSession.setMode(request.detectionMode)

        try {
            when (request) {
                is StartRequest.ProcessorTest -> {
                    Log.i(TAG, "Using on-device OCR Processor for screen text")
                    val source = ScreenSource(activity)
                    cameraSource = source
                    source.setMachineLearningFrameProcessor(
                        OriginalRecognitionProcessor(activity) { matches ->
                            source.finishCurrentScan {
                                ScreenDetectorSession.publishMatches(matches)
                            }
                        }
                    )
                }
                is StartRequest.Quiz -> {
                    Log.i(TAG, "Using on-device Quiz recognition Processor for Quiz")
                    val source = ScreenSource(activity)
                    cameraSource = source
                    source.setMachineLearningFrameProcessor(
                        QuizRecognitionProcessor(
                            activity,
                            request.quizzes,
                            onMatchesDetected = { matches ->
                                source.finishCurrentScan {
                                    ScreenDetectorSession.publishMatches(matches)
                                }
                            },
                            minMatchScore = PreferenceUtils.getScreenSearchMinMatchScore(activity)
                        )
                    )
                }
                is StartRequest.AccessibilityQuiz -> {
                    Log.i(TAG, "Using accessibility text source for Quiz")
                    accessibilitySource = AccessibilityTextSource(
                        context = activity,
                        quizzes = request.quizzes,
                        onMatchesDetected = ::handleAccessibilityMatches,
                        onPageActivityDetected = ::handleAccessibilityPageActivity
                    )
                }
            }
            maybeStartOverlayService()
            startCurrentSource()
        } catch (e: Exception) {
            Log.e(TAG, "Can not create screen processor.", e)
            Toast.makeText(
                activity.applicationContext,
                "Can not create screen processor: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
            stopScreenDetection()
        }
    }

    private fun startCurrentSource() {
        if (isDetectionRunning) {
            return
        }
        try {
            cameraSource?.start()
            accessibilitySource?.start()
            isDetectionRunning = true
            isDetectionPaused = false
            ScreenDetectorSession.setState(ScreenDetectorSession.DetectionState.RUNNING)
        } catch (e: IOException) {
            Log.e(TAG, "Unable to start screen source.", e)
            stopAndResetSources(release = true)
        } catch (e: Exception) {
            Log.e(TAG, "Unable to start accessibility source.", e)
            hostActivity?.applicationContext?.let { context ->
                Toast.makeText(
                    context,
                    e.message ?: "Unable to start accessibility search",
                    Toast.LENGTH_LONG
                ).show()
            }
            stopAndResetSources(release = true)
        }
    }

    private fun pauseCurrentSource() {
        phaseBeforePause = ScreenDetectorSession.assistanceState.value.phase
        assistanceGeneration++
        assistanceHandler.removeCallbacksAndMessages(null)
        clearPendingNavigationTasks()
        assistanceBusy = false
        waitingForPageFingerprint = null
        latestStableSnapshotVersion = 0
        QuizAccessibilityService.instance?.cancelPendingClicks()
        cameraSource?.pause()
        accessibilitySource?.pause()
        isDetectionRunning = false
        isDetectionPaused = true
        ScreenDetectorSession.setState(ScreenDetectorSession.DetectionState.PAUSED)
        if (assistanceEnabled) {
            ScreenDetectorSession.setAssistanceState(
                isActive = true,
                phase = ScreenDetectorSession.AssistancePhase.PAUSED,
                statusText = "已暂停 · 点击继续"
            )
        }
    }

    private fun resumeCurrentSource() {
        if (!isDetectionPaused) {
            return
        }
        maybeStartOverlayService()
        cameraSource?.resume()
        accessibilitySource?.resume()
        isDetectionRunning = true
        isDetectionPaused = false
        ScreenDetectorSession.setState(ScreenDetectorSession.DetectionState.RUNNING)
        if (assistanceEnabled) {
            if (phaseBeforePause == ScreenDetectorSession.AssistancePhase.WAITING_MANUAL_PAGE) {
                waitForManualPage()
                if (selectedPageAxis != null) {
                    scheduleAssistanceStep(ASSISTANCE_RESUME_DELAY_MS, ::continueWithPageDirection)
                }
            } else {
                ScreenDetectorSession.setAssistanceState(
                    isActive = true,
                    phase = ScreenDetectorSession.AssistancePhase.RECOGNIZING_ANSWERS,
                    statusText = "继续识别当前题目"
                )
                scheduleAssistanceStep(ASSISTANCE_RESUME_DELAY_MS)
            }
        }
    }

    private fun stopAndResetSources(release: Boolean = false) {
        assistanceEnabled = false
        assistanceBusy = false
        waitingForPageFingerprint = null
        selectedPageAxis = null
        phaseBeforePause = ScreenDetectorSession.AssistancePhase.IDLE
        pendingAnswerPlan = null
        pendingAnswerPointIndex = 0
        clearQuestionProgress()
        assistanceGeneration++
        assistanceHandler.removeCallbacksAndMessages(null)
        clearPendingNavigationTasks()
        QuizAccessibilityService.instance?.cancelPendingClicks()
        if (release) {
            cameraSource?.release()
            cameraSource = null
        } else {
            cameraSource?.stop()
        }
        accessibilitySource?.stop()
        if (release) {
            accessibilitySource = null
        }
        isDetectionRunning = false
        isDetectionPaused = false
        ScreenDetectorSession.setState(ScreenDetectorSession.DetectionState.STOPPED)
        ScreenDetectorSession.setMode(ScreenDetectorSession.DetectionMode.NONE)
        ScreenDetectorSession.clearMatches()
        ScreenDetectorSession.clearScreenFrameInfo()
        ScreenDetectorSession.clearDangerousActionBounds()
        ScreenDetectorSession.clearAssistanceState()
    }

    private fun maybeStartOverlayService() {
        val activity = hostActivity ?: return
        val overlayEnabled = hasOverlayPermission(activity)
        val shouldShowControlWindow =
            overlayEnabled &&
                (ScreenDetectorSession.mode.value != ScreenDetectorSession.DetectionMode.ACCESSIBILITY ||
                    accessibilityFloatingWindowEnabled)
        val shouldShowMarkerOverlay = overlayEnabled
        activity.startService(
            Intent(activity, ScreenDetectorService::class.java).apply {
                putExtra(ScreenDetectorService.LIBRARY_ID, libId)
                putExtra(ScreenDetectorService.EXTRA_SHOW_FLOATING_WINDOWS, shouldShowControlWindow)
                putExtra(ScreenDetectorService.EXTRA_SHOW_MARKER_OVERLAY, shouldShowMarkerOverlay)
                putExtra(ScreenDetectorService.EXTRA_ANSWER_DOTS_ONLY, accessibilityAnswerDotsOnlyEnabled)
            }
        )
    }

    private fun stopOverlayService() {
        hostActivity?.stopService(Intent(hostActivity, ScreenDetectorService::class.java))
    }

    override fun stopScreenDetection() {
        val activity = hostActivity
        if (activity == null) {
            stopAndResetSources(release = true)
            ScreenDetectorSession.detachController(this)
            return
        }
        activity.runOnUiThread {
            stopAndResetSources(release = true)
            stopOverlayService()
            ScreenDetectorSession.detachController(this)
            hostActivity = null
            pendingStartRequest = null
            pendingPermissionPrompt = null
            permissionHostActivity = null
        }
    }

    override fun pauseScreenDetection() {
        hostActivity?.runOnUiThread {
            if (isDetectionRunning) {
                pauseCurrentSource()
            }
        }
    }

    override fun resumeScreenDetection() {
        hostActivity?.runOnUiThread {
            if (isDetectionPaused) {
                resumeCurrentSource()
            }
        }
    }

    override fun retryScreenDetectionOnce() {
        hostActivity?.runOnUiThread {
            if (isDetectionPaused) {
                cameraSource?.retryOnce()
                accessibilitySource?.retryOnce()
            }
        }
    }

    override fun toggleAnswerAssistance() {
        hostActivity?.runOnUiThread {
            if (ScreenDetectorSession.mode.value != ScreenDetectorSession.DetectionMode.ACCESSIBILITY) {
                return@runOnUiThread
            }
            if (assistanceEnabled) {
                stopAnswerAssistance()
                return@runOnUiThread
            }
            if (!isDetectionRunning) {
                return@runOnUiThread
            }
            prepareQuestionProgressForRestart()
            assistanceEnabled = true
            assistanceBusy = false
            waitingForPageFingerprint = null
            selectedPageAxis = null
            phaseBeforePause = ScreenDetectorSession.AssistancePhase.IDLE
            assistanceGeneration++
            assistanceHandler.removeCallbacksAndMessages(null)
            clearPendingNavigationTasks()
            ScreenDetectorSession.setAssistanceState(
                isActive = true,
                phase = ScreenDetectorSession.AssistancePhase.RECOGNIZING_ANSWERS,
                statusText = "连续辅助已启动"
            )
            runAssistanceStep()
        }
    }

    private fun stopAnswerAssistance() {
        assistanceEnabled = false
        assistanceBusy = false
        waitingForPageFingerprint = null
        selectedPageAxis = null
        phaseBeforePause = ScreenDetectorSession.AssistancePhase.IDLE
        assistanceGeneration++
        assistanceHandler.removeCallbacksAndMessages(null)
        clearPendingNavigationTasks()
        QuizAccessibilityService.instance?.cancelPendingClicks()
        ScreenDetectorSession.clearAssistanceState()
    }

    private fun prepareQuestionProgressForRestart() {
        val plan = ScreenDetectorSession.buildAnswerClickPlan()
        val currentTargets = plan?.targets.orEmpty()
        val hasTrackedQuestion = answeredQuestionFingerprints.isNotEmpty() ||
            pendingAnswerPlan != null
        val hasCurrentOverlap = currentTargets.any { target ->
            target.questionFingerprint in answeredQuestionFingerprints ||
                pendingAnswerPlan?.targets?.any {
                    it.questionFingerprint == target.questionFingerprint &&
                        it.fingerprint == target.fingerprint
                } == true
        }
        if (hasTrackedQuestion && !hasCurrentOverlap) {
            pendingAnswerPlan = null
            pendingAnswerPointIndex = 0
            clearQuestionProgress()
            ScreenDetectorSession.resetAnswerClickState()
        }
    }

    override fun swipePageLeft() {
        selectPageDirectionAndSwipe(QuizAccessibilityService.PageAxis.HORIZONTAL)
    }

    override fun swipePageUp() {
        selectPageDirectionAndSwipe(QuizAccessibilityService.PageAxis.VERTICAL)
    }

    private fun handleAccessibilityMatches(
        matches: List<com.virin.visionquiz.util.QuizGraphicItem>,
        snapshotVersion: Int,
        dangerousActionBounds: List<android.graphics.Rect>
    ) {
        latestStableSnapshotVersion = snapshotVersion
        ScreenDetectorSession.publishDangerousActionBounds(dangerousActionBounds)
        ScreenDetectorSession.publishMatches(matches)
        if (!assistanceEnabled || !isDetectionRunning) {
            return
        }
        val plan = ScreenDetectorSession.buildAnswerClickPlan()
        val currentQuestionFingerprints =
            plan?.targets?.mapTo(mutableSetOf()) { it.questionFingerprint }.orEmpty()

        val expectedOldFingerprint = waitingForPageFingerprint
        if (expectedOldFingerprint != null) {
            val currentFingerprint = ScreenDetectorSession.buildPageFingerprint(matches)
            if (currentFingerprint != null && currentFingerprint != expectedOldFingerprint) {
                val preservesAnsweredQuestions =
                    currentQuestionFingerprints.any(answeredQuestionFingerprints::contains)
                handleNewQuestionContent(preservesAnsweredQuestions)
            }
            return
        }
        if (!assistanceBusy &&
            answeredQuestionFingerprints.isNotEmpty() &&
            currentQuestionFingerprints.isNotEmpty() &&
            currentQuestionFingerprints.none(answeredQuestionFingerprints::contains)
        ) {
            handleNewQuestionContent(preserveAnsweredQuestions = false)
            return
        }
        if (!assistanceBusy) {
            runAssistanceStep()
        }
    }

    private fun handleNewQuestionContent(preserveAnsweredQuestions: Boolean) {
        val previousPhase = ScreenDetectorSession.assistanceState.value.phase
        assistanceGeneration++
        clearPendingNavigationTasks()
        waitingForPageFingerprint = null
        if (previousPhase == ScreenDetectorSession.AssistancePhase.SWIPING) {
            QuizAccessibilityService.instance?.cancelPendingClicks()
        }
        assistanceBusy = false
        pendingAnswerPlan = null
        pendingAnswerPointIndex = 0
        if (!preserveAnsweredQuestions) {
            clearQuestionProgress()
            ScreenDetectorSession.resetAnswerClickState()
        }
        ScreenDetectorSession.setAssistanceState(
            isActive = true,
            phase = ScreenDetectorSession.AssistancePhase.RECOGNIZING_ANSWERS,
            statusText = "已识别新题，准备作答"
        )
        scheduleAssistanceStep(NEW_PAGE_SETTLE_DELAY_MS)
    }

    private fun runAssistanceStep() {
        if (!assistanceEnabled || !isDetectionRunning || assistanceBusy ||
            waitingForPageFingerprint != null
        ) {
            return
        }
        val plan = ScreenDetectorSession.buildAnswerClickPlan()
        if (plan == null) {
            ScreenDetectorSession.setAssistanceState(
                isActive = true,
                phase = ScreenDetectorSession.AssistancePhase.RECOGNIZING_ANSWERS,
                statusText = "等待识别正确选项"
            )
            return
        }
        val answerTargets = plan.targets.filter {
            it.isComplete && it.questionFingerprint !in answeredQuestionFingerprints
        }
        if (answerTargets.isEmpty()) {
            if (hasUnansweredDangerousActionBlockedTarget(plan)) {
                handleDangerousActionBlocked()
                return
            }
            val clippedTarget = plan.bottomClippedTarget?.takeIf {
                it.questionFingerprint !in answeredQuestionFingerprints
            }
            if (clippedTarget != null) {
                waitForManualPage(
                    "最后一题选项未完整显示，请手动上滑",
                    ScreenDetectorSession.AssistanceIndicator.ERROR
                )
            } else {
                continueWithPageDirection()
            }
            return
        }
        if (shouldWaitForAnswerOverlayRender() &&
            !ScreenDetectorSession.isCurrentOverlayRendered()
        ) {
            ScreenDetectorSession.setAssistanceState(
                isActive = true,
                phase = ScreenDetectorSession.AssistancePhase.RECOGNIZING_ANSWERS,
                statusText = "正在更新题目框"
            )
            scheduleAssistanceStep(OVERLAY_RENDER_RETRY_DELAY_MS)
            return
        }
        val freshExecution = buildAnswerExecution(
            answerTargets,
            plan.contentFingerprint,
            latestStableSnapshotVersion
        )
        val resumableExecution = pendingAnswerPlan?.takeIf { pending ->
            pendingAnswerPointIndex > 0 &&
                pending.snapshotVersion == latestStableSnapshotVersion &&
                pending.targets
                    .filter { it.questionFingerprint !in answeredQuestionFingerprints }
                    .all { pendingTarget ->
                        answerTargets.any {
                            it.questionFingerprint == pendingTarget.questionFingerprint &&
                                it.fingerprint == pendingTarget.fingerprint
                        }
                    }
        }
        val execution = resumableExecution ?: freshExecution
        if (resumableExecution == null && pendingAnswerPlan?.fingerprint != execution.fingerprint) {
            pendingAnswerPlan = freshExecution
            pendingAnswerPointIndex = 0
        }
        if (!ScreenDetectorSession.beginAnswerClick(execution.fingerprint)) {
            return
        }
        val service = QuizAccessibilityService.instance
        if (service == null) {
            ScreenDetectorSession.finishAnswerClick(execution.fingerprint)
            pauseAssistance("无障碍服务已断开")
            return
        }
        val activity = hostActivity
        if (activity == null) {
            ScreenDetectorSession.finishAnswerClick(execution.fingerprint)
            pauseAssistance("应用页面已断开")
            return
        }
        val frameInfo = ScreenDetectorSession.screenFrameInfo.value
        if (frameInfo == null) {
            ScreenDetectorSession.finishAnswerClick(execution.fingerprint)
            pauseAssistance("屏幕尺寸不可用")
            return
        }

        assistanceBusy = true
        val generation = assistanceGeneration
        ScreenDetectorSession.setAssistanceState(
            isActive = true,
            phase = ScreenDetectorSession.AssistancePhase.CLICKING_ANSWERS,
            statusText = "正在回答已完整显示的题目"
        )
        service.clickPointsSequentially(
            points = execution.points,
            startIndex = pendingAnswerPointIndex,
            excludedPackageName = activity.packageName,
            screenBounds = android.graphics.Rect(0, 0, frameInfo.width, frameInfo.height),
            shouldContinue = {
                generation == assistanceGeneration &&
                    isAnswerExecutionCurrent(execution)
            },
            onPointCompleted = { nextIndex ->
                if (generation == assistanceGeneration) {
                    pendingAnswerPointIndex = nextIndex
                    execution.targetEndIndexes
                        .filter { (_, endIndex) -> nextIndex >= endIndex }
                        .forEach { (questionFingerprint, _) ->
                            answeredQuestionFingerprints.add(questionFingerprint)
                        }
                }
            }
        ) { result ->
            if (result == QuizAccessibilityService.ClickSequenceResult.COMPLETED) {
                ScreenDetectorSession.markAnswerClickDispatched(
                    execution.fingerprint,
                    execution.contentFingerprint
                )
                pendingAnswerPlan = null
                pendingAnswerPointIndex = 0
            }
            ScreenDetectorSession.finishAnswerClick(execution.fingerprint)
            if (generation != assistanceGeneration) {
                return@clickPointsSequentially
            }
            assistanceBusy = false
            if (result == QuizAccessibilityService.ClickSequenceResult.BLOCKED_BY_DANGEROUS_ACTION ||
                ScreenDetectorSession.buildAnswerClickPlan()
                    ?.let(::hasUnansweredDangerousActionBlockedTarget) == true
            ) {
                pendingAnswerPlan = null
                pendingAnswerPointIndex = 0
                accessibilitySource?.requestFreshScan()
                handleDangerousActionBlocked()
                return@clickPointsSequentially
            }
            if (result != QuizAccessibilityService.ClickSequenceResult.COMPLETED) {
                if (isDetectionRunning) {
                    pauseAssistance("答案点击被中断")
                }
                return@clickPointsSequentially
            }
            ScreenDetectorSession.setAssistanceState(
                isActive = true,
                phase = ScreenDetectorSession.AssistancePhase.RECOGNIZING_ANSWERS,
                statusText = "正在检查下一道题"
            )
            scheduleAutoAdvance(ANSWER_SETTLE_DELAY_MS, ::runAssistanceStep)
        }
    }

    private fun hasUnansweredDangerousActionBlockedTarget(
        plan: ScreenDetectorSession.AnswerClickPlan
    ): Boolean {
        return plan.targets.any {
            it.isBlockedByDangerousAction &&
                it.questionFingerprint !in answeredQuestionFingerprints
        }
    }

    private fun handleDangerousActionBlocked() {
        if (selectedPageAxis == QuizAccessibilityService.PageAxis.VERTICAL) {
            continueWithPageDirection()
            return
        }
        waitForManualPage(
            "答案被底部操作按钮遮挡，请选择上滑",
            ScreenDetectorSession.AssistanceIndicator.ERROR
        )
    }

    private fun shouldWaitForAnswerOverlayRender(): Boolean {
        val activity = hostActivity ?: return false
        return hasOverlayPermission(activity)
    }

    private fun buildAnswerExecution(
        targets: List<ScreenDetectorSession.AnswerTarget>,
        contentFingerprint: String,
        snapshotVersion: Int
    ): AnswerExecution {
        val points = mutableListOf<android.graphics.Point>()
        val targetEndIndexes = mutableListOf<Pair<String, Int>>()
        targets.forEach { target ->
            points.addAll(target.points)
            targetEndIndexes.add(target.questionFingerprint to points.size)
        }
        return AnswerExecution(
            targets = targets,
            points = points,
            fingerprint = targets.joinToString("|") { it.fingerprint },
            contentFingerprint = contentFingerprint,
            snapshotVersion = snapshotVersion,
            targetEndIndexes = targetEndIndexes
        )
    }

    private fun isAnswerExecutionCurrent(execution: AnswerExecution): Boolean {
        if (latestStableSnapshotVersion != execution.snapshotVersion) {
            return false
        }
        val plan = ScreenDetectorSession.buildAnswerClickPlan() ?: return false
        if (plan.contentFingerprint != execution.contentFingerprint) {
            return false
        }
        val currentTargetFingerprints = plan.targets
            .filter { it.isComplete }
            .associate { it.questionFingerprint to it.fingerprint }
        return execution.targets.all { target ->
            currentTargetFingerprints[target.questionFingerprint] == target.fingerprint
        }
    }

    private fun handleAccessibilityPageActivity() {
        latestStableSnapshotVersion = 0
        ScreenDetectorSession.clearMatches()
        ScreenDetectorSession.clearAnnotationBounds()
        ScreenDetectorSession.clearDangerousActionBounds()
        if (!assistanceEnabled || !isDetectionRunning) {
            return
        }
        cancelPendingAutoAdvance()
        if (ScreenDetectorSession.assistanceState.value.phase ==
            ScreenDetectorSession.AssistancePhase.CLICKING_ANSWERS
        ) {
            assistanceGeneration++
            QuizAccessibilityService.instance?.cancelPendingClicks()
            assistanceBusy = false
            pendingAnswerPlan = null
            pendingAnswerPointIndex = 0
            ScreenDetectorSession.resetAnswerClickState()
        }
        if (ScreenDetectorSession.assistanceState.value.phase !=
            ScreenDetectorSession.AssistancePhase.SWIPING
        ) {
            ScreenDetectorSession.setAssistanceState(
                isActive = true,
                phase = ScreenDetectorSession.AssistancePhase.RECOGNIZING_ANSWERS,
                statusText = "页面变动中，等待稳定扫描"
            )
        }
    }

    private fun continueWithPageDirection() {
        val selectedAxis = selectedPageAxis
        if (selectedAxis == null) {
            waitForManualPage()
        } else {
            waitForManualPage()
            scheduleAutoAdvance(AUTO_PAGE_DELAY_MS) {
                performPageSwipe(selectedAxis)
            }
        }
    }

    private fun waitForManualPage(
        statusOverride: String? = null,
        indicator: ScreenDetectorSession.AssistanceIndicator? = null
    ) {
        if (!assistanceEnabled || !isDetectionRunning || assistanceBusy) {
            return
        }
        val selectedAxis = selectedPageAxis
        ScreenDetectorSession.setAssistanceState(
            isActive = true,
            phase = ScreenDetectorSession.AssistancePhase.WAITING_MANUAL_PAGE,
            pageDirection = selectedAxis.toSessionPageDirection(),
            indicator = indicator ?: when (selectedAxis) {
                QuizAccessibilityService.PageAxis.HORIZONTAL ->
                    ScreenDetectorSession.AssistanceIndicator.SWIPE_LEFT
                QuizAccessibilityService.PageAxis.VERTICAL ->
                    ScreenDetectorSession.AssistanceIndicator.SWIPE_UP
                null -> ScreenDetectorSession.AssistanceIndicator.TOUCH
            },
            statusText = statusOverride ?: when (selectedAxis) {
                QuizAccessibilityService.PageAxis.HORIZONTAL -> "已选择左滑，准备自动翻页"
                QuizAccessibilityService.PageAxis.VERTICAL -> "已选择上滑，准备自动翻页"
                null -> "等待页面刷新，可选择左滑或上滑"
            }
        )
    }

    private fun selectPageDirectionAndSwipe(axis: QuizAccessibilityService.PageAxis) {
        hostActivity?.runOnUiThread {
            if (assistanceEnabled && selectedPageAxis == axis) {
                clearSelectedPageDirection()
                return@runOnUiThread
            }
            if (!assistanceEnabled || !isDetectionRunning || assistanceBusy ||
                ScreenDetectorSession.assistanceState.value.phase !=
                ScreenDetectorSession.AssistancePhase.WAITING_MANUAL_PAGE
            ) {
                return@runOnUiThread
            }
            cancelPendingAutoAdvance()
            selectedPageAxis = axis
            performPageSwipe(axis)
        }
    }

    private fun performPageSwipe(axis: QuizAccessibilityService.PageAxis) {
        if (!assistanceEnabled || !isDetectionRunning || assistanceBusy ||
            ScreenDetectorSession.assistanceState.value.phase !=
            ScreenDetectorSession.AssistancePhase.WAITING_MANUAL_PAGE
        ) {
            return
        }
        val pageFingerprint = ScreenDetectorSession.buildPageFingerprint()
        val frameInfo = ScreenDetectorSession.screenFrameInfo.value
        val service = QuizAccessibilityService.instance
        if (pageFingerprint == null || frameInfo == null) {
            ScreenDetectorSession.setAssistanceState(
                isActive = true,
                phase = ScreenDetectorSession.AssistancePhase.WAITING_MANUAL_PAGE,
                pageDirection = axis.toSessionPageDirection(),
                indicator = ScreenDetectorSession.AssistanceIndicator.ERROR,
                statusText = "翻页未生效，请重试"
            )
            return
        }
        if (service == null) {
            pauseAssistance("无障碍服务已断开")
            return
        }

        assistanceBusy = true
        val generation = assistanceGeneration
        val verticalTargetPosition = if (
            axis == QuizAccessibilityService.PageAxis.VERTICAL &&
            PreferenceUtils.shouldUseSmartAccessibilityVerticalSwipe(service)
        ) {
            ScreenDetectorSession.buildAnswerClickPlan()
                ?.targets
                ?.takeIf { it.size >= MIN_QUESTIONS_FOR_ALIGNED_VERTICAL_SWIPE }
                ?.maxByOrNull { it.questionRect.top }
                ?.questionRect
                ?.top
        } else {
            null
        }
        ScreenDetectorSession.setAssistanceState(
            isActive = true,
            phase = ScreenDetectorSession.AssistancePhase.SWIPING,
            pageDirection = axis.toSessionPageDirection(),
            statusText = if (axis == QuizAccessibilityService.PageAxis.HORIZONTAL) {
                "正在自动左滑"
            } else {
                "正在自动上滑"
            }
        )
        service.swipePage(
            screenBounds = android.graphics.Rect(0, 0, frameInfo.width, frameInfo.height),
            overlayBounds = ScreenDetectorSession.getOverlayBoundsSnapshot(),
            axis = axis,
            verticalTargetPosition = verticalTargetPosition
        ) { success ->
            if (generation != assistanceGeneration) {
                return@swipePage
            }
            assistanceBusy = false
            if (!success) {
                ScreenDetectorSession.setAssistanceState(
                    isActive = true,
                    phase = ScreenDetectorSession.AssistancePhase.WAITING_MANUAL_PAGE,
                    pageDirection = axis.toSessionPageDirection(),
                    indicator = ScreenDetectorSession.AssistanceIndicator.ERROR,
                    statusText = "翻页未生效，请重试"
                )
                return@swipePage
            }
            waitingForPageFingerprint = pageFingerprint
            ScreenDetectorSession.setAssistanceState(
                isActive = true,
                phase = ScreenDetectorSession.AssistancePhase.WAITING_NEW_PAGE,
                pageDirection = axis.toSessionPageDirection(),
                statusText = "等待识别新题"
            )
            accessibilitySource?.requestFreshScan()
            schedulePageChangeTimeout(generation, pageFingerprint)
        }
    }

    private fun schedulePageChangeTimeout(generation: Int, oldFingerprint: String) {
        pendingPageChangeTimeoutRunnable?.let(assistanceHandler::removeCallbacks)
        val runnable = Runnable {
            pendingPageChangeTimeoutRunnable = null
            if (generation == assistanceGeneration &&
                waitingForPageFingerprint == oldFingerprint
            ) {
                waitingForPageFingerprint = null
                assistanceBusy = false
                ScreenDetectorSession.setAssistanceState(
                    isActive = true,
                    phase = ScreenDetectorSession.AssistancePhase.WAITING_MANUAL_PAGE,
                    pageDirection = selectedPageAxis.toSessionPageDirection(),
                    indicator = ScreenDetectorSession.AssistanceIndicator.ERROR,
                    statusText = "翻页未生效，仍在等待页面刷新"
                )
            }
        }
        pendingPageChangeTimeoutRunnable = runnable
        assistanceHandler.postDelayed(runnable, PAGE_CHANGE_TIMEOUT_MS)
    }

    private fun scheduleAutoAdvance(delayMs: Long, step: () -> Unit) {
        cancelPendingAutoAdvance()
        val generation = assistanceGeneration
        val runnable = Runnable {
            pendingAutoAdvanceRunnable = null
            if (generation == assistanceGeneration) {
                step()
            }
        }
        pendingAutoAdvanceRunnable = runnable
        assistanceHandler.postDelayed(runnable, delayMs)
    }

    private fun cancelPendingAutoAdvance() {
        pendingAutoAdvanceRunnable?.let(assistanceHandler::removeCallbacks)
        pendingAutoAdvanceRunnable = null
    }

    private fun clearPendingNavigationTasks() {
        cancelPendingAutoAdvance()
        pendingPageChangeTimeoutRunnable?.let(assistanceHandler::removeCallbacks)
        pendingPageChangeTimeoutRunnable = null
    }

    private fun clearQuestionProgress() {
        answeredQuestionFingerprints.clear()
    }

    private fun scheduleAssistanceStep(
        delayMs: Long,
        step: () -> Unit = ::runAssistanceStep
    ) {
        val generation = assistanceGeneration
        assistanceHandler.postDelayed(
            {
                if (generation == assistanceGeneration) {
                    step()
                }
            },
            delayMs
        )
    }

    private fun pauseAssistance(reason: String) {
        if (!assistanceEnabled) {
            return
        }
        pauseCurrentSource()
        ScreenDetectorSession.setAssistanceState(
            isActive = true,
            phase = ScreenDetectorSession.AssistancePhase.PAUSED,
            indicator = ScreenDetectorSession.AssistanceIndicator.ERROR,
            statusText = "$reason · 已暂停"
        )
    }

    private fun clearSelectedPageDirection() {
        selectedPageAxis = null
        waitingForPageFingerprint = null
        clearPendingNavigationTasks()
        val state = ScreenDetectorSession.assistanceState.value
        if (state.isActive) {
            if (state.phase == ScreenDetectorSession.AssistancePhase.SWIPING) {
                assistanceGeneration++
                QuizAccessibilityService.instance?.cancelPendingClicks()
                assistanceBusy = false
            }
            ScreenDetectorSession.setAssistanceState(
                isActive = true,
                phase = if (state.phase in WAITING_FOR_NEXT_QUESTION_PHASES) {
                    ScreenDetectorSession.AssistancePhase.WAITING_MANUAL_PAGE
                } else {
                    state.phase
                },
                pageDirection = null,
                statusText = if (state.phase in WAITING_FOR_NEXT_QUESTION_PHASES) {
                    "等待页面刷新，可选择左滑或上滑"
                } else {
                    state.statusText
                }
            )
        }
    }

    private fun QuizAccessibilityService.PageAxis?.toSessionPageDirection():
        ScreenDetectorSession.PageDirection? {
        return when (this) {
            QuizAccessibilityService.PageAxis.HORIZONTAL -> ScreenDetectorSession.PageDirection.LEFT
            QuizAccessibilityService.PageAxis.VERTICAL -> ScreenDetectorSession.PageDirection.UP
            null -> null
        }
    }

    private fun ensureStartPermissions(
        activity: FragmentActivity,
        request: StartRequest
    ): Boolean {
        if (!request.requiresAccessibility && !commonROMPermissionCheck(activity)) {
            pendingStartRequest = request
            pendingPermissionPrompt = PendingPermissionPrompt.OVERLAY
            permissionHostActivity = activity
            requestOverlayPermission(activity)
            return false
        }
        if (request.requiresAccessibility && !AccessibilityPermissionHelper.isServiceEnabled(activity)) {
            pendingStartRequest = request
            permissionHostActivity = activity
            if (pendingPermissionPrompt != PendingPermissionPrompt.ACCESSIBILITY) {
                pendingPermissionPrompt = PendingPermissionPrompt.ACCESSIBILITY
                showAccessibilityPermissionDialog(activity)
            }
            return false
        }
        return true
    }

    private fun showAccessibilityPermissionDialog(activity: FragmentActivity) {
        if (accessibilityDialog?.isShowing == true) {
            return
        }
        val appName = activity.getString(R.string.app_name)
        val isAccessibilityEnabled = AccessibilityPermissionHelper.isServiceEnabled(activity)
        val hasShownIntro = AccessibilityPermissionHelper.hasShownIntro(activity)
        val titleRes = if (isAccessibilityEnabled) {
            R.string.accessibility_search_intro_title
        } else if (hasShownIntro) {
            R.string.accessibility_permission_required
        } else {
            R.string.accessibility_search_intro_title
        }
        val messageRes = if (isAccessibilityEnabled) {
            R.string.accessibility_search_ready_message
        } else if (hasShownIntro) {
            R.string.accessibility_search_prompt_message
        } else {
            R.string.accessibility_search_intro_message
        }
        if (!hasShownIntro) {
            AccessibilityPermissionHelper.markIntroShown(activity)
        }
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(titleRes, appName))
            .create()
        accessibilityDialog = dialog
        dialog.setOnDismissListener {
            if (accessibilityDialog === dialog) {
                accessibilityDialog = null
            }
        }
        dialog.setOnCancelListener {
            clearPendingAccessibilityStart()
        }
        dialog.setView(
            createAccessibilityPermissionDialogView(
                activity,
                activity.getString(messageRes, appName),
                dialog
            )
        )
        dialog.show()
    }

    private fun createAccessibilityPermissionDialogView(
        activity: FragmentActivity,
        message: String,
        dialog: DialogInterface
    ): LinearLayout {
        val horizontalPadding = dpToPx(activity, 24)
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(horizontalPadding, dpToPx(activity, 6), horizontalPadding, dpToPx(activity, 8))
        }
        layout.addView(
            TextView(activity).apply {
                text = message
                setTextColor(
                    MaterialColors.getColor(
                        this,
                        com.google.android.material.R.attr.colorOnSurfaceVariant
                    )
                )
                textSize = 14f
                setLineSpacing(dpToPx(activity, 2).toFloat(), 1f)
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        val notificationButton = createPermissionStatusButton(activity)
        layout.addView(
            notificationButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(activity, 16)
            }
        )
        val overlayButton = createPermissionStatusButton(activity)
        layout.addView(
            overlayButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(activity, 8)
            }
        )
        val floatingWindowCheckBox = MaterialCheckBox(activity).apply {
            setText(R.string.accessibility_search_show_overlay_window)
            isChecked = accessibilityFloatingWindowEnabled
        }
        layout.addView(
            floatingWindowCheckBox,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(activity, 14)
            }
        )
        layout.addView(
            TextView(activity).apply {
                setText(R.string.accessibility_search_show_overlay_window_summary)
                setTextColor(
                    MaterialColors.getColor(
                        this,
                        com.google.android.material.R.attr.colorOnSurfaceVariant
                    )
                )
                textSize = 12f
                setLineSpacing(dpToPx(activity, 2).toFloat(), 1f)
                setPadding(dpToPx(activity, 32), 0, 0, 0)
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        val answerDotsOnlyCheckBox = MaterialCheckBox(activity).apply {
            setText(R.string.accessibility_search_answer_dots_only)
            isChecked = accessibilityAnswerDotsOnlyEnabled
        }
        layout.addView(
            answerDotsOnlyCheckBox,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(activity, 12)
            }
        )
        layout.addView(
            TextView(activity).apply {
                setText(R.string.accessibility_search_answer_dots_only_summary)
                setTextColor(
                    MaterialColors.getColor(
                        this,
                        com.google.android.material.R.attr.colorOnSurfaceVariant
                    )
                )
                textSize = 12f
                setLineSpacing(dpToPx(activity, 2).toFloat(), 1f)
                setPadding(dpToPx(activity, 32), 0, 0, 0)
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        val actionRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        layout.addView(
            actionRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(activity, 18)
            }
        )
        val cancelButton = MaterialButton(
            activity,
            null,
            com.google.android.material.R.attr.borderlessButtonStyle
        ).apply {
            setText(R.string.cancel)
            setOnClickListener {
                clearPendingAccessibilityStart()
                dialog.dismiss()
            }
        }
        actionRow.addView(
            cancelButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        val enableButton = MaterialButton(
            activity,
            null,
            com.google.android.material.R.attr.materialButtonStyle
        ).apply {
            setOnClickListener {
                handleAccessibilityDialogPrimaryAction(activity, dialog)
            }
        }
        actionRow.addView(
            enableButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = dpToPx(activity, 8)
            }
        )
        notificationButton.setOnClickListener {
            requestNotificationPermissionOrOpenSettings(activity)
            updateAccessibilityDialogPermissionButtons(
                activity,
                notificationButton,
                overlayButton,
                enableButton,
                floatingWindowCheckBox
            )
            scheduleNotificationPermissionRefresh(
                activity,
                notificationButton,
                overlayButton,
                enableButton,
                floatingWindowCheckBox
            )
        }
        overlayButton.setOnClickListener {
            requestOverlayPermission(activity)
            dialog.dismiss()
        }
        floatingWindowCheckBox.setOnCheckedChangeListener { _, isChecked ->
            accessibilityFloatingWindowEnabled = isChecked
            PreferenceUtils.setShowAccessibilityFloatingControl(activity, isChecked)
            updateAccessibilityDialogPermissionButtons(
                activity,
                notificationButton,
                overlayButton,
                enableButton,
                floatingWindowCheckBox
            )
        }
        answerDotsOnlyCheckBox.setOnCheckedChangeListener { _, isChecked ->
            accessibilityAnswerDotsOnlyEnabled = isChecked
            PreferenceUtils.setAccessibilitySimplifiedAnswerDisplay(activity, isChecked)
        }
        updateAccessibilityDialogPermissionButtons(
            activity,
            notificationButton,
            overlayButton,
            enableButton,
            floatingWindowCheckBox
        )
        return layout
    }

    private fun createPermissionStatusButton(activity: FragmentActivity): MaterialButton {
        return MaterialButton(
            activity,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        )
    }

    private fun scheduleNotificationPermissionRefresh(
        activity: FragmentActivity,
        notificationButton: MaterialButton,
        overlayButton: MaterialButton,
        enableButton: MaterialButton,
        floatingWindowCheckBox: MaterialCheckBox,
        attemptsRemaining: Int = NOTIFICATION_PERMISSION_REFRESH_ATTEMPTS
    ) {
        if (attemptsRemaining <= 0 || !notificationButton.isAttachedToWindow) {
            return
        }
        notificationButton.postDelayed({
            if (!notificationButton.isAttachedToWindow) {
                return@postDelayed
            }
            updateAccessibilityDialogPermissionButtons(
                activity,
                notificationButton,
                overlayButton,
                enableButton,
                floatingWindowCheckBox
            )
            if (!hasNotificationPermission(activity)) {
                scheduleNotificationPermissionRefresh(
                    activity,
                    notificationButton,
                    overlayButton,
                    enableButton,
                    floatingWindowCheckBox,
                    attemptsRemaining - 1
                )
            }
        }, NOTIFICATION_PERMISSION_REFRESH_INTERVAL_MS)
    }

    private fun updateAccessibilityDialogPermissionButtons(
        activity: FragmentActivity,
        notificationButton: MaterialButton,
        overlayButton: MaterialButton,
        enableButton: MaterialButton,
        floatingWindowCheckBox: MaterialCheckBox
    ) {
        val notificationsEnabled = hasNotificationPermission(activity)
        val overlayEnabled = hasOverlayPermission(activity)
        notificationButton.setText(
            if (notificationsEnabled) {
                R.string.accessibility_search_notifications_enabled
            } else if (hasRequestedNotificationPermission(activity)) {
                R.string.accessibility_search_open_notification_settings
            } else {
                R.string.accessibility_search_enable_notifications
            }
        )
        notificationButton.isEnabled = !notificationsEnabled
        overlayButton.setText(
            if (overlayEnabled) {
                R.string.accessibility_search_overlay_enabled
            } else {
                R.string.accessibility_search_enable_overlay
            }
        )
        overlayButton.isEnabled = !overlayEnabled
        enableButton.setText(
            if (AccessibilityPermissionHelper.isServiceEnabled(activity)) {
                R.string.accessibility_search_start_cta
            } else {
                R.string.accessibility_search_enable_cta
            }
        )
        enableButton.isEnabled = notificationsEnabled || overlayEnabled
    }

    private fun handleAccessibilityDialogPrimaryAction(
        activity: FragmentActivity,
        dialog: DialogInterface
    ) {
        if (!AccessibilityPermissionHelper.isServiceEnabled(activity)) {
            dialog.dismiss()
            AccessibilityPermissionHelper.openAccessibilitySettings(activity)
            return
        }
        val request = pendingStartRequest ?: return
        pendingStartRequest = null
        pendingPermissionPrompt = null
        permissionHostActivity = null
        dialog.dismiss()
        startInternal(activity, request)
    }

    private fun clearPendingAccessibilityStart() {
        if (pendingStartRequest?.requiresAccessibility == true) {
            pendingStartRequest = null
            pendingPermissionPrompt = null
            permissionHostActivity = null
        }
    }

    private fun hasNotificationPermission(activity: FragmentActivity): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestNotificationPermissionOrOpenSettings(activity: FragmentActivity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            hasNotificationPermission(activity)
        ) {
            return
        }
        if (!canRequestNotificationPermission(activity)) {
            openNotificationSettings(activity)
            return
        }
        markNotificationPermissionRequested(activity)
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            REQUEST_NOTIFICATION_CODE
        )
    }

    private fun canRequestNotificationPermission(activity: FragmentActivity): Boolean {
        return !hasRequestedNotificationPermission(activity) ||
            ActivityCompat.shouldShowRequestPermissionRationale(
                activity,
                Manifest.permission.POST_NOTIFICATIONS
            )
    }

    private fun hasRequestedNotificationPermission(activity: FragmentActivity): Boolean {
        return activity
            .getSharedPreferences(PermissionManager.PERMISSION_STATE_PREFS, Context.MODE_PRIVATE)
            .getBoolean(PermissionManager.KEY_NOTIFICATION_PERMISSION_REQUESTED, false)
    }

    private fun markNotificationPermissionRequested(activity: FragmentActivity) {
        activity
            .getSharedPreferences(PermissionManager.PERMISSION_STATE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PermissionManager.KEY_NOTIFICATION_PERMISSION_REQUESTED, true)
            .apply()
    }

    private fun openNotificationSettings(activity: FragmentActivity) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${activity.packageName}")
            }
        }
        runCatching {
            activity.startActivity(intent)
        }.onFailure {
            activity.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${activity.packageName}")
                }
            )
        }
    }

    private fun hasOverlayPermission(activity: FragmentActivity): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            Settings.canDrawOverlays(activity)
    }

    private fun requestOverlayPermission(activity: FragmentActivity) {
        pendingPermissionPrompt = PendingPermissionPrompt.OVERLAY
        permissionHostActivity = activity
        Toast.makeText(activity, "请允许悬浮窗权限", Toast.LENGTH_LONG).show()
        activity.startActivityForResult(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = Uri.parse("package:${activity.packageName}")
            },
            REQUEST_FLOAT_CODE
        )
    }

    private fun dpToPx(context: Context, dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }

    private fun commonROMPermissionCheck(context: Context?): Boolean {
        var result = true
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                val clazz: Class<*> = Settings::class.java
                val canDrawOverlays =
                    clazz.getDeclaredMethod("canDrawOverlays", Context::class.java)
                result = canDrawOverlays.invoke(null, context) as Boolean
            } catch (e: Exception) {
                Log.e(TAG, Log.getStackTraceString(e))
            }
        }
        return result
    }

    private sealed class StartRequest(
        val libraryId: Int,
        val requiresAccessibility: Boolean = false,
        val detectionMode: ScreenDetectorSession.DetectionMode
    ) {
        data class Quiz(
            val libId: Int,
            val quizzes: LiveData<List<com.virin.visionquiz.dao.Quiz>>
        ) : StartRequest(libId, detectionMode = ScreenDetectorSession.DetectionMode.SCREEN_OCR)

        data class AccessibilityQuiz(
            val libId: Int,
            val quizzes: LiveData<List<com.virin.visionquiz.dao.Quiz>>
        ) : StartRequest(
            libId,
            requiresAccessibility = true,
            detectionMode = ScreenDetectorSession.DetectionMode.ACCESSIBILITY
        )

        object ProcessorTest : StartRequest(
            0,
            detectionMode = ScreenDetectorSession.DetectionMode.SCREEN_OCR
        )
    }

    private enum class PendingPermissionPrompt {
        OVERLAY,
        ACCESSIBILITY
    }

    private const val REQUEST_FLOAT_CODE = 1001
    private const val REQUEST_NOTIFICATION_CODE = 1002
    private const val NOTIFICATION_PERMISSION_REFRESH_INTERVAL_MS = 500L
    private const val NOTIFICATION_PERMISSION_REFRESH_ATTEMPTS = 20
    private const val ANSWER_SETTLE_DELAY_MS = 550L
    private const val AUTO_PAGE_DELAY_MS = 120L
    private const val NEW_PAGE_SETTLE_DELAY_MS = 240L
    private const val OVERLAY_RENDER_RETRY_DELAY_MS = 48L
    private const val ASSISTANCE_RESUME_DELAY_MS = 220L
    private const val PAGE_CHANGE_TIMEOUT_MS = 3_500L
    private val WAITING_FOR_NEXT_QUESTION_PHASES = setOf(
        ScreenDetectorSession.AssistancePhase.WAITING_MANUAL_PAGE,
        ScreenDetectorSession.AssistancePhase.SWIPING,
        ScreenDetectorSession.AssistancePhase.WAITING_NEW_PAGE
    )
}
