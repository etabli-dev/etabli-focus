// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

import SwiftUI
import SwiftData

struct CategoriesView: View {
    @Environment(\.modelContext) private var context
    @Query(sort: \Category.order) private var categories: [Category]
    @State private var editing: Category?
    @State private var creating = false

    var body: some View {
        NavigationStack {
            ZStack {
                Theme.Color.paper.ignoresSafeArea()
                ScrollView {
                    VStack(alignment: .leading, spacing: Theme.Space.lg) {
                        PromptHeader(["categories"]) {
                            Button { creating = true } label: {
                                Image(systemName: "plus.circle").foregroundStyle(Theme.Color.accent)
                            }.buttonStyle(.plain)
                        }
                        Text("Categories")
                            .font(Theme.Font.display).foregroundStyle(Theme.Color.ink)
                        MonoLabel("color and default focus/break length per category.",
                                  color: Theme.Color.faint)
                        Card(title: "all categories", systemImage: "tag") {
                            VStack(spacing: 0) {
                                ForEach(categories) { cat in
                                    Button { editing = cat } label: {
                                        ListRow(
                                            title: cat.name,
                                            metadata: "\(cat.defaultFocusMinutes) min focus · #\(cat.colorHex)",
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
                    }.padding(Theme.Space.lg)
                }
            }
            .navigationBarHidden(true)
            .sheet(item: $editing) { cat in
                CategoryEditSheet(category: cat)
            }
            .sheet(isPresented: $creating) {
                CategoryEditSheet(category: nil)
            }
        }
    }
}

private struct CategoryEditSheet: View {
    let category: Category?
    @Environment(\.modelContext) private var context
    @Environment(\.dismiss) private var dismiss
    @Query(sort: \Category.order) private var allCategories: [Category]

    @State private var name: String = ""
    @State private var colorHex: String = "28A745"
    @State private var focus: Int = 50
    @State private var breakMin: Int = 10

    private let palette: [String] = [
        "28A745", "1F6FEB", "C9A35B", "8957E5", "1F9DA5", "6B6B6B",
        "9B3B3B", "1A1A1A",
    ]

    var body: some View {
        NavigationStack {
            ZStack {
                Theme.Color.paper.ignoresSafeArea()
                ScrollView {
                    VStack(alignment: .leading, spacing: Theme.Space.lg) {
                        Text(category == nil ? "New category" : "Edit category")
                            .font(Theme.Font.title).foregroundStyle(Theme.Color.ink)
                        Card(title: "name", systemImage: "tag") {
                            TextField("e.g. Grant writing", text: $name)
                                .textFieldStyle(.plain)
                                .font(Theme.Font.monoBody)
                                .padding(Theme.Space.sm)
                                .overlay(RoundedRectangle(cornerRadius: Theme.Radius.sm)
                                    .strokeBorder(Theme.Color.hairline, lineWidth: 1))
                        }
                        Card(title: "color", systemImage: "paintpalette") {
                            LazyVGrid(columns: [GridItem(.adaptive(minimum: 44))], spacing: Theme.Space.sm) {
                                ForEach(palette, id: \.self) { hex in
                                    Button { colorHex = hex } label: {
                                        Circle().fill(colorFromHex(hex))
                                            .frame(width: 36, height: 36)
                                            .overlay(Circle()
                                                .strokeBorder(colorHex == hex ? Theme.Color.ink : Theme.Color.hairline,
                                                              lineWidth: colorHex == hex ? 2 : 1))
                                    }.buttonStyle(.plain)
                                }
                            }
                        }
                        Card(title: "durations", systemImage: "clock") {
                            VStack(spacing: Theme.Space.md) {
                                Stepper(value: $focus, in: 5...120, step: 5) {
                                    HStack {
                                        MonoLabel("focus", color: Theme.Color.faint); Spacer()
                                        MonoLabel("\(focus) min")
                                    }
                                }
                                Stepper(value: $breakMin, in: 1...60, step: 1) {
                                    HStack {
                                        MonoLabel("break", color: Theme.Color.faint); Spacer()
                                        MonoLabel("\(breakMin) min")
                                    }
                                }
                            }
                        }
                        actionRow
                    }.padding(Theme.Space.lg)
                }
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }.font(Theme.Font.mono)
                }
            }
            .onAppear {
                if let c = category {
                    name = c.name; colorHex = c.colorHex
                    focus = c.defaultFocusMinutes; breakMin = c.defaultBreakMinutes
                }
            }
        }
    }

    private var actionRow: some View {
        HStack(spacing: Theme.Space.md) {
            PrimaryButton(category == nil ? "Create" : "Save", systemImage: "checkmark.seal",
                          enabled: !name.trimmingCharacters(in: .whitespaces).isEmpty) {
                save()
            }
            if let c = category {
                Button(role: .destructive) {
                    context.delete(c); try? context.save(); dismiss()
                } label: {
                    Text("Delete").font(Theme.Font.body.weight(.semibold))
                        .padding(.horizontal, Theme.Space.md).padding(.vertical, Theme.Space.sm)
                        .foregroundStyle(Theme.Color.surface)
                        .background(Theme.Color.danger)
                        .clipShape(RoundedRectangle(cornerRadius: Theme.Radius.sm))
                }.buttonStyle(.plain)
            }
        }
    }

    private func save() {
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        if let c = category {
            c.name = trimmed; c.colorHex = colorHex
            c.defaultFocusMinutes = focus; c.defaultBreakMinutes = breakMin
        } else {
            let next = (allCategories.map(\.order).max() ?? -1) + 1
            context.insert(Category(name: trimmed, colorHex: colorHex,
                                    defaultFocusMinutes: focus,
                                    defaultBreakMinutes: breakMin, order: next))
        }
        try? context.save()
        dismiss()
    }

    private func colorFromHex(_ hex: String) -> Color {
        guard let v = UInt32(hex, radix: 16) else { return Theme.Color.accent }
        return Color(red: Double((v >> 16) & 0xFF)/255,
                     green: Double((v >> 8) & 0xFF)/255,
                     blue: Double(v & 0xFF)/255)
    }
}
