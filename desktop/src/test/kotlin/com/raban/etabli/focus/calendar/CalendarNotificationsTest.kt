// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0
package com.raban.etabli.focus.calendar

import com.raban.etabli.focus.notifications.CalendarNotifications
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

class CalendarNotificationsTest {
    @Test fun emptyRefreshPreservesDueDeliveryButDisconnectCancelsIt() = runBlocking {
        // Launches are queued until this coroutine yields: model an OS delivery delay.
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val scheduler = CalendarNotifications(scope)
        val now = System.currentTimeMillis() - 1
        val entry = CalendarEntry("event", "Appointment", now, now + 60000, false)
        val reminder = CalendarReminder("event:0", entry, now, 0)
        try {
            scheduler.schedule(listOf(reminder))
            scheduler.schedule(emptyList())
            assertEquals(reminder, withTimeout(5000) { scheduler.alerts.first() })
            scheduler.schedule(listOf(reminder.copy(key = "cancelled")))
            scheduler.cancelAll()
            yield()
            assertNull(withTimeoutOrNull(20) { scheduler.alerts.first() })
        } finally { scope.cancel() }
    }
}
