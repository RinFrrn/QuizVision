package com.virin.visionquiz.vision.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaddleBoxRectMapperTest {

    @Test
    fun mapsRotatedQuadUsingFloorAndCeil() {
        val bounds = PaddleBoxRectMapper.map(
            points = listOf(
                point(12.8f, 21.2f),
                point(91.1f, 18.4f),
                point(94.6f, 56.1f),
                point(10.2f, 59.7f)
            ),
            imageWidth = 200,
            imageHeight = 100
        )

        assertEquals(PaddleBoxRectMapper.IntBounds(10, 18, 95, 60), bounds)
    }

    @Test
    fun clampsQuadToImageBounds() {
        val bounds = PaddleBoxRectMapper.map(
            points = listOf(
                point(-8f, -4f),
                point(120f, -2f),
                point(110f, 80f),
                point(-3f, 75f)
            ),
            imageWidth = 100,
            imageHeight = 60
        )

        assertEquals(PaddleBoxRectMapper.IntBounds(0, 0, 100, 60), bounds)
    }

    @Test
    fun rejectsMalformedOrNonFiniteQuads() {
        assertNull(
            PaddleBoxRectMapper.map(
                points = listOf(point(0f, 0f), point(2f, 2f)),
                imageWidth = 100,
                imageHeight = 100
            )
        )
        assertNull(
            PaddleBoxRectMapper.map(
                points = listOf(
                    point(0f, 0f),
                    point(Float.NaN, 0f),
                    point(10f, 10f),
                    point(0f, 10f)
                ),
                imageWidth = 100,
                imageHeight = 100
            )
        )
    }

    @Test
    fun rejectsDegenerateQuadAfterClamping() {
        assertNull(
            PaddleBoxRectMapper.map(
                points = listOf(
                    point(-10f, 2f),
                    point(-8f, 2f),
                    point(-8f, 20f),
                    point(-10f, 20f)
                ),
                imageWidth = 100,
                imageHeight = 100
            )
        )
    }

    private fun point(x: Float, y: Float): PaddleBoxRectMapper.Coordinate {
        return PaddleBoxRectMapper.Coordinate(x, y)
    }
}
