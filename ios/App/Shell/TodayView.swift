// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

import SwiftUI
import SwiftData

struct TodayView: View {
    @Environment(\.modelContext) private var context
    @Environment(PomodoroEngine.self) private var engine
    @Environment(\.horizontalSizeClass) private var sizeClass

    @Query(sort: \Category.order) private var categories: [Category]
    @Query private var sessions: [Session]
    @Query private var templates: [TemplateEntry]

    @State private var showingTimer = false
    @State private var selectedCategory: Category?

    private var todayWeekday: Int {
        Calendar.current.component(.weekday, from: Date())
    }
    private var todayStart: Date {
        Calendar.current.startOfDay(for: Date())
    }
    private var todayPlan: [(category: Category, target: Int, completed: Int)] {
        categories.compactMap { cat in
            let target = templates
                .first(where: { $0.weekday == todayWeekday && $0.categoryID == cat.id })?
                .targetBlocks ?? 0
            let completed = sessions.filter {
                $0.categoryID == cat.id && $0.kind == "focus" &&
                $0.completed && $0.startedAt >= todayStart
            }.count
            if target == 0 && completed == 0 { return nil }
            return (cat, target, completed)
        }
    }

    var body: some View {
        NavigationStack {
            ZStack {
                Theme.Color.paper.ignoresSafeArea()
                ScrollView {
                    if sizeClass == .regular {
                        // iPad: side-by-side timer + plan.
                        HStack(alignment: .top, spacing: Theme.Space.lg) {
                            timerColumn.frame(maxWidth: .infinity)
                            planColumn.frame(maxWidth: .infinity)
                        }
                        .padding(Theme.Space.lg)
                    } else {
                        VStack(alignment: .leading, spacing: Theme.Space.lg) {
                            timerColumn
                            planColumn
                        }.padding(Theme.Space.lg)
                    }
                }
            }
            .navigationBarHidden(true)
            .sheet(isPresented: $showingTimer) {
                if let cat = selectedCategory {
                    TimerSheet(category: cat)
                }
            }
        }
    }

    private var timerColumn: some View {
        VStack(alignment: .leading, spacing: Theme.Space.lg) {
            PromptHeader(["today", formattedDate()])
            Text(currentStateTitle)
                .font(Theme.Font.display).foregroundStyle(Theme.Color.ink)
            Card(title: "now", systemImage: "play.circle") {
                if engine.state.phase == .idle {
                    MonoLabel("Pick a category below to start a focus block.",
                              color: Theme.Color.faint)
                } else {
                    LiveTimerInline()
                        .environment(engine)
                }
            }
        }
    }

    private var planColumn: some View {
        VStack(alignment: .leading, spacing: Theme.Space.lg) {
            Card(title: "today's plan", systemImage: "checklist") {
                if todayPlan.isEmpty {
                    EmptyState(
                        title: "no plan for today",
                        detail: "Open Template to set targets per category per weekday.",
                        systemImage: "calendar.badge.plus"
                    ).frame(height: 200)
                } else {
                    VStack(spacing: Theme.Space.md) {
                        ForEach(todayPlan, id: \.category.id) { row in
                            planRow(row)
                        }
                    }
                }
            }
            Card(title: "start a block", systemImage: "play.fill") {
                VStack(spacing: 0) {
                    ForEach(categories) { cat in
                        Button {
                            selectedCategory = cat
                            showingTimer = true
                        } label: {
                            ListRow(
                                title: cat.name,
                                metadata: "\(cat.defaultFocusMinutes) min focus · \(cat.defaultBreakMinutes) min break",
                                leading: { Circle().fill(cat.color).frame(width: 12, height: 12) },
                                trailing: {
                                    Image(systemName: "chevron.right")
                                        .font(Theme.Font.mono).foregroundStyle(Theme.Color.faint)
                                }
                            )
                        }.buttonStyle(.plain)
                        if cat.id != categories.last?.id {
                            Divider().background(Theme.Color.hairline)
                        }
                    }
                }
            }
        }
    }

    private func planRow(_ row: (category: Category, target: Int, completed: Int)) -> some View {
        let pct = row.target == 0 ? 0.0 : min(1.0, Double(row.completed) / Double(row.target))
        return HStack(spacing: Theme.Space.md) {
            Circle().fill(row.category.color).frame(width: 12, height: 12)
            VStack(alignment: .leading, spacing: 2) {
                Text(row.category.name).font(Theme.Font.body).foregroundStyle(Theme.Color.ink).lineLimit(1)
                ZStack(alignment: .leading) {
                    Capsule().fill(Theme.Color.hairline).frame(height: 6)
                    Capsule().fill(row.category.color).frame(width: max(6, 200 * pct), height: 6)
                }
            }
            Spacer()
            MonoLabel("\(row.completed) / \(row.target)",
                      color: row.completed >= row.target && row.target > 0
                             ? Theme.Color.accent : Theme.Color.faint)
        }
    }

