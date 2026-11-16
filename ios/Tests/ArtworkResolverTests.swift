import UIKit
import XCTest
@testable import QueuePlayer

/// `ArtworkResolver` always returns a non-nil UIImage, drops stale
/// responses via a monotonic sequence counter, honours injected HTTP
/// headers + user-agent, and decodes file:// images off-main while
/// firing the completion on main.
///
/// HTTP paths run through a `URLProtocol` stub registered on a
/// test-injected ephemeral `URLSession`. The same URL stub captures
/// the request's HTTP headers so the precedence + injection contracts
/// can be asserted.
final class ArtworkResolverTests: XCTestCase {

  override func setUp() {
    super.setUp()
    ArtworkStubURLProtocol.handlers.removeAll()
    ArtworkStubURLProtocol.lastRequestHeaders = nil
  }

  override func tearDown() {
    ArtworkStubURLProtocol.handlers.removeAll()
    ArtworkStubURLProtocol.lastRequestHeaders = nil
    super.tearDown()
  }

  // MARK: - Synchronous fallbacks (no network)

  func testResolveNilReturnsPlaceholder() {
    let resolver = ArtworkResolver()
    let exp = expectation(description: "completion")
    resolver.resolve(url: nil) { image in
      XCTAssertTrue(image === PlaceholderArtwork.image)
      exp.fulfill()
    }
    wait(for: [exp], timeout: 1)
  }

  func testResolveEmptyReturnsPlaceholder() {
    let resolver = ArtworkResolver()
    let exp = expectation(description: "completion")
    resolver.resolve(url: "") { image in
      XCTAssertTrue(image === PlaceholderArtwork.image)
      exp.fulfill()
    }
    wait(for: [exp], timeout: 1)
  }

  func testResolveInvalidUrlReturnsPlaceholder() {
    let resolver = ArtworkResolver()
    let exp = expectation(description: "completion")
    resolver.resolve(url: "not a url") { image in
      XCTAssertTrue(image === PlaceholderArtwork.image)
      exp.fulfill()
    }
    wait(for: [exp], timeout: 1)
  }

  func testResolveDataSchemeReturnsPlaceholder() {
    let resolver = ArtworkResolver()
    let exp = expectation(description: "completion")
    resolver.resolve(url: "data:image/png;base64,iVBOR") { image in
      XCTAssertTrue(image === PlaceholderArtwork.image)
      exp.fulfill()
    }
    wait(for: [exp], timeout: 1)
  }

  func testResolveSchemelessUrlReturnsPlaceholder() {
    let resolver = ArtworkResolver()
    let exp = expectation(description: "completion")
    resolver.resolve(url: "//host/path/art.jpg") { image in
      XCTAssertTrue(image === PlaceholderArtwork.image)
      exp.fulfill()
    }
    wait(for: [exp], timeout: 1)
  }

  // MARK: - file:// path

  func testResolveFileUrlMissingReturnsPlaceholderOnMain() {
    let resolver = ArtworkResolver()
    let exp = expectation(description: "completion")
    var completionThreadIsMain = false
    resolver.resolve(url: "file:///nonexistent/path/to/missing.jpg") { image in
      completionThreadIsMain = Thread.isMainThread
      XCTAssertTrue(image === PlaceholderArtwork.image)
      exp.fulfill()
    }
    wait(for: [exp], timeout: 2)
    XCTAssertTrue(completionThreadIsMain,
                  "completion must fire on the main thread")
  }

  func testResolveFileUrlSuccessReturnsDecodedImageOnMain() throws {
    let tmp = FileManager.default.temporaryDirectory
      .appendingPathComponent("rnqp-artwork-test-\(UUID().uuidString).jpg")
    try makeJpegBytes().write(to: tmp)
    defer { try? FileManager.default.removeItem(at: tmp) }

    let resolver = ArtworkResolver()
    let exp = expectation(description: "completion")
    var completionThreadIsMain = false
    var resolved: UIImage?
    resolver.resolve(url: tmp.absoluteString) { image in
      completionThreadIsMain = Thread.isMainThread
      resolved = image
      exp.fulfill()
    }
    wait(for: [exp], timeout: 2)
    XCTAssertTrue(completionThreadIsMain)
    XCTAssertFalse(resolved === PlaceholderArtwork.image,
                   "valid file URL must resolve to a non-placeholder image")
    XCTAssertNotNil(resolved)
  }

