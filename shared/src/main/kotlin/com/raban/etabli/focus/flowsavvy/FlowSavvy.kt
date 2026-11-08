// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0
package com.raban.etabli.focus.flowsavvy

import com.raban.etabli.focus.data.SessionEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.LocalDate

/** A task identity includes its occurrence and credential binding, never the API key. */
data class FlowTask(val id: String, val title: String, val occurrence: String? = null,
                    val scheduled: String? = null, val progress: Int = 0, val duration: Int? = null,
                    val credentialBinding: String? = null) {
    fun link(binding: String) = JSONObject().put("v", 1).put("binding", binding).put("id", id)
        .put("title", title).put("occurrence", occurrence ?: JSONObject.NULL).toString()
    val identity: String get() = "$id|${occurrence.orEmpty()}"
}

interface KeyVault {
    val description: String
    fun read(): String?
    fun write(key: String?)
}
interface LedgerStore { fun read(): JSONObject; fun write(value: JSONObject) }
class FileLedger(private val path: Path) : LedgerStore {
    override fun read(): JSONObject = if (Files.exists(path)) JSONObject(String(Files.readAllBytes(path), Charsets.UTF_8)) else JSONObject()
    override fun write(value: JSONObject) {
        Files.createDirectories(path.parent)
        val temporary = Files.createTempFile(path.parent, ".flowsavvy-", ".tmp")
        try {
            Files.write(temporary, value.toString().toByteArray(Charsets.UTF_8))
            java.io.FileOutputStream(temporary.toFile(), true).use { it.fd.sync() }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }
}
fun interface FlowTransport { suspend fun request(key: String, method: String, path: String, body: JSONObject?): JSONObject }
class FlowHttp : FlowTransport {
    override suspend fun request(key: String, method: String, path: String, body: JSONObject?): JSONObject = withContext(Dispatchers.IO) {
        require(path.startsWith("/api/"))
        val connection = URI("https://my.flowsavvy.app$path").toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15000; connection.readTimeout = 20000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer $key")
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            when (val status = connection.responseCode) {
                in 200..299 -> connection.inputStream.bufferedReader().use { JSONObject(it.readText().ifBlank { "{}" }) }
                401, 403 -> error("FlowSavvy rejected the connection. Check the API key and Pro subscription.")
                429 -> error("FlowSavvy is busy. Progress is saved; retry later.")
                else -> error("FlowSavvy request failed ($status). Progress is saved; retry later.")
            }
        } finally { connection.disconnect() }
    }
}

data class FlowStatus(val connected: Boolean = false, val busy: Boolean = false,
                      val tasks: List<FlowTask> = emptyList(), val message: String? = null,
                      val pendingMinutes: Long = 0)

