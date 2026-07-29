package com.virin.visionquiz.util

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class ImportCandidateSettingsTest {
    @Test
    fun defaultPromptHeadersRecognizeExamQuestionBody() {
        assertTrue("试题正文" in ImportCandidateConfig().promptHeaders)
    }

    @Test
    fun duplicateCandidateIsFoundAcrossDifferentGroups() {
        val owner = findImportCandidateOwner(
            candidate = " 题 目 ",
            groups = listOf(
                "题目表头" to listOf("题目"),
                "题型表头" to listOf("题型")
            )
        )

        assertEquals("题目表头", owner)
    }
}
