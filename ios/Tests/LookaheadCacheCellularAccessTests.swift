import XCTest
@testable import QueuePlayer

/// Prefetch requests carry `allowsCellularAccess`, and `setLookaheadCache`
/// changes it live — the cache instance and everything it holds on disk stay
/// as they are.
final class LookaheadCacheCellularAccessTests: XCTestCase {

  private var tempDir: URL!
  private var session: URLSession!

  override func setUp() {
    super.setUp()
    tempDir = FileManager.default.temporaryDirectory
      .appendingPathComponent("CellularAccess-\(UUID().uuidString)", isDirectory: true)
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

  // MARK: - The request carries the policy

  func testAPrefetchRequestIsAllowedOverCellularByDefault() async throws {
    let cache = makeCache()
    let url = URL(string: "https://example.com/default.m4a")!
    StubURLProtocol.stub(url: url, data: Data("payload".utf8), statusCode: 200)

    try await cache.download(track: makeTrack(id: "t", url: url.absoluteString), headers: nil)

    XCTAssertEqual(StubURLProtocol.lastRequest(for: url)?.allowsCellularAccess, true)
  }

  func testAPrefetchRequestRefusesCellularWhenTheCacheIsToldTo() async throws {
    let cache = makeCache(allowsCellularAccess: false)
    let url = URL(string: "https://example.com/denied.m4a")!
    StubURLProtocol.stub(url: url, data: Data("payload".utf8), statusCode: 200)

    try await cache.download(track: makeTrack(id: "t", url: url.absoluteString), headers: nil)

    XCTAssertEqual(StubURLProtocol.lastRequest(for: url)?.allowsCellularAccess, false)
  }

  func testTheNextRequestPicksUpAChangedPolicy() async throws {
    let cache = makeCache()
    let first = URL(string: "https://example.com/before.m4a")!
    let second = URL(string: "https://example.com/after.m4a")!
    StubURLProtocol.stub(url: first, data: Data("a".utf8), statusCode: 200)
    StubURLProtocol.stub(url: second, data: Data("b".utf8), statusCode: 200)
    try await cache.download(track: makeTrack(id: "a", url: first.absoluteString), headers: nil)

    cache.allowsCellularAccess = false
    try await cache.download(track: makeTrack(id: "b", url: second.absoluteString), headers: nil)

    XCTAssertEqual(StubURLProtocol.lastRequest(for: first)?.allowsCellularAccess, true)
    XCTAssertEqual(StubURLProtocol.lastRequest(for: second)?.allowsCellularAccess, false)
  }

  // MARK: - The runtime switch

  /// `applyLookaheadConfig` is player-queue confined — the prefetcher asserts
  /// it — so the tests reach it the way `setLookaheadCache` does.
  private func apply(
    _ player: TrackPlayer, enabled: Bool, count: Double, cellular: Bool?
  ) throws {
    var caught: Error?
    player.playerQueue.sync {
      do {
        try player.applyLookaheadConfig(
          LookaheadCacheConfig(
            enabled: enabled, lookaheadCount: count, allowsCellularAccess: cellular))
      } catch {
        caught = error
      }
    }
    if let caught { throw caught }
  }

  /// `enabled` starts true, so the cache only exists once the flag has been
  /// cycled — the construction path a consumer hits at `configure()`.
  private func playerWithCache() throws -> TrackPlayer {
    let player = TrackPlayer()
    try apply(player, enabled: false, count: 1, cellular: true)
    try apply(player, enabled: true, count: 1, cellular: true)
    return player
  }

  func testTurningCellularOffKeepsTheCacheAndOnlyChangesThePolicy() throws {
    let player = try playerWithCache()
    defer { _ = try? player.destroy() }
    let cache = try XCTUnwrap(player.lookaheadCache)
    XCTAssertTrue(cache.allowsCellularAccess)

    try apply(player, enabled: true, count: 1, cellular: false)

    XCTAssertTrue(cache === player.lookaheadCache, "nothing is rebuilt — the policy is live")
    XCTAssertFalse(cache.allowsCellularAccess)
  }

  func testTurningCellularBackOnRestoresIt() throws {
    let player = try playerWithCache()
    defer { _ = try? player.destroy() }
    try apply(player, enabled: true, count: 1, cellular: false)

    try apply(player, enabled: true, count: 1, cellular: true)

    XCTAssertTrue(try XCTUnwrap(player.lookaheadCache).allowsCellularAccess)
  }

  /// The cache is constructed lazily, so a policy set while it does not exist
  /// has to reach the instance the next construction produces.
  func testACacheBuiltAfterThePolicyWasSetHonoursIt() throws {
    let player = TrackPlayer()
    defer { _ = try? player.destroy() }
    try apply(player, enabled: false, count: 1, cellular: false)
    XCTAssertNil(player.lookaheadCache, "precondition: nothing built yet")

    try apply(player, enabled: true, count: 1, cellular: false)

    XCTAssertFalse(try XCTUnwrap(player.lookaheadCache).allowsCellularAccess)
  }

  /// An omitted `allowsCellularAccess` reads as permitted, so a consumer that
  /// never sets it is not silently cut off from prefetching.
  func testOmittingTheFlagLeavesCellularPermitted() throws {
    let player = TrackPlayer()
    defer { _ = try? player.destroy() }
    try apply(player, enabled: false, count: 1, cellular: nil)
    try apply(player, enabled: true, count: 1, cellular: nil)

    XCTAssertTrue(try XCTUnwrap(player.lookaheadCache).allowsCellularAccess)
  }

  /// `configure()` builds the cache itself rather than going through
  /// `applyLookaheadConfig`, so it has to read the stored policy too.
  func testConfigureBuildsTheCacheUnderTheStoredPolicy() async throws {
    let player = TrackPlayer()
    // Prefetch stays enabled — configure only builds the cache when it is —
    // and with no cache yet the policy lives in the stored config alone.
    try apply(player, enabled: true, count: 1, cellular: false)
    XCTAssertNil(player.lookaheadCache, "precondition: configure does the building")

    _ = try await player.configure(config: Self.playerConfig()).await()
    defer { _ = try? player.destroy() }

    let cache = try XCTUnwrap(player.lookaheadCache)
    XCTAssertFalse(cache.allowsCellularAccess)
  }

  private static func playerConfig() -> PlayerConfig {
    return PlayerConfig(
      httpHeaders: nil, userAgent: nil, placeholderArtworkUri: nil,
      autoRetries: nil, retryBackoffMs: nil, networkTimeoutMs: nil,
      audioContentType: nil, audioCategoryOptions: nil,
      voiceVocabularyDonationEnabled: nil,
      allowsExternalPlayback: nil,
      visualizationEnabled: nil, clampSeekToBuffered: nil,
      skipToPreviousBehavior: nil, lookaheadCacheMaxSizeMb: nil,
      lookaheadCacheEvictionPolicy: nil, progressUpdateIntervalMs: nil,
      backgroundProgressUpdateIntervalMs: nil, enableDiagnosticLogs: nil
    )
  }

  private func makeTrack(id: String, url: String) -> TrackItem {
    return TrackItem(
      id: id, url: url, title: nil, artist: nil, album: nil,
      duration: nil, artworkUrl: nil, extras: nil,
      replayGainTrackGain: nil, replayGainTrackPeak: nil,
      replayGainAlbumGain: nil, replayGainAlbumPeak: nil, isVideo: nil
    )
  }

  private func makeCache(allowsCellularAccess: Bool = true) -> LookaheadCache {
    LookaheadCache(
      directory: tempDir, maxSizeBytes: 10 * 1024 * 1024, urlSession: session,
      allowsCellularAccess: allowsCellularAccess
    )
  }
}
