// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0
import Foundation

@main struct CalendarChecks {
    static func main() {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        func event(_ id: String, seconds: Double, allDay: Bool = false) -> CalendarAppointment {
            CalendarAppointment(id: id, title: "Appointment", calendarTitle: "CalDAV", start: now.addingTimeInterval(seconds), end: now.addingTimeInterval(seconds + 3600), allDay: allDay)
        }
        let upcoming = event("one", seconds: 600)
        let reminders = CalendarReminderPlan.make(events: [upcoming], now: now, leadMinutes: 5)
        assert(reminders.count == 2 && reminders[0].fire == now.addingTimeInterval(300) && reminders[1].fire == upcoming.start)
        assert(reminders.map(\.leadMinutes) == [5, 0])
        assert(CalendarReminderPlan.make(events: [upcoming, upcoming], now: now, leadMinutes: 5) == reminders)
        assert(CalendarReminderPlan.make(events: [upcoming], now: now, leadMinutes: 0).count == 1)
        let nextDay = Calendar.current.date(byAdding: .day, value: 1, to: now)!
        let dayStart = Calendar.current.startOfDay(for: nextDay)
        let allDay = CalendarAppointment(id: "all", title: "All day", calendarTitle: "CalDAV", start: dayStart, end: dayStart.addingTimeInterval(86400), allDay: true)
        let allDayPlan = CalendarReminderPlan.make(events: [allDay], now: now, leadMinutes: 5)
        assert(allDayPlan.count == 2 && Calendar.current.component(.hour, from: allDayPlan[1].fire) == 9)
        assert(Calendar.current.component(.minute, from: allDayPlan[1].fire) == 0)
        assert(CalendarReminderPlan.make(events: [event("past", seconds: -100)], now: now, leadMinutes: 5).isEmpty)
        assert(CalendarReminderPlan.make(events: [event("soon", seconds: 120)], now: now, leadMinutes: 5).count == 1)
        let moved = CalendarReminderPlan.make(events: [event("one-moved", seconds: 1200)], now: now, leadMinutes: 5)
        assert(Set(moved.map(\.id)).isDisjoint(with: Set(reminders.map(\.id))))
        let many = (0..<50).map { event("event-\($0)", seconds: Double(600 + $0 * 600)) }
        let capped = CalendarReminderPlan.make(events: many, now: now, leadMinutes: 5)
        assert(capped.count == 60 && capped == capped.sorted { ($0.fire, $0.id) < ($1.fire, $1.id) })
        assert(CalendarReminderPlan.make(events: [], now: now, leadMinutes: 5).isEmpty)
        let pending = reminders.map { PendingCalendarReminder(id: $0.id, fire: $0.fire, fingerprint: $0.fingerprint) }
        let unchanged = CalendarReminderPlan.reconcile(plan: reminders, pending: pending, now: now)
        assert(unchanged.remove.isEmpty && unchanged.schedule.isEmpty)
        let boundary = now.addingTimeInterval(300)
        let laterPlan = CalendarReminderPlan.make(events: [upcoming], now: boundary, leadMinutes: 5)
        let crossing = CalendarReminderPlan.reconcile(plan: laterPlan, pending: pending, now: boundary)
        assert(crossing.remove.isEmpty && crossing.schedule.isEmpty)
        let cancelled = CalendarReminderPlan.reconcile(plan: [], pending: pending, now: boundary)
        assert(cancelled.remove == [reminders[1].id]) // Due lead remains owned by OS delivery.
        let movedChanges = CalendarReminderPlan.reconcile(plan: moved, pending: pending, now: now)
        assert(Set(movedChanges.remove) == Set(reminders.map(\.id)) && movedChanges.schedule == moved)
        let renamed = CalendarAppointment(id: upcoming.id, title: "Renamed", calendarTitle: upcoming.calendarTitle, start: upcoming.start, end: upcoming.end, allDay: false)
        let renamedPlan = CalendarReminderPlan.make(events: [renamed], now: now, leadMinutes: 5)
        let changed = CalendarReminderPlan.reconcile(plan: renamedPlan, pending: pending, now: now)
        assert(changed.remove.isEmpty && changed.schedule == renamedPlan)
        let legacy = PendingCalendarReminder(id: CalendarReminderPlan.prefix + "legacy", fire: nil, fingerprint: nil)
        let unknown = CalendarReminderPlan.reconcile(plan: capped, pending: [legacy], now: now)
        assert(unknown.remove.isEmpty && unknown.schedule.count == 59)
        print("Calendar reminders: 15 scenario groups passed")
    }
}
