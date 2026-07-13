package com.virin.visionquiz.screendetector

/** Handles the result of returning from the overlay permission settings page. */
internal object OverlayPermissionResumePolicy {
    enum class Decision {
        CONTINUE,
        CANCEL_PENDING_START,
    }

    fun decide(
        awaitingOverlayPermission: Boolean,
        requiresAccessibility: Boolean,
        overlayPermissionGranted: Boolean
    ): Decision {
        if (!awaitingOverlayPermission || requiresAccessibility) {
            return Decision.CONTINUE
        }
        return if (overlayPermissionGranted) {
            Decision.CONTINUE
        } else {
            Decision.CANCEL_PENDING_START
        }
    }
}
