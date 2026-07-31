package com.virin.visionquiz.vision.questiondetector

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrReadingOrderTest {

    @Test
    fun assignsTopToBottomThenLeftToRightRegardlessOfSourceOrder() {
        val label = item(block = 3, line = 0, sourceOrder = 3, left = 40, top = 104)
        val question = item(block = 0, line = 0, sourceOrder = 0, left = 180, top = 100)
        val continuation = item(block = 2, line = 0, sourceOrder = 2, left = 40, top = 180)
        val option = item(block = 1, line = 0, sourceOrder = 1, left = 40, top = 300)

        val orders = OcrReadingOrder.assign(
            listOf(option, question, continuation, label)
        )

        assertEquals(0, orders.getValue(label.key))
        assertEquals(1, orders.getValue(question.key))
        assertEquals(2, orders.getValue(continuation.key))
        assertEquals(3, orders.getValue(option.key))
    }

    @Test
    fun placesLinesWithoutBoundsLastInTheirSourceOrder() {
        val firstMissing = OcrReadingOrder.Item(
            key = OcrReadingOrder.Key(0, 0),
            bounds = null,
            sourceOrder = 0
        )
        val positioned = item(
            block = 1,
            line = 0,
            sourceOrder = 1,
            left = 20,
            top = 20
        )
        val secondMissing = OcrReadingOrder.Item(
            key = OcrReadingOrder.Key(2, 0),
            bounds = null,
            sourceOrder = 2
        )

        val orders = OcrReadingOrder.assign(
            listOf(secondMissing, positioned, firstMissing)
        )

        assertEquals(0, orders.getValue(positioned.key))
        assertEquals(1, orders.getValue(firstMissing.key))
        assertEquals(2, orders.getValue(secondMissing.key))
    }

    private fun item(
        block: Int,
        line: Int,
        sourceOrder: Int,
        left: Int,
        top: Int,
        width: Int = 100,
        height: Int = 40
    ): OcrReadingOrder.Item {
        return OcrReadingOrder.Item(
            key = OcrReadingOrder.Key(block, line),
            bounds = OcrOptionLocator.Bounds(left, top, left + width, top + height),
            sourceOrder = sourceOrder
        )
    }
}
