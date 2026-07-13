package com.virin.visionquiz.vision.questiondetector

/** Prevents a changed non-empty result from being published until it repeats consistently. */
internal class StableResultGate<T>(
    private val requiredStableResults: Int,
    private val fingerprintOf: (T) -> String,
    private val isEmpty: (T) -> Boolean
) {
    private var candidateFingerprint: String? = null
    private var candidateCount: Int = 0

    fun resolve(newValue: T, displayedValue: T): T {
        val newFingerprint = fingerprintOf(newValue)
        if (candidateFingerprint == newFingerprint) {
            candidateCount++
        } else {
            candidateFingerprint = newFingerprint
            candidateCount = 1
        }

        if (isEmpty(newValue) && isEmpty(displayedValue)) {
            return newValue
        }
        if (newFingerprint == fingerprintOf(displayedValue)) {
            return newValue
        }
        return if (candidateCount >= requiredStableResults.coerceAtLeast(1)) {
            newValue
        } else {
            displayedValue
        }
    }

    fun reset() {
        candidateFingerprint = null
        candidateCount = 0
    }
}
