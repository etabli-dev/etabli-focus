// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

import SwiftUI
import SwiftData

extension Session {
    var flowWork: FlowWorkRecord? {
        guard kind == "focus", let link = FlowTaskLink.decode(note), let seconds = activeSeconds, actualEnd != nil else { return nil }
        return FlowWorkRecord(sessionID: id.uuidString, link: link, seconds: seconds)
    }
}

struct FlowSavvyView: View {
    @Environment(FlowSavvyStore.self) private var store
    @Environment(PomodoroEngine.self) private var engine
    @Environment(\.dismiss) private var dismiss
    @Query(sort: \Category.order) private var categories: [Category]
    @Query private var sessions: [Session]
    @State private var apiKey = ""
    @State private var categoryID: UUID?
    @State private var minutes = 25
    @State private var confirming: FlowTask?
    private var category: Category? { categories.first { $0.id == categoryID } ?? categories.first }

    var body: some View {
        NavigationStack {
            Form {
                Section("Connection") {
                    Text("Connect FlowSavvy Pro to focus on your scheduled tasks. Work time syncs automatically; completing a task always needs your confirmation.")
                    if store.connected {
                        Label("Connected", systemImage: "checkmark.circle")
                        Button("Refresh tasks") { Task { await store.refresh() } }.disabled(store.busy)
                        Button("Disconnect", role: .destructive) { store.disconnect() }.disabled(store.busy || engine.state.phase != .idle)
                    } else {
                        SecureField("FlowSavvy API key", text: $apiKey)
                            .textInputAutocapitalization(.never).autocorrectionDisabled()
                        Button("Connect") {
                            let entered = apiKey; apiKey = ""
                            Task { await store.connect(entered) }
                        }.disabled(store.busy || apiKey.isEmpty)
                        Link("Get an API key in FlowSavvy", destination: URL(string: "https://my.flowsavvy.app/api/docs/")!)
                    }
                    if store.busy { ProgressView("Syncing…") }
                    if let message = store.message { Text(message).foregroundStyle(.secondary) }
                    if store.pendingMinutes > 0 { Text("\(store.pendingMinutes) minutes waiting to sync") }
                    Button("Retry saved work") { Task { await store.sync(sessions.compactMap(\.flowWork)) } }
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
                    Section("Tasks · next 7 days, then unscheduled") {
                        if store.tasks.isEmpty { Text("No open tasks found. Recurring tasks appear when scheduled in the next 7 days.") }
                        ForEach(store.tasks) { task in
                            VStack(alignment: .leading, spacing: 8) {
                                Text(task.title).font(.headline)
                                if let scheduled = task.scheduledStart { Text(scheduled.replacingOccurrences(of: "T", with: " · ")).font(.caption) }
                                if let progress = task.progressMinutes {
                                    Text("\(progress) min recorded" + (task.durationMinutes.map { " / \($0) min planned" } ?? "")).font(.caption)
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
            .navigationTitle("FlowSavvy")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } } }
            .task { if store.connected { await store.refresh() }; if categoryID == nil { categoryID = categories.first?.id } }
            .confirmationDialog("Mark this task complete in FlowSavvy?", isPresented: Binding(get: { confirming != nil }, set: { if !$0 { confirming = nil } }), titleVisibility: .visible) {
                if let task = confirming {
                    Button("Complete task") {
                        confirming = nil
                        Task { await store.complete(task, work: sessions.compactMap(\.flowWork)) }
                    }
                    Button("Cancel", role: .cancel) { confirming = nil }
                }
            } message: { Text(confirming?.title ?? "") }
        }
    }
}
