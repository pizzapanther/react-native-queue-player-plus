import XCTest
@testable import QueuePlayer

/// Coverage for the pure-Swift `CrossfadeDurationBounds` helper that
/// powers `PlaybackModeStateMachine.requireValid`. Full state-machine
/// validation (constructing `PlaybackMode` instances + driving
/// `setRequested`) is out of scope here — `PlaybackMode` is bridged
/// via Nitro `@_spi` and can't be constructed from a Swift XCTest
/// target. Mirrors the cxx-interop carve-out already documented in
/// AGENTS.md section 2.
final class PlaybackModeStateMachineTests: XCTestCase {

  func testCrossfadeBoundsHelperAcceptsEveryValueOnTheStep() {
    var ms = CrossfadeDurationBounds.minMs
    while ms <= CrossfadeDurationBounds.maxMs {
      XCTAssertTrue(
        CrossfadeDurationBounds.isQuantised(ms),
        "expected \(ms) to be on a \(CrossfadeDurationBounds.stepMs)ms boundary")
      ms += CrossfadeDurationBounds.stepMs
    }
  }

  func testCrossfadeBoundsHelperRejectsNonQuantisedValues() {
    for ms in [1_001.0, 1_234.0, 5_500.5, 7_777.0, 11_999.0] {
      XCTAssertFalse(
        CrossfadeDurationBounds.isQuantised(ms),
        "expected \(ms) to be rejected as off-step")
    }
  }

  func testCrossfadeBoundsHelperConstants() {
    XCTAssertEqual(CrossfadeDurationBounds.minMs, 1_000)
    XCTAssertEqual(CrossfadeDurationBounds.maxMs, 12_000)
    XCTAssertEqual(CrossfadeDurationBounds.stepMs, 500)
  }
}
