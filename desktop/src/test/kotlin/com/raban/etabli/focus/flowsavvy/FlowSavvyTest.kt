package com.raban.etabli.focus.flowsavvy

import com.raban.etabli.focus.data.SessionEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import kotlin.test.*

class FlowSavvyTest {
    private class MemoryLedger : LedgerStore {
        var json = JSONObject()
        var fail = false
        override fun read() = JSONObject(json.toString())
        override fun write(value: JSONObject) { check(!fail) { "Disk unavailable" }; json = JSONObject(value.toString()) }
    }
    private class Vault : KeyVault {
        var key: String? = null
        override val description = "test"
        override fun read() = key
        override fun write(key: String?) { this.key = key }
    }
    private class Remote : FlowTransport {
        var row = JSONObject("""{"id":"task/1","itemType":"task","title":"Write","progressMinutes":10,"durationMinutes":120,"completed":false,"notes":"<p>Keep this</p>","priority":"high","blockedByIds":["other"],"customColor":"#123456"}""")
        val puts = mutableListOf<Pair<String, JSONObject>>()
        var schedule = org.json.JSONArray()
        var completed = 0
        var progressAtCompletion: Int? = null
        var loseResponse = false
        var unavailable = false
        override suspend fun request(key: String, method: String, path: String, body: JSONObject?): JSONObject {
            check(!unavailable) { "Offline" }
            if (method == "GET" && path.startsWith("/api/items?")) return JSONObject().put("items", org.json.JSONArray().put(row))
            if (method == "GET" && path.startsWith("/api/schedule")) return JSONObject().put("scheduleItems", schedule)
            if (method == "GET") return JSONObject(row.toString())
            if (method == "POST") { completed++; progressAtCompletion = row.getInt("progressMinutes"); return row }
            puts += path to JSONObject(body.toString())
            body!!.keys().forEach { row.put(it, body.get(it)) }
            if (loseResponse) { loseResponse = false; throw java.io.IOException("Connection lost") }
            return row
        }
    }
    private class Fixture : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val history = MutableStateFlow<List<SessionEntity>>(emptyList())
        val ledger = MemoryLedger()
        val vault = Vault()
        val remote = Remote()
        val service = FlowSavvy(vault, ledger, history, scope, remote)
        suspend fun connect() { service.connect("test-key").join() }
        fun row(id: String, millis: Long, title: String = "Write", kind: String = "focus") = SessionEntity(
            id, "category", 0, millis, millis, false, kind,
            FlowSavvy.NOTE_PREFIX + FlowTask("task/1", title).link(service.binding()!!), millis,
        )
        override fun close() { scope.cancel() }
    }
    @Test fun aggregatesFractionsAndIgnoresBreaksAndDuplicateSessionIDs() = runBlocking {
        Fixture().use { f ->
            f.connect()
            val first = f.row("one", 30000)
            f.history.value = listOf(first)
            f.service.refresh().join()
            assertEquals(0, f.remote.puts.size)
            f.history.value = listOf(first, first, f.row("two", 30000, "Renamed"), f.row("break", 600000, kind = "break"))
            f.service.refresh().join()
            assertEquals(1, f.remote.puts.size)
            assertEquals(11, f.remote.row.getInt("progressMinutes"))
            f.service.refresh().join()
            assertEquals(1, f.remote.puts.size)
            assertEquals(0, f.remote.completed)
            assertEquals("<p>Keep this</p>", f.remote.puts.single().second.getString("notes"))
            assertEquals("high", f.remote.puts.single().second.getString("priority"))
            assertFalse(f.remote.puts.single().second.has("completed"))
        }
    }
    @Test fun responseLossUsesDurableTargetWithoutAddingTwice() = runBlocking {
        Fixture().use { f ->
            f.connect(); f.remote.loseResponse = true
            f.history.value = listOf(f.row("one", 1500000))
            yield()
            assertEquals(35, f.remote.row.getInt("progressMinutes"))
            assertNotNull(f.service.status.value.message)
            f.service.refresh().join()
            assertEquals(1, f.remote.puts.size)
            assertEquals(35, f.remote.row.getInt("progressMinutes"))
            assertNull(f.service.status.value.message)
        }
    }
    @Test fun completionDrainsNewWorkAfterRecoveringLostResponse() = runBlocking {
        Fixture().use { f ->
            f.connect()
            val task = f.service.status.value.tasks.first()
            f.remote.loseResponse = true
            val first = f.row("one", 60000)
            f.history.value = listOf(first); yield()
            assertEquals(11, f.remote.row.getInt("progressMinutes"))
            f.remote.unavailable = true
            f.history.value = listOf(first, f.row("two", 120000)); yield()
            f.remote.unavailable = false
            f.service.complete(task).join()
            assertEquals(13, f.remote.row.getInt("progressMinutes"))
            assertEquals(13, f.remote.progressAtCompletion)
            assertEquals(2, f.remote.puts.size)
            assertEquals(1, f.remote.completed)
            assertEquals(0L, f.service.status.value.pendingMinutes)
        }
    }
    @Test fun conflictingRetryDoesNotOverwriteOtherDeviceProgress() = runBlocking {
        Fixture().use { f ->
            f.connect(); f.remote.loseResponse = true
            f.history.value = listOf(f.row("one", 60000)); yield()
            f.remote.row.put("progressMinutes", 17)
            f.service.refresh().join()
            assertEquals(1, f.remote.puts.size)
            assertEquals(17, f.remote.row.getInt("progressMinutes"))
            assertTrue(f.service.status.value.message!!.contains("changed elsewhere"))
            f.service.complete(f.service.status.value.tasks.first()).join()
            assertEquals(0, f.remote.completed)
        }
    }
    @Test fun changingCredentialsDoesNotUploadOldAccountWork() = runBlocking {
        Fixture().use { f ->
            f.connect(); f.ledger.fail = true
            val row = f.row("one", 60000)
            f.history.value = listOf(row); yield()
            assertEquals(0, f.remote.puts.size)
            f.ledger.fail = false
            f.service.connect("different-key").join()
            assertEquals(0, f.remote.puts.size)
            assertTrue(f.service.status.value.message!!.contains("different API key"))
            f.service.connect("test-key").join()
            assertEquals(1, f.remote.puts.size)
        }
    }
    @Test fun lostLedgerDoesNotReplayRestoredHistory() = runBlocking {
        Fixture().use { f ->
            f.connect()
            val old = f.row("one", 60000)
            f.history.value = listOf(old); yield()
            assertEquals(1, f.remote.puts.size)
            f.ledger.json = JSONObject()
            f.service.connect("test-key").join()
            assertEquals(1, f.remote.puts.size)
            assertTrue(f.service.status.value.message!!.contains("different API key"))
        }
    }
    @Test fun taskCompletionRequiresSeparateAction() = runBlocking {
        Fixture().use { f ->
            f.connect(); f.history.value = listOf(f.row("one", 60000)); yield()
            assertEquals(0, f.remote.completed)
            f.service.complete(f.service.status.value.tasks.first()).join()
            assertEquals(1, f.remote.completed)
        }
    }
    @Test fun recurringScheduleSelectsOccurrenceAndUpdatesOnlyThatOccurrence() = runBlocking {
        Fixture().use { f ->
            f.remote.row.put("recurrenceRule", "FREQ=DAILY")
            f.remote.schedule = org.json.JSONArray("""[
              {"itemId":"task/1","itemType":"task","occurrenceDate":"2026-11-09","startTime":"2026-11-09T09:00:00"},
              {"itemId":"task/1","itemType":"task","occurrenceDate":"2026-11-09","startTime":"2026-11-09T11:00:00"},
              {"itemId":"task/1","itemType":"task","occurrenceDate":"2026-11-10","startTime":"2026-11-10T09:00:00"}
            ]""")
            f.connect()
            assertEquals(2, f.service.status.value.tasks.size)
            assertEquals("2026-11-09T09:00:00", f.service.status.value.tasks.first().scheduled)
            val task = f.service.status.value.tasks.first()
            f.history.value = listOf(f.row("one", 60000).copy(note = FlowSavvy.NOTE_PREFIX + task.link(f.service.binding()!!)))
            f.service.refresh().join()
            assertEquals("/api/tasks/task%2F1?occurrenceDate=2026-11-09&scope=thisOccurrence", f.remote.puts.single().first)
            assertEquals("FREQ=DAILY", f.remote.puts.single().second.getString("recurrenceRule"))
        }
    }

    @Test fun staleCompletionAfterCredentialSwitchDoesNotWrite() = runBlocking {
        Fixture().use { f ->
            f.connect()
            val selected = f.service.status.value.tasks.first()
            f.service.connect("other-account-key").join()
            f.service.complete(selected).join()
            assertEquals(0, f.remote.completed)
            assertTrue(f.service.status.value.message!!.contains("connection changed"))
        }
    }

}
