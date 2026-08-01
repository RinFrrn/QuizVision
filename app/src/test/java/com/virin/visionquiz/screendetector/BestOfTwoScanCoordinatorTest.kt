package com.virin.visionquiz.screendetector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BestOfTwoScanCoordinatorTest {

    @Test
    fun enabledCoordinatorRequestsSecondScanThenPublishesMergedResult() {
        val coordinator = BestOfTwoScanCoordinator<List<String>>(
            enabled = true,
            merge = { first, second -> (first + second).distinct() }
        )

        val first = coordinator.resolve(listOf("first"))
        val second = coordinator.resolve(listOf("second"))

        assertTrue(first.requestSecondScan)
        assertEquals(listOf("first"), first.value)
        assertFalse(second.requestSecondScan)
        assertEquals(listOf("first", "second"), second.value)
    }

    @Test
    fun disabledCoordinatorNeverRequestsOrMergesSecondScan() {
        val coordinator = BestOfTwoScanCoordinator<List<String>>(
            enabled = false,
            merge = { first, second -> first + second }
        )

        val result = coordinator.resolve(listOf("only"))

        assertFalse(result.requestSecondScan)
        assertEquals(listOf("only"), result.value)
    }

    @Test
    fun pendingFirstResultCanBeTakenForContinuationFailureFallback() {
        val coordinator = BestOfTwoScanCoordinator<List<String>>(
            enabled = true,
            merge = { first, second -> first + second }
        )
        coordinator.resolve(listOf("first"))

        assertEquals(listOf("first"), coordinator.takePendingResult())
        assertEquals(null, coordinator.takePendingResult())

        val nextPage = coordinator.resolve(listOf("next-page"))
        assertTrue(nextPage.requestSecondScan)
    }
}
