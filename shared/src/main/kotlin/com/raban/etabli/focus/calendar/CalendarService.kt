// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0
package com.raban.etabli.focus.calendar

import com.raban.etabli.focus.flowsavvy.KeyVault
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** Schedule notifications without changing the Pomodoro or calendar events. */
data class CalendarReminder(val key: String, val entry: CalendarEntry, val atMillis: Long, val minutesBefore: Int)
fun reminders(entries: List<CalendarEntry>, lead: Int, now: Long): List<CalendarReminder> = entries.flatMap { entry ->
    val at = entry.reminderStart()
    listOf(lead, 0).distinct().map { minutes ->
        CalendarReminder("${entry.id}:$minutes", entry, at - minutes * 60000L, minutes)
    }.filter { it.atMillis > now }
}.sortedBy { it.atMillis }.take(60)

data class CalendarState(
    val connected: Boolean = false, val busy: Boolean = false,
    val entries: List<CalendarEntry> = emptyList(), val error: String? = null,
    val lastSync: Long? = null, val leadMinutes: Int = 5,
    val upcomingReminder: CalendarEntry? = null,
)

class CalendarService(
    private val vault: KeyVault,
    private val scope: CoroutineScope,
    private val schedule: (List<CalendarReminder>) -> Unit,
    private val client: CalDavClient = CalDavClient(),
    private val cancelReminders: () -> Unit = { schedule(emptyList()) },
) {
    private val lock = Mutex()
    private var connection: CalendarConnection? = null
    private val mutable = MutableStateFlow(CalendarState())
    val state: StateFlow<CalendarState> = mutable.asStateFlow()
    val storageDescription: String get() = vault.description
    init {
        scope.launch(Dispatchers.IO) {
            lock.withLock {
            try {
                vault.read()?.let { raw ->
                    val json = JSONObject(raw)
                    connection = CalendarConnection(json.getString("url"), json.getString("username"),
                        json.getString("password"), json.optInt("lead", 5))
                    mutable.value = mutable.value.copy(connected = true, leadMinutes = connection!!.leadMinutes)
                }
            } catch (_: Exception) { mutable.value = mutable.value.copy(error = "Could not read the calendar connection. Reconnect to retry.") }
            }
            while (isActive) { refreshNow(); delay(60_000) }
        }
        scope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                mutable.update { s -> s.copy(upcomingReminder = s.entries.firstOrNull {
                    val at = it.reminderStart()
                    now >= at - s.leadMinutes * 60000L && now <= maxOf(at + 60_000, it.endMillis)
                }) }
                delay(1000)
            }
        }
    }
    fun connect(url: String, username: String, password: String, lead: Int) = scope.launch(Dispatchers.IO) {
        lock.withLock {
            mutable.update { it.copy(busy = true, error = null) }
            try {
                val value = CalendarConnection(url.trim(), username.trim(), password, lead)
                value.validate()
                val entries = client.entries(value)
                vault.write(JSONObject().put("url", value.url).put("username", value.username)
                    .put("password", value.password).put("lead", value.leadMinutes).toString())
                connection = value
                schedule(reminders(entries, lead, System.currentTimeMillis()))
                mutable.value = CalendarState(true, entries = entries, lastSync = System.currentTimeMillis(), leadMinutes = lead)
            } catch (e: Exception) {
                mutable.update { it.copy(busy = false, error = safeError(e)) }
            }
        }
    }
    fun refresh() = scope.launch(Dispatchers.IO) { refreshNow() }
    private suspend fun refreshNow() = lock.withLock {
        val value = connection ?: return@withLock
        mutable.update { it.copy(busy = true) }
        try {
            val entries = client.entries(value)
            schedule(reminders(entries, value.leadMinutes, System.currentTimeMillis()))
            mutable.update { it.copy(busy = false, error = null, entries = entries, lastSync = System.currentTimeMillis()) }
        } catch (e: Exception) { mutable.update { it.copy(busy = false, error = safeError(e)) } }
    }
    fun disconnect() = scope.launch(Dispatchers.IO) {
        lock.withLock {
            try {
                vault.write(null)
                connection = null
                cancelReminders()
                mutable.value = CalendarState()
            } catch (_: Exception) { mutable.update { it.copy(error = "Could not disconnect the calendar. Please retry.") } }
        }
    }
    private fun safeError(error: Exception): String = when (error) {
        is IllegalArgumentException, is IllegalStateException -> error.message ?: "Calendar sync failed."
        else -> "Calendar is offline or its response could not be read. Cached appointments may be outdated; retry sync."
    }
}
