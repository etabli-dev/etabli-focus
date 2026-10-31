import Foundation

@main struct TimerCheck {
    static func main() throws {
        let old = #"{"phase":"paused","kind":"focus","categoryID":null,"sessionID":null,"endDate":null,"pausedRemainingSeconds":1200,"totalSeconds":1500,"blocksCompletedInSet":3}"#
        var timer = try JSONDecoder().decode(TimerState.self, from: Data(old.utf8))
        precondition(timer.startedAt == nil)
        precondition(timer.activeSeconds(at: Date(), completed: false) == 300)
        timer.startedAt = Date(timeIntervalSince1970: 1000)
        timer.phase = .running
        timer.endDate = Date(timeIntervalSince1970: 6000)
        precondition(timer.activeSeconds(at: Date(timeIntervalSince1970: 4900), completed: false) == 400)
        precondition(timer.activeSeconds(at: Date(timeIntervalSince1970: 9000), completed: true) == 1500)
        precondition(timer.remainingSeconds(at: Date(timeIntervalSince1970: 9000)) == 0)
        let restored = try JSONDecoder().decode(TimerState.self, from: JSONEncoder().encode(timer))
        precondition(restored == timer)
        timer.phase = .paused
        timer.pausedRemainingSeconds = 100000
        precondition(timer.activeSeconds(at: Date(), completed: false) == 0)
        print("Swift persisted timer compatibility and active-duration checks passed")
    }
}
