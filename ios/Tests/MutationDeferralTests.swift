import AVFoundation
import XCTest
@testable import QueuePlayer

/// A queue mutation that touches neither leg waits for a crossfade to settle
/// rather than cutting it short; one that touches a leg cannot wait.
final class MutationDeferralTests: XCTestCase {

  private var player: TrackPlayer!

  override func setUp() async throws {
    try await super.setUp()
    player = TrackPlayer()
  }

  override func tearDown() async throws {
    player = nil
    try await super.tearDown()
  }

  func testAMutationClearOfBothLegsIsHeldBack() async throws {
    try await seed(fading: true, attaching: [0, 1])

    let deferred = try await onQueue { $0.deferEngineWork(touching: [7]) }

    XCTAssertTrue(deferred)
    let held = try await read { $0.engineReconcileDeferred }
    XCTAssertTrue(held)
  }

  /// The prerolled standby leg counts: removing it out from under the arm
  /// chain leaves the fade running into a track the queue no longer has.
  func testAMutationTouchingTheStandbyLegIsNotHeldBack() async throws {
    _ = try await seed(fading: true, attaching: [0, 1])

    let deferred = try await onQueue { $0.deferEngineWork(touching: [1]) }

    XCTAssertFalse(deferred)
    let held = try await read { $0.engineReconcileDeferred }
    XCTAssertFalse(held)
  }

  func testAMutationTouchingTheLeadingLegIsNotHeldBack() async throws {
    _ = try await seed(fading: true, attaching: [0, 1])

    let deferred = try await onQueue { $0.deferEngineWork(touching: [0]) }

    XCTAssertFalse(deferred)
  }

  /// With no fade to protect there is nothing to wait for.
  func testNothingIsHeldBackWhenNoFadeIsPending() async throws {
    _ = try await seed(fading: false, attaching: [0])

    let deferred = try await onQueue { $0.deferEngineWork(touching: [7]) }

    XCTAssertFalse(deferred)
  }

  /// One flag, however many mutations: the reconcile re-derives what the
  /// engine should hold from the model, so the order they arrived in and how
  /// many there were do not matter.
  func testSeveralHeldBackMutationsFlushAsOne() async throws {
    let engine = try await seed(fading: true, attaching: [0, 1])
    _ = try await onQueue { $0.deferEngineWork(touching: [6]) }
    _ = try await onQueue { $0.deferEngineWork(touching: [7]) }
    _ = try await onQueue { $0.deferEngineWork(touching: [8]) }

    XCTAssertEqual(
      engine.setItemsCount, 0, "nothing reaches the engine while the fade runs")

    engine.fadePending = false
    await onQueue { $0.flushDeferredEngineWork() }

    let stillHeld = try await read { $0.engineReconcileDeferred }
    XCTAssertFalse(stillHeld)
    XCTAssertEqual(
      engine.setItemsCount, 1, "the engine is reconciled once, not once per mutation")
  }

  func testFlushingWithNothingHeldBackLeavesTheEngineAlone() async throws {
    let engine = try await seed(fading: false, attaching: [0])

    await onQueue { $0.flushDeferredEngineWork() }

    XCTAssertEqual(engine.setItemsCount, 0)
  }

  /// The new engine is installed from the queue model, which already carries
  /// every mutation, so nothing is owed to it.
  func testAnEngineSwapDropsWhatWasHeldBack() async throws {
    _ = try await seed(fading: true, attaching: [0, 1])
    _ = try await onQueue { $0.deferEngineWork(touching: [7]) }

    await onQueue {
      $0.applyEngineSwapForMode(PlaybackMode(kind: .gapless, crossfadeDurationMs: nil))
    }

    let held = try await read { $0.engineReconcileDeferred }
    XCTAssertFalse(held)
  }

  // MARK: - The model stays authoritative

