import Network
import XCTest
@testable import QueuePlayer
@_spi(QueuePlayerTests) import QueuePlayer

/// The monitor reports the network coming back after an outage, and only that.
/// A handoff between interfaces is not a recovery, and treating it as one would
/// retry against a connection that never dropped.
///
/// `NWPathMonitor` delivers the current path once on `start`, and the monitor
/// seeds its last status as unsatisfied, so on a device with a satisfied path
/// every armed `start()` reports exactly one restore. That first delivery is
/// what makes the arming observable. A device with no satisfied path fails
/// these tests at the precondition rather than passing them empty.
final class PlaybackNetworkMonitorTests: XCTestCase {

  private let firstDeliveryTimeout: TimeInterval = 5

  /// Arms `monitor` and waits for the one restore its first path delivery reports.
  private func armAndAwaitRestore(_ monitor: PlaybackNetworkMonitor, restores: Counter) {
    let first = expectation(description: "the current path is delivered on start")
    // The callback stays installed for the monitor's life, so a genuine
    // down → up during a later window reaches the counter assertions rather
    // than tripping this expectation's over-fulfil check.
    first.assertForOverFulfill = false
    monitor.onNetworkRestored = {
      restores.bump()
      first.fulfill()
    }
    monitor.start()
    let outcome = XCTWaiter.wait(for: [first], timeout: firstDeliveryTimeout)
    guard outcome == .completed else {
      XCTFail(
        "no restore within \(Int(firstDeliveryTimeout))s of start — this device has no satisfied "
          + "network path, so the monitor cannot be observed here")
      return
    }
    XCTAssertEqual(restores.value, 1, "exactly one restore per armed start")
  }

  /// A `stop()` before any `start()` is not terminal: the arming that follows
  /// still delivers the current path.
  func testStopBeforeStartChangesNothing() {
    let monitor = PlaybackNetworkMonitor(queue: .main)
    let restores = Counter()
    monitor.stop()
    armAndAwaitRestore(monitor, restores: restores)
    monitor.stop()
  }

}

/// Main-queue counter the monitor's `queue: .main` callbacks bump.
private final class Counter {
  private(set) var value = 0
  func bump() { value += 1 }
}

/// Recovery runs only when all four conditions hold together.
final class NetworkRecoveryPolicyTests: XCTestCase {

  func testRetriesWhenPlaybackIsIntendedAndTheItemFailedTransiently() {
    XCTAssertTrue(
      NetworkRecoveryPolicy.shouldRetry(
        wantsToPlay: true, isInterrupted: false, isInError: true, isTransient: true))
  }

  func testDoesNotRetryWhenPlaybackWasNeverIntended() {
    XCTAssertFalse(
      NetworkRecoveryPolicy.shouldRetry(
        wantsToPlay: false, isInterrupted: false, isInError: true, isTransient: true),
      "a network change must not start audio nobody asked for")
  }

  func testDoesNotRetryDuringAnInterruption() {
    XCTAssertFalse(
      NetworkRecoveryPolicy.shouldRetry(
        wantsToPlay: true, isInterrupted: true, isInError: true, isTransient: true),
      "the network returning mid-call must not start audio")
  }

  func testDoesNotRetryWhenNothingHasFailed() {
    XCTAssertFalse(
      NetworkRecoveryPolicy.shouldRetry(
        wantsToPlay: true, isInterrupted: false, isInError: false, isTransient: true),
      "an interface handoff must not rebuild a healthy item")
  }

  func testDoesNotRetryAFailureANetworkCannotCure() {
    XCTAssertFalse(
      NetworkRecoveryPolicy.shouldRetry(
        wantsToPlay: true, isInterrupted: false, isInError: true, isTransient: false),
      "a codec, format or auth failure re-surfaces on every rebuild")
  }
}
