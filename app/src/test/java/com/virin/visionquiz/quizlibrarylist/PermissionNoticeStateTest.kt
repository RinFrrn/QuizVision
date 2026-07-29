package com.virin.visionquiz.quizlibrarylist

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionNoticeStateTest {

    @Test
    fun permissionNotice_remainsVisibleWhileAnyPermissionIsMissing() {
        assertTrue(
            shouldShowPermissionNotice(
                cameraAllowed = true,
                overlayAllowed = false
            )
        )
    }

    @Test
    fun permissionNotice_hidesOnlyAfterEveryPermissionIsAllowed() {
        assertFalse(
            shouldShowPermissionNotice(
                cameraAllowed = true,
                overlayAllowed = true
            )
        )
    }
}
