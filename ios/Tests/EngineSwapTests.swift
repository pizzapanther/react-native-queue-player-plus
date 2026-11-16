import AVFoundation
import XCTest
@testable import QueuePlayer

/// A playback-mode swap installs the window on the new engine and always
/// releases the one it replaces.
final class EngineSwapTests: XCTestCase {

  private var player: TrackPlayer!

  override func setUp() async throws {
    try await super.setUp()
    player = TrackPlayer()
  }

  override func tearDown() async throws {
    if let player {
      _ = try await player.destroy().await()
    }
    player = nil
    try await super.tearDown()
  }

  /// Nothing at or after the current position can be built, so the new engine
  /// is left empty — and the old one still goes.
  func testTheOldEngineIsReleasedWhenNothingCanBeSeated() async throws {
    let player = try XCTUnwrap(self.player)
    let old = ActiveItemEngineStub()
    await player.onPlayerQueue {
      _ = player.queueState.replaceAll([PlayerFixtures.track(id: "t0", url: "")])
      player.currentTrackIndex = 0
      player.engine = old
    }

    await player.onPlayerQueue {
      player.applyEngineSwapForMode(PlaybackMode(kind: .gapless, crossfadeDurationMs: nil))
    }

    XCTAssertTrue(old.released, "the replaced engine is released whatever was seated")
    let installed = await player.onPlayerQueueValue { player.engine?.allMediaItems.count ?? -1 }
    XCTAssertEqual(installed, 0, "an unbuildable current track seats nothing")
  }

  func testTheOldEngineIsReleasedOnAnOrdinarySwap() async throws {
    let player = try XCTUnwrap(self.player)
    let old = ActiveItemEngineStub()
    await player.onPlayerQueue {
      _ = player.queueState.replaceAll([
        PlayerFixtures.track(id: "t0", url: "https://example.com/0.m4a"),
        PlayerFixtures.track(id: "t1", url: "https://example.com/1.m4a"),
      ])
      player.currentTrackIndex = 0
      player.engine = old
    }

    await player.onPlayerQueue {
      player.applyEngineSwapForMode(PlaybackMode(kind: .gapless, crossfadeDurationMs: nil))
    }

    XCTAssertTrue(old.released)
    let installed = await player.onPlayerQueueValue { player.engine?.allMediaItems.count ?? -1 }
    XCTAssertEqual(installed, 2, "the window is installed on the new engine")
  }

  // MARK: - seatIndex

  private func item(_ id: String) -> AVPlayerItem {
    let item = AVPlayerItem(url: URL(fileURLWithPath: "/dev/null/\(id).mp3"))
    item.queueItemId = id
    return item
  }

  func testASwappedInEngineTakesExternalPlaybackFromTheConfig() async throws {
    let player = try XCTUnwrap(self.player)
    for allowed in [false, true] {
      await player.onPlayerQueue {
        player.config = PlayerFixtures.config(allowsExternalPlayback: allowed)
        player.applyEngineSwapForMode(PlaybackMode(kind: .crossfade, crossfadeDurationMs: 5000))
      }
      let crossfade = await player.onPlayerQueueValue {
        (player.engine as? CrossfadeEngine).map {
          [$0.leadingPlayer.allowsExternalPlayback, $0.standbyPlayer.allowsExternalPlayback]
        }
      }
      XCTAssertEqual(crossfade, [allowed, allowed], "crossfade, allowed=\(allowed)")

      await player.onPlayerQueue {
        player.applyEngineSwapForMode(PlaybackMode(kind: .gapless, crossfadeDurationMs: nil))
      }
      let gapless = await player.onPlayerQueueValue {
        (player.engine as? GaplessEngine)?.player.allowsExternalPlayback
      }
      XCTAssertEqual(gapless, allowed, "gapless, allowed=\(allowed)")
    }
  }

  func testTheSeatIsTheCurrentItemWhenItBuilt() {
    let items = [item("a"), item("b"), item("c")]
    XCTAssertEqual(
      TrackPlayer.seatIndex(in: items, sliceIds: ["a", "b", "c"], currentId: "b"), 1)
  }

  /// A current position whose item did not build seats on the next built
  /// position forward, never on the trail behind it.
  func testTheSeatMovesForwardPastAnUnbuiltCurrentItem() {
    let items = [item("a"), item("c")]
    XCTAssertEqual(
      TrackPlayer.seatIndex(in: items, sliceIds: ["a", "b", "c"], currentId: "b"), 1)
  }

  func testThereIsNoSeatWhenNothingAheadBuilt() {
    let items = [item("a")]
    XCTAssertNil(TrackPlayer.seatIndex(in: items, sliceIds: ["a", "b", "c"], currentId: "b"))
  }
}
