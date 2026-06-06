package com.virin.visionquiz.screendetector

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
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
        fun onAccessibilityContentChanged()
    }

    data class TextNode(
        val text: String,
        val rect: Rect
    )

    enum class PageAxis {
        HORIZONTAL,
        VERTICAL
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        callback?.onAccessibilityContentChanged()
        ScreenDetectorController.onAccessibilityServiceConnected()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> callback?.onAccessibilityContentChanged()
        }
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
        onPointCompleted: (Int) -> Unit = {},
        onComplete: (Boolean) -> Unit
    ) {
        if (points.isEmpty() || startIndex !in 0..points.size) {
            onComplete(false)
            return
        }
        if (startIndex == points.size) {
            onComplete(true)
            return
        }
        activeGestureCompletion?.invoke(false)
        activeGestureCompletion = onComplete
        val generation = ++gestureGeneration
        dispatchNextPoint(
            points = points.map(::Point),
            index = startIndex,
            generation = generation,
            onPointCompleted = onPointCompleted
        )
    }

    fun swipePage(
        screenBounds: Rect,
        overlayBounds: Rect?,
        axis: PageAxis,
        onComplete: (Boolean) -> Unit
    ) {
        dispatchPageSwipe(screenBounds, overlayBounds, axis, onComplete)
    }

    fun swipeUpToReveal(
        screenBounds: Rect,
        overlayBounds: Rect?,
        targetTop: Int,
        onComplete: (Boolean) -> Unit
    ) {
        if (screenBounds.isEmpty) {
            onComplete(false)
            return
        }
        val desiredTop = screenBounds.top + (screenBounds.height() * REVEAL_TARGET_TOP_RATIO).toInt()
        val minimumDistance = (screenBounds.height() * REVEAL_MIN_DISTANCE_RATIO).toInt()
        val maximumDistance = (screenBounds.height() * REVEAL_MAX_DISTANCE_RATIO).toInt()
        val distance = (targetTop - desiredTop).coerceIn(minimumDistance, maximumDistance)
        dispatchVerticalSwipe(screenBounds, overlayBounds, distance, onComplete)
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
        onPointCompleted: (Int) -> Unit
    ) {
        if (generation != gestureGeneration) {
            return
        }
        if (index >= points.size) {
            completeGesture(generation, true)
            return
        }

        val point = points[index]
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
        onComplete: (Boolean) -> Unit
    ) {
        if (screenBounds.isEmpty) {
            onComplete(false)
            return
        }
        if (axis == PageAxis.VERTICAL) {
            dispatchVerticalSwipe(
                screenBounds,
                overlayBounds,
                (screenBounds.height() * (SWIPE_START_RATIO - SWIPE_END_RATIO)).toInt(),
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
        val startY = screenBounds.top + (screenBounds.height() * SWIPE_START_RATIO).toInt()
        val minimumEndY = screenBounds.top + (screenBounds.height() * SWIPE_END_RATIO).toInt()
        val endY = (startY - distance).coerceAtLeast(minimumEndY)
        val path = Path().apply {
            moveTo(x.toFloat(), startY.toFloat())
            lineTo(x.toFloat(), endY.toFloat())
        }
        dispatchPath(path, generation)
    }

    private fun dispatchPath(
        path: Path,
        generation: Int
    ) {
        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0L,
                    SWIPE_DURATION_MS
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

    fun collectVisibleTextNodes(excludedPackageName: String, screenBounds: Rect): List<TextNode> {
        val nodes = mutableListOf<TextNode>()
        val interactiveWindows = windows.orEmpty()
            .sortedByDescending { it.layer }
        if (interactiveWindows.isNotEmpty()) {
            for (window in interactiveWindows) {
                collectWindowTextNodes(window, excludedPackageName, screenBounds, nodes)
            }
        } else {
            val root = rootInActiveWindow ?: return emptyList()
            try {
                traverseNode(root, excludedPackageName, screenBounds, nodes)
            } finally {
                root.recycle()
            }
        }
        return nodes
    }

    private fun collectWindowTextNodes(
        window: AccessibilityWindowInfo,
        excludedPackageName: String,
        screenBounds: Rect,
        outNodes: MutableList<TextNode>
    ) {
        val root = window.root ?: return
        try {
            traverseNode(root, excludedPackageName, screenBounds, outNodes)
        } finally {
            root.recycle()
        }
    }

    private fun traverseNode(
        node: AccessibilityNodeInfo,
        excludedPackageName: String,
        screenBounds: Rect,
        outNodes: MutableList<TextNode>
    ) {
        addNodeTextIfNeeded(node, excludedPackageName, screenBounds, outNodes)
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try {
                traverseNode(child, excludedPackageName, screenBounds, outNodes)
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
        private const val REVEAL_TARGET_TOP_RATIO = 0.25f
        private const val REVEAL_MIN_DISTANCE_RATIO = 0.22f
        private const val REVEAL_MAX_DISTANCE_RATIO = 0.55f
        private const val PERMISSION_RETURN_INITIAL_DELAY_MS = 120L
        private const val PERMISSION_RETURN_STEP_DELAY_MS = 240L
        private const val MAX_PERMISSION_RETURN_ATTEMPTS = 3
        private val WHITESPACE_REGEX = Regex("\\s+")
    }
}
