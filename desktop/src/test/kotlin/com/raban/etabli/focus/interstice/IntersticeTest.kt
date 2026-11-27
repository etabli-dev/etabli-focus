// Copyright 2026 R. Heller
// SPDX-License-Identifier: AGPL-3.0-only
package com.raban.etabli.focus.interstice

import com.raban.etabli.focus.data.SessionEntity
import com.raban.etabli.focus.flowsavvy.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.*

class IntersticeTest {
    class Ledger : LedgerStore {
        var value = JSONObject()
        var failWrites = false
        override fun read() = JSONObject(value.toString())
        override fun write(value: JSONObject) { check(!failWrites); this.value = JSONObject(value.toString()) }
    }
    class Vault : KeyVault {
        var value: String? = null
        override val description = "Test vault"
        override fun read() = value
        override fun write(key: String?) { value = key }
    }
    class Remote : IntersticeTransport {
        val calls = mutableListOf<String>()
        val receipts = mutableMapOf<String, JSONObject>()
        var progress = 0L
        var complete = false
        var loseResponse = false
        var mode = "test"
        var offline = false
        var revision = 1
        var metadata = JSONObject().put("list","Forschung").put("category","discussion").put("person","Alex").put("communication_mode","phone").put("tags",JSONArray().put("budget"))
        var edits = 0
        var completionConflict = false
        var scheduleUnavailable = false
        override suspend fun request(connection: IntersticeConnection, method: String, path: String, body: JSONObject?, headers: Map<String,String>): JSONObject {
            check(!offline) { "Offline" }
            calls += "$method $path"
            if (path.endsWith("/status")) return JSONObject().put("role","admin").put("mode",mode)
            if (path.endsWith("/schedule") && scheduleUnavailable) throw IntersticeHTTPError(503)
            if (path.endsWith("/schedule")) return JSONObject().put("blocks",JSONArray()).put("calendar_inputs_ready",true)
            if (method == "GET") return JSONObject().put("tasks",JSONArray().put(JSONObject(metadata.toString()).put("id","one").put("title","Task").put("minutes",60).put("progress",progress).put("completed",complete).put("revision",revision)))
            val key = headers.getValue("Idempotency-Key")
            if (receipts.containsKey(key)) return receipts.getValue(key)
            if (method == "PATCH") {
                if(headers["If-Match"] != revision.toString()) throw IntersticeHTTPError(409)
                metadata = JSONObject(body.toString()); revision++; edits++
            }
            if (path.endsWith("/complete") && completionConflict) throw IntersticeHTTPError(409)
            if (path.endsWith("/progress")) progress = body!!.getLong("minutes")
            if (path.endsWith("/complete")) complete = true
            val result = JSONObject().put("progress",progress).put("completed",complete)
            receipts[key] = result
            if (loseResponse) { loseResponse=false; throw java.io.IOException("Response lost") }
            return result
        }
    }
    class Fixture(retryMillis: Long = 60_000) : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val history = MutableStateFlow<List<SessionEntity>>(emptyList())
        val ledger=Ledger();val vault=Vault();val remote=Remote()
        val service=Interstice(vault,ledger,history,scope,remote,retryMillis)
        suspend fun connect() { service.connect("https://tasks.example/workspace","test-key").join() }
        fun row(id:String,millis:Long) = SessionEntity(id,"category",0,millis,millis,false,"focus",
            Interstice.PREFIX+FlowTask("one","Task",provider="interstice").link(service.binding()!!),millis)
        override fun close() { scope.cancel() }
    }
    @Test fun cumulativeFractionsAndLostResponsesDoNotDuplicateCredit() = runBlocking {
        Fixture().use { f ->
            f.connect();f.remote.loseResponse=true
            val first=f.row("first",30000)
            f.history.value=listOf(first,f.row("second",30000),first)
            yield()
            assertEquals(1L,f.remote.progress)
            f.service.refresh().join()
            assertEquals(1L,f.remote.progress)
            assertEquals(1,f.remote.receipts.size)
            assertNull(f.service.status.value.message)
        }
    }
    @Test fun sourceHistoryAndOtherCredentialBindingsAreNotReplayed(): Unit = runBlocking {
        Fixture().use { f ->
            f.connect()
            val row=f.row("one",60000)
            f.service.connect("https://tasks.example/workspace","new-key").join()
            f.history.value=listOf(row,row.copy(id="source",note=FlowSavvy.NOTE_PREFIX+"{}"))
            f.service.refresh().join()
            assertEquals(0L,f.remote.progress)
            assertNotNull(f.service.status.value.message)
        }
    }
    @Test fun completionRetainsExactIntentAcrossResponseLoss() = runBlocking {
        Fixture().use { f ->
            f.connect();val task=f.service.status.value.tasks.first()
            f.remote.loseResponse=true;f.service.complete(task).join()
            assertTrue(f.remote.complete);assertTrue(f.ledger.value.has("completion"))
            f.service.refresh().join()
            assertFalse(f.ledger.value.has("completion"));assertEquals(1,f.remote.receipts.size)
        }
    }
    @Test fun readOnlyWorkspaceCannotBeConnectedForTimerWrites() = runBlocking {
        Fixture().use { f ->
            f.remote.mode="shadow";f.connect()
            assertNull(f.vault.value);assertFalse(f.service.status.value.connected)
        }
    }
    @Test fun unsafeEndpointsAndProviderMixingAreRejected() {
        for (url in listOf("http://tasks.example","https://user:pass@tasks.example","https://tasks.example/?key=x","https://tasks.example/#fragment")) {
            assertFails { IntersticeConnection.checked(url,"key") }
        }
        assertTrue(taskSessionNote(FlowTask("one","Task",provider="interstice").link("binding")).startsWith(Interstice.PREFIX))
        assertTrue(taskSessionNote("legacy").startsWith(FlowSavvy.NOTE_PREFIX))
    }
    @Test fun reconnectRetriesWithoutAHistoryChangeOrManualRefresh() = runBlocking {
        Fixture(20).use { f ->
            f.connect();f.remote.offline=true
            f.history.value=listOf(f.row("offline-session",60000));yield()
            assertEquals(0L,f.remote.progress)
            f.remote.offline=false
            withTimeout(2000) { while(f.remote.progress==0L) delay(10) }
            assertEquals(1L,f.remote.progress)
            assertEquals(1,f.remote.receipts.size)
        }
    }
    @Test fun changedTaskNeedsFreshConfirmationWithoutBlockingFutureSync() = runBlocking {
        Fixture().use { f ->
            f.connect();f.remote.completionConflict=true
            f.service.complete(f.service.status.value.tasks.first()).join()
            assertFalse(f.remote.complete);assertFalse(f.ledger.value.has("completion"))
            assertContains(f.service.status.value.message.orEmpty(),"confirm completion again")
            f.remote.completionConflict=false;f.service.refresh().join()
            f.service.complete(f.service.status.value.tasks.first()).join()
            assertTrue(f.remote.complete)
        }
    }
    @Test fun taskContextSurvivesScheduleFailureAndOfflineRestart() = runBlocking {
        Fixture().use { f ->
            f.remote.scheduleUnavailable = true; f.connect()
            val task = f.service.status.value.tasks.single()
            assertEquals("discussion", task.category); assertEquals("Forschung", task.workList)
            assertContains(task.contextLabel(), "Alex"); assertContains(task.contextLabel(), "Phone call")
            assertContains(task.contextLabel(), "#budget")
            assertContains(f.service.status.value.message.orEmpty(), "Schedule unavailable")
            f.remote.offline = true
            val restartedScope = CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
            try {
                val restarted = Interstice(f.vault,f.ledger,f.history,restartedScope,f.remote)
                assertEquals(task, restarted.status.value.tasks.single())
            } finally { restartedScope.cancel() }
        }
    }

    private fun details() = IntersticeDetails("Lehre", "discussion", "Sam", "email", "course, budget")
    @Test fun editResponseLossReplaysPersistedIntentAfterRestart() = runBlocking {
        Fixture().use { f ->
            f.connect(); val task=f.service.status.value.tasks.single();f.remote.loseResponse=true
            var saved=false;f.service.saveDetails(task,details()) { saved=true }.join()
            assertFalse(saved);assertTrue(f.ledger.value.has("edit"));assertEquals(2,f.remote.revision)
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
            try {
                val restarted=Interstice(f.vault,f.ledger,f.history,scope,f.remote)
                assertFalse(f.ledger.value.has("edit"));assertEquals(1,f.remote.edits)
                assertEquals("Sam",restarted.status.value.tasks.single().person)
                assertEquals(2,restarted.status.value.tasks.single().revision)
            } finally { scope.cancel() }
        }
    }
    @Test fun staleEditorCannotOverwriteNewerMetadata() = runBlocking {
        Fixture().use { f ->
            f.connect();val task=f.service.status.value.tasks.single();f.remote.revision=2
            var saved=false;f.service.saveDetails(task,details()) { saved=true }.join()
            assertFalse(saved);assertFalse(f.ledger.value.has("edit"));assertEquals("Alex",f.remote.metadata.getString("person"))
            assertContains(f.service.status.value.message.orEmpty(),"Task changed")
            f.service.refresh().join();f.service.saveDetails(f.service.status.value.tasks.single(),details()) { saved=true }.join()
            assertTrue(saved);assertEquals("Lehre",f.service.status.value.tasks.single().workList)
        }
    }
    @Test fun failedPersistencePreventsEditAndPendingEditsStayWithTheirConnection() = runBlocking {
        Fixture().use { f ->
            f.connect();val task=f.service.status.value.tasks.single();f.ledger.failWrites=true
            f.service.saveDetails(task,details()) {}.join();assertEquals(0,f.remote.edits)
            f.ledger.failWrites=false;f.remote.offline=true
            f.service.saveDetails(task,details()) {}.join();assertTrue(f.ledger.value.has("edit"))
            f.remote.offline=false;f.service.connect("https://tasks.example/another","another-key").join()
            assertEquals(0,f.remote.edits);assertTrue(f.ledger.value.has("edit"))
            f.service.connect("https://tasks.example/workspace","test-key").join()
            assertEquals("Sam",f.remote.metadata.getString("person"));assertFalse(f.ledger.value.has("edit"))
        }
    }
    @Test fun transportSendsPatchWithBoundedDetailsAndRevision() = runBlocking {
        val client=okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            val request=chain.request()
            assertEquals("PATCH",request.method);assertEquals("7",request.header("If-Match"));assertEquals("receipt",request.header("Idempotency-Key"))
            val buffer=okio.Buffer();request.body!!.writeTo(buffer)
            assertEquals("Sam",JSONObject(buffer.readUtf8()).getString("person"))
            okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body(okhttp3.ResponseBody.create(null,"{}")).build()
        }.build()
        IntersticeHttp(client).request(IntersticeConnection.checked("https://tasks.example","key"),"PATCH","/api/v1/tasks/one",details().body(),mapOf("If-Match" to "7","Idempotency-Key" to "receipt"))
        assertFails { details().copy(category="tenth").body() };assertFails { details().copy(person="x".repeat(201)).body() };Unit
    }

    @Test fun pendingEditCannotBeReplacedAndCanBeRetriedUnchanged() = runBlocking {
        Fixture().use { f ->
            f.connect(); val task=f.service.status.value.tasks.single(); f.remote.offline=true
            f.service.saveDetails(task,details()) {}.join()
            val pending=f.ledger.value.getJSONObject("edit").toString()
            f.service.saveDetails(task,details().copy(person="Different")) {}.join()
            assertEquals(pending,f.ledger.value.getJSONObject("edit").toString())
            assertContains(f.service.status.value.message.orEmpty(),"earlier edit")
            f.remote.offline=false;var saved=false
            f.service.saveDetails(task,details()) { saved=true }.join()
            assertTrue(saved);assertEquals(1,f.remote.edits);assertFalse(f.ledger.value.has("edit"))
        }
    }

}
