package com.virin.visionquiz.cram

import com.virin.visionquiz.dao.Quiz
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CramQuizReferenceParserTest {

    @Test
    fun parsesEveryIdInLocalQuestionNumberList() {
        val text = "支持 2 次；题号 28853、28884，其他数字 2025年。"

        val links = CramQuizReferenceParser.find(text)

        assertEquals(
            listOf(
                CramQuizReferenceTarget(CramQuizReferenceKind.DATABASE_ID, 28853),
                CramQuizReferenceTarget(CramQuizReferenceKind.DATABASE_ID, 28884)
            ),
            links.map(CramQuizTextLink::target)
        )
        assertEquals(listOf("28853", "28884"), links.map { text.substring(it.start, it.endExclusive) })
    }

    @Test
    fun distinguishesAiSourceRowsFromDatabaseIds() {
        val text = "母规则 [题#88、题#89]；源题号 90、91；补充 [ID:12、ID:13]；旧总稿 [14, 15]。"

        val links = CramQuizReferenceParser.find(text)

        assertEquals(
            listOf(
                CramQuizReferenceTarget(CramQuizReferenceKind.SOURCE_ROW, 88),
                CramQuizReferenceTarget(CramQuizReferenceKind.SOURCE_ROW, 89),
                CramQuizReferenceTarget(CramQuizReferenceKind.SOURCE_ROW, 90),
                CramQuizReferenceTarget(CramQuizReferenceKind.SOURCE_ROW, 91),
                CramQuizReferenceTarget(CramQuizReferenceKind.DATABASE_ID, 12),
                CramQuizReferenceTarget(CramQuizReferenceKind.DATABASE_ID, 13),
                CramQuizReferenceTarget(CramQuizReferenceKind.LEGACY_NUMBER, 14),
                CramQuizReferenceTarget(CramQuizReferenceKind.LEGACY_NUMBER, 15)
            ),
            links.map(CramQuizTextLink::target)
        )
    }

    @Test
    fun ignoresNumbersWithoutAnExplicitQuestionReference() {
        val text = "2025年、30天、100元、Top 3，用户ID:12，答案支持 2 次。"

        assertTrue(CramQuizReferenceParser.find(text).isEmpty())
    }

    @Test
    fun referenceIndexUsesTheCorrectNamespace() {
        val databaseIdTwelve = quiz(id = 12, sourceRow = 99)
        val sourceRowTwelve = quiz(id = 99, sourceRow = 12)
        val index = CramQuizReferenceIndex(listOf(databaseIdTwelve, sourceRowTwelve))

        assertEquals(
            0,
            index.selection(
                CramQuizReferenceTarget(CramQuizReferenceKind.DATABASE_ID, 12)
            )?.initialIndex
        )
        assertEquals(
            1,
            index.selection(
                CramQuizReferenceTarget(CramQuizReferenceKind.SOURCE_ROW, 12)
            )?.initialIndex
        )
    }

    @Test
    fun duplicatedSourceRowIsNotResolvedToAnArbitraryQuiz() {
        val index = CramQuizReferenceIndex(
            listOf(
                quiz(id = 12, sourceRow = 88),
                quiz(id = 13, sourceRow = 88)
            )
        )
        val duplicatedSourceRow = CramQuizReferenceTarget(
            CramQuizReferenceKind.SOURCE_ROW,
            88
        )

        assertTrue(!index.contains(duplicatedSourceRow))
        assertEquals(null, index.selection(duplicatedSourceRow))
    }

    @Test
    fun legacyNumberDoesNotChooseBetweenDifferentDatabaseAndSourceRowMatches() {
        val index = CramQuizReferenceIndex(
            listOf(
                quiz(id = 12, sourceRow = 99),
                quiz(id = 99, sourceRow = 12)
            )
        )
        val legacyNumber = CramQuizReferenceTarget(
            CramQuizReferenceKind.LEGACY_NUMBER,
            12
        )

        assertTrue(!index.contains(legacyNumber))
        assertEquals(null, index.selection(legacyNumber))
    }

    @Test
    fun legacyNumberResolvesWhenOnlyOneNamespaceMatches() {
        val sourceOnlyIndex = CramQuizReferenceIndex(
            listOf(quiz(id = 99, sourceRow = 12))
        )
        val legacyNumber = CramQuizReferenceTarget(
            CramQuizReferenceKind.LEGACY_NUMBER,
            12
        )

        assertEquals(99, sourceOnlyIndex.selection(legacyNumber)?.quizzes?.first()?.id)
    }

    @Test
    fun moduleSelectionKeepsRequestedOrderAndFullBankForSimilarQuestions() {
        val allQuizzes = listOf(
            quiz(id = 12, sourceRow = 1),
            quiz(id = 13, sourceRow = 2),
            quiz(id = 14, sourceRow = 3)
        )
        val selection = CramQuizReferenceIndex(allQuizzes)
            .selectionForDatabaseIds(listOf(14, 999, 12, 14))

        assertEquals(listOf(14, 12), selection?.quizzes?.map(Quiz::id))
        assertEquals(0, selection?.initialIndex)
        assertEquals(allQuizzes, selection?.allQuizzes)
    }

    private fun quiz(id: Int, sourceRow: Int): Quiz {
        return Quiz(
            id = id,
            prompt = "题目$id",
            options = listOf("正确", "错误"),
            answer = setOf(0),
            isMultipleChoice = false,
            questionType = "判断",
            libraryId = 1,
            sourceRow = sourceRow
        )
    }
}
