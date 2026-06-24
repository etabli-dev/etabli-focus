// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.focus.engine

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.raban.etabli.focus.data.AppDatabase
import com.raban.etabli.focus.data.SessionEntity
import com.raban.etabli.focus.notifications.NotificationScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

// Android twin of the iOS PomodoroEngine (PomodoroEngine.swift).
//
// Identical contract:
//   - active timer state lives in DataStore (not Room) so it survives
//     force-quit / background / reboot AND can be read instantly on launch
//     before the @Database container opens
//   - `endEpochMillis` is the source of truth for remaining time while
//     running (NOT a wall-clock tick) — guarantees correctness across
//     backgrounding
//   - `pausedRemainingMillis` carries the budget while paused
//   - tick() detects the "ran out while we were backgrounded" case and
//     finalises the SessionEntity row with completed=true
//   - notifications scheduled via AlarmManager so they fire at endDate
//     regardless of process state.

enum class TimerPhase { Idle, Running, Paused }
enum class TimerKind  { Focus, ShortBreak, LongBreak }

data class TimerState(
    val phase: TimerPhase = TimerPhase.Idle,
    val kind: TimerKind = TimerKind.Focus,
    val categoryID: String? = null,
    val sessionID: String? = null,
    val endEpochMillis: Long? = null,
    val pausedRemainingMillis: Long? = null,
    val totalSeconds: Long = 0,
    val blocksCompletedInSet: Int = 0,
)

// MARK: - DataStore-backed persistence

private val Context.timerStore by preferencesDataStore(name = "timer_state")

private object Keys {
    val phase            = stringPreferencesKey("phase")
    val kind             = stringPreferencesKey("kind")
    val categoryID       = stringPreferencesKey("categoryID")
    val sessionID        = stringPreferencesKey("sessionID")
    val endEpoch         = longPreferencesKey("endEpoch")
    val pausedRemaining  = longPreferencesKey("pausedRemaining")
    val totalSeconds     = longPreferencesKey("totalSeconds")
    val blocksInSet      = intPreferencesKey("blocksInSet")
    val touched          = booleanPreferencesKey("touched")
}