  // MARK: - HTTP path (URLProtocol-stubbed)

  func testResolveHttpSuccessReturnsResolvedImage() {
    let url = "https://test.invalid/art.jpg"
    ArtworkStubURLProtocol.handlers[url] = .success(makeJpegBytes())

    let session = makeStubSession()
    let resolver = ArtworkResolver(session: session)
    let exp = expectation(description: "completion")
    resolver.resolve(url: url) { image in
      XCTAssertFalse(image === PlaceholderArtwork.image)
      exp.fulfill()
    }
    wait(for: [exp], timeout: 5)
  }

  func testResolveHttpFailureReturnsPlaceholder() {
    let url = "https://test.invalid/missing.jpg"
    ArtworkStubURLProtocol.handlers[url] = .failure(NSError(domain: "test", code: 404))

    let session = makeStubSession()
    let resolver = ArtworkResolver(session: session)
    let exp = expectation(description: "completion")
    resolver.resolve(url: url) { image in
      XCTAssertTrue(image === PlaceholderArtwork.image)
      exp.fulfill()
    }
    // URLSession's internal scheduling of `dataTask` completion after
    // `urlProtocol(_:didFailWithError:)` is opaque + can spike under
    // CI runner load (observed: 1/20 runs exceeded 5 s on a GitHub
    // Actions macOS runner). 15 s gives generous headroom for that
    // tail latency; the success-path tests stay tighter because their
    // path doesn't go through URLSession's failure-handling machinery.
    wait(for: [exp], timeout: 15)
  }

  func testResolveHttpDecodeFailureReturnsPlaceholder() {
    let url = "https://test.invalid/garbage"
    ArtworkStubURLProtocol.handlers[url] = .success(Data([0x00, 0x01, 0x02]))

    let session = makeStubSession()
    let resolver = ArtworkResolver(session: session)
    let exp = expectation(description: "completion")
    resolver.resolve(url: url) { image in
      XCTAssertTrue(image === PlaceholderArtwork.image)
      exp.fulfill()
    }
    wait(for: [exp], timeout: 5)
  }

  // MARK: - Header injection

  func testResolveHttpInjectsConfigHeaders() {
    let url = "https://test.invalid/headers.jpg"
    ArtworkStubURLProtocol.handlers[url] = .success(makeJpegBytes())

    let session = makeStubSession()
    let resolver = ArtworkResolver(session: session)
    resolver.configHeaders = ["Authorization": "Bearer abc", "X-Tenant": "acme"]
    let exp = expectation(description: "completion")
    resolver.resolve(url: url) { _ in exp.fulfill() }
    wait(for: [exp], timeout: 5)

    let headers = ArtworkStubURLProtocol.lastRequestHeaders ?? [:]
    XCTAssertEqual(headers["Authorization"], "Bearer abc")
    XCTAssertEqual(headers["X-Tenant"], "acme")
  }

  func testResolveHttpInjectsConfigUserAgent() {
    let url = "https://test.invalid/ua.jpg"
    ArtworkStubURLProtocol.handlers[url] = .success(makeJpegBytes())

    let session = makeStubSession()
    let resolver = ArtworkResolver(session: session)
    resolver.configUserAgent = "RNQP/1.2.3"
    let exp = expectation(description: "completion")
    resolver.resolve(url: url) { _ in exp.fulfill() }
    wait(for: [exp], timeout: 5)

    XCTAssertEqual(ArtworkStubURLProtocol.lastRequestHeaders?["User-Agent"],
                   "RNQP/1.2.3")
  }