    private var currentStateTitle: String {
        switch engine.state.phase {
        case .idle:    "Ready"
        case .running: "Focusing"
        case .paused:  "Paused"
        }
    }

    private func formattedDate() -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        return f.string(from: Date())
    }
}

// Inline live timer (used when a session is mid-flight, embedded in Today's card).
private struct LiveTimerInline: View {
    @Environment(\.modelContext) private var context
    @Environment(PomodoroEngine.self) private var engine

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { _ in
            VStack(spacing: Theme.Space.md) {
                ZStack {
                    Circle().stroke(Theme.Color.hairline, lineWidth: 6)
                    Circle()
                        .trim(from: 0, to: engine.progress)
                        .stroke(Theme.Color.accent,
                                style: StrokeStyle(lineWidth: 6, lineCap: .round))
                        .rotationEffect(.degrees(-90))
                    VStack {
                        Text(timeString(engine.remainingSeconds))
                            .font(Theme.Font.display)
                            .foregroundStyle(Theme.Color.ink)
                        MonoLabel(engine.state.phase == .paused ? "paused" : "running",
                                  color: Theme.Color.faint)
                    }
                }
                .frame(width: 160, height: 160)
                HStack(spacing: Theme.Space.md) {
                    if engine.state.phase == .running {
                        PrimaryButton("Pause", systemImage: "pause") { engine.pause() }
                    } else if engine.state.phase == .paused {
                        PrimaryButton("Resume", systemImage: "play") { engine.resume() }
                    }
                    Button { engine.cancel(in: context) } label: {
                        HStack { Image(systemName: "stop"); Text("Reset") }
                            .font(Theme.Font.body).foregroundStyle(Theme.Color.danger)
                            .padding(.horizontal, Theme.Space.md).padding(.vertical, Theme.Space.sm)
                            .overlay(RoundedRectangle(cornerRadius: Theme.Radius.sm)
                                .strokeBorder(Theme.Color.danger.opacity(0.5), lineWidth: 1))
                    }
                    .buttonStyle(.plain)
                }
            }
            .onChange(of: engine.remainingSeconds) { _, _ in
                engine.tick(in: context)
            }
        }
    }

    private func timeString(_ seconds: Double) -> String {
        let total = Int(seconds.rounded(.up))
        return String(format: "%02d:%02d", total / 60, total % 60)
    }
}

// MARK: - Timer sheet (full-screen when starting a new block)

private struct TimerSheet: View {
    let category: Category
    @Environment(\.modelContext) private var context
    @Environment(PomodoroEngine.self) private var engine
    @Environment(\.dismiss) private var dismiss

    @State private var customMinutes: Int

    init(category: Category) {
        self.category = category
        _customMinutes = State(initialValue: category.defaultFocusMinutes)
    }

    var body: some View {
        NavigationStack {
            ZStack {
                Theme.Color.paper.ignoresSafeArea()
                VStack(alignment: .leading, spacing: Theme.Space.lg) {
                    VStack(alignment: .leading, spacing: Theme.Space.xs) {
                        HStack(spacing: Theme.Space.sm) {
                            Circle().fill(category.color).frame(width: 12, height: 12)
                            Text(category.name)
                                .font(Theme.Font.title).foregroundStyle(Theme.Color.ink)
                        }
                        MonoLabel("focus block", color: Theme.Color.faint)
                    }
                    Card(title: "duration", systemImage: "clock") {
                        VStack(alignment: .leading, spacing: Theme.Space.md) {
                            HStack {
                                MonoLabel("\(customMinutes) min", color: Theme.Color.ink)
                                Spacer()
                                Stepper("", value: $customMinutes, in: 5...120, step: 5).labelsHidden()
                            }
                            MonoLabel("default: \(category.defaultFocusMinutes) min", color: Theme.Color.faint)
                        }
                    }
                    PrimaryButton("Start focus", systemImage: "play.fill") {
                        engine.start(categoryID: category.id, kind: .focus, minutes: customMinutes)
                        dismiss()
                    }
                    Spacer()
                }.padding(Theme.Space.lg)
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }.font(Theme.Font.mono)
                }
            }
        }
    }
}
