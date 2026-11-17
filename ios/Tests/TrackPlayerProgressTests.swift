import XCTest
@testable import QueuePlayer

final class TrackPlayerProgressTests: XCTestCase {
  func testEmptyPlayerReturnsZeros() throws {
    let progress = try TrackPlayer().getProgress()
    XCTAssertEqual(progress.position, 0)
    XCTAssertEqual(progress.duration, 0)
    XCTAssertEqual(progress.buffered, 0)
  }

  func testPausedSnapshotRemainsReadableButOnlyPlayingEmitsProgress() throws {
    let player = TrackPlayer()
    let engine = ActiveItemEngineStub()
    player.onPlayerQueueSync {
      player.engine = engine
      _ = player.queueState.replaceAll([PlayerFixtures.track(id: "local", url: "file:///test.m4a")])
      player.currentTrackIndex = 0
      engine.currentPositionSeconds = 12.5
      engine.currentDurationSeconds = 90
      engine.bufferedPositionSeconds = 20
    }
    let progress = try player.getProgress()
    XCTAssertEqual(progress.position, 12.5)
    XCTAssertEqual(progress.duration, 90)
    XCTAssertEqual(progress.buffered, 7.5)

    var seen: [PlayerProgress] = []
    let dispose = try player.onProgress { seen.append($0) }
    defer { dispose() }
    _ = try player.getProgress()
    XCTAssertTrue(seen.isEmpty)
    player.onPlayerQueueSync { player.emitProgressIfSubscribed() }
    XCTAssertTrue(seen.isEmpty)
    player.onPlayerQueueSync {
      engine.currentPositionSeconds = 15
      player.emitProgressIfSubscribed() // Paused seek remains silent.
    }
    XCTAssertTrue(seen.isEmpty)
    XCTAssertEqual(try player.getProgress().position, 15)
    player.onPlayerQueueSync {
      engine.currentPositionSeconds = 12.5
      engine.isPlaying = true
      player.emitProgressIfSubscribed()
    }
    XCTAssertEqual(seen.count, 1)
    XCTAssertEqual(seen.first?.position, progress.position)
    XCTAssertEqual(seen.first?.duration, progress.duration)
    XCTAssertEqual(seen.first?.buffered, progress.buffered)

    player.onPlayerQueueSync {
      engine.isPlaying = false
      player.progressEmissionGate = ProgressEmissionGate()
      player.emitProgressIfSubscribed()
      engine.bufferedPositionSeconds = 10
      engine.currentDurationSeconds = .nan
    }
    XCTAssertEqual(seen.count, 1)
    XCTAssertEqual(try player.getProgress().buffered, 0)
    XCTAssertEqual(try player.getProgress().duration, 0)
    player.onPlayerQueueSync { player.queueState.clear() }
    XCTAssertEqual(try player.getProgress().position, 0)
  }

  func testRemoteProgressOnlyEmitsWhileReceiverIsPlaying() throws {
    let router = PlaybackStateRouter.shared
    router._resetForTesting()
    defer { router._resetForTesting() }
    let session = ControllableRemoteSession()
    try router.setActive(session)
    let player = TrackPlayer()
    var seen: [PlayerProgress] = []
    let dispose = try player.onProgress { seen.append($0) }
    defer { dispose() }
    for state in [RemotePlaybackStateKind.idle, .loading, .paused, .buffering, .ended, .error] {
      session.fireState(state)
      player.emitRemoteProgress(positionMs: 5000, durationMs: 10000, bufferedMs: 6000)
      player.onPlayerQueueSync {} // Drain the remote callback before changing state.
    }
    XCTAssertTrue(seen.isEmpty)
    session.fireState(.playing)
    player.emitRemoteProgress(positionMs: 5000, durationMs: 10000, bufferedMs: 6000)
    player.onPlayerQueueSync {}
    XCTAssertEqual(seen.count, 1)
    XCTAssertEqual(seen.first?.position, 5)
    session.fireState(.paused)
    player.onPlayerQueueSync { player.progressEmissionGate = ProgressEmissionGate() }
    player.emitRemoteProgress(positionMs: 6000, durationMs: 10000, bufferedMs: 7000)
    player.onPlayerQueueSync {}
    XCTAssertEqual(seen.count, 1)
    session.fireState(.playing)
    player.emitRemoteProgress(positionMs: 6000, durationMs: 10000, bufferedMs: 7000)
    player.onPlayerQueueSync {}
    XCTAssertEqual(seen.count, 2)
  }
}
