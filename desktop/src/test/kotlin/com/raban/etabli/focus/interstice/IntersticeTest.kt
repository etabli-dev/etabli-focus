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
        override fun read() = JSONObject(value.toString())
        override fun write(value: JSONObject) { this.value = JSONObject(value.toString()) }
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
        override suspend fun request(connection: IntersticeConnection, method: String, path: String, body: JSONObject?, headers: Map<String,String>): JSONObject {
            calls += "$method $path"
            if (path.endsWith("/status")) return JSONObject().put("role","admin").put("mode",mode)
            if (path.endsWith("/schedule")) return JSONObject().put("blocks",JSONArray()).put("calendar_inputs_ready",true)
            if (method == "GET") return JSONObject().put("tasks",JSONArray().put(JSONObject().put("id","one").put("title","Task").put("minutes",60).put("progress",progress).put("completed",complete).put("revision",1)))
            val key = headers.getValue("Idempotency-Key")
            if (receipts.containsKey(key)) return receipts.getValue(key)
            if (path.endsWith("/progress")) progress = body!!.getLong("minutes")
            if (path.endsWith("/complete")) complete = true
            val result = JSONObject().put("progress",progress).put("completed",complete)
            receipts[key] = result
            if (loseResponse) { loseResponse=false; throw java.io.IOException("Response lost") }
            return result
        }
    }
    class Fixture : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val history = MutableStateFlow<List<SessionEntity>>(emptyList())
        val ledger=Ledger();val vault=Vault();val remote=Remote()
        val service=Interstice(vault,ledger,history,scope,remote)
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
    @Test fun sourceHistoryAndOtherCredentialBindingsAreNotReplayed() = runBlocking {
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
}
