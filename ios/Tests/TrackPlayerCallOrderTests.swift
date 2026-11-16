import XCTest
@testable import QueuePlayer

/// Queue mutations reach the player in the order JS called them.
///
/// Each method is issued and a probe is enqueued immediately afterwards, the
/// way a transport call issued on the next line would be. The probe runs on
/// `playerQueue` behind whatever the method enqueued, so what it observes is
/// what that next call would act on.
final class TrackPlayerCallOrderTests: XCTestCase {

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

  func testSetQueueIsVisibleToWorkEnqueuedAfterIt() async throws {
    let tracks = try PlayerFixtures.localTracks(count: 3, for: self)

    _ = try player.setQueue(tracks: tracks, startAtIndex: 1)
    let seen = try await probe { (count: $0.tracks.count, index: $0.currentTrackIndex) }

    XCTAssertEqual(seen.count, 3)
    XCTAssertEqual(seen.index, 1)
  }

  func testAddToQueueIsVisibleToWorkEnqueuedAfterIt() async throws {
    _ = try await player.setQueue(tracks: PlayerFixtures.localTracks(count: 3, for: self), startAtIndex: 0).await()

    _ = try player.addToQueue(tracks: PlayerFixtures.localTracks(count: 2, for: self), insertBefore: nil)
    let count = try await probe { $0.tracks.count }

    XCTAssertEqual(count, 5)
  }

  /// The immediate-next slot is the insert `AVQueuePlayer` prerolls, so it is
  /// the one whose commit is most at risk of being deferred.
  func testAnImmediateNextInsertIsVisibleToWorkEnqueuedAfterIt() async throws {
    _ = try await player.setQueue(tracks: PlayerFixtures.localTracks(count: 3, for: self), startAtIndex: 0).await()

    _ = try player.addToQueue(tracks: PlayerFixtures.localTracks(count: 1, for: self), insertBefore: 1)
    let count = try await probe { $0.tracks.count }

    XCTAssertEqual(count, 4)
  }

  func testRemoveFromQueueIsVisibleToWorkEnqueuedAfterIt() async throws {
    _ = try await player.setQueue(tracks: PlayerFixtures.localTracks(count: 4, for: self), startAtIndex: 0).await()

    _ = try player.removeFromQueue(indices: [2, 3])
    let count = try await probe { $0.tracks.count }

    XCTAssertEqual(count, 2)
  }

  func testMoveInQueueIsVisibleToWorkEnqueuedAfterIt() async throws {
    _ = try await player.setQueue(tracks: PlayerFixtures.localTracks(count: 3, for: self), startAtIndex: 0).await()

    _ = try player.moveInQueue(fromIndex: 2, toIndex: 1)
    let idAtOne = try await probe { $0.tracks[1].id }

    XCTAssertEqual(idAtOne, "t2")
  }

  /// A start index of NaN reaches `Int(_:)` unless it is guarded in `Double`
  /// space, where the conversion is undefined behaviour.
  func testANonFiniteStartIndexResolvesToTheQueueHead() async throws {
    _ = try player.setQueue(tracks: PlayerFixtures.localTracks(count: 3, for: self), startAtIndex: Double.nan)
    let index = try await probe { $0.currentTrackIndex }

    XCTAssertEqual(index, 0)
  }

  // MARK: - Helpers

  /// Enqueue a read behind whatever the call under test just enqueued.
  private func probe<T>(_ body: @escaping (TrackPlayer) -> T) async throws -> T {
    let player = try XCTUnwrap(self.player)
    return await player.onPlayerQueueValue { body(player) }
  }

}
