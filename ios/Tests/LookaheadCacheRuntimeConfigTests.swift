import XCTest
@testable import QueuePlayer
@_spi(QueuePlayerTests) import QueuePlayer

/// Runtime cache-config + status-surface tests.
///
/// Exercises the cache + prefetcher primitives that the runtime
/// config builds on (`setMaxSizeBytes`, `currentSizeBytes`,
/// `isFullyCached`, prefetcher's `currentlyDownloadingUrl` +
/// `defaultLookaheadCount`).
final class LookaheadCacheRuntimeConfigTests: XCTestCase {

  private var tempDir: URL!
  private var session: URLSession!

  override func setUp() {
    super.setUp()
    tempDir = FileManager.default.temporaryDirectory
      .appendingPathComponent("RuntimeConfig-\(UUID().uuidString)", isDirectory: true)
    let config = URLSessionConfiguration.ephemeral
    config.protocolClasses = [StubURLProtocol.self] + (config.protocolClasses ?? [])
    session = URLSession(configuration: config)
    StubURLProtocol.reset()
  }

  override func tearDown() {
    session?.invalidateAndCancel()
    try? FileManager.default.removeItem(at: tempDir)
    StubURLProtocol.reset()
    super.tearDown()
  }

  // MARK: - currentSizeBytes / isFullyCached

  func testCurrentSizeBytesIsZeroOnEmptyCache() {
    let cache = LookaheadCache(
      directory: tempDir, maxSizeBytes: 1024 * 1024, urlSession: session
    )
    XCTAssertEqual(cache.currentSizeBytes(), 0)
  }

  func testCurrentSizeBytesSumsOverEntries() async throws {
    let cache = LookaheadCache(
      directory: tempDir, maxSizeBytes: 10 * 1024 * 1024, urlSession: session
    )
    let payload = Data(repeating: 0xAB, count: 1024)
    for id in ["t1", "t2", "t3"] {
      let url = URL(string: "https://example.com/\(id).m4a")!
      StubURLProtocol.stub(url: url, data: payload, statusCode: 200)
      try await cache.download(
        track: makeTrack(id: id, url: url.absoluteString), headers: nil
      )
    }
    XCTAssertEqual(cache.currentSizeBytes(), 3 * 1024,
      "currentSizeBytes must sum across all index entries")
  }

  func testIsFullyCachedReturnsFalseForUnknownTrack() {
    let cache = LookaheadCache(
      directory: tempDir, maxSizeBytes: 1024, urlSession: session
    )
    XCTAssertFalse(cache.isFullyCached(url: "missing"))
  }

  func testIsFullyCachedReturnsTrueAfterDownload() async throws {
    let cache = LookaheadCache(
      directory: tempDir, maxSizeBytes: 1024 * 1024, urlSession: session
    )
    let url = URL(string: "https://example.com/cached.m4a")!
    StubURLProtocol.stub(url: url, data: Data("payload".utf8), statusCode: 200)
    try await cache.download(
      track: makeTrack(id: "cached", url: url.absoluteString), headers: nil
    )
    XCTAssertTrue(cache.isFullyCached(url: "https://example.com/cached.m4a"))
  }

  // Cache key is track.url.
  func testCacheKeyIsTrackUrl() async throws {
    let cache = LookaheadCache(
      directory: tempDir, maxSizeBytes: 1024 * 1024, urlSession: session
    )
    let url = URL(string: "https://example.com/keyshape.m4a")!
    StubURLProtocol.stub(url: url, data: Data("payload".utf8), statusCode: 200)
    let track = makeTrack(id: "consumer-supplied-42", url: url.absoluteString)
    XCTAssertNotEqual(track.id, track.url)
    try await cache.download(track: track, headers: nil)

    XCTAssertFalse(cache.isFullyCached(url: track.id!))
    XCTAssertTrue(cache.isFullyCached(url: track.url))
  }

  /// A disabled cache serves nothing, whatever is on disk; the file is still
  /// there for the next enable.
  func testADisabledCacheAnswersNoCachedURL() async throws {
    let cache = LookaheadCache(
      directory: tempDir, maxSizeBytes: 1024 * 1024, urlSession: session
    )
    let url = URL(string: "https://example.com/held.m4a")!
    StubURLProtocol.stub(url: url, data: Data("payload".utf8), statusCode: 200)
    try await cache.download(
      track: makeTrack(id: "held", url: url.absoluteString), headers: nil
    )
    let fileURL = try XCTUnwrap(cache.cachedURL(forUrl: url.absoluteString))

    cache.disable()
    XCTAssertNil(cache.cachedURL(forUrl: url.absoluteString), "disabled means every request goes to the origin")
    XCTAssertTrue(FileManager.default.fileExists(atPath: fileURL.path), "the file waits for the next enable")

    cache.enable()
    XCTAssertEqual(cache.cachedURL(forUrl: url.absoluteString), fileURL)
  }

