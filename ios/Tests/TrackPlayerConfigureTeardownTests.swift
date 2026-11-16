import XCTest
@testable import QueuePlayer

/// `configure()` is a clean-slate operation: it releases whichever engine is
/// live and clears the queue model before building the fresh one. The
/// "is anything configured?" gate keys on the engine, which both engines
/// populate — `player` holds the gapless `AVQueuePlayer` and is nil for the
/// whole time a `CrossfadeEngine` is active, so it cannot answer that
/// question.
final class TrackPlayerConfigureTeardownTests: XCTestCase {

  private var player: TrackPlayer!

  override func setUp() async throws {
    try await super.setUp()
    player = TrackPlayer()
  }

  override func tearDown() async throws {
    if let player {
      _ = try await player.destroy().await()
    }
    player = nil
    try await super.tearDown()
  }

  func testConfigureBuildsTheEngineWithTheConfiguredExternalPlayback() async throws {
    let player = try XCTUnwrap(self.player)
    for allowed: Bool? in [nil, true, false] {
      _ = try await player.configure(
        config: PlayerFixtures.config(allowsExternalPlayback: allowed)).await()
      let value = await player.onPlayerQueueValue {
        (player.engine as? GaplessEngine)?.player.allowsExternalPlayback
      }
      XCTAssertEqual(value, allowed ?? false, "allowsExternalPlayback=\(String(describing: allowed))")
    }
  }

  func testReconfigureClearsQueueUnderGapless() async throws {
    try await assertReconfigureIsCleanSlate(
      mode: PlaybackMode(kind: .gapless, crossfadeDurationMs: nil),
      label: "gapless"
    )
  }

  func testReconfigureClearsQueueUnderCrossfade() async throws {
    try await assertReconfigureIsCleanSlate(
      mode: PlaybackMode(kind: .crossfade, crossfadeDurationMs: 3000),
      label: "crossfade"
    )
  }

  /// Configure, install [mode], load a queue, then configure again without an
  /// intervening `destroy()`. The second configure must have torn the prior
  /// engine down: queue empty, current index reset.
  private func assertReconfigureIsCleanSlate(
    mode: PlaybackMode, label: String
  ) async throws {
    _ = try await player.configure(config: PlayerFixtures.config()).await()
    _ = try await player.setPlaybackMode(mode: mode).await()
    let tracks = try PlayerFixtures.localTracks(count: 3, for: self)
    _ = try await player.setQueue(tracks: tracks, startAtIndex: 0).await()

    let seeded = try await player.getQueue().await()
    XCTAssertEqual(
      seeded.count, tracks.count,
      "\(label): expected the seeded queue before the re-configure")

    _ = try await player.configure(config: PlayerFixtures.config()).await()

    let cleared = try await player.getQueue().await()
    XCTAssertEqual(
      cleared.count, 0,
      "\(label): configure() must clear the queue, got \(cleared.count) entries")
    XCTAssertEqual(
      try player.getCurrentTrackIndex(), -1,
      "\(label): configure() must reset the current index")
  }

  /// The re-configure clears the queue without an emit; the revision the next
  /// emit carries skips one, which is the signal a delta mirror re-reads on.
  func testReconfigureLeavesARevisionGapForTheNextEmit() async throws {
    _ = try await player.configure(config: PlayerFixtures.config()).await()
    var revisions: [Int] = []
    let unsubscribe = try player.onQueueChange { delta, _, _ in
      revisions.append(Int(delta.revision))
    }
    defer { unsubscribe() }
    _ = try await player.setQueue(tracks: PlayerFixtures.localTracks(count: 2, for: self), startAtIndex: 0).await()
    let before = try XCTUnwrap(revisions.last)

    _ = try await player.configure(config: PlayerFixtures.config()).await()
    _ = try await player.setQueue(tracks: PlayerFixtures.localTracks(count: 1, for: self), startAtIndex: 0).await()

    XCTAssertEqual(revisions.last, before + 2)
  }

}
