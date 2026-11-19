import XCTest
@testable import QueuePlayer

final class TrackPlayerStateDedupTests: XCTestCase {
  func testTrackChangesRetainOutgoingTrackAndStateAcrossReplacementAndClear() throws {
    let player = TrackPlayer()
    var previousIds: [String?] = []
    var playing: [Bool] = []
    let dispose = try player.onTrackChange { _, _, _, _, previous, wasPlaying in
      previousIds.append(previous?.id)
      playing.append(wasPlaying)
    }
    player.onPlayerQueueSync {
      _ = player.queueState.replaceAll([PlayerFixtures.track(id: "first", url: "https://example.com/first.mp3")])
      player.currentTrackIndex = 0
      player.dispatchTrackChange(reason: .queueReplaced)
      player.emitState(.playing, reason: .user)
      _ = player.queueState.replaceAll([PlayerFixtures.track(id: "second", url: "https://example.com/second.mp3")])
      // Incoming loading state must not overwrite the outgoing playing state.
      player.emitState(.buffering, reason: .system)
      player.dispatchTrackChange(reason: .queueReplaced)
      player.emitState(.paused, reason: .user)
      player.queueState.clear()
      player.currentTrackIndex = -1
      player.emitTrackChange(nil, index: -1, reason: .queueReplaced, gap: nil)
      _ = player.queueState.replaceAll([PlayerFixtures.track(id: "third", url: "https://example.com/third.mp3")])
      player.currentTrackIndex = 0
      player.dispatchTrackChange(reason: .queueReplaced)
    }
    XCTAssertEqual(previousIds, [nil, "first", "second", nil])
    XCTAssertEqual(playing, [false, true, false, false])
    dispose()
  }

  func testStateEventsIgnoreDuplicatesRegardlessOfReasonOrSource() throws {
    let player = TrackPlayer()
    var states: [PlayerState] = []
    var reasons: [StateChangeReason] = []
    let dispose = try player.onStateChange { state, reason in
      states.append(state)
      reasons.append(reason)
    }
    XCTAssertTrue(states.isEmpty)
    player.onPlayerQueueSync {
      player.emitState(.none, reason: .system)
      player.emitState(.playing, reason: .user)
      player.emitState(.playing, reason: .system)
    }
    player.emitRemoteState(.playing, reason: .interruption)
    player.emitRemoteState(.paused, reason: .interruption)
    player.emitRemoteState(.paused, reason: .user)
    player.onPlayerQueueSync {
      player.emitState(.paused, reason: .system)
      player.emitState(.playing, reason: .system)
    }
    XCTAssertEqual(states, [.playing, .paused, .playing])
    XCTAssertEqual(reasons, [.user, .interruption, .system])
    dispose()
    player.onPlayerQueueSync { player.emitState(.paused, reason: .system) }
    XCTAssertEqual(states.count, 3)
  }
}
