import XCTest
@testable import QueuePlayer

final class TrackPlayerStateDedupTests: XCTestCase {
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
