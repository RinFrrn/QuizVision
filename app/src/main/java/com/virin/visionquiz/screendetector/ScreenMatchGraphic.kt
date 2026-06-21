package com.virin.visionquiz.screendetector

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import com.virin.visionquiz.preference.PreferenceUtils
import com.virin.visionquiz.util.QuizGraphicItem
import com.virin.visionquiz.util.abbreviateAnswerText
import com.virin.visionquiz.vision.graphic.GraphicOverlay
import kotlin.math.max
import kotlin.math.min

class ScreenMatchGraphic(
    private val overlayView: GraphicOverlay,
    matches: List<QuizGraphicItem>,
    private val frameInfo: ScreenDetectorSession.ScreenFrameInfo,
    private val screenBounds: ScreenDetectorService.OverlayWindowBounds,
    private val overlayFingerprint: String,
    private val showQuestionAnnotations: Boolean,
    private val showAnswerFrames: Boolean,
    private val useTouchAnswerDots: Boolean
) : GraphicOverlay.Graphic(overlayView) {

    private val displayMatches = matches
        .distinctBy { it.question.id.takeIf { id -> id != 0 } ?: it.question.prompt }
        .sortedByDescending { it.distance }
        .take(MAX_MATCHES)
    private val showConfidence = PreferenceUtils.shouldShowTextConfidence(overlayView.context)
    private val useBriefAnswerDisplay = PreferenceUtils.shouldUseBriefAnswerDisplay(overlayView.context)
    private val textSizeSp = PreferenceUtils.getQuizOverlayTextSizeSp(overlayView.context)

    private val frameShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = FRAME_SHADOW_COLOR
        style = Paint.Style.STROKE
        strokeWidth = FRAME_SHADOW_WIDTH
    }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = FRAME_COLOR
        style = Paint.Style.STROKE
        strokeWidth = FRAME_WIDTH
    }
    private val answerFrameShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ANSWER_FRAME_SHADOW_COLOR
        style = Paint.Style.STROKE
        strokeWidth = ANSWER_FRAME_SHADOW_WIDTH
    }
    private val answerFramePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ANSWER_FRAME_COLOR
        style = Paint.Style.STROKE
        strokeWidth = ANSWER_FRAME_WIDTH
    }
    private val partialAnswerFrameShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PARTIAL_ANSWER_FRAME_SHADOW_COLOR
        style = Paint.Style.STROKE
        strokeWidth = ANSWER_FRAME_SHADOW_WIDTH
    }
    private val partialAnswerFramePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PARTIAL_ANSWER_FRAME_COLOR
        style = Paint.Style.STROKE
        strokeWidth = ANSWER_FRAME_WIDTH
    }
    private val briefAnswerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PreferenceUtils.getAccessibilityAnswerDotColor(overlayView.context)
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = LABEL_BACKGROUND_COLOR
        style = Paint.Style.FILL
    }
    private val labelBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = LABEL_BORDER_COLOR
        style = Paint.Style.STROKE
        strokeWidth = LABEL_BORDER_WIDTH
    }
    private val connectorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = CONNECTOR_COLOR
        style = Paint.Style.STROKE
        strokeWidth = CONNECTOR_WIDTH
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            textSizeSp,
            overlayView.context.resources.displayMetrics
        )
    }
    private val debugPanelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = DEBUG_PANEL_COLOR
        style = Paint.Style.FILL
    }
    private val debugPanelBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = DEBUG_PANEL_BORDER_COLOR
        style = Paint.Style.STROKE
        strokeWidth = DEBUG_PANEL_BORDER_WIDTH
    }
    private val debugTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            DEBUG_TEXT_SIZE_SP,
            overlayView.context.resources.displayMetrics
        )
    }

    override fun draw(canvas: Canvas) {
        val occupiedLabels = mutableListOf<RectF>()
        val screenMaskBounds = mutableListOf<android.graphics.Rect>()
        val overlayLocation = IntArray(2)
        overlayView.getLocationOnScreen(overlayLocation)
        if (showConfidence) {
            drawDebugPanel(canvas)?.let { debugRect ->
                occupiedLabels += debugRect
                addMaskBound(debugRect, overlayLocation, screenMaskBounds)
            }
        }
        displayMatches.forEach { match ->
            val rect = mapFrameRectToOverlay(match.rect, overlayLocation)
            if (showQuestionAnnotations) {
                canvas.drawRoundRect(rect, FRAME_RADIUS, FRAME_RADIUS, frameShadowPaint)
                canvas.drawRoundRect(rect, FRAME_RADIUS, FRAME_RADIUS, framePaint)
            }
            if (showAnswerFrames) {
                val answerShadowPaint = if (match.isAnswerPartiallyMatched) {
                    partialAnswerFrameShadowPaint
                } else {
                    answerFrameShadowPaint
                }
                val answerPaint = if (match.isAnswerPartiallyMatched) {
                    partialAnswerFramePaint
                } else {
                    answerFramePaint
                }
                match.answerRects.forEach { answerSourceRect ->
                    val answerRect = mapFrameRectToOverlay(answerSourceRect, overlayLocation)
                    if (useTouchAnswerDots) {
                        drawBriefAnswerDot(canvas, answerRect, overlayLocation, screenMaskBounds)
                    } else {
                        canvas.drawRoundRect(
                            answerRect,
                            ANSWER_FRAME_RADIUS,
                            ANSWER_FRAME_RADIUS,
                            answerShadowPaint
                        )
                        canvas.drawRoundRect(
                            answerRect,
                            ANSWER_FRAME_RADIUS,
                            ANSWER_FRAME_RADIUS,
                            answerPaint
                        )
                        addFrameMaskBounds(answerRect, overlayLocation, screenMaskBounds)
                    }
                }
            }
            if (showQuestionAnnotations) {
                val labelRect = drawLabel(canvas, rect, buildAnswerLabel(match), occupiedLabels)
                addMaskBound(labelRect, overlayLocation, screenMaskBounds)
            }
        }
        ScreenDetectorSession.publishAnnotationBounds(screenMaskBounds)
        ScreenDetectorSession.markOverlayRendered(overlayFingerprint)
    }

    private fun drawDebugPanel(canvas: Canvas): RectF? {
        val debugText = buildDebugPanelText()
        if (debugText.isBlank()) {
            return null
        }
        val panelWidth = max(
            MIN_DEBUG_PANEL_WIDTH,
            min(canvas.width - EDGE_PADDING * 2, canvas.width * DEBUG_PANEL_WIDTH_RATIO)
        ).toInt()
        val layout = StaticLayout.Builder
            .obtain(debugText, 0, debugText.length, debugTextPaint, panelWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(0f, 1f)
            .setMaxLines(MAX_DEBUG_PANEL_LINES)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
        val rect = RectF(
            EDGE_PADDING,
            EDGE_PADDING,
            EDGE_PADDING + layout.width + DEBUG_PANEL_PADDING * 2,
            EDGE_PADDING + layout.height + DEBUG_PANEL_PADDING * 2
        )
        canvas.drawRoundRect(rect, DEBUG_PANEL_RADIUS, DEBUG_PANEL_RADIUS, debugPanelPaint)
        canvas.drawRoundRect(rect, DEBUG_PANEL_RADIUS, DEBUG_PANEL_RADIUS, debugPanelBorderPaint)
        canvas.save()
        canvas.translate(rect.left + DEBUG_PANEL_PADDING, rect.top + DEBUG_PANEL_PADDING)
        layout.draw(canvas)
        canvas.restore()
        return rect
    }

    private fun buildDebugPanelText(): String {
        val lines = mutableListOf("OCR 调试")
        displayMatches.take(MAX_DEBUG_MATCHES).forEachIndexed { index, match ->
            val answerState = when {
                match.isAnswerPartiallyMatched -> "黄"
                match.answerRects.isNotEmpty() -> "绿"
                else -> "无框"
            }
            lines += "${index + 1}. ${String.format("%.2f", match.distance)} $answerState"
            match.debugLines.take(MAX_DEBUG_LINES_PER_MATCH).forEach {
                lines += "   $it"
            }
        }
        return lines.joinToString("\n")
    }

    private fun mapFrameRectToOverlay(
        sourceRect: android.graphics.Rect,
        overlayLocation: IntArray
    ): RectF {
        val scaleX = screenBounds.width.toFloat() / frameInfo.width
        val scaleY = screenBounds.height.toFloat() / frameInfo.height
        return RectF(
            sourceRect.left * scaleX - overlayLocation[0],
            sourceRect.top * scaleY - overlayLocation[1],
            sourceRect.right * scaleX - overlayLocation[0],
            sourceRect.bottom * scaleY - overlayLocation[1]
        )
    }

    private fun drawBriefAnswerDot(
        canvas: Canvas,
        answerRect: RectF,
        overlayLocation: IntArray,
        outBounds: MutableList<android.graphics.Rect>
    ) {
        val radius = BRIEF_ANSWER_DOT_DIAMETER_DP * overlayView.context.resources.displayMetrics.density / 2f
        val gap = BRIEF_ANSWER_DOT_GAP_DP * overlayView.context.resources.displayMetrics.density
        val maxCenterX = max(radius, canvas.width - radius)
        val maxCenterY = max(radius, canvas.height - radius)
        val centerX = (answerRect.left - gap).coerceIn(radius, maxCenterX)
        val centerY = answerRect.centerY().coerceIn(radius, maxCenterY)
        canvas.drawCircle(centerX, centerY, radius, briefAnswerDotPaint)
        addMaskBound(
            RectF(centerX - radius, centerY - radius, centerX + radius, centerY + radius),
            overlayLocation,
            outBounds
        )
    }

    private fun drawLabel(
        canvas: Canvas,
        targetRect: RectF,
        text: String,
        occupiedLabels: MutableList<RectF>
    ): RectF {
        val maxTextWidth = max(
            MIN_LABEL_TEXT_WIDTH,
            min(canvas.width - EDGE_PADDING * 2, canvas.width * MAX_LABEL_WIDTH_RATIO)
        ).toInt()
        val preferredWidth = min(maxTextWidth, max(MIN_LABEL_TEXT_WIDTH.toInt(), textPaint.measureText(text).toInt()))
        val layout = createLabelLayout(text, preferredWidth)
        val labelWidth = layout.width + LABEL_PADDING * 2
        val labelHeight = layout.height + LABEL_PADDING * 2
        val labelLeft = targetRect.left.coerceIn(
            EDGE_PADDING,
            max(EDGE_PADDING, canvas.width - labelWidth - EDGE_PADDING)
        )
        var labelTop = if (targetRect.top - labelHeight - LABEL_GAP >= EDGE_PADDING) {
            targetRect.top - labelHeight - LABEL_GAP
        } else {
            targetRect.bottom + LABEL_GAP
        }
        var labelRect = RectF(labelLeft, labelTop, labelLeft + labelWidth, labelTop + labelHeight)

        var attempts = 0
        while (attempts < MAX_PLACEMENT_ATTEMPTS && occupiedLabels.any { RectF.intersects(it, labelRect) }) {
            labelTop += labelHeight + LABEL_GAP
            if (labelTop + labelHeight > canvas.height - EDGE_PADDING) {
                labelTop = EDGE_PADDING
            }
            labelRect = RectF(labelLeft, labelTop, labelLeft + labelWidth, labelTop + labelHeight)
            attempts++
        }

        occupiedLabels.add(labelRect)
        drawConnectorIfNeeded(canvas, labelRect, targetRect)
        canvas.drawRoundRect(labelRect, LABEL_RADIUS, LABEL_RADIUS, labelPaint)
        canvas.drawRoundRect(labelRect, LABEL_RADIUS, LABEL_RADIUS, labelBorderPaint)
        canvas.save()
        canvas.translate(labelRect.left + LABEL_PADDING, labelRect.top + LABEL_PADDING)
        layout.draw(canvas)
        canvas.restore()
        return labelRect
    }

    private fun createLabelLayout(text: String, width: Int): StaticLayout {
        return StaticLayout.Builder
            .obtain(text, 0, text.length, textPaint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(0f, 1f)
            .setMaxLines(MAX_LABEL_LINES)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
    }

    private fun drawConnectorIfNeeded(canvas: Canvas, labelRect: RectF, targetRect: RectF) {
        val labelAnchorX = labelRect.left
        val labelAnchorY = labelRect.top + labelRect.height() / 2
        val targetAnchorX = targetRect.left
        val targetAnchorY = targetRect.top
        val dx = labelAnchorX - targetAnchorX
        val dy = labelAnchorY - targetAnchorY
        if (dx * dx + dy * dy <= MIN_CONNECTOR_DISTANCE * MIN_CONNECTOR_DISTANCE) {
            return
        }
        canvas.drawLine(labelAnchorX, labelAnchorY, targetAnchorX, targetAnchorY, connectorPaint)
    }

    private fun buildAnswerLabel(match: QuizGraphicItem): String {
        val answers = match.question.answer.sorted().mapNotNull { optionIndex ->
            val option = match.question.options.getOrNull(optionIndex)
                ?.replace(WHITESPACE_REGEX, " ")
                ?.replace(OPTION_PREFIX_REGEX, "")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            "${'A' + optionIndex}. ${abbreviateAnswerText(option, useBriefAnswerDisplay)}"
        }
        val answerText = if (answers.isEmpty()) "--" else answers.joinToString(" / ")
        return if (showConfidence) {
            String.format("%.2f  %s", match.distance, answerText)
        } else {
            answerText
        }
    }

    private fun addFrameMaskBounds(
        rect: RectF,
        overlayLocation: IntArray,
        outBounds: MutableList<android.graphics.Rect>
    ) {
        val pad = FRAME_MASK_PADDING
        addMaskBound(RectF(rect.left - pad, rect.top - pad, rect.right + pad, rect.top + pad), overlayLocation, outBounds)
        addMaskBound(RectF(rect.left - pad, rect.bottom - pad, rect.right + pad, rect.bottom + pad), overlayLocation, outBounds)
        addMaskBound(RectF(rect.left - pad, rect.top - pad, rect.left + pad, rect.bottom + pad), overlayLocation, outBounds)
        addMaskBound(RectF(rect.right - pad, rect.top - pad, rect.right + pad, rect.bottom + pad), overlayLocation, outBounds)
    }

    private fun addMaskBound(
        rect: RectF,
        overlayLocation: IntArray,
        outBounds: MutableList<android.graphics.Rect>
    ) {
        val clipped = RectF(
            rect.left.coerceIn(0f, overlayView.width.toFloat()),
            rect.top.coerceIn(0f, overlayView.height.toFloat()),
            rect.right.coerceIn(0f, overlayView.width.toFloat()),
            rect.bottom.coerceIn(0f, overlayView.height.toFloat())
        )
        if (clipped.isEmpty) {
            return
        }
        outBounds.add(
            android.graphics.Rect(
                (overlayLocation[0] + clipped.left).toInt(),
                (overlayLocation[1] + clipped.top).toInt(),
                (overlayLocation[0] + clipped.right).toInt(),
                (overlayLocation[1] + clipped.bottom).toInt()
            )
        )
    }

    companion object {
        private const val MAX_MATCHES = 6
        private const val FRAME_COLOR = Color.WHITE
        private const val FRAME_SHADOW_COLOR = 0xAA000000.toInt()
        private const val ANSWER_FRAME_COLOR = 0xFF4ADE80.toInt()
        private const val ANSWER_FRAME_SHADOW_COLOR = 0xAA052E16.toInt()
        private const val PARTIAL_ANSWER_FRAME_COLOR = 0xFFFACC15.toInt()
        private const val PARTIAL_ANSWER_FRAME_SHADOW_COLOR = 0xAA713F12.toInt()
        private const val LABEL_BACKGROUND_COLOR = 0xDD111111.toInt()
        private const val LABEL_BORDER_COLOR = 0xEEFFFFFF.toInt()
        private const val CONNECTOR_COLOR = 0xCCFFFFFF.toInt()
        private const val DEBUG_PANEL_COLOR = 0xDD111111.toInt()
        private const val DEBUG_PANEL_BORDER_COLOR = 0xEEFFFFFF.toInt()
        private const val FRAME_WIDTH = 4f
        private const val FRAME_SHADOW_WIDTH = 7f
        private const val ANSWER_FRAME_WIDTH = 5f
        private const val ANSWER_FRAME_SHADOW_WIDTH = 8f
        private const val FRAME_MASK_PADDING = 8f
        private const val FRAME_RADIUS = 6f
        private const val ANSWER_FRAME_RADIUS = 8f
        private const val BRIEF_ANSWER_DOT_DIAMETER_DP = 2f
        private const val BRIEF_ANSWER_DOT_GAP_DP = 4f
        private const val LABEL_BORDER_WIDTH = 1.5f
        private const val DEBUG_PANEL_BORDER_WIDTH = 1.5f
        private const val CONNECTOR_WIDTH = 2f
        private const val LABEL_PADDING = 8f
        private const val LABEL_GAP = 8f
        private const val LABEL_RADIUS = 8f
        private const val DEBUG_PANEL_PADDING = 8f
        private const val DEBUG_PANEL_RADIUS = 8f
        private const val EDGE_PADDING = 12f
        private const val MIN_LABEL_TEXT_WIDTH = 180f
        private const val MAX_LABEL_WIDTH_RATIO = 0.72f
        private const val MIN_DEBUG_PANEL_WIDTH = 220f
        private const val DEBUG_PANEL_WIDTH_RATIO = 0.82f
        private const val MAX_LABEL_LINES = 2
        private const val MAX_DEBUG_PANEL_LINES = 14
        private const val MAX_DEBUG_MATCHES = 3
        private const val MAX_DEBUG_LINES_PER_MATCH = 4
        private const val DEBUG_TEXT_SIZE_SP = 11f
        private const val MAX_PLACEMENT_ATTEMPTS = 8
        private const val MIN_CONNECTOR_DISTANCE = 20f
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val OPTION_PREFIX_REGEX = Regex("^[A-Ha-h][、.．)）]\\s*")
    }
}
