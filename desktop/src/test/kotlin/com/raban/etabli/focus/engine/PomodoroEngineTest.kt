// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.focus.engine

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.raban.etabli.focus.data.AppDatabase
import com.raban.etabli.focus.data.SessionEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val T0 = 1_000_000L
private const val MINUTE = 60_000L

/**
 * One app installation: a data folder that outlives individual "process"
 * lifetimes. [launch] starts the engine as the app does; [quit] tears it down
 * as quitting or a crash would, so tests can restart against the same files.
 */
private class Install(val directory: File) {
    var now = T0
    var failNextWrite = false
    var saveSession: (suspend (SessionEntity) -> Unit)? = null
    val history = mutableListOf<SessionEntity>()
    val scheduled = mutableListOf<Long>()
    var cancelledAlarms = 0
    val finished = mutableListOf<SessionEntity>()
    private var scope: CoroutineScope? = null

    fun launch(): PomodoroEngine {
        val processScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = processScope
        val store = PreferenceDataStoreFactory.create(scope = processScope) {
            directory.resolve("timer_state.preferences_pb")
        }
        return PomodoroEngine(
            store = store,
            saveSession = { row ->
                if (failNextWrite) { failNextWrite = false; throw java.io.IOException("fixture disk failure") }
                saveSession?.invoke(row)
                history.removeAll { it.id == row.id }
                history.add(row)
            },
            schedule = { at, _ -> scheduled += at },
            cancelAlarm = { cancelledAlarms++ },
            scope = processScope,
            nowMillis = { now },
            onFinished = { finished += it },
        )
    }

    suspend fun quit() {
        val processScope = scope ?: return
        processScope.cancel()
        processScope.coroutineContext[Job]!!.join()
        scope = null
    }
}

private fun install(block: suspend Install.() -> Unit) = runBlocking {
    val directory = Files.createTempDirectory("focus-engine").toFile()
    val install = Install(directory)
    try {
        install.block()
    } finally {
        install.quit()
        directory.deleteRecursively()
    }
}

private suspend fun PomodoroEngine.awaitState(predicate: (TimerState) -> Boolean): TimerState =
    withTimeout(5_000) { state.first(predicate) }

class PomodoroEngineTest {

    // MARK: - Ported from the Android PomodoroEngineTest

    @Test fun rapidStartAndCompletionCannotReplaceOrDoubleCount() = install {
        val engine = launch()
        (1..25).map { engine.start("research", TimerKind.Focus, 1) }.joinAll()
        now = 1_061_000
        (1..25).map { engine.tick() }.joinAll()
        assertEquals(1, history.size)
        assertEquals(1_000_000L, history.single().startedAtEpoch)
        assertEquals(1_060_000L, history.single().actualEndEpoch)
        assertEquals(60_000L, history.single().activeMillis)
        assertTrue(history.single().completed)
        engine.start("review", TimerKind.Focus, 1).join()
        now = 1_121_000
        engine.tick().join()
        assertEquals(2, history.size)
        assertEquals(2, finished.size)
    }

    @Test fun failedHistoryWriteRetainsSessionForRetry() = install {
        val engine = launch()
        engine.start("research", TimerKind.Focus, 1).join()
        now = 1_070_000
        failNextWrite = true
        engine.tick().join()
        assertNotNull(engine.error.value)
        assertTrue(history.isEmpty())
        assertTrue(finished.isEmpty())
        engine.tick().join()
        assertNull(engine.error.value)
        assertEquals(1, history.size)
        assertEquals(1_060_000L, history.single().actualEndEpoch)
        assertEquals(1, finished.size)
    }

    @Test fun pauseResumeCancelPreservesOriginalStartAndOnlyActiveTime() = install {
        val engine = launch()
        engine.start("research", TimerKind.Focus, 2).join()
        now = 1_030_000
        engine.pause().join()
        now = 2_000_000
        engine.resume().join()
        now = 2_020_000
        engine.cancel().join()
        assertEquals(50_000L, history.single().activeMillis)
        assertEquals(1_000_000L, history.single().startedAtEpoch)
        assertFalse(history.single().completed)
        assertTrue(finished.isEmpty())
    }

    @Test fun calendarBucketsHandleBothDaylightSavingTransitionsAndExcludePriorDates() {
        val zone = ZoneId.of("Europe/Berlin")
        for (start in listOf(LocalDate.of(2026, 3, 28), LocalDate.of(2026, 10, 24))) {
            val times = (0L..2L).map { start.plusDays(it).atStartOfDay(zone).toInstant().toEpochMilli() } +
                start.minusDays(1).atTime(23, 59).atZone(zone).toInstant().toEpochMilli()
            assertEquals(listOf(1, 1, 1), dailySessionCounts(times, start, 3, zone))
        }
    }

    // MARK: - Pause budget

    @Test fun pauseKeepsTheRemainingBudgetAndResumeRearmsTheAlarm() = install {
        val engine = launch()
        engine.start("research", TimerKind.Focus, 25).join()
        assertEquals(listOf(T0 + 25 * MINUTE), scheduled)
        now = T0 + 5 * MINUTE
        engine.pause().join()
        val paused = engine.awaitState { it.phase == TimerPhase.Paused }
        assertEquals(20 * MINUTE, paused.pausedRemainingMillis)
        assertNull(paused.endEpochMillis)
        assertEquals(1, cancelledAlarms)
        // Time spent paused does not consume the budget.
        now = T0 + 95 * MINUTE
        assertEquals(20 * 60L, paused.remainingSeconds(now))
        engine.resume().join()
        assertEquals(T0 + 115 * MINUTE, scheduled.last())
        now = T0 + 115 * MINUTE
        engine.tick().join()
        val row = history.single()
        assertTrue(row.completed)
        assertEquals(25 * MINUTE, row.activeMillis)
        assertEquals(T0, row.startedAtEpoch)
        assertEquals(T0 + 115 * MINUTE, row.actualEndEpoch)
    }

