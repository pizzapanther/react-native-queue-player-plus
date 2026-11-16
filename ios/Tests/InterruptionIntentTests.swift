import XCTest
@testable import QueuePlayer

/// `isInterrupted` disables retries, window refills and network recovery
/// while the system holds the session. The system does not always deliver
/// the interruption's end, so the flag has user-side exits too.
final class InterruptionIntentTests: XCTestCase {

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

  /// An explicit play is the user ending the interruption from their side.
  func testAnExplicitPlayEndsTheInterruption() async throws {
    let player = try XCTUnwrap(self.player)
    await player.onPlayerQueue { player.isInterrupted = true }

    _ = try await player.play().await()

    let interrupted = await player.onPlayerQueueValue { player.isInterrupted }
    XCTAssertFalse(interrupted)
  }

  /// At the end of the queue play() does nothing: the engine is not asked to
  /// play and the intent is not recorded, so a track a later skip seats waits
  /// for its own play().
  func testPlayAtTheEndOfTheQueueRestartsNothing() async throws {
    let player = try XCTUnwrap(self.player)
    let engine = ActiveItemEngineStub()
    await player.onPlayerQueue {
      player.engine = engine
      player.reachedQueueEnd = true
      player.wantsToPlay = false
    }

    _ = try await player.play().await()

    XCTAssertEqual(engine.playCalls, 0, "the parked engine is left alone")
    let state = await player.onPlayerQueueValue { (player.wantsToPlay, player.reachedQueueEnd) }
    XCTAssertFalse(state.0, "no intent is recorded for a queue that is over")
    XCTAssertTrue(state.1)
  }

  /// Nothing about the torn-down session survives into the next configure.
  func testDestroyClearsTheInterruptionAndRecoveryFlags() async throws {
    let player = try XCTUnwrap(self.player)
    await player.onPlayerQueue {
      player.isInterrupted = true
      player.isRecoveringFromStall = true
      player.engineReconcileDeferred = true
    }

    _ = try await player.destroy().await()

    let flags = await player.onPlayerQueueValue {
      (player.isInterrupted, player.isRecoveringFromStall, player.engineReconcileDeferred)
    }
    XCTAssertFalse(flags.0)
    XCTAssertFalse(flags.1)
    XCTAssertFalse(flags.2)
  }
}
