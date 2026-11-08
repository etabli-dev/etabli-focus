// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0
import Foundation

@MainActor
final class FakeFlowServer {
    var progress = 10
    var completed = false
    var putCount = 0
    var completeCount = 0
    var loseNextResponse = false
    var failBeforeNextWrite = false
    var putBodies: [[String: FlowJSON]] = []
    var requests: [URLRequest] = []
    var extra: [String: FlowJSON] = ["title": .string("Original title"), "notes": .string("<p>Preserved notes</p>"), "customColor": .string("#123456"), "durationMinutes": .number(90), "priority": .string("high"), "blockedByIds": .array([.string("dependency")]), "timeZone": .string("Europe/Berlin")]
    func send(_ request: URLRequest) async throws -> (Data, Int) {
        requests.append(request)
        var body = extra
        body["id"] = .string("task-1"); body["itemType"] = .string("task")
        body["progressMinutes"] = .number(Double(progress)); body["completed"] = .bool(completed)
        body["lastModified"] = .string("2026-11-08T12:00:00Z")
        if request.httpMethod == "PUT" {
            if failBeforeNextWrite { failBeforeNextWrite = false; throw URLError(.notConnectedToInternet) }
            let data = try JSONDecoder().decode(FlowJSON.self, from: request.httpBody!).object
            putBodies.append(data); putCount += 1; progress = data["progressMinutes"]!.int!
            if loseNextResponse { loseNextResponse = false; throw URLError(.networkConnectionLost) }
            body["progressMinutes"] = .number(Double(progress))
        }
        if request.httpMethod == "POST" { completeCount += 1; completed = true; body["completed"] = .bool(true) }
        return (try JSONEncoder().encode(FlowJSON.object(body)), 200)
    }
}

