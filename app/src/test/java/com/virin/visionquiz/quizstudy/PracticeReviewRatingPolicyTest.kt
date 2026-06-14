package com.virin.visionquiz.quizstudy

import com.virin.visionquiz.dao.ReviewRating
import org.junit.Assert.assertEquals
import org.junit.Test

class PracticeReviewRatingPolicyTest {
    @Test
    fun correctPracticeAnswerDefaultsToGood() {
        assertEquals(
            ReviewRating.GOOD,
            PracticeReviewRatingPolicy.defaultRatingForPracticeAnswer(isCorrect = true)
        )
    }

    @Test
    fun incorrectPracticeAnswerDefaultsToForgot() {
        assertEquals(
            ReviewRating.FORGOT,
            PracticeReviewRatingPolicy.defaultRatingForPracticeAnswer(isCorrect = false)
        )
    }
}
