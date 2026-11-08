// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

import SwiftUI
import SwiftData
import UserNotifications

@main
struct EtabliFocusApp: App {

    @AppStorage(ThemePreference.userDefaultsKey) private var themeRaw: String = ThemePreference.system.rawValue
    @State private var engine = PomodoroEngine()
    @State private var flowSavvy = FlowSavvyStore()
    @State private var calendarAgenda = CalendarAgenda()

    private var theme: ThemePreference { ThemePreference(rawValue: themeRaw) ?? .system }

    // SwiftData container. Holding it as a property guarantees it survives
    // the App's full lifetime; SwiftData panics if you let it deallocate.
    private let container: ModelContainer? = {
        let schema = Schema([Category.self, Session.self, TemplateEntry.self])
        return try? ModelContainer(for: schema)
    }()

    var body: some Scene {
        WindowGroup {
            if let container {
            RootView()
                .modelContainer(container)
                .environment(engine)
                .environment(flowSavvy)
                .environment(calendarAgenda)
                .preferredColorScheme(theme.colorScheme)
                .tint(Theme.Color.accent)
                .task {
                    // Seed default categories on first launch.
                    UNUserNotificationCenter.current().delegate = FocusNotificationDelegate.shared
                    SeedHelper.ensureSeeded(container.mainContext)
                    // Ask for notification permission once.
                    await PomodoroEngine.requestAuthorization()
                }
            } else {
                ContentUnavailableView("Local history is unavailable", systemImage: "externaldrive.badge.exclamationmark",
                    description: Text("Your saved data has not been changed. Restart Établi Focus to retry. Timers and synchronization are paused until the history can be opened."))
            }
        }
    }
}
