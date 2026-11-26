// Copyright 2026 R. Heller
// SPDX-License-Identifier: AGPL-3.0-only
import Foundation

@main struct IntersticeChecks {
    static func main() async throws {
        for origin in ["http://tasks.example", "https://user:password@tasks.example", "https://tasks.example/?token=x", "https://tasks.example/#fragment"] {
            do { _ = try IntersticeConnection(origin: origin, token: "key"); fatalError("Unsafe origin accepted") }
            catch { }
        }
        let connection = try IntersticeConnection(origin: "https://tasks.example/workspace/", token: "test-key")
        let link = FlowTaskLink(taskID: "flowsavvy:one", occurrenceDate: nil, title: "Synthetic task",
            credentialID: connection.binding, installationID: "installation", provider: "interstice")
        precondition(FlowTaskLink.decode(link.encoded) == nil, "Source adapter must not accept Interstice history")
        let client = IntersticeClient { request in
            precondition(request.url?.absoluteString == "https://tasks.example/workspace/api/v1/tasks/flowsavvy%3Aone/progress")
            precondition(request.value(forHTTPHeaderField: "Idempotency-Key") == "stable-key")
            precondition(request.value(forHTTPHeaderField: "Authorization") == "Bearer test-key")
            return (Data("{\"progress\":25}".utf8), 200)
        }
        let result = try await client.request("POST", "/api/v1/tasks/\(IntersticeClient.component(link.taskID))/progress", connection: connection,
            body: ["session_id": .string("stable-session"), "minutes": .number(25)], headers: ["Idempotency-Key": "stable-key"])
        precondition(result["progress"]?.int == 25)
        let rejecting = IntersticeClient { _ in (Data("{}".utf8), 403) }
        do { _ = try await rejecting.request("GET", "/api/v1/tasks", connection: connection); fatalError("Denied request accepted") }
        catch { }
        print("Interstice client contract checks passed")
    }
}
