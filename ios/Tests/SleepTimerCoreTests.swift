import XCTest
@testable import QueuePlayer

/// Pure-logic coverage for `SleepTimerCore` — the wall-clock countdown, the 10s
/// linear fade, the 60s "don't cut a track off" tail rule, and end-of-track
/// conversion. Mirrors the Android `SleepTimerCoreTest`.
final class SleepTimerCoreTests: XCTestCase {

  private func tickBare(_ core: inout SleepTimerCore, _ nowMs: Int64) -> SleepTimerCore.Tick {
    core.tick(nowMs: nowMs, trackDurationSec: 0, trackPositionSec: 0)
  }

  func testInactiveIsIdle() {
    var t = SleepTimerCore()
    let r = tickBare(&t, 1000)
    XCTAssertFalse(t.isActive)
    XCTAssertEqual(r.fadeFraction, 1.0)
    XCTAssertFalse(r.pauseNow)
    XCTAssertFalse(r.stateChanged)
  }

  func testDurationCountsDownAtFullVolumeUntilFadeWindow() {
    var t = SleepTimerCore()
    t.armDuration(seconds: 100, nowMs: 0)
    XCTAssertTrue(t.isActive)
    let mid = tickBare(&t, 50_000)
    XCTAssertEqual(mid.fadeFraction, 1.0)
    XCTAssertFalse(mid.pauseNow)
    XCTAssertEqual(t.remainingSeconds(nowMs: 50_000), 50)
  }

  func testVolumeFadesLinearlyOverFinalTenSeconds() {
    var t = SleepTimerCore()
    t.armDuration(seconds: 100, nowMs: 0)
    XCTAssertEqual(tickBare(&t, 95_000).fadeFraction, 0.5, accuracy: 1e-9)
    XCTAssertEqual(tickBare(&t, 90_000).fadeFraction, 1.0)
  }

  func testDurationFiresPauseAtDeadlineWithNoTail() {
    var t = SleepTimerCore()
    t.armDuration(seconds: 30, nowMs: 0)
    let r = tickBare(&t, 30_000)
    XCTAssertTrue(r.pauseNow)
    XCTAssertTrue(r.stateChanged)
    XCTAssertFalse(t.isActive)
  }

  func testTailRuleDefersToEndOfNearlyFinishedTrack() {
    var t = SleepTimerCore()
    t.armDuration(seconds: 30, nowMs: 0)
    let deferred = t.tick(nowMs: 30_000, trackDurationSec: 200, trackPositionSec: 180)
    XCTAssertFalse(deferred.pauseNow)
    XCTAssertTrue(deferred.stateChanged)
    XCTAssertTrue(t.isActive)
    let fired = t.tick(nowMs: 51_500, trackDurationSec: 300, trackPositionSec: 1)
    XCTAssertTrue(fired.pauseNow)
    XCTAssertFalse(t.isActive)
  }

  func testTailRuleExtendsOnlyOnce() {
    var t = SleepTimerCore()
    t.armDuration(seconds: 10, nowMs: 0)
    let first = t.tick(nowMs: 10_000, trackDurationSec: 100, trackPositionSec: 95)
    XCTAssertFalse(first.pauseNow)
    let second = t.tick(nowMs: 16_000, trackDurationSec: 100, trackPositionSec: 100)
    XCTAssertTrue(second.pauseNow)
  }

  func testFiresWhenTrackHasPlentyLeft() {
    var t = SleepTimerCore()
    t.armDuration(seconds: 30, nowMs: 0)
    let r = t.tick(nowMs: 30_000, trackDurationSec: 200, trackPositionSec: 80)
    XCTAssertTrue(r.pauseNow)
  }

  func testEndOfTrackWaitsThenConverts() {
    var t = SleepTimerCore()
    t.armEndOfTrack()
    XCTAssertTrue(t.isEndOfTrack)
    XCTAssertNil(t.remainingSeconds(nowMs: 1000))
    let waiting = t.tick(nowMs: 1000, trackDurationSec: 200, trackPositionSec: 10)
    XCTAssertFalse(waiting.stateChanged)
    XCTAssertTrue(t.isEndOfTrack)
    let converted = t.tick(nowMs: 2000, trackDurationSec: 200, trackPositionSec: 150)
    XCTAssertTrue(converted.stateChanged)
    XCTAssertFalse(t.isEndOfTrack)
    XCTAssertTrue(t.isActive)
    // Deadline lands AT the boundary (tail=50s), not 1s past it.
    XCTAssertEqual(t.deadlineEpochMs, 2000 + 50_000)
  }

  func testClearResetsToInactive() {
    var t = SleepTimerCore()
    t.armDuration(seconds: 60, nowMs: 0)
    t.clear()
    XCTAssertFalse(t.isActive)
    XCTAssertNil(t.deadlineEpochMs)
    XCTAssertNil(t.remainingSeconds(nowMs: 0))
  }

  func testEndOfTrackFiresPauseAtTrackEndSignal() {
    var t = SleepTimerCore()
    t.armEndOfTrack()
    // Fires at the real track-end signal, before any tail conversion and even
    // when the duration was never known (never ticked).
    let fired = t.fireAtTrackEnd()
    XCTAssertTrue(fired.pauseNow)
    XCTAssertTrue(fired.stateChanged)
    XCTAssertFalse(t.isActive)
  }

  func testEndOfTrackSignalFiresAfterTailConversion() {
    var t = SleepTimerCore()
    t.armEndOfTrack()
    _ = t.tick(nowMs: 1000, trackDurationSec: 200, trackPositionSec: 150) // converts to fade
    // The end-of-track origin survives the conversion, so the boundary signal
    // still owns the pause.
    let fired = t.fireAtTrackEnd()
    XCTAssertTrue(fired.pauseNow)
    XCTAssertFalse(t.isActive)
  }

  func testFireAtTrackEndIsIdempotent() {
    var t = SleepTimerCore()
    t.armEndOfTrack()
    _ = t.fireAtTrackEnd()
    XCTAssertFalse(t.fireAtTrackEnd().pauseNow)
  }

  func testFireAtTrackEndIgnoresDurationTimer() {
    var t = SleepTimerCore()
    t.armDuration(seconds: 60, nowMs: 0)
    let fired = t.fireAtTrackEnd()
    XCTAssertFalse(fired.pauseNow)
    XCTAssertTrue(t.isActive)
  }
}
