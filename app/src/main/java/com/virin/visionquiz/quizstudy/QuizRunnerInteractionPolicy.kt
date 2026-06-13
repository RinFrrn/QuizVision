package com.virin.visionquiz.quizstudy

import com.virin.visionquiz.dao.QuizStudyMode
import com.virin.visionquiz.dao.QuizUiType

internal object QuizRunnerInteractionPolicy {
    fun nextSelection(
        type: QuizUiType,
        current: Set<Int>,
        optionIndex: Int
    ): Set<Int> {
        return if (type == QuizUiType.MULTIPLE_CHOICE) {
            if (optionIndex in current) current - optionIndex else current + optionIndex
        } else {
            setOf(optionIndex)
        }
    }

    fun isAnswerVisible(
        mode: QuizStudyMode,
        reviewMode: Boolean,
        quizId: Int,
        submittedPracticeQuizIds: Set<Int>
    ): Boolean {
        return reviewMode ||
            (mode != QuizStudyMode.EXAM && quizId in submittedPracticeQuizIds)
    }
}
