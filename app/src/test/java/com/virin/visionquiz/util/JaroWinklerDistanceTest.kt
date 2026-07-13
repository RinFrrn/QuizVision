package com.virin.visionquiz.util

import org.junit.Assert.assertEquals
import org.junit.Test

class JaroWinklerDistanceTest {
    @Test
    fun identicalAndEmptyInputsFollowSimilarityContract() {
        assertEquals(1.0, JaroWinklerDistance.computeJaroWinklerDistance("", ""), EPSILON)
        assertEquals(1.0, JaroWinklerDistance.computeJaroWinklerDistance("a", "a"), EPSILON)
        assertEquals(0.0, JaroWinklerDistance.computeJaroWinklerDistance("", "a"), EPSILON)
    }

    @Test
    fun standardTranspositionExamplesUseHalfTranspositions() {
        assertEquals(
            0.961111,
            JaroWinklerDistance.computeJaroWinklerDistance("MARTHA", "MARHTA"),
            EPSILON
        )
        assertEquals(
            0.813333,
            JaroWinklerDistance.computeJaroWinklerDistance("DIXON", "DICKSONX"),
            EPSILON
        )
    }

    @Test
    fun similarityIsSymmetric() {
        val forward = JaroWinklerDistance.computeJaroWinklerDistance("安全生产", "安全生産")
        val reverse = JaroWinklerDistance.computeJaroWinklerDistance("安全生産", "安全生产")

        assertEquals(forward, reverse, EPSILON)
    }

    private companion object {
        private const val EPSILON = 0.000001
    }
}
