package com.virin.visionquiz.screendetector

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.TextUtils
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ContextThemeWrapper
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.virin.visionquiz.R
import com.virin.visionquiz.preference.PreferenceUtils
import com.virin.visionquiz.util.QuizGraphicItem
import com.virin.visionquiz.vision.graphic.GraphicOverlay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

class ScreenDetectorService : LifecycleService() {
    private lateinit var windowManager: WindowManager
    private var markerOverlayView: GraphicOverlay? = null
    private var controlRootView: FrameLayout? = null
    private var expandedControlBar: LinearLayout? = null
    private var collapsedControlView: FrameLayout? = null
    private var collapsedStatusIcon: ImageView? = null
    private var collapsedStatusBadge: View? = null
    private var statusView: TextView? = null
    private var actionButton: ImageButton? = null
    private var answerClickButton: ImageButton? = null
    private var swipeLeftButton: ImageButton? = null
    private var swipeUpButton: ImageButton? = null
    private var touchControlsDivider: View? = null
    private var retryButton: ImageButton? = null
    private var stopButton: ImageButton? = null
    private var controlLayoutParams: WindowManager.LayoutParams? = null
    private var isControlCollapsed = false
    private var isSnappedToRight = false
    private var latestRenderState: RenderState? = null
    private var lastMarkerRenderKey: MarkerRenderKey? = null
    private var pendingScreenScanHiddenAckGeneration = 0
    private var lastCollapsedRenderState: CollapsedRenderState? = null
    private var showControlWindow = true
    private var showMarkerOverlayWindow = true
    private var answerDotsOnly = true

    private var libId = 0
    private val controlSnapPaddingPx by lazy { dpToPx(CONTROL_SNAP_PADDING_DP) }
    private val touchSlopPx by lazy { ViewConfiguration.get(this).scaledTouchSlop }
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartWindowX = 0
    private var dragStartWindowY = 0
    private var isDraggingControlBar = false
    private var pendingDragX = 0
    private var pendingDragY = 0
    private var dragFramePosted = false
    private var snapAnimator: ValueAnimator? = null
    private val applyPendingDragPosition = Runnable {
        dragFramePosted = false
        val root = controlRootView ?: return@Runnable
        val params = controlLayoutParams ?: return@Runnable
        updateControlBarPosition(root, params, pendingDragX, pendingDragY)
    }

    override fun onCreate() {
        super.onCreate()
        lifecycleScope.launch {
            ScreenDetectorSession.state
                .combine(ScreenDetectorSession.matches) { state, matches -> state to matches }
                .combine(ScreenDetectorSession.screenFrameInfo) { (state, matches), frameInfo ->
                    RenderState(state, matches, frameInfo)
                }
                .combine(ScreenDetectorSession.mode) { renderState, mode ->
                    renderState.copy(mode = mode)
                }
                .combine(ScreenDetectorSession.answerClickState) { renderState, answerClickState ->
                    renderState.copy(answerClickState = answerClickState)
                }
                .combine(ScreenDetectorSession.assistanceState) { renderState, assistanceState ->
                    renderState.copy(assistanceState = assistanceState)
                }
                .combine(ScreenDetectorSession.screenScanState) { renderState, screenScanState ->
                    renderState.copy(screenScanState = screenScanState)
                }
                .collect { renderState ->
                    latestRenderState = renderState
                    if (showControlWindow) {
                        renderControlBar(renderState)
                    }
                    if (showMarkerOverlayWindow) {
                        renderMarkerOverlay(renderState)
                    }
                    updateStatusNotification(renderState)
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_TOUCH -> {
                ScreenDetectorSession.requestToggleAnswerAssistance()
                latestRenderState?.let(::updateStatusNotification)
                return START_NOT_STICKY
            }
            ACTION_STOP_DETECTION -> {
                ScreenDetectorSession.requestStop()
                cancelStatusNotification()
                return START_NOT_STICKY
            }
        }
        libId = intent?.getIntExtra(LIBRARY_ID, 0) ?: 0
        showControlWindow = intent?.getBooleanExtra(EXTRA_SHOW_FLOATING_WINDOWS, true) ?: true
        showMarkerOverlayWindow = intent?.getBooleanExtra(EXTRA_SHOW_MARKER_OVERLAY, true) ?: true
        answerDotsOnly = intent?.getBooleanExtra(EXTRA_ANSWER_DOTS_ONLY, true) ?: true
        if (showControlWindow || showMarkerOverlayWindow) {
            showWindows()
        } else {
            removeWindows()
        }
        latestRenderState?.let(::updateStatusNotification)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cancelSnapAnimator()
        controlRootView?.removeCallbacks(applyPendingDragPosition)
        removeViewIfAttached(markerOverlayView)
        markerOverlayView = null
        lastMarkerRenderKey = null
        pendingScreenScanHiddenAckGeneration = 0
        removeViewIfAttached(controlRootView)
        controlRootView = null
        expandedControlBar = null
        collapsedControlView = null
        collapsedStatusIcon = null
        collapsedStatusBadge = null
        lastCollapsedRenderState = null
        cancelStatusNotification()
        ScreenDetectorSession.clearOverlayBounds()
        ScreenDetectorSession.clearAnnotationBounds()
        super.onDestroy()
    }

    private fun showWindows() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        if (controlRootView == null) {
            isControlCollapsed = false
            isSnappedToRight = false
        }
        if (showMarkerOverlayWindow) {
            showMarkerOverlay()
        } else {
            removeMarkerOverlay()
        }
        if (showControlWindow) {
            showControlBar()
        } else {
            removeControlWindow()
        }
        val renderState = RenderState(
            state = ScreenDetectorSession.state.value,
            matches = ScreenDetectorSession.matches.value,
            frameInfo = ScreenDetectorSession.screenFrameInfo.value,
            mode = ScreenDetectorSession.mode.value,
            answerClickState = ScreenDetectorSession.answerClickState.value,
            assistanceState = ScreenDetectorSession.assistanceState.value
        )
        latestRenderState = renderState
        if (showControlWindow) {
            renderControlBar(renderState)
        }
        if (showMarkerOverlayWindow) {
            renderMarkerOverlay(renderState)
        }
    }

