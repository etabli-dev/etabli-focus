// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.focus.engine

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.raban.etabli.focus.data.SessionEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

// Desktop twin of the Android PomodoroEngine (android/.../engine/PomodoroEngine.kt)
// and the iOS PomodoroEngine.swift. The command logic is kept identical to
// Android so that both can move into one shared module later; only the
// platform wiring differs:
//   - active timer state lives in a Preferences DataStore file with the same
//     keys as Android, so it survives quitting the app and restarts
//   - `endEpochMillis` is the source of truth while running, never a tick
//   - `pausedRemainingMillis` carries the budget while paused
//   - tick()/restoreAlarm() finalise a block whose deadline passed while the
//     app was closed or asleep, exactly once
//   - `schedule`/`cancelAlarm` drive an in-process wall-clock alarm, and
//     `onFinished` lets the desktop shell post a completion notification.

private object Keys {
    val phase            = stringPreferencesKey("phase")
    val kind             = stringPreferencesKey("kind")
    val categoryID       = stringPreferencesKey("categoryID")
    val sessionID        = stringPreferencesKey("sessionID")
    val endEpoch         = longPreferencesKey("endEpoch")
    val startedAt        = longPreferencesKey("startedAt")
    val pausedRemaining  = longPreferencesKey("pausedRemaining")
    val totalSeconds     = longPreferencesKey("totalSeconds")
    val blocksInSet      = intPreferencesKey("blocksInSet")
    val flowTaskLink     = stringPreferencesKey("flowTaskLink")
    val touched          = booleanPreferencesKey("touched")
}

class PomodoroEngine(
    private val store: DataStore<Preferences>,
    private val saveSession: suspend (SessionEntity) -> Unit,
    private val schedule: (Long, TimerKind) -> Unit,
    private val cancelAlarm: () -> Unit,
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val onFinished: (SessionEntity) -> Unit = {},
) {
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    val state: StateFlow<TimerState> = store.data
        .catch { error ->
            if (error is CancellationException) throw error
            _error.value = "Could not read the saved timer. Restart the app to retry."
        }
        .map { prefs -> readState(prefs) }
        .stateIn(scope, started = SharingStarted.Eagerly, TimerState())

    private val commands = Mutex()
    private fun command(action: suspend () -> Unit) = scope.launch {
        commands.withLock {
            try {
                action()
                _error.value = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _error.value = "Could not update the timer. Your saved session is retained; please retry."
            }
        }
    }

    private suspend fun current() = readState(store.data.first())

    fun start(categoryID: String, kind: TimerKind, minutes: Int, flowTaskLink: String? = null) = command {
        require(categoryID.isNotBlank() && minutes in 1..1440)
        val previous = current()
        if (previous.phase != TimerPhase.Idle) return@command
        val seconds = minutes * 60L
        val now = nowMillis()
        val end = now + seconds * 1000
        store.edit { p ->
            if (kind == TimerKind.Focus && flowTaskLink != null) p[Keys.flowTaskLink] = flowTaskLink else p.remove(Keys.flowTaskLink)
            p[Keys.phase] = TimerPhase.Running.name
            p[Keys.kind] = kind.name
            p[Keys.categoryID] = categoryID
            p[Keys.sessionID] = UUID.randomUUID().toString()
            p[Keys.startedAt] = now
            p[Keys.endEpoch] = end
            p.remove(Keys.pausedRemaining)
            p[Keys.totalSeconds] = seconds
            p[Keys.touched] = true
        }
        schedule(end, kind)
    }

    fun pause() = command {
        val s = current()
        if (s.phase != TimerPhase.Running) return@command
        val now = nowMillis()
        if ((s.endEpochMillis ?: Long.MAX_VALUE) <= now) {
            finish(s)
            return@command
        }
        store.edit { p ->
            p[Keys.phase] = TimerPhase.Paused.name
            p.remove(Keys.endEpoch)
            p[Keys.pausedRemaining] = s.remainingMillis(now)
        }
        cancelAlarm()
    }

    fun resume() = command {
        val s = current()
        if (s.phase != TimerPhase.Paused) return@command
        val rem = s.pausedRemainingMillis ?: return@command
        val newEnd = nowMillis() + rem.coerceAtLeast(0)
        store.edit { p ->
            p[Keys.phase] = TimerPhase.Running.name
            p[Keys.endEpoch] = newEnd
            p.remove(Keys.pausedRemaining)
        }
        schedule(newEnd, s.kind)
    }

    fun cancel() = command {
        val s = current()
        if (s.phase == TimerPhase.Idle) return@command
        if (s.phase == TimerPhase.Running && (s.endEpochMillis ?: Long.MAX_VALUE) <= nowMillis()) {
            finish(s)
            return@command
        }
        writeSession(s, completed = false)
        store.edit { p ->
            p.clear()
            p[Keys.blocksInSet] = s.blocksCompletedInSet
        }
        cancelAlarm()
    }

    fun tick() = command {
        val s = current()
        if (s.phase == TimerPhase.Running && (s.endEpochMillis ?: Long.MAX_VALUE) <= nowMillis()) finish(s)
    }

    /** Start-up reconciliation: finish an overdue block once, or re-arm the alarm. */
    fun restoreAlarm() = command {
        val s = current()
        if (s.phase != TimerPhase.Running) return@command
        val end = s.endEpochMillis ?: return@command
        if (end <= nowMillis()) finish(s)
        else schedule(end, s.kind)
    }

    private suspend fun finish(s: TimerState) {
        val row = writeSession(s, completed = true)
        store.edit { p ->
            p[Keys.phase] = TimerPhase.Idle.name
            p.remove(Keys.endEpoch)
            p.remove(Keys.sessionID)
            p.remove(Keys.flowTaskLink)
            if (s.kind == TimerKind.Focus) p[Keys.blocksInSet] = s.blocksCompletedInSet + 1
        }
        cancelAlarm()
        onFinished(row)
    }

    private suspend fun writeSession(s: TimerState, completed: Boolean): SessionEntity {
        val cid = requireNotNull(s.categoryID)
        val sid = requireNotNull(s.sessionID)
        val now = nowMillis()
        val end = if (completed) s.endEpochMillis ?: now else now
        val active = s.activeMillis(now, completed)
        val startedAt = s.startedAtEpochMillis ?: (end - active)
        val row = SessionEntity(
            id = sid, categoryID = cid, startedAtEpoch = startedAt,
            plannedEndEpoch = s.endEpochMillis ?: now,
            actualEndEpoch = end, activeMillis = active,
            completed = completed, kind = if (s.kind == TimerKind.Focus) "focus" else "break",
            note = if (s.kind == TimerKind.Focus) s.flowTaskLink?.let { "flowsavvy:v1:$it" } else null,
        )
        saveSession(row)
        return row
    }

    private fun readState(p: Preferences): TimerState = TimerState(
        phase                  = TimerPhase.values().firstOrNull { it.name == p[Keys.phase] } ?: TimerPhase.Idle,
        kind                   = TimerKind.values().firstOrNull { it.name == p[Keys.kind] } ?: TimerKind.Focus,
        categoryID             = p[Keys.categoryID],
        sessionID              = p[Keys.sessionID],
        flowTaskLink           = p[Keys.flowTaskLink],
        endEpochMillis         = p[Keys.endEpoch],
        startedAtEpochMillis   = p[Keys.startedAt],
        pausedRemainingMillis  = p[Keys.pausedRemaining],
        totalSeconds           = p[Keys.totalSeconds] ?: 0,
        blocksCompletedInSet   = p[Keys.blocksInSet] ?: 0,
    )
}
