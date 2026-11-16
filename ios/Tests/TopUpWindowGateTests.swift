import AVFoundation
import XCTest
@testable import QueuePlayer
@_spi(QueuePlayerTests) import QueuePlayer

/// Extending the installed run waits for a crossfade rather than cutting it
/// short, and reads the run's start from what is actually being heard.
final class TopUpWindowGateTests: XCTestCase {

  private var player: TrackPlayer!

  override func setUp() async throws {
    try await super.setUp()
    player = TrackPlayer()
  }

  override func tearDown() async throws {
    player = nil
    try await super.tearDown()
  }

  /// The same run, the same owed top-up: only the pending fade differs.
  func testAPendingFadeIsWhatHoldsTheTopUpBack() async throws {
    let engine = WindowEngineStub(count: 3)
    engine.fadePending = true
    try await seed(engine: engine, trackCount: 20)

    await onQueue { $0.topUpWindow() }
    XCTAssertTrue(
      engine.inserted.isEmpty,
      "a pending fade must not be cut short to extend the run")

    engine.fadePending = false
    await onQueue { $0.topUpWindow() }
    XCTAssertFalse(
      engine.inserted.isEmpty,
      "the same run extends once there is no fade to protect")
  }

  /// The run starts at the item being heard. An engine that retains played
  /// items reports those first, and measuring from them would call the run
  /// satisfied while the playhead sat near its end.
  func testTheRunIsMeasuredFromTheItemBeingHeardNotTheFirstInstalled() async throws {
    // Ten installed positions, but the playhead is at the last of them — so
    // the run ahead is exhausted and a top-up is owed.
    let engine = WindowEngineStub(count: 10)
    engine.leadingIndex = 9
    try await seed(engine: engine, trackCount: 30)

    await onQueue { $0.topUpWindow() }

    XCTAssertFalse(
      engine.inserted.isEmpty,
      "measured from the playhead the run is exhausted, so it must extend")
  }

  /// A skip is applied inside a mutation, and the transition it raises is not
  /// allowed to top up while one is in flight — so the skip extends the run
  /// itself when it lands on the last installed position.
  func testASkipOntoTheLastInstalledPositionExtendsTheRun() async throws {
    let engine = WindowEngineStub(count: 4)
    try await seed(engine: engine, trackCount: 20)

    await onQueue { $0.applyNewCurrentIndex(newIndex: 3, reason: .userSkipToIndex) }

    XCTAssertFalse(
      engine.inserted.isEmpty,
      "the run ahead of the skip target is empty and must be extended")
  }

  /// The crossfade steady state: the whole queue installed, the playhead part
  /// way into it. This is where the claim that the machinery is inert has to
  /// hold, and a shorter-than-floor queue does not test it.
  func testAWhollyInstalledQueueNeedsNoTopUpFromMidRun() async throws {
    let engine = WindowEngineStub(count: 30)
    engine.leadingIndex = 5
    try await seed(engine: engine, trackCount: 30)

    await onQueue { $0.topUpWindow() }

    XCTAssertTrue(
      engine.inserted.isEmpty,
      "nothing is owed when the queue is already installed to its end")
  }

  /// The engine refusing the append falls back to installing the slice.
  func testAnEngineThatRefusesTheAppendFallsBackToAFullInstall() async throws {
    let engine = WindowEngineStub(count: 3)
    engine.refuseInserts = true
    try await seed(engine: engine, trackCount: 20)

    await onQueue { $0.topUpWindow() }

    XCTAssertTrue(engine.inserted.isEmpty)
    XCTAssertTrue(
      engine.didSetItems, "a refused append must be recovered by a full install")
  }

  func testAFullTailNeedsNoTopUp() async throws {
    let engine = WindowEngineStub(count: 4)
    try await seed(engine: engine, trackCount: 4)

    await onQueue { $0.topUpWindow() }

    XCTAssertTrue(
      engine.inserted.isEmpty,
      "the queue is wholly installed, so there is nothing to append")
  }

  /// A queue that has ended is not extended by a top-up, even while the
  /// engine still holds a short run and the model has tracks past it: the
  /// finished track would otherwise get a run appended behind it, and the
  /// next play() would fade into it.
  func testTopUpDoesNotExtendAnEndedQueue() async throws {
    let engine = WindowEngineStub(count: 3)
    engine.leadingIndex = 2
    try await seed(engine: engine, trackCount: 20)
    await onQueue { $0.reachedQueueEnd = true }

    await onQueue { $0.topUpWindow() }

    XCTAssertTrue(engine.inserted.isEmpty, "nothing is appended behind the finished track")
    XCTAssertFalse(engine.didSetItems, "an ended queue must not be seated again by a top-up")
  }

