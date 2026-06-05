package com.virin.visionquiz.util

import android.os.SystemClock

object NavigationBackAnimationSource {
    enum class Source {
        SYSTEM,
        NAVIGATION_BUTTON
    }

    private const val PENDING_SOURCE_TTL_MS = 1_500L

    @Volatile
    private var pendingSource = Source.SYSTEM

    @Volatile
    private var pendingMarkedAt = 0L

    fun markNextPopFromNavigationButton() {
        pendingSource = Source.NAVIGATION_BUTTON
        pendingMarkedAt = SystemClock.uptimeMillis()
    }

    fun currentPopSource(): Source {
        if (pendingSource != Source.NAVIGATION_BUTTON) {
            return Source.SYSTEM
        }
        val elapsed = SystemClock.uptimeMillis() - pendingMarkedAt
        return if (elapsed <= PENDING_SOURCE_TTL_MS) {
            Source.NAVIGATION_BUTTON
        } else {
            clear()
            Source.SYSTEM
        }
    }

    fun clear() {
        pendingSource = Source.SYSTEM
        pendingMarkedAt = 0L
    }
}
