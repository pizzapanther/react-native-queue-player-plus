import XCTest
@testable import QueuePlayer

/// The sleep timer's pause is a transport intent: the paths that restart
/// playback on their own (a drain refill, a buffer coming back) read
/// `wantsToPlay`, so the timer clears it the way a user pause does.
final class SleepTimerPauseIntentTests: XCTestCase {

  private var player: TrackPlayer!

  override func setUp() async throws {
    try await super.setUp()
    player = TrackPlayer()
    _ = try await player.configure(config: PlayerFixtures.config()).await()
    _ = try await player.setQueue(
      tracks: PlayerFixtures.localTracks(count: 2, for: self), startAtIndex: 0
    ).await()
  }

  override func tearDown() async throws {
    if let player {
      _ = try await player.destroy().await()
    }
    player = nil
    try await super.tearDown()
  }

  func testTheTimersPauseClearsTheIntentToPlay() async throws {
    let player = try XCTUnwrap(self.player)
    await player.onPlayerQueue {
      player.wantsToPlay = true
      player.isRecoveringFromStall = true
      player.applySleepTimerResult(
        SleepTimerCore.Tick(fadeFraction: 1.0, pauseNow: true, stateChanged: true))
    }

    let state = await player.onPlayerQueueValue {
      (wants: player.wantsToPlay, recovering: player.isRecoveringFromStall)
    }
    XCTAssertFalse(state.wants, "a drain or a buffer refill must not restart what the timer stopped")
    XCTAssertFalse(state.recovering)
  }
}
