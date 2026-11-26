// Copyright 2026 R. Heller
// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI
import SwiftData
import UserNotifications

@main
struct EtabliFocusApp: App {

    @State private var engine = PomodoroEngine()
    @State private var flowSavvy = FlowSavvyStore()
    @State private var interstice = IntersticeStore()
    @State private var calendarAgenda = CalendarAgenda()

    init() { AppearancePreference.migrateLegacyMode(in: .standard) }

    // SwiftData container. Holding it as a property guarantees it survives
    // the App's full lifetime; SwiftData panics if you let it deallocate.
    private let container: ModelContainer? = {
        let schema = Schema([Category.self, Session.self, TemplateEntry.self])
        return try? ModelContainer(for: schema)
    }()

    var body: some Scene {
        WindowGroup {
            Group {
                if let container {
                    RootView()
                        .modelContainer(container)
                        .environment(engine)
                        .environment(flowSavvy)
                        .environment(interstice)
                        .environment(calendarAgenda)
                        .task {
                            // Seed default categories on first launch.
                            UNUserNotificationCenter.current().delegate = FocusNotificationDelegate.shared
                            SeedHelper.ensureSeeded(container.mainContext)
                        }
                } else {
                    ContentUnavailableView("Local history is unavailable", systemImage: "externaldrive.badge.exclamationmark",
                        description: Text("Your saved data has not been changed. Restart Établi Focus to retry. Timers and synchronization are paused until the history can be opened."))
                }
            }
            .etabliAppearance()
        }
    }
}
