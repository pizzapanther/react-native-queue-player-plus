import Foundation
import XCTest
@testable import QueuePlayer

/// Coverage for the single-slot pending intent buffer. Drives
/// `push` / `drain` / `peek` / `clear` / `isEmpty` directly with a
/// synthetic `Sendable` payload so the generic shape is exercised
/// without dragging Nitrogen-bridged request types into the test
/// target.
final class PendingIntentBufferTests: XCTestCase {

  private struct Sample: Sendable, Equatable {
    let payload: String
  }

  /// Synchronously-controllable clock for deterministic TTL tests.
  private final class FakeClock: @unchecked Sendable {
    private let lock = NSLock()
    private var current: Date
    init(_ start: Date) { self.current = start }
    func now() -> Date {
      lock.lock()
      defer { lock.unlock() }
      return current
    }
    func advance(by seconds: TimeInterval) {
      lock.lock()
      defer { lock.unlock() }
      current = current.addingTimeInterval(seconds)
    }
  }

  private func makeBuffer(
    ttl: TimeInterval = 20,
    clock: FakeClock
  ) -> PendingIntentBuffer<Sample> {
    return PendingIntentBuffer<Sample>(ttl: ttl, clock: { [clock] in clock.now() })
  }

  func testEmptyBufferDrainsNil() {
    let buffer = PendingIntentBuffer<Sample>()
    XCTAssertNil(buffer.drain())
    XCTAssertNil(buffer.peek())
    XCTAssertTrue(buffer.isEmpty)
  }

  func testPushThenDrainReturnsRequest() {
    let buffer = PendingIntentBuffer<Sample>()
    buffer.push(Sample(payload: "first"))
    XCTAssertEqual(buffer.drain(), Sample(payload: "first"))
  }

  func testDrainEmptiesTheBuffer() {
    let buffer = PendingIntentBuffer<Sample>()
    buffer.push(Sample(payload: "first"))
    _ = buffer.drain()
    XCTAssertNil(buffer.drain())
    XCTAssertTrue(buffer.isEmpty)
  }

  func testSecondPushReplacesFirst() {
    let buffer = PendingIntentBuffer<Sample>()
    buffer.push(Sample(payload: "first"))
    buffer.push(Sample(payload: "second"))
    XCTAssertEqual(buffer.drain(), Sample(payload: "second"))
    XCTAssertNil(buffer.drain())
  }

  func testPeekDoesNotEmpty() {
    let buffer = PendingIntentBuffer<Sample>()
    buffer.push(Sample(payload: "first"))
    XCTAssertEqual(buffer.peek(), Sample(payload: "first"))
    XCTAssertEqual(buffer.peek(), Sample(payload: "first"))
    XCTAssertEqual(buffer.drain(), Sample(payload: "first"))
  }

  func testExpiredEntryDrainsNil() {
    let clock = FakeClock(Date(timeIntervalSince1970: 1_000))
    let buffer = makeBuffer(ttl: 20, clock: clock)
    buffer.push(Sample(payload: "stale"))
    clock.advance(by: 21)
    XCTAssertNil(buffer.drain())
  }

  func testExpiredEntryPeeksNilAndClears() {
    let clock = FakeClock(Date(timeIntervalSince1970: 1_000))
    let buffer = makeBuffer(ttl: 20, clock: clock)
    buffer.push(Sample(payload: "stale"))
    clock.advance(by: 25)
    XCTAssertNil(buffer.peek())
    XCTAssertTrue(buffer.isEmpty)
    XCTAssertNil(buffer.drain())
  }

  func testJustBeforeTtlBoundaryStillDrains() {
    let clock = FakeClock(Date(timeIntervalSince1970: 1_000))
    let buffer = makeBuffer(ttl: 20, clock: clock)
    buffer.push(Sample(payload: "fresh"))
    clock.advance(by: 19.9)
    XCTAssertEqual(buffer.drain(), Sample(payload: "fresh"))
  }

  func testZeroTtlSameInstantDrains() {
    let clock = FakeClock(Date(timeIntervalSince1970: 1_000))
    let buffer = makeBuffer(ttl: 0, clock: clock)
    buffer.push(Sample(payload: "now"))
    XCTAssertEqual(buffer.drain(), Sample(payload: "now"))
  }

  func testZeroTtlAfterAdvanceDropsEntry() {
    let clock = FakeClock(Date(timeIntervalSince1970: 1_000))
    let buffer = makeBuffer(ttl: 0, clock: clock)
    buffer.push(Sample(payload: "now"))
    clock.advance(by: 0.001)
    XCTAssertNil(buffer.drain())
  }

  func testNegativeClockSkewClampsToZeroElapsed() {
    let clock = FakeClock(Date(timeIntervalSince1970: 1_000))
    let buffer = makeBuffer(ttl: 20, clock: clock)
    buffer.push(Sample(payload: "fresh"))
    clock.advance(by: -10)
    XCTAssertEqual(buffer.drain(), Sample(payload: "fresh"))
  }

  func testPushAfterPeekClearedExpiredSlotReturnsFreshEntry() {
    let clock = FakeClock(Date(timeIntervalSince1970: 1_000))
    let buffer = makeBuffer(ttl: 20, clock: clock)
    buffer.push(Sample(payload: "stale"))
    clock.advance(by: 25)
    XCTAssertNil(buffer.peek())
    XCTAssertTrue(buffer.isEmpty)
    buffer.push(Sample(payload: "fresh"))
    XCTAssertEqual(buffer.drain(), Sample(payload: "fresh"))
  }

  func testClearEmptiesWithoutReturn() {
    let buffer = PendingIntentBuffer<Sample>()
    buffer.push(Sample(payload: "first"))
    buffer.clear()
    XCTAssertNil(buffer.drain())
    XCTAssertTrue(buffer.isEmpty)
  }

  func testInterleavedPushAndDrainSerialiseSafely() {
    let buffer = PendingIntentBuffer<Sample>()
    let pushQueue = DispatchQueue(label: "push", attributes: .concurrent)
    let drainQueue = DispatchQueue(label: "drain", attributes: .concurrent)
    let group = DispatchGroup()
    for i in 0..<200 {
      group.enter()
      pushQueue.async {
        buffer.push(Sample(payload: "p-\(i)"))
        group.leave()
      }
      group.enter()
      drainQueue.async {
        _ = buffer.drain()
        group.leave()
      }
    }
    XCTAssertEqual(group.wait(timeout: .now() + .seconds(2)), .success)
  }
}
