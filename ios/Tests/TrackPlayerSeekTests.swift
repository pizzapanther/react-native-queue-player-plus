import MediaPlayer
import AVFoundation
import XCTest
@testable import QueuePlayer

/// `seekTo` resolves on every branch, including the ones that complete without
/// issuing a seek — a branch that returned without resuming would strand the
/// calling JS Promise for the life of the process.
final class TrackPlayerSeekTests: XCTestCase {

  /// A seek against a live engine writes the process-global
  /// `MPNowPlayingInfoCenter` on main. Drain that write and clear the centre
  /// so it does not reach the next test class.
  override func tearDown() {
    let drained = expectation(description: "pending main-queue now-playing writes complete")
    DispatchQueue.main.async { drained.fulfill() }
    wait(for: [drained], timeout: 5.0)
    MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
    super.tearDown()
  }

  func testSeekResolvesWithNoEngineAttached() {
    let player = TrackPlayer()
    expectSeekToResolve(player, position: 10, "no engine and no queue player")
  }

  func testSeekResolvesOnADrainedQueuePlayer() {
    let player = TrackPlayer()
    player.player = AVQueuePlayer()
    expectSeekToResolve(player, position: 10, "a queue player with no current item")
  }

  /// With no queue there is nothing to reinstall, so a seek on a drained
  /// player resolves without changing the end-of-queue state.
  func testSeekOnADrainedQueuePlayerWithNoQueueLeavesTheEndOfQueueStateSet() {
    let player = TrackPlayer()
    player.player = AVQueuePlayer()
    player.reachedQueueEnd = true

    expectSeekToResolve(player, position: 10, "a drained player at the end of the queue")

    XCTAssertTrue(
      player.reachedQueueEnd,
      "a seek that never reached the player must not report the queue as replaying")
  }

  func testSeekResolvesForAPositionBelowZero() {
    let player = TrackPlayer()
    expectSeekToResolve(player, position: -5, "a negative position")
  }

  /// A crossfade engine that does not hold the current track has it
  /// reinstalled, and the seek is then issued against the reinstalled run.
  func testSeekReinstallsAnAbsentCurrentTrackAndThenSeeksIt() {
    let player = TrackPlayer()
    let engine = RecordingEngineStub()
    _ = player.queueState.replaceAll((0 ..< 2).map {
      PlayerFixtures.track(id: "t\($0)", url: "http://127.0.0.1:1/\($0).m4a")
    })
    player.currentTrackIndex = 1
    player.engine = engine

    expectSeekToResolve(player, position: 30, "a current track the engine does not hold")

    XCTAssertTrue(
      engine.installedIds.contains(player.queueItemIds[1]),
      "the reinstalled run holds the current track")
    let seek = try? XCTUnwrap(engine.seeks.first)
    XCTAssertEqual(
      engine.allMediaItems[safe: seek?.index ?? -1]?.queueItemId, player.queueItemIds[1],
      "the seek targets the current track's position in the reinstalled run")
    XCTAssertEqual(seek?.position, 30)
  }

  /// Drives `seekTo` to completion and fails if it has not resolved within the
  /// timeout, rather than hanging the suite on a stranded continuation.
  private func expectSeekToResolve(
    _ player: TrackPlayer, position: Double, _ branch: String,
    file: StaticString = #filePath, line: UInt = #line
  ) {
    let resolved = expectation(description: "seekTo resolves on \(branch)")
    Task {
      do {
        _ = try await player.seekTo(position: position).await()
        resolved.fulfill()
      } catch {
        XCTFail("seekTo rejected on \(branch): \(error)", file: file, line: line)
      }
    }
    wait(for: [resolved], timeout: 5.0)
  }
}

/// Holds what it is given and records the seeks it is asked for.
private final class RecordingEngineStub: PlaybackEngine {
  private(set) var seeks: [(index: Int, position: TimeInterval)] = []
  private var installed: [AVPlayerItem] = []
  var installedIds: [String] { installed.compactMap { $0.queueItemId } }

  var currentMediaItem: AVPlayerItem? { nil }
  var allMediaItems: [AVPlayerItem] { installed }
  var attachedQueueItemIds: [String] { [] }
  var isFadePendingOrActive: Bool { false }
  var currentMediaItemIndex: Int { -1 }
  var currentPositionSeconds: TimeInterval { 0 }
  var currentDurationSeconds: TimeInterval { 0 }
  var bufferedPositionSeconds: TimeInterval { 0 }
  var isPlaying: Bool { false }
  var timeControlStatus: AVPlayer.TimeControlStatus { .paused }
  var delegate: PlaybackEngineDelegate?

  func release() {}
  func setItems(_ items: [AVPlayerItem], startIndex: Int, startPositionSeconds: TimeInterval) {
    installed = items
  }
  @discardableResult
  func insertItems(_ items: [AVPlayerItem], after: AVPlayerItem?) -> Bool {
    installed.append(contentsOf: items)
    return true
  }
  func remove(_ item: AVPlayerItem) { installed.removeAll { $0 === item } }
  func removeAllItems() { installed.removeAll() }
  func canInsert(_ item: AVPlayerItem, after: AVPlayerItem?) -> Bool { true }
  func play() {}
  func pause() {}
  func stop() {}
  func seek(toIndex index: Int, position: TimeInterval) { seeks.append((index, position)) }
  func skipToNext() {}
  func skipToPrevious() {}
  func setVolume(_ volume: Float) {}
  func setPlaybackSpeed(_ speed: Float) {}
  func seedPlaybackSpeed(_ speed: Float) {}
  func setRepeatMode(_ mode: RepeatMode) {}
  func setPitchCorrectionMode(_ mode: PitchCorrectionMode) {}
  func setAudioMixProvider(_ provider: AudioMixProvider?) {}
  func refreshAllItemMixes() {}
}
