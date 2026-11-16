import AVFoundation
import XCTest
@testable import QueuePlayer

/// Playback runs with `automaticallyWaitsToMinimizeStalling = false`, so
/// AVPlayer does not resume itself after a buffer underrun. A stall arms a
/// recovery that re-issues play when the buffer refills, and transport intent
/// decides whether it arms at all.
///
/// The resume itself needs a real item whose buffer actually refills, so that
/// half is covered by the streaming demo suites; what is covered here is the
/// arming and clearing, which is where the intent bugs would be.
final class StallRecoveryTests: XCTestCase {

  func testAStallArmsRecoveryWhilePlaybackIsIntended() {
    let player = TrackPlayer()
    let active = seedActiveItem(player)
    player.wantsToPlay = true

    player.handlePlaybackStalled(
      Notification(name: AVPlayerItem.playbackStalledNotification, object: active))
    drainPlayerQueue(player)

    XCTAssertTrue(player.isRecoveringFromStall)
  }

  /// The observer sees every item in the process; a stall on an item that
  /// is not the one being heard is not this player's stall.
  func testAStallOnAnotherItemDoesNotArmRecovery() {
    let player = TrackPlayer()
    _ = seedActiveItem(player)
    player.wantsToPlay = true
    let other = AVPlayerItem(url: URL(fileURLWithPath: "/dev/null/other.mp3"))

    player.handlePlaybackStalled(
      Notification(name: AVPlayerItem.playbackStalledNotification, object: other))
    drainPlayerQueue(player)

    XCTAssertFalse(player.isRecoveringFromStall)
  }

  /// A notification with no item names nothing this player is playing — also
  /// while the player itself has no active item, where "no item" and "no
  /// active item" must not be read as a match.
  func testAStallWithNoItemDoesNotArmRecovery() {
    let player = TrackPlayer()
    player.wantsToPlay = true

    player.handlePlaybackStalled(Notification(name: AVPlayerItem.playbackStalledNotification))
    drainPlayerQueue(player)

    XCTAssertFalse(player.isRecoveringFromStall)
  }

  func testAStallWhilePausedDoesNotArmRecovery() {
    let player = TrackPlayer()
    let active = seedActiveItem(player)
    player.wantsToPlay = false

    player.handlePlaybackStalled(
      Notification(name: AVPlayerItem.playbackStalledNotification, object: active))
    drainPlayerQueue(player)

    XCTAssertFalse(
      player.isRecoveringFromStall,
      "a stall arriving while paused must not arm a resume that fires later")
  }

  func testAnExplicitPauseDisarmsAPendingRecovery() async throws {
    let player = TrackPlayer()
    player.wantsToPlay = true
    player.isRecoveringFromStall = true

    _ = try await player.pause().await()

    XCTAssertFalse(
      player.isRecoveringFromStall,
      "an explicit pause outranks a pending recovery, or the next refill "
        + "restarts audio the user just stopped")
  }

  /// Installs an engine holding one item and returns that item.
  private func seedActiveItem(_ player: TrackPlayer) -> AVPlayerItem {
    let engine = ActiveItemEngineStub()
    let item = AVPlayerItem(url: URL(fileURLWithPath: "/dev/null/active.mp3"))
    engine.item = item
    player.engine = engine
    return item
  }

  /// The handler hops to the player queue, so the assertion has to follow it.
  private func drainPlayerQueue(_ player: TrackPlayer) {
    let drained = expectation(description: "player queue drained")
    player.playerQueue.async { drained.fulfill() }
    wait(for: [drained], timeout: 2.0)
  }
}