  func testConsumerUserAgentInHeadersOverridesConfigUserAgent() {
    let url = "https://test.invalid/ua-precedence.jpg"
    ArtworkStubURLProtocol.handlers[url] = .success(makeJpegBytes())

    let session = makeStubSession()
    let resolver = ArtworkResolver(session: session)
    resolver.configUserAgent = "RNQP/1.2.3"
    resolver.configHeaders = ["User-Agent": "Consumer-UA/9.9.9"]
    let exp = expectation(description: "completion")
    resolver.resolve(url: url) { _ in exp.fulfill() }
    wait(for: [exp], timeout: 5)

    XCTAssertEqual(ArtworkStubURLProtocol.lastRequestHeaders?["User-Agent"],
                   "Consumer-UA/9.9.9")
  }

  // MARK: - Stale-load detection

  func testStaleLoadDroppedWhenSecondResolveSupersedes() {
    let urlA = "https://test.invalid/a.jpg"
    let urlB = "https://test.invalid/b.jpg"
    // A's response is delayed past B's resolve; the seq-mismatch
    // branch must drop A's late completion.
    ArtworkStubURLProtocol.handlers[urlA] = .delayed(makeJpegBytes(), after: 0.6)
    ArtworkStubURLProtocol.handlers[urlB] = .success(makeJpegBytes())

    let session = makeStubSession()
    let resolver = ArtworkResolver(session: session)

    let expA = expectation(description: "A completion (must NOT fire)")
    expA.isInverted = true
    resolver.resolve(url: urlA) { _ in expA.fulfill() }

    let expB = expectation(description: "B completion fires once with image")
    resolver.resolve(url: urlB) { image in
      XCTAssertFalse(image === PlaceholderArtwork.image)
      expB.fulfill()
    }
    wait(for: [expA, expB], timeout: 3)
  }

  func testCancelInFlightDropsLateCompletion() {
    let url = "https://test.invalid/slow.jpg"
    ArtworkStubURLProtocol.handlers[url] = .delayed(makeJpegBytes(), after: 0.6)

    let session = makeStubSession()
    let resolver = ArtworkResolver(session: session)
    let exp = expectation(description: "must NOT fire")
    exp.isInverted = true
    resolver.resolve(url: url) { _ in exp.fulfill() }
    resolver.cancelInFlight()
    wait(for: [exp], timeout: 1.5)
  }

  // MARK: - Cache hit

  func testCacheHitOnSecondResolveOfSameUrl() {
    let url = "https://test.invalid/cached.jpg"
    ArtworkStubURLProtocol.handlers[url] = .success(makeJpegBytes())

    let session = makeStubSession()
    let resolver = ArtworkResolver(session: session)

    let firstExp = expectation(description: "first resolve hits network")
    var firstImage: UIImage?
    resolver.resolve(url: url) { image in
      firstImage = image
      firstExp.fulfill()
    }
    wait(for: [firstExp], timeout: 5)

    ArtworkStubURLProtocol.handlers[url] = .failure(NSError(domain: "test", code: 500))

    let secondExp = expectation(description: "second resolve uses cache")
    resolver.resolve(url: url) { image in
      XCTAssertTrue(image === firstImage,
                    "cache hit must return the same UIImage instance")
      secondExp.fulfill()
    }
    wait(for: [secondExp], timeout: 1)
  }

  func testResetIsTerminalAndIdempotent() {
    let url = "https://test.invalid/reset.jpg"
    ArtworkStubURLProtocol.handlers[url] = .success(makeJpegBytes())

    let session = makeStubSession()
    let resolver = ArtworkResolver(session: session)

    let firstExp = expectation(description: "warm the cache")
    resolver.resolve(url: url) { _ in firstExp.fulfill() }
    wait(for: [firstExp], timeout: 5)

    // `reset()` is documented as terminal (the owner discards the
    // instance afterwards); calling it twice must not crash, leak,
    // or surface a partial-state error.
    resolver.reset()
    resolver.reset()
  }

  // MARK: - Helpers

  private func makeStubSession() -> URLSession {
    let config = URLSessionConfiguration.ephemeral
    config.protocolClasses = [ArtworkStubURLProtocol.self]
    return URLSession(configuration: config)
  }

