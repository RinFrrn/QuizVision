package com.virin.visionquiz.vision.questiondetector

import com.virin.visionquiz.dao.Quiz
import com.virin.visionquiz.dao.QuizManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrQuestionMatchingPipelineTest {

    @Test
    fun screenshot8CandidateHandlesFiveVisualLinesAndSplitDecimalScore() {
        val prompt =
            "根据《电力设施保护条例》规定：在依法划定的电力设施保护区内种植的或自然生长的" +
                "可能危及电力设施安全的树木、竹子，（）应依法予以砍伐。"
        val quiz = quiz(
            id = 8,
            prompt = prompt,
            options = listOf("电力企业", "当地政府部门", "电力管理部门", "用户")
        )
        val lines = listOf(
            line("单选题", block = 0, order = 0, left = 42, top = 260, width = 130),
            line(
                "8、根据《电力设施保护条例》规",
                block = 1,
                order = 1_000,
                left = 205,
                top = 262,
                width = 780
            ),
            line(
                "定：在依法划定的电力设施保护区内种植",
                block = 2,
                order = 2_000,
                left = 42,
                top = 330,
                width = 920
            ),
            line(
                "的或自然生长的可能危及电力设施安全的",
                block = 3,
                order = 3_000,
                left = 42,
                top = 398,
                width = 920
            ),
            line(
                "树木、竹子，（）应依法予以砍伐。（0.5",
                block = 4,
                order = 4_000,
                left = 42,
                top = 466,
                width = 920
            ),
            line("0分）", block = 5, order = 5_000, left = 42, top = 534, width = 100),
            line("A 电力企业", block = 6, order = 6_000, left = 42, top = 680)
        )

        assertBestCandidateMatches(quiz, lines)
    }

    @Test
    fun screenshot19CandidateHandlesBlankSplitAcrossLines() {
        val prompt =
            "根据《国家电网有限公司电费业务管理办法》要求，电力客户根据（）执行国家目录销售电价体系" +
                "或输配电价体系。"
        val quiz = quiz(
            id = 19,
            prompt = prompt,
            options = listOf("用电特性", "用电类别", "用电性质", "行业类别")
        )
        val lines = listOf(
            line("单选题", block = 0, order = 0, left = 42, top = 260, width = 130),
            line(
                "19、根据《国家电网有限公司电",
                block = 1,
                order = 1_000,
                left = 205,
                top = 262,
                width = 780
            ),
            line(
                "费业务管理办法》要求，电力客户根据（",
                block = 2,
                order = 2_000,
                left = 42,
                top = 330,
                width = 920
            ),
            line(
                "）执行国家目录销售电价体系或输配电价",
                block = 3,
                order = 3_000,
                left = 42,
                top = 398,
                width = 920
            ),
            line(
                "体系。（0.50分）",
                block = 4,
                order = 4_000,
                left = 42,
                top = 466,
                width = 400
            ),
            line("A 用电特性", block = 5, order = 5_000, left = 42, top = 610)
        )

        assertBestCandidateMatches(quiz, lines)
    }

    @Test
    fun screenshot46CandidateMatchesAfterCrossBlockMergeAndCleaning() {
        val prompt =
            "根据《电力需求侧管理办法（2023年版）》，有序用电方案应："
        val quiz = quiz(
            id = 46,
            prompt = prompt,
            options = listOf("政府强制", "及时更新", "长期不变", "用户制定")
        )
        val lines = listOf(
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
                "（2023年版）》，有序用电方案应：（0.50",
                block = 2,
                order = 2_000,
                left = 42,
                top = 342,
                width = 920
            ),
            line("分）", block = 3, order = 3_000, left = 42, top = 420, width = 80),
            line("A 政府强制", block = 4, order = 4_000, left = 42, top = 548)
        )

        val candidate = OcrQuestionCandidateBuilder.build(lines)
            .maxByOrNull { it.visualLineCount }
            ?: error("Expected a cross-block question candidate")
        val cleaned = OcrQuestionTextCleaner.clean(candidate.text)
        val matches = QuizManager.matchQuiz(
            input = cleaned,
            questions = listOf(quiz),
            minScore = 0.90,
            maxResults = 5
        )

        assertEquals(
            QuizManager.normalizeQuestionText(prompt),
            QuizManager.normalizeQuestionText(cleaned)
        )
        assertEquals(46, matches.single().first.id)
        assertTrue(matches.single().second >= 0.90)
    }

    @Test
    fun screenshot77CandidateStopsBeforeVeryShortOptionsAndMatches() {
        val prompt =
            "根据《电力负荷管理办法（2023年版）》，实施有序用电应至少提前多久告知用户："
        val quiz = quiz(
            id = 77,
            prompt = prompt,
            options = listOf("12小时", "1小时", "1天", "3天")
        )
        val lines = listOf(
            line("单选题", block = 0, order = 0, left = 42, top = 262, width = 130),
            line(
                "77、根据《电力负荷管理办法（2023年",
                block = 1,
                order = 1_000,
                left = 198,
                top = 264,
                width = 820
            ),
            line(
                "版）》，实施有序用电应至少提前多久告知用户：（0.50分）",
                block = 2,
                order = 2_000,
                left = 42,
                top = 326,
                width = 950
            ),
            line("A 12小时", block = 3, order = 3_000, left = 42, top = 500)
        )

        val candidate = OcrQuestionCandidateBuilder.build(lines)
            .maxByOrNull { it.visualLineCount }
            ?: error("Expected a cross-block question candidate")
        val cleaned = OcrQuestionTextCleaner.clean(candidate.text)
        val matches = QuizManager.matchQuiz(
            input = cleaned,
            questions = listOf(quiz),
            minScore = 0.90,
            maxResults = 5
        )

        assertTrue(candidate.text.contains("至少提前多久告知用户"))
        assertTrue(candidate.text.contains("12小时").not())
        assertEquals(77, matches.single().first.id)
    }

    private fun quiz(id: Int, prompt: String, options: List<String>): Quiz {
        return Quiz(
            id = id,
            prompt = prompt,
            options = options,
            answer = setOf(0),
            isMultipleChoice = false,
            libraryId = 1
        )
    }

    private fun assertBestCandidateMatches(
        quiz: Quiz,
        lines: List<OcrQuestionCandidateBuilder.Line>
    ) {
        val candidate = OcrQuestionCandidateBuilder.build(lines)
            .maxByOrNull { it.visualLineCount }
            ?: error("Expected a cross-block question candidate")
        val cleaned = OcrQuestionTextCleaner.clean(candidate.text)
        val matches = QuizManager.matchQuiz(
            input = cleaned,
            questions = listOf(quiz),
            minScore = 0.90,
            maxResults = 5
        )

        assertEquals(
            QuizManager.normalizeQuestionText(quiz.prompt),
            QuizManager.normalizeQuestionText(cleaned)
        )
        assertEquals(quiz.id, matches.single().first.id)
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
