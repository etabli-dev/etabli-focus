import SwiftUI

struct SettingsView: View {
    @AppStorage(ThemePreference.userDefaultsKey) private var themeRaw: String = ThemePreference.system.rawValue
    @AppStorage("scholarfocus.longBreak.afterN") private var longBreakAfter: Int = 4
    @AppStorage("scholarfocus.longBreak.minutes") private var longBreakMinutes: Int = 20

    private var theme: ThemePreference { ThemePreference(rawValue: themeRaw) ?? .system }

    var body: some View {
        NavigationStack {
            ZStack {
                Theme.Color.paper.ignoresSafeArea()
                ScrollView {
                    VStack(alignment: .leading, spacing: Theme.Space.lg) {
                        PromptHeader(["settings"])
                        Text("Settings")
                            .font(Theme.Font.display).foregroundStyle(Theme.Color.ink)
                        themeCard
                        breakCard
                        aboutCard
                    }.padding(Theme.Space.lg)
                }
            }
            .navigationBarHidden(true)
        }
    }

    private var themeCard: some View {
        Card(title: "appearance", systemImage: "paintbrush") {
            VStack(alignment: .leading, spacing: Theme.Space.sm) {
                Picker("Theme", selection: Binding(
                    get: { theme }, set: { themeRaw = $0.rawValue }
                )) {
                    ForEach(ThemePreference.allCases) { p in
                        Label(p.label, systemImage: p.systemImage).tag(p)
                    }
                }.pickerStyle(.segmented)
                MonoLabel("Auto follows the system Light / Dark setting.",
                          color: Theme.Color.faint)
            }
        }
    }

    private var breakCard: some View {
        Card(title: "long breaks", systemImage: "moon.zzz") {
            VStack(spacing: Theme.Space.md) {
                Stepper(value: $longBreakAfter, in: 2...8) {
                    HStack {
                        MonoLabel("after N focus blocks", color: Theme.Color.faint)
                        Spacer(); MonoLabel("\(longBreakAfter)")
                    }
                }
                Stepper(value: $longBreakMinutes, in: 10...60, step: 5) {
                    HStack {
                        MonoLabel("long break length", color: Theme.Color.faint)
                        Spacer(); MonoLabel("\(longBreakMinutes) min")
                    }
                }
            }
        }
    }

    private var aboutCard: some View {
        Card(title: "about", systemImage: "info.circle") {
            VStack(alignment: .leading, spacing: Theme.Space.sm) {
                Text("EtabliFocus")
                    .font(Theme.Font.headline).foregroundStyle(Theme.Color.ink)
                Text("Pomodoro for academic work — category-aware, weekly-template-driven, completion stats. Offline only; no analytics; no tracking.")
                    .font(Theme.Font.body).foregroundStyle(Theme.Color.faint)
                MonoLabel("v\(Bundle.main.shortVersion) (\(Bundle.main.buildVersion))",
                          color: Theme.Color.faint)
            }
        }
    }
}

private extension Bundle {
    var shortVersion: String { (infoDictionary?["CFBundleShortVersionString"] as? String) ?? "—" }
    var buildVersion: String { (infoDictionary?["CFBundleVersion"] as? String) ?? "—" }
}