  /// Returns a JPEG-encoded 1x1 image payload. UIImage(data:) accepts.
  private func makeJpegBytes() -> Data {
    let renderer = UIGraphicsImageRenderer(size: CGSize(width: 1, height: 1))
    let image = renderer.image { ctx in
      UIColor.red.setFill()
      ctx.fill(CGRect(x: 0, y: 0, width: 1, height: 1))
    }
    return image.jpegData(compressionQuality: 1.0) ?? Data()
  }
}

// MARK: - ArtworkLoader

/// `ArtworkLoader` is the multi-request artwork fetcher shared across
/// the now-playing single-slot resolver + the CarPlay browse builder's
/// per-row image loads. Coverage:
///  - nil/empty/non-http URIs return nil immediately on main.
///  - `file://` decodes off-main, completes on main with the bitmap.
///  - http(s) URIs route through the injected URLSession + propagate
///    `configHeaders` / `configUserAgent` on every request.
///  - Successful fetches populate the NSCache; subsequent fetches of
///    the same URL hit the cache without re-issuing a request.
///  - `clearCache` evicts so the next fetch hits the network again.
final class ArtworkLoaderTests: XCTestCase {

  override func setUp() {
    super.setUp()
    ArtworkStubURLProtocol.handlers.removeAll()
    ArtworkStubURLProtocol.lastRequestHeaders = nil
  }

  override func tearDown() {
    ArtworkStubURLProtocol.handlers.removeAll()
    ArtworkStubURLProtocol.lastRequestHeaders = nil
    super.tearDown()
  }

  func testLoadNilReturnsNil() {
    let loader = ArtworkLoader()
    let exp = expectation(description: "completion")
    loader.load(uri: nil) { image in
      XCTAssertNil(image)
      exp.fulfill()
    }
    wait(for: [exp], timeout: 1)
  }

  func testLoadEmptyReturnsNil() {
    let loader = ArtworkLoader()
    let exp = expectation(description: "completion")
    loader.load(uri: "") { image in
      XCTAssertNil(image)
      exp.fulfill()
    }
    wait(for: [exp], timeout: 1)
  }

  func testLoadDataSchemeReturnsNil() {
    let loader = ArtworkLoader()
    let exp = expectation(description: "completion")
    loader.load(uri: "data:image/png;base64,iVBOR") { image in
      XCTAssertNil(image)
      exp.fulfill()
    }
    wait(for: [exp], timeout: 1)
  }

  func testLoadHttpSuccessDeliversImage() {
    let url = "https://test.invalid/cover.jpg"
    ArtworkStubURLProtocol.handlers[url] = .success(makeJpegBytes())

    let session = makeStubSession()
    let loader = ArtworkLoader(session: session)
    let exp = expectation(description: "completion")
    loader.load(uri: url) { image in
      XCTAssertNotNil(image)
      exp.fulfill()
    }
    wait(for: [exp], timeout: 5)
  }

  func testLoadHttpFailureDeliversNil() {
    let url = "https://test.invalid/missing.jpg"
    ArtworkStubURLProtocol.handlers[url] = .failure(NSError(domain: "test", code: 404))

    let session = makeStubSession()
    let loader = ArtworkLoader(session: session)
    let exp = expectation(description: "completion")
    loader.load(uri: url) { image in
      XCTAssertNil(image)
      exp.fulfill()
    }
    wait(for: [exp], timeout: 5)
  }

  func testLoadCachesSuccessfulResponse() {
    let url = "https://test.invalid/cached.jpg"
    ArtworkStubURLProtocol.handlers[url] = .success(makeJpegBytes())

    let session = makeStubSession()
    let loader = ArtworkLoader(session: session)

    let firstExp = expectation(description: "first load hits network")
    var firstImage: UIImage?
    loader.load(uri: url) { image in
      firstImage = image
      firstExp.fulfill()
    }
    wait(for: [firstExp], timeout: 5)

    ArtworkStubURLProtocol.handlers[url] = .failure(NSError(domain: "test", code: 500))

    let secondExp = expectation(description: "second load uses cache")
    loader.load(uri: url) { image in
      XCTAssertTrue(image === firstImage,
                    "cache hit must return the same UIImage instance")
      secondExp.fulfill()
    }
    wait(for: [secondExp], timeout: 1)
  }

