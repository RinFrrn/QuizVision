package com.virin.visionquiz.vision.questiondetector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrOptionLocatorTest {

    @Test
    fun locatesSingleAndMultipleChoiceAnswers() {
        val candidates = listOf(
            candidate("A. 甲", 1, 100),
            candidate("B、乙", 2, 140),
            candidate("C）丙", 3, 180)
        )
        val single = locate(
            options = listOf("甲", "乙", "丙"),
            answers = setOf(1),
            candidates = candidates
        )
        val multiple = locate(
            options = listOf("甲", "乙", "丙"),
            answers = setOf(0, 2),
            candidates = candidates
        )

        assertEquals(3, single.optionBounds.size)
        assertEquals(listOf(candidates[1].bounds), single.answerBounds)
        assertEquals(listOf(candidates[0].bounds, candidates[2].bounds), multiple.answerBounds)
    }

    @Test
    fun fallsBackToReliableAnswerWhenNotAllOptionsAreVisible() {
        val answer = candidate("B. 一段足够长的正确答案", 2, 140)
        val result = locate(
            options = listOf("另一段较长选项", "一段足够长的正确答案", "缺失选项"),
            answers = setOf(1),
            candidates = listOf(answer)
        )

        assertTrue(result.optionBounds.isEmpty())
        assertEquals(listOf(answer.bounds), result.answerBounds)
    }

    @Test
    fun marksPartialAnswerWhenMultipleChoiceIsIncomplete() {
        val firstAnswer = candidate("A. 第一个正确答案", 1, 100)
        val result = locate(
            options = listOf("第一个正确答案", "错误选项", "第二个正确答案"),
            answers = setOf(0, 2),
            candidates = listOf(firstAnswer)
        )

        assertEquals(listOf(firstAnswer.bounds), result.answerBounds)
        assertTrue(result.isAnswerPartiallyMatched)
    }

    @Test
    fun shortOptionsRequireExactMatch() {
        val result = locate(
            options = listOf("对", "错"),
            answers = setOf(0),
            candidates = listOf(candidate("这句话是对的", 1, 100))
        )

        assertTrue(result.answerBounds.isEmpty())
    }

    @Test
    fun longOptionAllowsSmallOcrTypo() {
        val answer = candidate("社会主义核心介值观", 2, 140)
        val result = locate(
            options = listOf("依法治国基本方略", "社会主义核心价值观"),
            answers = setOf(1),
            candidates = listOf(answer)
        )

        assertEquals(listOf(answer.bounds), result.answerBounds)
    }

    @Test
    fun highThresholdRejectsSmallOcrTypo() {
        val result = locate(
            options = listOf("依法治国基本方略", "社会主义核心价值观"),
            answers = setOf(1),
            candidates = listOf(candidate("社会主义核心介值观", 2, 140)),
            minMatchScore = 0.95
        )

        assertTrue(result.answerBounds.isEmpty())
    }

    @Test
    fun fuzzyOptionStillRejectsDifferentLongText() {
        val result = locate(
            options = listOf("依法治国基本方略", "社会主义核心价值观"),
            answers = setOf(1),
            candidates = listOf(candidate("社会主义市场经济体制", 2, 140))
        )

        assertTrue(result.answerBounds.isEmpty())
    }

    @Test
    fun shortOptionUsesExpectedPrefixWhenOcrTextIsWrong() {
        val expectedAnswer = candidate("B. 已", 2, 140)
        val result = locate(
            options = listOf("甲", "乙"),
            answers = setOf(1),
            candidates = listOf(
                candidate("A. 甲", 1, 100),
                expectedAnswer
            )
        )

        assertEquals(listOf(expectedAnswer.bounds), result.answerBounds)
    }

    @Test
    fun standaloneOptionLabelCombinesWithAdjacentText() {
        val label = candidate("B.", 2, 140, left = 20, width = 30)
        val text = candidate("已", 3, 140, left = 70)
        val result = locate(
            options = listOf("甲", "乙"),
            answers = setOf(1),
            candidates = listOf(
                candidate("A. 甲", 1, 100),
                label,
                text
            )
        )

        assertEquals(
            listOf(
                OcrOptionLocator.Bounds(
                    label.bounds.left,
                    label.bounds.top,
                    text.bounds.right,
                    text.bounds.bottom
                )
            ),
            result.answerBounds
        )
    }

    @Test
    fun wrappedLongOptionCombinesFollowingLine() {
        val firstLine = candidate("B. 这是一个需要换行", 2, 140)
        val secondLine = candidate("才能完整显示", 3, 184, left = 36)
        val result = locate(
            options = listOf("错误选项内容", "这是一个需要换行才能完整显示正确答案内容"),
            answers = setOf(1),
            candidates = listOf(
                candidate("A. 错误选项内容", 1, 100),
                firstLine,
                secondLine
            )
        )

        assertEquals(
            listOf(
                OcrOptionLocator.Bounds(
                    firstLine.bounds.left,
                    firstLine.bounds.top,
                    maxOf(firstLine.bounds.right, secondLine.bounds.right),
                    secondLine.bounds.bottom
                )
            ),
            result.answerBounds
        )
    }

    @Test
    fun wrappedLongOptionStopsAtNextOptionPrefix() {
        val result = locate(
            options = listOf("错误选项内容", "这是一个需要换行才能完整显示正确答案内容"),
            answers = setOf(1),
            candidates = listOf(
                candidate("A. 错误选项内容", 1, 100),
                candidate("B. 这是一个需要换行", 2, 140),
                candidate("C. 另一个选项", 3, 184),
                candidate("才能完整显示", 4, 228, left = 36)
            )
        )

        assertTrue(result.answerBounds.isEmpty())
    }

    @Test
    fun wrongOptionPrefixDoesNotRescueShortText() {
        val result = locate(
            options = listOf("甲", "乙"),
            answers = setOf(1),
            candidates = listOf(candidate("A. 已", 1, 100))
        )

        assertTrue(result.answerBounds.isEmpty())
    }

    @Test
    fun compactChinesePrefixLocatesShortOption() {
        val expectedAnswer = candidate("B乙", 2, 140)
        val result = locate(
            options = listOf("甲", "乙"),
            answers = setOf(1),
            candidates = listOf(
                candidate("A甲", 1, 100),
                expectedAnswer
            )
        )

        assertEquals(listOf(expectedAnswer.bounds), result.answerBounds)
    }

    @Test
    fun nextQuestionBoundaryPreventsCrossQuestionMatch() {
        val result = locate(
            options = listOf("甲", "乙"),
            answers = setOf(1),
            candidates = listOf(
                candidate("A. 甲", 1, 100),
                candidate("下一题", 2, 180),
                candidate("B. 乙", 3, 220)
            ),
            nextQuestionStartOrder = 2
        )

        assertTrue(result.answerBounds.isEmpty())
    }

    @Test
    fun duplicateOptionTextUsesDistinctRects() {
        val first = candidate("A. 相同", 1, 100, left = 20)
        val second = candidate("B. 相同", 2, 100, left = 220)
        val result = locate(
            options = listOf("相同", "相同"),
            answers = setOf(0, 1),
            candidates = listOf(first, second)
        )

        assertEquals(listOf(first.bounds, second.bounds), result.answerBounds)
    }

    @Test
    fun oversizedAndFarCandidatesAreIgnored() {
        val result = locate(
            options = listOf("甲", "乙"),
            answers = setOf(1),
            candidates = listOf(
                candidate("A. 甲", 1, 100),
                candidate("B. 乙", 2, 900, height = 250)
            )
        )

        assertTrue(result.answerBounds.isEmpty())
    }

    private fun locate(
        options: List<String>,
        answers: Set<Int>,
        candidates: List<OcrOptionLocator.TextCandidate>,
        nextQuestionStartOrder: Int? = null,
        minMatchScore: Double = 0.76
    ): OcrOptionLocator.Result {
        return OcrOptionLocator.locate(
            question = OcrOptionLocator.QuestionMatch(
                options = options,
                answerIndices = answers,
                bounds = bounds(20, 40),
                startOrder = 0,
                endOrder = 0
            ),
            candidates = candidates,
            nextQuestionStartOrder = nextQuestionStartOrder,
            imageHeight = 1000,
            minMatchScore = minMatchScore
        )
    }

    private fun candidate(
        text: String,
        order: Int,
        top: Int,
        left: Int = 20,
        height: Int = 40,
        width: Int = 180
    ): OcrOptionLocator.TextCandidate {
        return OcrOptionLocator.TextCandidate(
            text = text,
            bounds = bounds(left, top, height, width),
            order = order
        )
    }

    private fun bounds(
        left: Int,
        top: Int,
        height: Int = 40,
        width: Int = 180
    ): OcrOptionLocator.Bounds {
        return OcrOptionLocator.Bounds(left, top, left + width, top + height)
    }
}
