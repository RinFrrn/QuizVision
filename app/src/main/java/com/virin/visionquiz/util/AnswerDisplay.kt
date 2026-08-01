package com.virin.visionquiz.util

import com.virin.visionquiz.dao.Quiz
import com.virin.visionquiz.dao.QuizUiType
import com.virin.visionquiz.dao.inferredUiType

fun abbreviateAnswerText(text: String, enabled: Boolean): String {
    if (!enabled || text.length <= BRIEF_ANSWER_EDGE_LENGTH * 2) {
        return text
    }
    return text.take(BRIEF_ANSWER_EDGE_LENGTH) +
            BRIEF_ANSWER_SEPARATOR +
            text.takeLast(BRIEF_ANSWER_EDGE_LENGTH)
}

fun Quiz.selectAllAnswerHint(): String? {
    val selectsEveryOption = options.isNotEmpty() &&
        answer.size == options.size &&
        answer.containsAll(options.indices.toSet())
    return SELECT_ALL_HINT.takeIf {
        inferredUiType() == QuizUiType.MULTIPLE_CHOICE && selectsEveryOption
    }
}

private const val BRIEF_ANSWER_EDGE_LENGTH = 3
private const val BRIEF_ANSWER_SEPARATOR = "…"
private const val SELECT_ALL_HINT = "全选"
