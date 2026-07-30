package com.virin.visionquiz.dao

import com.virin.visionquiz.util.ImportCandidateConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class QuizManagerImportTest {
    @Test
    fun combinedOptionCellSplitsAmpersandsAndDropsBlankSegments() {
        val options = QuizManager.splitCombinedOptionCell(
            "  选项一 & 选项二&&选项三 ＆ 选项四  "
        )

        assertEquals(
            listOf("选项一", "选项二", "选项三", "选项四"),
            options
        )
    }

    @Test
    fun combinedOptionsHeaderDoesNotReplaceSeparatedOptionColumns() {
        val settings = ImportCandidateConfig()

        assertEquals(
            2,
            QuizManager.findCombinedOptionsHeaderIndex(
                headers = listOf("题型", "试题正文", "试题选项", "试题答案"),
                optionPrefixes = settings.optionPrefixes
            )
        )
        assertNull(
            QuizManager.findCombinedOptionsHeaderIndex(
                headers = listOf("题型", "试题正文", "试题选项A", "试题选项B", "试题答案"),
                optionPrefixes = settings.optionPrefixes
            )
        )
    }

    @Test
    fun genericMappedRowKeepsCombinedOptionsAndSourceMetadata() {
        val settings = ImportCandidateConfig()
        val headers = listOf(
            "序号",
            "题型",
            "试题正文",
            "试题答案",
            "依据",
            "备注",
            "试题选项"
        )
        val mapping = QuizManager.resolveHeaderMapping(headers, settings)?.mapping
        assertNotNull(mapping)

        val parsed = QuizManager.parseMappedRow(
            rowValues = listOf(
                "1",
                "单选题",
                "供电企业应当按照什么计收电费？",
                "B",
                "《中华人民共和国电力法》",
                "注意责任主体",
                "当地政府 & 物价部门 & 计量部门 & 国家"
            ),
            headerMapping = requireNotNull(mapping),
            sourceRow = 2,
            settings = settings
        )
        val draft = requireNotNull(parsed.draft)

        assertEquals(listOf("当地政府", "物价部门", "计量部门", "国家"), draft.options)
        assertEquals(setOf(1), draft.answer)
        assertEquals("《中华人民共和国电力法》", draft.reference)
        assertEquals("注意责任主体", draft.explanation)
        assertEquals(1, draft.sourceRow)
    }

    @Test
    fun genericMappedRowStillReadsSeparatedOptionColumns() {
        val settings = ImportCandidateConfig()
        val headers = listOf(
            "题型",
            "试题正文",
            "试题答案",
            "选项A",
            "选项B",
            "选项C"
        )
        val mapping = requireNotNull(
            QuizManager.resolveHeaderMapping(headers, settings)?.mapping
        )

        val draft = requireNotNull(
            QuizManager.parseMappedRow(
                rowValues = listOf("多选题", "请选择正确项", "AC", "甲", "乙", "丙"),
                headerMapping = mapping,
                sourceRow = 8,
                settings = settings
            ).draft
        )

        assertEquals(listOf("甲", "乙", "丙"), draft.options)
        assertEquals(setOf(0, 2), draft.answer)
        assertEquals(8, draft.sourceRow)
    }

    @Test
    fun legacyPositionalQuizConstructorLeavesImportMetadataEmpty() {
        val quiz = Quiz(
            1,
            "题目",
            listOf("正确", "错误"),
            setOf(0),
            false,
            "判断",
            2
        )

        assertNull(quiz.explanation)
        assertNull(quiz.reference)
        assertNull(quiz.sourceRow)
    }
}
