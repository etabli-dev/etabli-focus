// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0
package com.raban.etabli.focus.calendar

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun calendarTime(entry: CalendarEntry): String {
    val date = Instant.ofEpochMilli(entry.startMillis).atZone(ZoneId.systemDefault())
    return date.format(DateTimeFormatter.ofPattern(if (entry.allDay) "EEE, d MMM · 'all day'" else "EEE, d MMM · HH:mm"))
}

@Composable
fun CalendarPanel(service: CalendarService, onBack: () -> Unit) {
    val state by service.state.collectAsState()
    var url by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var lead by remember { mutableStateOf("5") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text("Back to timer") }
        Text("Calendar", style = MaterialTheme.typography.headlineMedium)
        Text("Read-only CalDAV appointments. Reminders appear before and at the start; your Pomodoro keeps running.")
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (!state.connected) {
            Text("Enter HTTPS calendar collection URLs, one per line (not the account home), plus the shared username and app password. Recurring events require server-side CalDAV expansion.")
            OutlinedTextField(url, { url = it }, label = { Text("CalDAV calendar collection URLs") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(username, { username = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(password, { password = it }, label = { Text("App password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(lead, { lead = it }, label = { Text("Minutes before (0–60)") }, singleLine = true)
            Text(service.storageDescription, style = MaterialTheme.typography.bodySmall)
            Button(onClick = {
                service.connect(url, username, password, lead.toIntOrNull() ?: -1)
                password = ""
            }, enabled = !state.busy && password.isNotEmpty()) { Text("Connect calendar") }
        } else {
            Text("Reminders: ${state.leadMinutes} minutes before and at the start. All-day entries remind at 09:00. Refreshes every minute while the app is running.")
            Text("Allow notifications in system settings. Keep the desktop app running for reminders; on Android already scheduled reminders can run in the background.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { service.refresh() }, enabled = !state.busy) { Text("Refresh") }
                TextButton(onClick = { service.disconnect() }, enabled = !state.busy) { Text("Disconnect") }
            }
            if (state.entries.isEmpty()) Text("No appointments in the next seven days.")
            state.entries.filter { it.endMillis >= System.currentTimeMillis() }.forEach { entry ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(entry.title, style = MaterialTheme.typography.titleMedium)
                        Text(calendarTime(entry))
                        if (entry.location.isNotBlank()) Text(entry.location)
                        if (entry.description.isNotBlank()) Text(entry.description.take(2000))
                    }
                }
            }
        }
    }
}

@Composable
fun CalendarUpcoming(service: CalendarService, onOpen: () -> Unit) {
    val state by service.state.collectAsState()
    val entry = state.upcomingReminder ?: state.entries.firstOrNull { it.endMillis >= System.currentTimeMillis() }
    Column {
        if (entry != null) Text("${if (state.upcomingReminder != null) "Calendar reminder" else "Next appointment"}: ${entry.title} · ${calendarTime(entry)}")
        if (state.error != null && state.connected) Text("Calendar sync needs attention", color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onOpen) { Text(if (state.connected) "View calendar" else "Connect CalDAV calendar") }
    }
}