  func testIsFullyCachedReturnsFalseWhenFileMissing() async throws {
    let cache = LookaheadCache(
      directory: tempDir, maxSizeBytes: 1024 * 1024, urlSession: session
    )
    let url = URL(string: "https://example.com/orphaned.m4a")!
    StubURLProtocol.stub(url: url, data: Data("payload".utf8), statusCode: 200)
    try await cache.download(
      track: makeTrack(id: "orphaned", url: url.absoluteString), headers: nil
    )
    let fileURL = cache.cachedURL(forUrl: "https://example.com/orphaned.m4a")!
    try FileManager.default.removeItem(at: fileURL)

    XCTAssertFalse(
      cache.isFullyCached(url: "https://example.com/orphaned.m4a"),
      "missing on-disk file means not fully cached"
    )
  }

  // MARK: - setMaxSizeBytes

  func testSetMaxSizeBytesGrowsWithoutEviction() async throws {
    let cache = LookaheadCache(
      directory: tempDir, maxSizeBytes: 10_000, urlSession: session
    )
    let payload = Data(repeating: 0xAB, count: 3000)
    for id in ["a", "b", "c"] {
      let url = URL(string: "https://example.com/\(id).m4a")!
      StubURLProtocol.stub(url: url, data: payload, statusCode: 200)
      try await cache.download(
        track: makeTrack(id: id, url: url.absoluteString), headers: nil
      )
    }
    XCTAssertEqual(cache.indexSnapshot.count, 3)

    cache.setMaxSizeBytes(20_000)

    XCTAssertEqual(cache.indexSnapshot.count, 3,
      "growing the budget must not evict")
    XCTAssertEqual(cache.getMaxSizeBytes(), 20_000)
  }

  func testSetMaxSizeBytesShrinksWithImmediateEviction() async throws {
    let cache = LookaheadCache(
      directory: tempDir, maxSizeBytes: 10_000, urlSession: session
    )
    let payload = Data(repeating: 0xAB, count: 3000)
    for id in ["oldest", "middle", "newest"] {
      let url = URL(string: "https://example.com/\(id).m4a")!
      StubURLProtocol.stub(url: url, data: payload, statusCode: 200)
      try await cache.download(
        track: makeTrack(id: id, url: url.absoluteString), headers: nil
      )
    }
    XCTAssertEqual(cache.indexSnapshot.count, 3)

    cache.setMaxSizeBytes(4_000)

    let remaining = Set(cache.indexSnapshot.map { $0.trackId })
    XCTAssertEqual(remaining.count, 1,
      "shrink to 4000B with 3×3000B entries must evict oldest two")
    XCTAssertEqual(remaining.first, "https://example.com/newest.m4a",
      "newest entry survives — LRU evicts oldest first")
  }

  // MARK: - Prefetcher headers refresh

  func testPrefetcherHeadersUpdateOnDirectAssign() {
    // Re-configuring with new auth propagates to subsequent prefetch
    // downloads via the prefetcher's mutable `configHeaders` /
    // `configUserAgent` properties.
    let cache = LookaheadCache(
      directory: tempDir, maxSizeBytes: 1024 * 1024, urlSession: session
    )
    let prefetcher = LookaheadCachePrefetcher(
      cache: cache,
      confinedTo: .main,
      configHeaders: ["X-Auth": "token-1"],
      configUserAgent: "rnqp/1"
    )
    XCTAssertEqual(prefetcher.configHeaders?["X-Auth"], "token-1")
    XCTAssertEqual(prefetcher.configUserAgent, "rnqp/1")

    prefetcher.configHeaders = ["X-Auth": "token-2"]
    prefetcher.configUserAgent = "rnqp/2"

    XCTAssertEqual(prefetcher.configHeaders?["X-Auth"], "token-2")
    XCTAssertEqual(prefetcher.configUserAgent, "rnqp/2")
  }

  // MARK: - End-to-end

