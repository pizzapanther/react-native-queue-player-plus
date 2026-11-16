import Network
import XCTest
@testable import QueuePlayer

/// The loopback server the player reads every remote track through.
///
/// These drive a real listener over a real socket, because what matters here is
/// what an HTTP client actually receives — the byte-range maths is covered by
/// `MediaHTTPConnectionTests`.
final class ReadThroughServerTests: XCTestCase {

  private var tempDir: URL!

  override func setUpWithError() throws {
    tempDir = FileManager.default.temporaryDirectory
      .appendingPathComponent("readthrough-tests-\(UUID().uuidString)")
    try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
  }

  override func tearDownWithError() throws {
    try? FileManager.default.removeItem(at: tempDir)
  }

  private func writeFile(_ bytes: [UInt8], named name: String = "track.mp3") throws -> URL {
    let url = tempDir.appendingPathComponent(name)
    try Data(bytes).write(to: url)
    return url
  }

  /// An origin that cannot be reached, for the paths where the cache must be
  /// what answers — a request that reached the network would fail loudly.
  private let unreachableOrigin = URL(string: "http://127.0.0.1:1/never.mp3")!

  private func makeServer(
    cachedFile: @escaping (String) -> URL? = { _ in nil },
    onFirstDiskHit: @escaping (String) -> Void = { _ in }
  ) async -> ReadThroughServer {
    let server = ReadThroughServer(
      cachedFileForOrigin: cachedFile,
      onFirstDiskHit: onFirstDiskHit
    )
    server.start()
    XCTAssertTrue(server.waitUntilBound(), "the loopback listener binds within the wait")
    return server
  }

  /// The cache is keyed on the consumer's `track.url` verbatim; the lookup
  /// receives that string, not the origin URL's re-serialisation.
  func testTheCacheLookupReceivesTheSourceStringVerbatim() async throws {
    var asked: [String] = []
    let server = await makeServer(cachedFile: { asked.append($0); return nil })
    defer { server.stop() }
    // Loopback port 1 refuses at once; the space is what the assertion needs.
    let raw = "http://127.0.0.1:1/a b.mp3"
    let origin = try XCTUnwrap(URL(string: raw))
    let local = try XCTUnwrap(server.localURL(for: origin, sourceURL: raw))

    _ = try? await get(local)

    // The refused origin drops the connection and URLSession retries the
    // request, so the lookup runs once per attempt; the string is what matters.
    XCTAssertFalse(asked.isEmpty)
    XCTAssertEqual(Set(asked), [raw])
    XCTAssertNotEqual(origin.absoluteString, raw, "the origin re-serialises the space; the key must not")
  }

