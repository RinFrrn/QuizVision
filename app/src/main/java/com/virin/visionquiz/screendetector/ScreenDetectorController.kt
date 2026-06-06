package com.virin.visionquiz.screendetector

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.LiveData
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.virin.visionquiz.R
import com.virin.visionquiz.ScreenSource
import com.virin.visionquiz.dao.Quiz
import com.virin.visionquiz.preference.PreferenceUtils
import com.virin.visionquiz.vision.questiondetector.OriginalRecognitionProcessor
import com.virin.visionquiz.vision.questiondetector.QuizRecognitionProcessor
import java.io.IOException

object ScreenDetectorController : ScreenDetectorSession.Controller {
    private const val TAG = "ScreenDetectorController"

    private data class AnswerExecution(
        val targets: List<ScreenDetectorSession.AnswerTarget>,
        val points: List<android.graphics.Point>,
        val fingerprint: String,
        val contentFingerprint: String,
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
    private var assistanceEnabled = false
    private var assistanceBusy = false
    private var assistanceGeneration = 0
    private var waitingForPageFingerprint: String? = null
    private var selectedPageAxis: QuizAccessibilityService.PageAxis? = null
    private var phaseBeforePause = ScreenDetectorSession.AssistancePhase.IDLE
    private var pendingAnswerPlan: AnswerExecution? = null
    private var pendingAnswerPointIndex = 0
    private var pendingAutoAdvanceRunnable: Runnable? = null
    private var pendingPageChangeTimeoutRunnable: Runnable? = null
    private var pendingOptionUpdateTimeoutRunnable: Runnable? = null
    private var pendingRevealQuestionFingerprint: String? = null
    private var pendingRevealQuestionTop: Int? = null
    private var revealTargetQuestionFingerprint: String? = null
    private val answeredQuestionFingerprints = mutableSetOf<String>()
    private val revealAttempts = mutableMapOf<String, Int>()
    private val blockedRevealFingerprints = mutableSetOf<String>()

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
        val request = StartRequest.AccessibilityQuiz(libraryId, quizzes)
        if (!ensureStartPermissions(activity, request)) {
            return
        }
        startInternal(activity, request)
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
                    cameraSource = ScreenSource(activity)
                    cameraSource?.setMachineLearningFrameProcessor(
                        OriginalRecognitionProcessor(activity) { matches ->
                            ScreenDetectorSession.publishMatches(matches)
                        }
                    )
                }
                is StartRequest.Quiz -> {
                    Log.i(TAG, "Using on-device Quiz recognition Processor for Quiz")
                    cameraSource = ScreenSource(activity)
                    cameraSource?.setMachineLearningFrameProcessor(
                        QuizRecognitionProcessor(
                            activity,
                            request.quizzes,
                            onMatchesDetected = { matches ->
                                ScreenDetectorSession.publishMatches(matches)
                            },
                            minMatchScore = PreferenceUtils.getScreenSearchMinMatchScore(activity)
                        )
                    )
                }
                is StartRequest.AccessibilityQuiz -> {
                    Log.i(TAG, "Using accessibility text source for Quiz")
                    accessibilitySource = AccessibilityTextSource(activity, request.quizzes) { matches ->
                        handleAccessibilityMatches(matches)
                    }
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
        pendingRevealQuestionFingerprint = null
        pendingRevealQuestionTop = null
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
        ScreenDetectorSession.clearAssistanceState()
    }