  /// The same holds for a drained engine that has not ended: seating it is the
  /// caller's job, not the top-up's.
  func testTopUpDoesNotSeatADrainedEngine() async throws {
    let engine = WindowEngineStub(count: 0)
    try await seed(engine: engine, trackCount: 20)

    await onQueue { $0.topUpWindow() }

    XCTAssertFalse(engine.didSetItems)
  }

  /// An add that fits inside the window is inserted surgically, so the items
  /// already buffering ahead of it are kept.
  func testAnAddInsideTheWindowIsInsertedWithoutReinstalling() async throws {
    let engine = WindowEngineStub(count: QueueWindowArithmetic.floor)
    try await seed(engine: engine, trackCount: QueueWindowArithmetic.floor)

    _ = try await player.addToQueue(
      tracks: [
        PlayerFixtures.track(id: "n0", url: "https://example.com/n0.m4a"),
        PlayerFixtures.track(id: "n1", url: "https://example.com/n1.m4a"),
      ],
      insertBefore: 3
    ).await()

    XCTAssertEqual(engine.inserted.count, 2, "both added items are inserted")
    XCTAssertFalse(engine.didSetItems, "nothing is reinstalled")
    let ids = try await onQueueValue { $0.queueItemIds }
    XCTAssertEqual(
      engine.allMediaItems.compactMap(\.queueItemId), Array(ids.prefix(QueueWindowArithmetic.floor + 2)),
      "the run is the queue's positions in order with the two added items in place")
  }

  /// An add that runs past the window is not built item by item: the engine
  /// is reconciled to the slice, and `topUpWindow` materialises the rest as
  /// the playhead advances.
  func testAnAddPastTheWindowBuildsOnlyTheSlice() async throws {
    let engine = WindowEngineStub(count: QueueWindowArithmetic.floor)
    try await seed(engine: engine, trackCount: QueueWindowArithmetic.floor)
    let album = (0 ..< 30).map {
      PlayerFixtures.track(id: "n\($0)", url: "https://example.com/n\($0).m4a")
    }

    _ = try await player.addToQueue(tracks: album, insertBefore: 5).await()

    XCTAssertEqual(
      engine.inserted.count, QueueWindowArithmetic.floor - 5,
      "only the positions inside the window are built")
    XCTAssertFalse(engine.didSetItems, "the run is trimmed and extended, not reinstalled")
    let ids = try await onQueueValue { $0.queueItemIds }
    XCTAssertEqual(
      engine.allMediaItems.compactMap(\.queueItemId), Array(ids.prefix(QueueWindowArithmetic.floor)),
      "the engine holds exactly the window: the five kept positions and the first five added")
  }

  /// The same after the end of the queue on an engine that keeps its items:
  /// the finished item is a materialised anchor, and an item inserted behind
  /// it would be what the next play() fades into.
  func testAnAppendAfterTheEndOfTheQueueLeavesARetainingEngineAlone() async throws {
    let engine = WindowEngineStub(count: 3)
    engine.leadingIndex = 2
    try await seed(engine: engine, trackCount: 3)
    await onQueue { $0.reachedQueueEnd = true }

    _ = try await player.addToQueue(
      tracks: [PlayerFixtures.track(id: "t3", url: "https://example.com/t3.m4a")],
      insertBefore: nil
    ).await()

    XCTAssertTrue(engine.inserted.isEmpty, "nothing is inserted behind the finished item")
    XCTAssertFalse(engine.didSetItems)
    let state = try await onQueueValue { ($0.currentTrackIndex, $0.reachedQueueEnd) }
    XCTAssertEqual(state.0, 2)
    XCTAssertTrue(state.1)
  }

  /// A drain reported while the queue is already over does not re-decide it:
  /// with tracks added behind the finished one, the verdict would otherwise be
  /// `.windowDrained` and seat the added track.
  func testARepeatedEndReportWhileEndedSeatsNothing() async throws {
    let engine = WindowEngineStub(count: 0)
    engine.leadingIndex = 2
    try await seed(engine: engine, trackCount: 3)
    await onQueue {
      $0.reachedQueueEnd = true
      _ = $0.queueState.insert(
        [PlayerFixtures.track(id: "t3", url: "https://example.com/t3.m4a")], at: 3)
    }

    await onQueue { $0.applyEndVerdict() }

    XCTAssertFalse(engine.didSetItems, "the ended queue is not re-seated")
    let state = try await onQueueValue { ($0.currentTrackIndex, $0.reachedQueueEnd) }
    XCTAssertEqual(state.0, 2, "the index stays on the track that finished")
    XCTAssertTrue(state.1)
  }

