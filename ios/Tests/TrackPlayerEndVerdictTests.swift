import XCTest
@testable import QueuePlayer

/// A drained engine reaches the same end-of-queue verdict whichever engine is
/// installed. The engines report that they drained; the queue above them
/// decides what that means.
final class TrackPlayerEndVerdictTests: XCTestCase {

  private var player: TrackPlayer!

  override func setUp() async throws {
    try await super.setUp()
    player = TrackPlayer()
    _ = try await player.configure(config: PlayerFixtures.config()).await()
  }

  override func tearDown() async throws {
    if let player {
      _ = try await player.destroy().await()
    }
    player = nil
    try await super.tearDown()
  }

  // MARK: - The wrap case

  func testRepeatQueueWrapsToTheHeadFromTheTail() async throws {
    try await seedQueue(count: 3, at: 2, repeatMode: .queue)

    await onQueue { $0.applyEndVerdict() }

    let state = try await read { (index: $0.currentTrackIndex, ended: $0.reachedQueueEnd) }
    XCTAssertEqual(state.index, 0)
    XCTAssertFalse(state.ended, "a wrap is not the end of the queue")
  }

  /// A single-track queue is already at position 0, so only the re-seat marks
  /// the wrap: the dedup key is cleared so the repeated track still emits.
  func testRepeatQueueReSeatsASingleTrackQueue() async throws {
    try await seedQueue(count: 1, at: 0, repeatMode: .queue)
    await onQueue { $0.lastEmittedQueueItemId = "stale" }

    await onQueue { $0.applyEndVerdict() }

    let state = try await read {
      (ended: $0.reachedQueueEnd, emitted: $0.lastEmittedQueueItemId,
       head: $0.queueItemIds.first)
    }
    XCTAssertFalse(state.ended)
    XCTAssertEqual(
      state.emitted, state.head,
      "clearing the dedup key lets the repeated track emit again")
  }

  /// Under crossfade there is no `AVQueuePlayer`, and the verdict must not
  /// depend on one being there.
  func testRepeatQueueWrapsUnderCrossfadeToo() async throws {
    try await installCrossfade()
    try await seedQueue(count: 3, at: 2, repeatMode: .queue)
    let queuePlayer = try await read { $0.player }
    XCTAssertNil(queuePlayer, "precondition: no gapless queue player")

    await onQueue { $0.applyEndVerdict() }

    let state = try await read { (index: $0.currentTrackIndex, ended: $0.reachedQueueEnd) }
    XCTAssertEqual(state.index, 0)
    XCTAssertFalse(state.ended, "a wrap is not the end of the queue on either engine")
  }

  func testRepeatOffEndsTheQueueAtTheTail() async throws {
    try await seedQueue(count: 3, at: 2, repeatMode: .off)

    await onQueue { $0.applyEndVerdict() }

    let ended = try await read { $0.reachedQueueEnd }
    XCTAssertTrue(ended)
  }

  // MARK: - The engine's own signal reaches the verdict

  /// The crossfade engine reports a drain through the delegate, which is the
  /// route that has to reach the same decision.
  func testTheEngineDelegateEndSignalWrapsTheQueue() async throws {
    try await installCrossfade()
    try await seedQueue(count: 3, at: 2, repeatMode: .queue)
    let engine = try await read { $0.engine }
    let reporting = try XCTUnwrap(engine)

    await onQueue { $0.enginePlaybackEnded(reporting) }

    let state = try await read { (index: $0.currentTrackIndex, ended: $0.reachedQueueEnd) }
    XCTAssertEqual(state.index, 0)
    XCTAssertFalse(state.ended)
  }

  // MARK: - A tail that cannot be built

  /// A track whose URL cannot be built is dropped from the engine's items, so
  /// the engine drains before the logical tail. Re-seating that position
  /// installs nothing and fires no further engine signal, so the decision has
  /// to be retaken rather than left parked mid-queue.
  func testAnUnbuildableTailStillEndsTheQueue() async throws {
    try await installCrossfade()
    try await seedQueue(count: 3, at: 1, repeatMode: .off, lastTrackURL: "")

    await onQueue { $0.applyEndVerdict() }

    let ended = try await read { $0.reachedQueueEnd }
    XCTAssertTrue(ended, "an unplayable last track must still end the queue")
  }

