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
    fun firstPositiveResultPublishesImmediately() {
        val first = gate.resolve(listOf("question-1"), emptyList())

        assertEquals(listOf("question-1"), first)
    }

    @Test
    fun changedResultPublishesImmediatelyButClearWaitsForConfirmation() {
        val displayed = publish(listOf("question-1"))

        val changed = gate.resolve(listOf("question-2"), displayed)
        val firstEmpty = gate.resolve(emptyList(), changed)
        val confirmedEmpty = gate.resolve(emptyList(), firstEmpty)

        assertEquals(listOf("question-2"), changed)
        assertEquals(changed, firstEmpty)
        assertEquals(emptyList<String>(), confirmedEmpty)
    }

    @Test
    fun unchangedFingerprintCanRefreshImmediately() {
        val displayed = publish(listOf("question-1"))

        assertEquals(displayed, gate.resolve(displayed, displayed))
    }

    @Test
    fun screenPolicyPublishesEmptyResultImmediately() {
        val screenGate = StableResultGate<List<String>>(
            requiredStableResults = 2,
            fingerprintOf = { it.joinToString("|") },
            isEmpty = List<String>::isEmpty,
            confirmEmptyResults = false
        )

        assertEquals(
            emptyList<String>(),
            screenGate.resolve(emptyList(), listOf("question-1"))
        )
    }

    private fun publish(value: List<String>): List<String> {
        return gate.resolve(value, emptyList())
    }
}
