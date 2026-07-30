package com.virin.visionquiz.cram

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import org.junit.Assert.assertEquals
import org.junit.Test

class CramMnemonicQuizLinksTest {

    @Test
    fun createsOneClickableLinkForEveryRelatedQuiz() {
        val clicked = mutableListOf<Pair<CramQuizReferenceTarget, String>>()
        val text = buildCramMnemonicQuizLinks(
            quizIds = listOf(12, 34),
            memoryPointId = "mnemonic-30-days",
            linkStyles = TextLinkStyles(),
            onOpenQuizReference = { target, memoryPointId ->
                clicked += target to memoryPointId
            }
        )

        assertEquals("相关题目：12、34", text.text)
        val links = text.getLinkAnnotations(0, text.length)
        assertEquals(
            listOf("cram-quiz-12", "cram-quiz-34"),
            links.map { (it.item as LinkAnnotation.Clickable).tag }
        )

        val second = links[1].item
        second.linkInteractionListener?.onClick(second)

        assertEquals(
            listOf(
                CramQuizReferenceTarget(CramQuizReferenceKind.DATABASE_ID, 34) to
                    "mnemonic-30-days"
            ),
            clicked
        )
    }
}