  func testDownloadThenShrinkProtectsCurrentlyPlayingTrackEndToEnd() async throws {
    // Drive 4 downloads → cache holds 4 entries → call setMaxSizeBytes
    // with a budget that forces eviction + protect the "currently
    // playing" trackId.
    let cache = LookaheadCache(
      directory: tempDir, maxSizeBytes: 12_000, urlSession: session
    )
    let payload = Data(repeating: 0xCD, count: 3000)
    let ids = ["playing", "next-1", "next-2", "next-3"]
    for id in ids {
      let url = URL(string: "https://example.com/\(id).m4a")!
      StubURLProtocol.stub(url: url, data: payload, statusCode: 200)
      try await cache.download(
        track: makeTrack(id: id, url: url.absoluteString), headers: nil
      )
    }
    XCTAssertEqual(cache.indexSnapshot.count, 4)
    XCTAssertEqual(cache.currentSizeBytes(), 12_000)

    // Shrink to 4_000 (one entry's worth) with `playing` protected.
    let playingUrl = "https://example.com/playing.m4a"
    cache.setMaxSizeBytes(4_000, protectedUrls: [playingUrl])

    XCTAssertTrue(
      cache.isFullyCached(url: playingUrl),
      "currently-playing track must survive the shrink eviction"
    )
    XCTAssertLessThanOrEqual(
      cache.currentSizeBytes(), 4_000,
      "shrink must drive total size under the new budget"
    )
  }

  func testDownloadThenClearWipesEverything() async throws {
    // End-to-end: download 3 tracks → assert all cached → clear() →
    // assert empty. Mirrors the JS-facing clearLookaheadCache contract.
    let cache = LookaheadCache(
      directory: tempDir, maxSizeBytes: 1024 * 1024, urlSession: session
    )
    for id in ["a", "b", "c"] {
      let url = URL(string: "https://example.com/\(id).m4a")!
      StubURLProtocol.stub(url: url, data: Data("x".utf8), statusCode: 200)
      try await cache.download(
        track: makeTrack(id: id, url: url.absoluteString), headers: nil
      )
    }
    XCTAssertEqual(cache.indexSnapshot.count, 3)

    cache.clear()

    XCTAssertEqual(cache.indexSnapshot.count, 0)
    XCTAssertEqual(cache.currentSizeBytes(), 0)
    XCTAssertNil(cache.cachedURL(forUrl: "a"))
    XCTAssertNil(cache.cachedURL(forUrl: "b"))
    XCTAssertNil(cache.cachedURL(forUrl: "c"))
  }

  // MARK: - Helpers

  private func makeTrack(id: String, url: String) -> TrackItem {
    return TrackItem(
      id: id, url: url, title: nil, artist: nil, album: nil,
      duration: nil, artworkUrl: nil, extras: nil,
      replayGainTrackGain: nil, replayGainTrackPeak: nil,
      replayGainAlbumGain: nil, replayGainAlbumPeak: nil, isVideo: nil
    )
  }

  // MARK: - The live protected set guards every eviction, not just a shrink

  func testCommitEvictionSparesTheProtectedPlayingTrack() async throws {
    // Budget fits two entries. The third commit forces an eviction, and the
    // oldest entry is the one playing — the LRU victim without protection.
    let cache = LookaheadCache(
      directory: tempDir, maxSizeBytes: 8_000, urlSession: session
    )
    let playingUrl = "https://example.com/playing.m4a"
    cache.setProtectedUrls([playingUrl])

    for id in ["playing", "b", "c"] {
      let url = URL(string: "https://example.com/\(id).m4a")!
      StubURLProtocol.stub(url: url, data: Data(repeating: 0x41, count: 4_000), statusCode: 200)
      try await cache.download(
        track: makeTrack(id: id, url: url.absoluteString), headers: nil
      )
    }

    XCTAssertTrue(
      cache.isFullyCached(url: playingUrl),
      "a prefetch commit must not evict the track that is playing"
    )
  }

