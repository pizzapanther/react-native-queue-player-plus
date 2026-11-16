import XCTest
@testable import QueuePlayer

/// Unit tests for `MilestoneTracker` — the pure 25/50/75/90% crossing
/// logic behind `onPlaybackMilestone`.
final class MilestoneTrackerTests: XCTestCase {

  /// 200s track, ticks every ~10s of media time.
  private func play(_ t: inout MilestoneTracker, to seconds: Double, duration: Double = 200) -> [Int] {
    t.tick(durationSec: duration, positionSec: seconds)
  }

  func testForwardPlaythroughFiresEachOnceInOrder() {
    var t = MilestoneTracker()
    var fired: [Int] = []
    // Walk 0 -> 100% in 5% steps.
    for pct in stride(from: 0, through: 100, by: 5) {
      fired += play(&t, to: Double(pct) * 2) // 200s track => pct*2 seconds
    }
    XCTAssertEqual(fired, [25, 50, 75, 90])
  }

  func testFirstTickEstablishesBaselineAndDoesNotFire() {
    var t = MilestoneTracker()
    // First observation is already past 25/50 — a mid-track start.
    XCTAssertEqual(t.tick(durationSec: 200, positionSec: 120), []) // 60%
    // Only thresholds ahead of the start fire.
    XCTAssertEqual(t.tick(durationSec: 200, positionSec: 152), [75])
    XCTAssertEqual(t.tick(durationSec: 200, positionSec: 184), [90])
  }

  func testSeekPastThresholdsSuppressesThem() {
    var t = MilestoneTracker()
    _ = t.tick(durationSec: 200, positionSec: 10) // baseline 5%
    // Seek straight to 80% — 25/50/75 are jumped, not played.
    t.seek(durationSec: 200, positionSec: 160)
    XCTAssertEqual(t.tick(durationSec: 200, positionSec: 164), []) // 82%
    XCTAssertEqual(t.tick(durationSec: 200, positionSec: 182), [90]) // only 90 fires
  }

  func testEachThresholdFiresOnlyOncePerPlaythrough() {
    var t = MilestoneTracker()
    _ = t.tick(durationSec: 200, positionSec: 0)
    XCTAssertEqual(t.tick(durationSec: 200, positionSec: 120), [25, 50]) // 60%
    // Seek back to 10% and replay forward — already-fired thresholds
    // do not re-fire.
    t.seek(durationSec: 200, positionSec: 20)
    XCTAssertEqual(t.tick(durationSec: 200, positionSec: 160), [75]) // 80%, 25/50 stay fired
  }

  func testResetReArmsForANewPlaythrough() {
    var t = MilestoneTracker()
    _ = t.tick(durationSec: 200, positionSec: 0)
    XCTAssertEqual(t.tick(durationSec: 200, positionSec: 200), [25, 50, 75, 90])
    t.reset()
    _ = t.tick(durationSec: 200, positionSec: 0)
    XCTAssertEqual(t.tick(durationSec: 200, positionSec: 200), [25, 50, 75, 90])
  }

  func testUnknownDurationFiresNothing() {
    var t = MilestoneTracker()
    XCTAssertEqual(t.tick(durationSec: 0, positionSec: 30), [])
    XCTAssertEqual(t.tick(durationSec: 0, positionSec: 9999), [])
    // Once a duration is known, milestones resume from the current point.
    XCTAssertEqual(t.tick(durationSec: 200, positionSec: 100), []) // baseline 50%
    XCTAssertEqual(t.tick(durationSec: 200, positionSec: 152), [75])
  }

  func testThresholdExactlyOnTickFires() {
    var t = MilestoneTracker()
    _ = t.tick(durationSec: 200, positionSec: 0)
    XCTAssertEqual(t.tick(durationSec: 200, positionSec: 100), [25, 50]) // exactly 50%
  }
}
