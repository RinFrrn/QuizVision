package com.virin.visionquiz.cram

import android.content.Context
import android.text.Spanned
import android.text.style.ClickableSpan
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.virin.visionquiz.quizlist.quizcontent.QuizContentMemoryPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CramQuizReferenceLinkifierTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun linksOnlyResolvableQuestionNumbersAndDispatchesTheirTarget() {
        val textView = TextView(context).apply {
            text = "题号 12、34；2025年。"
        }
        val clicked = mutableListOf<CramQuizReferenceContext>()
        val target = CramQuizReferenceTarget(CramQuizReferenceKind.DATABASE_ID, 12)
        val referenceContext = CramQuizReferenceContext(
            target = target,
            occurrenceOrdinal = 0,
            memoryPoint = QuizContentMemoryPoint(
                id = "rule-12",
                sourceLabel = "本地分析",
                cue = "先核对主体"
            )
        )

        val count = CramQuizReferenceLinkifier.linkify(
            textView = textView,
            linkColor = 0xff006b2f.toInt(),
            isResolvable = { it.value == 12 },
            referenceContexts = listOf(referenceContext),
            onQuizClick = clicked::add
        )

        assertEquals(1, count)
        val spans = (textView.text as Spanned).getSpans(
            0,
            textView.length(),
            ClickableSpan::class.java
        )
        assertEquals(1, spans.size)

        spans.single().onClick(textView)

        assertEquals(
            listOf(referenceContext),
            clicked
        )
        assertTrue(textView.linksClickable)
    }
}
