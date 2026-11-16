import XCTest
@testable import QueuePlayer
@_spi(QueuePlayerTests) import QueuePlayer

/// Pure-Swift validation helpers for the JS↔native boundary. Tests
/// hit the helpers directly — they're SPI-exposed for exactly this
/// reason — so the consumer-facing `skipToIndex` / `seekTo` paths
/// are covered without a live AVPlayer or AudioSession.
final class InputGuardsTests: XCTestCase {

  /// A finite value too large for `Int64` is as fatal as an infinite one once
  /// it is scaled to milliseconds, so the guard bounds magnitude, not just
  /// finiteness.
  func testSeekPositionIsBoundedToAConvertibleMagnitude() throws {
    let huge = try XCTUnwrap(InputGuards.sanitisedSeekPosition(1e300))
    XCTAssertLessThan(huge * 1000.0, Double(Int64.max))
    let negative = try XCTUnwrap(InputGuards.sanitisedSeekPosition(-1e300))
    XCTAssertGreaterThan(negative * 1000.0, Double(Int64.min))
  }

  func testSeekPositionPassesOrdinaryValuesThrough() {
    XCTAssertEqual(InputGuards.sanitisedSeekPosition(42.5), 42.5)
    XCTAssertNil(InputGuards.sanitisedSeekPosition(.nan))
    XCTAssertNil(InputGuards.sanitisedSeekPosition(.infinity))
  }

  /// The conversion has to be bounded in `Double` space; `Int(_:)` traps on a
  /// finite value larger than `Int` before any clamp on the result can apply.
  func testLookaheadCountIsBoundedBeforeConversion() {
    XCTAssertEqual(InputGuards.validLookaheadCount(.infinity), 10_000)
    XCTAssertEqual(InputGuards.validLookaheadCount(1e300), 10_000)
    XCTAssertEqual(InputGuards.validLookaheadCount(.nan), 0)
    XCTAssertEqual(InputGuards.validLookaheadCount(-5), 0)
    XCTAssertEqual(InputGuards.validLookaheadCount(7), 7)
  }

  // MARK: - validQueueIndex

  /// A finite value past what `Int` holds means "append", the same as any
  /// other position past the end; converting it first would trap.
  func testInsertPositionClampsAMagnitudeIntCannotHold() {
    XCTAssertEqual(InputGuards.insertPosition(1e300, count: 5), 5)
    XCTAssertEqual(InputGuards.insertPosition(-1e300, count: 5), 0)
  }

  func testInsertPositionPassesOrdinaryValuesThroughAndDropsNonFinite() {
    XCTAssertEqual(InputGuards.insertPosition(2, count: 5), 2)
    XCTAssertEqual(InputGuards.insertPosition(7, count: 5), 5)
    XCTAssertNil(InputGuards.insertPosition(nil, count: 5))
    XCTAssertNil(InputGuards.insertPosition(.nan, count: 5))
    XCTAssertNil(InputGuards.insertPosition(.infinity, count: 5))
  }

  func testValidQueueIndexReturnsIntForInRangeValue() {
    XCTAssertEqual(InputGuards.validQueueIndex(0, count: 5), 0)
    XCTAssertEqual(InputGuards.validQueueIndex(2, count: 5), 2)
    XCTAssertEqual(InputGuards.validQueueIndex(4, count: 5), 4)
  }

  func testValidQueueIndexFloorsFractionalValues() {
    XCTAssertEqual(
      InputGuards.validQueueIndex(2.7, count: 5), 2,
      "fractional indices floor — matches Android (value * 1.0).toLong() parity")
  }

  func testValidQueueIndexRejectsNegative() {
    XCTAssertNil(InputGuards.validQueueIndex(-1, count: 5))
    XCTAssertNil(InputGuards.validQueueIndex(-0.5, count: 5))
  }

  func testValidQueueIndexRejectsAtBoundary() {
    XCTAssertNil(
      InputGuards.validQueueIndex(5, count: 5),
      "boundary is exclusive: value < count")
  }

  func testValidQueueIndexRejectsOversized() {
    XCTAssertNil(InputGuards.validQueueIndex(100, count: 5))
    XCTAssertNil(InputGuards.validQueueIndex(.greatestFiniteMagnitude, count: 5))
  }

  func testValidQueueIndexRejectsNaN() {
    XCTAssertNil(InputGuards.validQueueIndex(.nan, count: 5))
    XCTAssertNil(InputGuards.validQueueIndex(.signalingNaN, count: 5))
  }

  func testValidQueueIndexRejectsInfinity() {
    XCTAssertNil(InputGuards.validQueueIndex(.infinity, count: 5))
    XCTAssertNil(InputGuards.validQueueIndex(-.infinity, count: 5))
  }

  func testValidQueueIndexEmptyQueueRejectsEverything() {
    XCTAssertNil(InputGuards.validQueueIndex(0, count: 0))
    XCTAssertNil(InputGuards.validQueueIndex(-1, count: 0))
    XCTAssertNil(InputGuards.validQueueIndex(.nan, count: 0))
  }

  // MARK: - sanitisedSeekPosition

  func testSanitisedSeekPositionReturnsFiniteValueUnchanged() {
    XCTAssertEqual(InputGuards.sanitisedSeekPosition(0), 0)
    XCTAssertEqual(InputGuards.sanitisedSeekPosition(42.5), 42.5)
    XCTAssertEqual(InputGuards.sanitisedSeekPosition(-3.7), -3.7,
      "negative values pass through; clamping is the caller's job")
  }

  func testSanitisedSeekPositionRejectsNaN() {
    XCTAssertNil(InputGuards.sanitisedSeekPosition(.nan))
    XCTAssertNil(InputGuards.sanitisedSeekPosition(.signalingNaN))
  }

  func testSanitisedSeekPositionRejectsInfinity() {
    XCTAssertNil(InputGuards.sanitisedSeekPosition(.infinity))
    XCTAssertNil(InputGuards.sanitisedSeekPosition(-.infinity))
  }
}