  func testCommitEvictionStillTakesUnprotectedEntries() async throws {
    let cache = LookaheadCache(
      directory: tempDir, maxSizeBytes: 8_000, urlSession: session
    )
    cache.setProtectedUrls([])

    for id in ["a", "b", "c"] {
      let url = URL(string: "https://example.com/\(id).m4a")!
      StubURLProtocol.stub(url: url, data: Data(repeating: 0x41, count: 4_000), statusCode: 200)
      try await cache.download(
        track: makeTrack(id: id, url: url.absoluteString), headers: nil
      )
    }

    XCTAssertLessThanOrEqual(
      cache.currentSizeBytes(), 8_000,
      "with nothing protected the budget must still be enforced"
    )
    XCTAssertFalse(
      cache.isFullyCached(url: "https://example.com/a.m4a"),
      "the oldest unprotected entry is still the eviction victim"
    )
  }

  // MARK: - A download shorter than declared is never committed

  private func downloadStubbed(
    _ cache: LookaheadCache, id: String, body: Data, headers: [String: String]?
  ) async throws {
    let url = URL(string: "https://example.com/\(id).m4a")!
    StubURLProtocol.stub(url: url, data: body, statusCode: 200, headerFields: headers)
    try await cache.download(track: makeTrack(id: id, url: url.absoluteString), headers: nil)
  }

  func testTruncatedBodyIsRejected() async throws {
    let cache = LookaheadCache(
      directory: tempDir, maxSizeBytes: 1024 * 1024, urlSession: session
    )
    do {
      try await downloadStubbed(
        cache, id: "short", body: Data(repeating: 0x41, count: 400),
        headers: ["Content-Length": "1000"])
      XCTFail("a body shorter than the declared length must not be committed")
    } catch LookaheadCacheError.truncatedBody(let expected, let received) {
      XCTAssertEqual(expected, 1000)
      XCTAssertEqual(received, 400)
    }
    XCTAssertFalse(
      cache.isFullyCached(url: "https://example.com/short.m4a"),
      "a rejected download must leave no cache entry to poison later plays")
  }

  func testExactLengthBodyIsAccepted() async throws {
    let cache = LookaheadCache(
      directory: tempDir, maxSizeBytes: 1024 * 1024, urlSession: session
    )
    try await downloadStubbed(
      cache, id: "exact", body: Data(repeating: 0x41, count: 400),
      headers: ["Content-Length": "400"])
    XCTAssertTrue(cache.isFullyCached(url: "https://example.com/exact.m4a"))
  }

  func testChunkedBodyWithNoDeclaredLengthIsAccepted() async throws {
    let cache = LookaheadCache(
      directory: tempDir, maxSizeBytes: 1024 * 1024, urlSession: session
    )
    try await downloadStubbed(
      cache, id: "chunked", body: Data(repeating: 0x41, count: 400), headers: nil)
    XCTAssertTrue(
      cache.isFullyCached(url: "https://example.com/chunked.m4a"),
      "no declared length means nothing to compare against, not a rejection")
  }

  func testBodyLongerThanDeclaredIsAccepted() async throws {
    let cache = LookaheadCache(
      directory: tempDir, maxSizeBytes: 1024 * 1024, urlSession: session
    )
    // A decoded body can exceed the declared compressed length; only short
    // is a truncation.
    try await downloadStubbed(
      cache, id: "longer", body: Data(repeating: 0x41, count: 400),
      headers: ["Content-Length": "200"])
    XCTAssertTrue(cache.isFullyCached(url: "https://example.com/longer.m4a"))
  }

  // MARK: - buildCacheStatusSnapshot

  func testCacheStatusSnapshotReportsTheSuppliedInFlightUrl() {
    let player = TrackPlayer()
    let status = player.buildCacheStatusSnapshot(
      inFlightUrl: "https://example.com/inflight.m4a")
    XCTAssertEqual(status.currentlyCaching, ["https://example.com/inflight.m4a"])
  }

  func testCacheStatusSnapshotReportsNothingCachingWithoutAnInFlightUrl() {
    let player = TrackPlayer()
    XCTAssertEqual(player.buildCacheStatusSnapshot(inFlightUrl: nil).currentlyCaching, [])
  }

  func testCacheStatusSnapshotReportsTheDefaultCacheCeilingBeforeConfigure() {
    let player = TrackPlayer()
    let status = player.buildCacheStatusSnapshot(inFlightUrl: nil)
    XCTAssertTrue(status.enabled)
    XCTAssertEqual(status.currentSizeMb, 0)
    XCTAssertEqual(status.tracksFullyCached, 0)
    XCTAssertEqual(
      status.maxSizeMb,
      Double(LookaheadCache.DEFAULT_MAX_SIZE_BYTES) / Double(LookaheadCache.BYTES_PER_MB))
  }
}
