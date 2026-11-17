import XCTest
@testable import QueuePlayer

/// `getState()` reports the receiver while a cast session owns playback. The
/// local engine is parked and paused for the session's duration, so reading it
/// would describe nothing the user can hear.
final class TrackPlayerCastStateTests: XCTestCase {

  private let router = PlaybackStateRouter.shared

  override func setUp() {
    super.setUp()
    router._resetForTesting()
  }

  override func tearDown() {
    router._resetForTesting()
    super.tearDown()
  }

  /// Register a session that has already reported `state` to the receiver.
  private func activateReporting(_ state: RemotePlaybackStateKind) throws {
    let session = ControllableRemoteSession()
    session.fireState(state)
    try router.setActive(session)
  }

  func testReportsReceiverPlayingDuringCastSession() throws {
    try activateReporting(.playing)
    XCTAssertEqual(try TrackPlayer().getState(), .playing)
  }

  func testProgressReadsReceiverWithoutLocalQueueOrSubscribers() throws {
    let session = ControllableRemoteSession()
    session.firePosition(positionMs: 12_500, durationMs: 90_000, bufferedMs: 20_000)
    try router.setActive(session)
    let progress = try TrackPlayer().getProgress()
    XCTAssertEqual(progress.position, 12.5)
    XCTAssertEqual(progress.duration, 90)
    XCTAssertEqual(progress.buffered, 7.5)
  }

  func testReportsReceiverPausedDuringCastSession() throws {
    try activateReporting(.paused)
    XCTAssertEqual(try TrackPlayer().getState(), .paused)
  }

  func testMapsBufferingReceiverToBuffering() throws {
    try activateReporting(.buffering)
    XCTAssertEqual(try TrackPlayer().getState(), .buffering)
  }

  func testMapsEndedReceiverToEnded() throws {
    try activateReporting(.ended)
    XCTAssertEqual(try TrackPlayer().getState(), .ended)
  }

  func testReportsIdleReceiverOnceItHasReported() throws {
    // A receiver that has spoken stays authoritative: a later idle means it
    // genuinely has nothing loaded, not that it is still activating.
    let session = ControllableRemoteSession()
    session.fireState(.playing)
    session.fireState(.idle)
    try router.setActive(session)

    XCTAssertEqual(try TrackPlayer().getState(), PlayerState.none)
  }

  func testReadsLocalEngineUntilReceiverReports() throws {
    // Session activating: the receiver holds a state it has not reported, so
    // the local engine answers. Distinguishable from the receiver's `.playing`
    // precisely because no engine is configured here.
    let session = ControllableRemoteSession()
    session.lastState = .playing
    try router.setActive(session)

    XCTAssertFalse(session.hasReportedState)
    XCTAssertEqual(try TrackPlayer().getState(), PlayerState.none)
  }

  func testReadsLocalEngineWithNoCastSession() throws {
    // An unregistered session must not be consulted, however it reports.
    let session = ControllableRemoteSession()
    session.fireState(.playing)

    XCTAssertEqual(try TrackPlayer().getState(), PlayerState.none)
  }
}
