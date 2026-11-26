// Copyright 2026 R. Heller
// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import Observation
import Security

@MainActor @Observable
final class IntersticeStore {
    private(set) var tasks: [FlowTask] = []
    private(set) var connected = false
    private(set) var busy = false
    private(set) var message: String?
    private(set) var pendingMinutes = 0
    private var connection: IntersticeConnection?
    private var ledger = IntersticeLedger()
    private var file: URL?
    private let client = IntersticeClient()

    init() {
        do {
            let folder = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
                appropriateFor: nil, create: true).appendingPathComponent("Interstice", isDirectory: true)
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            var location = folder; var attributes = URLResourceValues(); attributes.isExcludedFromBackup = true
            try location.setResourceValues(attributes)
            let path = folder.appendingPathComponent("receipts.json")
            if FileManager.default.fileExists(atPath: path.path) {
                ledger = try JSONDecoder().decode(IntersticeLedger.self, from: Data(contentsOf: path))
            }
            file = path; try save()
            if let secret = try IntersticeKeychain.read() {
                let decoded = try JSONDecoder().decode(IntersticeConnection.self, from: Data(secret.utf8))
                connection = try IntersticeConnection(origin: decoded.origin, token: decoded.token)
            }
            connected = connection != nil
            if ledger.cacheBinding == connection?.binding { tasks = ledger.cachedTasks }
        } catch { file = nil; message = "Saved connection or receipt history could not be read. Sync is paused to protect recorded work." }
    }
    private func save() throws {
        guard let file else { throw FlowSavvyError(message: "Receipt storage is unavailable.") }
        try JSONEncoder().encode(ledger).write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
    func connect(_ origin: String, key: String) async {
        guard !busy, file != nil else { return }
        busy = true; defer { busy = false }
        do {
            let candidate = try IntersticeConnection(origin: origin, token: key.trimmingCharacters(in: .whitespacesAndNewlines))
            let status = try await client.request("GET", "/api/v1/status", connection: candidate)
            guard status["role"]?.string == "admin", status["mode"]?.string != "shadow" else {
                throw FlowSavvyError(message: "This workspace is read-only. Connect a writable installation to track focus time.")
            }
            try IntersticeKeychain.write(String(decoding: JSONEncoder().encode(candidate), as: UTF8.self))
            connection = candidate; connected = true; tasks = []; message = nil
            try await load()
        } catch { message = error.localizedDescription }
    }
    func disconnect() {
        guard !busy else { return }
        do { try IntersticeKeychain.write(nil); connection = nil; connected = false; tasks = [] }
        catch { message = error.localizedDescription }
    }
    func refresh() async {
        guard !busy, connection != nil, file != nil else { return }
        busy = true; defer { busy = false }
        do { try await finishCompletion(); try await load(); message = nil }
        catch { message = error.localizedDescription }
    }
    func sync(_ records: [FlowWorkRecord]) async {
        guard !busy, connection != nil, file != nil else { return }
        busy = true; defer { busy = false }
        do { try await synchronize(records); message = nil }
        catch { message = error.localizedDescription }
    }
    private func synchronize(_ records: [FlowWorkRecord]) async throws {
        guard let connection else { return }
        try await finishCompletion()
        let unique = Dictionary(records.map { ($0.sessionID, $0) }, uniquingKeysWith: { a, _ in a })
        let groups = Dictionary(grouping: unique.values.filter { $0.link.provider == "interstice" }, by: { $0.link.ledgerID })
        var foreign = false
        for (group, rows) in groups {
            let link = rows[0].link
            let seconds = rows.reduce(0.0) { $0 + max(0, $1.seconds) }
            guard seconds.isFinite, seconds >= 0, seconds / 60 <= 10080 else { throw FlowSavvyError(message: "Recorded work exceeds the server limit; reconcile before syncing.") }
            let minutes = Int(seconds / 60)
            if minutes == 0 || ledger.receipts[group] == minutes { continue }
            guard link.credentialID == connection.binding, link.installationID == ledger.installationID else { foreign = true; continue }
            pendingMinutes = minutes
            let sessionID = FlowTaskLink.credentialID(ledger.installationID + "|" + group)
            _ = try await client.request("POST", "/api/v1/tasks/\(IntersticeClient.component(link.taskID))/progress", connection: connection,
                body: ["session_id": .string(sessionID), "minutes": .number(Double(minutes))],
                headers: ["Idempotency-Key": "focus:\(sessionID):\(minutes)"])
            ledger.receipts[group] = minutes; try save()
        }
        pendingMinutes = 0
        if foreign { throw FlowSavvyError(message: "Unsent work belongs to another connection. Reconnect it to sync.") }
    }
    func complete(_ task: FlowTask, work: [FlowWorkRecord]) async {
        guard !busy, let connection, file != nil else { return }
        busy = true; defer { busy = false }
        do {
            guard task.link.credentialID == connection.binding, task.link.installationID == ledger.installationID else {
                throw FlowSavvyError(message: "Connection changed; select the task again.")
            }
            try await synchronize(work)
            let response = try await client.request("GET", "/api/v1/tasks", connection: connection)
            guard let row = response["tasks"]?.array.map(\.object).first(where: { $0["id"]?.string == task.link.taskID }), let revision = row["revision"]?.int else {
                throw FlowSavvyError(message: "Task is no longer available.")
            }
            if row["completed"]?.bool != true {
                ledger.completion = IntersticeCompletion(binding: connection.binding, taskID: task.link.taskID, revision: revision, key: UUID().uuidString)
                try save(); try await finishCompletion()
            }
            try await load(); message = nil
        } catch { message = error.localizedDescription }
    }
    private func finishCompletion() async throws {
        guard let intent = ledger.completion, let connection else { return }
        guard intent.binding == connection.binding else { throw FlowSavvyError(message: "Pending completion belongs to another connection.") }
        _ = try await client.request("POST", "/api/v1/tasks/\(IntersticeClient.component(intent.taskID))/complete", connection: connection,
            body: [:], headers: ["Idempotency-Key": intent.key, "If-Match": String(intent.revision)])
        ledger.completion = nil; try save()
    }
    private func load() async throws {
        guard let connection else { return }
        let response = try await client.request("GET", "/api/v1/tasks", connection: connection)
        let plan = try await client.request("GET", "/api/v1/schedule", connection: connection)
        var starts: [String: String] = [:]
        for block in plan["blocks"]?.array ?? [] {
            if let id = block.object["task_id"]?.string, let start = block.object["start"]?.string {
                starts[id] = min(starts[id] ?? start, start)
            }
        }
        tasks = (response["tasks"]?.array ?? []).compactMap { value in
            let row = value.object
            guard row["completed"]?.bool != true, let id = row["id"]?.string, let title = row["title"]?.string else { return nil }
            let link = FlowTaskLink(taskID: id, occurrenceDate: nil, title: title, credentialID: connection.binding,
                installationID: ledger.installationID, provider: "interstice")
            return FlowTask(link: link, progressMinutes: row["progress"]?.int, durationMinutes: row["minutes"]?.int, scheduledStart: starts[id])
        }.sorted { ($0.scheduledStart ?? "~", $0.title) < ($1.scheduledStart ?? "~", $1.title) }
        ledger.cachedTasks = tasks; ledger.cacheBinding = connection.binding; try save()
    }
}

struct IntersticeKeychain {
    private static let service = "com.raban.etabli.focus.interstice"
    static func read() throws -> String? {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service, kSecAttrAccount as String: "api-key",
            kSecReturnData as String: true, kSecMatchLimit as String: kSecMatchLimitOne]
        var value: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &value)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = value as? Data else {
            throw FlowSavvyError(message: String(localized: "Could not read the API key from Keychain. Unlock your device and retry."))
        }
        return String(data: data, encoding: .utf8)
    }
    static func write(_ key: String?) throws {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service, kSecAttrAccount as String: "api-key"]
        guard let key else {
            let status = SecItemDelete(query as CFDictionary)
            guard status == errSecSuccess || status == errSecItemNotFound else {
                throw FlowSavvyError(message: String(localized: "Could not remove the saved API key."))
            }
            return
        }
        let attributes: [String: Any] = [kSecValueData as String: Data(key.utf8),
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        var status = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound {
            status = SecItemAdd(query.merging(attributes) { _, new in new } as CFDictionary, nil)
        }
        guard status == errSecSuccess else { throw FlowSavvyError(message: String(localized: "Could not securely save the API key.")) }
    }
}

