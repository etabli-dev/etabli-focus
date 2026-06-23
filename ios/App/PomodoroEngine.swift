import Foundation
import Observation
import SwiftUI
import SwiftData
import UserNotifications

// MARK: - Persisted timer state
//
// Active-timer state lives in UserDefaults as JSON. Living outside
// SwiftData on purpose: the timer must survive force-quit / background /
// device restart and be reconstructable BEFORE the @Model container
// finishes opening (otherwise an "almost done" session could lose seconds).
public struct TimerState: Codable, Equatable {
    public enum Kind: String, Codable { case focus, breakShort, breakLong }
    public enum Phase: String, Codable { case running, paused, idle }
    public var phase: Phase
    public var kind: Kind
    public var categoryID: UUID?
    public var sessionID: UUID?
    /// `endDate` is the source of truth for time-remaining while running.
    /// `pausedRemainingSeconds` carries over the budget while paused.
    public var endDate: Date?
    public var pausedRemainingSeconds: Double?
    public var totalSeconds: Double
    public var blocksCompletedInSet: Int   // for "long break after N"
    public static let idle = TimerState(phase: .idle, kind: .focus,
                                        categoryID: nil, sessionID: nil,
                                        endDate: nil, pausedRemainingSeconds: nil,
                                        totalSeconds: 0, blocksCompletedInSet: 0)
}

// MARK: - Engine

@MainActor
@Observable
public final class PomodoroEngine {
    public private(set) var state: TimerState
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
        switch state.phase {
        case .running:
            guard let end = state.endDate else { return 0 }
            return max(0, end.timeIntervalSinceNow)
        case .paused:
            return max(0, state.pausedRemainingSeconds ?? 0)
        case .idle:
            return 0
        }
    }

    /// 0…1 progress for the ring.
    public var progress: Double {
        guard state.totalSeconds > 0 else { return 0 }
        return 1 - (remainingSeconds / state.totalSeconds)
    }

    // MARK: - Control

    public func start(categoryID: UUID, kind: TimerState.Kind, minutes: Int) {
        let seconds = Double(minutes) * 60
        let now = Date()
        var s = state
        s.phase = .running
        s.kind = kind
        s.categoryID = categoryID
        s.sessionID = UUID()
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
        if state.sessionID != nil {
            writeSession(into: context, completed: false)
        }
        state = .idle
        persist()
        cancelNotification()
    }

    /// Detects "ran out while we were backgrounded" and finalises the session.
    /// Call from the UI's TimelineView once per second.
    public func tick(in context: ModelContext) {
        guard state.phase == .running, let end = state.endDate else { return }
        if Date() >= end {
            if state.kind == .focus {
                writeSession(into: context, completed: true)
                state.blocksCompletedInSet += 1
            }
            state.phase = .idle
            state.endDate = nil
            state.sessionID = nil
            persist()
        }
    }

    // MARK: - Session writes

    private func writeSession(into context: ModelContext, completed: Bool) {
        guard let cid = state.categoryID, let sid = state.sessionID else { return }
        let actualEnd = Date()
        let started = (state.endDate ?? actualEnd).addingTimeInterval(-state.totalSeconds)
        let session = Session(
            id: sid,
            categoryID: cid,
            startedAt: started,
            plannedEnd: state.endDate ?? actualEnd,
            actualEnd: actualEnd,
            completed: completed,
            kind: state.kind == .focus ? "focus" : "break"
        )
        context.insert(session)
        try? context.save()
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
