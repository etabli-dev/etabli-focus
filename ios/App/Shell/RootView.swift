import SwiftUI

struct RootView: View {
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
    }
}
