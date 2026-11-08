package com.raban.etabli.focus.calendar

import com.raban.etabli.focus.flowsavvy.KeyVault
import kotlinx.coroutines.*
import kotlin.test.*

class CalendarServiceTest {
    @Test fun failedReplacementKeepsPreviousConnectionAndDisconnectCancelsReminders() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val vault = object : KeyVault {
            override val description = "test vault"
            var value: String? = null
            override fun read() = value
            override fun write(key: String?) { value = key }
        }
        var last: List<CalendarReminder>? = null
        val client = CalDavClient { connection, _ ->
            if (connection.password == "bad") error("Calendar access denied.")
            "<d:multistatus xmlns:d=\"DAV:\"/>"
        }
        try {
            val service = CalendarService(vault, scope, { last = it }, client)
            service.connect("https://example.test/calendar/", "user", "good", 5).join()
            assertTrue(service.state.value.connected)
            val saved = vault.value
            service.connect("https://example.test/other/", "user", "bad", 5).join()
            assertEquals(saved, vault.value)
            assertTrue(service.state.value.connected)
            assertNotNull(service.state.value.error)
            service.disconnect().join()
            assertNull(vault.value)
            assertFalse(service.state.value.connected)
            assertEquals(emptyList(), last)
        } finally { scope.cancel() }
    }
}
