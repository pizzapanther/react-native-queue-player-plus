import AVFoundation
import XCTest
@testable import QueuePlayer

/// An item that reaches its end mid-queue with no fade in flight advances the
/// engine anyway. A fade is how this engine usually crosses a boundary, not
/// the only way it can — the arm can fail to load, time out, or be cancelled
/// by a route change or a queue mutation, and the queue must not stop on a
/// track that has finished.
final class CrossfadeAdvanceWithoutFadeTests: XCTestCase {

  func testAnItemEndingMidQueueAdvancesToTheNextItem() {
    let engine = makeEngine(count: 3)
    engine.play()

    endLeadingItem(on: engine)

    XCTAssertEqual(
      engine.currentMediaItem?.queueItemId, "id-1",
      "the engine moves to the next item in the run")
    XCTAssertEqual(engine.currentMediaItemIndex, 1)
  }

  /// The queue slot may still hold the instance the arm attached to the
  /// standby leg, and attaching an item still associated with another player
  /// raises an exception Swift cannot catch.
  func testTheAdvanceAttachesAFreshItemRatherThanTheStoredOne() {
    let engine = makeEngine(count: 3)
    engine.play()
    let stored = engine.allMediaItems

    endLeadingItem(on: engine)

    XCTAssertFalse(
      engine.currentMediaItem === stored[1],
      "the incoming item is rebuilt, never the stored instance")
    XCTAssertEqual(
      engine.currentMediaItem?.queueItemId, stored[1].queueItemId,
      "the rebuild carries the queue identity across")
  }

  /// The end-of-item notification hops onto the owner's queue, so a pause
  /// taken in between must survive the advance.
  func testAnItemEndingWhilePausedDoesNotResumePlayback() {
    let engine = makeEngine(count: 3)
    engine.play()
    engine.pause()

    endLeadingItem(on: engine)

    XCTAssertEqual(
      engine.currentMediaItem?.queueItemId, "id-1",
      "the advance still happens")
    XCTAssertEqual(
      engine.timeControlStatus, .paused,
      "a paused engine stays paused across the advance")
  }

  func testTheLastItemEndingEndsTheQueueRatherThanAdvancing() {
    let engine = makeEngine(count: 2)
    let delegate = RecordingDelegate()
    engine.delegate = delegate
    engine.play()
    engine.seek(toIndex: 1, position: 0)

    endLeadingItem(on: engine)

    XCTAssertTrue(delegate.playbackEnded, "the queue reports that it ran out")
    XCTAssertEqual(engine.currentMediaItemIndex, 1, "and stays on the last item")
  }

  // MARK: - Helpers

  private func makeEngine(count: Int) -> CrossfadeEngine {
    let engine = CrossfadeEngine(crossfadeDurationSeconds: 5.0, confinedTo: .main, allowsExternalPlayback: false)
    let items = (0 ..< count).map { index -> AVPlayerItem in
      let item = AVPlayerItem(url: URL(string: "http://127.0.0.1:1/\(index).m4a")!)
      item.queueItemId = "id-\(index)"
      return item
    }
    engine.setItems(items, startIndex: 0, startPositionSeconds: 0)
    engines.append(engine)
    return engine
  }

  /// Post the end-of-item notification the way AVFoundation does, then let the
  /// engine's hop onto its owner queue run before asserting.
  private func endLeadingItem(on engine: CrossfadeEngine) {
    guard let item = engine.currentMediaItem else {
      return XCTFail("no item is attached to end")
    }
    NotificationCenter.default.post(
      name: .AVPlayerItemDidPlayToEndTime, object: item)
    let drained = expectation(description: "owner queue drained")
    DispatchQueue.main.async { drained.fulfill() }
    wait(for: [drained], timeout: 2.0)
  }

  private var engines: [CrossfadeEngine] = []

  override func tearDown() {
    engines.forEach { $0.release() }
    engines.removeAll()
    super.tearDown()
  }
}

private final class RecordingDelegate: PlaybackEngineDelegate {
  var playbackEnded = false

  func engineStateMaybeChanged(_ engine: PlaybackEngine, reason: StateChangeReason?) {}
  func engineDidTransitionTrack(_ engine: PlaybackEngine) {}
  func engineActiveItemFormatChanged(_ engine: PlaybackEngine) {}
  func engineDidFailWithError(
    _ engine: PlaybackEngine, error: Error, item: AVPlayerItem?) {}
  func engineDidPlayItemToEnd(_ engine: PlaybackEngine) {}
  func enginePlaybackEnded(_ engine: PlaybackEngine) { playbackEnded = true }
  func engineCrossfadeDidBegin(_ engine: PlaybackEngine) {}
  func engineCrossfadeDidCancel(_ engine: PlaybackEngine) {}
}