  /// An append after the end of the queue extends the model, not the engine:
  /// inserting into a drained engine would seat playback on the new track
  /// while `currentTrackIndex` still names the one that finished, so the
  /// next `play()` would start a track the consumer was never told about.
  func testAnAppendAfterTheEndOfTheQueueLeavesTheDrainedEngineAlone() async throws {
    let engine = WindowEngineStub(count: 0)
    engine.leadingIndex = 2
    try await seed(engine: engine, trackCount: 3)
    await onQueue { $0.reachedQueueEnd = true }

    _ = try await player.addToQueue(
      tracks: [PlayerFixtures.track(id: "t3", url: "https://example.com/t3.m4a")],
      insertBefore: nil
    ).await()

    XCTAssertTrue(engine.inserted.isEmpty, "nothing is inserted into a drained engine")
    XCTAssertFalse(engine.didSetItems)
    let state = try await onQueueValue {
      ($0.tracks.count, $0.currentTrackIndex, $0.reachedQueueEnd)
    }
    XCTAssertEqual(state.0, 4, "the queue model carries the append")
    XCTAssertEqual(state.1, 2, "the current index stays on the track that finished")
    XCTAssertTrue(state.2, "the queue is still over until a skip or seek says otherwise")
  }

  /// Reinstalling the run the engine is already rendering from restarts the
  /// track being heard unless its playhead is carried across.
  func testAForcedReinstallKeepsThePlayheadOfTheTrackBeingHeard() async throws {
    let engine = WindowEngineStub(count: 12)
    engine.leadingIndex = 4
    engine.position = 37.5
    try await seed(engine: engine, trackCount: 20)

    await onQueue { p in
      p.performingMutation { p.fullRebuildPlayerQueue(forcingReinstall: true) }
    }

    XCTAssertTrue(engine.didSetItems, "the run is installed whole")
    XCTAssertEqual(
      engine.seatedAtPosition, 37.5,
      "the track being heard resumes where it was, not from its start")
  }

  /// A reinstall that seats on a different track is a track change, and a
  /// track change starts at the beginning.
  func testAReinstallOntoADifferentTrackStartsItFromZero() async throws {
    let engine = WindowEngineStub(count: 12)
    engine.leadingIndex = 4
    engine.position = 37.5
    try await seed(engine: engine, trackCount: 20)
    await onQueue { $0.currentTrackIndex = 9 }

    await onQueue { p in
      p.performingMutation { p.fullRebuildPlayerQueue(forcingReinstall: true) }
    }

    XCTAssertEqual(engine.seatedAtPosition, 0)
  }

  /// The same run, the same owed top-up: only the current track's reported
  /// failure differs.
  func testAReportedFailureOnTheCurrentTrackIsWhatHoldsTheTopUpBack() async throws {
    let engine = WindowEngineStub(count: 3)
    try await seed(engine: engine, trackCount: 20)
    await onQueue { $0.reportedFailureQueueItemIds = [$0.queueItemIds[$0.currentTrackIndex]] }

    await onQueue { $0.topUpWindow() }
    XCTAssertTrue(
      engine.inserted.isEmpty,
      "the track whose failure was reported is not installed again")

    await onQueue { $0.reportedFailureQueueItemIds.removeAll() }
    await onQueue { $0.topUpWindow() }
    XCTAssertFalse(
      engine.inserted.isEmpty,
      "the same run extends once the track is no longer the reported failure")
  }

  /// The same run, the same owed top-up: only the scheduled attempt differs.
  func testAScheduledAttemptForTheCurrentTrackAlsoHoldsTheTopUpBack() async throws {
    let engine = WindowEngineStub(count: 3)
    try await seed(engine: engine, trackCount: 20)
    await onQueue { $0.pendingRetryQueueItemId = $0.queueItemIds[$0.currentTrackIndex] }

    await onQueue { $0.topUpWindow() }
    XCTAssertTrue(
      engine.inserted.isEmpty,
      "the attempt re-seats the track itself; rebuilding first fails it again")

    await onQueue { $0.pendingRetryQueueItemId = nil }
    await onQueue { $0.topUpWindow() }
    XCTAssertFalse(engine.inserted.isEmpty)
  }

  /// A failure against a track the playhead has left does not stop the run
  /// being extended.
  func testATopUpRunsWhenTheReportedFailureIsADifferentTrack() async throws {
    let engine = WindowEngineStub(count: 3)
    try await seed(engine: engine, trackCount: 20)
    await onQueue { $0.reportedFailureQueueItemIds = [$0.queueItemIds[7]] }

    await onQueue { $0.topUpWindow() }

    XCTAssertFalse(engine.inserted.isEmpty)
  }

