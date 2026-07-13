package com.virin.visionquiz.util

/**
 * Assigns at most one candidate to every option while preventing candidates from sharing content.
 * The solver first maximizes the number of matched options, then minimizes the sum of each
 * candidate's rank in its option-specific preference list.
 */
internal object OptionCandidateAssignment {
    fun <T> solve(
        candidatesByOption: List<List<T>>,
        comparator: Comparator<T>,
        conflicts: (T, T) -> Boolean,
        maxCandidatesPerOption: Int = DEFAULT_MAX_CANDIDATES_PER_OPTION
    ): List<T?> {
        if (candidatesByOption.isEmpty()) return emptyList()

        val rankedCandidates = candidatesByOption.map { candidates ->
            candidates.sortedWith(comparator).take(maxCandidatesPerOption.coerceAtLeast(1))
        }
        val optionOrder = rankedCandidates.indices.sortedWith(
            compareBy<Int> { rankedCandidates[it].size }.thenBy { it }
        )
        val current = MutableList<T?>(rankedCandidates.size) { null }
        val selected = mutableListOf<T>()
        var best = current.toList()
        var bestMatchedCount = -1
        var bestPenalty = Int.MAX_VALUE

        fun search(orderIndex: Int, matchedCount: Int, penalty: Int) {
            val remaining = optionOrder.size - orderIndex
            if (matchedCount + remaining < bestMatchedCount) return
            if (matchedCount + remaining == bestMatchedCount && penalty >= bestPenalty) return
            if (bestMatchedCount == rankedCandidates.size && bestPenalty == 0) return

            if (orderIndex == optionOrder.size) {
                if (matchedCount > bestMatchedCount ||
                    matchedCount == bestMatchedCount && penalty < bestPenalty
                ) {
                    best = current.toList()
                    bestMatchedCount = matchedCount
                    bestPenalty = penalty
                }
                return
            }

            val optionIndex = optionOrder[orderIndex]
            rankedCandidates[optionIndex].forEachIndexed { rank, candidate ->
                if (selected.none { conflicts(it, candidate) }) {
                    current[optionIndex] = candidate
                    selected += candidate
                    search(orderIndex + 1, matchedCount + 1, penalty + rank)
                    selected.removeAt(selected.lastIndex)
                    current[optionIndex] = null
                }
            }
            search(orderIndex + 1, matchedCount, penalty)
        }

        search(orderIndex = 0, matchedCount = 0, penalty = 0)
        return best
    }

    private const val DEFAULT_MAX_CANDIDATES_PER_OPTION = 16
}
