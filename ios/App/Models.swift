import Foundation
import SwiftData
import SwiftUI

// SwiftData models. Schema version 1.
//
// Design notes:
//  - We seed six default categories on first launch from `Category.defaults`.
//  - The active timer is NOT a SwiftData @Model — it lives in UserDefaults
//    as `TimerState` so it can be restored cheaply on launch even before
//    the SwiftData container finishes opening (matters for end-date math).
//  - Sessions are immutable once written (no edit-history complexity in v1).

// MARK: - Category

@Model
public final class Category {
    @Attribute(.unique) public var id: UUID
    public var name: String
    public var colorHex: String          // hex like "28A745"
    public var defaultFocusMinutes: Int
    public var defaultBreakMinutes: Int
    public var order: Int                // for stable display

    public init(id: UUID = UUID(), name: String, colorHex: String,
                defaultFocusMinutes: Int, defaultBreakMinutes: Int, order: Int) {
        self.id = id; self.name = name; self.colorHex = colorHex
        self.defaultFocusMinutes = defaultFocusMinutes
        self.defaultBreakMinutes = defaultBreakMinutes
        self.order = order
    }

    /// The six built-in categories. Created once if the store is empty.
    /// Colors are picked to be distinguishable while staying inside the
    /// Coder palette — accent green for the highest-priority deep work,
    /// muted variants for the rest.
    public static let defaults: [Category] = [
        Category(name: "Own paper · Data analysis",
                 colorHex: "28A745", defaultFocusMinutes: 50, defaultBreakMinutes: 10, order: 0),
        Category(name: "Own paper · Manuscript writing",
                 colorHex: "1F6FEB", defaultFocusMinutes: 50, defaultBreakMinutes: 10, order: 1),
        Category(name: "External review",
                 colorHex: "C9A35B", defaultFocusMinutes: 50, defaultBreakMinutes: 10, order: 2),
        Category(name: "Internal review",
                 colorHex: "8957E5", defaultFocusMinutes: 50, defaultBreakMinutes: 10, order: 3),
        Category(name: "Literature research & reading",
                 colorHex: "1F9DA5", defaultFocusMinutes: 50, defaultBreakMinutes: 10, order: 4),
        Category(name: "Organizational / admin",
                 colorHex: "6B6B6B", defaultFocusMinutes: 25, defaultBreakMinutes: 5, order: 5),
    ]
}

public extension Category {
    /// Hex → SwiftUI.Color. Falls back to the accent if the stored value
    /// is malformed (shouldn't happen but defensive).
    var color: Color {
        guard let v = UInt32(colorHex, radix: 16) else { return Theme.Color.accent }
        let r = Double((v >> 16) & 0xFF) / 255.0
        let g = Double((v >>  8) & 0xFF) / 255.0
        let b = Double( v        & 0xFF) / 255.0
        return Color(red: r, green: g, blue: b)
    }
}

// MARK: - Session (immutable history)

@Model
public final class Session {
    @Attribute(.unique) public var id: UUID
    public var categoryID: UUID          // not a relationship — survives category deletion
    public var startedAt: Date
    public var plannedEnd: Date
    public var actualEnd: Date?          // nil while running / on cancel
    public var completed: Bool
    public var kind: String              // "focus" or "break"
    public var note: String?

    public init(id: UUID = UUID(), categoryID: UUID, startedAt: Date,
                plannedEnd: Date, actualEnd: Date? = nil,
                completed: Bool = false, kind: String = "focus", note: String? = nil) {
        self.id = id; self.categoryID = categoryID
        self.startedAt = startedAt; self.plannedEnd = plannedEnd
        self.actualEnd = actualEnd; self.completed = completed
        self.kind = kind; self.note = note
    }
}

// MARK: - Weekly template entry

/// One row per (weekday, category) pair. Weekday uses Calendar's convention:
/// 1 = Sunday, 2 = Monday, … 7 = Saturday.
@Model
public final class TemplateEntry {
    @Attribute(.unique) public var id: UUID
    public var weekday: Int
    public var categoryID: UUID
    public var targetBlocks: Int

    public init(id: UUID = UUID(), weekday: Int, categoryID: UUID, targetBlocks: Int) {
        self.id = id; self.weekday = weekday
        self.categoryID = categoryID; self.targetBlocks = targetBlocks
    }
}

// MARK: - First-launch seeding

public enum SeedHelper {
    /// Inserts the six default categories if the store is empty.
    public static func ensureSeeded(_ context: ModelContext) {
        let fetch = FetchDescriptor<Category>()
        let existing = (try? context.fetchCount(fetch)) ?? 0
        guard existing == 0 else { return }
        for c in Category.defaults { context.insert(c) }
        try? context.save()
    }
}
