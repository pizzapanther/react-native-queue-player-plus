import AVFoundation
import XCTest
@testable import QueuePlayer

/// The tap provider's engine-facing mix work runs on the dispatch target it
/// was given: inline when the caller is already on that target, on the target
/// itself otherwise. `resetMixDispatchTarget` puts it back on main, and
/// `detachFromEngine` clears the provider off the engine it was attached to.
final class AudioTapProviderDispatchTargetTests: XCTestCase {

  func testRefreshRunsInlineWhenTheCallerIsOnTheMixQueue() {
    let provider = AudioTapProvider()
    let engine = MixDispatchEngineStub()
    provider.wireToPlaybackEngine(engine)
    let queue = makeQueue(label: "inline")
    provider.setMixDispatchTarget(
      queue,
      isCurrent: { DispatchQueue.getSpecific(key: MixDispatchEngineStub.queueKey) == "inline" })

    queue.sync { provider.refreshActiveItemMixes() }

    XCTAssertEqual(
      engine.observedTargets, ["inline"],
      "an in-queue caller reaches the engine before the call returns, without "
        + "a second hop onto the queue it is already on")
  }

  func testRefreshLandsOnTheInjectedQueueWhenTheCallerIsNotOnIt() {
    let provider = AudioTapProvider()
    let engine = MixDispatchEngineStub()
    provider.wireToPlaybackEngine(engine)
    let landed = expectation(description: "refresh reaches the engine")
    engine.onRefresh = { landed.fulfill() }
    provider.setMixDispatchTarget(makeQueue(label: "deferred"), isCurrent: { false })

    provider.refreshActiveItemMixes()

    wait(for: [landed], timeout: 1.0)
    XCTAssertEqual(engine.observedTargets, ["deferred"])
  }

  func testResetMixDispatchTargetReturnsTheWorkToMain() {
    let provider = AudioTapProvider()
    let engine = MixDispatchEngineStub()
    provider.wireToPlaybackEngine(engine)
    provider.setMixDispatchTarget(makeQueue(label: "player"), isCurrent: { false })

    provider.resetMixDispatchTarget()
    provider.refreshActiveItemMixes()

    XCTAssertEqual(engine.observedTargets, [nil], "the player queue is no longer the target")
    XCTAssertEqual(
      engine.observedMainThreadFlags, [true],
      "a main-thread caller runs inline once the target is back on main")
  }

  func testDetachFromEngineClearsTheEngineMixProvider() {
    let provider = AudioTapProvider()
    let engine = MixDispatchEngineStub()
    provider.wireToPlaybackEngine(engine)
    XCTAssertNotNil(engine.lastSetProvider)

    provider.detachFromEngine()

    XCTAssertNil(engine.lastSetProvider)
    provider.refreshActiveItemMixes()
    XCTAssertTrue(
      engine.observedTargets.isEmpty,
      "a detached provider has no engine left to refresh")
  }

  /// A queue tagged with its own label so the stub can report which target an
  /// engine call arrived on.
  private func makeQueue(label: String) -> DispatchQueue {
    let queue = DispatchQueue(label: "test.mixDispatch.\(label)")
    queue.setSpecific(key: MixDispatchEngineStub.queueKey, value: label)
    return queue
  }
}

/// Records the dispatch target of every `refreshAllItemMixes` call and the last
/// provider set on it. All other PlaybackEngine methods are no-op stubs.
private final class MixDispatchEngineStub: PlaybackEngine {

  /// Tags a queue built by the test; `nil` in `observedTargets` means the call
  /// arrived on a thread with no tagged queue.
  static let queueKey = DispatchSpecificKey<String>()

  private let lock = NSLock()
  private var observed: [String?] = []
  private var observedOnMain: [Bool] = []
  var lastSetProvider: AudioMixProvider?
  var onRefresh: (() -> Void)?

  var observedTargets: [String?] {
    lock.lock()
    defer { lock.unlock() }
    return observed
  }

  /// Whether each recorded call arrived on the main thread — an untagged
  /// target is only evidence of "main" together with this.
  var observedMainThreadFlags: [Bool] {
    lock.lock()
    defer { lock.unlock() }
    return observedOnMain
  }

  func setAudioMixProvider(_ provider: AudioMixProvider?) {
    lastSetProvider = provider
  }

  func refreshAllItemMixes() {
    let target = DispatchQueue.getSpecific(key: Self.queueKey)
    let onMain = Thread.isMainThread
    lock.lock()
    observed.append(target)
    observedOnMain.append(onMain)
    lock.unlock()
    onRefresh?()
  }

  func release() {}
  func setItems(_ items: [AVPlayerItem], startIndex: Int, startPositionSeconds: TimeInterval) {}
  @discardableResult
  func insertItems(_ items: [AVPlayerItem], after: AVPlayerItem?) -> Bool { true }
  func remove(_ item: AVPlayerItem) {}
  func removeAllItems() {}
  func canInsert(_ item: AVPlayerItem, after: AVPlayerItem?) -> Bool { true }
  func play() {}
  func pause() {}
  func stop() {}
  func seek(toIndex index: Int, position: TimeInterval) {}
  func skipToNext() {}
  func skipToPrevious() {}
  func setVolume(_ volume: Float) {}
  func setPlaybackSpeed(_ speed: Float) {}
  func seedPlaybackSpeed(_ speed: Float) {}
  func setRepeatMode(_ mode: RepeatMode) {}
  func setPitchCorrectionMode(_ mode: PitchCorrectionMode) {}

  var currentMediaItemIndex: Int { -1 }
  var currentPositionSeconds: TimeInterval { 0 }
  var currentDurationSeconds: TimeInterval { 0 }
  var bufferedPositionSeconds: TimeInterval { 0 }
  var isPlaying: Bool { false }
  var timeControlStatus: AVPlayer.TimeControlStatus { .paused }
  var currentMediaItem: AVPlayerItem? { nil }
  var allMediaItems: [AVPlayerItem] { [] }

  /// No fade to schedule — this stub exercises the rest of the surface.
  var isFadePendingOrActive: Bool { false }

  /// Nothing attached — this stub exercises the rest of the surface.
  var attachedQueueItemIds: [String] { [] }

  var delegate: PlaybackEngineDelegate?
}
