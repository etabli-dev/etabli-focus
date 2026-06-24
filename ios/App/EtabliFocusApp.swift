// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

import SwiftUI
import SwiftData

@main
struct EtabliFocusApp: App {

    @AppStorage(ThemePreference.userDefaultsKey) private var themeRaw: String = ThemePreference.system.rawValue
    @State private var engine = PomodoroEngine()

    private var theme: ThemePreference { ThemePreference(rawValue: themeRaw) ?? .system }

    // SwiftData container. Holding it as a property guarantees it survives
    // the App's full lifetime; SwiftData panics if you let it deallocate.
    private let container: ModelContainer = {
        do {
            let schema = Schema([Category.self, Session.self, TemplateEntry.self])
            return try ModelContainer(for: schema)
        } catch {
            // First-launch corrupt store recovery: nuke and rebuild in
            // memory. The user re-creates their template if this happens.
            do {
                let config = ModelConfiguration(isStoredInMemoryOnly: true)
                return try ModelContainer(for: Category.self, Session.self, TemplateEntry.self,
                                          configurations: config)
            } catch {
                fatalError("EtabliFocus: in-memory container fallback also failed: \(error)")
            }
        }
    }()

    var body: some Scene {
        WindowGroup {
            RootView()
                .modelContainer(container)
                .environment(engine)
                .preferredColorScheme(theme.colorScheme)
                .tint(Theme.Color.accent)
                .task {
                    // Seed default categories on first launch.
                    SeedHelper.ensureSeeded(container.mainContext)
                    // Ask for notification permission once.
                    await PomodoroEngine.requestAuthorization()
                }
        }
    }
}
