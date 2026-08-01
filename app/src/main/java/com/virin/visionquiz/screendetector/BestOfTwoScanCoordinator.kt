package com.virin.visionquiz.screendetector

/** Coordinates an optional forced second scan and merges both results. */
internal class BestOfTwoScanCoordinator<T>(
    private val enabled: Boolean,
    private val merge: (first: T, second: T) -> T
) {
    data class Resolution<T>(
        val value: T,
        val requestSecondScan: Boolean
    )

    private var firstResult: T? = null

    fun resolve(result: T): Resolution<T> {
        if (!enabled) {
            return Resolution(result, requestSecondScan = false)
        }
        val first = firstResult
        if (first == null) {
            firstResult = result
            return Resolution(result, requestSecondScan = true)
        }
        firstResult = null
        return Resolution(merge(first, result), requestSecondScan = false)
    }

    fun takePendingResult(): T? {
        val pending = firstResult
        firstResult = null
        return pending
    }
}