  /// A raw HTTP client that reads `chunk` bytes at a time with a pause between
  /// reads, so the relay is what fills up rather than the client. Returns the
  /// body once `Content-Length` bytes have arrived.
  private func slowGet(_ url: URL, chunk: Int, pauseNanoseconds: UInt64) async throws -> Data {
    let host = try XCTUnwrap(url.host)
    let port = try XCTUnwrap(NWEndpoint.Port(rawValue: UInt16(try XCTUnwrap(url.port))))
    let connection = NWConnection(host: NWEndpoint.Host(host), port: port, using: .tcp)
    defer { connection.cancel() }
    connection.start(queue: DispatchQueue(label: "slow-get"))
    // A relay that stops delivering would otherwise block the receive forever.
    let deadline = DispatchWorkItem { connection.cancel() }
    DispatchQueue.global().asyncAfter(deadline: .now() + 30, execute: deadline)
    defer { deadline.cancel() }
    let request = "GET \(url.path) HTTP/1.1\r\nHost: \(host)\r\nConnection: close\r\n\r\n"
    try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
      connection.send(content: Data(request.utf8), completion: .contentProcessed { error in
        if let error { cont.resume(throwing: error) } else { cont.resume() }
      })
    }
    var received = Data()
    var bodyStart: Int?
    var contentLength: Int?
    while true {
      let (piece, complete) = try await withCheckedThrowingContinuation {
        (cont: CheckedContinuation<(Data?, Bool), Error>) in
        connection.receive(minimumIncompleteLength: 1, maximumLength: chunk) {
          data, _, isComplete, error in
          if let error { cont.resume(throwing: error) } else { cont.resume(returning: (data, isComplete)) }
        }
      }
      if let piece { received.append(piece) }
      if bodyStart == nil, let headerEnd = received.range(of: Data("\r\n\r\n".utf8)) {
        bodyStart = headerEnd.upperBound
        let head = String(decoding: received[..<headerEnd.lowerBound], as: UTF8.self)
        let lengthLine = head.split(separator: "\r\n").first { $0.lowercased().hasPrefix("content-length:") }
        contentLength = lengthLine.flatMap { Int($0.split(separator: ":")[1].trimmingCharacters(in: .whitespaces)) }
      }
      if let bodyStart, let contentLength, received.count - bodyStart >= contentLength {
        return received[bodyStart ..< bodyStart + contentLength]
      }
      if complete { throw URLError(.networkConnectionLost) }
      try await Task.sleep(nanoseconds: pauseNanoseconds)
    }
  }

  private func get(_ url: URL, range: String? = nil) async throws -> (HTTPURLResponse, Data) {
    var request = URLRequest(url: url)
    if let range { request.setValue(range, forHTTPHeaderField: "Range") }
    let config = URLSessionConfiguration.ephemeral
    config.timeoutIntervalForRequest = 10
    let (data, response) = try await URLSession(configuration: config).data(for: request)
    return (response as! HTTPURLResponse, data)
  }

  // MARK: - Routing

  func testNoLocalURLBeforeTheListenerIsUp() {
    let server = ReadThroughServer(cachedFileForOrigin: { _ in nil }, onFirstDiskHit: { _ in })
    XCTAssertNil(
      server.localURL(for: unreachableOrigin),
      "an unbound server must report no URL so the caller keeps the origin")
  }

  func testTheURLIsLoopbackAndCarriesA32HexToken() async {
    let server = await makeServer()
    defer { server.stop() }
    let url = server.localURL(for: unreachableOrigin)
    XCTAssertEqual(url?.host, "127.0.0.1")
    let token = url?.lastPathComponent ?? ""
    XCTAssertEqual(token.count, 32, token)
    XCTAssertTrue(token.allSatisfy { $0.isHexDigit }, token)
  }

  /// One token per origin for the life of the session. A window rebuild hands
  /// the player the URL it already holds, so the asset never re-points and
  /// AVFoundation keeps reusing its own connections.
  func testTheSameOriginAlwaysGetsTheSameURL() async {
    let server = await makeServer()
    defer { server.stop() }
    let first = server.localURL(for: unreachableOrigin)
    let second = server.localURL(for: unreachableOrigin)
    XCTAssertEqual(first, second)
  }

  func testDifferentOriginsGetDifferentTokens() async {
    let server = await makeServer()
    defer { server.stop() }
    let a = server.localURL(for: URL(string: "https://example.com/a.mp3")!)
    let b = server.localURL(for: URL(string: "https://example.com/b.mp3")!)
    XCTAssertNotEqual(a, b)
  }

  // MARK: - Serving

  func testAFullyCachedFileIsServedFromDisk() async throws {
    let bytes = (0 ..< 2048).map { UInt8($0 % 251) }
    let file = try writeFile(bytes)
    let server = await makeServer(cachedFile: { _ in file })
    defer { server.stop() }
    let url = try XCTUnwrap(server.localURL(for: unreachableOrigin))

    let (response, data) = try await get(url)

    XCTAssertEqual(response.statusCode, 200)
    XCTAssertEqual([UInt8](data), bytes, "the bytes served are not the cached file's")
  }

  func testARangeRequestOnACachedFileGetsThatRange() async throws {
    let bytes = (0 ..< 2048).map { UInt8($0 % 251) }
    let file = try writeFile(bytes)
    let server = await makeServer(cachedFile: { _ in file })
    defer { server.stop() }
    let url = try XCTUnwrap(server.localURL(for: unreachableOrigin))

    let (response, data) = try await get(url, range: "bytes=100-199")

    XCTAssertEqual(response.statusCode, 206)
    XCTAssertEqual(response.value(forHTTPHeaderField: "Content-Range"), "bytes 100-199/2048")
    XCTAssertEqual([UInt8](data), Array(bytes[100 ... 199]))
  }

  /// AVFoundation opens every remote asset with this probe and reads the total
  /// size out of the `Content-Range`.
  func testTheContentInfoProbeIsAnsweredWithTheResourceSize() async throws {
    let file = try writeFile((0 ..< 5000).map { UInt8($0 % 251) })
    let server = await makeServer(cachedFile: { _ in file })
    defer { server.stop() }
    let url = try XCTUnwrap(server.localURL(for: unreachableOrigin))

    let (response, data) = try await get(url, range: "bytes=0-1")

    XCTAssertEqual(response.statusCode, 206)
    XCTAssertEqual(response.value(forHTTPHeaderField: "Content-Range"), "bytes 0-1/5000")
    XCTAssertEqual(data.count, 2)
  }

  func testAnUnknownTokenIs404() async throws {
    let server = await makeServer()
    defer { server.stop() }
    let port = server.boundPortValue()
    let url = URL(string: "http://127.0.0.1:\(port)/media/\(String(repeating: "a", count: 32))")!

    let (response, _) = try await get(url)

    XCTAssertEqual(response.statusCode, 404)
  }

  /// `/health` needs no token and never consults the cache.
  func testTheHealthRouteAnswers200WithAnEmptyBody() async throws {
    let lookups = TouchCounter()
    let server = await makeServer(cachedFile: { _ in lookups.bump(); return nil })
    defer { server.stop() }
    let port = server.boundPortValue()
    let url = URL(string: "http://127.0.0.1:\(port)/health")!

    let (response, data) = try await get(url)

    XCTAssertEqual(response.statusCode, 200)
    XCTAssertTrue(data.isEmpty)
    XCTAssertEqual(lookups.value, 0)
    let instance = try XCTUnwrap(
      response.value(forHTTPHeaderField: MediaHTTPConnection.instanceHeader),
      "the read-through server identifies itself, so a probe can tell it from a port squatter")
    XCTAssertEqual(instance.count, 32)
  }

  /// The client names a token and nothing else. A path or query that carried a
  /// URL would make this an open relay that fetches whatever it is told with
  /// the consumer's credentials attached.
  func testAPathThatNamesAURLIsRejected() async throws {
    let server = await makeServer()
    defer { server.stop() }
    let port = server.boundPortValue()
    let url = URL(string: "http://127.0.0.1:\(port)/media/https%3A%2F%2Fevil.example.com%2Fx.mp3")!

    let (response, _) = try await get(url)

    XCTAssertEqual(response.statusCode, 404)
  }

  /// A cache entry whose file has gone reads through instead of failing, so a
  /// `clear()` during playback costs a re-fetch rather than a dead item.
  func testAMissingCacheFileFallsThroughToTheOrigin() async throws {
    let vanished = tempDir.appendingPathComponent("not-written.mp3")
    let server = await makeServer(cachedFile: { _ in vanished })
    defer { server.stop() }
    let url = try XCTUnwrap(server.localURL(for: unreachableOrigin))

    // The origin is unreachable, so falling through surfaces as the server
    // closing the connection without a response — the origin leg was tried
    // and failed. A 404 would mean it never tried.
    do {
      let (response, _) = try await get(url)
      XCTFail(
        "a missing cache file must read through to the origin; got HTTP \(response.statusCode) instead of a dropped connection")
    } catch let error as URLError {
      XCTAssertEqual(
        error.code, .networkConnectionLost,
        "expected the origin leg to close the connection without a reply")
    }
  }

  /// A cache miss relays the origin's body, in order and to the last byte. A
  /// second loopback server serving the file from disk stands in as the origin,
  /// so the relay leg runs over a real socket in both directions. The body is
  /// larger than the relay's read buffer, so the bytes cross more than one
  /// delivery.
  func testACacheMissRelaysTheOriginBodyIntact() async throws {
    let bytes = (0 ..< 3_000_000).map { UInt8($0 % 251) }
    let file = try writeFile(bytes)
    let origin = await makeServer(cachedFile: { _ in file })
    defer { origin.stop() }
    let originURL = try XCTUnwrap(origin.localURL(for: unreachableOrigin))
    let relay = await makeServer(cachedFile: { _ in nil })
    defer { relay.stop() }
    let url = try XCTUnwrap(relay.localURL(for: originURL))

    let (response, data) = try await get(url)

    XCTAssertEqual(response.statusCode, 200)
    XCTAssertEqual(data.count, bytes.count)
    XCTAssertEqual([UInt8](data), bytes, "the relayed bytes are not the origin's")
  }

  /// The playback case: AVFoundation reads at playback rate while the origin
  /// delivers as fast as the network allows. The relay holds a bounded amount
  /// and stops reading the origin until the client has drained it — and then
  /// reads again, so a slow client still receives the whole body.
  func testASlowClientStillReceivesTheWholeRelayedBody() async throws {
    let bytes = (0 ..< 6_000_000).map { UInt8($0 % 251) }
    let file = try writeFile(bytes)
    let origin = await makeServer(cachedFile: { _ in file })
    defer { origin.stop() }
    let originURL = try XCTUnwrap(origin.localURL(for: unreachableOrigin))
    let relay = await makeServer(cachedFile: { _ in nil })
    defer { relay.stop() }
    let url = try XCTUnwrap(relay.localURL(for: originURL))

    let body = try await slowGet(url, chunk: 64 * 1024, pauseNanoseconds: 3_000_000)

    XCTAssertEqual(body.count, bytes.count)
    XCTAssertEqual([UInt8](body), bytes, "the relayed bytes are not the origin's")
  }

  /// The range a client asks for is what the origin is asked for.
  func testACacheMissRelaysARangeRequestToTheOrigin() async throws {
    let bytes = (0 ..< 2048).map { UInt8($0 % 251) }
    let file = try writeFile(bytes)
    let origin = await makeServer(cachedFile: { _ in file })
    defer { origin.stop() }
    let originURL = try XCTUnwrap(origin.localURL(for: unreachableOrigin))
    let relay = await makeServer(cachedFile: { _ in nil })
    defer { relay.stop() }
    let url = try XCTUnwrap(relay.localURL(for: originURL))

    let (response, data) = try await get(url, range: "bytes=100-199")

    XCTAssertEqual(response.statusCode, 206)
    XCTAssertEqual(response.value(forHTTPHeaderField: "Content-Range"), "bytes 100-199/2048")
    XCTAssertEqual([UInt8](data), Array(bytes[100 ... 199]))
  }

  /// LRU is bumped when a file is actually read, once per token — `touch`
  /// writes the index on the same queue the lookup takes, and a player issues
  /// several range requests per track.
  func testTheCacheIsTouchedOncePerTokenNotPerRequest() async throws {
    let file = try writeFile((0 ..< 512).map { UInt8($0 % 251) })
    let touches = TouchCounter()
    let server = await makeServer(cachedFile: { _ in file }, onFirstDiskHit: { _ in touches.bump() })
    defer { server.stop() }
    let url = try XCTUnwrap(server.localURL(for: unreachableOrigin))

    _ = try await get(url, range: "bytes=0-1")
    _ = try await get(url, range: "bytes=0-99")
    _ = try await get(url)

    XCTAssertEqual(touches.value, 1, "three requests for one token bumped LRU more than once")
  }

  // MARK: - Liveness and restart

  func testARunningServerIsAnswering() async {
    let server = await makeServer()
    defer { server.stop() }

    XCTAssertTrue(server.isAnswering())
  }

  func testAStoppedServerIsNotAnswering() async {
    let server = await makeServer()
    server.stop()

    XCTAssertFalse(server.isAnswering())
  }

  /// A restart keeps every token, so an item built before it resolves the same
  /// token afterwards and is still served from the cache.
  func testARestartedServerKeepsItsTokensAndServesFromThem() async throws {
    let bytes = (0 ..< 2048).map { UInt8($0 % 251) }
    let file = try writeFile(bytes)
    let server = await makeServer(cachedFile: { _ in file })
    defer { server.stop() }
    let before = try XCTUnwrap(server.localURL(for: unreachableOrigin))

    let port = server.restart()

    XCTAssertNotEqual(port, 0)
    let after = try XCTUnwrap(server.localURL(for: unreachableOrigin))
    XCTAssertEqual(after.path, before.path)
    let (response, data) = try await get(after)
    XCTAssertEqual(response.statusCode, 200)
    XCTAssertEqual([UInt8](data), bytes)
  }

  /// A restarted server answers, and still reports the port it returned once the
  /// replaced listener has finished cancelling.
  func testARestartedServerAnswersAndKeepsItsPort() async throws {
    let server = await makeServer()
    defer { server.stop() }

    let port = server.restart()
    try await Task.sleep(nanoseconds: 100_000_000)

    XCTAssertNotEqual(port, 0)
    XCTAssertEqual(server.boundPortValue(), port)
    XCTAssertTrue(server.isAnswering())
  }

  func testStoppingDropsEveryToken() async throws {
    let server = await makeServer()
    XCTAssertNotNil(server.localURL(for: unreachableOrigin), "precondition: a bound server routes")
    server.stop()

    XCTAssertNil(
      server.localURL(for: unreachableOrigin),
      "a stopped server must not hand out URLs pointing at a dead port")
    XCTAssertEqual(server.boundPortValue(), 0)
  }
}

/// Counts callbacks from connection queues.
private final class TouchCounter: @unchecked Sendable {
  private let lock = NSLock()
  private var count = 0
  func bump() { lock.lock(); count += 1; lock.unlock() }
  var value: Int { lock.lock(); defer { lock.unlock() }; return count }
}
