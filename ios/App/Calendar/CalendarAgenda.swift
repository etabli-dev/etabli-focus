// Copyright 2026 R. Heller
// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
import EventKit
import Observation
import UserNotifications
import CryptoKit

/// Uses system calendar accounts, including CalDAV accounts configured in Settings.
/// The integration never creates or changes calendar events.
@MainActor @Observable
final class CalendarAgenda {
    private let store = EKEventStore()
    private(set) var calendars: [EKCalendar] = []
    private(set) var appointments: [CalendarAppointment] = []
    private(set) var authorized = false
    private(set) var message: String?
    var selectedIDs: Set<String> {
        didSet { UserDefaults.standard.set(Array(selectedIDs), forKey: "focus.calendar.selected"); scheduleRefresh() }
    }
    var leadMinutes: Int {
        didSet { UserDefaults.standard.set(leadMinutes, forKey: "focus.calendar.lead"); scheduleRefresh() }
    }
    var remindersEnabled: Bool {
        didSet { UserDefaults.standard.set(remindersEnabled, forKey: "focus.calendar.reminders"); scheduleRefresh() }
    }
    private var observer: NSObjectProtocol?
    private var refreshing = false
    private var refreshAgain = false
    private var signature = ""
    private let reminderPrefix = "etabli.calendar."

