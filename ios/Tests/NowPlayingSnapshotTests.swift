import AVFoundation
import XCTest
@testable import QueuePlayer

/// The lock-screen snapshot reports the engine's position, duration and
/// effective rate — the configured speed while playing, zero otherwise — for
/// whichever engine is active, including one with no gapless queue player.
final class NowPlayingSnapshotTests: XCTestCase {

  func testSnapshotReportsTheEnginesPositionAndDurationWithNoQueuePlayer() {
    let player = TrackPlayer()
    seedOneTrack(player)
    let engine = SnapshotEngineStub()
    engine.position = 42.5
    engine.duration = 180
    engine.playing = true
    player.engine = engine
    XCTAssertNil(player.player, "precondition: no gapless queue player to read")

    let snapshot = player.nowPlayingSnapshot()

    XCTAssertEqual(snapshot.elapsedSeconds, 42.5)
    XCTAssertEqual(snapshot.durationSeconds, 180)
    XCTAssertEqual(
      snapshot.rate, 1.0,
      "a playing engine must report a non-zero rate, or the lock screen renders paused")
  }

  func testRateIsZeroBecauseTheEngineIsNotPlayingRatherThanAlways() {
    let player = TrackPlayer()
    seedOneTrack(player)
    let engine = SnapshotEngineStub()
    engine.position = 10
    engine.duration = 60
    engine.playing = false
    player.engine = engine
    player.playbackSpeedState = 1.5

    XCTAssertEqual(player.nowPlayingSnapshot().rate, 0)
    XCTAssertEqual(
      player.nowPlayingSnapshot().elapsedSeconds, 10,
      "position still reports while paused")

    // Same fixture, same configured speed — only the play state changes.
    engine.playing = true
    XCTAssertEqual(player.nowPlayingSnapshot().rate, 1.5)
  }

  func testSnapshotCarriesTheConfiguredSpeedAsTheRate() {
    let player = TrackPlayer()
    seedOneTrack(player)
    let engine = SnapshotEngineStub()
    engine.playing = true
    player.engine = engine
    player.playbackSpeedState = 1.5

    XCTAssertEqual(player.nowPlayingSnapshot().rate, 1.5)
  }

  func testSnapshotHasNoTrackForAnEmptyQueue() {
    let player = TrackPlayer()
    player.engine = SnapshotEngineStub()

    XCTAssertNil(player.nowPlayingSnapshot().track)
  }

  func testSnapshotIdIsTheQueueEntryAtTheCurrentPositionNotTheTrackId() {
    let player = TrackPlayer()
    let track = TrackItem(
      id: "same", url: "https://example.com/0.m4a", title: "Song", artist: nil,
      album: nil, duration: nil, artworkUrl: nil, extras: nil,
      replayGainTrackGain: nil, replayGainTrackPeak: nil,
      replayGainAlbumGain: nil, replayGainAlbumPeak: nil)
    let ids = player.queueState.replaceAll([track, track])
    player.currentTrackIndex = 1
    player.engine = SnapshotEngineStub()

    XCTAssertEqual(player.nowPlayingSnapshot().queueItemId, ids[1])
  }

  private func seedOneTrack(_ player: TrackPlayer) {
    _ = player.queueState.replaceAll([
      TrackItem(
        id: "t0", url: "https://example.com/0.m4a", title: "Song", artist: nil,
        album: nil, duration: nil, artworkUrl: nil, extras: nil,
        replayGainTrackGain: nil, replayGainTrackPeak: nil,
        replayGainAlbumGain: nil, replayGainAlbumPeak: nil)
    ])
    player.currentTrackIndex = 0
  }
}

/// Reports whatever the test sets. Every other `PlaybackEngine` member is an
/// inert stub — the snapshot only reads position, duration and
/// `timeControlStatus`.
private final class SnapshotEngineStub: PlaybackEngine {
  var position: TimeInterval = 0
  var duration: TimeInterval = 0
  var playing = false

  var currentPositionSeconds: TimeInterval { position }
  var currentDurationSeconds: TimeInterval { duration }
  /// The snapshot reads `timeControlStatus`; `isPlaying` is here only to
  /// satisfy the protocol, and the two agree in this stub.
  var isPlaying: Bool { playing }

  func setAudioMixProvider(_ provider: AudioMixProvider?) {}
  func refreshAllItemMixes() {}
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
  var bufferedPositionSeconds: TimeInterval { 0 }
  var timeControlStatus: AVPlayer.TimeControlStatus { playing ? .playing : .paused }
  var currentMediaItem: AVPlayerItem? { nil }
  var allMediaItems: [AVPlayerItem] { [] }

  /// No fade to schedule — this stub exercises the rest of the surface.
  var isFadePendingOrActive: Bool { false }

  /// Nothing attached — this stub exercises the rest of the surface.
  var attachedQueueItemIds: [String] { [] }

  var delegate: PlaybackEngineDelegate?
}
