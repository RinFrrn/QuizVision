package com.virin.visionquiz.ai

import android.content.Context
import android.text.Spanned
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiMarkdownRendererTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun rendersSupportedMarkdownAsNativeSpans() {
        val textView = TextView(context)
        val markdown = """
            ### 结论
            **核心知识**

            - 选项 A
            - 选项 B

            1. 第一步
            2. 第二步

            > 注意边界

            使用 `关键字` 判断。
        """.trimIndent()

        AiMarkdownRenderer(context).render(textView, markdown)

        assertTrue(textView.text is Spanned)
        assertTrue(textView.text.toString().contains("结论"))
        assertTrue(textView.text.toString().contains("核心知识"))
        assertFalse(textView.text.toString().contains("**"))
        assertTrue((textView.text as Spanned).getSpans(0, textView.length(), Any::class.java).isNotEmpty())
    }

    @Test
    fun acceptsPlainOrIncompleteMarkdown() {
        val textView = TextView(context)

        AiMarkdownRenderer(context).render(textView, "普通文本\n### 未完成但可显示")

        assertTrue(textView.text.toString().contains("普通文本"))
        assertTrue(textView.text.toString().contains("未完成但可显示"))
    }

    @Test
    fun fallsBackToRawContentWhenRenderingFails() {
        val textView = TextView(context)
        val raw = "### 原始内容"
        val renderer = AiMarkdownRenderer(context) { _, _ ->
            error("render failed")
        }

        renderer.render(textView, raw)

        assertEquals(raw, textView.text.toString())
    }
}
