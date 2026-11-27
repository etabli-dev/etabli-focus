// Copyright 2026 R. Heller
// SPDX-License-Identifier: AGPL-3.0-only
package com.raban.etabli.focus.interstice

import com.raban.etabli.focus.flowsavvy.FlowTask

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.raban.etabli.focus.data.CategoryEntity

@Composable
fun IntersticePanel(service: Interstice, categories: List<CategoryEntity>, timerIdle: Boolean,
                  onBack: () -> Unit, onStart: (String, Int, String) -> Unit) {
    val status by service.status.collectAsState()
    var origin by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var categoryID by remember { mutableStateOf<String?>(null) }
    var showCategories by remember { mutableStateOf(false) }
    var minutes by remember { mutableIntStateOf(25) }
    var taskQuery by remember { mutableStateOf("") }
    var taskCategory by remember { mutableStateOf("") }
    var showTaskCategories by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<FlowTask?>(null) }
    var confirmation by remember { mutableStateOf<FlowTask?>(null) }
    val category = categories.firstOrNull { it.id == categoryID } ?: categories.firstOrNull()
    LaunchedEffect(Unit) { service.refresh() }
    LazyColumn(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Interstice", style = MaterialTheme.typography.headlineMedium)
                TextButton(onClick = onBack) { Text("Back to timer") }
            }
            Text("Work through scheduled tasks with Pomodoro. Focus time syncs automatically; completing a task always asks you first.")
        }
        if (!status.connected) item {
            OutlinedTextField(value = origin, onValueChange = { origin = it }, label = { Text("Interstice HTTPS address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = apiKey, onValueChange = { apiKey = it }, label = { Text("Interstice API key") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
            Text(service.credentialDescription, style = MaterialTheme.typography.bodySmall)
            Text("Use the access key supplied for this device by your Interstice installation.", style = MaterialTheme.typography.bodySmall)
            Button(enabled = !status.busy && apiKey.isNotBlank(), onClick = { service.connect(origin, apiKey); apiKey = "" }) { Text("Connect") }
        }
        if (status.connected) item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(enabled = !status.busy, onClick = { service.refresh() }) { Text("Refresh / retry sync") }
                TextButton(enabled = !status.busy, onClick = { service.disconnect() }) { Text("Disconnect") }
            }
            OutlinedTextField(value = taskQuery, onValueChange = { taskQuery = it }, label = { Text("Find by list, person or topic") }, modifier = Modifier.fillMaxWidth())
            Box {
                OutlinedButton(onClick = { showTaskCategories = true }) { Text(intersticeCategories[taskCategory] ?: "All task categories") }
                DropdownMenu(expanded = showTaskCategories, onDismissRequest = { showTaskCategories = false }) {
                    (mapOf("" to "All task categories") + intersticeCategories).forEach { (id, label) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { taskCategory = id; showTaskCategories = false })
                    }
                }
            }
            Text("Timer category", style = MaterialTheme.typography.labelMedium)
            Text("Scheduled tasks first · server planning horizon", style = MaterialTheme.typography.bodySmall)
            status.syncedAt?.let { Text("Task copy refreshed: $it", style = MaterialTheme.typography.bodySmall) }
            Box {
                OutlinedButton(onClick = { showCategories = true }) { Text(category?.name ?: "Create a category first") }
                DropdownMenu(expanded = showCategories, onDismissRequest = { showCategories = false }) {
                    categories.forEach { cat -> DropdownMenuItem(text = { Text(cat.name) }, onClick = { categoryID = cat.id; showCategories = false }) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(enabled = minutes > 5, onClick = { minutes -= 5 }) { Text("−") }
                Text("$minutes min focus", modifier = Modifier.padding(top = 12.dp))
                OutlinedButton(enabled = minutes < 120, onClick = { minutes += 5 }) { Text("+") }
            }
            if (!timerIdle) Text("A timer is active. Return to the timer to pause or stop it before starting another task.")
        }
        if (status.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        status.message?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        if (status.pendingMinutes > 0) item { Text("${status.pendingMinutes} minutes waiting to sync") }
        if (status.connected && !status.busy && status.tasks.isEmpty()) item { Text("No open tasks found. Recurring occurrences appear within the server planning horizon.") }
        items(status.tasks.filter { (taskCategory.isEmpty() || it.category == taskCategory) && (it.title + " " + it.contextLabel()).contains(taskQuery.trim(), ignoreCase = true) }, key = { it.identity }) { task ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(task.title, style = MaterialTheme.typography.titleMedium)
                    Text(task.contextLabel(), style = MaterialTheme.typography.bodySmall)
                    TextButton(enabled = !status.busy, onClick = { editing = task }) { Text("Edit details") }
                    task.scheduled?.let { Text(it.replace('T', ' '), style = MaterialTheme.typography.bodySmall) }
                    Text("${task.progress} min reported" + (task.duration?.let { " · $it min planned" } ?: ""), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(enabled = timerIdle && category != null && !status.busy, onClick = {
                            val binding = service.binding()
                            if (binding != null && binding == task.credentialBinding && category != null) { onStart(category.id, minutes, task.link(binding)); onBack() }
                        }) { Text("Start Pomodoro") }
                        TextButton(enabled = timerIdle && !status.busy, onClick = { confirmation = task }) { Text("Mark complete…") }
                    }
                }
            }
        }
    }
    editing?.let { task ->
        IntersticeDetailsEditor(task, status.busy, status.message, { editing = null }) { details ->
            service.saveDetails(task, details) { editing = null }
        }
    }
    confirmation?.let { task ->
        AlertDialog(onDismissRequest = { confirmation = null }, title = { Text("Complete task?") },
            text = { Text("Mark “${task.title}” as completed in Interstice? Unsent focus time will be synced first.") },
            confirmButton = { TextButton(onClick = { service.complete(task); confirmation = null }) { Text("Mark complete") } },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("Keep open") } })
    }
}

