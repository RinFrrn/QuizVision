package com.virin.visionquiz.quizlist.quizcontent

import org.junit.Assert.assertEquals
import org.junit.Test

class QuizContentDialogHandleTest {

    @Test
    fun dismissIsIdempotent() {
        var dismissCount = 0
        val handle = QuizContentDialogHandle {
            dismissCount += 1
        }

        handle.dismiss()
        handle.dismiss()

        assertEquals(1, dismissCount)
    }
}
