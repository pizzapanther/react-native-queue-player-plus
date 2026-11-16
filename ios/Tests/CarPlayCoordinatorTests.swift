import XCTest
@testable import QueuePlayer

/// `CarPlayCoordinator` connection-flag tests. The flag is written by the
/// CarPlay scene delegate at scene attach / detach; this suite locks the
/// get/set contract the `isCarConnected()` getter reads back.
final class CarPlayCoordinatorTests: XCTestCase {

  override func setUp() {
    super.setUp()
    // Shared process-wide singleton — start from a known-disconnected state
    // regardless of test order.
    CarPlayCoordinator.shared.setCarConnected(false)
  }

  override func tearDown() {
    // Shared process-wide singleton — leave it disconnected so it can't
    // leak a connected state into another suite.
    CarPlayCoordinator.shared.setCarConnected(false)
    super.tearDown()
  }

  func testCarConnectedFlagReflectsSetter() {
    let coordinator = CarPlayCoordinator.shared

    coordinator.setCarConnected(false)
    XCTAssertFalse(coordinator.isCarConnected, "disconnected after setCarConnected(false)")

    coordinator.setCarConnected(true)
    XCTAssertTrue(coordinator.isCarConnected, "connected after setCarConnected(true)")

    coordinator.setCarConnected(false)
    XCTAssertFalse(coordinator.isCarConnected, "disconnected again after setCarConnected(false)")
  }

  /// Only a playing player opens CarPlay on Now Playing.
  func testOnlyPlayingOpensNowPlayingAtConnect() {
    XCTAssertTrue(CarPlayCoordinator.opensNowPlayingAtConnect(state: .playing))
    for state: PlayerState in [.none, .paused, .buffering, .loading, .ended, .error] {
      XCTAssertFalse(CarPlayCoordinator.opensNowPlayingAtConnect(state: state), state.stringValue)
    }
  }

  /// `configure` registers the reader, which replies on main; `destroy` clears
  /// it. Configured twice, so a reader cleared by the second configure's own
  /// teardown would show.
  func testThePlayerStateReaderLivesFromConfigureToDestroy() async throws {
    let player = TrackPlayer()
    addTeardownBlock { _ = try? await player.destroy().await() }
    _ = try await player.configure(config: PlayerFixtures.config()).await()
    _ = try await player.configure(config: PlayerFixtures.config()).await()
    let reader = try XCTUnwrap(CarPlayCoordinator.shared.playerStateReader)

    let replied = expectation(description: "reply on main")
    reader { state in
      XCTAssertTrue(Thread.isMainThread)
      XCTAssertEqual(state, PlayerState.none, "an empty queue")
      replied.fulfill()
    }
    await fulfillment(of: [replied], timeout: 5)

    _ = try await player.destroy().await()
    XCTAssertNil(CarPlayCoordinator.shared.playerStateReader)
  }
}
