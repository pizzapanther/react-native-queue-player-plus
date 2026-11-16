import XCTest
@testable import QueuePlayer
@_spi(QueuePlayerTests) import QueuePlayer

/// A rebuild keeps the leading run the engine already holds and replaces only
/// what differs, so the item being rendered survives it.
final class QueueRebuildPrefixTests: XCTestCase {

  // MARK: - The arithmetic

  func testAnIdenticalSliceIsWhollyAPrefix() {
    XCTAssertEqual(
      QueueMutationArithmetic.commonPrefixLength(
        current: ["a", "b", "c"], desired: ["a", "b", "c"]),
      3)
  }

  func testThePrefixStopsAtTheFirstDifference() {
    XCTAssertEqual(
      QueueMutationArithmetic.commonPrefixLength(
        current: ["a", "b", "x"], desired: ["a", "b", "c"]),
      2)
  }

  func testAnAppendKeepsEverythingAlreadyHeld() {
    XCTAssertEqual(
      QueueMutationArithmetic.commonPrefixLength(
        current: ["a", "b"], desired: ["a", "b", "c", "d"]),
      2)
  }

  func testATruncationKeepsWhatSurvives() {
    XCTAssertEqual(
      QueueMutationArithmetic.commonPrefixLength(
        current: ["a", "b", "c", "d"], desired: ["a", "b"]),
      2)
  }

  func testADifferentHeadKeepsNothing() {
    XCTAssertEqual(
      QueueMutationArithmetic.commonPrefixLength(
        current: ["z", "b", "c"], desired: ["a", "b", "c"]),
      0)
  }

  /// An item the library did not stamp has no identity to match on, so the
  /// prefix cannot claim it.
  func testAnUnstampedItemEndsThePrefix() {
    XCTAssertEqual(
      QueueMutationArithmetic.commonPrefixLength(
        current: ["a", nil, "c"], desired: ["a", "b", "c"]),
      1)
  }

  func testEmptySidesHaveNoPrefix() {
    XCTAssertEqual(
      QueueMutationArithmetic.commonPrefixLength(current: [], desired: ["a"]), 0)
    XCTAssertEqual(
      QueueMutationArithmetic.commonPrefixLength(current: ["a"], desired: []), 0)
  }

  // MARK: - The rebuild

  /// The engine already holds exactly this slice, so nothing is reinstalled
  /// and its buffered items survive.
  func testAnUnchangedSliceIsLeftAlone() async throws {
    let player = try await seeded(count: 3)
    let before = try await read(player) { $0.engine?.allMediaItems ?? [] }
    XCTAssertEqual(before.count, 3, "precondition: the engine holds the slice")

    await onQueue(player) { p in p.performingMutation { p.fullRebuildPlayerQueue() } }

    let after = try await read(player) { $0.engine?.allMediaItems ?? [] }
    XCTAssertEqual(after.count, 3)
    for (old, new) in zip(before, after) {
      XCTAssertTrue(old === new, "an unchanged slice must not be reinstalled")
    }
  }

  /// The model gained a track the engine does not hold: the run it does hold
  /// stays, and only the new tail is installed.
  func testAnAppendKeepsTheInstalledRunAndAddsTheTail() async throws {
    let player = try await seeded(count: 3)
    let before = try await read(player) { $0.engine?.allMediaItems ?? [] }
    XCTAssertEqual(before.count, 3)

    // Extend the model behind the engine's back so the rebuild is what
    // reconciles them — the public mutation APIs install the tail themselves.
    let extra = try PlayerFixtures.localTracks(count: 2, for: self)
    await onQueue(player) { p in
        _ = p.queueState.insert(extra, at: p.tracks.count)
        p.performingMutation { p.fullRebuildPlayerQueue() }
    }

    let after = try await read(player) { $0.engine?.allMediaItems ?? [] }
    XCTAssertEqual(after.count, 5, "the appended positions must be installed")
    for (old, new) in zip(before, after.prefix(3)) {
      XCTAssertTrue(old === new, "the run already held must be kept, not rebuilt")
    }
  }

