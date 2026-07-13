package com.virin.visionquiz.screendetector

/** Decides when the full floating control must remain visible for user action. */
internal object FloatingControlAutoCollapsePolicy {
    enum class Decision {
        NONE,
        EXPAND,
        COLLAPSE_AFTER_DELAY,
    }

    fun decide(
        enabled: Boolean,
        detectionState: ScreenDetectorSession.DetectionState,
        assistanceState: ScreenDetectorSession.AssistanceState
    ): Decision {
        if (!enabled) return Decision.NONE
        if (detectionState != ScreenDetectorSession.DetectionState.RUNNING) {
            return Decision.EXPAND
        }
        if (assistanceState.indicator == ScreenDetectorSession.AssistanceIndicator.ERROR) {
            return Decision.EXPAND
        }
        val needsPageDirection = assistanceState.isActive &&
            assistanceState.phase == ScreenDetectorSession.AssistancePhase.WAITING_MANUAL_PAGE &&
            assistanceState.pageDirection == null
        return if (needsPageDirection) {
            Decision.EXPAND
        } else {
            Decision.COLLAPSE_AFTER_DELAY
        }
    }
}
