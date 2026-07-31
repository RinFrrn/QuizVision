package com.virin.visionquiz.vision.questiondetector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrQuestionCandidateBuilderTest {

    @Test
    fun rebuildsScreenshot46QuestionAcrossHorizontalAndVerticalBlocks() {
        val candidates = OcrQuestionCandidateBuilder.build(
            listOf(
                line("单选题", block = 0, order = 0, left = 42, top = 262, width = 130),
                line(
                    "46、根据《电力需求侧管理办法",
                    block = 1,
                    order = 1_000,
                    left = 205,
                    top = 264,
                    width = 810
                ),
                line(
                    "（2023年版）》，有序用电方案应：",
                    block = 2,
                    order = 2_000,
                    left = 72,
                    top = 342,
                    width = 850
                ),
                line("A 政府强制", block = 3, order = 3_000, left = 44, top = 548)
            )
        )

        val fullQuestion = candidates.single { it.visualLineCount == 2 }
        assertEquals(setOf(0, 1, 2), fullQuestion.blockIndices)
        assertTrue(fullQuestion.text.contains("46、根据《电力需求侧管理办法"))
        assertTrue(fullQuestion.text.contains("有序用电方案应"))
        assertFalse(fullQuestion.text.contains("政府强制"))
    }

    @Test
    fun rebuildsScreenshot77QuestionButStopsBeforeShortOptions() {
        val candidates = OcrQuestionCandidateBuilder.build(
            listOf(
                line("单选题", block = 10, order = 0, left = 42, top = 262, width = 126),
                line(
                    "77、根据《电力负荷管理办法（2023年",
                    block = 11,
                    order = 1_000,
                    left = 198,
                    top = 264,
                    width = 820
                ),
                line(
                    "版）》，实施有序用电应至少提前多久告知用户：",
                    block = 12,
                    order = 2_000,
                    left = 42,
                    top = 326,
                    width = 950
                ),
                line("A. 12小时", block = 13, order = 3_000, left = 43, top = 500),
                line("B. 1小时", block = 14, order = 4_000, left = 43, top = 650)
            )
        )

        val fullQuestion = candidates.single { it.visualLineCount == 2 }
        assertEquals(setOf(10, 11, 12), fullQuestion.blockIndices)
        assertTrue(fullQuestion.text.contains("至少提前多久告知用户"))
        assertTrue(candidates.none { it.text.contains("12小时") || it.text.contains("1小时") })
    }

    @Test
    fun optionAndNextQuestionMarkersAreHardWindowBoundaries() {
        val candidates = OcrQuestionCandidateBuilder.build(
            listOf(
                line("18、第一题的第一行", block = 0, order = 0, left = 40, top = 100),
                line("第一题的第二行", block = 1, order = 1_000, left = 40, top = 170),
                line("A 第一题选项", block = 2, order = 2_000, left = 40, top = 240),
                line("19、第二题题干", block = 3, order = 3_000, left = 40, top = 310),
                line("第二题的第二行", block = 4, order = 4_000, left = 40, top = 380)
            )
        )

        assertTrue(candidates.any { it.text.contains("第一题的第二行") })
        assertTrue(candidates.any { it.text.contains("19、第二题题干") })
        assertTrue(candidates.none { it.text.contains("第一题选项") })
        assertTrue(
            candidates.none {
                it.text.contains("第一题的第二行") && it.text.contains("19、第二题题干")
            }
        )
    }

    @Test
    fun doesNotTreatCategoryOrParenthesizedSubpointAsHardBoundary() {
        val candidates = OcrQuestionCandidateBuilder.build(
            listOf(
                line("题干第一行", block = 0, order = 0, left = 40, top = 100),
                line("A类用户应按规定执行", block = 1, order = 1_000, left = 40, top = 160),
                line("1）其中重要用户需提前报告", block = 2, order = 2_000, left = 40, top = 220),
                line("A 正确选项", block = 3, order = 3_000, left = 40, top = 300)
            )
        )

        assertTrue(
            candidates.any {
                it.text.contains("A类用户") && it.text.contains("1）其中重要用户")
            }
        )
        assertTrue(candidates.none { it.text.contains("正确选项") })
    }

    @Test
    fun recognizesExplicitQuestionNumberBoundariesWithoutOvermatchingSubpoints() {
        assertTrue(OcrQuestionCandidateBuilder.isQuestionStartLine("第19题 下列说法正确的是"))
        assertTrue(OcrQuestionCandidateBuilder.isQuestionStartLine("19）下列说法正确的是"))
        assertFalse(OcrQuestionCandidateBuilder.isQuestionStartLine("1）其中第一种情况"))
    }

    @Test
    fun acceptsLargeRelativeGapButRejectsGapBeyondLineHeightLimit() {
        val candidates = OcrQuestionCandidateBuilder.build(
            listOf(
                line("题干第一行", block = 0, order = 0, left = 40, top = 100),
                // 90 px of whitespace for a 40 px line: large, but still plausibly one paragraph.
                line("题干第二行", block = 1, order = 1_000, left = 44, top = 230),
                // 110 px of whitespace: beyond the 2.5-line-height boundary.
                line("不应被拼入的远行", block = 2, order = 2_000, left = 44, top = 380)
            )
        )

        assertTrue(
            candidates.any {
                it.text.contains("题干第一行") && it.text.contains("题干第二行")
            }
        )
        assertTrue(candidates.none { it.text.contains("不应被拼入的远行") })
    }

    @Test
    fun returnsOnlyCrossBlockCandidatesAndDeduplicatesIdenticalInput() {
        val first = line("同一块第一行", block = 0, order = 0, left = 40, top = 100)
        val second = line("同一块第二行", block = 0, order = 1_000, left = 40, top = 160)
        assertTrue(OcrQuestionCandidateBuilder.build(listOf(first, second)).isEmpty())

        val crossBlock = second.copy(blockIndex = 1)
        val candidates = OcrQuestionCandidateBuilder.build(
            listOf(first, crossBlock, crossBlock)
        )
        assertEquals(1, candidates.size)
        assertEquals(setOf(0, 1), candidates.single().blockIndices)
    }

    private fun line(
        text: String,
        block: Int,
        order: Int,
        left: Int,
        top: Int,
        width: Int = 700,
        height: Int = 40
    ): OcrQuestionCandidateBuilder.Line {
        return OcrQuestionCandidateBuilder.Line(
            text = text,
            bounds = OcrOptionLocator.Bounds(left, top, left + width, top + height),
            order = order,
            blockIndex = block
        )
    }
}
