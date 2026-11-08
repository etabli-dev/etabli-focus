// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

import Foundation
import CryptoKit

struct CalendarAppointment: Identifiable, Equatable {
    let id: String
    let title: String
    let calendarTitle: String
    let start: Date
    let end: Date
    let allDay: Bool
}
struct CalendarReminder: Equatable {
    let id: String
    let appointment: CalendarAppointment
    let fire: Date
    let leadMinutes: Int
    var fingerprint: String {
        "\(appointment.title)|\(appointment.calendarTitle)|\(appointment.start.timeIntervalSince1970)|\(fire.timeIntervalSince1970)|\(leadMinutes)|\(appointment.allDay)"
    }
}
struct PendingCalendarReminder {
    let id: String
    let fire: Date?
    let fingerprint: String?
}
struct CalendarReminderChanges {
    let remove: [String]
    let schedule: [CalendarReminder]
}

enum CalendarReminderPlan {
    static let prefix = "etabli.calendar."
    /// Never withdraw an already-due request while the OS may be delivering it.
    /// Unknown legacy trigger dates are retained until delivery for the same reason.
    static func reconcile(plan: [CalendarReminder], pending: [PendingCalendarReminder], now: Date) -> CalendarReminderChanges {
        let owned = pending.filter { $0.id.hasPrefix(prefix) }
        let due = owned.filter { $0.fire == nil || $0.fire! <= now }
        let dueIDs = Set(due.map(\.id))
        let future = Array(plan.filter { !dueIDs.contains($0.id) }.prefix(max(0, 60 - due.count)))
        let wanted = Set(future.map(\.id))
        let remove = owned.filter { !dueIDs.contains($0.id) && !wanted.contains($0.id) }.map(\.id)
        let schedule = future.filter { reminder in
            !owned.contains { $0.id == reminder.id && $0.fingerprint == reminder.fingerprint }
        }
        return CalendarReminderChanges(remove: remove, schedule: schedule)
    }
    static func make(events: [CalendarAppointment], now: Date, leadMinutes: Int) -> [CalendarReminder] {
        var reminders: [CalendarReminder] = []; var seen = Set<String>()
        for event in events where event.end > now {
            let appointmentTime = event.allDay
                ? Calendar.current.date(bySettingHour: 9, minute: 0, second: 0, of: event.start) ?? event.start
                : event.start
            for lead in Set([0, max(0, min(60, leadMinutes))]) {
                let fire = appointmentTime.addingTimeInterval(Double(-lead * 60))
                guard fire > now else { continue }
                let digest = SHA256.hash(data: Data("\(event.id)|\(lead)".utf8)).map { String(format: "%02x", $0) }.joined()
                let id = prefix + digest
                guard seen.insert(id).inserted else { continue }
                reminders.append(CalendarReminder(id: id, appointment: event, fire: fire, leadMinutes: lead))
            }
        }
        // Leave four of iOS's 64 pending notification slots for the timer.
        return Array(reminders.sorted { ($0.fire, $0.id) < ($1.fire, $1.id) }.prefix(60))
    }
}
