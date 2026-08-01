package com.virin.visionquiz.quizlist.quizcontent

import com.virin.visionquiz.dao.Quiz

internal fun Quiz.withEditedChoiceAnswer(selectedAnswers: Set<Int>): Quiz {
    require(selectedAnswers.isNotEmpty()) { "Answer is empty" }
    return copy(answer = selectedAnswers)
}

internal fun Quiz.withEditedTextAnswers(answerTexts: List<String>): Quiz {
    val normalizedAnswers = answerTexts.map(String::trim)
    require(normalizedAnswers.isNotEmpty()) { "Answer is empty" }
    require(normalizedAnswers.all(String::isNotBlank)) { "Answer contains blank text" }
    return copy(
        options = normalizedAnswers,
        answer = normalizedAnswers.indices.toSet()
    )
}
