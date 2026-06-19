package com.virin.visionquiz.vision.questiondetector

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import com.virin.visionquiz.vision.graphic.GraphicOverlay
import com.virin.visionquiz.dao.answerOptionsString
import com.virin.visionquiz.util.abbreviateAnswerText
import com.virin.visionquiz.util.QuizGraphicItem
import kotlin.math.max
import kotlin.math.min

class QuizGraphic constructor(
    overlay: GraphicOverlay?,
    private val matchedQuiz: List<QuizGraphicItem>,
    private val shouldGroupTextInBlocks: Boolean,
//    private val showLanguageTag: Boolean,
    private val showConfidence: Boolean,
    private val useBriefAnswerDisplay: Boolean,
    private val textSizeSp: Float = DEFAULT_TEXT_SIZE_SP
) : GraphicOverlay.Graphic(overlay) {

    private val rectPaint: Paint = Paint()
    private val textPaint: TextPaint
    private val labelPaint: Paint
    private val labelBorderPaint: Paint
    private val connectorPaint: Paint
    private val debugPanelPaint: Paint
    private val debugPanelBorderPaint: Paint
    private val debugTextPaint: TextPaint

    init {
        rectPaint.color = MARKER_COLOR
        rectPaint.style = Paint.Style.STROKE
        rectPaint.strokeWidth = STROKE_WIDTH
        textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        textPaint.color = TEXT_COLOR
        textPaint.textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            textSizeSp,
            overlay?.context?.resources?.displayMetrics
        )
        labelPaint = Paint()
        labelPaint.color = MARKER_COLOR
        labelPaint.style = Paint.Style.FILL
        labelPaint.alpha = LABEL_ALPHA
        labelBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        labelBorderPaint.color = LABEL_BORDER_COLOR
        labelBorderPaint.style = Paint.Style.STROKE
        labelBorderPaint.strokeWidth = 1.5f
        connectorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        connectorPaint.color = CONNECTOR_COLOR
        connectorPaint.style = Paint.Style.STROKE
        connectorPaint.strokeWidth = CONNECTOR_WIDTH
        debugPanelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        debugPanelPaint.color = DEBUG_PANEL_COLOR
        debugPanelPaint.style = Paint.Style.FILL
        debugPanelBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        debugPanelBorderPaint.color = DEBUG_PANEL_BORDER_COLOR
        debugPanelBorderPaint.style = Paint.Style.STROKE
        debugPanelBorderPaint.strokeWidth = 1.5f
        debugTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        debugTextPaint.color = DEBUG_TEXT_COLOR
        debugTextPaint.textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            DEBUG_TEXT_SIZE_SP,
            overlay?.context?.resources?.displayMetrics
        )
        // Redraw the overlay, as this graphic has been added.
        postInvalidate()
    }

    // TODO: match question
    /** Draws the text block annotations for position, size, and raw value on the supplied canvas. */
    override fun draw(canvas: Canvas) {
        val occupiedLabels = mutableListOf<RectF>()
        if (showConfidence) {
            drawDebugPanel(canvas)?.let(occupiedLabels::add)
        }

        for (item in matchedQuiz.sortedBy { it.rect.top }) {
            val prompt = item.question.prompt
            val answerChars = item.question.answerOptionsString()
            val correctOpts = item.question.options.filterIndexed { index, _ ->
                item.question.answer.contains(index)
            }.joinToString(" / ") {
                abbreviateAnswerText(
                    it.replace(WHITESPACE_REGEX, " ").trim(),
                    useBriefAnswerDisplay
                )
            }
            val promptLine = prompt.replace(WHITESPACE_REGEX, " ").trim()

            val content = buildAnswerContent(promptLine, answerChars, correctOpts, item.distance)

            // FIXME: 答案中存在换行导致显示不全
//                    .map { answer ->
//                    
//                    if (answer.contains("\n")) answer.replace("\n", ", ")
//                    answer
//                }

//            Log.e("###", content)

            drawText(
                content,
                RectF(item.rect),
                canvas,
                occupiedLabels
            )
        }
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
            .setEllipsize(android.text.TextUtils.TruncateAt.END)
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
        matchedQuiz
            .sortedByDescending { it.distance }
            .take(MAX_DEBUG_MATCHES)
            .forEachIndexed { index, item ->
                val answerState = when {
                    item.isAnswerPartiallyMatched -> "黄"
                    item.answerRects.isNotEmpty() -> "绿"
                    else -> "无框"
                }
                lines += "${index + 1}. ${String.format("%.2f", item.distance)} $answerState"
                item.debugLines.take(MAX_DEBUG_LINES_PER_MATCH).forEach {
                    lines += "   $it"
                }
            }
        return lines.joinToString("\n")
    }

    private fun buildAnswerContent(
        prompt: String,
        answerChars: String,
        answerText: String,
        confidence: Double?
    ): CharSequence {
        val promptText = if (showConfidence && confidence != null) {
            String.format("(%.2f) %s", confidence, prompt)
        } else {
            prompt
        }
        val answerPrefix = "答案($answerChars): "
        val content = "$promptText\n$answerPrefix$answerText"
        val answerStart = content.length - answerText.length
        return SpannableString(content).apply {
            if (answerText.isNotBlank()) {
                setSpan(
                    StyleSpan(Typeface.BOLD),
                    answerStart,
                    content.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
    }

    private fun drawText(
        text: CharSequence,
        rect: RectF,
        canvas: Canvas,
        occupiedLabels: MutableList<RectF>
    ) {
        // If the image is flipped, the left will be translated to right, and the right to left.
        val x0 = translateX(rect.left)
        val x1 = translateX(rect.right)
        rect.left = min(x0, x1)
        rect.right = max(x0, x1)
        rect.top = translateY(rect.top)
        rect.bottom = translateY(rect.bottom)
        canvas.drawRect(rect, rectPaint)

        val maxTextWidth = max(
            MIN_LABEL_WIDTH,
            min(canvas.width - EDGE_PADDING * 2, canvas.width * MAX_LABEL_WIDTH_RATIO)
        ).toInt()
        val layout = createTextLayout(text, maxTextWidth)
        val labelWidth = layout.width + LABEL_PADDING * 2
        val labelHeight = layout.height + LABEL_PADDING * 2
        val labelLeft = rect.left.coerceIn(EDGE_PADDING, max(EDGE_PADDING, canvas.width - labelWidth - EDGE_PADDING))
        var labelTop = if (rect.top - labelHeight - LABEL_GAP >= EDGE_PADDING) {
            rect.top - labelHeight - LABEL_GAP
        } else {
            rect.bottom + LABEL_GAP
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
        drawConnectorIfNeeded(canvas, labelRect, rect)
        canvas.drawRoundRect(labelRect, LABEL_RADIUS, LABEL_RADIUS, labelPaint)
        canvas.drawRoundRect(labelRect, LABEL_RADIUS, LABEL_RADIUS, labelBorderPaint)

        canvas.save()
        canvas.translate(labelRect.left + LABEL_PADDING, labelRect.top + LABEL_PADDING)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun drawConnectorIfNeeded(canvas: Canvas, labelRect: RectF, targetRect: RectF) {
        val labelAnchorX = labelRect.left
        val labelAnchorY = labelRect.top
        val targetAnchorX = targetRect.left
        val targetAnchorY = targetRect.top
        val dx = labelAnchorX - targetAnchorX
        val dy = labelAnchorY - targetAnchorY
        if (dx * dx + dy * dy <= MIN_CONNECTOR_DISTANCE * MIN_CONNECTOR_DISTANCE) {
            return
        }
        canvas.drawLine(labelAnchorX, labelAnchorY, targetAnchorX, targetAnchorY, connectorPaint)
        canvas.drawCircle(targetAnchorX, targetAnchorY, CONNECTOR_DOT_RADIUS, connectorPaint)
    }

    private fun createTextLayout(text: CharSequence, maxTextWidth: Int): StaticLayout {
        val displayText = limitQuestionToSingleLine(text, maxTextWidth)
        return StaticLayout.Builder
            .obtain(displayText, 0, displayText.length, textPaint, maxTextWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(0f, 1f)
            .setMaxLines(MAX_LABEL_LINES)
            .setEllipsize(android.text.TextUtils.TruncateAt.END)
            .build()
    }

    private fun limitQuestionToSingleLine(text: CharSequence, maxTextWidth: Int): CharSequence {
        val newlineIndex = text.indexOf('\n')
        val question = if (newlineIndex >= 0) {
            text.subSequence(0, newlineIndex).toString()
        } else {
            text.toString()
        }
        val answer = if (newlineIndex >= 0 && newlineIndex + 1 < text.length) {
            text.subSequence(newlineIndex + 1, text.length)
        } else {
            ""
        }
        val ellipsizedQuestion = android.text.TextUtils.ellipsize(
            question,
            textPaint,
            maxTextWidth.toFloat(),
            android.text.TextUtils.TruncateAt.END
        ).toString()
        val displayText = if (answer.isBlank()) {
            ellipsizedQuestion
        } else {
            "$ellipsizedQuestion\n$answer"
        }
        return SpannableString(displayText).apply {
            setSpan(
                RelativeSizeSpan(QUESTION_TEXT_SCALE),
                0,
                ellipsizedQuestion.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            val answerStart = ellipsizedQuestion.length + 1
            if (answer.isNotBlank() && answerStart < displayText.length) {
                val answerText = answer.toString()
                val boldStartInAnswer = answerText.indexOf(": ")
                    .takeIf { it >= 0 }
                    ?.let { it + 2 }
                    ?: 0
                val boldStart = (answerStart + boldStartInAnswer).coerceAtMost(displayText.length)
                setSpan(
                    StyleSpan(Typeface.BOLD),
                    boldStart,
                    displayText.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
    }

    companion object {
        private const val TAG = "TextGraphic"
        private const val TEXT_WITH_LANGUAGE_TAG_FORMAT = "%s:%s"
        private const val TEXT_COLOR = Color.BLACK
        private const val MARKER_COLOR = Color.WHITE
        private const val LABEL_BORDER_COLOR = 0xDD222222.toInt()
        private const val CONNECTOR_COLOR = 0xEEFFFFFF.toInt()
        private const val DEBUG_PANEL_COLOR = 0xDD111111.toInt()
        private const val DEBUG_PANEL_BORDER_COLOR = 0xEEFFFFFF.toInt()
        private const val DEBUG_TEXT_COLOR = Color.WHITE
        private const val LABEL_ALPHA = 228
        private const val DEFAULT_TEXT_SIZE_SP = 12.0f
        private const val DEBUG_TEXT_SIZE_SP = 11.0f
        private const val STROKE_WIDTH = 4.0f
        private const val CONNECTOR_WIDTH = 2.0f
        private const val CONNECTOR_DOT_RADIUS = 3.0f
        private const val LABEL_PADDING = 8.0f
        private const val LABEL_GAP = 6.0f
        private const val LABEL_RADIUS = 6.0f
        private const val EDGE_PADDING = 8.0f
        private const val MIN_LABEL_WIDTH = 220.0f
        private const val MAX_LABEL_WIDTH_RATIO = 0.72f
        private const val MIN_DEBUG_PANEL_WIDTH = 220.0f
        private const val DEBUG_PANEL_WIDTH_RATIO = 0.82f
        private const val DEBUG_PANEL_PADDING = 8.0f
        private const val DEBUG_PANEL_RADIUS = 8.0f
        private const val MIN_CONNECTOR_DISTANCE = 18.0f
        private const val MAX_LABEL_LINES = 6
        private const val MAX_DEBUG_PANEL_LINES = 14
        private const val MAX_DEBUG_MATCHES = 3
        private const val MAX_DEBUG_LINES_PER_MATCH = 4
        private const val MAX_PLACEMENT_ATTEMPTS = 8
        private const val QUESTION_TEXT_SCALE = 0.92f
        private val WHITESPACE_REGEX = Regex("\\s+")
    }
}
