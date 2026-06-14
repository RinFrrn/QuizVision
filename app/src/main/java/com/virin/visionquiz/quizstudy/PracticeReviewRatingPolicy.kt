package com.virin.visionquiz.quizstudy

import com.virin.visionquiz.dao.ReviewRating

internal object PracticeReviewRatingPolicy {
    fun defaultRatingForPracticeAnswer(isCorrect: Boolean): ReviewRating {
        return if (isCorrect) ReviewRating.GOOD else ReviewRating.FORGOT
    }
}
