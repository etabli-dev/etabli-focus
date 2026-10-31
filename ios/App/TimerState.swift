import Foundation

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
    public var startedAt: Date? = nil
    public var endDate: Date?
    public var pausedRemainingSeconds: Double?
    public var totalSeconds: Double
    public var blocksCompletedInSet: Int   // for "long break after N"
    public static let idle = TimerState(phase: .idle, kind: .focus,
                                        categoryID: nil, sessionID: nil,
                                        endDate: nil, pausedRemainingSeconds: nil,
                                        totalSeconds: 0, blocksCompletedInSet: 0)
}

extension TimerState {
    func remainingSeconds(at now: Date) -> Double {
        let budget = min(86400, max(0, totalSeconds))
        switch phase {
        case .running: return min(budget, max(0, (endDate ?? now).timeIntervalSince(now)))
        case .paused: return min(budget, max(0, pausedRemainingSeconds ?? 0))
        case .idle: return 0
        }
    }
    func activeSeconds(at now: Date, completed: Bool) -> Double {
        let budget = min(86400, max(0, totalSeconds))
        return completed ? budget : budget - remainingSeconds(at: now)
    }
}
