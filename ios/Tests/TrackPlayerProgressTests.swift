import XCTest
@testable import QueuePlayer

final class TrackPlayerProgressTests: XCTestCase {
  func testEmptyPlayerReturnsZeros() throws {
    let progress = try TrackPlayer().getProgress()
    XCTAssertEqual(progress.position, 0)
    XCTAssertEqual(progress.duration, 0)
    XCTAssertEqual(progress.buffered, 0)
  }

  func testPausedSnapshotUsesEngineClockAndDoesNotConsumeProgressEvent() throws {
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
    XCTAssertEqual(seen.count, 1)
    XCTAssertEqual(seen.first?.position, progress.position)
    XCTAssertEqual(seen.first?.duration, progress.duration)
    XCTAssertEqual(seen.first?.buffered, progress.buffered)

    player.onPlayerQueueSync {
      engine.bufferedPositionSeconds = 10
      engine.currentDurationSeconds = .nan
    }
    XCTAssertEqual(try player.getProgress().buffered, 0)
    XCTAssertEqual(try player.getProgress().duration, 0)
    player.onPlayerQueueSync { player.queueState.clear() }
    XCTAssertEqual(try player.getProgress().position, 0)
  }
}