class PomodoroEngine(
    private val context: Context,
    private val db: AppDatabase,
    private val scope: CoroutineScope,
) {
    val state: StateFlow<TimerState> = context.timerStore.data
        .map { prefs -> readState(prefs) }
        .stateIn(scope, started = kotlinx.coroutines.flow.SharingStarted.Eagerly, TimerState())

    private val scheduler = NotificationScheduler(context)

    /// Start a fresh focus/break of `minutes`. Persists end-epoch and
    /// schedules a completion alarm.
    fun start(categoryID: String, kind: TimerKind, minutes: Int) {
        val seconds = minutes * 60L
        val now = System.currentTimeMillis()
        val end = now + seconds * 1000
        val sessionID = UUID.randomUUID().toString()
        scope.launch {
            context.timerStore.edit { p ->
                p[Keys.phase] = TimerPhase.Running.name
                p[Keys.kind] = kind.name
                p[Keys.categoryID] = categoryID
                p[Keys.sessionID] = sessionID
                p[Keys.endEpoch] = end
                p.remove(Keys.pausedRemaining)
                p[Keys.totalSeconds] = seconds
                p[Keys.touched] = true
            }
            scheduler.scheduleCompletion(at = end, kind = kind)
        }
    }

    fun pause() {
        scope.launch {
            val s = state.value
            if (s.phase != TimerPhase.Running) return@launch
            val remaining = ((s.endEpochMillis ?: 0) - System.currentTimeMillis()).coerceAtLeast(0)
            context.timerStore.edit { p ->
                p[Keys.phase] = TimerPhase.Paused.name
                p.remove(Keys.endEpoch)
                p[Keys.pausedRemaining] = remaining
            }
            scheduler.cancel()
        }
    }

    fun resume() {
        scope.launch {
            val s = state.value
            val rem = s.pausedRemainingMillis ?: return@launch
            val newEnd = System.currentTimeMillis() + rem
            context.timerStore.edit { p ->
                p[Keys.phase] = TimerPhase.Running.name
                p[Keys.endEpoch] = newEnd
                p.remove(Keys.pausedRemaining)
            }
            scheduler.scheduleCompletion(at = newEnd, kind = s.kind)
        }
    }

    /// Cancel without completing — writes a SessionEntity with completed=false
    /// reflecting actual elapsed time.
    fun cancel() {
        scope.launch {
            val s = state.value
            if (s.sessionID != null) writeSession(s, completed = false)
            context.timerStore.edit { it.clear() }
            scheduler.cancel()
        }
    }

    /// Detects "ran out while we were backgrounded" and finalises the session.
    /// Called from a Compose `LaunchedEffect` once per second.
    fun tick() {
        scope.launch {
            val s = state.value
            if (s.phase != TimerPhase.Running) return@launch
            val end = s.endEpochMillis ?: return@launch
            if (System.currentTimeMillis() >= end) {
                if (s.kind == TimerKind.Focus) {
                    writeSession(s, completed = true)
                }
                context.timerStore.edit { p ->
                    p[Keys.phase] = TimerPhase.Idle.name
                    p.remove(Keys.endEpoch)
                    p.remove(Keys.sessionID)
                    if (s.kind == TimerKind.Focus) {
                        p[Keys.blocksInSet] = (p[Keys.blocksInSet] ?: 0) + 1
                    }
                }
            }
        }
    }

    private suspend fun writeSession(s: TimerState, completed: Boolean) {
        val cid = s.categoryID ?: return
        val sid = s.sessionID ?: return
        val now = System.currentTimeMillis()
        val startedAt = (s.endEpochMillis ?: now) - s.totalSeconds * 1000
        db.sessionDao().insert(
            SessionEntity(
                id = sid,
                categoryID = cid,
                startedAtEpoch = startedAt,
                plannedEndEpoch = s.endEpochMillis ?: now,
                actualEndEpoch = now,
                completed = completed,
                kind = if (s.kind == TimerKind.Focus) "focus" else "break",
            )
        )
    }

    private fun readState(p: Preferences): TimerState = TimerState(
        phase                  = TimerPhase.valueOf(p[Keys.phase] ?: TimerPhase.Idle.name),
        kind                   = TimerKind.valueOf(p[Keys.kind]   ?: TimerKind.Focus.name),
        categoryID             = p[Keys.categoryID],
        sessionID              = p[Keys.sessionID],
        endEpochMillis         = p[Keys.endEpoch],
        pausedRemainingMillis  = p[Keys.pausedRemaining],
        totalSeconds           = p[Keys.totalSeconds] ?: 0,
        blocksCompletedInSet   = p[Keys.blocksInSet] ?: 0,
    )

    companion object {
        @Volatile private var instance: PomodoroEngine? = null
        fun get(context: Context, db: AppDatabase, scope: CoroutineScope): PomodoroEngine {
            return instance ?: synchronized(this) {
                instance ?: PomodoroEngine(context.applicationContext, db, scope).also { instance = it }
            }
        }
    }
}

/// Helper for the UI: seconds remaining, computed from the state snapshot.
fun TimerState.remainingSeconds(now: Long = System.currentTimeMillis()): Long {
    return when (phase) {
        TimerPhase.Running -> ((endEpochMillis ?: 0) - now).coerceAtLeast(0) / 1000
        TimerPhase.Paused  -> (pausedRemainingMillis ?: 0) / 1000
        TimerPhase.Idle    -> 0
    }
}
/// 0..1 progress for the ring.
fun TimerState.progress(now: Long = System.currentTimeMillis()): Float {
    if (totalSeconds == 0L) return 0f
    val remaining = remainingSeconds(now)
    return (1f - remaining.toFloat() / totalSeconds.toFloat()).coerceIn(0f, 1f)
}
