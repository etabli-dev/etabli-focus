// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

import SwiftUI
import SwiftData

// Weekly template — target focus blocks per (category × weekday). Single
// editable singleton. Persists as a set of TemplateEntry rows.
struct TemplateView: View {
    @Environment(\.modelContext) private var context
    @Query(sort: \Category.order) private var categories: [Category]
    @Query private var entries: [TemplateEntry]

    private let weekdayLabels = ["S", "M", "T", "W", "T", "F", "S"]  // Cal weekday 1..7

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
                    }.padding(Theme.Space.lg)
                }
            }
            .navigationBarHidden(true)
        }
    }

    private var grid: some View {
        VStack(spacing: Theme.Space.sm) {
            HStack(spacing: 0) {
                Text("").frame(width: 160, alignment: .leading)
                ForEach(1...7, id: \.self) { wd in
                    Text(weekdayLabels[wd - 1])
                        .font(Theme.Font.mono)
                        .foregroundStyle(Theme.Color.faint)
                        .frame(maxWidth: .infinity)
                }
            }
            Divider().background(Theme.Color.hairline)
            ForEach(categories) { cat in
                HStack(spacing: 0) {
                    HStack(spacing: Theme.Space.xs) {
                        Circle().fill(cat.color).frame(width: 8, height: 8)
                        Text(cat.name)
                            .font(Theme.Font.body).foregroundStyle(Theme.Color.ink)
                            .lineLimit(1).truncationMode(.tail)
                    }
                    .frame(width: 160, alignment: .leading)
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

    private func cell(category: Category, weekday: Int) -> some View {
        let current = entries.first { $0.categoryID == category.id && $0.weekday == weekday }
        let value = current?.targetBlocks ?? 0
        return Stepper(value: Binding(
            get: { value },
            set: { newValue in
                setBlocks(category: category, weekday: weekday, blocks: newValue)
            }
        ), in: 0...12) {
            Text("\(value)")
                .font(Theme.Font.monoBody)
                .foregroundStyle(value > 0 ? category.color : Theme.Color.faint)
                .frame(maxWidth: .infinity)
        }
        .labelsHidden()
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
