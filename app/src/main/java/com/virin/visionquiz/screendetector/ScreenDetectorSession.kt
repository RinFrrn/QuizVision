package com.virin.visionquiz.screendetector

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

    enum class DetectionState {
        STOPPED,
        RUNNING,
        PAUSED,
    }

    interface Controller {
        fun pauseScreenDetection()
        fun resumeScreenDetection()
        fun retryScreenDetectionOnce()
        fun stopScreenDetection()
    }

    @Volatile
    private var controller: Controller? = null

    private val _state = MutableStateFlow(DetectionState.STOPPED)
    val state: StateFlow<DetectionState> = _state.asStateFlow()

    private val _matches = MutableStateFlow<List<QuizGraphicItem>>(emptyList())
    val matches: StateFlow<List<QuizGraphicItem>> = _matches.asStateFlow()

    private val _overlayBounds = MutableStateFlow<Rect?>(null)
    val overlayBounds: StateFlow<Rect?> = _overlayBounds.asStateFlow()

    private val _annotationBounds = MutableStateFlow<List<Rect>>(emptyList())

    private val _screenFrameInfo = MutableStateFlow<ScreenFrameInfo?>(null)
    val screenFrameInfo: StateFlow<ScreenFrameInfo?> = _screenFrameInfo.asStateFlow()

    fun attachController(controller: Controller) {
        this.controller = controller
    }

    fun detachController(controller: Controller) {
        if (this.controller === controller) {
            this.controller = null
        }
    }

    fun setState(state: DetectionState) {
        _state.value = state
    }

    fun publishMatches(matches: List<QuizGraphicItem>) {
        _matches.value = matches
    }

    fun clearMatches() {
        _matches.value = emptyList()
    }

    @JvmStatic
    fun publishScreenFrameInfo(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            _screenFrameInfo.value = ScreenFrameInfo(width, height)
        }
    }

    @JvmStatic
    fun clearScreenFrameInfo() {
        _screenFrameInfo.value = null
    }

    fun publishOverlayBounds(bounds: Rect) {
        _overlayBounds.value = Rect(bounds)
    }

    fun clearOverlayBounds() {
        _overlayBounds.value = null
    }

    @JvmStatic
    fun getOverlayBoundsSnapshot(): Rect? {
        return _overlayBounds.value?.let { Rect(it) }
    }

    fun publishAnnotationBounds(bounds: List<Rect>) {
        _annotationBounds.value = bounds.map { Rect(it) }
    }

    fun clearAnnotationBounds() {
        _annotationBounds.value = emptyList()
    }

    @JvmStatic
    fun getAnnotationBoundsSnapshot(): List<Rect> {
        return _annotationBounds.value.map { Rect(it) }
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
}
