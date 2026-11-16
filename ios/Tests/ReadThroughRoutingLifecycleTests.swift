import XCTest
@testable import QueuePlayer

/// The read-through server is bound before the first item can be built and
/// stays up, on the same port, across the cache being disabled and enabled:
/// seated items address it by port and are never re-pointed. What changes
/// with the flag is which server new items are routed through.
final class ReadThroughRoutingLifecycleTests: XCTestCase {

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

  func testTheServerIsBoundOnceConfigureResolves() async throws {
    let port = try await read { $0.readThroughServer?.boundPortValue() ?? 0 }
    XCTAssertNotEqual(port, 0, "an item built right after configure must be able to route")
  }

  func testDisablingTheCacheKeepsTheServerAndStopsRoutingNewItems() async throws {
    let before = try await read {
      (server: $0.readThroughServer, port: $0.readThroughServer?.boundPortValue() ?? 0)
    }

    _ = try await player.setLookaheadCache(
      config: LookaheadCacheConfig(enabled: false, lookaheadCount: 3, allowsCellularAccess: nil)
    ).await()

    let disabled = try await read {
      (server: $0.readThroughServer, port: $0.readThroughServer?.boundPortValue() ?? 0,
       routing: $0.routingServer)
    }
    XCTAssertTrue(disabled.server === before.server, "the seated items' server must survive a disable")
    XCTAssertEqual(disabled.port, before.port)
    XCTAssertNil(disabled.routing, "an item built while disabled binds its origin")

    _ = try await player.setLookaheadCache(
      config: LookaheadCacheConfig(enabled: true, lookaheadCount: 3, allowsCellularAccess: nil)
    ).await()

    let enabled = try await read {
      (server: $0.readThroughServer, routing: $0.routingServer)
    }
    XCTAssertTrue(enabled.server === before.server, "re-enabling must not change the port items hold")
    XCTAssertTrue(enabled.routing === before.server)
  }

  private func read<T>(_ body: @escaping (TrackPlayer) -> T) async throws -> T {
    let player = try XCTUnwrap(self.player)
    return await player.onPlayerQueueValue { body(player) }
  }
}
