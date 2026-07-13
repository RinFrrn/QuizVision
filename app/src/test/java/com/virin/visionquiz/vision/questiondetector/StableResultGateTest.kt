package com.virin.visionquiz.vision.questiondetector

import org.junit.Assert.assertEquals
import org.junit.Test

class StableResultGateTest {
    private val gate = StableResultGate<List<String>>(
        requiredStableResults = 2,
        fingerprintOf = { it.joinToString("|") },
        isEmpty = List<String>::isEmpty
    )

    @Test
    fun firstPositiveResultWaitsForConfirmation() {
        val first = gate.resolve(listOf("question-1"), emptyList())
        val second = gate.resolve(listOf("question-1"), first)

        assertEquals(emptyList<String>(), first)
        assertEquals(listOf("question-1"), second)
    }

    @Test
    fun changedAndClearedResultsAlsoWaitForConfirmation() {
        val displayed = publish(listOf("question-1"))

        val firstChange = gate.resolve(listOf("question-2"), displayed)
        val confirmedChange = gate.resolve(listOf("question-2"), firstChange)
        val firstEmpty = gate.resolve(emptyList(), confirmedChange)
        val confirmedEmpty = gate.resolve(emptyList(), firstEmpty)

        assertEquals(displayed, firstChange)
        assertEquals(listOf("question-2"), confirmedChange)
        assertEquals(confirmedChange, firstEmpty)
        assertEquals(emptyList<String>(), confirmedEmpty)
    }

    @Test
    fun unchangedFingerprintCanRefreshImmediately() {
        val displayed = publish(listOf("question-1"))

        assertEquals(displayed, gate.resolve(displayed, displayed))
    }

    private fun publish(value: List<String>): List<String> {
        val first = gate.resolve(value, emptyList())
        return gate.resolve(value, first)
    }
}