  func testLoadPropagatesUserAgentAndHeaders() {
    let url = "https://test.invalid/headers.jpg"
    ArtworkStubURLProtocol.handlers[url] = .success(makeJpegBytes())

    let session = makeStubSession()
    let loader = ArtworkLoader(session: session)
    loader.configUserAgent = "RNQP/1.2.3"
    loader.configHeaders = ["X-Auth": "abc"]
    let exp = expectation(description: "completion")
    loader.load(uri: url) { _ in exp.fulfill() }
    wait(for: [exp], timeout: 5)

    let headers = ArtworkStubURLProtocol.lastRequestHeaders ?? [:]
    XCTAssertEqual(headers["User-Agent"], "RNQP/1.2.3")
    XCTAssertEqual(headers["X-Auth"], "abc")
  }

  func testClearCacheEvictsSubsequentLoadHitsNetwork() {
    let url = "https://test.invalid/evict.jpg"
    ArtworkStubURLProtocol.handlers[url] = .success(makeJpegBytes())

    let session = makeStubSession()
    let loader = ArtworkLoader(session: session)
    let firstExp = expectation(description: "warm cache")
    loader.load(uri: url) { _ in firstExp.fulfill() }
    wait(for: [firstExp], timeout: 5)

    loader.clearCache()
    ArtworkStubURLProtocol.handlers[url] = .failure(NSError(domain: "test", code: 500))

    let secondExp = expectation(description: "post-clear hits network + fails")
    loader.load(uri: url) { image in
      XCTAssertNil(image)
      secondExp.fulfill()
    }
    wait(for: [secondExp], timeout: 5)
  }

  // MARK: - Helpers (mirror the resolver-side helpers)

  private func makeStubSession() -> URLSession {
    let config = URLSessionConfiguration.ephemeral
    config.protocolClasses = [ArtworkStubURLProtocol.self]
    return URLSession(configuration: config)
  }

  private func makeJpegBytes() -> Data {
    let renderer = UIGraphicsImageRenderer(size: CGSize(width: 1, height: 1))
    let image = renderer.image { ctx in
      UIColor.blue.setFill()
      ctx.fill(CGRect(x: 0, y: 0, width: 1, height: 1))
    }
    return image.jpegData(compressionQuality: 1.0) ?? Data()
  }
}

// MARK: - URLProtocol stub

/// Drives URLSession through fixtures keyed by URL string + records
/// the request's HTTP headers for assertion. Each fixture is one of:
/// `.success(Data)`, `.failure(Error)`, or `.delayed(Data, after:)`
/// (fires the success after `after` seconds, surviving `cancel()` so
/// stale-load tests can race a real late completion against the
/// resolver's seq-mismatch drop branch).
fileprivate final class ArtworkStubURLProtocol: URLProtocol {

  enum Outcome {
    case success(Data)
    case failure(Error)
    case delayed(Data, after: TimeInterval)
  }

  fileprivate static var handlers: [String: Outcome] = [:]
  fileprivate static var lastRequestHeaders: [String: String]?

  override class func canInit(with request: URLRequest) -> Bool { true }
  override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

  override func startLoading() {
    ArtworkStubURLProtocol.lastRequestHeaders = request.allHTTPHeaderFields
    let key = request.url?.absoluteString ?? ""
    guard let outcome = ArtworkStubURLProtocol.handlers[key] else {
      client?.urlProtocol(
        self,
        didFailWithError: NSError(domain: "test-no-stub", code: 0,
                                  userInfo: [NSLocalizedDescriptionKey: "no stub for \(key)"]))
      return
    }
    switch outcome {
    case .success(let data):
      sendSuccess(data)
    case .failure(let error):
      client?.urlProtocol(self, didFailWithError: error)
    case .delayed(let data, let after):
      DispatchQueue.global().asyncAfter(deadline: .now() + after) { [weak self] in
        guard let self = self else { return }
        self.sendSuccess(data)
      }
    }
  }

  override func stopLoading() {}

  private func sendSuccess(_ data: Data) {
    let response = HTTPURLResponse(
      url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
    client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
    client?.urlProtocol(self, didLoad: data)
    client?.urlProtocolDidFinishLoading(self)
  }
}
