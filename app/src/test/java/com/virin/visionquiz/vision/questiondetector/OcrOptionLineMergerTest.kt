package com.virin.visionquiz.vision.questiondetector

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrOptionLineMergerTest {

    @Test
    fun mergesStandaloneLabelsWithTextOnTheSameVisualRow() {
        val candidates = listOf(
            candidate("A", order = 900, left = 20, top = 100, width = 30),
            candidate("政府强制", order = 1_900, left = 70, top = 102),
            candidate("B.", order = 2_900, left = 20, top = 180, width = 30),
            candidate("及时更新", order = 3_900, left = 70, top = 182)
        )

        val merged = OcrOptionLineMerger.merge(candidates)

        assertEquals(listOf("A 政府强制", "B. 及时更新"), merged.map { it.text })
        assertEquals(20, merged.first().bounds.left)
        assertEquals(250, merged.first().bounds.right)
    }

    @Test
    fun doesNotMergeAStandaloneLabelWithTextOnAnotherRow() {
        val label = candidate("A", order = 900, left = 20, top = 100, width = 30)
        val nextRow = candidate("A类用户属于重要用户", order = 1_900, left = 70, top = 180)

        assertEquals(
            listOf(label, nextRow),
            OcrOptionLineMerger.merge(listOf(label, nextRow))
        )
    }

    private fun candidate(
        text: String,
        order: Int,
        left: Int,
        top: Int,
        width: Int = 180,
        height: Int = 40
    ): OcrOptionLocator.TextCandidate {
        return OcrOptionLocator.TextCandidate(
            text = text,
            bounds = OcrOptionLocator.Bounds(left, top, left + width, top + height),
            order = order
        )
    }
}
