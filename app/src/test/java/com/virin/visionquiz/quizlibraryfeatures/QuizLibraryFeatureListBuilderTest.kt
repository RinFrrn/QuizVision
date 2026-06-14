package com.virin.visionquiz.quizlibraryfeatures

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuizLibraryFeatureListBuilderTest {
    @Test
    fun buildGroupedFeatureItemsKeepsSectionAndFeatureOrder() {
        val items = buildGroupedFeatureItems(buildLibraryStudyFeatures())

        val sectionTitles = items.filterIsInstance<QuizLibraryFeatureListItem.SectionHeader>()
            .map { it.title }
        val reviewItem = items.filterIsInstance<QuizLibraryFeatureListItem.FeatureItem>()
            .first { it.feature.action == QuizLibraryFeaturesFragment.FeatureAction.REVIEW }
        val quizListItem = items.filterIsInstance<QuizLibraryFeatureListItem.FeatureItem>()
            .first { it.feature.action == QuizLibraryFeaturesFragment.FeatureAction.QUIZ_LIST }
        val itemOrder = items.mapNotNull { item ->
            when (item) {
                is QuizLibraryFeatureListItem.FeatureItem -> item.feature.action.name
                QuizLibraryFeatureListItem.Stats -> "STATS"
                is QuizLibraryFeatureListItem.SectionHeader -> null
            }
        }

        assertEquals(listOf("Today", "自主练习", "题库概览", "复盘巩固", "题库工具"), sectionTitles)
        assertEquals("开始学习", reviewItem.feature.title)
        assertEquals("浏览题目", quizListItem.feature.title)
        assertEquals(
            listOf(
                "REVIEW",
                "ORDERED_PRACTICE",
                "RANDOM_PRACTICE",
                "EXAM",
                "STATS",
                "QUIZ_LIST",
                "FAVORITES",
                "WRONG",
                "HISTORY",
                "EXAM_HISTORY",
                "EXPORT",
                "SIMILAR_ANALYSIS"
            ),
            itemOrder
        )
    }

    @Test
    fun spanRulesMakeStudyActionsThreeAcrossAndOthersTwoAcross() {
        val items = buildGroupedFeatureItems(buildLibraryStudyFeatures())
        val reviewItem = items.filterIsInstance<QuizLibraryFeatureListItem.FeatureItem>()
            .first { it.feature.action == QuizLibraryFeaturesFragment.FeatureAction.REVIEW }
        val orderedItem = items.filterIsInstance<QuizLibraryFeatureListItem.FeatureItem>()
            .first { it.feature.action == QuizLibraryFeaturesFragment.FeatureAction.ORDERED_PRACTICE }
        val randomItem = items.filterIsInstance<QuizLibraryFeatureListItem.FeatureItem>()
            .first { it.feature.action == QuizLibraryFeaturesFragment.FeatureAction.RANDOM_PRACTICE }
        val examItem = items.filterIsInstance<QuizLibraryFeatureListItem.FeatureItem>()
            .first { it.feature.action == QuizLibraryFeaturesFragment.FeatureAction.EXAM }
        val quizListItem = items.filterIsInstance<QuizLibraryFeatureListItem.FeatureItem>()
            .first { it.feature.action == QuizLibraryFeaturesFragment.FeatureAction.QUIZ_LIST }
        val favoriteItem = items.filterIsInstance<QuizLibraryFeatureListItem.FeatureItem>()
            .first { it.feature.action == QuizLibraryFeaturesFragment.FeatureAction.FAVORITES }
        val exportItem = items.filterIsInstance<QuizLibraryFeatureListItem.FeatureItem>()
            .first { it.feature.action == QuizLibraryFeaturesFragment.FeatureAction.EXPORT }
        val similarAnalysisItem = items.filterIsInstance<QuizLibraryFeatureListItem.FeatureItem>()
            .first { it.feature.action == QuizLibraryFeaturesFragment.FeatureAction.SIMILAR_ANALYSIS }
        val statsItem = items.first { it is QuizLibraryFeatureListItem.Stats }

        assertTrue(isFullSpanFeatureItem(items.first()))
        assertTrue(isFullSpanFeatureItem(reviewItem))
        assertTrue(isFullSpanFeatureItem(statsItem))
        assertTrue(isFullSpanFeatureItem(quizListItem))
        assertFalse(isFullSpanFeatureItem(orderedItem))
        assertFalse(isFullSpanFeatureItem(exportItem))
        assertFalse(isFullSpanFeatureItem(similarAnalysisItem))
        assertTrue(orderedItem.compact)
        assertTrue(randomItem.compact)
        assertTrue(examItem.compact)
        assertFalse(favoriteItem.compact)

        assertEquals(6, quizLibraryFeatureSpanSize(reviewItem, 6))
        assertEquals(2, quizLibraryFeatureSpanSize(orderedItem, 6))
        assertEquals(2, quizLibraryFeatureSpanSize(randomItem, 6))
        assertEquals(2, quizLibraryFeatureSpanSize(examItem, 6))
        assertEquals(6, quizLibraryFeatureSpanSize(statsItem, 6))
        assertEquals(6, quizLibraryFeatureSpanSize(quizListItem, 6))
        assertEquals(3, quizLibraryFeatureSpanSize(favoriteItem, 6))
        assertEquals(3, quizLibraryFeatureSpanSize(exportItem, 6))
        assertEquals(3, quizLibraryFeatureSpanSize(similarAnalysisItem, 6))
    }
}
