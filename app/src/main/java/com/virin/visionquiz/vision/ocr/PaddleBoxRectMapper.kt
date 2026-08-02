package com.virin.visionquiz.vision.ocr

import android.graphics.Rect
import kotlin.math.ceil
import kotlin.math.floor

internal object PaddleBoxRectMapper {

    data class Coordinate(val x: Float, val y: Float)

    data class IntBounds(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    ) {
        fun toRect(): Rect = Rect(left, top, right, bottom)
    }

    /** Converts an arbitrary four-point quadrilateral into a clamped axis-aligned rectangle. */
    fun map(
        points: List<Coordinate>,
        imageWidth: Int,
        imageHeight: Int
    ): IntBounds? {
        if (
            points.size != EXPECTED_POINT_COUNT ||
            imageWidth <= 0 ||
            imageHeight <= 0 ||
            points.any { !it.x.isFinite() || !it.y.isFinite() }
        ) {
            return null
        }

        val minX = points.minOf(Coordinate::x).coerceIn(0f, imageWidth.toFloat())
        val minY = points.minOf(Coordinate::y).coerceIn(0f, imageHeight.toFloat())
        val maxX = points.maxOf(Coordinate::x).coerceIn(0f, imageWidth.toFloat())
        val maxY = points.maxOf(Coordinate::y).coerceIn(0f, imageHeight.toFloat())
        val left = floor(minX.toDouble()).toInt().coerceIn(0, imageWidth)
        val top = floor(minY.toDouble()).toInt().coerceIn(0, imageHeight)
        val right = ceil(maxX.toDouble()).toInt().coerceIn(0, imageWidth)
        val bottom = ceil(maxY.toDouble()).toInt().coerceIn(0, imageHeight)
        if (right <= left || bottom <= top) {
            return null
        }
        return IntBounds(left, top, right, bottom)
    }

    private const val EXPECTED_POINT_COUNT = 4
}
