import XCTest
@testable import QueuePlayer
@_spi(QueuePlayerTests) import QueuePlayer

/// `LookaheadCacheEviction.computeEvictions` unit tests.
///
/// Pure-logic LRU policy. No FS access, no URLSession.
final class LookaheadCacheEvictionTests: XCTestCase {

  func testNoEvictionWhenUnderBudget() {
    let entries = [
      entry("a", size: 100, tick: 1),
      entry("b", size: 200, tick: 2),
      entry("c", size: 300, tick: 3),
    ]
    let evicted = LookaheadCacheEviction.computeEvictions(
      entries: entries, maxBytes: 1000
    )
    XCTAssertTrue(evicted.isEmpty)
  }

  func testNoEvictionAtExactlyBudget() {
    let entries = [
      entry("a", size: 100, tick: 1),
      entry("b", size: 200, tick: 2),
    ]
    let evicted = LookaheadCacheEviction.computeEvictions(
      entries: entries, maxBytes: 300
    )
    XCTAssertTrue(evicted.isEmpty, "exactly-at-budget must not evict")
  }

  func testEvictsOldestFirstUntilUnderBudget() {
    let entries = [
      entry("oldest", size: 100, tick: 1),
      entry("middle", size: 100, tick: 2),
      entry("newest", size: 100, tick: 3),
    ]
    let evicted = LookaheadCacheEviction.computeEvictions(
      entries: entries, maxBytes: 150
    )
    XCTAssertEqual(evicted.map { $0.trackId }, ["oldest", "middle"],
      "oldest two must evict to drop total bytes from 300 → 100 ≤ 150")
  }

  func testEvictsExactlyOneWhenOneSuffices() {
    let entries = [
      entry("oldest", size: 200, tick: 1),
      entry("newer", size: 100, tick: 2),
      entry("newest", size: 100, tick: 3),
    ]
    let evicted = LookaheadCacheEviction.computeEvictions(
      entries: entries, maxBytes: 200
    )
    XCTAssertEqual(evicted.map { $0.trackId }, ["oldest"],
      "evicting just the oldest brings total from 400 → 200 (at budget)")
  }

  func testEvictsByTickNotByOrderInArray() {
    // Index entries are stored in insertion order, but eviction
    // must sort by lastAccessTick — a recently-touched older
    // insertion stays.
    let entries = [
      entry("inserted-first-touched-newest", size: 100, tick: 5),
      entry("inserted-second-touched-oldest", size: 100, tick: 1),
      entry("inserted-third-touched-middle", size: 100, tick: 3),
    ]
    let evicted = LookaheadCacheEviction.computeEvictions(
      entries: entries, maxBytes: 150
    )
    XCTAssertEqual(
      evicted.map { $0.trackId },
      ["inserted-second-touched-oldest", "inserted-third-touched-middle"]
    )
  }

  func testEvictsAllWhenBudgetIsZero() {
    let entries = [
      entry("a", size: 100, tick: 1),
      entry("b", size: 200, tick: 2),
    ]
    let evicted = LookaheadCacheEviction.computeEvictions(
      entries: entries, maxBytes: 0
    )
    XCTAssertEqual(evicted.count, 2,
      "budget=0 must evict everything")
  }

  func testTickCollisionFallsBackToTrackIdOrder() {
    // Two entries with the same tick — eviction order must be
    // deterministic for test reproducibility. Falls back to
    // ascending trackId comparison.
    let entries = [
      entry("zzz", size: 100, tick: 5),
      entry("aaa", size: 100, tick: 5),
      entry("mmm", size: 100, tick: 5),
    ]
    let evicted = LookaheadCacheEviction.computeEvictions(
      entries: entries, maxBytes: 100
    )
    XCTAssertEqual(evicted.map { $0.trackId }, ["aaa", "mmm"],
      "deterministic tiebreak: ascending trackId → aaa evicts first")
  }

  func testEmptyEntriesReturnsEmpty() {
    let evicted = LookaheadCacheEviction.computeEvictions(
      entries: [], maxBytes: 100
    )
    XCTAssertTrue(evicted.isEmpty)
  }

  // MARK: - protectedTrackIds

