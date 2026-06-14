package com.virin.visionquiz.quizstudy

import com.virin.visionquiz.dao.PracticeSession
import com.virin.visionquiz.dao.Quiz
import com.virin.visionquiz.dao.QuizStudyMode
import com.virin.visionquiz.dao.isSupportedStudyType

internal data class PreparedQuizRunnerSession(
    val quizzes: List<Quiz>,
    val supportedQuizSource: List<Quiz>,
    val practiceRestore: PreparedPracticeSessionRestore? = null
)

internal data class PreparedPracticeSessionRestore(
    val sessionId: Int,
    val sessionStartedAt: Long,
    val currentIndex: Int,
    val currentSelection: Set<Int>,
    val answerVisible: Boolean,
    val practiceAnswers: Map<Int, Set<Int>>,
    val practiceAnswerResults: Map<Int, Boolean>,
    val recordedPracticeQuizIds: Set<Int>,
    val timerStartedAt: Long? = null
)

internal fun prepareQuizRunnerSession(
    source: List<Quiz>,
    selectedIds: List<Int>,
    restoredOrderIds: List<Int>,
    mode: QuizStudyMode,
    practiceSession: PracticeSession? = null,
    existingTimerStartedAt: Long = 0L,
    nowMillis: Long = System.currentTimeMillis()
): PreparedQuizRunnerSession {
    val supported = source.filter { it.isSupportedStudyType() }
    val byId = supported.associateBy { it.id }
    val restoredOrder = restoredOrderIds.takeIf { it.isNotEmpty() }

    if (mode == QuizStudyMode.REVIEW) {
        val orderIds = restoredOrder ?: selectedIds
        return PreparedQuizRunnerSession(
            quizzes = orderIds.mapNotNull { byId[it] },
            supportedQuizSource = supported
        )
    }

    if (mode.isPracticeSessionMode()) {
        restoredOrder?.let { orderIds ->
            return PreparedQuizRunnerSession(
                quizzes = orderIds.mapNotNull { byId[it] },
                supportedQuizSource = supported
            )
        }

        val storedOrder = practiceSession?.quizOrder?.toIntList().orEmpty()
        val quizzes = if (storedOrder.isNotEmpty()) {
            storedOrder.mapNotNull { byId[it] }.takeIf { it.isNotEmpty() }
                ?: buildFreshPracticeOrder(supported, mode)
        } else {
            buildFreshPracticeOrder(supported, mode)
        }
        return PreparedQuizRunnerSession(
            quizzes = quizzes,
            supportedQuizSource = supported,
            practiceRestore = buildPracticeRestore(
                session = practiceSession,
                existingTimerStartedAt = existingTimerStartedAt,
                nowMillis = nowMillis
            )
        )
    }

    val quizzes = if (selectedIds.isNotEmpty()) {
        selectedIds.mapNotNull { byId[it] }
    } else {
        supported
    }
    return PreparedQuizRunnerSession(
        quizzes = quizzes,
        supportedQuizSource = supported
    )
}

private fun buildPracticeRestore(
    session: PracticeSession?,
    existingTimerStartedAt: Long,
    nowMillis: Long
): PreparedPracticeSessionRestore {
    val timerStartedAt = session
        ?.takeIf { existingTimerStartedAt <= 0L }
        ?.let { buildPracticeTimerStartedAt(it, nowMillis) }
    val sessionStartedAt = timerStartedAt
        ?: session?.startedAt
        ?: nowMillis

    return PreparedPracticeSessionRestore(
        sessionId = session?.id ?: 0,
        sessionStartedAt = sessionStartedAt,
        currentIndex = session?.currentIndex ?: 0,
        currentSelection = session?.currentSelection?.toIntSet().orEmpty(),
        answerVisible = session?.answerVisible ?: false,
        practiceAnswers = session?.practiceAnswers?.decodeAnswerMapText().orEmpty(),
        practiceAnswerResults = session?.practiceResults?.decodeResultMapText().orEmpty(),
        recordedPracticeQuizIds = session?.recordedQuizIds?.toIntSet().orEmpty(),
        timerStartedAt = timerStartedAt
    )
}

private fun buildPracticeTimerStartedAt(
    session: PracticeSession,
    nowMillis: Long
): Long {
    val savedElapsed = (session.updatedAt - session.startedAt).coerceAtLeast(0L)
    return nowMillis - savedElapsed
}

private fun buildFreshPracticeOrder(
    source: List<Quiz>,
    mode: QuizStudyMode
): List<Quiz> {
    return if (mode == QuizStudyMode.RANDOM_PRACTICE) source.shuffled() else source
}

private fun QuizStudyMode.isPracticeSessionMode(): Boolean {
    return this == QuizStudyMode.ORDERED_PRACTICE || this == QuizStudyMode.RANDOM_PRACTICE
}

internal fun Collection<Int>.encodeIntSetText(): String {
    return sorted().joinToString(",")
}

internal fun List<Int>.encodeIntListText(): String {
    return joinToString(",")
}

internal fun String.toIntList(): List<Int> {
    if (isBlank()) return emptyList()
    return split(",").mapNotNull { it.toIntOrNull() }
}

internal fun String.toIntSet(): Set<Int> {
    return toIntList().toSet()
}

internal fun Map<Int, Set<Int>>.encodeAnswerMapText(): String {
    return entries.joinToString("|") { (quizId, selected) ->
        "$quizId:${selected.sorted().joinToString(",")}"
    }
}

internal fun String.decodeAnswerMapText(): Map<Int, Set<Int>> {
    if (isBlank()) return emptyMap()
    return split("|").mapNotNull { item ->
        val parts = item.split(":", limit = 2)
        val quizId = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
        val selected = parts.getOrNull(1).orEmpty().toIntSet()
        quizId to selected
    }.toMap(LinkedHashMap())
}

internal fun Map<Int, Boolean>.encodeResultMapText(): String {
    return entries.joinToString("|") { (quizId, isCorrect) ->
        "$quizId:${if (isCorrect) 1 else 0}"
    }
}

internal fun String.decodeResultMapText(): Map<Int, Boolean> {
    if (isBlank()) return emptyMap()
    return split("|").mapNotNull { item ->
        val parts = item.split(":", limit = 2)
        val quizId = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
        val isCorrect = parts.getOrNull(1) == "1"
        quizId to isCorrect
    }.toMap(LinkedHashMap())
}