@main struct FlowSavvyChecks {
    @MainActor static func main() async throws {
        var checks = 0
        let key = "test-key"
        let installationID = "test-installation"
        let link = FlowTaskLink(taskID: "task-1", occurrenceDate: "2026-11-08", title: "Original title", credentialID: FlowTaskLink.credentialID(key), installationID: installationID)
        let record = FlowWorkRecord(sessionID: "session-1", link: link, seconds: 1500)
        let server = FakeFlowServer()
        var persisted = FlowLedger(installationID: installationID)
        let client = FlowSavvyClient(transport: { try await server.send($0) })
        let sync = FlowSynchronizer(ledger: FlowLedger(installationID: installationID), client: client) { persisted = $0 }
        try await sync.synchronize([record, record], key: key)
        assert(server.progress == 35 && server.putCount == 1 && server.completeCount == 0)
        assert(server.putBodies[0]["notes"] == server.extra["notes"])
        assert(server.putBodies[0]["blockedByIds"] == server.extra["blockedByIds"])
        assert(server.putBodies[0]["customColor"] == server.extra["customColor"])
        assert(server.putBodies[0]["id"] == nil && server.putBodies[0]["completed"] == nil)
        let putURL = server.requests.first { $0.httpMethod == "PUT" }!.url!.absoluteString
        assert(putURL.contains("occurrenceDate=2026-11-08") && putURL.contains("scope=thisOccurrence"))
        checks += 1
        try await sync.synchronize([record], key: key)
        assert(server.putCount == 1); checks += 1
        let restored = FlowSynchronizer(ledger: try JSONDecoder().decode(FlowLedger.self, from: JSONEncoder().encode(persisted)), client: client) { persisted = $0 }
        try await restored.synchronize([record], key: key)
        assert(server.putCount == 1); checks += 1
        let short = FlowWorkRecord(sessionID: "partial", link: link, seconds: 35)
        try await restored.synchronize([record, short], key: key)
        assert(server.putCount == 1)
        let short2 = FlowWorkRecord(sessionID: "partial2", link: link, seconds: 30)
        try await restored.synchronize([record, short, short2], key: key)
        assert(server.progress == 36 && server.putCount == 2); checks += 1
        server.loseNextResponse = true
        let second = FlowWorkRecord(sessionID: "session-2", link: link, seconds: 1500)
        do { try await restored.synchronize([record, short, short2, second], key: key); fatalError("Expected lost reply") }
        catch is URLError { }
        assert(server.progress == 61 && persisted.receipts[link.ledgerID]?.intent != nil)
        let retry = FlowSynchronizer(ledger: persisted, client: client) { persisted = $0 }
        try await retry.synchronize([record, short, short2, second], key: key)
        assert(server.progress == 61 && server.putCount == 3 && persisted.receipts[link.ledgerID]?.intent == nil); checks += 1
        var conflicting = FlowLedger(installationID: installationID)
        conflicting.receipts[link.ledgerID] = FlowReceipt(acknowledgedSeconds: 0, intent: FlowIntent(baseline: 10, target: 35, totalSeconds: 1500))
        let conflict = FlowSynchronizer(ledger: conflicting, client: client) { _ in }
        do { try await conflict.synchronize([record], key: key); fatalError("Expected conflict") }
        catch is FlowSavvyError { }
        assert(server.putCount == 3); checks += 1
        let wrongAccount = FlowSynchronizer(ledger: FlowLedger(installationID: installationID), client: client) { _ in }
        let requestCount = server.requests.count
        do { try await wrongAccount.synchronize([record], key: "other-key"); fatalError("Expected account mismatch") }
        catch is FlowSavvyError { }
        assert(server.requests.count == requestCount); checks += 1
        let restoredWithoutLedger = FlowSynchronizer(client: client) { _ in }
        do { try await restoredWithoutLedger.synchronize([record], key: key); fatalError("Expected restore guard") }
        catch is FlowSavvyError { }
        assert(server.requests.count == requestCount); checks += 1
        let diskFailure = FlowSynchronizer(ledger: FlowLedger(installationID: installationID), client: client) { _ in throw CocoaError(.fileWriteNoPermission) }
        do { try await diskFailure.synchronize([record], key: key); fatalError("Expected persistence failure") }
        catch is CocoaError { }
        assert(server.putCount == 3); checks += 1
        server.completed = true
        let completed = FlowSynchronizer(ledger: FlowLedger(installationID: installationID), client: client) { _ in }
        do { try await completed.synchronize([record], key: key); fatalError("Expected completed task guard") }
        catch is FlowSavvyError { }
        assert(server.putCount == 3); checks += 1
        var pages = 0
        let listing = FlowSavvyClient { request in
            var result: [String: FlowJSON]
            if request.url!.path == "/api/schedule" {
                result = ["scheduleItems": .array([.object(["itemId": .string("repeating"), "itemType": .string("task"), "occurrenceDate": .string("2026-11-08"), "startTime": .string("2026-11-08T13:00:00")]), .object(["itemId": .string("repeating"), "itemType": .string("task"), "occurrenceDate": .string("2026-11-08"), "startTime": .string("2026-11-08T14:00:00")])])]
            } else {
                pages += 1
                if pages == 1 { result = ["nextPageToken": .string("page2"), "items": .array([.object(["id": .string("repeating"), "recurrenceRule": .string("FREQ=DAILY"), "title": .string("Scheduled")])])] }
                else { result = ["items": .array([.object(["id": .string("single"), "title": .string("Unscheduled")])])] }
            }
            return (try JSONEncoder().encode(FlowJSON.object(result)), 200)
        }
        let tasks = try await listing.tasks(key: key, installationID: installationID)
        assert(pages == 2 && tasks.count == 2 && tasks[0].link.occurrenceDate == "2026-11-08" && tasks[1].link.taskID == "single"); checks += 1
        try await client.complete(link, key: key)
        assert(server.completeCount == 1); checks += 1
        let oldState = "{\"phase\":\"idle\",\"kind\":\"focus\",\"totalSeconds\":0,\"blocksCompletedInSet\":0}"
        let state = try JSONDecoder().decode(TimerState.self, from: Data(oldState.utf8))
        assert(state.flowSavvyLink == nil)
        var linked = state; linked.flowSavvyLink = link.encoded
        let decoded = try JSONDecoder().decode(TimerState.self, from: JSONEncoder().encode(linked))
        assert(FlowTaskLink.decode(decoded.flowSavvyLink) == link); checks += 1
        for appliedBeforeFailure in [false, true] {
            let recoveryServer = FakeFlowServer()
            recoveryServer.loseNextResponse = appliedBeforeFailure
            recoveryServer.failBeforeNextWrite = !appliedBeforeFailure
            var saved = FlowLedger(installationID: installationID)
            let recoveryClient = FlowSavvyClient(transport: { try await recoveryServer.send($0) })
            let initial = FlowSynchronizer(ledger: saved, client: recoveryClient) { saved = $0 }
            do { try await initial.synchronize([record], key: key); fatalError("Expected interrupted write") }
            catch is URLError { }
            let afterRestart = FlowSynchronizer(ledger: saved, client: recoveryClient) { saved = $0 }
            try await afterRestart.synchronize([record, second], key: key)
            assert(recoveryServer.progress == 60 && saved.receipts[link.ledgerID]?.acknowledgedSeconds == 3000)
            assert(saved.receipts[link.ledgerID]?.intent == nil && recoveryServer.putCount == 2)
            try await recoveryClient.complete(link, key: key)
            assert(recoveryServer.completeCount == 1 && recoveryServer.progress == 60)
            checks += 1
        }
        print("FlowSavvy: \(checks) scenario groups passed (mock transport; no account writes)")
    }
}
