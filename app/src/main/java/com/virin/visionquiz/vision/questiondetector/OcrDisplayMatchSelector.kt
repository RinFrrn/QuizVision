package com.virin.visionquiz.vision.questiondetector

import com.virin.visionquiz.util.QuizGraphicItem
import kotlin.math.sqrt

/** Keeps only the strongest quiz match for each visual question position. */
internal object OcrDisplayMatchSelector {

    data class Candidate<T>(
        val value: T,
        val identity: String,
        val bounds: OcrOptionLocator.Bounds,
        val score: Double,
        val locatedAnswerCount: Int = 0,
        val expectedAnswerCount: Int = 0,
        val isAnswerPartiallyMatched: Boolean = false
    )

    fun <T> select(candidates: List<Candidate<T>>): List<Candidate<T>> {
        if (candidates.size < 2) {
            return candidates
        }

        val remaining = candidates.toMutableList()
        val selected = mutableListOf<Candidate<T>>()
        while (remaining.isNotEmpty()) {
            val cluster = mutableListOf(remaining.removeAt(0))
            var index = 0
            while (index < cluster.size) {
                val anchor = cluster[index++]
                val iterator = remaining.iterator()
                while (iterator.hasNext()) {
                    val candidate = iterator.next()
                    if (conflicts(anchor, candidate)) {
                        cluster += candidate
                        iterator.remove()
                    }
                }
            }
            selected += selectBest(cluster)
        }
        return selected.sortedWith(
            compareByDescending<Candidate<T>> { it.score }
                .thenBy { it.bounds.top }
                .thenBy { it.bounds.left }
        )
    }

    fun selectQuizGraphicItems(matches: List<QuizGraphicItem>): List<QuizGraphicItem> {
        return select(
            matches.map { match ->
                Candidate(
                    value = match,
                    identity = match.question.id.takeIf { it != 0 }?.toString()
                        ?: match.question.prompt,
                    bounds = OcrOptionLocator.Bounds(
                        match.rect.left,
                        match.rect.top,
                        match.rect.right,
                        match.rect.bottom
                    ),
                    score = match.distance,
                    locatedAnswerCount = match.answerRects.size,
                    expectedAnswerCount = match.question.answer.size,
                    isAnswerPartiallyMatched = match.isAnswerPartiallyMatched
                )
            }
        ).map(Candidate<QuizGraphicItem>::value)
    }

    private fun <T> selectBest(cluster: List<Candidate<T>>): Candidate<T> {
        val maxArea = cluster.maxOf { it.bounds.area }.coerceAtLeast(1)
        return cluster.maxWithOrNull(
            compareBy<Candidate<T>> { candidate ->
                candidate.score + answerEvidenceBonus(candidate) +
                    SPATIAL_COVERAGE_BONUS * sqrt(
                        candidate.bounds.area.toDouble() / maxArea
                    )
            }
                .thenBy { it.score }
                .thenBy { it.bounds.area }
        ) ?: cluster.first()
    }

    private fun <T> answerEvidenceBonus(candidate: Candidate<T>): Double {
        if (candidate.expectedAnswerCount <= 0 || candidate.locatedAnswerCount <= 0) {
            return 0.0
        }
        return if (
            !candidate.isAnswerPartiallyMatched &&
            candidate.locatedAnswerCount >= candidate.expectedAnswerCount
        ) {
            COMPLETE_ANSWER_BONUS
        } else {
            PARTIAL_ANSWER_BONUS
        }
    }

    private fun <T> conflicts(first: Candidate<T>, second: Candidate<T>): Boolean {
        return first.identity == second.identity || sameVisualPosition(first.bounds, second.bounds)
    }

    internal fun sameVisualPosition(first: OcrOptionLocator.Bounds, second: OcrOptionLocator.Bounds): Boolean {
        val intersectionWidth =
            (minOf(first.right, second.right) - maxOf(first.left, second.left)).coerceAtLeast(0)
        val intersectionHeight =
            (minOf(first.bottom, second.bottom) - maxOf(first.top, second.top)).coerceAtLeast(0)
        if (intersectionWidth == 0 || intersectionHeight == 0) {
            return false
        }

        val intersectionArea = intersectionWidth.toLong() * intersectionHeight
        val minimumArea = minOf(first.area, second.area).coerceAtLeast(1)
        if (intersectionArea.toDouble() / minimumArea >= MIN_CONTAINED_OVERLAP_RATIO) {
            return true
        }

        val horizontalOverlap = intersectionWidth.toDouble() /
            minOf(first.width, second.width).coerceAtLeast(1)
        val verticalOverlap = intersectionHeight.toDouble() /
            minOf(first.height, second.height).coerceAtLeast(1)
        return horizontalOverlap >= MIN_HORIZONTAL_OVERLAP_RATIO &&
            verticalOverlap >= MIN_VERTICAL_OVERLAP_RATIO
    }

    private const val SPATIAL_COVERAGE_BONUS = 0.04
    private const val COMPLETE_ANSWER_BONUS = 0.04
    private const val PARTIAL_ANSWER_BONUS = 0.01
    private const val MIN_CONTAINED_OVERLAP_RATIO = 0.62
    private const val MIN_HORIZONTAL_OVERLAP_RATIO = 0.70
    private const val MIN_VERTICAL_OVERLAP_RATIO = 0.50
}
