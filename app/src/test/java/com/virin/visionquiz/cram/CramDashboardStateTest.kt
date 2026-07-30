package com.virin.visionquiz.cram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CramDashboardStateTest {

    @Test
    fun initialStateDefaultsToThreeDaysAndOneHourWithoutFakeAnalysisContent() {
        val state = buildInitialCramDashboardState(
            libraryId = 42,
            todayEpochDay = 1_000,
            storedExamDateEpochDay = null,
            storedDailyMinutes = null
        )

        assertEquals(1_003, state.examDateEpochDay)
        assertEquals(3, state.daysRemaining)
        assertEquals(60, state.dailyMinutes)
        assertTrue(state.content.todayQuizIds.isEmpty())
        assertTrue(state.content.priorityModules.isEmpty())
        assertTrue(state.content.mnemonics.isEmpty())
        assertTrue(state.content.selfTestQuizIds.isEmpty())
    }

    @Test
    fun expiredStoredDateFallsBackToFreshThreeDayPlan() {
        val state = buildInitialCramDashboardState(
            libraryId = 7,
            todayEpochDay = 2_000,
            storedExamDateEpochDay = 1_999,
            storedDailyMinutes = 95
        )

        assertEquals(2_003, state.examDateEpochDay)
        assertEquals(3, state.daysRemaining)
        assertEquals(90, state.dailyMinutes)
    }

    @Test
    fun countdownNeverBecomesNegative() {
        assertEquals(0, calculateCramDaysRemaining(100, 99))
        assertEquals("今天考试", cramCountdownLabel(-2))
        assertEquals("明天考试", cramCountdownLabel(1))
        assertEquals("距考试 3 天", cramCountdownLabel(3))
    }

    @Test
    fun dailyMinutesAreRoundedAndClampedToSupportedSteps() {
        assertEquals(15, normalizeCramDailyMinutes(-1))
        assertEquals(60, normalizeCramDailyMinutes(64))
        assertEquals(75, normalizeCramDailyMinutes(68))
        assertEquals(240, normalizeCramDailyMinutes(999))
    }

    @Test
    fun remainingDaysSelectTheMatchingCramPlanStage() {
        assertEquals(1, planDayForDaysRemaining(5))
        assertEquals(1, planDayForDaysRemaining(3))
        assertEquals(2, planDayForDaysRemaining(2))
        assertEquals(3, planDayForDaysRemaining(1))
        assertEquals(3, planDayForDaysRemaining(0))
    }

    @Test
    fun completedCountOnlyUsesTheExactCurrentCramQueue() {
        assertEquals(
            2,
            countCompletedForExactCramQueue(
                sessionQuizOrder = "3,1,2",
                recordedQuizIds = "1,3",
                currentQuizIds = listOf(3, 1, 2)
            )
        )
        assertEquals(
            0,
            countCompletedForExactCramQueue(
                sessionQuizOrder = "1,2,3",
                recordedQuizIds = "1,3",
                currentQuizIds = listOf(3, 1, 2)
            )
        )
    }

    @Test
    fun localFingerprintIncludesPlanInputs() {
        val base = buildLocalAnalysisFingerprint("quiz", 60, 30)
        assertNotEquals(base, buildLocalAnalysisFingerprint("quiz", 90, 30))
        assertNotEquals(base, buildLocalAnalysisFingerprint("quiz", 60, 20))
    }

    @Test
    fun finalReportFingerprintIsBoundToTheCurrentLocalPlan() {
        val fingerprint = buildFinalReportCacheFingerprint("local-a", "prompt")
        assertTrue(isFinalReportBoundToLocalFingerprint(fingerprint, "local-a"))
        assertTrue(!isFinalReportBoundToLocalFingerprint(fingerprint, "local-b"))
    }
}