@Composable
private fun DetailChoice(label: String, value: String, choices: Map<String,String>, enabled: Boolean, changed: (String)->Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(enabled = enabled, onClick = { expanded = true }) { Text("$label: ${choices[value] ?: value}") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { (key,text) -> DropdownMenuItem(text = { Text(text) }, onClick = { changed(key); expanded = false }) }
        }
    }
}

@Composable
private fun IntersticeDetailsEditor(task: FlowTask, busy: Boolean, message: String?, close: ()->Unit, save: (IntersticeDetails)->Unit) {
    var workList by remember(task.identity) { mutableStateOf(task.workList) }
    var category by remember(task.identity) { mutableStateOf(task.category) }
    var person by remember(task.identity) { mutableStateOf(task.person) }
    var mode by remember(task.identity) { mutableStateOf(task.communicationMode) }
    var topics by remember(task.identity) { mutableStateOf(task.tags.joinToString(", ")) }
    val details = IntersticeDetails(workList, category, person, mode, topics)
    val valid = runCatching { details.body() }.isSuccess
    AlertDialog(onDismissRequest = { if(!busy) close() }, title = { Text("Edit task details") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(task.title)
                OutlinedTextField(value = workList, onValueChange = { workList = it }, enabled = !busy, label = { Text("List · work area") }, singleLine = true)
                DetailChoice("Suggested lists", workList, intersticeLists.associateWith { it }, !busy) { workList = it }
                DetailChoice("Category", category, intersticeCategories, !busy) { category = it }
                OutlinedTextField(value = person, onValueChange = { person = it }, enabled = !busy, label = { Text("Person (optional)") })
                DetailChoice("Communication mode", mode, intersticeModes, !busy) { mode = it }
                OutlinedTextField(value = topics, onValueChange = { topics = it }, enabled = !busy, label = { Text("Topic tags · comma separated") })
                Text("Saved edits retry when connected. Closing after a network error keeps the pending save on this device.")
                if(!valid) Text("Check list length (1–100), person (up to 200), and tags (up to 20, 80 characters each).")
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(enabled = !busy && valid, onClick = { save(details) }) { Text(if(busy) "Saving…" else "Save / retry") } },
        dismissButton = { TextButton(enabled = !busy, onClick = close) { Text("Close") } })
}
