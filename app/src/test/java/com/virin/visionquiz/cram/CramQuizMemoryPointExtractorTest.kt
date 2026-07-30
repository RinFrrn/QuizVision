package com.virin.visionquiz.cram

import com.virin.visionquiz.dao.Quiz
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CramQuizMemoryPointExtractorTest {

    @Test
    fun sharesTheNearestRuleAcrossAQuestionNumberCluster() {
        val markdown = """
            ## 高频母规则
            - 先核对主体，再看是否提前通知。题号 28853、28884
        """.trimIndent()

        val contexts = CramQuizMemoryPointExtractor.extract(
            markdown = markdown,
            sourceLabel = "本地分析"
        )

        assertEquals(listOf(28853, 28884), contexts.map { it.target.value })
        assertEquals(
            listOf("先核对主体，再看是否提前通知", "先核对主体，再看是否提前通知"),
            contexts.map { it.memoryPoint?.cue }
        )
        assertEquals(
            listOf("高频母规则", "高频母规则"),
            contexts.map { it.memoryPoint?.supportingText }
        )
    }

    @Test
    fun removesEvidenceCountButKeepsTheNumericRuleAndItsConditions() {
        val markdown = """
            ## 数字与时限
            - **30日**（时限）：申请应在收到通知后30日内提出；支持 2 次；题号 12、13
        """.trimIndent()

        val contexts = CramQuizMemoryPointExtractor.extract(
            markdown = markdown,
            sourceLabel = "本地分析"
        )

        assertEquals(
            listOf(
                "30日（时限）：申请应在收到通知后30日内提出",
                "30日（时限）：申请应在收到通知后30日内提出"
            ),
            contexts.map { it.memoryPoint?.cue }
        )
    }

    @Test
    fun usesTheRightHandRuleWhenTheReferenceComesFirst() {
        val contexts = CramQuizMemoryPointExtractor.extract(
            markdown = "- 题号 12：应先通知并保留记录。",
            sourceLabel = "本地分析"
        )

        assertEquals("应先通知并保留记录", contexts.single().memoryPoint?.cue)
    }

    @Test
    fun keepsRepeatedQuestionOccurrencesAsDifferentMemoryPoints() {
        val markdown = """
            ## 规则
            - 一般规则 [题#12]；例外须书面确认 [题#13]。
            - 第二条规则 [题#12]
        """.trimIndent()

        val contexts = CramQuizMemoryPointExtractor.extract(
            markdown = markdown,
            sourceLabel = "AI 冲刺总稿"
        )

        val questionTwelve = contexts.filter { it.target.value == 12 }
        assertEquals(listOf(0, 1), questionTwelve.map { it.occurrenceOrdinal })
        assertEquals(
            listOf("一般规则", "第二条规则"),
            questionTwelve.map { it.memoryPoint?.cue }
        )
        assertEquals(
            "例外须书面确认",
            contexts.single { it.target.value == 13 }.memoryPoint?.cue
        )
    }

    @Test
    fun hiddenMarkdownDestinationsDoNotShiftVisibleReferenceOccurrences() {
        val markdown = """
            [法规](https://example.test/题号12)
            <!-- 隐藏说明：题号 12 -->
            [ref]: https://example.test/题号12

            - 应先通知并保留记录。题号 12
        """.trimIndent()

        val contexts = CramQuizMemoryPointExtractor.extract(
            markdown = markdown,
            sourceLabel = "AI 冲刺总稿"
        )

        assertEquals(1, contexts.size)
        assertEquals(0, contexts.single().occurrenceOrdinal)
        assertEquals("应先通知并保留记录", contexts.single().memoryPoint?.cue)
    }

    @Test
    fun mergesSoftWrappedListTextAndNeverBorrowsFromThePreviousItem() {
        val markdown = """
            - 主体必须先通知，
              并保留记录。[题#12]
            - 题号 13
        """.trimIndent()

        val contexts = CramQuizMemoryPointExtractor.extract(
            markdown = markdown,
            sourceLabel = "AI 冲刺总稿"
        )

        assertEquals(
            "主体必须先通知， 并保留记录",
            contexts.single { it.target.value == 12 }.memoryPoint?.cue
        )
        assertNull(contexts.single { it.target.value == 13 }.memoryPoint)
    }

    @Test
    fun doesNotTurnPureIndexesOrWeakYearCitationsIntoMemoryPoints() {
        val markdown = """
            ## 题号索引
            - 供电中止：12

            ## 补充
            - 参考年份 [2024]
        """.trimIndent()

        val contexts = CramQuizMemoryPointExtractor.extract(
            markdown = markdown,
            sourceLabel = "AI 冲刺总稿"
        )

        assertNull(contexts.single { it.target.value == 12 }.memoryPoint)
        assertNull(contexts.single { it.target.value == 2024 }.memoryPoint)
    }

    @Test
    fun supportsLegacyNumericQuestionReferencesInCachedAiReports() {
        val contexts = CramQuizMemoryPointExtractor.extract(
            markdown = """
                ## 高频母规则
                - 先核对主体，再核对时限。[14, 15]
            """.trimIndent(),
            sourceLabel = "AI 冲刺总稿",
            allowLegacyNumericReferences = true
        )

        assertEquals(
            listOf("先核对主体，再核对时限", "先核对主体，再核对时限"),
            contexts.map { it.memoryPoint?.cue }
        )
    }

    @Test
    fun preservesBracketedYearsInsideAStrongQuestionMemoryPoint() {
        val contexts = CramQuizMemoryPointExtractor.extract(
            markdown = "- 法规于 [2024] 发布，申请应在30日内提出。题号 12",
            sourceLabel = "本地分析"
        )

        assertNull(contexts.single { it.target.value == 2024 }.memoryPoint)
        assertEquals(
            "法规于 [2024] 发布，申请应在30日内提出",
            contexts.single { it.target.value == 12 }.memoryPoint?.cue
        )
    }

    @Test
    fun neverPromotesAConflictingNumberIntoAMemoryRule() {
        val contexts = CramQuizMemoryPointExtractor.extract(
            markdown = """
                ### 数字冲突，禁止直接背
                - **30日**：同一语境同时出现支持与错误陈述；请核对题号 12。
            """.trimIndent(),
            sourceLabel = "本地分析"
        )

        assertNull(contexts.single().memoryPoint)
    }

    @Test
    fun mapsSourceRowsToDatabaseIdsAndKeepsTheClickedOccurrenceFirst() {
        val quizzes = listOf(
            quiz(id = 101, sourceRow = 12),
            quiz(id = 102, sourceRow = 13)
        )
        val contexts = CramQuizMemoryPointExtractor.extract(
            markdown = """
                - 第一条规则 [题#12]
                - 更重要的规则 [题#12]
                - 另一题规则 [题#13]
            """.trimIndent(),
            sourceLabel = "AI 冲刺总稿"
        )
        val preferred = contexts[1].memoryPoint?.id

        val extras = CramQuizReferenceIndex(quizzes).extrasFor(
            contexts = contexts,
            preferredMemoryPointId = preferred
        )

        assertEquals(
            listOf("更重要的规则", "第一条规则"),
            extras.memoryPointsByQuizId.getValue(101).map { it.cue }
        )
        assertEquals(listOf("另一题规则"), extras.memoryPointsByQuizId.getValue(102).map { it.cue })
        assertEquals(preferred, extras.preferredMemoryPointId)
    }

    private fun quiz(id: Int, sourceRow: Int): Quiz {
        return Quiz(
            id = id,
            prompt = "题目$id",
            options = listOf("正确", "错误"),
            answer = setOf(0),
            isMultipleChoice = false,
            questionType = "判断",
            libraryId = 1,
            sourceRow = sourceRow
        )
    }
}
