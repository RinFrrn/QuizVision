package com.virin.visionquiz.util

fun abbreviateAnswerText(text: String, enabled: Boolean): String {
    if (!enabled || text.length <= BRIEF_ANSWER_EDGE_LENGTH * 2) {
        return text
    }
    return text.take(BRIEF_ANSWER_EDGE_LENGTH) +
            BRIEF_ANSWER_SEPARATOR +
            text.takeLast(BRIEF_ANSWER_EDGE_LENGTH)
}

private const val BRIEF_ANSWER_EDGE_LENGTH = 3
private const val BRIEF_ANSWER_SEPARATOR = "…"
