import XCTest
@testable import QueuePlayer

/// `onPlayerQueueSync` serialises on `playerQueue`, and recognises when it is already
/// there. The recognition is what makes re-entrancy safe: a `sync` onto the
/// queue from a block already running on it deadlocks immediately.
final class TrackPlayerThreadingTests: XCTestCase {

  func testSyncAccessorRunsInlineWhenAlreadyOnThePlayerQueue() {
    let player = TrackPlayer()
    let returned = expectation(description: "onPlayerQueueSync returns from the player queue")
    var value = 0

    player.playerQueue.async {
      value = player.onPlayerQueueSync { 42 }
      returned.fulfill()
    }

    wait(for: [returned], timeout: 2.0)
    XCTAssertEqual(value, 42)
  }

  func testSyncAccessorReturnsFromOffTheQueue() {
    let player = TrackPlayer()
    let returned = expectation(description: "onPlayerQueueSync returns from an off-queue caller")
    var value = 0

    DispatchQueue.global().async {
      value = player.onPlayerQueueSync { 7 }
      returned.fulfill()
    }

    wait(for: [returned], timeout: 2.0)
    XCTAssertEqual(value, 7)
  }

  func testNestedSyncAccessorsDoNotDeadlock() {
    let player = TrackPlayer()
    let returned = expectation(description: "a nested onPlayerQueueSync returns")
    var value = 0

    DispatchQueue.global().async {
      value = player.onPlayerQueueSync { player.onPlayerQueueSync { 9 } }
      returned.fulfill()
    }

    wait(for: [returned], timeout: 2.0)
    XCTAssertEqual(value, 9)
  }

  func testOnQueueRecognitionIsPerInstance() {
    let first = TrackPlayer()
    let second = TrackPlayer()
    let checked = expectation(description: "recognition evaluated on first's queue")
    var firstSeesItsOwnQueue = false
    var secondSeesAnotherPlayersQueue = true

    first.playerQueue.async {
      firstSeesItsOwnQueue = first.isOnPlayerQueue
      secondSeesAnotherPlayersQueue = second.isOnPlayerQueue
      checked.fulfill()
    }

    wait(for: [checked], timeout: 2.0)
    XCTAssertTrue(firstSeesItsOwnQueue)
    XCTAssertFalse(
      secondSeesAnotherPlayersQueue,
      "one player must not report being serialised by another player's queue")
  }

  // MARK: - Call-order preservation

  /// Work enqueued from one thread runs in the order it was enqueued. This is
  /// what makes a JS `play()` then `pause()` reach the player in that order:
  /// a serial queue gives mutual exclusion, and enqueueing at call time is
  /// what gives ordering on top of it.
  func testEnqueuedWorkRunsInTheOrderItWasCalled() {
    let player = TrackPlayer()
    let lock = NSLock()
    var order: [Int] = []
    let ran = expectation(description: "every enqueued body ran")
    ran.expectedFulfillmentCount = 50

    for i in 0 ..< 50 {
      _ = player.enqueue {
        lock.lock()
        order.append(i)
        lock.unlock()
        ran.fulfill()
      }
    }

    wait(for: [ran], timeout: 5.0)
    XCTAssertEqual(order, Array(0 ..< 50))
  }

  func testEnqueueResolvesItsPromiseAfterTheBodyRuns() async throws {
    let player = TrackPlayer()
    var didRun = false
    let promise = player.enqueue { didRun = true }

    _ = try await promise.await()

    XCTAssertTrue(didRun, "the promise must not resolve before the body has run")
  }

  func testEnqueueThrowingRejectsWithTheThrownError() async {
    let player = TrackPlayer()
    struct Boom: Error {}
    do {
      _ = try await player.enqueueThrowing { throw Boom() }.await()
      XCTFail("expected the promise to reject")
    } catch {
      // The bridge surfaces the message; reaching here is the contract.
    }
  }

  func testEnqueueValueCarriesItsResultBack() async throws {
    let player = TrackPlayer()
    player.currentTrackIndex = 7

    let value = try await player.enqueueValue { player.currentTrackIndex }.await()

    XCTAssertEqual(value, 7)
  }

}
