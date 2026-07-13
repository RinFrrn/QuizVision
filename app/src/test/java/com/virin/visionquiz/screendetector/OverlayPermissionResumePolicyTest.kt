package com.virin.visionquiz.screendetector

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayPermissionResumePolicyTest {
    @Test
    fun deniedPermissionCancelsPendingScreenStart() {
        val decision = OverlayPermissionResumePolicy.decide(
            awaitingOverlayPermission = true,
            requiresAccessibility = false,
            overlayPermissionGranted = false
        )

        assertEquals(
            OverlayPermissionResumePolicy.Decision.CANCEL_PENDING_START,
            decision
        )
    }

    @Test
    fun grantedPermissionContinuesPendingScreenStart() {
        val decision = OverlayPermissionResumePolicy.decide(
            awaitingOverlayPermission = true,
            requiresAccessibility = false,
            overlayPermissionGranted = true
        )

        assertEquals(OverlayPermissionResumePolicy.Decision.CONTINUE, decision)
    }

    @Test
    fun unrelatedResumeDoesNotCancelPendingStart() {
        val decision = OverlayPermissionResumePolicy.decide(
            awaitingOverlayPermission = false,
            requiresAccessibility = false,
            overlayPermissionGranted = false
        )

        assertEquals(OverlayPermissionResumePolicy.Decision.CONTINUE, decision)
    }

    @Test
    fun accessibilityFlowKeepsItsExistingPermissionDialogHandling() {
        val decision = OverlayPermissionResumePolicy.decide(
            awaitingOverlayPermission = true,
            requiresAccessibility = true,
            overlayPermissionGranted = false
        )

        assertEquals(OverlayPermissionResumePolicy.Decision.CONTINUE, decision)
    }
}