  // MARK: - Helpers

  private func seed(engine: WindowEngineStub, trackCount: Int) async throws {
    let player = try XCTUnwrap(self.player)
    await player.onPlayerQueue {
      let ids = player.queueState.replaceAll(
        (0 ..< trackCount).map {
          PlayerFixtures.track(id: "t\($0)", url: "https://example.com/\($0).m4a")
        })
      engine.stampIds(Array(ids.prefix(engine.installedCount)))
      player.currentTrackIndex = engine.leadingIndex
      player.engine = engine
    }
  }

  private func onQueue(_ body: @escaping (TrackPlayer) -> Void) async {
    guard let player else { return }
    await player.onPlayerQueue { body(player) }
  }

  private func onQueueValue<T>(_ body: @escaping (TrackPlayer) -> T) async throws -> T {
    let player = try XCTUnwrap(self.player)
    return await player.onPlayerQueueValue { body(player) }
  }
}

/// Reports an installed run of `count` items and records what is appended to
/// it. Every other member is inert — the window logic reads the run, the
/// leading identity and the fade flag, nothing else.
private final class WindowEngineStub: PlaybackEngine {
  let installedCount: Int
  var leadingIndex: Int = 0
  var fadePending = false
  /// `CrossfadeEngine.insertItems` refuses an anchor it does not hold; the
  /// caller's recovery from that is a contract worth exercising.
  var refuseInserts = false
  /// The engine's own playhead, which a reinstall of the run it is already
  /// rendering has to carry across.
  var position: TimeInterval = 0
  private(set) var inserted: [AVPlayerItem] = []
  private(set) var didSetItems = false
  private(set) var seatedAtPosition: TimeInterval?
  private var items: [AVPlayerItem] = []

  init(count: Int) {
    self.installedCount = count
  }

  func stampIds(_ ids: [String]) {
    items = ids.map { id in
      let item = AVPlayerItem(url: URL(string: "https://example.com/\(id).m4a")!)
      item.queueItemId = id
      return item
    }
  }

  var allMediaItems: [AVPlayerItem] { items }
  var leadingQueueItemId: String? { items[safe: leadingIndex]?.queueItemId }
  var isFadePendingOrActive: Bool { fadePending }
  /// The whole installed run stands in for what is attached; this stub's
  /// contract is the window, not the legs.
  var attachedQueueItemIds: [String] { items.compactMap { $0.queueItemId } }

  /// Inserts after the anchor, as the engines do, so the run's shape is
  /// observable and not only the count of what was appended.
  @discardableResult
  func insertItems(_ newItems: [AVPlayerItem], after: AVPlayerItem?) -> Bool {
    guard !refuseInserts else { return false }
    let position: Int
    if let after {
      guard let index = items.firstIndex(where: { $0 === after }) else { return false }
      position = index + 1
    } else {
      position = items.count
    }
    inserted.append(contentsOf: newItems)
    items.insert(contentsOf: newItems, at: position)
    return true
  }

  func setAudioMixProvider(_ provider: AudioMixProvider?) {}
  func refreshAllItemMixes() {}
  func release() {}
  func setItems(_ items: [AVPlayerItem], startIndex: Int, startPositionSeconds: TimeInterval) {
    didSetItems = true
    seatedAtPosition = startPositionSeconds
    self.items = items
  }
  func remove(_ item: AVPlayerItem) { items.removeAll { $0 === item } }
  func removeAllItems() { items.removeAll() }
  func canInsert(_ item: AVPlayerItem, after: AVPlayerItem?) -> Bool { true }
  func play() {}
  func pause() {}
  func stop() {}
  /// A seek moves the playhead: the run is measured from the new leading item.
  func seek(toIndex index: Int, position: TimeInterval) { leadingIndex = index }
  func skipToNext() {}
  func skipToPrevious() {}
  func setVolume(_ volume: Float) {}
  func setPlaybackSpeed(_ speed: Float) {}
  func seedPlaybackSpeed(_ speed: Float) {}
  func setRepeatMode(_ mode: RepeatMode) {}
  func setPitchCorrectionMode(_ mode: PitchCorrectionMode) {}

  var currentMediaItemIndex: Int { leadingIndex }
  var currentPositionSeconds: TimeInterval { position }
  var currentDurationSeconds: TimeInterval { 0 }
  var bufferedPositionSeconds: TimeInterval { 0 }
  var isPlaying: Bool { false }
  var timeControlStatus: AVPlayer.TimeControlStatus { .paused }
  var currentMediaItem: AVPlayerItem? { items[safe: leadingIndex] }
  var delegate: PlaybackEngineDelegate?
}
