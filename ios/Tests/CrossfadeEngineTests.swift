import AVFoundation
import XCTest
@testable import QueuePlayer

/// Coverage for `CrossfadeEngine`'s construction, delegate assignment and
/// index contract.
///
/// The index contract is the load-bearing part: the engine indexes within
/// whatever slice it was handed, so its indices are NOT queue positions.
/// Callers translate by `queueItemId` identity. These tests cover the engine
/// side of that contract — slice-relative indexing, ordered identity-bearing
/// `allMediaItems`, and identity surviving the seek cross-attach. The
/// translation itself lives in `TrackPlayer.engineIndex(forQueuePosition:)`,
/// which needs a configured engine and is covered by the
/// `non-zero-start-index-seeks-within-the-started-track` demo step.
///
/// The negative-duration constructor guard at
/// `CrossfadeEngine.init(crossfadeDurationSeconds:)` is not testable — a
/// `precondition` trap is non-recoverable from XCTest. The load-bearing
/// guard for runtime callers is the clamp inside
/// `PlaybackModeStateMachine`; the constructor check is a defensive belt.
final class CrossfadeEngineTests: XCTestCase {

  func testConstructorAcceptsNonNegativeCrossfadeDuration() {
    let engine = CrossfadeEngine(crossfadeDurationSeconds: 5.0, confinedTo: .main, allowsExternalPlayback: false)
    XCTAssertEqual(engine.currentMediaItemIndex, -1, "a fresh engine holds no queue")
    XCTAssertTrue(engine.allMediaItems.isEmpty)
  }

  func testExternalPlaybackFollowsTheInitArgumentOnBothLegs() {
    for allowed in [false, true] {
      let engine = CrossfadeEngine(
        crossfadeDurationSeconds: 5.0, confinedTo: .main, allowsExternalPlayback: allowed)
      XCTAssertEqual(engine.leadingPlayer.allowsExternalPlayback, allowed)
      XCTAssertEqual(engine.standbyPlayer.allowsExternalPlayback, allowed)
      engine.release()
    }
  }

  func testDelegateIsWirable() {
    let engine = CrossfadeEngine(crossfadeDurationSeconds: 5.0, confinedTo: .main, allowsExternalPlayback: false)
    let delegate = NoopDelegate()
    engine.delegate = delegate
    XCTAssertTrue(engine.delegate === delegate)
  }

  // MARK: - Index contract

  /// Items carrying the lib-generated identity the translation relies on.
  private func makeItems(ids: [String]) -> [AVPlayerItem] {
    ids.map { id in
      let item = AVPlayerItem(url: URL(fileURLWithPath: "/dev/null/\(id).mp3"))
      item.queueItemId = id
      return item
    }
  }

  func testEngineIndexesWithinItsOwnSliceNotTheQueuePosition() {
    let engine = CrossfadeEngine(crossfadeDurationSeconds: 5.0, confinedTo: .main, allowsExternalPlayback: false)
    // The slice a caller hands over when playback starts at queue position 5.
    engine.setItems(makeItems(ids: ["q5", "q6", "q7"]), startIndex: 0, startPositionSeconds: 0)

    // Zero, not 5 — the engine has no knowledge of the queue position the
    // slice came from. Reading this as a queue position seats the wrong track.
    XCTAssertEqual(engine.currentMediaItemIndex, 0)
    XCTAssertEqual(engine.allMediaItems.count, 3)
  }

  /// `allMediaItems` exposes the installed slice in order, with identities
  /// intact. Callers translate a queue position into an engine index through
  /// this, so an engine that reordered or dropped identities would break the
  /// translation silently.
  func testAllMediaItemsPreservesSliceOrderAndIdentity() {
    let engine = CrossfadeEngine(crossfadeDurationSeconds: 5.0, confinedTo: .main, allowsExternalPlayback: false)
    engine.setItems(makeItems(ids: ["q5", "q6", "q7"]), startIndex: 0, startPositionSeconds: 0)

    XCTAssertEqual(engine.allMediaItems.map(\.queueItemId), ["q5", "q6", "q7"])
  }

