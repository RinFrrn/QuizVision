package com.virin.visionquiz.util

import org.junit.Assert.assertEquals
import org.junit.Test

class OptionCandidateAssignmentTest {
    @Test
    fun maximizesMatchCountBeforeLocalCandidatePreference() {
        val shared = candidate("shared", rank = 0)
        val alternative = candidate("alternative", rank = 1)
        val result = OptionCandidateAssignment.solve(
            candidatesByOption = listOf(
                listOf(shared, alternative),
                listOf(shared)
            ),
            comparator = compareBy(Candidate::rank),
            conflicts = { first, second -> first.key == second.key }
        )

        assertEquals(listOf(alternative, shared), result)
    }

    @Test
    fun keepsBestRankedAssignmentWhenMatchCountsAreEqual() {
        val result = OptionCandidateAssignment.solve(
            candidatesByOption = listOf(
                listOf(candidate("a0", 0), candidate("a1", 1)),
                listOf(candidate("b0", 0), candidate("b1", 1))
            ),
            comparator = compareBy(Candidate::rank),
            conflicts = { _, _ -> false }
        )

        assertEquals(listOf(candidate("a0", 0), candidate("b0", 0)), result)
    }

    private fun candidate(key: String, rank: Int) = Candidate(key, rank)

    private data class Candidate(val key: String, val rank: Int)
}
