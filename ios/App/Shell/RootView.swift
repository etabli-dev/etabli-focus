// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

import SwiftUI
import SwiftData

struct RootView: View {
    @Environment(\.modelContext) private var context
    @Environment(\.scenePhase) private var scenePhase
    @Environment(PomodoroEngine.self) private var engine
    @Environment(CalendarAgenda.self) private var calendarAgenda
    @Environment(FlowSavvyStore.self) private var flowSavvy
    @Query private var sessions: [Session]
    private var work: [FlowWorkRecord] { sessions.compactMap(\.flowWork) }
    var body: some View {
        // On iPhone this renders as a tab bar; on iPad iOS 18+ promotes to
        // the sidebar style. iPad split layouts are inside the individual
        // tabs (Today shows planned + timer side-by-side on regular width).
        TabView {
            TodayView()
                .tabItem { Label("Today", systemImage: "calendar") }
            TemplateView()
                .tabItem { Label("Template", systemImage: "rectangle.grid.3x2") }
            StatsView()
                .tabItem { Label("Stats", systemImage: "chart.xyaxis.line") }
            CategoriesView()
                .tabItem { Label("Categories", systemImage: "tag") }
            SettingsView()
                .tabItem { Label("Settings", systemImage: "gear") }
        }
        .task(id: scenePhase) {
            guard scenePhase == .active else { return }
            while !Task.isCancelled {
                engine.tick(in: context)
                do { try await Task.sleep(for: .seconds(1)) } catch { return }
            }
        }
        .task(id: scenePhase) {
            guard scenePhase == .active else { return }
            while !Task.isCancelled {
                await flowSavvy.sync(work)
                do { try await Task.sleep(for: .seconds(30)) } catch { return }
            }
        }
        .task(id: scenePhase) {
            guard scenePhase == .active else { return }
            while !Task.isCancelled {
                await calendarAgenda.refresh()
                do { try await Task.sleep(for: .seconds(30)) } catch { return }
            }
        }
        .onChange(of: work) { _, records in Task { await flowSavvy.sync(records) } }
    }
}
