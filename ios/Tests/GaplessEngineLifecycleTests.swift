import AVFoundation
import XCTest
@testable import QueuePlayer

/// Lifecycle smoke coverage for `GaplessEngine` — empty-queue
/// sentinels + idempotent / delegate-clearing release contract.
final class GaplessEngineLifecycleTests: XCTestCase {

  func testEmptyQueueReturnsDocumentedSentinelState() {
    let engine = GaplessEngine(allowsExternalPlayback: false)
    XCTAssertEqual(engine.currentMediaItemIndex, -1)
    XCTAssertFalse(engine.isPlaying)
    XCTAssertNil(engine.currentMediaItem)
    XCTAssertEqual(engine.allMediaItems.count, 0)
    XCTAssertEqual(engine.currentPositionSeconds, 0)
    XCTAssertEqual(engine.currentDurationSeconds, 0)
    XCTAssertEqual(engine.bufferedPositionSeconds, 0)
  }

  func testExternalPlaybackFollowsTheInitArgument() {
    XCTAssertFalse(GaplessEngine(allowsExternalPlayback: false).player.allowsExternalPlayback)
    XCTAssertTrue(GaplessEngine(allowsExternalPlayback: true).player.allowsExternalPlayback)
  }

  func testReleaseIsIdempotentAndClearsDelegate() {
    let engine = GaplessEngine(allowsExternalPlayback: false)
    let delegate = NoopDelegate()
    engine.delegate = delegate
    XCTAssertNotNil(engine.delegate)

    engine.release()
    XCTAssertNil(engine.delegate)

    engine.release()
    XCTAssertNil(engine.delegate)
  }

  // MARK: - Repeat mode

  /// Repeat-one holds the current item at its end
  /// (`actionAtItemEnd = .none`) so `TrackPlayer`'s end-of-track handler
  /// can deterministically rewind + replay it; off / queue keep the
  /// default `.advance`. Without the hold, the AVQueuePlayer advances
  /// into the next enqueued item before the async rewind runs, so the
  /// track skips forward instead of repeating.
  func testRepeatTrackHoldsItemAtEndWhileOthersAdvance() {
    let engine = GaplessEngine(allowsExternalPlayback: false)
    XCTAssertEqual(engine.player.actionAtItemEnd, .advance)

    engine.setRepeatMode(.track)
    XCTAssertEqual(engine.player.actionAtItemEnd, .none)

    engine.setRepeatMode(.queue)
    XCTAssertEqual(engine.player.actionAtItemEnd, .advance)

    engine.setRepeatMode(.off)
    XCTAssertEqual(engine.player.actionAtItemEnd, .advance)

    // Re-entering repeat-one re-applies the hold.
    engine.setRepeatMode(.track)
    XCTAssertEqual(engine.player.actionAtItemEnd, .none)
  }

  // MARK: - Queue-mutation surface

  /// `setItems` installs the slice from `startIndex` onward; items
  /// before `startIndex` never enter the AVQueuePlayer (matches
  /// `AVQueuePlayer`'s forward-only contract).
  func testSetItemsInstallsSliceFromStartIndex() {
    let engine = GaplessEngine(allowsExternalPlayback: false)
    let items = makeItems(count: 5)
    engine.setItems(items, startIndex: 2, startPositionSeconds: 0)
    XCTAssertEqual(engine.allMediaItems.count, 3)
    XCTAssertEqual(engine.currentMediaItem, items[2])
  }

  /// Drain-on-bad-input: empty items, negative startIndex, and
  /// startIndex >= items.count all leave the queue empty. Matches
  /// the existing `TrackPlayer.fullRebuildPlayerQueue` shape
  /// (removeAllItems unconditional, then guard).
  func testSetItemsDrainsQueueOnEmptyOrOutOfRangeStartIndex() {
    let engine = GaplessEngine(allowsExternalPlayback: false)
    // Seed the queue first so we can verify the drain.
    engine.setItems(makeItems(count: 3), startIndex: 0, startPositionSeconds: 0)
    XCTAssertEqual(engine.allMediaItems.count, 3)

    // Empty items drains.
    engine.setItems([], startIndex: 0, startPositionSeconds: 0)
    XCTAssertEqual(engine.allMediaItems.count, 0)

    // Negative startIndex drains.
    engine.setItems(makeItems(count: 3), startIndex: -1, startPositionSeconds: 0)
    XCTAssertEqual(engine.allMediaItems.count, 0)

    // startIndex past end drains.
    engine.setItems(makeItems(count: 3), startIndex: 3, startPositionSeconds: 0)
    XCTAssertEqual(engine.allMediaItems.count, 0)
  }