  func testProtectedTrackIsNeverEvicted() {
    // The just-appended track is the LRU-newest, but if its size
    // alone exceeds budget, plain LRU would evict it (since it's
    // also the only entry). protectedTrackIds skips it from
    // candidacy entirely, so an oversized download stays cached
    // (caller may overshoot the budget — better than discarding
    // the just-paid-for download).
    let entries = [
      entry("oversized", size: 500, tick: 1),
    ]
    let evicted = LookaheadCacheEviction.computeEvictions(
      entries: entries, maxBytes: 100,
      protectedUrls: ["oversized"]
    )
    XCTAssertTrue(evicted.isEmpty,
      "single entry larger than budget must stay when protected")
  }

  func testProtectedTrackProtectedEvenIfOldest() {
    // Mixed: protected entry IS the LRU-oldest, but should still be
    // skipped. Other (newer) entries get evicted instead.
    let entries = [
      entry("protected", size: 100, tick: 1),
      entry("newer-1", size: 100, tick: 2),
      entry("newer-2", size: 100, tick: 3),
    ]
    let evicted = LookaheadCacheEviction.computeEvictions(
      entries: entries, maxBytes: 150,
      protectedUrls: ["protected"]
    )
    XCTAssertEqual(
      evicted.map { $0.trackId },
      ["newer-1", "newer-2"],
      "protected entry must stay even though it's the LRU-oldest"
    )
  }

  // MARK: - FIFO policy

  func testFifoEvictsByInsertionOrderNotAccess() {
    // Access ticks (LRU) and insertion order disagree. Under `.fifo` the
    // oldest-INSERTED evicts first, regardless of playback recency.
    let entries = [
      entry("inserted-first-touched-newest", size: 100, tick: 9, insertion: 1),
      entry("inserted-second-touched-oldest", size: 100, tick: 1, insertion: 2),
      entry("inserted-third", size: 100, tick: 5, insertion: 3),
    ]
    let evicted = LookaheadCacheEviction.computeEvictions(
      entries: entries, maxBytes: 150, useFifoOrder: true
    )
    XCTAssertEqual(
      evicted.map { $0.trackId },
      ["inserted-first-touched-newest", "inserted-second-touched-oldest"],
      "fifo evicts oldest-inserted first even though it was touched most recently"
    )
  }

  func testTouchReordersLruButNotFifo() {
    // The entry touched most recently but inserted first: LRU keeps it,
    // FIFO drops it.
    let entries = [
      entry("first-in", size: 100, tick: 9, insertion: 1),
      entry("second-in", size: 100, tick: 2, insertion: 2),
    ]
    let lru = LookaheadCacheEviction.computeEvictions(
      entries: entries, maxBytes: 100, useFifoOrder: false
    )
    XCTAssertEqual(lru.map { $0.trackId }, ["second-in"],
      "lru evicts the least-recently-touched")
    let fifo = LookaheadCacheEviction.computeEvictions(
      entries: entries, maxBytes: 100, useFifoOrder: true
    )
    XCTAssertEqual(fifo.map { $0.trackId }, ["first-in"],
      "fifo evicts the oldest-inserted regardless of touch")
  }

  func testFifoProtectedTrackIsNeverEvicted() {
    let entries = [
      entry("protected-oldest-insertion", size: 100, tick: 3, insertion: 1),
      entry("newer", size: 100, tick: 1, insertion: 2),
    ]
    let evicted = LookaheadCacheEviction.computeEvictions(
      entries: entries, maxBytes: 100, useFifoOrder: true,
      protectedUrls: ["protected-oldest-insertion"]
    )
    XCTAssertEqual(evicted.map { $0.trackId }, ["newer"],
      "protected entry stays even as the fifo-oldest")
  }

  // MARK: - Helpers

  private func entry(
    _ id: String, size: UInt64, tick: UInt64, insertion: UInt64? = nil
  ) -> LookaheadCache.Entry {
    return LookaheadCache.Entry(
      trackId: id, fileName: "\(id).bin",
      sizeBytes: size, lastAccessTick: tick,
      insertionOrderTick: insertion ?? tick
    )
  }
}
