package com.virin.visionquiz.screendetector

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
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
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.virin.visionquiz.R
import com.virin.visionquiz.util.QuizGraphicItem
import com.virin.visionquiz.vision.graphic.GraphicOverlay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

class ScreenDetectorService : LifecycleService() {
    private lateinit var windowManager: WindowManager
    private var markerOverlayView: GraphicOverlay? = null
    private var controlRootView: LinearLayout? = null
    private var statusView: TextView? = null
    private var actionButton: ImageButton? = null
    private var retryButton: ImageButton? = null
    private var stopButton: ImageButton? = null
    private var controlLayoutParams: WindowManager.LayoutParams? = null

    private var libId = 0
    private val overlayMarginPx by lazy { dpToPx(16) }
    private val touchSlopPx by lazy { ViewConfiguration.get(this).scaledTouchSlop }
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartWindowX = 0
    private var dragStartWindowY = 0
    private var isDraggingControlBar = false
    private var snapAnimator: ValueAnimator? = null

    override fun onCreate() {
        super.onCreate()
        lifecycleScope.launch {
            ScreenDetectorSession.state
                .combine(ScreenDetectorSession.matches) { state, matches -> state to matches }
                .combine(ScreenDetectorSession.screenFrameInfo) { (state, matches), frameInfo ->
                    RenderState(state, matches, frameInfo)
                }
                .collect { renderState ->
                    renderControlBar(renderState.state, renderState.matches)
                    renderMarkerOverlay(renderState.state, renderState.matches, renderState.frameInfo)
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        libId = intent?.getIntExtra(LIBRARY_ID, 0) ?: 0
        showWindows()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cancelSnapAnimator()
        removeViewIfAttached(markerOverlayView)
        markerOverlayView = null
        removeViewIfAttached(controlRootView)
        controlRootView = null
        ScreenDetectorSession.clearOverlayBounds()
        ScreenDetectorSession.clearAnnotationBounds()
        super.onDestroy()
    }

    private fun showWindows() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showMarkerOverlay()
        showControlBar()
        renderControlBar(ScreenDetectorSession.state.value, ScreenDetectorSession.matches.value)
        renderMarkerOverlay(
            ScreenDetectorSession.state.value,
            ScreenDetectorSession.matches.value,
            ScreenDetectorSession.screenFrameInfo.value
        )
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
        val controlBar = createControlBar()
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
        windowManager.addView(controlBar, layoutParams)
        publishControlBounds()
        controlBar.post {
            alignControlBarToBottom()
            publishControlBounds()
        }
    }

    private fun createControlBar(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_overlay_window)
            elevation = dpToPx(8).toFloat()
            setPadding(dpToPx(10), dpToPx(6), dpToPx(10), dpToPx(6))

            statusView = TextView(context).apply {
                setTextColor(0xFF111111.toInt())
                textSize = 13f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setOnTouchListener(::handleControlBarTouch)
            }
            addView(
                statusView,
                LinearLayout.LayoutParams(dpToPx(128), LinearLayout.LayoutParams.WRAP_CONTENT)
            )

            actionButton = createControlIconButton(
                iconRes = R.drawable.round_pause_24,
                description = "暂停"
            ).apply {
                setOnClickListener { ScreenDetectorSession.requestPauseResume() }
            }
            addView(actionButton)

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
            setOnTouchListener(::handleControlBarTouch)
        }
    }

    private fun handleControlBarTouch(view: View, event: MotionEvent): Boolean {
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
                val targetX = dragStartWindowX + deltaX.roundToInt()
                val targetY = dragStartWindowY + deltaY.roundToInt()
                updateControlBarPosition(root, params, targetX, targetY)
                return true
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                if (isDraggingControlBar) {
                    snapControlBarToHorizontalEdge(root, params)
                } else {
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
            0
        } else {
            maxX
        }
        animateControlBarPosition(root, params, targetX, params.y)
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

    private fun renderControlBar(
        state: ScreenDetectorSession.DetectionState,
        matches: List<QuizGraphicItem>
    ) {
        statusView?.text = buildStatusText(state, matches)
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
        controlRootView?.post { publishControlBounds() }
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

    private fun buildStatusText(
        state: ScreenDetectorSession.DetectionState,
        matches: List<QuizGraphicItem>
    ): String {
        val prefix = when (state) {
            ScreenDetectorSession.DetectionState.RUNNING -> "识别中"
            ScreenDetectorSession.DetectionState.PAUSED -> "已暂停"
            ScreenDetectorSession.DetectionState.STOPPED -> "已停止"
        }
        return "$prefix · ${matches.size} 题"
    }

    private fun renderMarkerOverlay(
        state: ScreenDetectorSession.DetectionState,
        matches: List<QuizGraphicItem>,
        frameInfo: ScreenDetectorSession.ScreenFrameInfo?
    ) {
        val overlay = markerOverlayView ?: return
        if (state == ScreenDetectorSession.DetectionState.STOPPED || frameInfo == null) {
            overlay.clear()
            ScreenDetectorSession.clearAnnotationBounds()
            return
        }

        overlay.setImageSourceInfo(frameInfo.width, frameInfo.height, false)
        overlay.clear()
        if (matches.isNotEmpty()) {
            overlay.add(ScreenMatchGraphic(overlay, matches, frameInfo, getOverlayWindowBounds()))
        } else {
            ScreenDetectorSession.clearAnnotationBounds()
        }
        overlay.postInvalidate()
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
        val frameInfo: ScreenDetectorSession.ScreenFrameInfo?
    )

    companion object {
        const val LIBRARY_ID = "LibraryId"
        private const val MARKER_WINDOW_ALPHA = 0.74f
        private const val SNAP_ANIMATION_DURATION_MS = 180L
    }
}
