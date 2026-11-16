import XCTest
@testable import QueuePlayer

/// Unit tests for `ProgressEmissionGate` — the pure throttle behind the
/// configurable / background-reduced `onProgress` cadence.
///
/// Most tests pass `samplerPeriodMs: 0` to exercise exact interval semantics;
/// the jitter + quantization tests pass the production `500` to verify the
/// half-sampler tolerance that keeps a periodic sampler from dropping ticks.
final class ProgressEmissionGateTests: XCTestCase {

  func testFirstTickAlwaysEmits() {
    var gate = ProgressEmissionGate()
    gate.setIntervals(foregroundMs: 500, backgroundMs: 500, samplerPeriodMs: 0)
    // lastEmittedMs starts at -infinity, so any first observation emits.
    XCTAssertTrue(gate.shouldEmit(nowMs: 0))
  }

  func testForegroundCadenceSuppressesWithinTheInterval() {
    var gate = ProgressEmissionGate()
    gate.setIntervals(foregroundMs: 1000, backgroundMs: 1000, samplerPeriodMs: 0)
    XCTAssertTrue(gate.shouldEmit(nowMs: 0)) // first
    XCTAssertFalse(gate.shouldEmit(nowMs: 500)) // 500 < 1000
    XCTAssertFalse(gate.shouldEmit(nowMs: 999))
    XCTAssertTrue(gate.shouldEmit(nowMs: 1000)) // exactly the interval emits (>=)
    XCTAssertFalse(gate.shouldEmit(nowMs: 1499))
    XCTAssertTrue(gate.shouldEmit(nowMs: 2000))
  }

  func testBackgroundUsesTheSlowerInterval() {
    var gate = ProgressEmissionGate()
    gate.setIntervals(foregroundMs: 500, backgroundMs: 2000, samplerPeriodMs: 0)
    XCTAssertTrue(gate.shouldEmit(nowMs: 0)) // first
    gate.setBackgrounded(true)
    // Now gated on 2000ms, so a 500ms and 1000ms tick both suppress.
    XCTAssertFalse(gate.shouldEmit(nowMs: 500))
    XCTAssertFalse(gate.shouldEmit(nowMs: 1000))
    XCTAssertTrue(gate.shouldEmit(nowMs: 2000))
  }

  func testForegroundReturnFlushesImmediately() {
    var gate = ProgressEmissionGate()
    gate.setIntervals(foregroundMs: 500, backgroundMs: 10_000, samplerPeriodMs: 0)
    XCTAssertTrue(gate.shouldEmit(nowMs: 0))
    gate.setBackgrounded(true)
    XCTAssertFalse(gate.shouldEmit(nowMs: 5000)) // still within the 10s bg interval
    // Returning to the foreground forces the next tick to emit, even though
    // only 5001ms have elapsed since the last background emit at t=0.
    gate.setBackgrounded(false)
    XCTAssertTrue(gate.shouldEmit(nowMs: 5001))
    // Then the normal foreground cadence resumes.
    XCTAssertFalse(gate.shouldEmit(nowMs: 5100))
    XCTAssertTrue(gate.shouldEmit(nowMs: 5601))
  }

  func testBackgroundToBackgroundDoesNotFlush() {
    var gate = ProgressEmissionGate()
    gate.setIntervals(foregroundMs: 500, backgroundMs: 2000, samplerPeriodMs: 0)
    XCTAssertTrue(gate.shouldEmit(nowMs: 0))
    gate.setBackgrounded(true)
    // A redundant setBackgrounded(true) must NOT reset the throttle position.
    gate.setBackgrounded(true)
    XCTAssertFalse(gate.shouldEmit(nowMs: 500))
  }

  func testForegroundToForegroundDoesNotFlush() {
    var gate = ProgressEmissionGate()
    gate.setIntervals(foregroundMs: 1000, backgroundMs: 1000, samplerPeriodMs: 0)
    XCTAssertTrue(gate.shouldEmit(nowMs: 0))
    // A no-op foreground→foreground transition must not reset the position.
    gate.setBackgrounded(false)
    XCTAssertFalse(gate.shouldEmit(nowMs: 500))
  }

  func testSetIntervalsTakesEffectPreservingPosition() {
    var gate = ProgressEmissionGate()
    gate.setIntervals(foregroundMs: 2000, backgroundMs: 2000, samplerPeriodMs: 0)
    XCTAssertTrue(gate.shouldEmit(nowMs: 0))
    XCTAssertFalse(gate.shouldEmit(nowMs: 1000)) // 1000 < 2000
    // Tighten the cadence mid-stream; the last-emitted position is preserved,
    // so a tick 600ms past the last emit now clears the new 500ms interval.
    gate.setIntervals(foregroundMs: 500, backgroundMs: 500, samplerPeriodMs: 0)
    XCTAssertTrue(gate.shouldEmit(nowMs: 600))
  }

  func testDefaultIntervalEmitsEveryTickDespiteJitter() {
    // The production case: interval == the 500ms sampler period. With the
    // half-sampler tolerance, ticks landing a hair under 500ms apart (timer
    // jitter) still emit — otherwise ~half would drop and the rate would sag
    // toward 1Hz.
    var gate = ProgressEmissionGate()
    gate.setIntervals(foregroundMs: 500, backgroundMs: 500, samplerPeriodMs: 500)
    XCTAssertTrue(gate.shouldEmit(nowMs: 0))
    XCTAssertTrue(gate.shouldEmit(nowMs: 480)) // 480 >= 500 - 250
    XCTAssertTrue(gate.shouldEmit(nowMs: 955))
    XCTAssertTrue(gate.shouldEmit(nowMs: 1440))
  }

  func testLargerIntervalQuantizesToEveryNthTick() {
    // interval == 2× the sampler period ⇒ emit every other tick. The tolerance
    // snaps the emit to the on-target tick (1000, 2000) without letting the
    // intermediate ticks (500, 1500) through.
    var gate = ProgressEmissionGate()
    gate.setIntervals(foregroundMs: 1000, backgroundMs: 1000, samplerPeriodMs: 500)
    XCTAssertTrue(gate.shouldEmit(nowMs: 0))
    XCTAssertFalse(gate.shouldEmit(nowMs: 500)) // 500 < 1000 - 250
    XCTAssertTrue(gate.shouldEmit(nowMs: 1000))
    XCTAssertFalse(gate.shouldEmit(nowMs: 1500))
    XCTAssertTrue(gate.shouldEmit(nowMs: 2000))
  }
}
