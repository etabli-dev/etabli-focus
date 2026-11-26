// Copyright 2026 R. Heller
// SPDX-License-Identifier: AGPL-3.0-only
package com.raban.etabli.focus.interstice

import com.raban.etabli.focus.data.SessionEntity
import com.raban.etabli.focus.flowsavvy.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID

data class IntersticeConnection(val origin: String, val token: String) {
    fun encoded() = JSONObject().put("origin", origin).put("token", token).toString()
    companion object {
        fun checked(origin: String, token: String): IntersticeConnection {
            val url = URI(origin.trim().trimEnd('/'))
            require(url.scheme == "https" && !url.host.isNullOrBlank() && url.rawUserInfo == null && url.rawQuery == null && url.rawFragment == null) { "Enter your Interstice HTTPS address." }
            require(token.isNotBlank() && token.none { it.isWhitespace() || it.code < 32 }) { "Enter a valid access key." }
            return IntersticeConnection(url.toASCIIString(), token)
        }
        fun decode(value: String): IntersticeConnection = JSONObject(value).let { checked(it.getString("origin"), it.getString("token")) }
    }
}

fun interface IntersticeTransport {
    suspend fun request(connection: IntersticeConnection, method: String, path: String, body: JSONObject?, headers: Map<String, String>): JSONObject
}

class IntersticeHttp : IntersticeTransport {
    override suspend fun request(connection: IntersticeConnection, method: String, path: String, body: JSONObject?, headers: Map<String, String>): JSONObject = withContext(Dispatchers.IO) {
        require(path.startsWith("/api/v1/") && !path.contains(".."))
        val http = URI(connection.origin + path).toURL().openConnection() as HttpURLConnection
        try {
            http.requestMethod = method; http.instanceFollowRedirects = false
            http.connectTimeout = 15000; http.readTimeout = 90000
            http.setRequestProperty("Authorization", "Bearer ${connection.token}")
            http.setRequestProperty("Accept", "application/json")
            headers.forEach { (key, value) -> http.setRequestProperty(key, value) }
            if (body != null) {
                http.doOutput = true; http.setRequestProperty("Content-Type", "application/json")
                http.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            check(http.responseCode in 200..299) { "Interstice request failed (${http.responseCode}). Saved work remains on this device." }
            val bytes = http.inputStream.use { stream ->
                val result = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer); if (count < 0) break
                    check(result.size() + count <= 16_000_000) { "Interstice response is too large." }
                    result.write(buffer, 0, count)
                }
                result.toByteArray()
            }
            JSONObject(String(bytes, Charsets.UTF_8))
        } finally { http.disconnect() }
    }
}

