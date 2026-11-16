import XCTest
@testable import QueuePlayer
@_spi(QueuePlayerTests) import QueuePlayer

/// Pure-Swift tests for the bounded gapless enqueue window.
final class QueueWindowArithmeticTests: XCTestCase {

  private typealias W = QueueWindowArithmetic

  // MARK: - isAtLogicalTail

  func testIsAtLogicalTailMatchesTheLastPosition() {
    XCTAssertTrue(W.isAtLogicalTail(anchorPosition: 9, trackCount: 10))
    XCTAssertFalse(W.isAtLogicalTail(anchorPosition: 8, trackCount: 10))
  }

  func testIsAtLogicalTailIsFalseOnAnEmptyQueue() {
    XCTAssertFalse(W.isAtLogicalTail(anchorPosition: -1, trackCount: 0))
  }

  func testIsAtLogicalTailIsFalseForAnAnchorPastTheEnd() {
    XCTAssertFalse(W.isAtLogicalTail(anchorPosition: 12, trackCount: 10))
  }

  // MARK: - verdict

  func testUndrainedEngineNeverEnds() {
    for mode in [QueueSkipRepeatMode.off, .track, .queue] {
      for tail in [true, false] {
        XCTAssertEqual(
          W.verdict(drained: false, atLogicalTail: tail, repeatMode: mode), .notEnded,
          "mode=\(mode) tail=\(tail)")
      }
    }
  }

  func testRepeatOneAlwaysRefillsTheCurrentPosition() {
    for tail in [true, false] {
      XCTAssertEqual(
        W.verdict(drained: true, atLogicalTail: tail, repeatMode: .track), .refillCurrent,
        "tail=\(tail)")
    }
  }

  func testDrainedMidQueueRefillsTheWindow() {
    XCTAssertEqual(W.verdict(drained: true, atLogicalTail: false, repeatMode: .off), .windowDrained)
    XCTAssertEqual(W.verdict(drained: true, atLogicalTail: false, repeatMode: .queue),
                   .windowDrained)
  }

  func testDrainedAtTheTailEndsUnderRepeatOffAndWrapsUnderRepeatQueue() {
    XCTAssertEqual(W.verdict(drained: true, atLogicalTail: true, repeatMode: .off), .queueEnd)
    XCTAssertEqual(W.verdict(drained: true, atLogicalTail: true, repeatMode: .queue), .wrapToStart)
  }
}