  /// Repeat-track re-seats the same position, so when that position cannot
  /// be built there is nothing to refill: the queue ends rather than being
  /// re-decided on the same anchor again.
  func testAnUnbuildableCurrentTrackUnderRepeatTrackEndsTheQueue() async throws {
    try await installCrossfade()
    try await seedQueue(count: 3, at: 2, repeatMode: .track, lastTrackURL: "")

    await onQueue { $0.applyEndVerdict() }

    let state = try await read { (index: $0.currentTrackIndex, ended: $0.reachedQueueEnd) }
    XCTAssertEqual(state.index, 2)
    XCTAssertTrue(state.ended, "an unplayable repeated track has nothing to refill")
  }

  func testAnUnbuildableTailStillWrapsUnderRepeatQueue() async throws {
    try await installCrossfade()
    try await seedQueue(count: 3, at: 1, repeatMode: .queue, lastTrackURL: "")

    await onQueue { $0.applyEndVerdict() }

    let state = try await read { (index: $0.currentTrackIndex, ended: $0.reachedQueueEnd) }
    XCTAssertEqual(state.index, 0)
    XCTAssertFalse(state.ended)
  }

  // MARK: - A drain that is really a failure

  /// A drain that follows the current track's reported failure stays where it
  /// is rather than wrapping.
  func testADrainAfterTheCurrentTrackFailedDoesNotAdvanceTheQueue() async throws {
    try await seedQueue(count: 3, at: 2, repeatMode: .queue)
    await onQueue { $0.reportedFailureQueueItemIds = [$0.queueItemIds[2]] }

    await onQueue { $0.applyEndVerdict() }

    let state = try await read { (index: $0.currentTrackIndex, ended: $0.reachedQueueEnd) }
    XCTAssertEqual(state.index, 2, "the queue stays on the track whose failure was reported")
    XCTAssertFalse(state.ended, "a reported failure is not the end of the queue either")
  }

  /// The crossfade engine's own end signal reaches the same decision.
  func testAnEngineEndReportAfterTheCurrentTrackFailedDoesNotAdvanceTheQueue() async throws {
    try await installCrossfade()
    try await seedQueue(count: 3, at: 2, repeatMode: .queue)
    await onQueue { $0.reportedFailureQueueItemIds = [$0.queueItemIds[2]] }
    let engine = try await read { $0.engine }
    let reporting = try XCTUnwrap(engine)

    await onQueue { $0.enginePlaybackEnded(reporting) }

    let index = try await read { $0.currentTrackIndex }
    XCTAssertEqual(index, 2)
  }

  /// A report against a track the playhead has left does not hold a genuine
  /// end of queue open.
  func testADrainAfterADifferentTrackFailedStillEndsTheQueue() async throws {
    try await seedQueue(count: 3, at: 2, repeatMode: .off)
    await onQueue { $0.reportedFailureQueueItemIds = [$0.queueItemIds[0]] }

    await onQueue { $0.applyEndVerdict() }

    let ended = try await read { $0.reachedQueueEnd }
    XCTAssertTrue(ended)
  }

  // MARK: - Helpers

  private func installCrossfade() async throws {
    _ = try await player.setPlaybackMode(
      mode: PlaybackMode(kind: .crossfade, crossfadeDurationMs: 3000)).await()
  }

  private func seedQueue(
    count: Int, at index: Int, repeatMode: RepeatMode, lastTrackURL: String? = nil
  ) async throws {
    var tracks = try PlayerFixtures.localTracks(count: count, for: self)
    if let lastTrackURL {
      tracks[count - 1] = PlayerFixtures.track(id: "unbuildable", url: lastTrackURL)
    }
    _ = try await player.setQueue(tracks: tracks, startAtIndex: 0).await()
    _ = try await player.setRepeatMode(mode: repeatMode).await()
    await onQueue { $0.currentTrackIndex = index }
  }

  private func onQueue(_ body: @escaping (TrackPlayer) -> Void) async {
    guard let player else { return }
    await player.onPlayerQueue { body(player) }
  }

  private func read<T>(_ body: @escaping (TrackPlayer) -> T) async throws -> T {
    let player = try XCTUnwrap(self.player)
    return await player.onPlayerQueueValue { body(player) }
  }
}