    init() {
        selectedIDs = Set(UserDefaults.standard.stringArray(forKey: "focus.calendar.selected") ?? [])
        leadMinutes = UserDefaults.standard.object(forKey: "focus.calendar.lead") as? Int ?? 5
        remindersEnabled = UserDefaults.standard.object(forKey: "focus.calendar.reminders") as? Bool ?? true
        observer = NotificationCenter.default.addObserver(forName: .EKEventStoreChanged, object: store, queue: .main) { [weak self] _ in
            Task { @MainActor in await self?.refresh() }
        }
    }
    func requestAccess() async {
        do {
            let granted: Bool = try await withCheckedThrowingContinuation { continuation in
                store.requestFullAccessToEvents { granted, error in
                    if let error { continuation.resume(throwing: error) }
                    else { continuation.resume(returning: granted) }
                }
            }
            if granted {
                if UserDefaults.standard.object(forKey: "focus.calendar.selected") == nil {
                    selectedIDs = Set(store.calendars(for: .event).map(\.calendarIdentifier))
                }
                let center = UNUserNotificationCenter.current()
                _ = try await center.requestAuthorization(options: [.alert, .sound])
            }
            await refresh()
        } catch { message = String(localized: "Calendar access could not be requested. You can enable it in Settings.") }
    }
    private func scheduleRefresh() { Task { await refresh() } }
    func refresh() async {
        if refreshing { refreshAgain = true; return }
        refreshing = true
        defer {
            refreshing = false
            if refreshAgain { refreshAgain = false; scheduleRefresh() }
        }
        authorized = EKEventStore.authorizationStatus(for: .event) == .fullAccess
        guard authorized else {
            calendars = []; appointments = []; signature = ""
            await removeReminders()
            let status = EKEventStore.authorizationStatus(for: .event)
            message = status == .notDetermined ? nil : String(localized: "Calendar access is off. Enable Full Access for Établi Focus in Settings.")
            return
        }
        calendars = store.calendars(for: .event).sorted { $0.title < $1.title }
        let selected = calendars.filter { selectedIDs.contains($0.calendarIdentifier) }
        let now = Date()
        let end = Calendar.current.date(byAdding: .day, value: 7, to: now) ?? now.addingTimeInterval(604800)
        let events = selected.isEmpty ? [] : store.events(matching: store.predicateForEvents(withStart: Calendar.current.startOfDay(for: now), end: end, calendars: selected))
        appointments = events.filter { $0.endDate > now && $0.status != .canceled }.map {
            CalendarAppointment(id: "\($0.calendarItemIdentifier)|\($0.startDate.timeIntervalSince1970)",
                                title: $0.title?.isEmpty == false ? $0.title! : String(localized: "Untitled event"),
                                calendarTitle: $0.calendar.title, start: $0.startDate, end: $0.endDate, allDay: $0.isAllDay)
        }.sorted { $0.start < $1.start }
        message = nil
        let authorizationStatus: Int = await withCheckedContinuation { continuation in
            UNUserNotificationCenter.current().getNotificationSettings { settings in
                continuation.resume(returning: settings.authorizationStatus.rawValue)
            }
        }
        if remindersEnabled && authorizationStatus != UNAuthorizationStatus.authorized.rawValue && authorizationStatus != UNAuthorizationStatus.provisional.rawValue {
            message = String(localized: "Calendar events are visible, but notifications are disabled. Enable notifications in Settings to receive reminders.")
        }
        let plan = CalendarReminderPlan.make(events: appointments, now: now, leadMinutes: leadMinutes)
        let newSignature = "\(selectedIDs.sorted())|\(leadMinutes)|\(remindersEnabled)|\(authorizationStatus)|" + appointments.map { "\($0.id)|\($0.title)|\($0.calendarTitle)|\($0.end.timeIntervalSince1970)" }.joined(separator: ";") + "|\(plan.map(\.id))|\(TimeZone.current.identifier)"
        guard newSignature != signature else { return }
        guard remindersEnabled else {
            await removeReminders()
            signature = newSignature
            return
        }
        let center = UNUserNotificationCenter.current()
        let pending = await pendingReminders(center)
        let changes = CalendarReminderPlan.reconcile(plan: plan, pending: pending, now: Date())
        center.removePendingNotificationRequests(withIdentifiers: changes.remove)
        do {
            for reminder in changes.schedule {
                let event = reminder.appointment
                let content = UNMutableNotificationContent()
                content.title = event.title
                let time = event.start.formatted(date: .omitted, time: .shortened)
                content.body = event.allDay ? String(localized: "All day · \(event.calendarTitle)")
                    : reminder.leadMinutes == 0 ? String(localized: "Starts now · \(event.calendarTitle)")
                    : String(localized: "Starts at \(time) · in \(reminder.leadMinutes) minutes")
                content.sound = .default
                content.userInfo = ["calendarStart": event.start.timeIntervalSinceReferenceDate,
                                    "calendarFire": reminder.fire.timeIntervalSince1970,
                                    "calendarFingerprint": reminder.fingerprint]
                let trigger = UNTimeIntervalNotificationTrigger(timeInterval: max(1, reminder.fire.timeIntervalSinceNow), repeats: false)
                try await UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: reminder.id, content: content, trigger: trigger))
            }
            signature = newSignature
        } catch { message = String(localized: "Some calendar reminders could not be scheduled. Open the app to retry.") }
    }
    private func removeReminders() async {
        let center = UNUserNotificationCenter.current()
        let pending = await pendingReminders(center)
        center.removePendingNotificationRequests(withIdentifiers: pending.map(\.id).filter { $0.hasPrefix(reminderPrefix) })
    }
    private func pendingReminders(_ center: UNUserNotificationCenter) async -> [PendingCalendarReminder] {
        await withCheckedContinuation { continuation in
            center.getPendingNotificationRequests { requests in
                let pending = requests.map {
                    PendingCalendarReminder(id: $0.identifier,
                        fire: ($0.content.userInfo["calendarFire"] as? Double).map { Date(timeIntervalSince1970: $0) } ?? ($0.trigger as? UNTimeIntervalNotificationTrigger)?.nextTriggerDate(),
                        fingerprint: $0.content.userInfo["calendarFingerprint"] as? String)
                }
                continuation.resume(returning: pending)
            }
        }
    }
}

/// Calendar reminders must remain visible when the timer is in the foreground.
final class FocusNotificationDelegate: NSObject, UNUserNotificationCenterDelegate, Sendable {
    static let shared = FocusNotificationDelegate()
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        [.banner, .sound, .list]
    }
    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        guard let start = response.notification.request.content.userInfo["calendarStart"] as? Double,
              let url = URL(string: "calshow:\(start)") else { return }
        await MainActor.run { UIApplication.shared.open(url) }
    }
}

import UIKit
