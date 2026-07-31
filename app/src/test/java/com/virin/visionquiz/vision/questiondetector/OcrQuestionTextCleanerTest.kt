package com.virin.visionquiz.vision.questiondetector

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrQuestionTextCleanerTest {

    @Test
    fun cleansScreenshot46TypeNumberAndDecimalScore() {
        val input = """
            单选题 46、根据《电力需求侧管理办法
            （2023年版）》，有序用电方案应： （0.50分）
        """.trimIndent()

        assertEquals(
            """
                根据《电力需求侧管理办法
                （2023年版）》，有序用电方案应：
            """.trimIndent(),
            OcrQuestionTextCleaner.clean(input)
        )
    }

    @Test
    fun cleansScreenshot77WhenLabelAndNumberUseFullWidthPunctuation() {
        val input =
            "【单选题】 ７７．根据《电力负荷管理办法（2023年版）》，" +
                "实施有序用电应至少提前多久告知用户：（０．５\n０ 分）"

        assertEquals(
            "根据《电力负荷管理办法（2023年版）》，实施有序用电应至少提前多久告知用户：",
            OcrQuestionTextCleaner.clean(input)
        )
    }

    @Test
    fun supportsOtherExplicitQuestionLabelsAndQuestionNumberLabel() {
        assertEquals(
            "下列说法正确的是",
            OcrQuestionTextCleaner.clean("多选题：第12题、下列说法正确的是")
        )
        assertEquals(
            "线路损耗是指什么",
            OcrQuestionTextCleaner.clean("题号：8、线路损耗是指什么")
        )
        assertEquals(
            "供电方案是否合理",
            OcrQuestionTextCleaner.clean("判断题 9）供电方案是否合理")
        )
        assertEquals(
            "请填写正确名称",
            OcrQuestionTextCleaner.clean("填空题 10. 请填写正确名称")
        )
    }

    @Test
    fun leavesOrdinaryPromptPrefixesAndParenthesesUntouched() {
        val inputs = listOf(
            "判断题意是否明确（2023年版）",
            "判断题 通常需要判断正误",
            "单选题库中的题目如何管理",
            "2023年版电力负荷管理办法",
            "第46章规定了什么",
            "至少提前多久告知用户（12小时）"
        )

        inputs.forEach { input ->
            assertEquals(input, OcrQuestionTextCleaner.clean(input))
        }
    }

    @Test
    fun disabledCleaningReturnsOriginalTextExactly() {
        val input = "  单选题 46、题干内容 （0.50分）  "

        assertEquals(input, OcrQuestionTextCleaner.clean(input, enabled = false))
    }
}
