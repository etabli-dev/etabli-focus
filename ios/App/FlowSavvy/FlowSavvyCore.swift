// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

import Foundation
import CryptoKit

/// Retains the full JSON document when an API update replaces a task.
indirect enum FlowJSON: Codable, Equatable {
    case object([String: FlowJSON]), array([FlowJSON]), string(String), number(Double), bool(Bool), null
    init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { self = .null }
        else if let v = try? c.decode(Bool.self) { self = .bool(v) }
        else if let v = try? c.decode(Double.self) { self = .number(v) }
        else if let v = try? c.decode(String.self) { self = .string(v) }
        else if let v = try? c.decode([FlowJSON].self) { self = .array(v) }
        else { self = .object(try c.decode([String: FlowJSON].self)) }
    }
    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case .object(let v): try c.encode(v)
        case .array(let v): try c.encode(v)
        case .string(let v): try c.encode(v)
        case .number(let v): try c.encode(v)
        case .bool(let v): try c.encode(v)
        case .null: try c.encodeNil()
        }
    }
    var object: [String: FlowJSON] { if case .object(let v) = self { return v }; return [:] }
    var array: [FlowJSON] { if case .array(let v) = self { return v }; return [] }
    var string: String? { if case .string(let v) = self { return v }; return nil }
    var int: Int? { if case .number(let v) = self, v.isFinite, v >= 0, v < Double(Int.max) { return Int(v) }; return nil }
    var bool: Bool { if case .bool(let v) = self { return v }; return false }
}

struct FlowTaskLink: Codable, Equatable, Hashable {
    let taskID: String
    let occurrenceDate: String?
    let title: String
    let credentialID: String
    let installationID: String?
    var ledgerID: String { "\(installationID ?? "legacy")|\(credentialID)|\(taskID)|\(occurrenceDate ?? "")" }
    var encoded: String? { (try? JSONEncoder().encode(self)).flatMap { String(data: $0, encoding: .utf8) } }
    static func decode(_ text: String?) -> FlowTaskLink? {
        guard let data = text?.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(Self.self, from: data)
    }
    static func credentialID(_ key: String) -> String {
        SHA256.hash(data: Data(key.utf8)).map { String(format: "%02x", $0) }.joined()
    }
}

struct FlowTask: Identifiable, Equatable {
    let link: FlowTaskLink
    let progressMinutes: Int?
    let durationMinutes: Int?
    let scheduledStart: String?
    var id: String { link.ledgerID }
    var title: String { link.title }
}

struct FlowWorkRecord: Equatable {
    let sessionID: String
    let link: FlowTaskLink
    let seconds: Double
}

struct FlowIntent: Codable, Equatable {
    let baseline: Int
    let target: Int
    let totalSeconds: Double
}
struct FlowReceipt: Codable, Equatable {
    var acknowledgedSeconds: Double = 0
    var intent: FlowIntent?
}
struct FlowLedger: Codable {
    var installationID: String = UUID().uuidString
    var receipts: [String: FlowReceipt] = [:]
}

struct FlowSavvyError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

private final class FlowHTTPSDelegate: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        // The API has a fixed HTTPS origin. Never forward credentials through a redirect.
        completionHandler(nil)
    }
}
private enum FlowHTTPS {
    static let session: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.httpCookieStorage = nil
        configuration.urlCredentialStorage = nil
        configuration.urlCache = nil
        return URLSession(configuration: configuration, delegate: FlowHTTPSDelegate(), delegateQueue: nil)
    }()
}

struct FlowSavvyClient {
    typealias Transport = (URLRequest) async throws -> (Data, Int)
    let transport: Transport
    init(transport: @escaping Transport = { request in
        let (data, response) = try await FlowHTTPS.session.data(for: request)
        return (data, (response as? HTTPURLResponse)?.statusCode ?? 0)
    }) { self.transport = transport }

