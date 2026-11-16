import XCTest
@testable import QueuePlayer

final class PlaybackStateRouterTests: XCTestCase {

  private let router = PlaybackStateRouter.shared

  override func setUp() {
    super.setUp()
    router._resetForTesting()
  }

  override func tearDown() {
    router._resetForTesting()
    super.tearDown()
  }

  func testActiveRemoteStartsNil() {
    XCTAssertNil(router.activeRemote())
  }

  func testSetActiveStoresSessionThenClearGoesNil() throws {
    let session = FakeRemoteSession()
    try router.setActive(session)
    XCTAssertTrue(router.activeRemote() === session)
    try router.setActive(nil)
    XCTAssertNil(router.activeRemote())
  }

  func testSetActiveWithSecondSessionThrowsAlreadyActive() throws {
    let first = FakeRemoteSession()
    let second = FakeRemoteSession()
    try router.setActive(first)
    XCTAssertThrowsError(try router.setActive(second)) { err in
      XCTAssertEqual(
        err as? PlaybackStateRouter.CastRouterError,
        .alreadyActive
      )
    }
    XCTAssertTrue(router.activeRemote() === first)
  }

  func testReRegisterSameSessionIsNoOp() throws {
    let session = FakeRemoteSession()
    try router.setActive(session)
    try router.setActive(session)  // no throw, no listener fire
    XCTAssertTrue(router.activeRemote() === session)
  }

  func testClearActiveOnlyClearsExpectedSession() throws {
    let first = FakeRemoteSession()
    let unrelated = FakeRemoteSession()
    try router.setActive(first)
    router.clearActive(expected: unrelated)
    XCTAssertTrue(
      router.activeRemote() === first,
      "clearActive(expected:) is a compare-and-clear; mismatched expected is a no-op"
    )
    router.clearActive(expected: first)
    XCTAssertNil(router.activeRemote())
  }

  func testListenerFiresOnEveryTransition() throws {
    var seen: [ObjectIdentifier?] = []
    _ = router.addActiveChangeListener { session in
      seen.append(session.map { ObjectIdentifier($0) })
    }
    let session = FakeRemoteSession()
    try router.setActive(session)
    try router.setActive(nil)

    XCTAssertEqual(seen.count, 2)
    XCTAssertEqual(seen[0], ObjectIdentifier(session))
    XCTAssertNil(seen[1])
  }

  func testListenerDoesNotFireOnReRegisterSameSession() throws {
    var fireCount = 0
    _ = router.addActiveChangeListener { _ in fireCount += 1 }
    let session = FakeRemoteSession()
    try router.setActive(session)
    try router.setActive(session)
    XCTAssertEqual(fireCount, 1)
  }

  func testListenerDoesNotFireOnRedundantClear() throws {
    var fireCount = 0
    _ = router.addActiveChangeListener { _ in fireCount += 1 }
    try router.setActive(nil)
    try router.setActive(nil)
    XCTAssertEqual(fireCount, 0)
  }

  func testDisposerRemovesListener() throws {
    var fireCount = 0
    let dispose = router.addActiveChangeListener { _ in fireCount += 1 }
    let session = FakeRemoteSession()
    try router.setActive(session)
    XCTAssertEqual(fireCount, 1)
    dispose()
    try router.setActive(nil)
    XCTAssertEqual(fireCount, 1, "disposed listener does not fire")
  }

  func testListenerNotInvokedOnSubscribe() throws {
    let session = FakeRemoteSession()
    try router.setActive(session)
    var fireCount = 0
    _ = router.addActiveChangeListener { _ in fireCount += 1 }
    XCTAssertEqual(
      fireCount, 0,
      "subscribe does not replay the current value; callers read activeRemote() for the snapshot"
    )
  }

}

// MARK: - Fake session

/// Minimal `CastSessionRemotePlayer` conformance for routing tests.
/// All transport calls record into invocation arrays; state getters
/// return deterministic seed values.
final class FakeRemoteSession: CastSessionRemotePlayer {
  var isPushSink: Bool { false }

  var lastState: RemotePlaybackStateKind = .idle
  var hasReportedState: Bool = false
  var lastPositionMs: Int64 = 0
  var lastDurationMs: Int64 = 0
  var lastBufferedMs: Int64 = 0
  var lastCurrentTrackIndex: Int = -1
  var lastVolume: Float = .nan

  private(set) var playCalls = 0
  private(set) var pauseCalls = 0
  private(set) var stopCalls = 0
  private(set) var seekCalls: [Int64] = []
  private(set) var seekByCalls: [Int64] = []
  private(set) var queueNextCalls = 0
  private(set) var queuePrevCalls = 0
  private(set) var queueJumpCalls: [Int] = []
  private(set) var volumeCalls: [Float] = []
  private(set) var playbackRateCalls: [Float] = []
  private(set) var repeatModeCalls: [RemoteRepeatMode] = []
  private(set) var loadCalls: [(items: [CastMediaItem], startIndex: Int, startPositionMs: Int64)] = []
  private(set) var closeCalls = 0

  func setVolume(_ value: Float) { volumeCalls.append(value) }
  func close() { closeCalls += 1 }

  func addStateListener(_ cb: @escaping (RemotePlaybackStateKind) -> Void) -> () -> Void { {} }
  func addPositionListener(_ cb: @escaping (Int64, Int64, Int64) -> Void) -> () -> Void { {} }
  func addCurrentTrackIndexListener(_ cb: @escaping (Int) -> Void) -> () -> Void { {} }
  func addEndedReasonListener(_ cb: @escaping (RemoteEndReasonKind) -> Void) -> () -> Void { {} }
  func addMediaErrorListener(_ cb: @escaping (RemoteMediaErrorPayload) -> Void) -> () -> Void { {} }
  func addSeekCompletedListener(_ cb: @escaping (Int64) -> Void) -> () -> Void { {} }
  func addVolumeListener(_ cb: @escaping (Float) -> Void) -> () -> Void { {} }

  func play() { playCalls += 1 }
  func pause() { pauseCalls += 1 }
  func stop() { stopCalls += 1 }
  func seekTo(positionMs: Int64) { seekCalls.append(positionMs) }
  func seekBy(deltaMs: Int64) { seekByCalls.append(deltaMs) }
  func queueNext() { queueNextCalls += 1 }
  func queuePrev() { queuePrevCalls += 1 }
  func skipToQueueIndex(index: Int) { queueJumpCalls.append(index) }
  func setPlaybackRate(_ rate: Float) { playbackRateCalls.append(rate) }
  func setRepeatMode(_ mode: RemoteRepeatMode) { repeatModeCalls.append(mode) }

  func load(items: [CastMediaItem], startIndex: Int, startPositionMs: Int64, playWhenReady: Bool) async throws -> [Int] {
    loadCalls.append((items, startIndex, startPositionMs))
    return (0..<items.count).map { $0 + 1 }
  }

  func queueInsert(items: [CastMediaItem], beforeIndex: Int?) async throws {}
  func queueRemove(indices: [Int]) async throws {}
  func queueMove(fromIndex: Int, toIndex: Int) async throws {}
  func queueClear() async throws {}
}