/** Separate provider binding and cumulative per-session receipts prevent history replay. */
class Interstice(private val vault: KeyVault, private val ledgerStore: LedgerStore,
                 private val sessions: Flow<List<SessionEntity>>, private val scope: CoroutineScope,
                 private val transport: IntersticeTransport = IntersticeHttp(),
                 private val retryMillis: Long = 60_000) {
    private val mutex = Mutex()
    private var connection: IntersticeConnection? = null
    private var installation: String? = null
    private val _status = MutableStateFlow(FlowStatus())
    val status = _status.asStateFlow()
    val credentialDescription get() = vault.description
    fun binding(): String? = connection?.let { c -> installation?.let { FlowSavvy.fingerprint(c.origin + "|" + c.token) + ":" + it } }
    init {
        require(retryMillis > 0)
        scope.launch { action {
            val ledger = ledgerStore.read()
            installation = ledger.stringOrNull("installation") ?: UUID.randomUUID().toString().also {
                ledger.put("installation", it); ledgerStore.write(ledger)
            }
            connection = vault.read()?.let(IntersticeConnection::decode)
            if (connection != null && ledger.stringOrNull("cacheBinding") == binding()) {
                val cached = ledger.optJSONArray("cachedTasks") ?: JSONArray()
                _status.update { it.copy(tasks = (0 until cached.length()).map { index ->
                    val row = cached.getJSONObject(index)
                    FlowTask(row.getString("id"), row.getString("title"), scheduled = row.stringOrNull("scheduled"),
                        progress = row.getInt("progress"), duration = row.getInt("minutes"), credentialBinding = binding(), provider = "interstice")
                }, syncedAt = ledger.stringOrNull("cachedAt"), message = "Cached tasks may be stale; refresh when connected.") }
            }
            if (connection != null) { syncLocked(); loadLocked() }
        } }
        scope.launch { sessions.collect { action { if (connection != null) syncLocked() } } }
        scope.launch {
            while (isActive) {
                delay(retryMillis)
                action { if (connection != null) syncLocked() }
            }
        }
    }
    fun connect(origin: String, key: String) = scope.launch { action {
        val candidate = IntersticeConnection.checked(origin, key.trim())
        val remote = transport.request(candidate, "GET", "/api/v1/status", null, emptyMap())
        check(remote.optString("role") == "admin" && remote.optString("mode") != "shadow") { "This workspace is read-only. Connect a writable Interstice installation to track focus time." }
        vault.write(candidate.encoded()); connection = candidate
        _status.update { it.copy(tasks = emptyList()) }
        syncLocked(); loadLocked()
    } }
    fun disconnect() = scope.launch { action { vault.write(null); connection = null; _status.value = FlowStatus(message = "Disconnected. Unsent work stays on this device.") } }
    fun refresh() = scope.launch { action { if (connection != null) { syncLocked(); loadLocked() } } }
    fun complete(task: FlowTask) = scope.launch { action {
        check(task.credentialBinding == binding()) { "Connection changed; select the task again." }
        syncLocked()
        val current = request("GET", "/tasks").getJSONArray("tasks")
        val row = (0 until current.length()).map { current.getJSONObject(it) }.first { it.getString("id") == task.id }
        if (!row.getBoolean("completed")) {
            val ledger = ledgerStore.read()
            check(!ledger.has("completion")) { "Resolve the pending completion before completing another task." }
            ledger.put("completion", JSONObject().put("binding", binding()).put("id", task.id)
                .put("revision", row.getInt("revision")).put("key", UUID.randomUUID().toString()))
            ledgerStore.write(ledger)
            finishCompletion(ledger)
        }
        loadLocked()
    } }
    private suspend fun request(method: String, path: String, body: JSONObject? = null, headers: Map<String, String> = emptyMap()) =
        transport.request(requireNotNull(connection), method, "/api/v1$path", body, headers)
    private suspend fun action(block: suspend () -> Unit) = mutex.withLock {
        _status.update { it.copy(busy = true, message = null) }
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) { _status.update { it.copy(message = (if (it.tasks.isNotEmpty()) "Cached tasks may be stale. " else "") + (e.message?.take(220) ?: "Interstice unavailable; recorded work is saved.")) } }
        finally { _status.update { it.copy(busy = false, connected = connection != null) } }
    }
    private suspend fun finishCompletion(ledger: JSONObject) {
        val intent = ledger.optJSONObject("completion") ?: return
        check(intent.getString("binding") == binding()) { "Pending completion belongs to a different connection." }
        request("POST", "/tasks/${FlowSavvy.encode(intent.getString("id"))}/complete", JSONObject(),
            mapOf("Idempotency-Key" to intent.getString("key"), "If-Match" to intent.getInt("revision").toString()))
        ledger.remove("completion"); ledgerStore.write(ledger)
    }
    private suspend fun syncLocked() {
        val ledger = ledgerStore.read()
        // Replay the exact completion request first; an accepted response may have been lost.
        finishCompletion(ledger)
        val receipts = ledger.optJSONObject("receipts") ?: JSONObject().also { ledger.put("receipts", it) }
        var foreign = false
        val groups = sessions.first().distinctBy { it.id }.filter {
            it.kind == "focus" && it.actualEndEpoch != null && it.note?.startsWith(PREFIX) == true
        }.groupBy {
            val ref = JSONObject(it.note!!.removePrefix(PREFIX))
            FlowSavvy.fingerprint(ref.getString("binding") + "|" + ref.getString("id"))
        }
        for ((receipt, rows) in groups) {
            val ref = JSONObject(rows.first().note!!.removePrefix(PREFIX))
            val minutes = rows.sumOf { (it.activeMillis ?: 0L).coerceAtLeast(0) } / 60000
            if (minutes == 0L) continue
            if (receipts.optLong(receipt, -1) == minutes) continue
            if (ref.getString("binding") != binding()) { foreign = true; continue }
            check(minutes <= 10080) { "Recorded session exceeds the server limit; reconcile before syncing." }
            _status.update { it.copy(pendingMinutes = minutes) }
            val sessionId = FlowSavvy.fingerprint(requireNotNull(installation) + "|" + receipt)
            val key = "focus:$sessionId:$minutes"
            request("POST", "/tasks/${FlowSavvy.encode(ref.getString("id"))}/progress",
                JSONObject().put("session_id", sessionId).put("minutes", minutes), mapOf("Idempotency-Key" to key))
            receipts.put(receipt, minutes); ledgerStore.write(ledger)
        }
        _status.update { it.copy(pendingMinutes = 0, message = if (foreign) "Unsent work belongs to another connection. Reconnect it to sync." else null) }
    }
    private suspend fun loadLocked() {
        val rows = request("GET", "/tasks").getJSONArray("tasks")
        val plan = request("GET", "/schedule")
        val blocks = plan.getJSONArray("blocks")
        val starts = mutableMapOf<String, String>()
        for (i in 0 until blocks.length()) {
            val b = blocks.getJSONObject(i); val id = b.getString("task_id"); val start = b.getString("start")
            if (starts[id] == null || start < starts.getValue(id)) starts[id] = start
        }
        val tasks = (0 until rows.length()).map { rows.getJSONObject(it) }.filter { !it.getBoolean("completed") }.map {
            FlowTask(it.getString("id"), it.getString("title"), scheduled = starts[it.getString("id")],
                progress = it.getInt("progress"), duration = it.getInt("minutes"), credentialBinding = binding(), provider = "interstice")
        }.sortedWith(compareBy<FlowTask> { it.scheduled == null }.thenBy { it.scheduled }.thenBy { it.title })
        val ledger = ledgerStore.read()
        val syncedAt = java.time.Instant.now().toString()
        ledger.put("cacheBinding", binding()).put("cachedAt", syncedAt).put("cachedTasks", JSONArray().apply {
            tasks.forEach { put(JSONObject().put("id", it.id).put("title", it.title).put("scheduled", it.scheduled ?: JSONObject.NULL)
                .put("progress", it.progress).put("minutes", it.duration)) }
        })
        ledgerStore.write(ledger)
        _status.update { it.copy(tasks = tasks, syncedAt = syncedAt, message = if (!plan.optBoolean("calendar_inputs_ready")) "Calendar data is stale or incomplete; check the schedule before relying on it." else it.message) }
    }
    companion object { const val PREFIX = "interstice:v1:" }
}

fun taskSessionNote(link: String): String {
    val prefix = if (runCatching { JSONObject(link).optString("provider") }.getOrNull() == "interstice") Interstice.PREFIX else FlowSavvy.NOTE_PREFIX
    return prefix + link
}
