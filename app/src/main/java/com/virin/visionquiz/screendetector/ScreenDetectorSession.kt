package com.virin.visionquiz.screendetector

import android.graphics.Point
import android.graphics.Rect
import com.virin.visionquiz.util.QuizGraphicItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ScreenDetectorSession {

    data class ScreenFrameInfo(
        val width: Int,
        val height: Int
    )

    data class ScreenScanState(
        val generation: Int = 0,
        val hideResults: Boolean = false
    )

    enum class DetectionState {
        STOPPED,
        RUNNING,
        PAUSED,
    }

    enum class DetectionMode {
        NONE,
        SCREEN_OCR,
        ACCESSIBILITY,
    }

    data class AnswerClickState(
        val isClicking: Boolean = false,
        val activeFingerprint: String? = null,
        val lastExecutedFingerprint: String? = null,
        val lastExecutedContentFingerprint: String? = null
    )

    data class AnswerTarget(
        val questionFingerprint: String,
        val questionRect: Rect,
        val points: List<Point>,
        val fingerprint: String,
        val isComplete: Boolean,
        val isBlockedByDangerousAction: Boolean
    )

    data class AnswerClickPlan(
        val targets: List<AnswerTarget>,
        val bottomClippedTarget: AnswerTarget?,
        val dangerousActionBlockedTarget: AnswerTarget?,
        val contentFingerprint: String,
    )

    enum class AssistancePhase {
        IDLE,
        RECOGNIZING_ANSWERS,
        CLICKING_ANSWERS,
        REVEALING_OPTIONS,
        WAITING_OPTION_UPDATE,
        WAITING_MANUAL_PAGE,
        SWIPING,
        WAITING_NEW_PAGE,
        PAUSED,
    }

    enum class PageDirection {
        LEFT,
        UP,
    }

    enum class AssistanceIndicator {
        IDLE,
        TOUCH,
        SWIPE_LEFT,
        SWIPE_UP,
        ERROR,
    }

    data class AssistanceState(
        val isActive: Boolean = false,
        val phase: AssistancePhase = AssistancePhase.IDLE,
        val pageDirection: PageDirection? = null,
        val indicator: AssistanceIndicator = AssistanceIndicator.IDLE,
        val statusText: String = ""
    )

    interface Controller {
        fun pauseScreenDetection()
        fun resumeScreenDetection()
        fun retryScreenDetectionOnce()
        fun toggleAnswerAssistance()
        fun swipePageLeft()
        fun swipePageUp()
        fun stopScreenDetection()
    }

    @Volatile
    private var controller: Controller? = null

    private val _state = MutableStateFlow(DetectionState.STOPPED)
    val state: StateFlow<DetectionState> = _state.asStateFlow()

    private val _mode = MutableStateFlow(DetectionMode.NONE)
    val mode: StateFlow<DetectionMode> = _mode.asStateFlow()

    private val _matches = MutableStateFlow<List<QuizGraphicItem>>(emptyList())
    val matches: StateFlow<List<QuizGraphicItem>> = _matches.asStateFlow()

    private val _answerClickState = MutableStateFlow(AnswerClickState())
    val answerClickState: StateFlow<AnswerClickState> = _answerClickState.asStateFlow()

    private val _assistanceState = MutableStateFlow(AssistanceState())
    val assistanceState: StateFlow<AssistanceState> = _assistanceState.asStateFlow()

    private val _overlayBounds = MutableStateFlow<Rect?>(null)
    val overlayBounds: StateFlow<Rect?> = _overlayBounds.asStateFlow()

    private val _annotationBounds = MutableStateFlow<List<Rect>>(emptyList())
    private val _renderedOverlayFingerprint = MutableStateFlow<String?>(null)
    private val _dangerousActionBounds = MutableStateFlow<List<Rect>>(emptyList())

    private val _screenFrameInfo = MutableStateFlow<ScreenFrameInfo?>(null)
    val screenFrameInfo: StateFlow<ScreenFrameInfo?> = _screenFrameInfo.asStateFlow()

    private val screenScanLock = Object()
    private var nextScreenScanGeneration = 0
    private var hiddenScreenScanGeneration = 0
    private val _screenScanState = MutableStateFlow(ScreenScanState())
    val screenScanState: StateFlow<ScreenScanState> = _screenScanState.asStateFlow()

    fun attachController(controller: Controller) {
        this.controller = controller
    }

    fun detachController(controller: Controller) {
        if (this.controller === controller) {
            this.controller = null
        }
    }

    fun setState(state: DetectionState) {
        if (_state.value == state) {
            return
        }
        _state.value = state
    }

    fun setMode(mode: DetectionMode) {
        if (_mode.value == mode) {
            return
        }
        _mode.value = mode
        if (mode != DetectionMode.ACCESSIBILITY) {
            resetAnswerClickState()
            clearAssistanceState()
        }
    }

    fun publishMatches(matches: List<QuizGraphicItem>) {
        if (_matches.value == matches) {
            return
        }
        _matches.value = matches
    }

    fun clearMatches() {
        if (_matches.value.isEmpty() && _renderedOverlayFingerprint.value == null) {
            return
        }
        _matches.value = emptyList()
        _renderedOverlayFingerprint.value = null
    }

    @JvmStatic
    fun beginScreenScan(): Int {
        synchronized(screenScanLock) {
            val generation = ++nextScreenScanGeneration
            _screenScanState.value = ScreenScanState(
                generation = generation,
                hideResults = true
            )
            return generation
        }
    }

    @JvmStatic
    fun acknowledgeScreenResultsHidden(generation: Int) {
        synchronized(screenScanLock) {
            val state = _screenScanState.value
            if (state.generation != generation || !state.hideResults) {
                return
            }
            hiddenScreenScanGeneration = maxOf(hiddenScreenScanGeneration, generation)
            screenScanLock.notifyAll()
        }
    }

    @JvmStatic
    fun awaitScreenResultsHidden(generation: Int, timeoutMs: Long): Boolean {
        val deadlineNanos = System.nanoTime() + timeoutMs.coerceAtLeast(0L) * 1_000_000L
        synchronized(screenScanLock) {
            while (
                hiddenScreenScanGeneration < generation &&
                _screenScanState.value.generation == generation &&
                _screenScanState.value.hideResults
            ) {
                val remainingNanos = deadlineNanos - System.nanoTime()
                if (remainingNanos <= 0L) {
                    break
                }
                val waitMillis = remainingNanos / 1_000_000L
                val waitNanos = (remainingNanos % 1_000_000L).toInt()
                try {
                    screenScanLock.wait(waitMillis, waitNanos)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            return hiddenScreenScanGeneration >= generation
        }
    }

    @JvmStatic
    fun finishScreenScan(generation: Int) {
        synchronized(screenScanLock) {
            if (_screenScanState.value.generation != generation) {
                return
            }
            _screenScanState.value = ScreenScanState(generation = generation)
            screenScanLock.notifyAll()
        }
    }

    @JvmStatic
    fun cancelScreenScan() {
        synchronized(screenScanLock) {
            val generation = _screenScanState.value.generation
            _screenScanState.value = ScreenScanState(generation = generation)
            screenScanLock.notifyAll()
        }
    }

    @JvmStatic
    fun publishScreenFrameInfo(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            val frameInfo = ScreenFrameInfo(width, height)
            if (_screenFrameInfo.value != frameInfo) {
                _screenFrameInfo.value = frameInfo
            }
        }
    }

    @JvmStatic
    fun clearScreenFrameInfo() {
        if (_screenFrameInfo.value == null) {
            return
        }
        _screenFrameInfo.value = null
    }

    fun publishOverlayBounds(bounds: Rect) {
        if (_overlayBounds.value == bounds) {
            return
        }
        _overlayBounds.value = Rect(bounds)
    }

    fun clearOverlayBounds() {
        if (_overlayBounds.value == null) {
            return
        }
        _overlayBounds.value = null
    }

    @JvmStatic
    fun getOverlayBoundsSnapshot(): Rect? {
        return _overlayBounds.value?.let { Rect(it) }
    }

    fun publishAnnotationBounds(bounds: List<Rect>) {
        if (_annotationBounds.value == bounds) {
            return
        }
        _annotationBounds.value = bounds.map { Rect(it) }
    }

    fun clearAnnotationBounds() {
        if (_annotationBounds.value.isEmpty() && _renderedOverlayFingerprint.value == null) {
            return
        }
        _annotationBounds.value = emptyList()
        _renderedOverlayFingerprint.value = null
    }

    @JvmStatic
    fun getAnnotationBoundsSnapshot(): List<Rect> {
        return _annotationBounds.value.map { Rect(it) }
    }

    fun publishDangerousActionBounds(bounds: List<Rect>) {
        if (_dangerousActionBounds.value == bounds) {
            return
        }
        _dangerousActionBounds.value = bounds.map(::Rect)
    }

    fun clearDangerousActionBounds() {
        if (_dangerousActionBounds.value.isEmpty()) {
            return
        }
        _dangerousActionBounds.value = emptyList()
    }

    fun requestPauseResume() {
        when (_state.value) {
            DetectionState.RUNNING -> controller?.pauseScreenDetection()
            DetectionState.PAUSED -> controller?.resumeScreenDetection()
            DetectionState.STOPPED -> Unit
        }
    }

    fun requestStop() {
        controller?.stopScreenDetection()
    }

    fun requestRetryOnce() {
        if (_state.value == DetectionState.PAUSED) {
            controller?.retryScreenDetectionOnce()
        }
    }

    fun requestToggleAnswerAssistance() {
        if (_mode.value == DetectionMode.ACCESSIBILITY &&
            _state.value != DetectionState.STOPPED
        ) {
            controller?.toggleAnswerAssistance()
        }
    }

    fun requestSwipePageLeft() {
        if (canRequestPageDirection(PageDirection.LEFT)) {
            controller?.swipePageLeft()
        }
    }

    fun requestSwipePageUp() {
        if (canRequestPageDirection(PageDirection.UP)) {
            controller?.swipePageUp()
        }
    }

    private fun canRequestPageDirection(direction: PageDirection): Boolean {
        return _mode.value == DetectionMode.ACCESSIBILITY &&
            _state.value != DetectionState.STOPPED &&
            _assistanceState.value.isActive &&
            (
                _assistanceState.value.pageDirection == direction ||
                    (
                        _state.value == DetectionState.RUNNING &&
                            _assistanceState.value.phase == AssistancePhase.WAITING_MANUAL_PAGE
                        )
                )
    }

    fun buildAnswerClickPlan(): AnswerClickPlan? {
        if (_mode.value != DetectionMode.ACCESSIBILITY) {
            return null
        }
        val frameInfo = _screenFrameInfo.value ?: return null
        val screenBounds = Rect(0, 0, frameInfo.width, frameInfo.height)
        val controlBounds = _overlayBounds.value
        val dangerousActionBounds = _dangerousActionBounds.value
        val seenRects = mutableSetOf<String>()
        val sortedMatches = _matches.value
            .sortedWith(
                compareBy<QuizGraphicItem> { it.rect.top }
                    .thenBy { it.rect.left }
                    .thenByDescending { it.distance }
            )
        val targets = sortedMatches.map { match ->
                val points = mutableListOf<Point>()
                val fingerprintParts = mutableListOf<String>()
                val questionFingerprint = buildQuestionFingerprint(match)
                var isBlockedByDangerousAction = false
                match.answerRects.forEachIndexed { answerOrder, answerRect ->
                    if (answerRect.isEmpty || !screenBounds.contains(answerRect.centerX(), answerRect.centerY())) {
                        return@forEachIndexed
                    }
                    val center = Point(answerRect.centerX(), answerRect.centerY())
                    if (controlBounds?.contains(center.x, center.y) == true) {
                        return@forEachIndexed
                    }
                    if (dangerousActionBounds.any { it.contains(center.x, center.y) }) {
                        isBlockedByDangerousAction = true
                        return@forEachIndexed
                    }
                    val rectKey = answerRect.flattenToString()
                    if (!seenRects.add(rectKey)) {
                        return@forEachIndexed
                    }
                    points.add(center)
                    fingerprintParts.add("$questionFingerprint:$answerOrder:$rectKey")
                }
                AnswerTarget(
                    questionFingerprint = questionFingerprint,
                    questionRect = Rect(match.rect),
                    points = points,
                    fingerprint = fingerprintParts.joinToString("|"),
                    isComplete =
                        points.size == match.question.answer.size &&
                            points.isNotEmpty() &&
                            (
                                !match.question.isMultipleChoice ||
                                    match.optionRects.size == match.question.options.size
                                ),
                    isBlockedByDangerousAction = isBlockedByDangerousAction
                )
            }

        if (targets.isEmpty()) {
            return null
        }
        val lastTarget = targets.last()
        val bottomClippedTarget = lastTarget.takeIf {
            !it.isComplete &&
                !it.isBlockedByDangerousAction &&
                it.questionRect.centerY() >= screenBounds.top + screenBounds.height() / 2
        }
        return AnswerClickPlan(
            targets = targets,
            bottomClippedTarget = bottomClippedTarget,
            dangerousActionBlockedTarget = targets.lastOrNull {
                it.isBlockedByDangerousAction
            },
            contentFingerprint = buildQuestionContentFingerprint() ?: return null,
        )
    }

    fun buildQuestionFingerprint(match: QuizGraphicItem): String {
        val question = match.question
        val identity = question.id
            .takeIf { it != 0 }
            ?.toString()
            ?: question.prompt
        val options = question.options.joinToString("\u001F")
        val answers = question.answer.sorted().joinToString(",")
        return "$identity\u001E${question.prompt}\u001E$options\u001E$answers"
    }

    fun buildQuestionContentFingerprint(
        matches: List<QuizGraphicItem> = _matches.value
    ): String? {
        val parts = matches.map(::buildQuestionFingerprint).distinct().sorted()
        return parts.takeIf { it.isNotEmpty() }?.joinToString("\u001D")
    }

    fun buildPageFingerprint(matches: List<QuizGraphicItem> = _matches.value): String? {
        val parts = matches
            .sortedWith(
                compareBy<QuizGraphicItem> { it.rect.top }
                    .thenBy { it.rect.left }
                    .thenByDescending { it.distance }
            )
            .map { match ->
                val identity = match.question.id
                    .takeIf { it != 0 }
                    ?.toString()
                    ?: match.question.prompt
                "$identity:${match.rect.flattenToString()}"
            }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("|")
    }

    fun buildOverlayFingerprint(matches: List<QuizGraphicItem> = _matches.value): String? {
        val parts = matches
            .sortedWith(
                compareBy<QuizGraphicItem> { it.rect.top }
                    .thenBy { it.rect.left }
                    .thenByDescending { it.distance }
            )
            .map { match ->
                val identity = match.question.id
                    .takeIf { it != 0 }
                    ?.toString()
                    ?: match.question.prompt
                val answers = match.answerRects.joinToString(",") { it.flattenToString() }
                "$identity:${match.rect.flattenToString()}:$answers"
            }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("|")
    }

    fun markOverlayRendered(fingerprint: String) {
        if (buildOverlayFingerprint() == fingerprint) {
            _renderedOverlayFingerprint.value = fingerprint
        }
    }

    fun isCurrentOverlayRendered(): Boolean {
        val currentFingerprint = buildOverlayFingerprint() ?: return false
        return _renderedOverlayFingerprint.value == currentFingerprint
    }

    fun beginAnswerClick(fingerprint: String): Boolean {
        val current = _answerClickState.value
        if (current.isClicking || current.lastExecutedFingerprint == fingerprint) {
            return false
        }
        _answerClickState.value = current.copy(
            isClicking = true,
            activeFingerprint = fingerprint
        )
        return true
    }

    fun markAnswerClickDispatched(fingerprint: String, contentFingerprint: String) {
        val current = _answerClickState.value
        if (current.activeFingerprint == fingerprint) {
            _answerClickState.value = current.copy(
                lastExecutedFingerprint = fingerprint,
                lastExecutedContentFingerprint = contentFingerprint
            )
        }
    }

    fun finishAnswerClick(fingerprint: String) {
        val current = _answerClickState.value
        if (current.activeFingerprint == fingerprint) {
            _answerClickState.value = current.copy(
                isClicking = false,
                activeFingerprint = null
            )
        }
    }

    fun resetAnswerClickState() {
        if (_answerClickState.value == AnswerClickState()) {
            return
        }
        _answerClickState.value = AnswerClickState()
    }

    fun setAssistanceState(
        isActive: Boolean,
        phase: AssistancePhase,
        pageDirection: PageDirection? = _assistanceState.value.pageDirection,
        indicator: AssistanceIndicator = defaultAssistanceIndicator(phase, pageDirection),
        statusText: String
    ) {
        val assistanceState = AssistanceState(
            isActive = isActive,
            phase = phase,
            pageDirection = pageDirection,
            indicator = indicator,
            statusText = statusText
        )
        if (_assistanceState.value != assistanceState) {
            _assistanceState.value = assistanceState
        }
    }

    fun clearAssistanceState() {
        if (_assistanceState.value == AssistanceState()) {
            return
        }
        _assistanceState.value = AssistanceState()
    }

    private fun defaultAssistanceIndicator(
        phase: AssistancePhase,
        pageDirection: PageDirection?
    ): AssistanceIndicator {
        return when (phase) {
            AssistancePhase.REVEALING_OPTIONS,
            AssistancePhase.WAITING_OPTION_UPDATE -> AssistanceIndicator.SWIPE_UP
            AssistancePhase.SWIPING,
            AssistancePhase.WAITING_NEW_PAGE,
            AssistancePhase.WAITING_MANUAL_PAGE -> when (pageDirection) {
                PageDirection.LEFT -> AssistanceIndicator.SWIPE_LEFT
                PageDirection.UP -> AssistanceIndicator.SWIPE_UP
                null -> AssistanceIndicator.TOUCH
            }
            AssistancePhase.IDLE -> AssistanceIndicator.IDLE
            else -> AssistanceIndicator.TOUCH
        }
    }
}