    private fun removeWindows() {
        removeMarkerOverlay()
        removeControlWindow()
    }

    private fun removeMarkerOverlay() {
        removeViewIfAttached(markerOverlayView)
        markerOverlayView = null
        lastMarkerRenderKey = null
        ScreenDetectorSession.clearAnnotationBounds()
    }

    private fun removeControlWindow() {
        cancelSnapAnimator()
        controlRootView?.removeCallbacks(applyPendingDragPosition)
        removeViewIfAttached(controlRootView)
        controlRootView = null
        expandedControlBar = null
        collapsedControlView = null
        collapsedStatusIcon = null
        collapsedStatusBadge = null
        statusView = null
        actionButton = null
        answerClickButton = null
        swipeLeftButton = null
        swipeUpButton = null
        touchControlsDivider = null
        retryButton = null
        stopButton = null
        controlLayoutParams = null
        lastCollapsedRenderState = null
        ScreenDetectorSession.clearOverlayBounds()
    }

    private fun showMarkerOverlay() {
        if (markerOverlayView != null) {
            return
        }
        val overlay = GraphicOverlay(this, null)
        val layoutParams = WindowManager.LayoutParams().apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            flags =
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            format = PixelFormat.RGBA_8888
            gravity = Gravity.TOP or Gravity.START
            alpha = MARKER_WINDOW_ALPHA
        }
        markerOverlayView = overlay
        windowManager.addView(overlay, layoutParams)
    }

    private fun showControlBar() {
        if (controlRootView != null) {
            return
        }
        val controlBar = createControlContainer()
        controlBar.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val windowBounds = getOverlayWindowBounds()
        val initialPositionRanges = getControlPositionRanges(
            windowBounds,
            controlBar.measuredWidth,
            controlBar.measuredHeight
        )
        val layoutParams = WindowManager.LayoutParams().apply {
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            flags =
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            format = PixelFormat.RGBA_8888
            gravity = Gravity.TOP or Gravity.START
            x = initialPositionRanges.horizontal.first
            y = initialPositionRanges.vertical.last
        }
        controlRootView = controlBar
        controlLayoutParams = layoutParams
        controlBar.addOnLayoutChangeListener { root, left, top, right, bottom,
                                               oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left == oldRight - oldLeft && bottom - top == oldBottom - oldTop) {
                return@addOnLayoutChangeListener
            }
            clampControlBarAfterLayout(root, layoutParams)
        }
        windowManager.addView(controlBar, layoutParams)
        publishControlBounds()
        controlBar.post {
            alignControlBarToBottom()
            publishControlBounds()
        }
    }

    private fun createControlContainer(): FrameLayout {
        return FrameLayout(ContextThemeWrapper(this@ScreenDetectorService, R.style.AppTheme)).apply {
            expandedControlBar = createExpandedControlBar()
            collapsedControlView = createCollapsedControlView()
            addView(
                expandedControlBar,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                collapsedControlView,
                FrameLayout.LayoutParams(dpToPx(COLLAPSED_SIZE_DP), dpToPx(COLLAPSED_SIZE_DP))
            )
            collapsedControlView?.visibility = View.GONE
        }
    }

    private fun createExpandedControlBar(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundResource(R.drawable.bg_overlay_window)
            elevation = dpToPx(8).toFloat()
            setPadding(dpToPx(10), dpToPx(2), dpToPx(10), dpToPx(7))
            setOnTouchListener { view, event -> handleControlBarTouch(view, event) }

            val dragHandleArea = FrameLayout(context).apply {
                contentDescription = "拖动悬浮控制"
                isClickable = true
                setOnTouchListener { view, event -> handleControlBarTouch(view, event) }
                addView(
                    View(context).apply {
                        background = createDragHandleBackground()
                    },
                    FrameLayout.LayoutParams(
                        dpToPx(DRAG_HANDLE_WIDTH_DP),
                        dpToPx(DRAG_HANDLE_HEIGHT_DP),
                        Gravity.CENTER
                    )
                )
            }
            addView(
                dragHandleArea,
                LinearLayout.LayoutParams(
                    dpToPx(STATUS_WIDTH_DP),
                    dpToPx(DRAG_HANDLE_TOUCH_HEIGHT_DP)
                )
            )

            statusView = TextView(context).apply {
                setTextColor(0xFF111111.toInt())
                textSize = 13f
                maxLines = 1
                gravity = Gravity.CENTER
                ellipsize = TextUtils.TruncateAt.END
                setOnTouchListener { view, event -> handleControlBarTouch(view, event) }
            }
            addView(
                statusView,
                LinearLayout.LayoutParams(
                    dpToPx(STATUS_WIDTH_DP),
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )

            val controlsRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            addView(
                controlsRow,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dpToPx(5)
                }
            )

            val collapseButton = createControlIconButton(
                iconRes = R.drawable.icon_hide_24px,
                description = "折叠悬浮窗"
            ).apply {
                setOnClickListener { setControlCollapsed(true) }
            }
            controlsRow.addView(
                collapseButton,
                LinearLayout.LayoutParams(dpToPx(32), dpToPx(32))
            )

            controlsRow.addView(
                View(context).apply {
                    setBackgroundColor(TOUCH_CONTROLS_DIVIDER_COLOR)
                },
                LinearLayout.LayoutParams(dpToPx(1), dpToPx(20)).apply {
                    leftMargin = dpToPx(CONTROL_BUTTON_GAP_DP)
                }
            )

            answerClickButton = createControlIconButton(
                iconRes = R.drawable.icon_touch_app_24px,
                description = "点击正确选项"
            ).apply {
                visibility = View.GONE
                setOnClickListener { ScreenDetectorSession.requestToggleAnswerAssistance() }
            }
            controlsRow.addView(
                answerClickButton,
                LinearLayout.LayoutParams(
                    dpToPx(32),
                    dpToPx(32)
                ).apply {
                    leftMargin = dpToPx(CONTROL_BUTTON_GAP_DP)
                }
            )

            swipeLeftButton = createControlIconButton(
                iconRes = R.drawable.round_arrow_forward_24,
                description = "左滑翻页"
            ).apply {
                visibility = View.GONE
                setOnClickListener { ScreenDetectorSession.requestSwipePageLeft() }
            }
            controlsRow.addView(
                swipeLeftButton,
                LinearLayout.LayoutParams(
                    dpToPx(32),
                    dpToPx(32)
                ).apply {
                    leftMargin = dpToPx(CONTROL_BUTTON_GAP_DP)
                }
            )

            swipeUpButton = createControlIconButton(
                iconRes = R.drawable.round_arrow_back_24,
                description = "上滑翻页"
            ).apply {
                rotation = -90f
                visibility = View.GONE
                setOnClickListener { ScreenDetectorSession.requestSwipePageUp() }
            }
            controlsRow.addView(
                swipeUpButton,
                LinearLayout.LayoutParams(
                    dpToPx(32),
                    dpToPx(32)
                ).apply {
                    leftMargin = dpToPx(CONTROL_BUTTON_GAP_DP)
                }
            )

            touchControlsDivider = View(context).apply {
                setBackgroundColor(TOUCH_CONTROLS_DIVIDER_COLOR)
                visibility = View.GONE
            }
            controlsRow.addView(
                touchControlsDivider,
                LinearLayout.LayoutParams(
                    dpToPx(1),
                    dpToPx(20)
                ).apply {
                    leftMargin = dpToPx(8)
                    rightMargin = dpToPx(2)
                }
            )

            actionButton = createControlIconButton(
                iconRes = R.drawable.round_pause_24,
                description = "暂停"
            ).apply {
                setOnClickListener { ScreenDetectorSession.requestPauseResume() }
            }
            controlsRow.addView(
                actionButton,
                LinearLayout.LayoutParams(
                    dpToPx(32),
                    dpToPx(32)
                ).apply {
                    leftMargin = dpToPx(CONTROL_BUTTON_GAP_DP)
                }
            )

            retryButton = createControlIconButton(
                iconRes = R.drawable.round_replay_24,
                description = "重试"
            ).apply {
                setOnClickListener { ScreenDetectorSession.requestRetryOnce() }
            }
            controlsRow.addView(
                retryButton,
                LinearLayout.LayoutParams(
                    dpToPx(32),
                    dpToPx(32)
                ).apply {
                    leftMargin = dpToPx(CONTROL_BUTTON_GAP_DP)
                }
            )

            stopButton = createControlIconButton(
                iconRes = R.drawable.round_close_24,
                description = "停止"
            ).apply {
                setOnClickListener { ScreenDetectorSession.requestStop() }
            }
            controlsRow.addView(
                stopButton,
                LinearLayout.LayoutParams(
                    dpToPx(32),
                    dpToPx(32)
                ).apply {
                    leftMargin = dpToPx(CONTROL_BUTTON_GAP_DP)
                }
            )
        }
    }

    private fun createCollapsedControlView(): FrameLayout {
        return FrameLayout(ContextThemeWrapper(this@ScreenDetectorService, R.style.AppTheme)).apply {
            setBackgroundResource(R.drawable.bg_overlay_window)
            elevation = dpToPx(8).toFloat()
            alpha = COLLAPSED_WINDOW_ALPHA
            isClickable = true
            contentDescription = "展开悬浮窗"

            collapsedStatusIcon = ImageView(context).apply {
                setImageResource(R.drawable.round_search_24)
                imageTintList = ColorStateList.valueOf(DEFAULT_ICON_COLOR)
                scaleType = ImageView.ScaleType.CENTER
            }
            addView(
                collapsedStatusIcon,
                FrameLayout.LayoutParams(dpToPx(24), dpToPx(24), Gravity.CENTER)
            )

            collapsedStatusBadge = View(context).apply {
                background = createStatusBadgeBackground(RUNNING_BADGE_COLOR)
            }
            addView(
                collapsedStatusBadge,
                FrameLayout.LayoutParams(
                    dpToPx(COLLAPSED_BADGE_SIZE_DP),
                    dpToPx(COLLAPSED_BADGE_SIZE_DP),
                    Gravity.TOP or Gravity.END
                ).apply {
                    topMargin = dpToPx(7)
                    rightMargin = dpToPx(7)
                }
            )
            setOnTouchListener { view, event ->
                handleControlBarTouch(view, event) {
                    setControlCollapsed(false)
                }
            }
        }
    }

    private fun handleControlBarTouch(
        view: View,
        event: MotionEvent,
        onClick: (() -> Unit)? = null
    ): Boolean {
        val root = controlRootView ?: return false
        val params = controlLayoutParams ?: return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelSnapAnimator()
                dragStartRawX = event.rawX
                dragStartRawY = event.rawY
                dragStartWindowX = params.x
                dragStartWindowY = params.y
                isDraggingControlBar = false
                view.parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaX = event.rawX - dragStartRawX
                val deltaY = event.rawY - dragStartRawY
                if (!isDraggingControlBar &&
                    abs(deltaX) < touchSlopPx &&
                    abs(deltaY) < touchSlopPx
                ) {
                    return true
                }
                isDraggingControlBar = true
                pendingDragX = dragStartWindowX + deltaX.roundToInt()
                pendingDragY = dragStartWindowY + deltaY.roundToInt()
                if (!dragFramePosted) {
                    dragFramePosted = true
                    root.postOnAnimation(applyPendingDragPosition)
                }
                return true
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                if (isDraggingControlBar) {
                    flushPendingDragPosition(root, params)
                    snapControlBarToHorizontalEdge(root, params)
                } else {
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        onClick?.invoke()
                    }
                    publishControlBounds()
                }
                view.parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return true
    }

    private fun updateControlBarPosition(
        root: View,
        params: WindowManager.LayoutParams,
        targetX: Int,
        targetY: Int
    ) {
        params.x = targetX
        params.y = targetY
        windowManager.updateViewLayout(root, params)
    }

    private fun flushPendingDragPosition(
        root: View,
        params: WindowManager.LayoutParams
    ) {
        if (!dragFramePosted) {
            return
        }
        root.removeCallbacks(applyPendingDragPosition)
        dragFramePosted = false
        updateControlBarPosition(root, params, pendingDragX, pendingDragY)
    }

    private fun clampControlBarAfterLayout(
        root: View,
        params: WindowManager.LayoutParams
    ) {
        val windowBounds = getOverlayWindowBounds()
        val positionRanges = getControlPositionRanges(
            windowBounds,
            root.width,
            root.height
        )
        val targetX = if (isSnappedToRight) {
            positionRanges.horizontal.last
        } else {
            params.x.coerceIn(positionRanges.horizontal)
        }
        val targetY = params.y.coerceIn(positionRanges.vertical)
        if (targetX != params.x || targetY != params.y) {
            updateControlBarPosition(root, params, targetX, targetY)
        }
        publishControlBounds()
    }

    private fun animateControlBarPosition(
        root: View,
        params: WindowManager.LayoutParams,
        targetX: Int,
        targetY: Int
    ) {
        val startX = params.x
        val startY = params.y
        if (startX == targetX && startY == targetY) {
            updateControlBarPosition(root, params, targetX, targetY)
            publishControlBounds()
            return
        }

        cancelSnapAnimator()
        snapAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SNAP_ANIMATION_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                val fraction = animator.animatedValue as Float
                val animatedX = startX + ((targetX - startX) * fraction).roundToInt()
                val animatedY = startY + ((targetY - startY) * fraction).roundToInt()
                updateControlBarPosition(root, params, animatedX, animatedY)
            }
            addListener(
                object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (snapAnimator === animation) {
                            snapAnimator = null
                            publishControlBounds()
                        }
                    }

                    override fun onAnimationCancel(animation: Animator) {
                        if (snapAnimator === animation) {
                            snapAnimator = null
                        }
                    }
                }
            )
            start()
        }
    }

    private fun cancelSnapAnimator() {
        snapAnimator?.cancel()
        snapAnimator = null
    }

    private fun snapControlBarToHorizontalEdge(
        root: View,
        params: WindowManager.LayoutParams
    ) {
        val windowBounds = getOverlayWindowBounds()
        val width = if (root.width > 0) root.width else 0
        val height = if (root.height > 0) root.height else 0
        val positionRanges = getControlPositionRanges(windowBounds, width, height)
        val targetX = if (params.x + width / 2 <= windowBounds.width / 2) {
            isSnappedToRight = false
            positionRanges.horizontal.first
        } else {
            isSnappedToRight = true
            positionRanges.horizontal.last
        }
        animateControlBarPosition(
            root,
            params,
            targetX,
            params.y.coerceIn(positionRanges.vertical)
        )
    }

    private fun setControlCollapsed(collapsed: Boolean) {
        if (isControlCollapsed == collapsed) {
            return
        }
        val root = controlRootView ?: return
        val params = controlLayoutParams ?: return
        val windowBounds = getOverlayWindowBounds()
        isSnappedToRight =
            params.x + root.width / 2 > windowBounds.width / 2
        isControlCollapsed = collapsed
        expandedControlBar?.visibility = if (collapsed) View.GONE else View.VISIBLE
        collapsedControlView?.visibility = if (collapsed) View.VISIBLE else View.GONE
        latestRenderState?.let(::renderControlBar)
        renderCurrentMarkerOverlay()
        root.requestLayout()
        root.post {
            val currentWindowBounds = getOverlayWindowBounds()
            val positionRanges = getControlPositionRanges(
                currentWindowBounds,
                root.width,
                root.height
            )
            val targetX = if (isSnappedToRight) {
                positionRanges.horizontal.last
            } else {
                positionRanges.horizontal.first
            }
            updateControlBarPosition(
                root,
                params,
                targetX,
                params.y.coerceIn(positionRanges.vertical)
            )
            publishControlBounds()
        }
    }

    private fun getControlPositionRanges(
        windowBounds: OverlayWindowBounds,
        controlWidth: Int,
        controlHeight: Int
    ): ControlPositionRanges {
        val insets = getControlWindowInsets()
        return ControlPositionRanges(
            horizontal = getContainedPositionRange(
                windowSize = windowBounds.width,
                controlSize = controlWidth,
                startInset = insets.left,
                endInset = insets.right
            ),
            vertical = getContainedPositionRange(
                windowSize = windowBounds.height,
                controlSize = controlHeight,
                startInset = insets.top,
                endInset = insets.bottom
            )
        )
    }

    private fun getContainedPositionRange(
        windowSize: Int,
        controlSize: Int,
        startInset: Int,
        endInset: Int
    ): IntRange {
        val minimum = startInset + controlSnapPaddingPx
        val maximum = windowSize - endInset - controlSize - controlSnapPaddingPx
        return if (maximum >= minimum) {
            minimum..maximum
        } else {
            startInset..maxOf(startInset, windowSize - endInset - controlSize)
        }
    }

    private fun createControlIconButton(
        @DrawableRes iconRes: Int,
        description: String
    ): ImageButton {
        return ImageButton(this).apply {
            contentDescription = description
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(0xFF1F2933.toInt())
            background = createIconButtonBackground()
            scaleType = ImageView.ScaleType.CENTER
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                dpToPx(32),
                dpToPx(32)
            )
        }
    }

    private fun alignControlBarToBottom() {
        val root = controlRootView ?: return
        val params = controlLayoutParams ?: return
        val windowBounds = getOverlayWindowBounds()
        val positionRanges = getControlPositionRanges(
            windowBounds,
            root.width,
            root.height
        )
        params.x = positionRanges.horizontal.first
        params.y = positionRanges.vertical.last
        windowManager.updateViewLayout(root, params)
    }

    private fun renderControlBar(renderState: RenderState) {
        if (isControlCollapsed) {
            renderCollapsedState(renderState)
            return
        }
        val state = renderState.state
        statusView?.text = buildStatusText(renderState)
        when (state) {
            ScreenDetectorSession.DetectionState.RUNNING -> {
                actionButton?.setImageResource(R.drawable.round_pause_24)
                actionButton?.contentDescription = "暂停"
                actionButton?.visibility = View.VISIBLE
                retryButton?.visibility = View.GONE
                stopButton?.visibility = View.VISIBLE
            }
            ScreenDetectorSession.DetectionState.PAUSED -> {
                actionButton?.setImageResource(R.drawable.round_play_arrow_24)
                actionButton?.contentDescription = "继续"
                actionButton?.visibility = View.VISIBLE
                retryButton?.visibility = View.VISIBLE
                stopButton?.visibility = View.VISIBLE
            }
            ScreenDetectorSession.DetectionState.STOPPED -> {
                actionButton?.visibility = View.GONE
                retryButton?.visibility = View.GONE
                stopButton?.visibility = View.GONE
            }
        }
        renderAnswerClickButton(renderState)
        renderManualPageButtons(renderState)
        renderCollapsedState(renderState)
    }

    private fun renderCollapsedState(renderState: RenderState) {
        val icon = collapsedStatusIcon ?: return
        val badge = collapsedStatusBadge ?: return
        val assistance = renderState.assistanceState
        val isPaused = renderState.state == ScreenDetectorSession.DetectionState.PAUSED
        val isError = assistance.indicator == ScreenDetectorSession.AssistanceIndicator.ERROR
        val iconRes = when {
            isError -> R.drawable.icon_error_24px
            isPaused -> R.drawable.round_play_arrow_24
            assistance.indicator == ScreenDetectorSession.AssistanceIndicator.SWIPE_LEFT ->
                R.drawable.round_arrow_forward_24
            assistance.indicator == ScreenDetectorSession.AssistanceIndicator.SWIPE_UP ->
                R.drawable.round_arrow_back_24
            assistance.isActive -> R.drawable.icon_touch_app_24px
            else -> R.drawable.round_search_24
        }
        val iconRotation = if (
            assistance.indicator == ScreenDetectorSession.AssistanceIndicator.SWIPE_UP
        ) {
            -90f
        } else {
            0f
        }
        val color = if (isError) ERROR_ICON_COLOR else DEFAULT_ICON_COLOR

        val description = when {
            isError -> "搜题流程异常，点击展开"
            isPaused -> "搜题已暂停，点击展开"
            assistance.indicator == ScreenDetectorSession.AssistanceIndicator.SWIPE_LEFT ->
                "正在左滑翻页，点击展开"
            assistance.indicator == ScreenDetectorSession.AssistanceIndicator.SWIPE_UP ->
                "正在上滑，点击展开"
            assistance.isActive -> "自动答题中，点击展开"
            else -> "正在扫描，点击展开"
        }
        val badgeColor = when {
            isError -> ERROR_ICON_COLOR
            isPaused -> PAUSED_BADGE_COLOR
            assistance.indicator == ScreenDetectorSession.AssistanceIndicator.SWIPE_LEFT ||
                assistance.indicator == ScreenDetectorSession.AssistanceIndicator.SWIPE_UP ->
                RUNNING_BADGE_COLOR
            assistance.indicator == ScreenDetectorSession.AssistanceIndicator.TOUCH ||
                assistance.isActive -> ASSISTANCE_BADGE_COLOR
            renderState.state == ScreenDetectorSession.DetectionState.RUNNING -> RUNNING_BADGE_COLOR
            else -> IDLE_BADGE_COLOR
        }
        val collapsedState = CollapsedRenderState(
            iconRes = iconRes,
            iconRotation = iconRotation,
            iconColor = color,
            badgeColor = badgeColor,
            contentDescription = description
        )
        val previousState = lastCollapsedRenderState
        if (previousState?.iconRes != collapsedState.iconRes) {
            icon.setImageResource(iconRes)
        }
        if (previousState?.iconRotation != collapsedState.iconRotation) {
            icon.rotation = iconRotation
        }
        if (previousState?.iconColor != collapsedState.iconColor) {
            icon.imageTintList = ColorStateList.valueOf(color)
        }
        if (previousState?.badgeColor != collapsedState.badgeColor) {
            badge.background = createStatusBadgeBackground(badgeColor)
        }
        if (previousState?.contentDescription != collapsedState.contentDescription) {
            collapsedControlView?.contentDescription = description
        }
        lastCollapsedRenderState = collapsedState
    }

    private fun renderAnswerClickButton(renderState: RenderState) {
        val button = answerClickButton ?: return
        val isAccessibilityMode =
            renderState.mode == ScreenDetectorSession.DetectionMode.ACCESSIBILITY
        button.visibility = if (
            isAccessibilityMode &&
            renderState.state != ScreenDetectorSession.DetectionState.STOPPED
        ) {
            View.VISIBLE
        } else {
            View.GONE
        }
        touchControlsDivider?.visibility = button.visibility
        if (button.visibility != View.VISIBLE) {
            return
        }

        val plan = ScreenDetectorSession.buildAnswerClickPlan()
        val clickState = renderState.answerClickState
        val hasTargets = plan?.targets?.any { it.isComplete } == true ||
            plan?.bottomClippedTarget != null
        val assistanceActive = renderState.assistanceState.isActive
        button.isEnabled = if (assistanceActive) {
            true
        } else {
            hasTargets && renderState.state == ScreenDetectorSession.DetectionState.RUNNING
        }
        button.imageAlpha = if (button.isEnabled) ENABLED_IMAGE_ALPHA else DISABLED_IMAGE_ALPHA
        button.imageTintList = ColorStateList.valueOf(
            if (assistanceActive) SELECTED_ICON_COLOR else DEFAULT_ICON_COLOR
        )
        button.contentDescription = when {
            assistanceActive -> "关闭自动答题"
            clickState.isClicking -> "正在点击正确选项"
            !hasTargets -> "暂无可点击的正确选项"
            else -> "启动连续辅助答题"
        }
    }

    private fun renderManualPageButtons(renderState: RenderState) {
        val waitingForDirection =
            renderState.mode == ScreenDetectorSession.DetectionMode.ACCESSIBILITY &&
                renderState.state == ScreenDetectorSession.DetectionState.RUNNING &&
                renderState.assistanceState.phase ==
                ScreenDetectorSession.AssistancePhase.WAITING_MANUAL_PAGE
        val leftSelected =
            renderState.assistanceState.pageDirection == ScreenDetectorSession.PageDirection.LEFT
        val upSelected =
            renderState.assistanceState.pageDirection == ScreenDetectorSession.PageDirection.UP
        val showLeft = waitingForDirection || leftSelected
        val showUp = waitingForDirection || upSelected
        swipeLeftButton?.visibility = if (showLeft) View.VISIBLE else View.GONE
        swipeUpButton?.visibility = if (showUp) View.VISIBLE else View.GONE
        swipeLeftButton?.isEnabled = leftSelected || waitingForDirection
        swipeUpButton?.isEnabled = upSelected || waitingForDirection
        swipeLeftButton?.imageTintList = ColorStateList.valueOf(
            if (leftSelected) SELECTED_ICON_COLOR else DEFAULT_ICON_COLOR
        )
        swipeUpButton?.imageTintList = ColorStateList.valueOf(
            if (upSelected) SELECTED_ICON_COLOR else DEFAULT_ICON_COLOR
        )
        swipeLeftButton?.contentDescription = if (leftSelected) {
            "取消自动左滑"
        } else {
            "选择自动左滑"
        }
        swipeUpButton?.contentDescription = if (upSelected) {
            "取消自动上滑"
        } else {
            "选择自动上滑"
        }
        val statusWidth = dpToPx(STATUS_WIDTH_DP)
        val statusLayoutParams = statusView?.layoutParams
        if (statusLayoutParams != null && statusLayoutParams.width != statusWidth) {
            statusLayoutParams.width = statusWidth
            statusView?.layoutParams = statusLayoutParams
            controlRootView?.requestLayout()
        }
    }

    private fun createIconButtonBackground(): RippleDrawable {
        val content = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(16).toFloat()
            setColor(0xFFE8EEF8.toInt())
        }
        return RippleDrawable(
            ColorStateList.valueOf(Color.argb(32, 17, 24, 39)),
            content,
            null
        )
    }

    private fun createStatusBadgeBackground(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(dpToPx(1), COLLAPSED_BADGE_STROKE_COLOR)
        }
    }

    private fun createDragHandleBackground(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(DRAG_HANDLE_HEIGHT_DP).toFloat() / 2f
            setColor(DRAG_HANDLE_COLOR)
        }
    }

    private fun buildStatusText(renderState: RenderState): String {
        val assistanceText = renderState.assistanceState.statusText
        if (renderState.mode == ScreenDetectorSession.DetectionMode.ACCESSIBILITY &&
            assistanceText.isNotBlank()
        ) {
            return assistanceText
        }
        val prefix = when (renderState.state) {
            ScreenDetectorSession.DetectionState.RUNNING -> "识别中"
            ScreenDetectorSession.DetectionState.PAUSED -> "已暂停"
            ScreenDetectorSession.DetectionState.STOPPED -> "已停止"
        }
        return "$prefix · ${renderState.matches.size} 题"
    }

    private fun updateStatusNotification(renderState: RenderState) {
        if (renderState.state == ScreenDetectorSession.DetectionState.STOPPED) {
            cancelStatusNotification()
            return
        }
        createStatusNotificationChannel()
        NotificationManagerCompat.from(this).notify(
            STATUS_NOTIFICATION_ID,
            buildStatusNotification(renderState)
        )
    }

    private fun buildStatusNotification(renderState: RenderState): Notification {
        val assistanceActive = renderState.assistanceState.isActive
        val touchTitle = if (assistanceActive) {
            "关闭Touch"
        } else {
            "开启Touch"
        }
        val touchIcon = if (assistanceActive) {
            R.drawable.round_pause_24
        } else {
            R.drawable.icon_touch_app_24px
        }
        val touchAction = NotificationCompat.Action.Builder(
            touchIcon,
            touchTitle,
            createServiceActionPendingIntent(ACTION_TOGGLE_TOUCH, REQUEST_TOGGLE_TOUCH)
        ).build()
        val stopAction = NotificationCompat.Action.Builder(
            R.drawable.round_close_24,
            "停止",
            createServiceActionPendingIntent(ACTION_STOP_DETECTION, REQUEST_STOP_DETECTION)
        ).build()
        return NotificationCompat.Builder(this, STATUS_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.round_search_24)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(buildStatusText(renderState))
            .setOngoing(renderState.state != ScreenDetectorSession.DetectionState.STOPPED)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .addAction(touchAction)
            .addAction(stopAction)
            .build()
    }

    private fun createStatusNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            STATUS_NOTIFICATION_CHANNEL_ID,
            "搜题状态",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun createServiceActionPendingIntent(action: String, requestCode: Int): PendingIntent {
        return PendingIntent.getService(
            this,
            requestCode,
            Intent(this, ScreenDetectorService::class.java).apply {
                this.action = action
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun cancelStatusNotification() {
        NotificationManagerCompat.from(this).cancel(STATUS_NOTIFICATION_ID)
    }

    private fun renderMarkerOverlay(renderState: RenderState) {
        val overlay = markerOverlayView ?: return
        val state = renderState.state
        val matches = renderState.matches
        val frameInfo = renderState.frameInfo
        if (
            renderState.mode == ScreenDetectorSession.DetectionMode.SCREEN_OCR &&
            renderState.screenScanState.hideResults
        ) {
            clearMarkerOverlayForScreenScan(
                overlay,
                renderState.screenScanState.generation
            )
            return
        }
        if (state == ScreenDetectorSession.DetectionState.STOPPED || frameInfo == null) {
            clearMarkerOverlay(overlay)
            return
        }

        val overlayFingerprint = ScreenDetectorSession.buildOverlayFingerprint(matches)
        val useTouchAnswerDots =
            renderState.mode == ScreenDetectorSession.DetectionMode.ACCESSIBILITY &&
                answerDotsOnly
        val showQuestionAnnotations = !useTouchAnswerDots
        val showAnswerFrames = true
        val markerRenderKey = MarkerRenderKey(
            state = state,
            frameInfo = frameInfo,
            overlayFingerprint = overlayFingerprint,
            showQuestionAnnotations = showQuestionAnnotations,
            showAnswerFrames = showAnswerFrames,
            useTouchAnswerDots = useTouchAnswerDots
        )
        if (lastMarkerRenderKey == markerRenderKey) {
            return
        }

        lastMarkerRenderKey = markerRenderKey
        overlay.setImageSourceInfo(frameInfo.width, frameInfo.height, false)
        overlay.clear()
        if (matches.isNotEmpty() && overlayFingerprint != null) {
            overlay.add(
                ScreenMatchGraphic(
                    overlay,
                    matches,
                    frameInfo,
                    getOverlayWindowBounds(),
                    overlayFingerprint,
                    showQuestionAnnotations,
                    showAnswerFrames,
                    useTouchAnswerDots
                )
            )
        } else {
            ScreenDetectorSession.clearAnnotationBounds()
        }
        overlay.postInvalidate()
    }

    private fun renderCurrentMarkerOverlay() {
        val renderState = latestRenderState ?: return
        lastMarkerRenderKey = null
        renderMarkerOverlay(renderState)
    }

    private fun clearMarkerOverlay(overlay: GraphicOverlay) {
        overlay.clear()
        ScreenDetectorSession.clearAnnotationBounds()
        lastMarkerRenderKey = null
    }

    private fun clearMarkerOverlayForScreenScan(
        overlay: GraphicOverlay,
        generation: Int
    ) {
        clearMarkerOverlay(overlay)
        if (generation <= pendingScreenScanHiddenAckGeneration) {
            return
        }
        pendingScreenScanHiddenAckGeneration = generation
        val observer = overlay.viewTreeObserver
        if (!observer.isAlive) {
            ScreenDetectorSession.acknowledgeScreenResultsHidden(generation)
            return
        }
        val listener = object : ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                overlay.post {
                    if (overlay.viewTreeObserver.isAlive) {
                        overlay.viewTreeObserver.removeOnDrawListener(this)
                    }
                    ScreenDetectorSession.acknowledgeScreenResultsHidden(generation)
                }
            }
        }
        observer.addOnDrawListener(listener)
        overlay.postInvalidateOnAnimation()
    }

    private fun publishControlBounds() {
        val root = controlRootView ?: return
        val params = controlLayoutParams ?: return
        val width = if (root.width > 0) root.width else params.width
        val height = if (root.height > 0) root.height else params.height
        if (width <= 0 || height <= 0) {
            return
        }

        val location = IntArray(2)
        root.getLocationOnScreen(location)
        val left = if (location[0] != 0 || location[1] != 0) location[0] else params.x
        val top = if (location[0] != 0 || location[1] != 0) location[1] else params.y
        ScreenDetectorSession.publishOverlayBounds(
            Rect(left, top, left + width, top + height)
        )
    }

    private fun getOverlayWindowBounds(): OverlayWindowBounds {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            OverlayWindowBounds(bounds.width(), bounds.height())
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            OverlayWindowBounds(metrics.widthPixels, metrics.heightPixels)
        }
    }

    private fun getControlWindowInsets(): ControlWindowInsets {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insets = windowManager.currentWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() or
                        WindowInsets.Type.displayCutout() or
                        WindowInsets.Type.mandatorySystemGestures()
                )
            return ControlWindowInsets(
                left = insets.left,
                top = insets.top,
                right = insets.right,
                bottom = insets.bottom
            )
        }
        return ControlWindowInsets(
            top = getSystemDimension("status_bar_height"),
            bottom = getSystemDimension("navigation_bar_height")
        )
    }

    private fun getSystemDimension(name: String): Int {
        val resourceId = resources.getIdentifier(name, "dimen", "android")
        return if (resourceId != 0) resources.getDimensionPixelSize(resourceId) else 0
    }

    private fun removeViewIfAttached(view: View?) {
        if (::windowManager.isInitialized && view != null) {
            windowManager.removeView(view)
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    data class OverlayWindowBounds(
        val width: Int,
        val height: Int
    )

    private data class ControlPositionRanges(
        val horizontal: IntRange,
        val vertical: IntRange
    )

    private data class ControlWindowInsets(
        val left: Int = 0,
        val top: Int = 0,
        val right: Int = 0,
        val bottom: Int = 0
    )

    private data class RenderState(
        val state: ScreenDetectorSession.DetectionState,
        val matches: List<QuizGraphicItem>,
        val frameInfo: ScreenDetectorSession.ScreenFrameInfo?,
        val mode: ScreenDetectorSession.DetectionMode = ScreenDetectorSession.DetectionMode.NONE,
        val answerClickState: ScreenDetectorSession.AnswerClickState =
            ScreenDetectorSession.AnswerClickState(),
        val assistanceState: ScreenDetectorSession.AssistanceState =
            ScreenDetectorSession.AssistanceState(),
        val screenScanState: ScreenDetectorSession.ScreenScanState =
            ScreenDetectorSession.ScreenScanState()
    )

    private data class MarkerRenderKey(
        val state: ScreenDetectorSession.DetectionState,
        val frameInfo: ScreenDetectorSession.ScreenFrameInfo,
        val overlayFingerprint: String?,
        val showQuestionAnnotations: Boolean,
        val showAnswerFrames: Boolean,
        val useTouchAnswerDots: Boolean
    )

    private data class CollapsedRenderState(
        val iconRes: Int,
        val iconRotation: Float,
        val iconColor: Int,
        val badgeColor: Int,
        val contentDescription: String
    )

    companion object {
        const val LIBRARY_ID = "LibraryId"
        const val EXTRA_SHOW_FLOATING_WINDOWS = "ShowFloatingWindows"
        const val EXTRA_SHOW_MARKER_OVERLAY = "ShowMarkerOverlay"
        const val EXTRA_ANSWER_DOTS_ONLY = "AnswerDotsOnly"
        private const val ACTION_TOGGLE_TOUCH =
            "com.virin.visionquiz.screendetector.action.TOGGLE_TOUCH"
        private const val ACTION_STOP_DETECTION =
            "com.virin.visionquiz.screendetector.action.STOP_DETECTION"
        private const val STATUS_NOTIFICATION_CHANNEL_ID = "quiz_search_status"
        private const val STATUS_NOTIFICATION_ID = 2307
        private const val REQUEST_TOGGLE_TOUCH = 2308
        private const val REQUEST_STOP_DETECTION = 2309
        private const val MARKER_WINDOW_ALPHA = 0.74f
        private const val SNAP_ANIMATION_DURATION_MS = 180L
        private const val CONTROL_SNAP_PADDING_DP = 8
        private const val ENABLED_IMAGE_ALPHA = 255
        private const val DISABLED_IMAGE_ALPHA = 96
        private const val STATUS_WIDTH_DP = 176
        private const val CONTROL_BUTTON_GAP_DP = 8
        private const val DRAG_HANDLE_WIDTH_DP = 36
        private const val DRAG_HANDLE_HEIGHT_DP = 4
        private const val DRAG_HANDLE_TOUCH_HEIGHT_DP = 12
        private const val COLLAPSED_SIZE_DP = 52
        private const val COLLAPSED_WINDOW_ALPHA = 0.7f
        private const val COLLAPSED_BADGE_SIZE_DP = 10
        private val DEFAULT_ICON_COLOR = 0xFF1F2933.toInt()
        private val SELECTED_ICON_COLOR = 0xFF1565C0.toInt()
        private val ERROR_ICON_COLOR = 0xFFC62828.toInt()
        private val PAUSED_BADGE_COLOR = 0xFFF9A825.toInt()
        private val ASSISTANCE_BADGE_COLOR = 0xFF2E7D32.toInt()
        private val RUNNING_BADGE_COLOR = 0xFF1565C0.toInt()
        private val IDLE_BADGE_COLOR = 0xFF9AA3AF.toInt()
        private val COLLAPSED_BADGE_STROKE_COLOR = 0xFFFFFFFF.toInt()
        private val TOUCH_CONTROLS_DIVIDER_COLOR = 0x401F2933
        private val DRAG_HANDLE_COLOR = 0x521F2933
    }
}
