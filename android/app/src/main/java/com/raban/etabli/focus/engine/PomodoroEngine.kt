// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.focus.engine

import android.content.Context
import androidx.datastore.core.DataStore
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

// MARK: - DataStore-backed persistence

private val Context.timerStore by preferencesDataStore(name = "timer_state")

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

class PomodoroEngine internal constructor(
    private val store: DataStore<Preferences>,
    private val saveSession: suspend (SessionEntity) -> Unit,
    private val schedule: (Long, TimerKind) -> Unit,
    private val cancelAlarm: () -> Unit,
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    constructor(context: Context, db: AppDatabase, scope: CoroutineScope) : this(
        context.timerStore,
        { db.sessionDao().insert(it) },
        { at, kind -> NotificationScheduler(context).scheduleCompletion(at, kind) },
        { NotificationScheduler(context).cancel() },
        scope,
    )

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

    fun restoreAlarm() = command {
        val s = current()
        if (s.phase != TimerPhase.Running) return@command
        val end = s.endEpochMillis ?: return@command
        if (end <= nowMillis()) finish(s)
        else schedule(end, s.kind)
    }

    private suspend fun finish(s: TimerState) {
        writeSession(s, completed = true)
        store.edit { p ->
            p[Keys.phase] = TimerPhase.Idle.name
            p.remove(Keys.endEpoch)
            p.remove(Keys.sessionID)
            p.remove(Keys.flowTaskLink)
            if (s.kind == TimerKind.Focus) p[Keys.blocksInSet] = s.blocksCompletedInSet + 1
        }
        cancelAlarm()
    }

    private suspend fun writeSession(s: TimerState, completed: Boolean) {
        val cid = requireNotNull(s.categoryID)
        val sid = requireNotNull(s.sessionID)
        val now = nowMillis()
        val end = if (completed) s.endEpochMillis ?: now else now
        val active = s.activeMillis(now, completed)
        val startedAt = s.startedAtEpochMillis ?: (end - active)
        saveSession(SessionEntity(
            id = sid, categoryID = cid, startedAtEpoch = startedAt,
            plannedEndEpoch = s.endEpochMillis ?: now,
            actualEndEpoch = end, activeMillis = active,
            completed = completed, kind = if (s.kind == TimerKind.Focus) "focus" else "break",
            note = if (s.kind == TimerKind.Focus) s.flowTaskLink?.let { "flowsavvy:v1:$it" } else null,
        ))
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

    companion object {
        @Volatile private var instance: PomodoroEngine? = null
        fun get(context: Context, db: AppDatabase, scope: CoroutineScope): PomodoroEngine {
            return instance ?: synchronized(this) {
                instance ?: PomodoroEngine(context.applicationContext, db, scope).also { instance = it }
            }
        }
    }
}
