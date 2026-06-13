package com.virin.visionquiz.quizstudy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwipeNavigationGesturePolicyTest {
    private val policy = SwipeNavigationGesturePolicy(
        horizontalLockDistance = 24f,
        verticalLockDistance = 16f,
        longSwipeDistance = 180f,
        quickSwipeDistance = 96f,
        minimumFlingVelocity = 1_200f
    )

    @Test
    fun directionRequiresClearHorizontalIntent() {
        assertEquals(
            GestureDirection.HORIZONTAL,
            policy.resolveDirection(absDeltaX = 80f, absDeltaY = 30f)
        )
        assertEquals(
            GestureDirection.VERTICAL,
            policy.resolveDirection(absDeltaX = 30f, absDeltaY = 80f)
        )
        assertEquals(
            GestureDirection.UNDECIDED,
            policy.resolveDirection(absDeltaX = 50f, absDeltaY = 40f)
        )
    }

    @Test
    fun longDeliberateSwipeNavigatesWithoutVelocityRequirement() {
        assertTrue(
            policy.shouldNavigate(
                deltaX = -190f,
                deltaY = 40f,
                velocityX = -100f,
                velocityY = 0f
            )
        )
    }

    @Test
    fun shortSwipeRequiresFastHorizontalFlingInSameDirection() {
        assertTrue(
            policy.shouldNavigate(
                deltaX = 110f,
                deltaY = 20f,
                velocityX = 1_500f,
                velocityY = 100f
            )
        )
        assertFalse(
            policy.shouldNavigate(
                deltaX = 110f,
                deltaY = 20f,
                velocityX = 800f,
                velocityY = 100f
            )
        )
        assertFalse(
            policy.shouldNavigate(
                deltaX = 110f,
                deltaY = 20f,
                velocityX = -1_500f,
                velocityY = 100f
            )
        )
    }

    @Test
    fun diagonalOrSmallMovementDoesNotNavigate() {
        assertFalse(
            policy.shouldNavigate(
                deltaX = 190f,
                deltaY = 140f,
                velocityX = 2_000f,
                velocityY = 100f
            )
        )
        assertFalse(
            policy.shouldNavigate(
                deltaX = 70f,
                deltaY = 5f,
                velocityX = 2_000f,
                velocityY = 0f
            )
        )
    }
}
