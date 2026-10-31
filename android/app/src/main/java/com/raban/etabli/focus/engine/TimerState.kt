// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.focus.engine

enum class TimerPhase { Idle, Running, Paused }
enum class TimerKind  { Focus, ShortBreak, LongBreak }

data class TimerState(
    val phase: TimerPhase = TimerPhase.Idle,
    val kind: TimerKind = TimerKind.Focus,
    val categoryID: String? = null,
    val sessionID: String? = null,
    val endEpochMillis: Long? = null,
    val startedAtEpochMillis: Long? = null,
    val pausedRemainingMillis: Long? = null,
    val totalSeconds: Long = 0,
    val blocksCompletedInSet: Int = 0,
)

/// Active time excludes pauses and never exceeds the planned budget.
fun TimerState.remainingMillis(now: Long): Long {
    val total = totalSeconds.coerceIn(0, 86400) * 1000
    return when (phase) {
        TimerPhase.Running -> ((endEpochMillis ?: now) - now).coerceIn(0, total)
        TimerPhase.Paused -> (pausedRemainingMillis ?: 0).coerceIn(0, total)
        TimerPhase.Idle -> 0
    }
}

fun TimerState.activeMillis(now: Long, completed: Boolean): Long {
    val total = totalSeconds.coerceIn(0, 86400) * 1000
    return if (completed) total else total - remainingMillis(now)
}

fun TimerState.remainingSeconds(now: Long = System.currentTimeMillis()): Long =
    (remainingMillis(now) + 999) / 1000

fun TimerState.progress(now: Long = System.currentTimeMillis()): Float {
    if (totalSeconds <= 0) return 0f
    return (1f - remainingMillis(now).toFloat() / (totalSeconds * 1000)).coerceIn(0f, 1f)
}
