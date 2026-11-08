// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

import Foundation
import Observation
import SwiftUI
import SwiftData
import UserNotifications

// MARK: - Engine

@MainActor
@Observable
public final class PomodoroEngine {
    public private(set) var state: TimerState
    public private(set) var lastError: String?
    public var blocksUntilLongBreak: Int = 4
    public var longBreakMinutes: Int = 20

    private let defaultsKey = "scholarfocus.timer.state"
    private let notificationID = "scholarfocus.completion"

    public init() {
        if let data = UserDefaults.standard.data(forKey: defaultsKey),
           let restored = try? JSONDecoder().decode(TimerState.self, from: data) {
            self.state = restored
        } else {
            self.state = .idle
        }
    }

    // MARK: - Derived view

    public var remainingSeconds: Double {
        state.remainingSeconds(at: Date())
    }

    /// 0…1 progress for the ring.
    public var progress: Double {
        guard state.totalSeconds > 0 else { return 0 }
        return min(1, max(0, 1 - (remainingSeconds / state.totalSeconds)))
    }

    // MARK: - Control

    public func start(categoryID: UUID, kind: TimerState.Kind, minutes: Int, flowSavvyLink: String? = nil) {
        guard state.phase == .idle, (1...1440).contains(minutes) else { return }
        let seconds = Double(minutes) * 60
        let now = Date()
        var s = state
        s.phase = .running
        s.kind = kind
        s.categoryID = categoryID
        s.sessionID = UUID()
        s.flowSavvyLink = kind == .focus ? flowSavvyLink : nil
        s.startedAt = now
        s.endDate = now.addingTimeInterval(seconds)
        s.pausedRemainingSeconds = nil
        s.totalSeconds = seconds
        state = s
        persist()
        scheduleNotification(at: s.endDate!, kind: kind)
    }

    public func pause() {
        guard state.phase == .running else { return }
        let remaining = remainingSeconds
        guard remaining > 0 else { return }
        var s = state
        s.phase = .paused
        s.endDate = nil
        s.pausedRemainingSeconds = remaining
        state = s
        persist()
        cancelNotification()
    }

    public func resume() {
        guard state.phase == .paused, let remaining = state.pausedRemainingSeconds else { return }
        var s = state
        s.phase = .running
        s.endDate = Date().addingTimeInterval(remaining)
        s.pausedRemainingSeconds = nil
        state = s
        persist()
        scheduleNotification(at: s.endDate!, kind: s.kind)
    }

    /// Cancel without completing — records a Session with `completed = false`.
    public func cancel(in context: ModelContext) {
        let ended = state.phase == .running && (state.endDate ?? .distantFuture) <= Date()
        if ended { tick(in: context); return }
        if state.sessionID != nil && !writeSession(into: context, completed: false) { return }
        let count = state.blocksCompletedInSet
        state = .idle
        state.blocksCompletedInSet = count
        persist()
        cancelNotification()
    }

    /// Detects "ran out while we were backgrounded" and finalises the session.
    /// Call from the UI's TimelineView once per second.
    public func tick(in context: ModelContext) {
        guard state.phase == .running, let end = state.endDate else { return }
        if Date() >= end {
            guard writeSession(into: context, completed: true) else { return }
            if state.kind == .focus { state.blocksCompletedInSet += 1 }
            state.phase = .idle
            state.endDate = nil
            state.sessionID = nil
            persist()
        }
    }

    // MARK: - Session writes

    private func writeSession(into context: ModelContext, completed: Bool) -> Bool {
        guard let cid = state.categoryID, let sid = state.sessionID else { return false }
        let now = Date()
        let actualEnd = completed ? (state.endDate ?? now) : now
        let active = state.activeSeconds(at: now, completed: completed)
        let started = state.startedAt ?? actualEnd.addingTimeInterval(-active)
        do {
            let descriptor = FetchDescriptor<Session>(predicate: #Predicate { $0.id == sid })
            let session: Session
            if let existing = try context.fetch(descriptor).first {
                session = existing
            } else {
                session = Session(id: sid, categoryID: cid, startedAt: started,
                                  plannedEnd: state.endDate ?? actualEnd)
                context.insert(session)
            }
            session.actualEnd = actualEnd
            session.activeSeconds = active
            session.note = state.flowSavvyLink
            session.completed = completed
            session.kind = state.kind == .focus ? "focus" : "break"
            try context.save()
            lastError = nil
            return true
        } catch {
            lastError = "Could not save the session. The timer is retained; please retry."
            return false
        }
    }

    // MARK: - Persistence

    private func persist() {
        if let data = try? JSONEncoder().encode(state) {
            UserDefaults.standard.set(data, forKey: defaultsKey)
        }
    }

    // MARK: - Notifications

    /// Asks for permission. Safe to call repeatedly.
    public static func requestAuthorization() async {
        let center = UNUserNotificationCenter.current()
        _ = try? await center.requestAuthorization(options: [.alert, .sound, .badge])
    }

    private func scheduleNotification(at date: Date, kind: TimerState.Kind) {
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: [notificationID])
        let content = UNMutableNotificationContent()
        content.title = kind == .focus ? "Focus block complete" : "Break complete"
        content.body  = kind == .focus
            ? "Suggest a short break — or start the next block."
            : "Time to get back to it when you're ready."
        content.sound = .default
        let interval = max(1, date.timeIntervalSinceNow)
        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: interval, repeats: false)
        let request = UNNotificationRequest(identifier: notificationID, content: content, trigger: trigger)
        center.add(request) { _ in }
    }

    private func cancelNotification() {
        UNUserNotificationCenter.current().removePendingNotificationRequests(
            withIdentifiers: [notificationID]
        )
    }
}
