import XCTest
import AVFoundation
@testable import QueuePlayer

// `AudioSession` is a pure-AVFoundation wrapper — no Nitrogen types — so
// `@testable import` works cleanly here (unlike the rest of the library,
// which wraps C++-interop types).

/// `AudioSession` translates AVAudioSession notifications (interruption +
/// route-change) into a library-level `InterruptionKind` / `RouteChangeKind`
/// surface. These tests post synthetic notifications through
/// NotificationCenter after `installObservers()` and verify the callback
/// fires (or intentionally does not fire) with the translated value — no
/// live audio session interaction needed.
///
/// Delivery model: `installObservers()` registers with `queue: .main`, so
/// every observer block is *enqueued* onto the main queue when the
/// notification is posted (even if posting from main — `NotificationCenter`
/// still hops through the queue for `queue`-specific observers). The
/// positive-case tests wait on an `XCTestExpectation` fulfilled inside the
/// callback, so ordering is safe. The negative-case tests use an
/// **inverted** expectation with a non-trivial timeout: if the callback
/// ever fires during that window the expectation counts as over-fulfilled
/// and XCTest fails the test. This is tighter than a timer-based drain
/// because it survives main-queue scheduler pressure.
///
/// Observer idempotency: covered by calling `installObservers()` twice and
/// fulfilling a positive expectation fired once by the retained (second)
/// observer. The body asserts `fireCount == 1` after the main queue has
/// drained, so stale pending blocks from the first registration would show
/// up as an over-fulfilment and fail the test.
final class AudioSessionTests: XCTestCase {

  private var session: AudioSession!

  override func setUp() {
    super.setUp()
    session = AudioSession()
    session.installObservers()
  }

  override func tearDown() {
    session.tearDownObservers()
    session = nil
    super.tearDown()
  }

  // MARK: - Interruption translation

  func testInterruptionBeganTranslatesToBegan() {
    let expect = expectation(description: "onInterruption fires with .began")
    session.onInterruption = { kind in
      if kind == .began { expect.fulfill() }
      else { XCTFail("expected .began, got \(kind)") }
    }
    postInterruption(type: .began, options: nil)
    wait(for: [expect], timeout: 1.0)
  }

  func testInterruptionEndedShouldResumeTranslatesToShouldResume() {
    let expect = expectation(description: ".endedShouldResume")
    session.onInterruption = { kind in
      if kind == .endedShouldResume { expect.fulfill() }
      else { XCTFail("expected .endedShouldResume, got \(kind)") }
    }
    postInterruption(type: .ended, options: [.shouldResume])
    wait(for: [expect], timeout: 1.0)
  }

  func testInterruptionEndedWithoutShouldResumeTranslatesToShouldNotResume() {
    let expect = expectation(description: ".endedShouldNotResume")
    session.onInterruption = { kind in
      if kind == .endedShouldNotResume { expect.fulfill() }
      else { XCTFail("expected .endedShouldNotResume, got \(kind)") }
    }
    postInterruption(type: .ended, options: [])
    wait(for: [expect], timeout: 1.0)
  }

  func testInterruptionEndedMissingOptionKeyTreatedAsShouldNotResume() {
    // AVAudioSession sometimes emits an `.ended` notification without
    // the `AVAudioSessionInterruptionOptionKey` userInfo entry. The
    // fallback must be conservative: do NOT auto-resume music.
    let expect = expectation(description: "missing options → .endedShouldNotResume")
    session.onInterruption = { kind in
      if kind == .endedShouldNotResume { expect.fulfill() }
      else { XCTFail("expected .endedShouldNotResume, got \(kind)") }
    }
    let userInfo: [AnyHashable: Any] = [
      AVAudioSessionInterruptionTypeKey:
        AVAudioSession.InterruptionType.ended.rawValue,
    ]
    NotificationCenter.default.post(
      name: AVAudioSession.interruptionNotification,
      object: AVAudioSession.sharedInstance(),
      userInfo: userInfo)
    wait(for: [expect], timeout: 1.0)
  }

  func testInterruptionMalformedNotificationIsIgnored() {
    let inverted = expectation(description: "onInterruption should NOT fire")
    inverted.isInverted = true
    session.onInterruption = { _ in inverted.fulfill() }
    NotificationCenter.default.post(
      name: AVAudioSession.interruptionNotification,
      object: AVAudioSession.sharedInstance(),
      userInfo: nil)
    wait(for: [inverted], timeout: 0.5)
  }