  /// The cross-attach inside `seek` rebuilds the target item; identity has to
  /// survive it or the translation stops resolving after the first seek.
  func testIdentitySurvivesTheSeekCrossAttach() {
    let engine = CrossfadeEngine(crossfadeDurationSeconds: 5.0, confinedTo: .main, allowsExternalPlayback: false)
    engine.setItems(makeItems(ids: ["q5", "q6", "q7"]), startIndex: 0, startPositionSeconds: 0)

    engine.seek(toIndex: 1, position: 0)

    XCTAssertEqual(engine.allMediaItems.map(\.queueItemId), ["q5", "q6", "q7"])
    XCTAssertEqual(engine.currentMediaItem?.queueItemId, "q6")
  }

  /// The seek cross-attach rebuilds the target item; the read-through routing
  /// stamp has to survive it too, or `getCurrentTrackSource()` reports a
  /// cache-served track as `.streaming` (the gapless engine never rebuilds, so
  /// it never loses the stamp).
  func testRoutingStampSurvivesTheSeekCrossAttach() {
    let engine = CrossfadeEngine(crossfadeDurationSeconds: 5.0, confinedTo: .main, allowsExternalPlayback: false)
    let items = makeItems(ids: ["q5", "q6", "q7"])
    items.forEach { $0.isRoutedThroughReadThroughServer = true }
    engine.setItems(items, startIndex: 0, startPositionSeconds: 0)

    engine.seek(toIndex: 1, position: 0)

    XCTAssertEqual(
      engine.allMediaItems.map(\.isRoutedThroughReadThroughServer),
      [true, true, true],
      "the rebuilt item must keep its read-through routing stamp")
  }

  func testSeekToIndexMovesWithinTheEngineSlice() {
    let engine = CrossfadeEngine(crossfadeDurationSeconds: 5.0, confinedTo: .main, allowsExternalPlayback: false)
    engine.setItems(makeItems(ids: ["q5", "q6", "q7"]), startIndex: 0, startPositionSeconds: 0)

    engine.seek(toIndex: 2, position: 0)
    XCTAssertEqual(engine.currentMediaItemIndex, 2)
    XCTAssertEqual(engine.currentMediaItem?.queueItemId, "q7")
  }

  func testSeekOutsideTheSliceIsRejected() {
    let engine = CrossfadeEngine(crossfadeDurationSeconds: 5.0, confinedTo: .main, allowsExternalPlayback: false)
    engine.setItems(makeItems(ids: ["q5", "q6", "q7"]), startIndex: 0, startPositionSeconds: 0)

    engine.seek(toIndex: 7, position: 0)
    XCTAssertEqual(engine.currentMediaItemIndex, 0, "an out-of-slice index must not move the engine")
  }

  /// A missing local file's `AVURLAsset` load can stall in `.unknown` on
  /// `AVPlayer`, so `.failed` never fires and the failure never reaches the
  /// delegate. Seating one must surface `engineDidFailWithError` from the file's
  /// non-existence directly, rather than wait on the load.
  func testAMissingLocalFileSurfacesAFailure() {
    let engine = CrossfadeEngine(crossfadeDurationSeconds: 5.0, confinedTo: .main, allowsExternalPlayback: false)
    let delegate = FailCapturingDelegate()
    engine.delegate = delegate
    let missing = URL(fileURLWithPath: "/tmp/rnqp-missing-\(UUID().uuidString).mp3")
    let item = AVPlayerItem(url: missing)
    item.queueItemId = "missing"
    item.sourceURL = missing.absoluteString

    let fired = expectation(description: "a missing local file surfaces a failure")
    delegate.onFail = { error in
      let ns = error as NSError
      if ns.domain == NSURLErrorDomain, ns.code == NSURLErrorFileDoesNotExist { fired.fulfill() }
    }
    engine.setItems([item], startIndex: 0, startPositionSeconds: 0)

    wait(for: [fired], timeout: 3.0)
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

private final class FailCapturingDelegate: PlaybackEngineDelegate {
  var onFail: ((Error) -> Void)?
  func engineStateMaybeChanged(_ engine: PlaybackEngine, reason: StateChangeReason?) {}
  func engineDidTransitionTrack(_ engine: PlaybackEngine) {}
  func engineActiveItemFormatChanged(_ engine: PlaybackEngine) {}
  func engineDidFailWithError(
    _ engine: PlaybackEngine, error: Error, item: AVPlayerItem?) { onFail?(error) }
  func engineDidPlayItemToEnd(_ engine: PlaybackEngine) {}
  func enginePlaybackEnded(_ engine: PlaybackEngine) {}
  func engineCrossfadeDidBegin(_ engine: PlaybackEngine) {}
  func engineCrossfadeDidCancel(_ engine: PlaybackEngine) {}
}
