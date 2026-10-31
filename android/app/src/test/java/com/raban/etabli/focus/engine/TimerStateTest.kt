package com.raban.etabli.focus.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class TimerStateTest {
    @Test fun lateReturnNeverAddsTimeToACompletedBlock() {
        val timer = TimerState(phase = TimerPhase.Running, totalSeconds = 1500,
            startedAtEpochMillis = 1000, endEpochMillis = 1501000)
        assertEquals(1500000, timer.activeMillis(36000000, completed = true))
        assertEquals(0, timer.remainingMillis(36000000))
    }

    @Test fun pausedCancellationRecordsOnlyWorkBeforePause() {
        val timer = TimerState(phase = TimerPhase.Paused, totalSeconds = 1500,
            startedAtEpochMillis = 1000, pausedRemainingMillis = 1200000)
        assertEquals(300000, timer.activeMillis(86400000, completed = false))
        assertEquals(1200, timer.remainingSeconds(86400000))
    }

    @Test fun resumeKeepsTheOriginalStartAndExcludesPausedTime() {
        val paused = TimerState(phase = TimerPhase.Paused, totalSeconds = 1500,
            startedAtEpochMillis = 1000, pausedRemainingMillis = 1200000)
        val resumed = paused.copy(phase = TimerPhase.Running, endEpochMillis = 4801000,
            pausedRemainingMillis = null)
        assertEquals(1000L, resumed.startedAtEpochMillis)
        assertEquals(600000, resumed.activeMillis(3901000, completed = false))
    }

    @Test fun invalidRemainingValuesCannotCreateNegativeWorkOrExceedTheBudget() {
        val timer = TimerState(phase = TimerPhase.Paused, totalSeconds = 60,
            pausedRemainingMillis = 900000)
        assertEquals(0, timer.activeMillis(0, completed = false))
        assertEquals(60000, timer.copy(pausedRemainingMillis = -100).activeMillis(0, completed = false))
        assertEquals(1, timer.copy(phase = TimerPhase.Running, endEpochMillis = 1).remainingSeconds(0))
    }
}
