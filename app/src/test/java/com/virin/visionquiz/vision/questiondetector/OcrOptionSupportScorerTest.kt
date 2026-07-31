package com.virin.visionquiz.vision.questiondetector

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrOptionSupportScorerTest {

    @Test
    fun twoDistinctShortExactMatchesProvideEvidence() {
        assertEquals(
            0.07,
            score(
                options = listOf("政府强制", "及时更新", "长期不变", "用户制定"),
                candidates = listOf("A 政府强制", "B 及时更新")
            ),
            0.0
        )
    }

    @Test
    fun oneShortMatchIsTooWeakToProvideEvidence() {
        assertEquals(
            0.0,
            score(
                options = listOf("12小时", "1小时", "1天", "3天"),
                candidates = listOf("C 1天")
            ),
            0.0
        )
    }

    @Test
    fun shortOptionsRequireExactText() {
        assertEquals(
            0.0,
            score(
                options = listOf("及时更新", "长期不变"),
                candidates = listOf("及时更改", "长期改变")
            ),
            0.0
        )
    }

    @Test
    fun shortOptionSupportCanBeDisabled() {
        assertEquals(
            0.0,
            score(
                options = listOf("政府强制", "及时更新"),
                candidates = listOf("政府强制", "及时更新"),
                allowShortOptions = false
            ),
            0.0
        )
    }

    @Test
    fun fullWidthOptionLabelsAreRemovedBeforeExactMatching() {
        assertEquals(
            0.07,
            score(
                options = listOf("政府强制", "及时更新"),
                candidates = listOf("Ａ．政府强制", "Ｂ：及时更新")
            ),
            0.0
        )
    }

    @Test
    fun consecutiveCompactOptionLabelsProvideEvidence() {
        assertEquals(
            0.07,
            score(
                options = listOf("政府强制", "及时更新"),
                candidates = listOf("A政府强制", "B及时更新")
            ),
            0.0
        )
    }

    @Test
    fun isolatedCompactCategoryTextIsNotTrustedAsAnOption() {
        assertEquals(
            0.0,
            score(
                options = listOf("类用户", "其他用户"),
                candidates = listOf("A类用户")
            ),
            0.0
        )
    }

    @Test
    fun oneCandidateCannotSupportTwoOptions() {
        assertEquals(
            0.0,
            score(
                options = listOf("1天", "1天"),
                candidates = listOf("1天")
            ),
            0.0
        )
    }

    @Test
    fun unlabeledOrSwappedShortOptionsDoNotProvideEvidence() {
        assertEquals(
            0.0,
            score(
                options = listOf("政府强制", "及时更新"),
                candidates = listOf("政府强制", "及时更新")
            ),
            0.0
        )
        assertEquals(
            0.0,
            score(
                options = listOf("政府强制", "及时更新"),
                candidates = listOf("A 及时更新", "B 政府强制")
            ),
            0.0
        )
    }

    @Test
    fun oneShortMatchDoesNotIncreaseOneLongMatch() {
        assertEquals(
            0.03,
            score(
                options = listOf("安全生产制度", "1天"),
                candidates = listOf("A 安全生产制度", "B 1天")
            ),
            0.0
        )
    }

    @Test
    fun oneLongOptionRetainsExistingSupportWeight() {
        assertEquals(
            0.03,
            score(
                options = listOf("安全生产制度", "设备维护管理"),
                candidates = listOf("A 安全生产制度")
            ),
            0.0
        )
    }

    private fun score(
        options: List<String>,
        candidates: List<String>,
        allowShortOptions: Boolean = true
    ): Double {
        return OcrOptionSupportScorer.score(
            options = options,
            nearbyTexts = candidates,
            minMatchScore = 0.90,
            allowShortOptions = allowShortOptions
        )
    }
}