    func request(_ method: String, _ path: String, key: String,
                 query: [URLQueryItem] = [], body: [String: FlowJSON]? = nil) async throws -> [String: FlowJSON] {
        var components = URLComponents(string: "https://my.flowsavvy.app")!
        components.path = path
        if !query.isEmpty { components.queryItems = query }
        guard let url = components.url else { throw FlowSavvyError(message: "Invalid task address.") }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.timeoutInterval = 30
        request.setValue("Bearer \(key)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let body {
            request.httpBody = try JSONEncoder().encode(FlowJSON.object(body))
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        let (data, status) = try await transport(request)
        guard (200..<300).contains(status) else {
            let explanation: String
            switch status {
            case 401, 403: explanation = "Check your FlowSavvy API key and Pro subscription."
            case 404: explanation = "The FlowSavvy task is no longer available."
            case 429: explanation = "FlowSavvy is busy. Your work is saved; retry later."
            default: explanation = "FlowSavvy could not sync (HTTP \(status)). Your work is saved."
            }
            throw FlowSavvyError(message: explanation)
        }
        return try JSONDecoder().decode(FlowJSON.self, from: data).object
    }
    func occurrenceQuery(_ link: FlowTaskLink, updating: Bool = false) -> [URLQueryItem] {
        guard let date = link.occurrenceDate else { return [] }
        var q = [URLQueryItem(name: "occurrenceDate", value: date)]
        if updating { q.append(URLQueryItem(name: "scope", value: "thisOccurrence")) }
        return q
    }
    func get(_ link: FlowTaskLink, key: String) async throws -> [String: FlowJSON] {
        try await request("GET", "/api/items/\(link.taskID)", key: key, query: occurrenceQuery(link))
    }
    static let readOnlyFields: Set<String> = ["itemType", "id", "lastModified", "dateCreated", "url", "completed", "parentItemId", "occurrenceDate", "cancelledOccurrenceDates"]
    static func replacingProgress(in task: [String: FlowJSON], with progress: Int) -> [String: FlowJSON] {
        var body = task.filter { !readOnlyFields.contains($0.key) }
        body["progressMinutes"] = .number(Double(progress))
        return body
    }
    func put(_ link: FlowTaskLink, key: String, body: [String: FlowJSON]) async throws {
        _ = try await request("PUT", "/api/tasks/\(link.taskID)", key: key,
                              query: occurrenceQuery(link, updating: true), body: body)
    }
    func complete(_ link: FlowTaskLink, key: String) async throws {
        _ = try await request("POST", "/api/tasks/\(link.taskID)/complete", key: key, query: occurrenceQuery(link))
    }
    func tasks(key: String, installationID: String, now: Date = Date()) async throws -> [FlowTask] {
        var items: [[String: FlowJSON]] = []
        var token: String?
        var seen = Set<String>()
        repeat {
            var query = [URLQueryItem(name: "itemType", value: "task"), URLQueryItem(name: "completed", value: "false"), URLQueryItem(name: "limit", value: "200")]
            if let token { query.append(URLQueryItem(name: "pageToken", value: token)) }
            let result = try await request("GET", "/api/items", key: key, query: query)
            items += (result["items"]?.array ?? []).map(\.object)
            token = result["nextPageToken"]?.string
            if let token, !seen.insert(token).inserted { throw FlowSavvyError(message: "FlowSavvy returned a repeated page. Please retry.") }
        } while token != nil
        let format = DateFormatter(); format.calendar = Calendar(identifier: .gregorian)
        format.locale = Locale(identifier: "en_US_POSIX"); format.dateFormat = "yyyy-MM-dd"
        let end = Calendar.current.date(byAdding: .day, value: 7, to: now) ?? now
        let schedule = try await request("GET", "/api/schedule", key: key,
                                        query: [URLQueryItem(name: "startDate", value: format.string(from: now)), URLQueryItem(name: "endDate", value: format.string(from: end))])
        let blocks = (schedule["scheduleItems"]?.array ?? []).map(\.object).filter {
            $0["itemType"]?.string == "task" && $0["completed"]?.bool != true && $0["thisPartCompleted"]?.bool != true
        }
        let binding = FlowTaskLink.credentialID(key)
        var tasks: [FlowTask] = []; var included = Set<String>()
        for item in items {
            guard let id = item["id"]?.string, !id.isEmpty, item["completed"]?.bool != true else { continue }
            let matching = blocks.filter { $0["itemId"]?.string == id }
            let repeating = !(item["recurrenceRule"]?.string ?? "").isEmpty
            if repeating {
                for block in matching {
                    guard let date = block["occurrenceDate"]?.string else { continue }
                    let link = FlowTaskLink(taskID: id, occurrenceDate: date, title: item["title"]?.string ?? "Untitled task", credentialID: binding, installationID: installationID)
                    if included.insert(link.ledgerID).inserted {
                        tasks.append(FlowTask(link: link, progressMinutes: nil,
                                              durationMinutes: nil, scheduledStart: block["startTime"]?.string))
                    }
                }
            } else {
                let link = FlowTaskLink(taskID: id, occurrenceDate: nil, title: item["title"]?.string ?? "Untitled task", credentialID: binding, installationID: installationID)
                if included.insert(link.ledgerID).inserted {
                    tasks.append(FlowTask(link: link, progressMinutes: item["progressMinutes"]?.int ?? 0,
                                          durationMinutes: item["durationMinutes"]?.int, scheduledStart: matching.compactMap { $0["startTime"]?.string }.min()))
                }
            }
        }
        return tasks.sorted { ($0.scheduledStart ?? "~", $0.title) < ($1.scheduledStart ?? "~", $1.title) }
    }
}

/// A persisted absolute progress target is reused after a failed response.
/// A conflicting remote value stops syncing instead of overwriting it.
@MainActor
final class FlowSynchronizer {
    var ledger: FlowLedger
    private let save: (FlowLedger) throws -> Void
    let client: FlowSavvyClient
    init(ledger: FlowLedger = FlowLedger(), client: FlowSavvyClient,
         save: @escaping (FlowLedger) throws -> Void) {
        self.ledger = ledger; self.client = client; self.save = save
    }
    func synchronize(_ records: [FlowWorkRecord], key: String) async throws {
        let binding = FlowTaskLink.credentialID(key)
        let unique = Dictionary(records.map { ($0.sessionID, $0) }, uniquingKeysWith: { first, _ in first })
        let grouped = Dictionary(grouping: unique.values, by: { $0.link.ledgerID })
        for id in grouped.keys.sorted() {
            guard let work = grouped[id], let link = work.first?.link else { continue }
            let total = work.reduce(0.0) { $0 + max(0, $1.seconds.isFinite ? $1.seconds : 0) }
            var receipt = ledger.receipts[id] ?? FlowReceipt()
            let deltaMinutes = Int(total / 60) - Int(receipt.acknowledgedSeconds / 60)
            guard deltaMinutes > 0 || receipt.intent != nil else { continue }
            guard link.installationID == ledger.installationID else { throw FlowSavvyError(message: "Saved work was restored without its original sync history. Automatic reporting is paused to avoid counting it twice. Restore the original sync history or review the work in FlowSavvy.") }
            guard link.credentialID == binding else { throw FlowSavvyError(message: "Saved work belongs to a different API key. Reconnect the original key before syncing it.") }
            repeat {
                var task = try await client.get(link, key: key)
                guard task["itemType"]?.string == "task" else { throw FlowSavvyError(message: "The selected item is no longer a task.") }
                var current = task["progressMinutes"]?.int ?? 0
                if let pending = receipt.intent {
                    if current == pending.target {
                        receipt.acknowledgedSeconds = pending.totalSeconds; receipt.intent = nil
                        try commit(receipt, id: id)
                    } else if current != pending.baseline {
                        throw FlowSavvyError(message: "Progress changed in FlowSavvy during a pending sync. Your local work is retained; review the task before retrying.")
                    }
                }
                let remaining = Int(total / 60) - Int(receipt.acknowledgedSeconds / 60)
                if receipt.intent == nil && remaining > 0 {
                    guard task["completed"]?.bool != true else { throw FlowSavvyError(message: "This task was completed elsewhere. Its unsynced work remains saved here.") }
                    // Refetch after reconciling an earlier intent so other task fields stay current.
                    task = try await client.get(link, key: key)
                    current = task["progressMinutes"]?.int ?? 0
                    receipt.intent = FlowIntent(baseline: current, target: current + remaining, totalSeconds: total)
                    try commit(receipt, id: id)
                }
                if let pending = receipt.intent {
                    guard task["completed"]?.bool != true else { throw FlowSavvyError(message: "The task is completed; saved work needs review.") }
                    try await client.put(link, key: key, body: FlowSavvyClient.replacingProgress(in: task, with: pending.target))
                    receipt.acknowledgedSeconds = pending.totalSeconds; receipt.intent = nil
                    try commit(receipt, id: id)
                }
            } while receipt.intent != nil || Int(total / 60) > Int(receipt.acknowledgedSeconds / 60)
        }
    }
    private func commit(_ receipt: FlowReceipt, id: String) throws {
        var next = ledger; next.receipts[id] = receipt
        try save(next); ledger = next
    }
}
