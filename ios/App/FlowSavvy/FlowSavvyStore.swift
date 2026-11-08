// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

import Foundation
import Observation
import Security

struct FlowKeychain {
    private static let service = "com.raban.etabli.focus.flowsavvy"
    static func read() throws -> String? {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service, kSecAttrAccount as String: "api-key",
            kSecReturnData as String: true, kSecMatchLimit as String: kSecMatchLimitOne]
        var value: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &value)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = value as? Data else {
            throw FlowSavvyError(message: "Could not read the API key from Keychain. Unlock your device and retry.")
        }
        return String(data: data, encoding: .utf8)
    }
    static func write(_ key: String?) throws {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service, kSecAttrAccount as String: "api-key"]
        guard let key else {
            let status = SecItemDelete(query as CFDictionary)
            guard status == errSecSuccess || status == errSecItemNotFound else {
                throw FlowSavvyError(message: "Could not remove the saved API key.")
            }
            return
        }
        let attributes: [String: Any] = [kSecValueData as String: Data(key.utf8),
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        var status = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound {
            status = SecItemAdd(query.merging(attributes) { _, new in new } as CFDictionary, nil)
        }
        guard status == errSecSuccess else { throw FlowSavvyError(message: "Could not securely save the API key.") }
    }
}

@MainActor @Observable
final class FlowSavvyStore {
    private(set) var tasks: [FlowTask] = []
    private(set) var connected = false
    private(set) var busy = false
    private(set) var message: String?
    private(set) var pendingMinutes = 0
    private(set) var lastSynced: Date?
    private var key: String?
    private var synchronizer: FlowSynchronizer?
    private var records: [FlowWorkRecord] = []
    private let client = FlowSavvyClient()

    init() {
        do {
            let folder = try FileManager.default.url(for: .applicationSupportDirectory,
                in: .userDomainMask, appropriateFor: nil, create: true).appendingPathComponent("FlowSavvy", isDirectory: true)
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            var location = folder
            var values = URLResourceValues(); values.isExcludedFromBackup = true
            try location.setResourceValues(values)
            let file = folder.appendingPathComponent("progress-ledger.json")
            let ledger = FileManager.default.fileExists(atPath: file.path)
                ? try JSONDecoder().decode(FlowLedger.self, from: Data(contentsOf: file)) : FlowLedger()
            if !FileManager.default.fileExists(atPath: file.path) {
                try JSONEncoder().encode(ledger).write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            }
            synchronizer = FlowSynchronizer(ledger: ledger, client: client) { next in
                try JSONEncoder().encode(next).write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            }
            key = try FlowKeychain.read(); connected = key != nil
        } catch {
            message = "Could not load the saved FlowSavvy connection or sync history. Sync is paused to protect recorded work."
        }
    }
    func connect(_ enteredKey: String) async {
        guard !busy, synchronizer != nil else { return }
        let proposed = enteredKey.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !proposed.isEmpty, !proposed.contains("\n"), !proposed.contains("\r") else {
            message = "Enter your FlowSavvy API key."; return
        }
        busy = true; defer { busy = false }
        do {
            let loaded = try await client.tasks(key: proposed, installationID: synchronizer!.ledger.installationID)
            try FlowKeychain.write(proposed)
            key = proposed; connected = true; tasks = loaded; message = nil
        } catch { message = error.localizedDescription }
    }
    func disconnect() {
        guard !busy else { return }
        do {
            try FlowKeychain.write(nil); key = nil; connected = false; tasks = []
            message = "Disconnected. Saved work remains on this device."
        } catch { message = error.localizedDescription }
    }
    func refresh() async {
        guard !busy, let key else { return }
        busy = true; defer { busy = false }
        do { tasks = try await client.tasks(key: key, installationID: synchronizer!.ledger.installationID); message = nil }
        catch { message = error.localizedDescription }
    }
    func sync(_ work: [FlowWorkRecord]) async {
        records = work; updatePending()
        guard !busy, let key, let synchronizer else { return }
        busy = true; defer { busy = false; updatePending() }
        do {
            try await synchronizer.synchronize(work, key: key)
            message = nil; lastSynced = Date()
        } catch { message = error.localizedDescription }
    }
    func complete(_ task: FlowTask, work: [FlowWorkRecord]) async {
        guard !busy, let key, let synchronizer else { return }
        guard task.link.credentialID == FlowTaskLink.credentialID(key) else {
            message = "Reconnect the API key used to load this task."; return
        }
        records = work; busy = true; defer { busy = false; updatePending() }
        do {
            try await synchronizer.synchronize(work, key: key)
            try await client.complete(task.link, key: key)
            tasks.removeAll { $0.id == task.id }
            message = "Task marked complete in FlowSavvy."; lastSynced = Date()
        } catch { message = error.localizedDescription }
    }
    private func updatePending() {
        let grouped = Dictionary(grouping: records, by: { $0.link.ledgerID })
        pendingMinutes = grouped.reduce(0) { total, pair in
            let unique = Dictionary(pair.value.map { ($0.sessionID, $0.seconds) }, uniquingKeysWith: { a, _ in a })
            let seconds = unique.values.reduce(0, +)
            let acknowledged = synchronizer?.ledger.receipts[pair.key]?.acknowledgedSeconds ?? 0
            return total + max(0, Int(seconds / 60) - Int(acknowledged / 60))
        }
    }
}
