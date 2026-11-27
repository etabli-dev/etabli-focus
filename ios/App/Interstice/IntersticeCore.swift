// Copyright 2026 R. Heller
// SPDX-License-Identifier: AGPL-3.0-only
import Foundation

struct IntersticeConnection: Codable, Sendable {
    let origin: String
    let token: String
    init(origin: String, token: String) throws {
        let origin = origin.trimmingCharacters(in: .whitespacesAndNewlines).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard let url = URLComponents(string: origin), url.scheme == "https", url.host?.isEmpty == false,
              url.user == nil, url.password == nil, url.query == nil, url.fragment == nil,
              !token.isEmpty, !token.unicodeScalars.contains(where: { CharacterSet.whitespacesAndNewlines.contains($0) || CharacterSet.controlCharacters.contains($0) }) else {
            throw FlowSavvyError(message: "Enter your Interstice HTTPS address and access key.")
        }
        self.origin = origin; self.token = token
    }
    var binding: String { FlowTaskLink.credentialID(origin + "|" + token) }
}

private final class IntersticeHTTPSDelegate: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
}

struct IntersticeHTTPError: LocalizedError, Sendable {
    let status: Int
    var errorDescription: String? { "Interstice request failed (\(status)). Saved work remains on this device." }
}

struct IntersticeClient: Sendable {
    typealias Transport = @Sendable (URLRequest) async throws -> (Data, Int)
    private static let session: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.httpCookieStorage = nil; config.urlCredentialStorage = nil; config.urlCache = nil
        return URLSession(configuration: config, delegate: IntersticeHTTPSDelegate(), delegateQueue: nil)
    }()
    let transport: Transport
    init(transport: @escaping Transport = { request in
        let (data, response) = try await IntersticeClient.session.data(for: request)
        return (data, (response as? HTTPURLResponse)?.statusCode ?? 0)
    }) { self.transport = transport }
    func request(_ method: String, _ path: String, connection: IntersticeConnection,
                 body: [String: FlowJSON]? = nil, headers: [String: String] = [:]) async throws -> [String: FlowJSON] {
        guard path.hasPrefix("/api/v1/"), !path.contains(".."), let url = URL(string: connection.origin + path) else {
            throw FlowSavvyError(message: "Invalid Interstice address.")
        }
        var request = URLRequest(url: url); request.httpMethod = method; request.timeoutInterval = 90
        request.setValue("Bearer \(connection.token)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        for (key, value) in headers { request.setValue(value, forHTTPHeaderField: key) }
        if let body {
            request.httpBody = try JSONEncoder().encode(FlowJSON.object(body))
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        let (data, status) = try await transport(request)
        guard (200..<300).contains(status) else { throw IntersticeHTTPError(status: status) }
        guard data.count <= 16_000_000 else { throw FlowSavvyError(message: "Interstice response is too large.") }
        return try JSONDecoder().decode(FlowJSON.self, from: data).object
    }
    static func component(_ value: String) -> String {
        value.addingPercentEncoding(withAllowedCharacters: CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-_~")))!
    }
}

struct IntersticeCompletion: Codable {
    let binding: String
    let taskID: String
    let revision: Int
    let key: String
}
struct IntersticeLedger: Codable {
    var installationID = UUID().uuidString
    var receipts: [String: Int] = [:]
    var completion: IntersticeCompletion?
    var edit: IntersticeEdit?
    var cachedTasks: [FlowTask] = []
    var cacheBinding: String?
    var cachedAt: Date?
}

let intersticeCategories: [(String, String)] = [("discussion", "Discussion"), ("writing", "Writing & review"),
    ("research", "Research & analysis"), ("software", "Software & systems"), ("administration", "Administration & planning"),
    ("security", "Security & accounts"), ("learning", "Learning & teaching"), ("practical", "Errands & practical work"), ("other", "Other")]
extension FlowTask {
    var contextLabel: String {
        let mode = ["meeting":"Meeting", "phone":"Phone call", "email":"Email", "video":"Video call", "message":"Message"][communicationMode ?? ""]
        return [workList, intersticeCategories.first { $0.0 == (category ?? "other") }?.1 ?? "Other", person, mode,
            tags?.map { "#" + $0 }.joined(separator: " ")].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · ")
    }
}

let intersticeLists = ["Lehre", "Klinik", "Forschung", "Ausbildung", "Firma", "Bw", "Privat"]
let intersticeModes = [("", "Not specified"), ("meeting", "Meeting"), ("phone", "Phone call"), ("email", "Email"), ("video", "Video call"), ("message", "Message")]
struct IntersticeDetails: Codable, Equatable, Sendable {
    var workList: String
    var category: String
    var person: String
    var mode: String
    var topics: String
    func body() throws -> [String: FlowJSON] {
        let list = workList.trimmingCharacters(in: .whitespacesAndNewlines)
        let name = person.trimmingCharacters(in: .whitespacesAndNewlines)
        var seen = Set<String>()
        let tags = topics.split(separator: ",").map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty && seen.insert($0.lowercased()).inserted }
        guard !list.isEmpty, list.count <= 100, name.count <= 200, tags.count <= 20, tags.allSatisfy({ $0.count <= 80 }),
              intersticeCategories.contains(where: { $0.0 == category }), intersticeModes.contains(where: { $0.0 == mode }) else {
            throw FlowSavvyError(message: "Check list (1–100 characters), person (up to 200), category, mode and tags (up to 20, 80 characters each).")
        }
        return ["list": .string(list), "category": .string(category), "person": .string(name),
                "communication_mode": .string(mode), "tags": .array(tags.map { .string($0) })]
    }
}
struct IntersticeEdit: Codable {
    let binding: String
    let taskID: String
    let revision: Int
    let key: String
    let details: IntersticeDetails
}