  /// A divergence part-way along replaces from that point, not from the head.
  func testADivergenceRebuildsFromThePointItDiverges() async throws {
    let player = try await seeded(count: 4)
    let before = try await read(player) { $0.engine?.allMediaItems ?? [] }
    XCTAssertEqual(before.count, 4)

    // Replace positions 2 and 3 in the model only.
    let replacement = try PlayerFixtures.localTracks(count: 2, for: self)
    await onQueue(player) { p in
        _ = p.queueState.remove(at: 3)
        _ = p.queueState.remove(at: 2)
        _ = p.queueState.insert(replacement, at: 2)
        p.performingMutation { p.fullRebuildPlayerQueue() }
    }

    let after = try await read(player) { $0.engine?.allMediaItems ?? [] }
    XCTAssertEqual(after.count, 4)
    for (old, new) in zip(before.prefix(2), after.prefix(2)) {
      XCTAssertTrue(old === new, "positions 0 and 1 still match, so they stay")
    }
    XCTAssertFalse(
      before[2] === after[2], "the divergent tail must be replaced")
    let ids = try await read(player) { $0.queueItemIds }
    XCTAssertEqual(
      after.map { $0.queueItemId }, ids,
      "the engine must end up holding exactly the model's positions")
  }

  /// A forced reinstall replaces the items even when identity says the slice
  /// already matches — what the retry paths need, since a failed item keeps
  /// its id.
  func testAForcedReinstallReplacesAnIdenticalSlice() async throws {
    let player = try await seeded(count: 3)
    let before = try await read(player) { $0.engine?.allMediaItems ?? [] }
    XCTAssertEqual(before.count, 3)

    await onQueue(player) { p in
      p.performingMutation { p.fullRebuildPlayerQueue(forcingReinstall: true) }
    }

    let after = try await read(player) { $0.engine?.allMediaItems ?? [] }
    XCTAssertEqual(after.count, 3)
    XCTAssertFalse(
      before[0] === after[0],
      "a forced reinstall must construct fresh items over the installed ones")
  }

  // MARK: - The windowed run

  /// The gapless player consumes finished items, so it holds the floor ahead
  /// of the playhead and nothing behind it — not the whole queue.
  func testTheGaplessRunIsBoundedAndHoldsNothingBehindThePlayhead() async throws {
    let player = try await seeded(count: 40)

    let installed = try await read(player) { $0.engine?.allMediaItems ?? [] }
    let ids = try await read(player) { $0.queueItemIds }

    XCTAssertEqual(
      installed.count, QueueWindowArithmetic.floor,
      "a long queue installs the floor, not all of it")
    XCTAssertEqual(
      installed.first?.queueItemId, ids.first,
      "the run starts at the playhead")
  }

  /// Advancing the playhead moves the run with it: what is now behind the
  /// trail comes out, and the floor ahead is restored.
  func testTheRunFollowsThePlayheadAndDropsWhatFallsBehindIt() async throws {
    let player = try await seeded(count: 40)
    let ids = try await read(player) { $0.queueItemIds }

    await onQueue(player) { p in
      p.currentTrackIndex = 12
      p.performingMutation { p.fullRebuildPlayerQueue() }
    }

    let installed = try await read(player) { $0.engine?.allMediaItems ?? [] }
    let held = installed.compactMap { $0.queueItemId }
    XCTAssertEqual(
      held.first, ids[12], "gapless keeps no trail, so the run starts at the playhead")
    XCTAssertEqual(held.count, QueueWindowArithmetic.floor)
    XCTAssertFalse(
      held.contains(ids[0]), "the position left behind is no longer installed")
  }

  /// The whole point of bounding the run: a long queue does not materialise
  /// end to end.
  func testALongQueueDoesNotMaterialiseEndToEnd() async throws {
    let player = try await seeded(count: 200)

    let installed = try await read(player) { $0.engine?.allMediaItems ?? [] }

    XCTAssertLessThanOrEqual(installed.count, QueueWindowArithmetic.floor + 2)
  }

  // MARK: - The crossfade run

  /// The crossfade engine retains what it is given, so its run carries a trail
  /// behind the playhead — the gapless player would drop it.
  func testTheCrossfadeRunCarriesATrailBehindThePlayhead() async throws {
    let player = try await seeded(count: 40, crossfade: true)
    let ids = try await read(player) { $0.queueItemIds }

    await onQueue(player) { p in
      p.currentTrackIndex = 12
      p.performingMutation { p.fullRebuildPlayerQueue() }
    }

    let held = try await read(player) {
      ($0.engine?.allMediaItems ?? []).compactMap { $0.queueItemId }
    }
    XCTAssertEqual(held.first, ids[10], "two played positions stay installed")
    XCTAssertTrue(held.contains(ids[12]), "the playhead is installed")
    XCTAssertLessThanOrEqual(
      held.count, QueueWindowArithmetic.floor + 2, "the run stays bounded")
  }

