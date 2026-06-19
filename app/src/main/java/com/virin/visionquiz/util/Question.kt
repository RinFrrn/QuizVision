package com.virin.visionquiz.util

import android.graphics.Rect
import com.virin.visionquiz.dao.Quiz

data class QuizGraphicItem(
    val question: Quiz,
    val distance: Double,
    val rect: Rect,
    val answerRects: List<Rect> = emptyList(),
    val optionRects: List<Rect> = emptyList(),
    val isAnswerPartiallyMatched: Boolean = false,
    val debugLines: List<String> = emptyList()
)
