package com.virin.visionquiz.quizstudy

import kotlin.math.abs

internal enum class GestureDirection {
    UNDECIDED,
    HORIZONTAL,
    VERTICAL
}

internal class SwipeNavigationGesturePolicy(
    private val horizontalLockDistance: Float,
    private val verticalLockDistance: Float,
    private val longSwipeDistance: Float,
    private val quickSwipeDistance: Float,
    private val minimumFlingVelocity: Float
) {
    fun resolveDirection(absDeltaX: Float, absDeltaY: Float): GestureDirection {
        return when {
            absDeltaY >= verticalLockDistance &&
                absDeltaY > absDeltaX * VERTICAL_DIRECTION_RATIO -> GestureDirection.VERTICAL
            absDeltaX >= horizontalLockDistance &&
                absDeltaX > absDeltaY * HORIZONTAL_DIRECTION_RATIO -> GestureDirection.HORIZONTAL
            else -> GestureDirection.UNDECIDED
        }
    }

    fun shouldNavigate(
        deltaX: Float,
        deltaY: Float,
        velocityX: Float,
        velocityY: Float
    ): Boolean {
        val absDeltaX = abs(deltaX)
        val absDeltaY = abs(deltaY)
        if (absDeltaX <= absDeltaY * HORIZONTAL_DIRECTION_RATIO) return false
        if (absDeltaX >= longSwipeDistance) return true
        return absDeltaX >= quickSwipeDistance &&
            abs(velocityX) >= minimumFlingVelocity &&
            abs(velocityX) > abs(velocityY) * HORIZONTAL_DIRECTION_RATIO &&
            velocityX * deltaX > 0f
    }

    companion object {
        private const val HORIZONTAL_DIRECTION_RATIO = 1.5f
        private const val VERTICAL_DIRECTION_RATIO = 1.1f
    }
}
