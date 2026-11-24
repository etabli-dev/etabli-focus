// Copyright 2026 R. Heller
// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI
import SwiftData

// Weekly template — target focus blocks per (category × weekday). Single
// editable singleton. Persists as a set of TemplateEntry rows.
struct TemplateView: View {
    @Environment(\.modelContext) private var context
    @Query(sort: \Category.order) private var categories: [Category]
    @Query private var entries: [TemplateEntry]
    @Environment(\.horizontalSizeClass) private var sizeClass

    @Environment(\.locale) private var locale
    /// Weekday names in the app's language, indexed like Calendar's weekday (1 = Sunday … 7 = Saturday) minus one.
    private var calendar: Calendar { var calendar = Calendar(identifier: .gregorian); calendar.locale = locale; return calendar }
    private var weekdayLabels: [String] { calendar.veryShortStandaloneWeekdaySymbols }
    private var weekdayNames: [String] { calendar.standaloneWeekdaySymbols }
    /// Category-name column; the seven day cells share the rest, so the grid fits a phone and an iPad column.
    private var nameWidth: CGFloat { sizeClass == .compact ? 96 : 160 }

    var body: some View {
        NavigationStack {
            ZStack {
                Theme.Color.paper.ignoresSafeArea()
                ScrollView {
                    VStack(alignment: .leading, spacing: Theme.Space.lg) {
                        PromptHeader(["template"])
                        Text("Weekly focus template")
                            .font(Theme.Font.display).foregroundStyle(Theme.Color.ink)
                        MonoLabel("target focus blocks per category per weekday — drives Today's plan.",
                                  color: Theme.Color.faint)
                        Card(title: "edit", systemImage: "square.grid.3x3") {
                            grid
                        }
                    }.padding(Theme.Space.lg).readableColumn()
                }
            }
            .navigationBarHidden(true)
        }
    }

    private var grid: some View {
        VStack(spacing: Theme.Space.sm) {
            HStack(spacing: 0) {
                Text("").frame(width: nameWidth, alignment: .leading)
                ForEach(1...7, id: \.self) { wd in
                    Text(weekdayLabels[wd - 1])
                        .font(Theme.Font.mono)
                        .foregroundStyle(Theme.Color.faint)
                        .frame(maxWidth: .infinity)
                        .accessibilityLabel(weekdayNames[wd - 1])
                }
            }
            Divider().background(Theme.Color.hairline)
            ForEach(categories) { cat in
                HStack(spacing: 0) {
                    HStack(spacing: Theme.Space.xs) {
                        Circle().fill(cat.color).frame(width: 8, height: 8)
                        Text(cat.name)
                            .font(Theme.Font.body).foregroundStyle(Theme.Color.ink)
                            .lineLimit(2).truncationMode(.tail)
                    }
                    .frame(width: nameWidth, alignment: .leading)
                    ForEach(1...7, id: \.self) { wd in
                        cell(category: cat, weekday: wd)
                            .frame(maxWidth: .infinity)
                    }
                }
                if cat.id != categories.last?.id {
                    Divider().background(Theme.Color.hairline)
                }
            }
        }
    }

    /// A day's target as a menu (0–12 blocks): narrow enough for seven per row on a phone.
    private func cell(category: Category, weekday: Int) -> some View {
        let current = entries.first { $0.categoryID == category.id && $0.weekday == weekday }
        let value = current?.targetBlocks ?? 0
        return Menu {
            Picker("Target blocks", selection: Binding(
                get: { value },
                set: { newValue in
                    setBlocks(category: category, weekday: weekday, blocks: newValue)
                }
            )) {
                ForEach(0...12, id: \.self) { Text("\($0)").tag($0) }
            }
        } label: {
            Text("\(value)")
                .font(Theme.Font.monoBody)
                .foregroundStyle(value > 0 ? category.color : Theme.Color.faint)
                .frame(maxWidth: .infinity, minHeight: 44)
                .background(RoundedRectangle(cornerRadius: Theme.Radius.sm).fill(Theme.Color.paper))
                .padding(1)
                .contentShape(Rectangle())
        }
        .accessibilityLabel("\(category.name), \(weekdayNames[weekday - 1])")
        .accessibilityValue("\(value) blocks")
    }

    private func setBlocks(category: Category, weekday: Int, blocks: Int) {
        let existing = entries.first { $0.categoryID == category.id && $0.weekday == weekday }
        if blocks == 0 {
            if let e = existing { context.delete(e) }
        } else if let e = existing {
            e.targetBlocks = blocks
        } else {
            context.insert(TemplateEntry(weekday: weekday, categoryID: category.id, targetBlocks: blocks))
        }
        try? context.save()
    }
}