/** One serialized writer, durable absolute-progress intents, and explicit completion. */
class FlowSavvy(
    private val vault: KeyVault,
    private val ledgerStore: LedgerStore,
    private val sessions: Flow<List<SessionEntity>>,
    private val scope: CoroutineScope,
    private val transport: FlowTransport = FlowHttp(),
) {
    private val mutex = Mutex()
    @Volatile private var key: String? = null
    @Volatile private var installation: String? = null
    private var history: List<SessionEntity> = emptyList()
    private val _status = MutableStateFlow(FlowStatus())
    val status: StateFlow<FlowStatus> = _status.asStateFlow()
    val credentialDescription get() = vault.description
    fun binding(): String? = key?.let { token -> installation?.let { "${fingerprint(token)}:$it" } }
    init {
        scope.launch {
            action {
                initializeLedger()
                key = vault.read()
                if (key != null) { syncLocked(); loadLocked() }
            }
        }
        scope.launch {
            sessions.collect { rows ->
                action {
                    history = rows
                    if (key != null) syncLocked()
                }
            }
        }
    }
    fun connect(value: String) = scope.launch { action {
        initializeLedger()
        val candidate = value.trim()
        require(candidate.isNotEmpty() && candidate.none { it.isWhitespace() || it.code < 32 }) { "Enter a valid API key." }
        // Validate without altering the current connection or uploading queued work.
        transport.request(candidate, "GET", "/api/items?itemType=task&completed=false&limit=1", null)
        vault.write(candidate); key = candidate
        _status.update { it.copy(tasks = emptyList()) }
        history = sessions.first()
        syncLocked(); loadLocked()
    } }
    fun disconnect() = scope.launch { action { vault.write(null); key = null; _status.value = FlowStatus(message = "Disconnected. Unsent work stays on this device.") } }
    fun refresh() = scope.launch { action { if (key != null) { history = sessions.first(); syncLocked(); loadLocked() } } }
    fun complete(task: FlowTask) = scope.launch { action {
        val token = requireNotNull(key) { "Connect FlowSavvy first." }
        check(task.credentialBinding == binding()) { "The connection changed. Refresh and select the task again." }
        history = sessions.first()
        syncLocked()
        transport.request(token, "POST", "/api/tasks/${encode(task.id)}/complete${query(task.occurrence)}", JSONObject())
        loadLocked()
        _status.update { it.copy(message = "Task marked complete in FlowSavvy.") }
    } }
    private suspend fun action(block: suspend () -> Unit) = mutex.withLock {
        _status.update { it.copy(busy = true, message = null) }
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { _status.update { it.copy(message = failure.message?.take(220) ?: "Connection failed. Your work is saved.") } }
        finally { _status.update { it.copy(busy = false, connected = key != null) } }
    }
    private suspend fun loadLocked() {
        val token = requireNotNull(key)
        val items = mutableMapOf<String, JSONObject>()
        var page: String? = null
        val seen = mutableSetOf<String>()
        do {
            val response = transport.request(token, "GET", "/api/items?itemType=task&completed=false&limit=200" + (page?.let { "&pageToken=${encode(it)}" } ?: ""), null)
            val rows = response.optJSONArray("items") ?: JSONArray()
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                if (row.optString("itemType") == "task" && !row.optBoolean("completed")) items[row.getString("id")] = row
            }
            page = response.stringOrNull("nextPageToken")
            check(page == null || seen.add(page)) { "FlowSavvy returned a repeated page. Please retry." }
        } while (page != null)
        val today = LocalDate.now()
        val schedule = transport.request(token, "GET", "/api/schedule?startDate=$today&endDate=${today.plusDays(7)}", null).optJSONArray("scheduleItems") ?: JSONArray()
        val tasks = linkedMapOf<String, FlowTask>()
        for (i in 0 until schedule.length()) {
            val slot = schedule.getJSONObject(i)
            if (slot.optString("itemType") != "task" || slot.optBoolean("completed") || slot.optBoolean("thisPartCompleted")) continue
            val id = slot.stringOrNull("itemId") ?: continue
            val row = items[id] ?: continue
            val task = FlowTask(id, row.stringOrNull("title") ?: "Untitled task", slot.stringOrNull("occurrenceDate"), slot.stringOrNull("startTime"), row.optInt("progressMinutes"), row.intOrNull("durationMinutes"), credentialBinding = binding())
            val old = tasks[task.identity]
            if (old == null || task.scheduled.orEmpty() < old.scheduled.orEmpty()) tasks[task.identity] = task
        }
        for ((id, row) in items) {
            // A recurring series without an occurrence is not a writable task selection.
            if (row.stringOrNull("recurrenceRule") != null) continue
            val task = FlowTask(id, row.stringOrNull("title") ?: "Untitled task", progress = row.optInt("progressMinutes"), duration = row.intOrNull("durationMinutes"), credentialBinding = binding())
            if (!tasks.containsKey(task.identity)) tasks[task.identity] = task
        }
        _status.update { it.copy(tasks = tasks.values.sortedWith(compareBy<FlowTask> { t -> t.scheduled ?: "9999" }.thenBy { t -> t.title.lowercase() })) }
    }
    private fun initializeLedger() {
        val ledger = ledgerStore.read()
        installation = ledger.stringOrNull("_installation") ?: java.util.UUID.randomUUID().toString().also {
            ledger.put("_installation", it); ledgerStore.write(ledger)
        }
    }
    private suspend fun syncLocked() {
        val token = requireNotNull(key)
        val binding = requireNotNull(binding())
        val ledger = ledgerStore.read()
        val groups = history.filter { it.kind == "focus" && it.actualEndEpoch != null && it.note?.startsWith(NOTE_PREFIX) == true }
            .distinctBy { it.id }.groupBy {
                val ref = JSONObject(it.note!!.removePrefix(NOTE_PREFIX))
                fingerprint(ref.getString("binding") + "|" + ref.getString("id") + "|" + ref.stringOrNull("occurrence").orEmpty())
            }
        var foreign = false
        for ((identity, rows) in groups) {
            val ref = JSONObject(rows.first().note!!.removePrefix(NOTE_PREFIX))
            val total = rows.sumOf { (it.activeMillis ?: 0L).coerceAtLeast(0) }
            val entry = ledger.optJSONObject(identity) ?: JSONObject().put("ackMillis", 0L)
            val ack = entry.optLong("ackMillis")
            if (ref.getString("binding") != binding) { if (total / 60000 > ack / 60000) foreign = true; continue }
            while (true) {
                val delta = total / 60000 - entry.optLong("ackMillis") / 60000
                if (delta <= 0 && !entry.has("intent")) break
                _status.update { it.copy(pendingMinutes = delta.coerceAtLeast(0)) }
                val id = ref.getString("id"); val occurrence = ref.stringOrNull("occurrence")
                val remote = transport.request(token, "GET", "/api/items/${encode(id)}${query(occurrence)}", null)
                check(remote.optString("itemType") == "task" && !remote.optBoolean("completed")) { "${ref.optString("title")}: task is completed or unavailable. Unsent work remains saved." }
                val current = remote.optInt("progressMinutes")
                var intent = entry.optJSONObject("intent")
                if (intent == null) {
                    intent = JSONObject().put("base", current).put("target", current.toLong() + delta).put("totalMillis", total)
                    entry.put("intent", intent); ledger.put(identity, entry); ledgerStore.write(ledger)
                }
                val preparedIntent = requireNotNull(intent)
                val base = preparedIntent.getLong("base"); val target = preparedIntent.getLong("target")
                when (current.toLong()) {
                    target -> Unit // The previous write succeeded, but its response or acknowledgement was lost.
                    base -> {
                        val body = writableTask(remote).put("progressMinutes", target)
                        val returnedID = remote.getString("id")
                        val isSeries = remote.stringOrNull("recurrenceRule") != null
                        val suffix = if (isSeries) query(occurrence, true) else ""
                        check(!isSeries || occurrence != null) { "Select a specific occurrence before reporting time." }
                        val updated = transport.request(token, "PUT", "/api/tasks/${encode(returnedID)}$suffix", body)
                        check(updated.optLong("progressMinutes", -1) == target) { "FlowSavvy did not confirm the reported minutes. Unsent work is saved; please refresh." }
                    }
                    else -> error("${ref.optString("title")}: progress changed elsewhere ($current min; expected $base or $target). Unsent work is saved. Reconcile in FlowSavvy before retrying.")
                }
                entry.put("ackMillis", preparedIntent.getLong("totalMillis")).remove("intent")
                ledger.put(identity, entry); ledgerStore.write(ledger)
            }
        }
        _status.update { it.copy(pendingMinutes = 0, message = if (foreign) "Unsent work belongs to a different API key. Reconnect that key to sync it." else null) }
    }
    companion object {
        const val NOTE_PREFIX = "flowsavvy:v1:"
        fun fingerprint(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        private val readOnly = setOf("itemType", "id", "lastModified", "dateCreated", "url", "parentItemId", "occurrenceDate", "cancelledOccurrenceDates", "completed")
        fun writableTask(task: JSONObject): JSONObject = JSONObject(task.toString()).apply { readOnly.forEach(::remove) }
        fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
        fun query(occurrence: String?, update: Boolean = false): String = occurrence?.let { "?occurrenceDate=${encode(it)}" + if (update) "&scope=thisOccurrence" else "" } ?: ""
    }
}
fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
fun JSONObject.intOrNull(key: String): Int? = if (isNull(key)) null else optInt(key)
