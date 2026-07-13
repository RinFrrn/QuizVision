package com.virin.visionquiz.vision.questiondetector

/** Publishes matches immediately while requiring confirmation before clearing visible results. */
internal class StableResultGate<T>(
    private val requiredStableResults: Int,
    private val fingerprintOf: (T) -> String,
    private val isEmpty: (T) -> Boolean,
    private val confirmEmptyResults: Boolean = true
) {
    private var candidateFingerprint: String? = null
    private var candidateCount: Int = 0

    fun resolve(newValue: T, displayedValue: T): T {
        if (!confirmEmptyResults) {
            return newValue
        }
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
        // The screen source has already waited for a stable page before invoking OCR, and normally
        // emits only one scan per page. Requiring a second identical non-empty result here would
        // leave the previous page visible indefinitely.
        if (!isEmpty(newValue) || isEmpty(displayedValue)) {
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
