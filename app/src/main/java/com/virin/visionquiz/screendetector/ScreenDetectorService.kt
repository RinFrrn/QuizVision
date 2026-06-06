package com.virin.visionquiz.screendetector

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.SharedPreferences
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.preference.PreferenceManager
import android.text.TextUtils
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ContextThemeWrapper
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.android.material.progressindicator.CircularProgressIndicator
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
    private var collapsedProgress: CircularProgressIndicator? = null
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
    private var markerOverlayHiddenForCollapse = false
    private var lastCollapsedRenderState: CollapsedRenderState? = null
    private lateinit var sharedPreferences: SharedPreferences
    private val preferenceChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == getString(R.string.pref_key_hide_question_annotations_when_overlay_collapsed) ||
                key == getString(R.string.pref_key_hide_answer_frames_when_overlay_collapsed)
            ) {
                renderCurrentMarkerOverlay()
            }
        }

    private var libId = 0
    private val overlayMarginPx by lazy { dpToPx(16) }
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
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        sharedPreferences.registerOnSharedPreferenceChangeListener(preferenceChangeListener)
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
                .collect { renderState ->
                    latestRenderState = renderState
                    renderControlBar(renderState)
                    renderMarkerOverlay(renderState)
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        libId = intent?.getIntExtra(LIBRARY_ID, 0) ?: 0
        showWindows()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener)
        cancelSnapAnimator()
        controlRootView?.removeCallbacks(applyPendingDragPosition)
        removeViewIfAttached(markerOverlayView)
        markerOverlayView = null
        lastMarkerRenderKey = null
        markerOverlayHiddenForCollapse = false
        removeViewIfAttached(controlRootView)
        controlRootView = null
        expandedControlBar = null
        collapsedControlView = null
        collapsedStatusIcon = null
        collapsedProgress = null
        lastCollapsedRenderState = null
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
        showMarkerOverlay()
        showControlBar()
        val renderState = RenderState(
            state = ScreenDetectorSession.state.value,
            matches = ScreenDetectorSession.matches.value,
            frameInfo = ScreenDetectorSession.screenFrameInfo.value,
            mode = ScreenDetectorSession.mode.value,
            answerClickState = ScreenDetectorSession.answerClickState.value,
            assistanceState = ScreenDetectorSession.assistanceState.value
        )
        latestRenderState = renderState
        renderControlBar(renderState)
        renderMarkerOverlay(renderState)
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
        val layoutParams = WindowManager.LayoutParams().apply {
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            flags =
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            format = PixelFormat.RGBA_8888
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = maxOf(0, getOverlayWindowBounds().height - dpToPx(72))
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
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_overlay_window)
            elevation = dpToPx(8).toFloat()
            setPadding(dpToPx(10), dpToPx(6), dpToPx(10), dpToPx(6))

            val collapseButton = createControlIconButton(
                iconRes = R.drawable.icon_collapse_all_24px,
                description = "折叠悬浮窗"
            ).apply {
                setOnClickListener { setControlCollapsed(true) }
            }
            addView(
                collapseButton,
                LinearLayout.LayoutParams(dpToPx(32), dpToPx(32))
            )

            statusView = TextView(context).apply {
                setTextColor(0xFF111111.toInt())
                textSize = 13f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setOnTouchListener { view, event -> handleControlBarTouch(view, event) }
            }
            addView(
                statusView,
                LinearLayout.LayoutParams(
                    dpToPx(STATUS_WIDTH_DP),
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    leftMargin = dpToPx(6)
                }
            )

            answerClickButton = createControlIconButton(
                iconRes = R.drawable.icon_touch_app_24px,
                description = "点击正确选项"
            ).apply {
                visibility = View.GONE
                setOnClickListener { ScreenDetectorSession.requestToggleAnswerAssistance() }
            }
            addView(
                answerClickButton,
                LinearLayout.LayoutParams(
                    dpToPx(32),
                    dpToPx(32)
                ).apply {
                    leftMargin = dpToPx(6)
                }
            )

            swipeLeftButton = createControlIconButton(
                iconRes = R.drawable.round_arrow_forward_24,
                description = "左滑翻页"
            ).apply {
                visibility = View.GONE
                setOnClickListener { ScreenDetectorSession.requestSwipePageLeft() }
            }
            addView(
                swipeLeftButton,
                LinearLayout.LayoutParams(
                    dpToPx(32),
                    dpToPx(32)
                ).apply {
                    leftMargin = dpToPx(6)
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
            addView(
                swipeUpButton,
                LinearLayout.LayoutParams(
                    dpToPx(32),
                    dpToPx(32)
                ).apply {
                    leftMargin = dpToPx(6)
                }
            )

            touchControlsDivider = View(context).apply {
                setBackgroundColor(TOUCH_CONTROLS_DIVIDER_COLOR)
                visibility = View.GONE
            }
            addView(
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
            addView(
                actionButton,
                LinearLayout.LayoutParams(
                    dpToPx(32),
                    dpToPx(32)
                ).apply {
                    leftMargin = dpToPx(6)
                }
            )

            retryButton = createControlIconButton(
                iconRes = R.drawable.round_replay_24,
                description = "重试"
            ).apply {
                setOnClickListener { ScreenDetectorSession.requestRetryOnce() }
            }
            addView(
                retryButton,
                LinearLayout.LayoutParams(
                    dpToPx(32),
                    dpToPx(32)
                ).apply {
                    leftMargin = dpToPx(6)
                }
            )

            stopButton = createControlIconButton(
                iconRes = R.drawable.round_close_24,
                description = "停止"
            ).apply {
                setOnClickListener { ScreenDetectorSession.requestStop() }
            }
            addView(
                stopButton,
                LinearLayout.LayoutParams(
                    dpToPx(32),
                    dpToPx(32)
                ).apply {
                    leftMargin = dpToPx(6)
                }
            )
            setOnTouchListener { view, event -> handleControlBarTouch(view, event) }
        }
    }

    private fun createCollapsedControlView(): FrameLayout {
        return FrameLayout(ContextThemeWrapper(this@ScreenDetectorService, R.style.AppTheme)).apply {
            setBackgroundResource(R.drawable.bg_overlay_window)
            elevation = dpToPx(8).toFloat()
            isClickable = true
            contentDescription = "展开悬浮窗"

            collapsedProgress = CircularProgressIndicator(context).apply {
                indicatorSize = dpToPx(COLLAPSED_PROGRESS_SIZE_DP)
                trackThickness = dpToPx(3)
                setIndicatorColor(SELECTED_ICON_COLOR)
                trackColor = COLLAPSED_PROGRESS_TRACK_COLOR
                isIndeterminate = true
            }
            addView(
                collapsedProgress,
                FrameLayout.LayoutParams(
                    dpToPx(COLLAPSED_PROGRESS_SIZE_DP),
                    dpToPx(COLLAPSED_PROGRESS_SIZE_DP),
                    Gravity.CENTER
                )
            )

            collapsedStatusIcon = ImageView(context).apply {
                setImageResource(R.drawable.round_search_24)
                imageTintList = ColorStateList.valueOf(DEFAULT_ICON_COLOR)
                scaleType = ImageView.ScaleType.CENTER
            }
            addView(
                collapsedStatusIcon,
                FrameLayout.LayoutParams(dpToPx(24), dpToPx(24), Gravity.CENTER)
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
        val windowBounds = getOverlayWindowBounds()
        val width = if (root.width > 0) root.width else 0
        val height = if (root.height > 0) root.height else 0
        params.x = targetX.coerceIn(0, maxOf(0, windowBounds.width - width))
        params.y = targetY.coerceIn(0, maxOf(0, windowBounds.height - height))
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
        val maxX = maxOf(0, windowBounds.width - root.width)
        val targetX = if (isSnappedToRight) maxX else params.x.coerceIn(0, maxX)
        val targetY = params.y.coerceIn(0, maxOf(0, windowBounds.height - root.height))
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
        val maxX = maxOf(0, windowBounds.width - width)
        val targetX = if (params.x + width / 2 <= windowBounds.width / 2) {
            isSnappedToRight = false
            0
        } else {
            isSnappedToRight = true
            maxX
        }
        animateControlBarPosition(root, params, targetX, params.y)
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
            val targetX = if (isSnappedToRight) {
                maxOf(0, getOverlayWindowBounds().width - root.width)
            } else {
                0
            }
            updateControlBarPosition(root, params, targetX, params.y)
            publishControlBounds()
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
        params.x = 0
        params.y = maxOf(0, windowBounds.height - root.height - overlayMarginPx)
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
        val progress = collapsedProgress ?: return
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

        val isWorking = when {
            isPaused || isError -> false
            !assistance.isActive -> renderState.state == ScreenDetectorSession.DetectionState.RUNNING
            assistance.phase in ACTIVE_ASSISTANCE_PHASES -> true
            else -> false
        }
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
        val collapsedState = CollapsedRenderState(
            iconRes = iconRes,
            iconRotation = iconRotation,
            iconColor = color,
            progressColor = if (isError) ERROR_ICON_COLOR else SELECTED_ICON_COLOR,
            trackColor = if (isError) ERROR_PROGRESS_TRACK_COLOR else COLLAPSED_PROGRESS_TRACK_COLOR,
            isWorking = isWorking,
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
        if (previousState?.progressColor != collapsedState.progressColor) {
            progress.setIndicatorColor(collapsedState.progressColor)
        }
        if (previousState?.trackColor != collapsedState.trackColor) {
            progress.trackColor = collapsedState.trackColor
        }
        if (previousState?.isWorking != collapsedState.isWorking) {
            progress.isIndeterminate = isWorking
        }
        if (!isWorking) {
            progress.setProgressCompat(COLLAPSED_STATIC_PROGRESS, false)
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
        val statusWidth = dpToPx(
            if (showLeft || showUp) MANUAL_PAGE_STATUS_WIDTH_DP else STATUS_WIDTH_DP
        )
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

    private fun renderMarkerOverlay(renderState: RenderState) {
        val overlay = markerOverlayView ?: return
        val state = renderState.state
        val matches = renderState.matches
        val frameInfo = renderState.frameInfo
        if (state == ScreenDetectorSession.DetectionState.STOPPED || frameInfo == null) {
            clearMarkerOverlay(overlay)
            return
        }

        val overlayFingerprint = ScreenDetectorSession.buildOverlayFingerprint(matches)
        if (!shouldRenderMarkerOverlay(renderState, overlayFingerprint)) {
            if (!markerOverlayHiddenForCollapse) {
                clearMarkerOverlay(overlay)
                markerOverlayHiddenForCollapse = true
            }
            return
        }

        val showQuestionAnnotations =
            !isControlCollapsed ||
                !PreferenceUtils.shouldHideQuestionAnnotationsWhenOverlayCollapsed(this)
        val showAnswerFrames =
            !isControlCollapsed ||
                !PreferenceUtils.shouldHideAnswerFramesWhenOverlayCollapsed(this)
        val markerRenderKey = MarkerRenderKey(
            state = state,
            frameInfo = frameInfo,
            overlayFingerprint = overlayFingerprint,
            showQuestionAnnotations = showQuestionAnnotations,
            showAnswerFrames = showAnswerFrames
        )
        if (lastMarkerRenderKey == markerRenderKey) {
            return
        }

        markerOverlayHiddenForCollapse = false
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
                    showAnswerFrames
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
        markerOverlayHiddenForCollapse = false
        renderMarkerOverlay(renderState)
    }

    private fun shouldRenderMarkerOverlay(
        renderState: RenderState,
        overlayFingerprint: String?
    ): Boolean {
        if (!isControlCollapsed) {
            return true
        }
        if (renderState.assistanceState.isActive) {
            return true
        }
        return overlayFingerprint == null
    }

    private fun clearMarkerOverlay(overlay: GraphicOverlay) {
        overlay.clear()
        ScreenDetectorSession.clearAnnotationBounds()
        lastMarkerRenderKey = null
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

    private data class RenderState(
        val state: ScreenDetectorSession.DetectionState,
        val matches: List<QuizGraphicItem>,
        val frameInfo: ScreenDetectorSession.ScreenFrameInfo?,
        val mode: ScreenDetectorSession.DetectionMode = ScreenDetectorSession.DetectionMode.NONE,
        val answerClickState: ScreenDetectorSession.AnswerClickState =
            ScreenDetectorSession.AnswerClickState(),
        val assistanceState: ScreenDetectorSession.AssistanceState =
            ScreenDetectorSession.AssistanceState()
    )

    private data class MarkerRenderKey(
        val state: ScreenDetectorSession.DetectionState,
        val frameInfo: ScreenDetectorSession.ScreenFrameInfo,
        val overlayFingerprint: String?,
        val showQuestionAnnotations: Boolean,
        val showAnswerFrames: Boolean
    )

    private data class CollapsedRenderState(
        val iconRes: Int,
        val iconRotation: Float,
        val iconColor: Int,
        val progressColor: Int,
        val trackColor: Int,
        val isWorking: Boolean,
        val contentDescription: String
    )

    companion object {
        const val LIBRARY_ID = "LibraryId"
        private const val MARKER_WINDOW_ALPHA = 0.74f
        private const val SNAP_ANIMATION_DURATION_MS = 180L
        private const val ENABLED_IMAGE_ALPHA = 255
        private const val DISABLED_IMAGE_ALPHA = 96
        private const val STATUS_WIDTH_DP = 176
        private const val MANUAL_PAGE_STATUS_WIDTH_DP = 112
        private const val COLLAPSED_SIZE_DP = 52
        private const val COLLAPSED_PROGRESS_SIZE_DP = 40
        private const val COLLAPSED_STATIC_PROGRESS = 100
        private val DEFAULT_ICON_COLOR = 0xFF1F2933.toInt()
        private val SELECTED_ICON_COLOR = 0xFF1565C0.toInt()
        private val ERROR_ICON_COLOR = 0xFFC62828.toInt()
        private val COLLAPSED_PROGRESS_TRACK_COLOR = 0x331565C0
        private val ERROR_PROGRESS_TRACK_COLOR = 0x33C62828
        private val TOUCH_CONTROLS_DIVIDER_COLOR = 0x401F2933
        private val ACTIVE_ASSISTANCE_PHASES = setOf(
            ScreenDetectorSession.AssistancePhase.RECOGNIZING_ANSWERS,
            ScreenDetectorSession.AssistancePhase.CLICKING_ANSWERS,
            ScreenDetectorSession.AssistancePhase.REVEALING_OPTIONS,
            ScreenDetectorSession.AssistancePhase.WAITING_OPTION_UPDATE,
            ScreenDetectorSession.AssistancePhase.SWIPING
        )
    }
}