  /// Holding the engine back does not hold the queue back: the model is
  /// updated and the consumer is told at once either way.
  func testADeferredRemoveStillChangesTheQueueAndReportsIt() async throws {
    let engine = try await seed(fading: true, attaching: [0, 1])
    var removeReports = 0
    let unsubscribe = try player.onQueueChange { _, _, reason in
      if reason == .remove { removeReports += 1 }
    }
    defer { unsubscribe() }

    _ = try await player.removeFromQueue(indices: [7]).await()

    let state = try await read { ($0.tracks.count, $0.queueItemIds.count) }
    XCTAssertEqual(state.0, 11, "the queue model drops the track at once")
    XCTAssertEqual(state.1, 11, "ids stay parallel to it")
    XCTAssertEqual(removeReports, 1, "the consumer is told once")
    XCTAssertTrue(engine.removed.isEmpty, "the engine is left alone until the fade settles")
    let held = try await read { $0.engineReconcileDeferred }
    XCTAssertTrue(held)
  }

  /// The same for a move, which reorders the model without touching a leg.
  func testADeferredMoveStillReordersTheQueueAndReportsIt() async throws {
    _ = try await seed(fading: true, attaching: [0, 1])
    var moveReports = 0
    let unsubscribe = try player.onQueueChange { _, _, reason in
      if reason == .move { moveReports += 1 }
    }
    defer { unsubscribe() }
    let idsBefore = try await read { $0.queueItemIds }

    _ = try await player.moveInQueue(fromIndex: 8, toIndex: 6).await()

    let idsAfter = try await read { $0.queueItemIds }
    XCTAssertEqual(idsAfter[6], idsBefore[8], "the model is reordered at once")
    XCTAssertEqual(moveReports, 1, "a deferred move is still reported")
  }

  // MARK: - Helpers

  @discardableResult
  private func seed(fading: Bool, attaching: [Int]) async throws -> DeferralEngineStub {
    let player = try XCTUnwrap(self.player)
    let engine = DeferralEngineStub()
    await player.onPlayerQueue {
      let ids = player.queueState.replaceAll(
        (0 ..< 12).map {
          PlayerFixtures.track(id: "t\($0)", url: "http://127.0.0.1:1/\($0).m4a")
        })
      engine.fadePending = fading
      engine.attached = attaching.map { ids[$0] }
      player.currentTrackIndex = 0
      player.engine = engine
    }
    return engine
  }

  private func onQueue<T>(_ body: @escaping (TrackPlayer) -> T) async throws -> T {
    let player = try XCTUnwrap(self.player)
    return await player.onPlayerQueueValue { body(player) }
  }

  private func onQueue(_ body: @escaping (TrackPlayer) -> Void) async {
    guard let player else { return }
    await player.onPlayerQueue { body(player) }
  }

  private func read<T>(_ body: @escaping (TrackPlayer) -> T) async throws -> T {
    try await onQueue(body)
  }
}

/// Reports a fade and a chosen set of attached identities, and records what
/// the queue asks of it.
private final class DeferralEngineStub: PlaybackEngine {
  var fadePending = false
  var attached: [String] = []
  private(set) var removed: [AVPlayerItem] = []
  private(set) var setItemsCount = 0

  var isFadePendingOrActive: Bool { fadePending }
  var attachedQueueItemIds: [String] { attached }

  func setItems(_ items: [AVPlayerItem], startIndex: Int, startPositionSeconds: TimeInterval) {
    setItemsCount += 1
  }
  func remove(_ item: AVPlayerItem) { removed.append(item) }
  @discardableResult
  func insertItems(_ items: [AVPlayerItem], after: AVPlayerItem?) -> Bool { true }
  func setAudioMixProvider(_ provider: AudioMixProvider?) {}
  func refreshAllItemMixes() {}
  func release() {}
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

  var currentMediaItemIndex: Int { 0 }
  var currentPositionSeconds: TimeInterval { 0 }
  var currentDurationSeconds: TimeInterval { 0 }
  var bufferedPositionSeconds: TimeInterval { 0 }
  var isPlaying: Bool { false }
  var timeControlStatus: AVPlayer.TimeControlStatus { .paused }
  var currentMediaItem: AVPlayerItem? { nil }
  var allMediaItems: [AVPlayerItem] { [] }
  var delegate: PlaybackEngineDelegate?
}