  // MARK: - Route-change translation

  func testRouteChangeOldDeviceUnavailableTranslates() {
    let expect = expectation(description: ".oldDeviceUnavailable")
    session.onRouteChange = { kind in
      if kind == .oldDeviceUnavailable { expect.fulfill() }
      else { XCTFail("expected .oldDeviceUnavailable, got \(kind)") }
    }
    postRouteChange(reason: .oldDeviceUnavailable)
    wait(for: [expect], timeout: 1.0)
  }

  func testRouteChangeNewDeviceAvailableTranslates() {
    let expect = expectation(description: ".newDeviceAvailable")
    session.onRouteChange = { kind in
      if kind == .newDeviceAvailable { expect.fulfill() }
      else { XCTFail("expected .newDeviceAvailable, got \(kind)") }
    }
    postRouteChange(reason: .newDeviceAvailable)
    wait(for: [expect], timeout: 1.0)
  }

  func testRouteChangeCategoryChangeIsSuppressed() {
    // Our own `activate()` triggers a category change; exposing it would
    // leak our own setup as a consumer-visible event. AudioSession filters
    // these out intentionally.
    let inverted = expectation(description: ".categoryChange must not fire callback")
    inverted.isInverted = true
    session.onRouteChange = { _ in inverted.fulfill() }
    postRouteChange(reason: .categoryChange)
    wait(for: [inverted], timeout: 0.5)
  }

  func testRouteChangeWakeFromSleepIsSuppressed() {
    let inverted = expectation(description: ".wakeFromSleep must not fire callback")
    inverted.isInverted = true
    session.onRouteChange = { _ in inverted.fulfill() }
    postRouteChange(reason: .wakeFromSleep)
    wait(for: [inverted], timeout: 0.5)
  }

  // MARK: - Observer idempotency

  func testInstallObserversIsIdempotent() {
    // setUp already called installObservers() once. Calling it again must
    // tear down the previous registration before re-adding, so a posted
    // notification produces exactly one callback invocation — not two.
    // `tearDownObservers` inside `installObservers` runs before the
    // post, so no in-flight block from the first observer can linger.
    session.installObservers()

    let expect = expectation(description: "callback fires exactly once")
    var fireCount = 0
    session.onInterruption = { _ in
      fireCount += 1
      if fireCount == 1 { expect.fulfill() }
    }
    postInterruption(type: .began, options: nil)
    wait(for: [expect], timeout: 1.0)
    // Drain one more main-queue hop to expose any stray second fire from
    // a stale observer that wasn't actually torn down.
    let drain = expectation(description: "drain")
    DispatchQueue.main.async { drain.fulfill() }
    wait(for: [drain], timeout: 1.0)
    XCTAssertEqual(fireCount, 1, "re-installing observers must not double-fire the callback")
  }

  func testTearDownObserversStopsCallbacks() {
    session.tearDownObservers()
    let inverted = expectation(description: "teardown should stop all further callbacks")
    inverted.isInverted = true
    session.onInterruption = { _ in inverted.fulfill() }
    postInterruption(type: .began, options: nil)
    wait(for: [inverted], timeout: 0.5)
  }

  func testTearDownIsSafeToCallTwice() {
    XCTAssertNoThrow(session.tearDownObservers())
    XCTAssertNoThrow(session.tearDownObservers())
    // If tearDown wasn't idempotent an NSInternalInconsistency exception
    // would propagate out of NotificationCenter — the two XCTAssertNoThrow
    // checks capture that.
  }

  // MARK: - Helpers

  private func postInterruption(
    type: AVAudioSession.InterruptionType,
    options: AVAudioSession.InterruptionOptions?
  ) {
    var userInfo: [AnyHashable: Any] = [
      AVAudioSessionInterruptionTypeKey: type.rawValue,
    ]
    if let options = options {
      userInfo[AVAudioSessionInterruptionOptionKey] = options.rawValue
    }
    NotificationCenter.default.post(
      name: AVAudioSession.interruptionNotification,
      object: AVAudioSession.sharedInstance(),
      userInfo: userInfo)
  }

  private func postRouteChange(reason: AVAudioSession.RouteChangeReason) {
    let userInfo: [AnyHashable: Any] = [
      AVAudioSessionRouteChangeReasonKey: reason.rawValue,
    ]
    NotificationCenter.default.post(
      name: AVAudioSession.routeChangeNotification,
      object: AVAudioSession.sharedInstance(),
      userInfo: userInfo)
  }
}
