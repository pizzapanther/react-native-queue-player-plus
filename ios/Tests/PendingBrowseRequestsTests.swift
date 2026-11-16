import XCTest
@testable import QueuePlayer

/// iOS mirror of the Android `PendingBrowseRequestsTest`. Same shape,
/// same contract assertions.
final class PendingBrowseRequestsTests: XCTestCase {

  func testDrainAllOnFreshQueueReturnsEmpty() {
    let q = PendingBrowseRequests()
    XCTAssertTrue(q.drainAll().isEmpty)
  }

  func testEnqueueRecordsTheParentId() {
    let q = PendingBrowseRequests()
    q.enqueue("root")
    XCTAssertEqual(q.snapshotForTest(), ["root"])
  }

  func testDuplicateEnqueuesCollapse() {
    let q = PendingBrowseRequests()
    q.enqueue("root")
    q.enqueue("root")
    q.enqueue("section-a")
    q.enqueue("section-a")
    XCTAssertEqual(q.drainAll(), ["root", "section-a"])
  }

  func testDrainAllEmptiesTheQueue() {
    let q = PendingBrowseRequests()
    q.enqueue("root")
    q.enqueue("section-a")
    let drained = q.drainAll()
    XCTAssertEqual(drained, ["root", "section-a"])
    XCTAssertTrue(q.drainAll().isEmpty)
    XCTAssertTrue(q.snapshotForTest().isEmpty)
  }

  func testClearEmptiesWithoutReturning() {
    let q = PendingBrowseRequests()
    q.enqueue("root")
    q.enqueue("section-a")
    q.clear()
    XCTAssertTrue(q.drainAll().isEmpty)
  }

  func testReEnqueueAfterDrainAccumulatesFreshEntries() {
    let q = PendingBrowseRequests()
    q.enqueue("a")
    q.drainAll()
    q.enqueue("b")
    q.enqueue("c")
    XCTAssertEqual(q.drainAll(), ["b", "c"])
  }

  func testConcurrentEnqueuesPreserveEveryUniqueParent() {
    let q = PendingBrowseRequests()
    let parents = (0..<200).map { "parent-\($0)" }
    let group = DispatchGroup()
    for p in parents {
      group.enter()
      DispatchQueue.global().async {
        q.enqueue(p)
        group.leave()
      }
    }
    group.wait()
    XCTAssertEqual(q.drainAll(), Set(parents))
  }
}
