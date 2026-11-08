// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.focus.notifications

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * In-process counterpart of Android's AlarmManager completion alarm. It
 * re-checks the wall clock at least once per second instead of sleeping for
 * the whole interval, because monotonic timers stop while the computer
 * sleeps; after a resume the overdue block is finished within a second.
 * When the app is not running, start-up reconciliation takes over.
 */
class DesktopAlarm(
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val onDue: () -> Unit,
) {
    private var job: Job? = null

    @Synchronized
    fun schedule(atEpochMillis: Long) {
        job?.cancel()
        job = scope.launch {
            while (true) {
                val remaining = atEpochMillis - nowMillis()
                if (remaining <= 0) break
                delay(remaining.coerceAtMost(1_000))
            }
            onDue()
        }
    }

    @Synchronized
    fun cancel() {
        job?.cancel()
        job = null
    }
}
