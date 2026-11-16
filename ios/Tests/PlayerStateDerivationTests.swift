import AVFoundation
import XCTest
@testable import QueuePlayer

/// Unit tests for `PlayerStateDerivation` — the pure `PlayerState` mapping
/// shared by both engines.
final class PlayerStateDerivationTests: XCTestCase {

  private func derive(
    _ status: AVPlayer.TimeControlStatus,
    index: Int = 0,
    hasItem: Bool = true,
    ended: Bool = false
  ) -> PlayerState {
    PlayerStateDerivation.translate(
      status: status, currentIndex: index, hasCurrentItem: hasItem, reachedQueueEnd: ended)
  }

  // MARK: baseline (queue has not drained)

  func testPlayingIsPlaying() {
    XCTAssertEqual(derive(.playing), .playing)
  }

  func testPausedWithTrackIsPaused() {
    XCTAssertEqual(derive(.paused, index: 3), .paused)
  }

  func testPausedEmptyQueueIsNone() {
    XCTAssertEqual(derive(.paused, index: -1), .none)
  }

  func testWaitingWithItemIsBuffering() {
    // Waiting to play with a current item = initial load / rebuffer.
    XCTAssertEqual(derive(.waitingToPlayAtSpecifiedRate, hasItem: true), .buffering)
  }

  func testWaitingWithoutItemEmptyQueueIsNone() {
    XCTAssertEqual(derive(.waitingToPlayAtSpecifiedRate, index: -1, hasItem: false), .none)
  }

  func testWaitingWithoutItemNonEmptyQueueIsEndedFallback() {
    // A drained AVQueuePlayer parks at .waitingToPlay with no item before
    // `reachedQueueEnd` is set — the end-of-queue fallback covers the timing race.
    XCTAssertEqual(derive(.waitingToPlayAtSpecifiedRate, index: 5, hasItem: false), .ended)
  }

  // MARK: reachedQueueEnd

  func testReachedQueueEndWithTrackNotPlayingIsEnded() {
    // Any non-playing status at end-of-queue is terminal — the same result for
    // both engines (crossfade keeps its finished item, so hasItem is true).
    XCTAssertEqual(derive(.paused, index: 5, ended: true), .ended)
    XCTAssertEqual(
      derive(.waitingToPlayAtSpecifiedRate, index: 5, hasItem: true, ended: true), .ended)
  }

  func testReachedQueueEndYieldsToPlaying() {
    // Playing takes precedence over `reachedQueueEnd`, so a resume reads
    // correctly even before it is cleared.
    XCTAssertEqual(derive(.playing, index: 5, ended: true), .playing)
  }

  func testReachedQueueEndIgnoredWhenQueueEmpty() {
    // A cleared queue (index < 0) ignores `reachedQueueEnd` and derives normally.
    XCTAssertEqual(derive(.paused, index: -1, ended: true), .none)
  }
}
