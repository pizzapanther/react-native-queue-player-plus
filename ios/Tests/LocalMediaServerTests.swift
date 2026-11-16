import XCTest
@testable import QueuePlayer

/// The cast media server's listener lifecycle, driven through `acquire` and
/// `release` on the shared instance. Every test closes what it acquires, so the
/// last release stops the listener before the next test.
final class LocalMediaServerTests: XCTestCase {

  /// Each acquire after a release returns with a bound port.
  func testReacquiringAfterReleaseHasABoundPort() async {
    let server = LocalMediaServer.shared
    for attempt in 0 ..< 20 {
      let handle = await server.acquire(sessionId: "reacquire-\(attempt)")
      XCTAssertNotNil(server.serverPortOrNil, "attempt \(attempt): acquire returned before the bind")
      XCTAssertTrue(server.isAnswering(), "attempt \(attempt)")
      handle.close()
    }
  }

  func testTheHealthRouteCarriesA32HexInstance() async throws {
    let handle = await LocalMediaServer.shared.acquire(sessionId: "health")
    defer { handle.close() }
    let port = try XCTUnwrap(LocalMediaServer.shared.serverPortOrNil)
    let config = URLSessionConfiguration.ephemeral
    config.timeoutIntervalForRequest = 10

    let (_, response) = try await URLSession(configuration: config)
      .data(from: URL(string: "http://127.0.0.1:\(port)/health")!)

    let http = try XCTUnwrap(response as? HTTPURLResponse)
    XCTAssertEqual(http.statusCode, 200)
    let instance = try XCTUnwrap(http.value(forHTTPHeaderField: MediaHTTPConnection.instanceHeader))
    XCTAssertEqual(instance.count, 32)
    XCTAssertTrue(instance.allSatisfy { $0.isHexDigit })
  }

  /// Reaches only the unbound branch: the last release stops the listener.
  func testAReleasedServerIsNotAnswering() async {
    let handle = await LocalMediaServer.shared.acquire(sessionId: "released")
    handle.close()

    XCTAssertNil(LocalMediaServer.shared.serverPortOrNil)
    XCTAssertFalse(LocalMediaServer.shared.isAnswering())
  }

  /// Closing one of two handles a session acquired leaves the other's URLs
  /// resolving.
  func testClosingOneHandleKeepsAnotherHandlesTokens() async throws {
    let kept = await LocalMediaServer.shared.acquire(sessionId: "shared-session")
    defer { kept.close() }
    let duplicate = await LocalMediaServer.shared.acquire(sessionId: "shared-session")
    let file = FileManager.default.temporaryDirectory.appendingPathComponent("kept-handle.mp3")
    try Data([0x49, 0x44, 0x33]).write(to: file)
    let url = try XCTUnwrap(kept.register(fileURL: file, lanAddress: "127.0.0.1"))

    duplicate.close()

    let config = URLSessionConfiguration.ephemeral
    config.timeoutIntervalForRequest = 10
    let (_, response) = try await URLSession(configuration: config).data(from: url)
    XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
  }

  func testAClosedHandleRegistersNothing() async throws {
    let open = await LocalMediaServer.shared.acquire(sessionId: "registers-open")
    defer { open.close() }
    let closed = await LocalMediaServer.shared.acquire(sessionId: "registers-closed")
    closed.close()
    let file = FileManager.default.temporaryDirectory.appendingPathComponent("closed-handle.mp3")

    XCTAssertNil(closed.register(fileURL: file, lanAddress: "127.0.0.1"))
    XCTAssertNotNil(open.register(fileURL: file, lanAddress: "127.0.0.1"), "control: an open handle registers")
  }
}