    @Test fun pauseAtOrAfterTheDeadlineCompletesInsteadOfPausing() = install {
        val engine = launch()
        engine.start("research", TimerKind.Focus, 1).join()
        now = T0 + MINUTE + 1
        engine.pause().join()
        assertTrue(history.single().completed)
        assertEquals(T0 + MINUTE, history.single().actualEndEpoch)
        assertEquals(TimerPhase.Idle, engine.awaitState { it.phase == TimerPhase.Idle }.phase)
    }

    @Test fun startIsIgnoredWhileABlockIsActiveAndCommandsAreNoOpsWhenIdle() = install {
        val engine = launch()
        engine.pause().join()
        engine.resume().join()
        engine.cancel().join()
        engine.tick().join()
        assertTrue(history.isEmpty())
        assertTrue(scheduled.isEmpty())
        engine.start("research", TimerKind.Focus, 25).join()
        engine.start("review", TimerKind.Focus, 50).join()
        val running = engine.awaitState { it.phase == TimerPhase.Running }
        assertEquals("research", running.categoryID)
        assertEquals(25 * 60L, running.totalSeconds)
        assertEquals(1, scheduled.size)
    }

    // MARK: - Restart persistence and start-up reconciliation

    @Test fun pausedBudgetSurvivesARestart() = install {
        val first = launch()
        first.start("research", TimerKind.Focus, 25).join()
        now = T0 + 5 * MINUTE
        first.pause().join()
        quit()

        now = T0 + 3 * 60 * MINUTE
        val second = launch()
        second.restoreAlarm().join()
        val restored = second.awaitState { it.phase == TimerPhase.Paused }
        assertEquals("research", restored.categoryID)
        assertEquals(20 * MINUTE, restored.pausedRemainingMillis)
        assertEquals(T0, restored.startedAtEpochMillis)
        assertTrue(history.isEmpty(), "a paused block is not finished by a restart")

        second.resume().join()
        now += 20 * MINUTE
        second.tick().join()
        assertEquals(25 * MINUTE, history.single().activeMillis)
        assertEquals(T0, history.single().startedAtEpoch)
    }

    @Test fun runningDeadlineSurvivesARestartAndReArmsTheAlarm() = install {
        val first = launch()
        first.start("research", TimerKind.Focus, 25).join()
        quit()

        now = T0 + 10 * MINUTE
        scheduled.clear()
        val second = launch()
        second.restoreAlarm().join()
        val restored = second.awaitState { it.phase == TimerPhase.Running }
        assertEquals(T0 + 25 * MINUTE, restored.endEpochMillis)
        assertEquals(15 * 60L, restored.remainingSeconds(now))
        assertEquals(listOf(T0 + 25 * MINUTE), scheduled)
        assertTrue(history.isEmpty())
    }

    @Test fun blockThatEndedWhileClosedIsReconciledExactlyOnceOnStartUp() = install {
        val first = launch()
        first.start("research", TimerKind.Focus, 50).join()
        quit()

        // The app stays closed well past the deadline.
        now = T0 + 6 * 60 * MINUTE
        val second = launch()
        second.restoreAlarm().join()
        val row = history.single()
        assertTrue(row.completed)
        assertEquals(T0, row.startedAtEpoch)
        assertEquals(T0 + 50 * MINUTE, row.actualEndEpoch, "completion is dated at the deadline, not the late start-up")
        assertEquals(50 * MINUTE, row.activeMillis)
        assertEquals(listOf(row), finished)
        val idle = second.awaitState { it.phase == TimerPhase.Idle }
        assertEquals(1, idle.blocksCompletedInSet)
        quit()

        val third = launch()
        third.restoreAlarm().join()
        third.tick().join()
        assertEquals(1, history.size)
        assertEquals(1, finished.size)
        assertEquals(1, third.awaitState { it.phase == TimerPhase.Idle }.blocksCompletedInSet)
    }

    @Test fun reconciliationWritesOneRowToTheSqliteHistory() = install {
        val db = AppDatabase.open(directory.toPath().resolve("etabli_focus.db"))
        try {
            saveSession = { db.sessionDao().insert(it) }
            val first = launch()
            first.start("research", TimerKind.Focus, 25).join()
            quit()
            now = T0 + 2 * 60 * MINUTE
            repeat(2) {
                val again = launch()
                again.restoreAlarm().join()
                again.tick().join()
                quit()
            }
            val stored = db.sessionDao().observeCompletedFocusSince(0).first()
            assertEquals(1, stored.size)
            assertEquals(25 * MINUTE, stored.single().activeMillis)
            assertEquals(T0 + 25 * MINUTE, stored.single().actualEndEpoch)
        } finally {
            db.close()
        }
    }
    @Test fun taskAssociationSurvivesRestartAndOnlyFocusWritesIt() = install {
        val link = "{\"id\":\"task-one\"}"
        var engine = launch()
        engine.start("category", TimerKind.Focus, 1, link).join()
        quit()
        engine = launch()
        now += MINUTE
        engine.restoreAlarm().join()
        assertEquals("flowsavvy:v1:$link", history.single().note)
        engine.start("category", TimerKind.ShortBreak, 1, link).join()
        now += MINUTE
        engine.tick().join()
        assertNull(history.last().note)
    }

}
