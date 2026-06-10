package com.virin.visionquiz.screendetector

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

class QuizAccessibilityService : AccessibilityService() {
    private val gestureHandler = Handler(Looper.getMainLooper())
    private val navigationHandler = Handler(Looper.getMainLooper())
    private var gestureGeneration = 0
    private var navigationGeneration = 0
    private var activeGestureCompletion: ((Boolean) -> Unit)? = null

    interface Callback {
        fun onAccessibilityContentChanged(hasPageMovement: Boolean)
    }

    data class TextNode(
        val text: String,
        val rect: Rect
    )

    data class PageSnapshot(
        val textNodes: List<TextNode>,
        val dangerousActionBounds: List<Rect>
    )

    enum class ClickSequenceResult {
        COMPLETED,
        CANCELLED,
        BLOCKED_BY_DANGEROUS_ACTION
    }

    enum class PageAxis {
        HORIZONTAL,
        VERTICAL
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        callback?.onAccessibilityContentChanged(false)
        ScreenDetectorController.onAccessibilityServiceConnected()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.packageName?.toString() == packageName) {
            return
        }
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED ->
                callback?.onAccessibilityContentChanged(hasMeaningfulPageMovement(event))
        }
    }

    private fun hasMeaningfulPageMovement(event: AccessibilityEvent): Boolean {
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            (event.scrollDeltaX != 0 || event.scrollDeltaY != 0)
        ) {
            return true
        }
        return event.scrollX != 0 ||
            event.scrollY != 0 ||
            (
                event.fromIndex >= 0 &&
                    event.toIndex >= 0 &&
                    event.fromIndex != event.toIndex
                )
    }

    override fun onInterrupt() {
        cancelActiveGesture()
    }

    override fun onDestroy() {
        cancelActiveGesture()
        navigationGeneration++
        navigationHandler.removeCallbacksAndMessages(null)
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    fun clickPointsSequentially(
        points: List<Point>,
        startIndex: Int = 0,
        excludedPackageName: String,
        screenBounds: Rect,
        shouldContinue: () -> Boolean = { true },
        onPointCompleted: (Int) -> Unit = {},
        onComplete: (ClickSequenceResult) -> Unit
    ) {
        if (points.isEmpty() || startIndex !in 0..points.size) {
            onComplete(ClickSequenceResult.CANCELLED)
            return
        }
        if (startIndex == points.size) {
            onComplete(ClickSequenceResult.COMPLETED)
            return
        }
        val remainingPoints = points.subList(startIndex, points.size)
        if (hasDangerousActionAtAnyPoint(
                remainingPoints,
                excludedPackageName,
                screenBounds
            )
        ) {
            onComplete(ClickSequenceResult.BLOCKED_BY_DANGEROUS_ACTION)
            return
        }
        activeGestureCompletion?.invoke(false)
        var blockedByDangerousAction = false
        activeGestureCompletion = { success ->
            onComplete(
                when {
                    success -> ClickSequenceResult.COMPLETED
                    blockedByDangerousAction ->
                        ClickSequenceResult.BLOCKED_BY_DANGEROUS_ACTION
                    else -> ClickSequenceResult.CANCELLED
                }
            )
        }
        val generation = ++gestureGeneration
        dispatchNextPoint(
            points = points.map(::Point),
            index = startIndex,
            generation = generation,
            isPointBlocked = { point ->
                val blocked = hasDangerousActionAtAnyPoint(
                    listOf(point),
                    excludedPackageName,
                    screenBounds
                )
                blockedByDangerousAction = blockedByDangerousAction || blocked
                blocked
            },
            shouldContinue = shouldContinue,
            onPointCompleted = onPointCompleted
        )
    }

    fun swipePage(
        screenBounds: Rect,
        overlayBounds: Rect?,
        axis: PageAxis,
        verticalTargetPosition: Int? = null,
        onComplete: (Boolean) -> Unit
    ) {
        dispatchPageSwipe(
            screenBounds,
            overlayBounds,
            axis,
            verticalTargetPosition,
            onComplete
        )
    }

    fun cancelPendingClicks() {
        cancelActiveGesture()
    }

    fun returnFromAccessibilitySettings(targetPackageName: String) {
        val generation = ++navigationGeneration
        navigationHandler.removeCallbacksAndMessages(null)
        navigationHandler.postDelayed(
            {
                performReturnStep(
                    targetPackageName = targetPackageName,
                    attempt = 0,
                    generation = generation
                )
            },
            PERMISSION_RETURN_INITIAL_DELAY_MS
        )
    }

    private fun performReturnStep(
        targetPackageName: String,
        attempt: Int,
        generation: Int
    ) {
        if (generation != navigationGeneration) {
            return
        }
        val root = rootInActiveWindow
        val currentPackage = try {
            root?.packageName?.toString()
        } finally {
            root?.recycle()
        }
        if (currentPackage == targetPackageName || attempt >= MAX_PERMISSION_RETURN_ATTEMPTS) {
            return
        }
        if (!performGlobalAction(GLOBAL_ACTION_BACK)) {
            return
        }
        navigationHandler.postDelayed(
            {
                performReturnStep(
                    targetPackageName = targetPackageName,
                    attempt = attempt + 1,
                    generation = generation
                )
            },
            PERMISSION_RETURN_STEP_DELAY_MS
        )
    }

    private fun dispatchNextPoint(
        points: List<Point>,
        index: Int,
        generation: Int,
        isPointBlocked: (Point) -> Boolean,
        shouldContinue: () -> Boolean,
        onPointCompleted: (Int) -> Unit
    ) {
        if (generation != gestureGeneration) {
            return
        }
        if (index >= points.size) {
            completeGesture(generation, true)
            return
        }
        if (!shouldContinue()) {
            completeGesture(generation, false)
            return
        }

        val point = points[index]
        if (isPointBlocked(point)) {
            completeGesture(generation, false)
            return
        }
        val path = Path().apply {
            moveTo(point.x.toFloat(), point.y.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0L,
                    CLICK_DURATION_MS
                )
            )
            .build()
        val accepted = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (generation != gestureGeneration) {
                        return
                    }
                    onPointCompleted(index + 1)
                    gestureHandler.postDelayed(
                        {
                            dispatchNextPoint(
                                points = points,
                                index = index + 1,
                                generation = generation,
                                isPointBlocked = isPointBlocked,
                                shouldContinue = shouldContinue,
                                onPointCompleted = onPointCompleted
                            )
                        },
                        CLICK_INTERVAL_MS
                    )
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    completeGesture(generation, false)
                }
            },
            gestureHandler
        )
        if (!accepted) {
            completeGesture(generation, false)
        }
    }

    private fun dispatchPageSwipe(
        screenBounds: Rect,
        overlayBounds: Rect?,
        axis: PageAxis,
        verticalTargetPosition: Int?,
        onComplete: (Boolean) -> Unit
    ) {
        if (screenBounds.isEmpty) {
            onComplete(false)
            return
        }
        if (axis == PageAxis.VERTICAL) {
            val defaultDistance =
                (screenBounds.height() * DEFAULT_VERTICAL_SWIPE_DISTANCE_RATIO).toInt()
            val targetDistance = verticalTargetPosition
                ?.minus(screenBounds.top)
                ?.takeIf { it > 0 }
                ?: defaultDistance
            dispatchVerticalSwipe(
                screenBounds,
                overlayBounds,
                targetDistance,
                onComplete
            )
            return
        }
        activeGestureCompletion?.invoke(false)
        activeGestureCompletion = onComplete
        val generation = ++gestureGeneration
        val path = Path()
        val y = chooseGestureCoordinate(
            preferred = screenBounds.centerY(),
            minimum = screenBounds.top + screenBounds.height() / 4,
            maximum = screenBounds.bottom - screenBounds.height() / 4,
            blockedStart = overlayBounds?.top,
            blockedEnd = overlayBounds?.bottom
        )
        path.moveTo(
            screenBounds.left + screenBounds.width() * SWIPE_START_RATIO,
            y.toFloat()
        )
        path.lineTo(
            screenBounds.left + screenBounds.width() * SWIPE_END_RATIO,
            y.toFloat()
        )
        dispatchPath(path, generation)
    }

    private fun dispatchVerticalSwipe(
        screenBounds: Rect,
        overlayBounds: Rect?,
        distance: Int,
        onComplete: (Boolean) -> Unit
    ) {
        activeGestureCompletion?.invoke(false)
        activeGestureCompletion = onComplete
        val generation = ++gestureGeneration
        val x = chooseGestureCoordinate(
            preferred = screenBounds.centerX(),
            minimum = screenBounds.left + screenBounds.width() / 4,
            maximum = screenBounds.right - screenBounds.width() / 4,
            blockedStart = overlayBounds?.left,
            blockedEnd = overlayBounds?.right
        )
        val startY =
            screenBounds.top + (screenBounds.height() * VERTICAL_SWIPE_START_RATIO).toInt()
        val minimumEndY =
            screenBounds.top + (screenBounds.height() * VERTICAL_SWIPE_END_RATIO).toInt()
        val endY = (startY - distance).coerceAtLeast(minimumEndY)
        val path = Path().apply {
            moveTo(x.toFloat(), startY.toFloat())
            lineTo(x.toFloat(), endY.toFloat())
        }
        dispatchPath(path, generation, VERTICAL_SWIPE_DURATION_MS)
    }

    private fun dispatchPath(
        path: Path,
        generation: Int,
        durationMs: Long = SWIPE_DURATION_MS
    ) {
        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0L,
                    durationMs
                )
            )
            .build()
        val accepted = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    completeGesture(generation, true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    completeGesture(generation, false)
                }
            },
            gestureHandler
        )
        if (!accepted) {
            completeGesture(generation, false)
        }
    }

    private fun chooseGestureCoordinate(
        preferred: Int,
        minimum: Int,
        maximum: Int,
        blockedStart: Int?,
        blockedEnd: Int?
    ): Int {
        if (blockedStart == null || blockedEnd == null || preferred !in blockedStart..blockedEnd) {
            return preferred
        }
        return if (blockedStart - minimum >= maximum - blockedEnd) {
            (minimum + blockedStart) / 2
        } else {
            (blockedEnd + maximum) / 2
        }
    }

    private fun completeGesture(generation: Int, success: Boolean) {
        if (generation != gestureGeneration) {
            return
        }
        val completion = activeGestureCompletion
        activeGestureCompletion = null
        completion?.invoke(success)
    }

    private fun cancelActiveGesture() {
        gestureGeneration++
        gestureHandler.removeCallbacksAndMessages(null)
        val completion = activeGestureCompletion
        activeGestureCompletion = null
        completion?.invoke(false)
    }

    fun collectPageSnapshot(
        excludedPackageName: String,
        screenBounds: Rect
    ): PageSnapshot {
        val textNodes = mutableListOf<TextNode>()
        val dangerousActionBounds = mutableListOf<Rect>()
        val interactiveWindows = windows.orEmpty()
            .sortedByDescending { it.layer }
        if (interactiveWindows.isNotEmpty()) {
            for (window in interactiveWindows) {
                collectWindowSnapshot(
                    window,
                    excludedPackageName,
                    screenBounds,
                    textNodes,
                    dangerousActionBounds
                )
            }
        } else {
            val root = rootInActiveWindow
                ?: return PageSnapshot(emptyList(), emptyList())
            try {
                traverseNode(
                    root,
                    excludedPackageName,
                    screenBounds,
                    textNodes,
                    dangerousActionBounds
                )
            } finally {
                root.recycle()
            }
        }
        return PageSnapshot(
            textNodes = textNodes,
            dangerousActionBounds = dangerousActionBounds
                .distinctBy { it.flattenToString() }
                .map(::Rect)
        )
    }

    private fun collectWindowSnapshot(
        window: AccessibilityWindowInfo,
        excludedPackageName: String,
        screenBounds: Rect,
        outTextNodes: MutableList<TextNode>,
        outDangerousActionBounds: MutableList<Rect>
    ) {
        val root = window.root ?: return
        try {
            traverseNode(
                root,
                excludedPackageName,
                screenBounds,
                outTextNodes,
                outDangerousActionBounds
            )
        } finally {
            root.recycle()
        }
    }

    private fun traverseNode(
        node: AccessibilityNodeInfo,
        excludedPackageName: String,
        screenBounds: Rect,
        outTextNodes: MutableList<TextNode>,
        outDangerousActionBounds: MutableList<Rect>
    ) {
        addNodeTextIfNeeded(node, excludedPackageName, screenBounds, outTextNodes)
        addDangerousActionBoundsIfNeeded(
            node,
            excludedPackageName,
            screenBounds,
            outDangerousActionBounds
        )
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try {
                traverseNode(
                    child,
                    excludedPackageName,
                    screenBounds,
                    outTextNodes,
                    outDangerousActionBounds
                )
            } finally {
                child.recycle()
            }
        }
    }

    private fun addNodeTextIfNeeded(
        node: AccessibilityNodeInfo,
        excludedPackageName: String,
        screenBounds: Rect,
        outNodes: MutableList<TextNode>
    ) {
        if (!node.isVisibleToUser) {
            return
        }
        if (node.packageName?.toString() == excludedPackageName) {
            return
        }

        val text = node.text?.toString()
            ?.replace(WHITESPACE_REGEX, " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (!rect.intersect(screenBounds)) {
            return
        }
        if (rect.width() <= MIN_TEXT_RECT_SIZE || rect.height() <= MIN_TEXT_RECT_SIZE) {
            return
        }
        outNodes.add(TextNode(text, Rect(rect)))
    }

    private fun addDangerousActionBoundsIfNeeded(
        node: AccessibilityNodeInfo,
        excludedPackageName: String,
        screenBounds: Rect,
        outBounds: MutableList<Rect>
    ) {
        if (!node.isVisibleToUser || node.packageName?.toString() == excludedPackageName) {
            return
        }
        val labels = listOfNotNull(node.text, node.contentDescription)
            .map { it.toString().replace(WHITESPACE_REGEX, " ").trim() }
        if (labels.none(::isDangerousActionLabel)) {
            return
        }
        val clickableBounds = findClickableAncestorBounds(node, screenBounds) ?: return
        val lowerScreenThreshold =
            screenBounds.top + (screenBounds.height() * DANGEROUS_ACTION_MIN_Y_RATIO).toInt()
        if (clickableBounds.centerY() < lowerScreenThreshold) {
            return
        }
        outBounds.add(clickableBounds)
    }

    private fun findClickableAncestorBounds(
        startNode: AccessibilityNodeInfo,
        screenBounds: Rect
    ): Rect? {
        var current = AccessibilityNodeInfo.obtain(startNode)
        repeat(MAX_CLICKABLE_ANCESTOR_DEPTH + 1) {
            val rect = Rect()
            current.getBoundsInScreen(rect)
            if (current.isClickable && rect.intersect(screenBounds) && !rect.isEmpty) {
                current.recycle()
                return Rect(rect)
            }
            val parent = current.parent
            current.recycle()
            if (parent == null) {
                return null
            }
            current = parent
        }
        current.recycle()
        return null
    }

    private fun hasDangerousActionAtAnyPoint(
        points: List<Point>,
        excludedPackageName: String,
        screenBounds: Rect
    ): Boolean {
        if (points.isEmpty()) {
            return false
        }
        val dangerousBounds = collectPageSnapshot(
            excludedPackageName,
            screenBounds
        ).dangerousActionBounds
        return points.any { point ->
            dangerousBounds.any { bounds -> bounds.contains(point.x, point.y) }
        }
    }

    private fun isDangerousActionLabel(label: String): Boolean {
        val normalized = label
            .lowercase()
            .replace(DANGEROUS_ACTION_LABEL_SEPARATOR_REGEX, "")
        return DANGEROUS_ACTION_LABELS.any { dangerousLabel ->
            normalized == dangerousLabel || normalized.contains(dangerousLabel)
        }
    }

    companion object {
        @Volatile
        var instance: QuizAccessibilityService? = null
            private set

        @Volatile
        var callback: Callback? = null

        private const val MIN_TEXT_RECT_SIZE = 3
        private const val CLICK_DURATION_MS = 60L
        private const val CLICK_INTERVAL_MS = 140L
        private const val SWIPE_DURATION_MS = 320L
        private const val SWIPE_START_RATIO = 0.78f
        private const val SWIPE_END_RATIO = 0.22f
        private const val VERTICAL_SWIPE_DURATION_MS = 700L
        private const val VERTICAL_SWIPE_START_RATIO = 0.92f
        private const val VERTICAL_SWIPE_END_RATIO = 0.05f
        private const val DEFAULT_VERTICAL_SWIPE_DISTANCE_RATIO = 0.60f
        private const val DANGEROUS_ACTION_MIN_Y_RATIO = 0.55f
        private const val MAX_CLICKABLE_ANCESTOR_DEPTH = 4
        private const val PERMISSION_RETURN_INITIAL_DELAY_MS = 120L
        private const val PERMISSION_RETURN_STEP_DELAY_MS = 240L
        private const val MAX_PERMISSION_RETURN_ATTEMPTS = 3
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val DANGEROUS_ACTION_LABEL_SEPARATOR_REGEX =
            Regex("[\\s\\p{P}\\p{S}]+")
        private val DANGEROUS_ACTION_LABELS = setOf(
            "交卷",
            "提交",
            "提交试卷",
            "提交答卷",
            "结束考试",
            "结束答题",
            "完成答题",
            "submit",
            "submitexam",
            "finishquiz",
            "finishexam",
            "endquiz",
            "endexam"
        )
    }
}
