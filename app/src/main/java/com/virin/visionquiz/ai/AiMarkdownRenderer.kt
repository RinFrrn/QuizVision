package com.virin.visionquiz.ai

import android.content.Context
import android.widget.TextView
import io.noties.markwon.Markwon

internal class AiMarkdownRenderer(
    context: Context,
    private val renderAction: ((TextView, String) -> Unit)? = null
) {
    private val markwon = if (renderAction == null) Markwon.create(context) else null

    fun render(textView: TextView, markdown: String) {
        runCatching {
            renderAction?.invoke(textView, markdown)
                ?: markwon?.setMarkdown(textView, markdown)
        }.onFailure {
            textView.text = markdown
        }
    }
}
