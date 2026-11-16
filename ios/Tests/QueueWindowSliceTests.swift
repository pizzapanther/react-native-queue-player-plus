import XCTest
@_spi(QueuePlayerTests) import QueuePlayer

/// The set of positions the engine holds around the playhead: a trail behind
/// it for cheap backward skips, the floor ahead of it for uninterrupted
/// playback, each position held once.
final class QueueWindowSliceTests: XCTestCase {

  private let floor = QueueWindowArithmetic.floor

  // MARK: - Forward run

  func testTheSliceStartsAtTheCurrentPositionWhenNothingPrecedesIt() {
    let slice = QueueWindowArithmetic.slice(
      currentIndex: 0, trackCount: 30, trail: 2, wraps: false)

    XCTAssertEqual(slice, Array(0 ..< floor))
  }

  func testTheSliceReachesTheFloorAheadOfThePlayhead() {
    let slice = QueueWindowArithmetic.slice(
      currentIndex: 5, trackCount: 30, trail: 2, wraps: false)

    XCTAssertEqual(slice, Array(3 ..< 5) + Array(5 ..< 5 + floor))
  }

  func testAQueueShorterThanTheFloorIsHeldWhole() {
    let slice = QueueWindowArithmetic.slice(
      currentIndex: 1, trackCount: 4, trail: 2, wraps: false)

    XCTAssertEqual(slice, [0, 1, 2, 3])
  }

  func testTheForwardRunStopsAtTheEndWhenTheQueueDoesNotWrap() {
    let slice = QueueWindowArithmetic.slice(
      currentIndex: 28, trackCount: 30, trail: 2, wraps: false)

    XCTAssertEqual(slice, [26, 27, 28, 29])
  }

  // MARK: - Wrapping

  /// The incoming item at a repeat-queue boundary has to be materialised
  /// before the outgoing one ends, or the boundary is a hard cut.
  func testAWrappingQueueCarriesTheHeadIntoTheSliceAtTheTail() {
    let slice = QueueWindowArithmetic.slice(
      currentIndex: 29, trackCount: 30, trail: 2, wraps: true)

    XCTAssertEqual(slice.prefix(3), [27, 28, 29])
    XCTAssertEqual(Array(slice.dropFirst(3)), Array(0 ..< floor - 1))
  }

  func testANonWrappingQueueDoesNotCarryTheHead() {
    let slice = QueueWindowArithmetic.slice(
      currentIndex: 29, trackCount: 30, trail: 2, wraps: false)

    XCTAssertEqual(slice, [27, 28, 29])
  }

  /// Where the wrap brings the forward run back around into the trail, the
  /// slice already covers every position, so it holds each once and the trail
  /// gives way to keep the run unbroken.
  func testAWrappingSliceHoldsTheWholeQueueOnceWithTheTrailGivingWay() {
    let slice = QueueWindowArithmetic.slice(
      currentIndex: 10, trackCount: 11, trail: 2, wraps: true)

    XCTAssertEqual(Set(slice).count, slice.count, "no position is held twice")
    XCTAssertEqual(slice.count, 11, "the whole queue, once each")
    XCTAssertEqual(slice.first, 9, "the trail gave way to the wrapped forward run")
  }

  func testASmallWrappingQueueHoldsOneUnbrokenRunWithEachPositionOnce() {
    for count in 1 ... 14 {
      for cur in 0 ..< count {
        let slice = QueueWindowArithmetic.slice(
          currentIndex: cur, trackCount: count, trail: 2, wraps: true)
        XCTAssertEqual(
          Set(slice).count, slice.count,
          "count=\(count) cur=\(cur) held a position twice")
        XCTAssertTrue(
          slice.allSatisfy { $0 >= 0 && $0 < count },
          "count=\(count) cur=\(cur) held a position outside the queue")
        XCTAssertTrue(slice.contains(cur), "count=\(count) cur=\(cur) dropped the playhead")
        for (position, next) in zip(slice, slice.dropFirst()) {
          XCTAssertEqual(
            (position + 1) % count, next,
            "count=\(count) cur=\(cur) broke the run between \(position) and \(next)")
        }
      }
    }
  }

  /// The trail comes off the end of the queue when the head is playing and the
  /// queue wraps — the only place the backward walk crosses zero.
  func testTheTrailComesFromTheTailWhenTheHeadIsPlayingAndTheQueueWraps() {
    XCTAssertEqual(
      QueueWindowArithmetic.slice(
        currentIndex: 0, trackCount: 30, trail: 2, wraps: true),
      [28, 29] + Array(0 ..< floor))
  }

  /// A queue of one cannot both hold a fresh incoming item and hold each
  /// position once, so it holds the position and the boundary hard-cuts.
  func testAQueueOfOneHoldsItsOnlyPosition() {
    XCTAssertEqual(
      QueueWindowArithmetic.slice(
        currentIndex: 0, trackCount: 1, trail: 2, wraps: true),
      [0])
  }

  // MARK: - Cap

  func testTheSliceNeverExceedsTheTrailPlusFloor() {
    for count in [12, 20, 50] {
      for cur in [0, 5, count - 1] {
        for wraps in [true, false] {
          let slice = QueueWindowArithmetic.slice(
            currentIndex: cur, trackCount: count, trail: 2, wraps: wraps)
          XCTAssertLessThanOrEqual(
            slice.count, min(2 + floor, count),
            "count=\(count) cur=\(cur) wraps=\(wraps) exceeded the cap")
        }
      }
    }
  }

  func testATrailDeeperThanTheFloorHoldsTrailPlusFloorPositions() {
    let slice = QueueWindowArithmetic.slice(
      currentIndex: 50, trackCount: 100, trail: 20, wraps: false)

    XCTAssertEqual(slice, Array(30 ..< 60))
  }

  func testANegativeTrailHoldsNothingBehindThePlayhead() {
    XCTAssertEqual(
      QueueWindowArithmetic.slice(
        currentIndex: 5, trackCount: 30, trail: -1, wraps: false),
      Array(5 ..< 5 + floor))
  }

  func testATrailOfZeroHoldsNothingBehindThePlayhead() {
    let slice = QueueWindowArithmetic.slice(
      currentIndex: 5, trackCount: 30, trail: 0, wraps: false)

    XCTAssertEqual(slice.first, 5)
    XCTAssertEqual(slice.count, floor)
  }

  // MARK: - Nothing to play

  func testAnEmptyOrOutOfRangeQueueHoldsNothing() {
    XCTAssertTrue(
      QueueWindowArithmetic.slice(
        currentIndex: 0, trackCount: 0, trail: 2, wraps: false).isEmpty)
    XCTAssertTrue(
      QueueWindowArithmetic.slice(
        currentIndex: 5, trackCount: 3, trail: 2, wraps: false).isEmpty)
    XCTAssertTrue(
      QueueWindowArithmetic.slice(
        currentIndex: -1, trackCount: 3, trail: 2, wraps: false).isEmpty)
  }
}