    private fun maybeStartOverlayService() {
        val activity = hostActivity ?: return
        if (!commonROMPermissionCheck(activity)) {
            return
        }
        activity.startService(
            Intent(activity, ScreenDetectorService::class.java).apply {
                putExtra(ScreenDetectorService.LIBRARY_ID, libId)
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
        clearPendingReveal()
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

    private fun handleAccessibilityMatches(matches: List<com.virin.visionquiz.util.QuizGraphicItem>) {
        ScreenDetectorSession.publishMatches(matches)
        if (!assistanceEnabled || !isDetectionRunning) {
            return
        }
        val plan = ScreenDetectorSession.buildAnswerClickPlan()
        val currentQuestionFingerprints =
            plan?.targets?.mapTo(mutableSetOf()) { it.questionFingerprint }.orEmpty()

        if (pendingRevealQuestionFingerprint != null) {
            handleRevealUpdate(plan)
            return
        }
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
        clearPendingReveal()
        if (previousPhase == ScreenDetectorSession.AssistancePhase.SWIPING ||
            previousPhase == ScreenDetectorSession.AssistancePhase.REVEALING_OPTIONS
        ) {
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
            waitingForPageFingerprint != null ||
            pendingRevealQuestionFingerprint != null
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
            val activeRevealTarget = revealTargetQuestionFingerprint?.let { fingerprint ->
                plan.targets.firstOrNull {
                    it.questionFingerprint == fingerprint && !it.isComplete
                }
            }
            if (revealTargetQuestionFingerprint != null && activeRevealTarget == null) {
                waitForManualPage(
                    "选项仍未完整显示，请手动上滑",
                    ScreenDetectorSession.AssistanceIndicator.ERROR
                )
                return
            }
            val clippedTarget = activeRevealTarget ?: plan.bottomClippedTarget?.takeIf {
                it.questionFingerprint !in answeredQuestionFingerprints
            }
            if (clippedTarget != null) {
                revealBottomClippedTarget(clippedTarget)
            } else {
                continueWithPageDirection()
            }
            return
        }
        if (!ScreenDetectorSession.isCurrentOverlayRendered()) {
            ScreenDetectorSession.setAssistanceState(
                isActive = true,
                phase = ScreenDetectorSession.AssistancePhase.RECOGNIZING_ANSWERS,
                statusText = "正在更新题目框"
            )
            scheduleAssistanceStep(OVERLAY_RENDER_RETRY_DELAY_MS)
            return
        }
        val freshExecution = buildAnswerExecution(answerTargets, plan.contentFingerprint)
        val resumableExecution = pendingAnswerPlan?.takeIf { pending ->
            pendingAnswerPointIndex > 0 &&
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
            onPointCompleted = { nextIndex ->
                if (generation == assistanceGeneration) {
                    pendingAnswerPointIndex = nextIndex
                    execution.targetEndIndexes
                        .filter { (_, endIndex) -> nextIndex >= endIndex }
                        .forEach { (questionFingerprint, _) ->
                            answeredQuestionFingerprints.add(questionFingerprint)
                            revealAttempts.remove(questionFingerprint)
                            blockedRevealFingerprints.remove(questionFingerprint)
                            if (revealTargetQuestionFingerprint == questionFingerprint) {
                                revealTargetQuestionFingerprint = null
                            }
                        }
                }
            }
        ) { success ->
            if (success) {
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
            if (!success) {
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

    private fun buildAnswerExecution(
        targets: List<ScreenDetectorSession.AnswerTarget>,
        contentFingerprint: String
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
            targetEndIndexes = targetEndIndexes
        )
    }

    private fun revealBottomClippedTarget(target: ScreenDetectorSession.AnswerTarget) {
        if (target.questionFingerprint in blockedRevealFingerprints) {
            waitForManualPage(
                "选项仍未完整显示，请手动上滑",
                ScreenDetectorSession.AssistanceIndicator.ERROR
            )
            return
        }
        val attempts = revealAttempts[target.questionFingerprint] ?: 0
        if (attempts >= MAX_REVEAL_ATTEMPTS) {
            blockedRevealFingerprints.add(target.questionFingerprint)
            waitForManualPage(
                "选项仍未完整显示，请手动上滑",
                ScreenDetectorSession.AssistanceIndicator.ERROR
            )
            return
        }
        val frameInfo = ScreenDetectorSession.screenFrameInfo.value
        val service = QuizAccessibilityService.instance
        if (frameInfo == null) {
            waitForManualPage("等待获取屏幕尺寸")
            return
        }
        if (service == null) {
            pauseAssistance("无障碍服务已断开")
            return
        }

        val generation = assistanceGeneration
        revealAttempts[target.questionFingerprint] = attempts + 1
        revealTargetQuestionFingerprint = target.questionFingerprint
        pendingRevealQuestionFingerprint = target.questionFingerprint
        pendingRevealQuestionTop = target.questionRect.top
        assistanceBusy = true
        ScreenDetectorSession.setAssistanceState(
            isActive = true,
            phase = ScreenDetectorSession.AssistancePhase.REVEALING_OPTIONS,
            statusText = "正在上滑显示下一题选项"
        )
        service.swipeUpToReveal(
            screenBounds = android.graphics.Rect(0, 0, frameInfo.width, frameInfo.height),
            overlayBounds = ScreenDetectorSession.getOverlayBoundsSnapshot(),
            targetTop = target.questionRect.top
        ) { success ->
            if (generation != assistanceGeneration) {
                return@swipeUpToReveal
            }
            assistanceBusy = false
            if (!success) {
                clearPendingReveal()
                if ((revealAttempts[target.questionFingerprint] ?: 0) >= MAX_REVEAL_ATTEMPTS) {
                    blockedRevealFingerprints.add(target.questionFingerprint)
                    waitForManualPage(
                        "选项仍未完整显示，请手动上滑",
                        ScreenDetectorSession.AssistanceIndicator.ERROR
                    )
                } else {
                    ScreenDetectorSession.setAssistanceState(
                        isActive = true,
                        phase = ScreenDetectorSession.AssistancePhase.RECOGNIZING_ANSWERS,
                        statusText = "上滑未生效，准备重试"
                    )
                    scheduleAssistanceStep(REVEAL_RETRY_DELAY_MS)
                }
                return@swipeUpToReveal
            }
            if (pendingRevealQuestionFingerprint != target.questionFingerprint) {
                return@swipeUpToReveal
            }
            ScreenDetectorSession.setAssistanceState(
                isActive = true,
                phase = ScreenDetectorSession.AssistancePhase.WAITING_OPTION_UPDATE,
                statusText = "等待完整选项"
            )
            accessibilitySource?.retryOnce()
            scheduleOptionUpdateTimeout(generation, target.questionFingerprint)
        }
    }

    private fun handleRevealUpdate(plan: ScreenDetectorSession.AnswerClickPlan?) {
        val questionFingerprint = pendingRevealQuestionFingerprint ?: return
        val previousTop = pendingRevealQuestionTop ?: return
        val target = plan?.targets?.firstOrNull {
            it.questionFingerprint == questionFingerprint
        }
        val movementThreshold = ScreenDetectorSession.screenFrameInfo.value
            ?.height
            ?.let { (it * REVEAL_MOVEMENT_THRESHOLD_RATIO).toInt() }
            ?.coerceAtLeast(MIN_REVEAL_MOVEMENT_PX)
            ?: MIN_REVEAL_MOVEMENT_PX
        val hasProgress = target?.isComplete == true ||
            (target != null && kotlin.math.abs(target.questionRect.top - previousTop) >= movementThreshold)
        if (!hasProgress) {
            return
        }
        clearPendingReveal()
        assistanceBusy = false
        ScreenDetectorSession.setAssistanceState(
            isActive = true,
            phase = ScreenDetectorSession.AssistancePhase.RECOGNIZING_ANSWERS,
            statusText = if (target.isComplete) {
                "选项已完整显示，准备作答"
            } else {
                "页面已上滑，继续定位选项"
            }
        )
        scheduleAssistanceStep(NEW_PAGE_SETTLE_DELAY_MS)
    }

    private fun scheduleOptionUpdateTimeout(generation: Int, questionFingerprint: String) {
        pendingOptionUpdateTimeoutRunnable?.let(assistanceHandler::removeCallbacks)
        val runnable = Runnable {
            pendingOptionUpdateTimeoutRunnable = null
            if (generation != assistanceGeneration ||
                pendingRevealQuestionFingerprint != questionFingerprint
            ) {
                return@Runnable
            }
            clearPendingReveal()
            assistanceBusy = false
            if ((revealAttempts[questionFingerprint] ?: 0) >= MAX_REVEAL_ATTEMPTS) {
                blockedRevealFingerprints.add(questionFingerprint)
                waitForManualPage(
                    "选项仍未完整显示，请手动上滑",
                    ScreenDetectorSession.AssistanceIndicator.ERROR
                )
            } else {
                ScreenDetectorSession.setAssistanceState(
                    isActive = true,
                    phase = ScreenDetectorSession.AssistancePhase.RECOGNIZING_ANSWERS,
                    statusText = "未检测到选项移动，准备重试"
                )
                scheduleAssistanceStep(REVEAL_RETRY_DELAY_MS)
            }
        }
        pendingOptionUpdateTimeoutRunnable = runnable
        assistanceHandler.postDelayed(runnable, OPTION_UPDATE_TIMEOUT_MS)
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
            axis = axis
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
            accessibilitySource?.retryOnce()
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
        pendingOptionUpdateTimeoutRunnable?.let(assistanceHandler::removeCallbacks)
        pendingOptionUpdateTimeoutRunnable = null
    }

    private fun clearPendingReveal() {
        pendingOptionUpdateTimeoutRunnable?.let(assistanceHandler::removeCallbacks)
        pendingOptionUpdateTimeoutRunnable = null
        pendingRevealQuestionFingerprint = null
        pendingRevealQuestionTop = null
    }

    private fun clearQuestionProgress() {
        clearPendingReveal()
        answeredQuestionFingerprints.clear()
        revealAttempts.clear()
        blockedRevealFingerprints.clear()
        revealTargetQuestionFingerprint = null
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
        if (!commonROMPermissionCheck(activity)) {
            pendingStartRequest = request
            pendingPermissionPrompt = PendingPermissionPrompt.OVERLAY
            permissionHostActivity = activity
            Toast.makeText(activity, "请允许悬浮窗权限", Toast.LENGTH_LONG).show()
            activity.startActivityForResult(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    data = Uri.parse("package:${activity.packageName}")
                },
                REQUEST_FLOAT_CODE
            )
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
        val appName = activity.getString(R.string.app_name)
        val hasShownIntro = AccessibilityPermissionHelper.hasShownIntro(activity)
        val titleRes = if (hasShownIntro) {
            R.string.accessibility_permission_required
        } else {
            R.string.accessibility_search_intro_title
        }
        val messageRes = if (hasShownIntro) {
            R.string.accessibility_search_prompt_message
        } else {
            R.string.accessibility_search_intro_message
        }
        if (!hasShownIntro) {
            AccessibilityPermissionHelper.markIntroShown(activity)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(titleRes, appName))
            .setMessage(activity.getString(messageRes, appName))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.accessibility_search_enable_cta) { _, _ ->
                AccessibilityPermissionHelper.openAccessibilitySettings(activity)
            }
            .show()
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
    private const val ANSWER_SETTLE_DELAY_MS = 550L
    private const val AUTO_PAGE_DELAY_MS = 120L
    private const val NEW_PAGE_SETTLE_DELAY_MS = 240L
    private const val OVERLAY_RENDER_RETRY_DELAY_MS = 48L
    private const val ASSISTANCE_RESUME_DELAY_MS = 220L
    private const val PAGE_CHANGE_TIMEOUT_MS = 3_500L
    private const val OPTION_UPDATE_TIMEOUT_MS = 1_800L
    private const val REVEAL_RETRY_DELAY_MS = 260L
    private const val MAX_REVEAL_ATTEMPTS = 2
    private const val REVEAL_MOVEMENT_THRESHOLD_RATIO = 0.03f
    private const val MIN_REVEAL_MOVEMENT_PX = 24
    private val WAITING_FOR_NEXT_QUESTION_PHASES = setOf(
        ScreenDetectorSession.AssistancePhase.WAITING_MANUAL_PAGE,
        ScreenDetectorSession.AssistancePhase.SWIPING,
        ScreenDetectorSession.AssistancePhase.WAITING_NEW_PAGE
    )
}
