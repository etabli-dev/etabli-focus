import SwiftUI
import SwiftData
import Charts

struct StatsView: View {
    @Query(sort: \Category.order) private var categories: [Category]
    @Query private var sessions: [Session]

    @State private var window: Window = .week
    enum Window: String, CaseIterable, Identifiable { case week, month; var id: String { rawValue } }

    private var windowStart: Date {
        let cal = Calendar.current
        switch window {
        case .week:
            return cal.date(byAdding: .day, value: -6, to: cal.startOfDay(for: Date())) ?? Date()
        case .month:
            return cal.date(byAdding: .day, value: -29, to: cal.startOfDay(for: Date())) ?? Date()
        }
    }

    private var completedFocus: [Session] {
        sessions.filter {
            $0.kind == "focus" && $0.completed && $0.startedAt >= windowStart
        }
    }

    var body: some View {
        NavigationStack {
            ZStack {
                Theme.Color.paper.ignoresSafeArea()
                ScrollView {
                    VStack(alignment: .leading, spacing: Theme.Space.lg) {
                        PromptHeader(["stats"])
                        Text("Focus stats")
                            .font(Theme.Font.display).foregroundStyle(Theme.Color.ink)
                        Picker("Window", selection: $window) {
                            ForEach(Window.allCases) { Text($0.rawValue.capitalized).tag($0) }
                        }.pickerStyle(.segmented)
                        totalsCard
                        chartCard
                        breakdownCard
                    }.padding(Theme.Space.lg)
                }
            }
            .navigationBarHidden(true)
        }
    }

    private var totalsCard: some View {
        Card(title: "totals", systemImage: "sum") {
            HStack(spacing: Theme.Space.xl) {
                bigNumber("blocks", "\(completedFocus.count)")
                bigNumber("hours",
                          String(format: "%.1f",
                                 completedFocus.reduce(0) { $0 + $1.plannedEnd.timeIntervalSince($1.startedAt) } / 3600))
            }
        }
    }

    private func bigNumber(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            MonoLabel(label, color: Theme.Color.faint)
            Text(value).font(Theme.Font.display).foregroundStyle(Theme.Color.accent)
        }
    }

    private var chartCard: some View {
        Card(title: "blocks per day", systemImage: "chart.bar") {
            if completedFocus.isEmpty {
                EmptyState(
                    title: "no completed blocks yet",
                    detail: "Complete a focus block to start filling the chart.",
                    systemImage: "chart.bar.xaxis"
                ).frame(height: 220)
            } else {
                Chart {
                    let cal = Calendar.current
                    ForEach(groupedByDay(), id: \.day) { row in
                        ForEach(row.byCategory, id: \.categoryID) { c in
                            BarMark(
                                x: .value("Day", row.day, unit: .day),
                                y: .value("Blocks", c.count)
                            )
                            .foregroundStyle(color(for: c.categoryID))
                        }
                    }
                }
                .chartXAxis {
                    AxisMarks(values: .stride(by: .day)) { value in
                        AxisGridLine().foregroundStyle(Theme.Color.hairline)
                        AxisValueLabel(format: .dateTime.weekday(.narrow))
                            .font(Theme.Font.mono).foregroundStyle(Theme.Color.faint)
                    }
                }
                .chartYAxis {
                    AxisMarks { _ in
                        AxisGridLine().foregroundStyle(Theme.Color.hairline)
                        AxisValueLabel().font(Theme.Font.mono).foregroundStyle(Theme.Color.faint)
                    }
                }
                .frame(height: 220)
            }
        }
    }

    private var breakdownCard: some View {
        Card(title: "by category", systemImage: "tag") {
            if completedFocus.isEmpty {
                MonoLabel("(no data)", color: Theme.Color.faint)
            } else {
                VStack(spacing: 0) {
                    let counts = countsByCategory()
                    ForEach(categories) { cat in
                        let n = counts[cat.id] ?? 0
                        if n > 0 {
                            HStack {
                                Circle().fill(cat.color).frame(width: 8, height: 8)
                                Text(cat.name).font(Theme.Font.body).foregroundStyle(Theme.Color.ink)
                                Spacer()
                                MonoLabel("\(n) blocks", color: Theme.Color.faint)
                            }.padding(.vertical, Theme.Space.xs)
                            Divider().background(Theme.Color.hairline)
                        }
                    }
                }
            }
        }
    }

    // MARK: - Aggregation

    private struct DayRow { let day: Date; let byCategory: [DayCategory] }
    private struct DayCategory { let categoryID: UUID; let count: Int }

    private func groupedByDay() -> [DayRow] {
        let cal = Calendar.current
        let grouped = Dictionary(grouping: completedFocus) {
            cal.startOfDay(for: $0.startedAt)
        }
        return grouped.keys.sorted().map { day in
            let dayItems = grouped[day] ?? []
            let byCat = Dictionary(grouping: dayItems, by: \.categoryID)
                .map { DayCategory(categoryID: $0.key, count: $0.value.count) }
                .sorted { $0.count > $1.count }
            return DayRow(day: day, byCategory: byCat)
        }
    }
    private func countsByCategory() -> [UUID: Int] {
        Dictionary(grouping: completedFocus, by: \.categoryID).mapValues(\.count)
    }
    private func color(for id: UUID) -> Color {
        categories.first { $0.id == id }?.color ?? Theme.Color.accent
    }
}