  /// Advancing drops what has fallen off the back of the trail without
  /// disturbing the item being heard.
  func testAdvancingTrimsTheTrailAndKeepsTheRenderingItem() async throws {
    let player = try await seeded(count: 40, crossfade: true)
    await onQueue(player) { p in
      p.currentTrackIndex = 12
      p.performingMutation { p.fullRebuildPlayerQueue() }
    }
    let ids = try await read(player) { $0.queueItemIds }
    let before = try await read(player) { $0.engine?.allMediaItems ?? [] }
    let rendering = try XCTUnwrap(before.first { $0.queueItemId == ids[12] })

    await onQueue(player) { p in
      p.currentTrackIndex = 13
      p.performingMutation { p.fullRebuildPlayerQueue() }
    }

    let after = try await read(player) { $0.engine?.allMediaItems ?? [] }
    let held = after.compactMap { $0.queueItemId }
    XCTAssertEqual(held.first, ids[11], "the trail moved up with the playhead")
    XCTAssertFalse(held.contains(ids[10]), "what fell off the trail is gone")
    XCTAssertTrue(
      after.contains { $0 === rendering },
      "the item being heard survives the trim")
  }

  /// Removing the item being heard replaces the leading leg's current item,
  /// which stops playback and restores no rate — so a divergence that would
  /// take it installs the run whole instead.
  func testADivergenceCoveringTheRenderingItemReinstallsRatherThanRemovingIt()
    async throws
  {
    let player = try await seeded(count: 20, crossfade: true)
    await onQueue(player) { p in
      p.currentTrackIndex = 6
      p.performingMutation { p.fullRebuildPlayerQueue() }
    }
    let before = try await read(player) { $0.engine?.allMediaItems ?? [] }
    let renderingId = try await read(player) { $0.engine?.leadingQueueItemId }
    XCTAssertNotNil(renderingId, "the engine is rendering something to protect")

    // Replace the model from a position at or before the playhead, so the run
    // diverges across the item being rendered.
    let replacement = try PlayerFixtures.localTracks(count: 6, for: self)
    await onQueue(player) { p in
      for index in stride(from: 10, through: 5, by: -1) {
        _ = p.queueState.remove(at: index)
      }
      _ = p.queueState.insert(replacement, at: 5)
      p.performingMutation { p.fullRebuildPlayerQueue() }
    }

    let after = try await read(player) { $0.engine?.allMediaItems ?? [] }
    let renderingAfter = try await read(player) { $0.engine?.leadingQueueItemId }
    XCTAssertFalse(after.isEmpty, "the run is reinstalled, not emptied")
    XCTAssertTrue(
      after.contains { $0.queueItemId == renderingAfter },
      "the engine renders an item its own run holds")
    XCTAssertFalse(
      before.contains { $0 === after.first },
      "a reinstall builds fresh items rather than surgically removing the old")
  }

  // MARK: - Helpers

  private var players: [TrackPlayer] = []

  override func tearDown() async throws {
    for player in players {
      _ = try? await player.destroy().await()
    }
    players = []
    try await super.tearDown()
  }

  private func seeded(count: Int, crossfade: Bool = false) async throws -> TrackPlayer {
    let player = TrackPlayer()
    players.append(player)
    _ = try await player.configure(config: PlayerFixtures.config()).await()
    if crossfade {
      _ = try await player.setPlaybackMode(
        mode: PlaybackMode(kind: .crossfade, crossfadeDurationMs: 3000)).await()
    }
    _ = try await player.setQueue(
      tracks: try PlayerFixtures.localTracks(count: count, for: self),
      startAtIndex: 0).await()
    return player
  }

  private func onQueue(_ player: TrackPlayer, _ body: @escaping (TrackPlayer) -> Void) async {
    await player.onPlayerQueue { body(player) }
  }

  private func read<T>(
    _ player: TrackPlayer, _ body: @escaping (TrackPlayer) -> T
  ) async throws -> T {
    await player.onPlayerQueueValue { body(player) }
  }
}
