// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0
package com.raban.etabli.focus.notifications

import com.raban.etabli.focus.calendar.CalendarReminder
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

class CalendarNotifications(private val scope: CoroutineScope) {
    private val jobs = mutableMapOf<String, Pair<CalendarReminder, Job>>()
    private val channel = Channel<CalendarReminder>(Channel.BUFFERED)
    val alerts = channel.receiveAsFlow()
    @Synchronized fun cancelAll() {
        jobs.values.forEach { it.second.cancel() }
        jobs.clear()
    }
    @Synchronized fun schedule(reminders: List<CalendarReminder>) {
        val current = reminders.associateBy { it.key }
        val now = System.currentTimeMillis()
        val iterator = jobs.iterator()
        while (iterator.hasNext()) {
            val (key, old) = iterator.next()
            // A due job must finish delivery even if refresh runs at its exact deadline.
            if (old.second.isCompleted || (old.first.atMillis > now && current[key] != old.first)) {
                old.second.cancel()
                iterator.remove()
            }
        }
        reminders.forEach { reminder ->
            if (!jobs.containsKey(reminder.key)) jobs[reminder.key] = reminder to scope.launch {
                delay((reminder.atMillis - System.currentTimeMillis()).coerceAtLeast(0))
                channel.send(reminder)
            }
        }
    }
}
