// Copyright 2026 R. Heller
// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI
import SwiftData

extension Session {
    var intersticeWork: FlowWorkRecord? {
        guard kind == "focus", let note, let data = note.data(using: .utf8), let link = try? JSONDecoder().decode(FlowTaskLink.self, from: data), link.provider == "interstice", let seconds = activeSeconds, actualEnd != nil else { return nil }
        return FlowWorkRecord(sessionID: id.uuidString, link: link, seconds: seconds)
    }
}

struct IntersticeView: View {
    @Environment(IntersticeStore.self) private var store
    @Environment(PomodoroEngine.self) private var engine
    @Environment(\.dismiss) private var dismiss
    @Query(sort: \Category.order) private var categories: [Category]
    @Query private var sessions: [Session]
    @State private var origin = ""
    @State private var apiKey = ""
    @State private var categoryID: UUID?
    @State private var minutes = 25
    @State private var confirming: FlowTask?
    private var category: Category? { categories.first { $0.id == categoryID } ?? categories.first }

    var body: some View {
        NavigationStack {
            Form {
                Section("Connection") {
                    Text("Connect Interstice to focus on your scheduled tasks. Work time syncs automatically; completing a task always needs your confirmation.")
                    if store.connected {
                        Label("Connected", systemImage: "checkmark.circle")
                        if let refreshed = store.lastSynced { Text("Task copy refreshed: \(refreshed.formatted())").font(.footnote) }
                        Button("Refresh tasks") { Task { await store.refresh() } }.disabled(store.busy)
                        Button("Disconnect", role: .destructive) { store.disconnect() }.disabled(store.busy || engine.state.phase != .idle)
                    } else {
                        TextField("Interstice HTTPS address", text: $origin).textInputAutocapitalization(.never).autocorrectionDisabled()
                        SecureField("Access key", text: $apiKey)
                            .textInputAutocapitalization(.never).autocorrectionDisabled()
                        Button("Connect") {
                            let entered = apiKey; apiKey = ""
                            Task { await store.connect(origin, key: entered) }
                        }.disabled(store.busy || apiKey.isEmpty)
                        Text("Use the access key supplied for this device by your Interstice installation.").font(.footnote)
                    }
                    if store.busy { ProgressView("Syncing…") }
                    if let message = store.message { Text(message).foregroundStyle(.secondary) }
                    if store.pendingMinutes > 0 { Text("\(store.pendingMinutes) minutes waiting to sync") }
                    Button("Retry saved work") { Task { await store.sync(sessions.compactMap(\.intersticeWork)) } }
                        .disabled(!store.connected || store.busy)
                    Text("Partial minutes accumulate. Offline work is retained and syncs when the app is active again.").font(.footnote)
                }
                if store.connected {
                    Section("Pomodoro") {
                        Picker("Category", selection: $categoryID) {
                            ForEach(categories) { cat in Text(cat.name).tag(Optional(cat.id)) }
                        }
                        Stepper("\(minutes) minutes", value: $minutes, in: 1...120)
                        if engine.state.phase != .idle { Text("A timer is already active. Return to Today to pause or stop it.") }
                    }
                    Section("Tasks · scheduled first") {
                        if store.tasks.isEmpty { Text("No open tasks found. Recurring occurrences appear within the server planning horizon.") }
                        ForEach(store.tasks) { task in
                            VStack(alignment: .leading, spacing: 8) {
                                Text(task.title).font(.headline)
                                if let scheduled = task.scheduledStart { Text(scheduled.replacingOccurrences(of: "T", with: " · ")).font(.caption) }
                                if let progress = task.progressMinutes, let planned = task.durationMinutes {
                                    Text("\(progress) min recorded / \(planned) min planned").font(.caption)
                                } else if let progress = task.progressMinutes {
                                    Text("\(progress) min recorded").font(.caption)
                                } else { Text("Recurring task · selected occurrence").font(.caption) }
                                HStack {
                                    Button("Start Pomodoro") {
                                        guard let category else { return }
                                        engine.start(categoryID: category.id, kind: .focus, minutes: minutes, flowSavvyLink: task.link.encoded)
                                        dismiss()
                                    }.disabled(engine.state.phase != .idle || category == nil || store.busy)
                                    Spacer()
                                    Button("Mark complete") { confirming = task }
                                        .disabled(store.busy || engine.state.phase != .idle)
                                }.buttonStyle(.borderless)
                            }.padding(.vertical, 4)
                        }
                    }
                }
            }
            .navigationTitle("Interstice")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } } }
            .task { if store.connected { await store.refresh() }; if categoryID == nil { categoryID = categories.first?.id } }
            .confirmationDialog("Mark this task complete in Interstice?", isPresented: Binding(get: { confirming != nil }, set: { if !$0 { confirming = nil } }), titleVisibility: .visible) {
                if let task = confirming {
                    Button("Complete task") {
                        confirming = nil
                        Task { await store.complete(task, work: sessions.compactMap(\.intersticeWork)) }
                    }
                    Button("Cancel", role: .cancel) { confirming = nil }
                }
            } message: { Text(confirming?.title ?? "") }
        }
    }
}
