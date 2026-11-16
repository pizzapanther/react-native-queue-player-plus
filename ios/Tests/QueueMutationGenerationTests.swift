import XCTest
@testable import QueuePlayer

/// Receiver work runs in the order the mutations were made, and work that
/// outlives its mutation does not act on a queue that has since been replaced.
final class QueueMutationGenerationTests: XCTestCase {

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

  // MARK: - Mirror ordering

  /// `addToQueue`, `removeFromQueue` and `moveInQueue` send index deltas
  /// resolved against the queue as it stood before their own mutation, so the
  /// receiver has to see them in that order or it diverges.
  func testMirrorsRunInTheOrderTheMutationsWereMade() async throws {
    let recorded = Recorder()

    for step in 0 ..< 5 {
      _ = player.enqueueThenMirror({ step }, mirror: { value, _ in
        await recorded.append(value)
      })
    }
    let last = player.enqueueThenMirror({ 5 }, mirror: { value, _ in
      await recorded.append(value)
    })
    _ = try await last.await()

    let order = await recorded.values
    XCTAssertEqual(order, [0, 1, 2, 3, 4, 5])
  }

  /// A slow round-trip holds the ones behind it rather than letting them
  /// overtake — the whole reason the chain exists.
  func testASlowMirrorDoesNotLetTheNextOneOvertakeIt() async throws {
    let recorded = Recorder()

    _ = player.enqueueThenMirror({ 0 }, mirror: { value, _ in
      try? await Task.sleep(nanoseconds: 120_000_000)
      await recorded.append(value)
    })
    let second = player.enqueueThenMirror({ 1 }, mirror: { value, _ in
      await recorded.append(value)
    })
    _ = try await second.await()

    let order = await recorded.values
    XCTAssertEqual(order, [0, 1], "the slow mirror must still land first")
  }

  /// The promise resolves only once that call's own receiver work has run, so
  /// awaiting a mutation means local and remote are both settled.
  func testTheCallResolvesOnlyAfterItsOwnMirrorHasRun() async throws {
    let recorded = Recorder()

    _ = try await player.enqueueThenMirror({ 0 }, mirror: { value, _ in
      await recorded.append(value)
    }).await()

    let order = await recorded.values
    XCTAssertEqual(order, [0])
  }

  // MARK: - Generation

  func testEachQueueMutationTakesTheNextGeneration() async throws {
    let start = try await read { $0.queueMutationGeneration }

    _ = try await player.setQueue(tracks: try tracks(3), startAtIndex: 0).await()
    let afterSet = try await read { $0.queueMutationGeneration }
    _ = try await player.addToQueue(tracks: try tracks(1), insertBefore: nil).await()
    let afterAdd = try await read { $0.queueMutationGeneration }

    XCTAssertEqual(afterSet, start &+ 1)
    XCTAssertEqual(afterAdd, start &+ 2)
  }

  /// The generation a mirror captured is what tells it whether the queue it
  /// was computed against is still the live one.
  func testAMirrorSeesItsOwnGenerationSupersededByALaterMutation() async throws {
    let captured = Recorder()
    _ = player.enqueueThenMirror({ 0 }, mirror: { _, generation in
      await captured.append(Int(generation))
    })
    _ = try await player.setQueue(tracks: try tracks(2), startAtIndex: 0).await()

    let capturedValues = await captured.values
    let mine = try XCTUnwrap(capturedValues.first)
    let live = try await read { Int($0.queueMutationGeneration) }
    XCTAssertGreaterThan(
      live, mine, "a later mutation must leave the earlier generation behind")
  }

  /// A burst leaves the queue the last call asked for, and the ids stay
  /// parallel to it.
  func testABurstLeavesTheQueueTheLastCallAskedFor() async throws {
    let start = try await read { $0.queueMutationGeneration }

    _ = try player.setQueue(tracks: try tracks(3), startAtIndex: 0)
    _ = try player.setQueue(tracks: try tracks(5), startAtIndex: 0)
    let last = try player.setQueue(tracks: try tracks(2), startAtIndex: 0)
    _ = try await last.await()

    let state = try await read {
      (count: $0.tracks.count, ids: $0.queueItemIds.count,
       generation: $0.queueMutationGeneration)
    }
    XCTAssertEqual(state.count, 2, "the last call in the burst is the one that stands")
    XCTAssertEqual(state.ids, state.count, "ids stay parallel to tracks through a burst")
    XCTAssertEqual(state.generation, start &+ 3)
  }

  // MARK: - Helpers

  private actor Recorder {
    private(set) var values: [Int] = []
    func append(_ value: Int) { values.append(value) }
  }

  private func tracks(_ count: Int) throws -> [TrackItem] {
    try PlayerFixtures.localTracks(count: count, for: self)
  }

  private func read<T>(_ body: @escaping (TrackPlayer) -> T) async throws -> T {
    let player = try XCTUnwrap(self.player)
    return await player.onPlayerQueueValue { body(player) }
  }
}
