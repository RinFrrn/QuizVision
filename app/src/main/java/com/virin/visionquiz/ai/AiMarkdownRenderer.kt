package com.virin.visionquiz.ai

import android.content.Context
import android.graphics.Color
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.TextView
import com.google.android.material.color.MaterialColors
import com.virin.visionquiz.R
import io.noties.markwon.Markwon
import io.noties.markwon.core.spans.HeadingSpan

internal class AiMarkdownRenderer(
    context: Context,
    private val renderAction: ((TextView, String) -> Unit)? = null
) {
    private val markwon = if (renderAction == null) Markwon.create(context) else null
    private val headingColor = MaterialColors.getColor(
        context,
        R.attr.colorPrimary,
        Color.BLACK
    )

    fun render(textView: TextView, markdown: String) {
        runCatching {
            if (renderAction != null) {
                renderAction.invoke(textView, markdown)
            } else {
                markwon?.let { renderer ->
                    val spanned = SpannableStringBuilder(renderer.toMarkdown(markdown))
                    compactHeadingBodySpacing(spanned)
                    renderer.setParsedMarkdown(textView, spanned)
                    applyHeadingThemeColor(textView)
                }
            }
        }.onFailure {
            textView.text = markdown
        }
    }

    private fun compactHeadingBodySpacing(text: SpannableStringBuilder) {
        val headingSpans = text
            .getSpans(0, text.length, HeadingSpan::class.java)
            .sortedByDescending { text.getSpanStart(it) }
        headingSpans.forEach { headingSpan ->
            val end = text.getSpanEnd(headingSpan)
            if (end < 0 || end >= text.length || text[end] != '\n') return@forEach

            var deleteEnd = end + 1
            while (deleteEnd < text.length && text[deleteEnd] == '\n') {
                deleteEnd++
            }
            if (deleteEnd > end + 1) {
                text.delete(end + 1, deleteEnd)
            }
        }
    }

    private fun applyHeadingThemeColor(textView: TextView) {
        val text = textView.text as? Spannable ?: return
        text.getSpans(0, text.length, HeadingSpan::class.java).forEach { headingSpan ->
            val start = text.getSpanStart(headingSpan)
            val end = text.getSpanEnd(headingSpan)
            if (start >= 0 && end > start) {
                text.setSpan(
                    ForegroundColorSpan(headingColor),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
    }
}