  /// `insertItems` with `nil` after appends to the END of the queue
  /// (matches `AVQueuePlayer.insert(_:after:)`'s contract). Returns
  /// `true` when every item was accepted.
  func testInsertItemsWithNilAfterAppendsToEnd() {
    let engine = GaplessEngine(allowsExternalPlayback: false)
    let initial = makeItems(count: 2)
    engine.setItems(initial, startIndex: 0, startPositionSeconds: 0)
    let appended = makeItems(count: 2)
    let ok = engine.insertItems(appended, after: nil)
    XCTAssertTrue(ok)
    XCTAssertEqual(engine.allMediaItems.count, 4)
    XCTAssertEqual(engine.allMediaItems[2], appended[0])
    XCTAssertEqual(engine.allMediaItems[3], appended[1])
  }

  /// `insertItems` with an explicit `after` cursor surgically slots
  /// the new items behind it.
  func testInsertItemsAfterExplicitCursorSlotsBehindIt() {
    let engine = GaplessEngine(allowsExternalPlayback: false)
    let initial = makeItems(count: 3)
    engine.setItems(initial, startIndex: 0, startPositionSeconds: 0)
    let inserted = makeItems(count: 1)
    let ok = engine.insertItems(inserted, after: initial[0])
    XCTAssertTrue(ok)
    XCTAssertEqual(engine.allMediaItems.count, 4)
    XCTAssertEqual(engine.allMediaItems[1], inserted[0])
  }

  /// `remove(_:)` drops the named item; AVQueuePlayer auto-advances
  /// when the current item is removed, but the removed item is no
  /// longer in `allMediaItems`.
  func testRemoveDropsTheNamedItem() {
    let engine = GaplessEngine(allowsExternalPlayback: false)
    let items = makeItems(count: 4)
    engine.setItems(items, startIndex: 0, startPositionSeconds: 0)
    engine.remove(items[2])
    XCTAssertEqual(engine.allMediaItems.count, 3)
    XCTAssertFalse(engine.allMediaItems.contains(items[2]))
  }

  /// `removeAllItems()` drains the queue; subsequent state queries
  /// return the empty-queue sentinels.
  func testRemoveAllItemsDrainsTheQueue() {
    let engine = GaplessEngine(allowsExternalPlayback: false)
    engine.setItems(makeItems(count: 3), startIndex: 0, startPositionSeconds: 0)
    XCTAssertEqual(engine.allMediaItems.count, 3)
    engine.removeAllItems()
    XCTAssertEqual(engine.allMediaItems.count, 0)
    XCTAssertEqual(engine.currentMediaItemIndex, -1)
    XCTAssertNil(engine.currentMediaItem)
  }

  /// `canInsert(_:after:)` mirrors `AVQueuePlayer.canInsert`. Fresh
  /// items are insertable; items already in the queue are not
  /// (AVQueuePlayer rejects re-insert of an in-flight item).
  func testCanInsertReportsAVQueuePlayerAcceptance() {
    let engine = GaplessEngine(allowsExternalPlayback: false)
    let initial = makeItems(count: 2)
    engine.setItems(initial, startIndex: 0, startPositionSeconds: 0)
    let fresh = makeItem()
    XCTAssertTrue(engine.canInsert(fresh, after: nil))
    XCTAssertFalse(engine.canInsert(initial[0], after: nil))
  }

  // MARK: - Test helpers

  private func makeItem() -> AVPlayerItem {
    // file:// URL keeps the AVURLAsset cheap; we never actually
    // play these — the queue-mutation surface only inspects the
    // items() snapshot + canInsert/insert/remove primitives.
    let url = URL(fileURLWithPath: "/dev/null/\(UUID().uuidString).mp3")
    return AVPlayerItem(url: url)
  }

  private func makeItems(count: Int) -> [AVPlayerItem] {
    (0 ..< count).map { _ in makeItem() }
  }
}

private final class NoopDelegate: PlaybackEngineDelegate {
  func engineStateMaybeChanged(_ engine: PlaybackEngine, reason: StateChangeReason?) {}
  func engineDidTransitionTrack(_ engine: PlaybackEngine) {}
  func engineActiveItemFormatChanged(_ engine: PlaybackEngine) {}
  func engineDidFailWithError(
    _ engine: PlaybackEngine, error: Error, item: AVPlayerItem?) {}
  func engineDidPlayItemToEnd(_ engine: PlaybackEngine) {}
  func enginePlaybackEnded(_ engine: PlaybackEngine) {}
  func engineCrossfadeDidBegin(_ engine: PlaybackEngine) {}
  func engineCrossfadeDidCancel(_ engine: PlaybackEngine) {}
}
