package com.raban.etabli.focus.engine

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.raban.etabli.focus.data.SessionEntity
import kotlinx.coroutines.*
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class PomodoroEngineTest {
    private fun fixture(block: suspend (PomodoroEngine, MutableList<SessionEntity>, (Long) -> Unit, () -> Unit) -> Unit) = runBlocking {
        val directory = Files.createTempDirectory("focus-engine").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var now = 1_000_000L
        var fail = false
        val history = mutableListOf<SessionEntity>()
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) { directory.resolve("timer.preferences_pb") }
            val engine = PomodoroEngine(store, { row ->
                if (fail) { fail = false; throw java.io.IOException("fixture disk failure") }
                history.removeAll { it.id == row.id }
                history.add(row)
            }, { _, _ -> }, {}, scope, { now })
            block(engine, history, { now = it }, { fail = true })
        } finally {
            scope.cancel()
            scope.coroutineContext[Job]!!.join()
            directory.deleteRecursively()
        }
    }

    @Test fun rapidStartAndCompletionCannotReplaceOrDoubleCount() = fixture { engine, history, clock, _ ->
        (1..25).map { engine.start("research", TimerKind.Focus, 1) }.joinAll()
        clock(1_061_000)
        (1..25).map { engine.tick() }.joinAll()
        assertEquals(1, history.size)
        assertEquals(1_000_000L, history.single().startedAtEpoch)
        assertEquals(1_060_000L, history.single().actualEndEpoch)
        assertEquals(60_000L, history.single().activeMillis)
        assertTrue(history.single().completed)
        engine.start("review", TimerKind.Focus, 1).join()
        clock(1_121_000)
        engine.tick().join()
        assertEquals(2, history.size)
    }

    @Test fun failedHistoryWriteRetainsSessionForRetry() = fixture { engine, history, clock, fail ->
        engine.start("research", TimerKind.Focus, 1).join()
        clock(1_070_000)
        fail()
        engine.tick().join()
        assertNotNull(engine.error.value)
        assertTrue(history.isEmpty())
        engine.tick().join()
        assertNull(engine.error.value)
        assertEquals(1, history.size)
        assertEquals(1_060_000L, history.single().actualEndEpoch)
    }

    @Test fun pauseResumeCancelPreservesOriginalStartAndOnlyActiveTime() = fixture { engine, history, clock, _ ->
        engine.start("research", TimerKind.Focus, 2).join()
        clock(1_030_000)
        engine.pause().join()
        clock(2_000_000)
        engine.resume().join()
        clock(2_020_000)
        engine.cancel().join()
        assertEquals(50_000L, history.single().activeMillis)
        assertEquals(1_000_000L, history.single().startedAtEpoch)
        assertFalse(history.single().completed)
    }

    @Test fun calendarBucketsHandleBothDaylightSavingTransitionsAndExcludePriorDates() {
        val zone = ZoneId.of("Europe/Berlin")
        for (start in listOf(LocalDate.of(2026, 3, 28), LocalDate.of(2026, 10, 24))) {
            val times = (0L..2L).map { start.plusDays(it).atStartOfDay(zone).toInstant().toEpochMilli() } +
                start.minusDays(1).atTime(23, 59).atZone(zone).toInstant().toEpochMilli()
            assertEquals(listOf(1, 1, 1), dailySessionCounts(times, start, 3, zone))
        }
    }
    @Test fun flowTaskAssociationIsPersistedOnlyForFocus() = fixture { engine, history, clock, _ ->
        val link = "{\"id\":\"task-one\"}"
        engine.start("category", TimerKind.Focus, 1, link).join()
        clock(1_060_000L)
        engine.tick().join()
        assertEquals("flowsavvy:v1:$link", history.single().note)
        engine.start("category", TimerKind.ShortBreak, 1, link).join()
        clock(1_120_000L)
        engine.tick().join()
        assertEquals(null, history.last().note)
    }

}
