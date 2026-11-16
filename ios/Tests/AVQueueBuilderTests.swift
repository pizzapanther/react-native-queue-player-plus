import XCTest
@testable import QueuePlayer
@_spi(QueuePlayerTests) import QueuePlayer
import AVFoundation

/// `AVQueueBuilder` is pure-Swift over AVFoundation. The plain-Swift
/// entry points `httpOptionsRaw(headers:, userAgent:)` and
/// `makeItemRaw(urlString:, headers:, userAgent:)` are deliberately
/// exposed on the library so XCTest targets can exercise the merging +
/// URL-routing logic without constructing the Nitrogen-generated
/// `TrackItem` / `PlayerConfig` types (which don't cross the Swift ↔ C++
/// interop boundary cleanly — see NOTES.md section 6).
///
/// The full `buildPlayerItems(tracks:, config:)` / `makeItem(for:, config:)`
/// overloads are adapters that unpack the Nitro structs and delegate here,
/// so exercising the raw entry points is sufficient coverage for the
/// merge + routing behaviour.
// Main-actor-isolated: several assertions read `AVPlayerItem.asset`, which
// the SDK marks main-actor-isolated; running the cases on the main actor
// keeps those reads warning-free.
@MainActor
final class AVQueueBuilderTests: XCTestCase {

  // MARK: - httpOptionsRaw merging

  func testHttpOptionsWithNoHeadersOrUAReturnsEmpty() {
    let options = AVQueueBuilder.httpOptionsRaw(headers: nil, userAgent: nil)
    XCTAssertTrue(options.isEmpty, "no headers + no UA should produce empty options")
  }

  func testHttpOptionsWithHeadersOnlyPassesThroughVerbatim() {
    let options = AVQueueBuilder.httpOptionsRaw(
      headers: ["Authorization": "Bearer t0ken", "X-Custom": "42"],
      userAgent: nil)
    let headers = options["AVURLAssetHTTPHeaderFieldsKey"] as? [String: String]
    XCTAssertEqual(headers?["Authorization"], "Bearer t0ken")
    XCTAssertEqual(headers?["X-Custom"], "42")
    XCTAssertNil(headers?["User-Agent"], "no UA configured → no UA injected")
  }

  func testHttpOptionsWithUAOnlyInjectsUserAgent() {
    let options = AVQueueBuilder.httpOptionsRaw(
      headers: nil, userAgent: "RNQPDemo/1.0")
    let headers = options["AVURLAssetHTTPHeaderFieldsKey"] as? [String: String]
    XCTAssertEqual(headers?["User-Agent"], "RNQPDemo/1.0")
    XCTAssertEqual(headers?.count, 1, "only UA should be present")
  }

  func testHttpOptionsMergesHeadersAndUA() {
    let options = AVQueueBuilder.httpOptionsRaw(
      headers: ["Authorization": "Bearer t0ken"],
      userAgent: "RNQPDemo/1.0")
    let headers = options["AVURLAssetHTTPHeaderFieldsKey"] as? [String: String]
    XCTAssertEqual(headers?["Authorization"], "Bearer t0ken")
    XCTAssertEqual(headers?["User-Agent"], "RNQPDemo/1.0")
  }

  func testHttpOptionsEmptyUADoesNotInject() {
    let options = AVQueueBuilder.httpOptionsRaw(
      headers: ["Authorization": "Bearer t0ken"],
      userAgent: "")
    let headers = options["AVURLAssetHTTPHeaderFieldsKey"] as? [String: String]
    XCTAssertNil(headers?["User-Agent"], "empty-string UA must be treated as no-UA")
    XCTAssertEqual(headers?["Authorization"], "Bearer t0ken")
  }

  func testHttpOptionsUAInHeadersIsOverriddenByExplicitUAField() {
    // `userAgent` argument wins over a User-Agent key inside headers.
    // The explicit typed field is canonical.
    let options = AVQueueBuilder.httpOptionsRaw(
      headers: ["User-Agent": "stale-value"],
      userAgent: "canonical-value")
    let headers = options["AVURLAssetHTTPHeaderFieldsKey"] as? [String: String]
    XCTAssertEqual(headers?["User-Agent"], "canonical-value")
  }

  // MARK: - makeItemRaw routing

  func testMakeItemForFileURLSkipsHeaderDictionary() {
    let item = AVQueueBuilder.makeItemRaw(
      urlString: "file:///tmp/track.m4a",
      headers: ["Authorization": "Bearer t0ken"],
      userAgent: "RNQPDemo/1.0")

    guard let item = item else {
      XCTFail("makeItemRaw returned nil for a valid file:// URL"); return
    }
    guard let urlAsset = item.asset as? AVURLAsset else {
      XCTFail("expected AVURLAsset, got \(type(of: item.asset))"); return
    }
    XCTAssertTrue(urlAsset.url.isFileURL, "file:// input should produce a file AVURLAsset")
    // file:// assets are constructed without options — no way to read back
    // the options dict from AVURLAsset, so the effective check is that
    // construction succeeded + URL is file-scheme. The documented contract
    // (source code) routes file URLs through `AVURLAsset(url:)` not
    // `AVURLAsset(url:options:)`.
  }

  func testMakeItemForRemoteURLReturnsPlayerItem() {
    let item = AVQueueBuilder.makeItemRaw(
      urlString: "https://example.com/track.m4a",
      headers: ["Authorization": "Bearer x"], userAgent: nil)
    XCTAssertNotNil(item, "https URL should produce a playable item")
    if let urlAsset = item?.asset as? AVURLAsset {
      XCTAssertEqual(urlAsset.url.absoluteString, "https://example.com/track.m4a")
    } else {
      XCTFail("expected AVURLAsset backing")
    }
  }

  // Note: No `makeItemReturnsNilForMalformedURL` test. On iOS 15+
  // Foundation's `URL(string:)` is extremely lenient — it percent-encodes
  // spaces, null bytes, and newlines rather than rejecting them, so the
  // nil branch in `makeItemRaw` is unreachable via string inputs in
  // practice. The guard stays as defense-in-depth; AVFoundation will
  // surface runtime errors from unplayable URLs through `onError`.

  // MARK: - preferredForwardBufferDuration is pinned to 0.0

  func testMakeItemPinsPreferredForwardBufferDurationForRemoteURL() {
    // Apple Dev Forum 683744 / FB9221518 mitigation: every item must
    // ship with `preferredForwardBufferDuration = 0.0` set explicitly.
    // Leaving the value at its default exposes the AVQueuePlayer
    // chain-advance-while-paused defect.
    let item = AVQueueBuilder.makeItemRaw(
      urlString: "https://example.com/track.m4a",
      headers: nil, userAgent: nil)
    XCTAssertNotNil(item)
    XCTAssertEqual(item?.preferredForwardBufferDuration, 0.0)
  }

  func testMakeItemPinsPreferredForwardBufferDurationForFileURL() {
    let item = AVQueueBuilder.makeItemRaw(
      urlString: "file:///tmp/track.m4a",
      headers: nil, userAgent: nil)
    XCTAssertNotNil(item)
    XCTAssertEqual(item?.preferredForwardBufferDuration, 0.0)
  }

  // MARK: - Read-through routing

  private func routableConfig() -> PlayerConfig {
    PlayerConfig(httpHeaders: nil, userAgent: nil, placeholderArtworkUri: nil, autoRetries: nil, retryBackoffMs: nil, networkTimeoutMs: nil, audioContentType: nil, audioCategoryOptions: nil, voiceVocabularyDonationEnabled: nil, allowsExternalPlayback: nil,  visualizationEnabled: nil, clampSeekToBuffered: nil, skipToPreviousBehavior: nil, lookaheadCacheMaxSizeMb: nil, lookaheadCacheEvictionPolicy: nil, progressUpdateIntervalMs: nil, backgroundProgressUpdateIntervalMs: nil, enableDiagnosticLogs: nil)
  }

  private func startedServer() async -> ReadThroughServer {
    let server = ReadThroughServer(cachedFileForOrigin: { _ in nil }, onFirstDiskHit: { _ in })
    server.start()
    XCTAssertTrue(server.waitUntilBound(), "the loopback listener binds within the wait")
    return server
  }

  /// The routing decision is stamped on the item at build time, which is what
  /// source classification reads: a routed item carries the stamp and an
  /// item built with no server does not.
  func testTheItemCarriesWhetherItWasRouted() async throws {
    let server = await startedServer()
    defer { server.stop() }
    let track = makeTrack(id: "remote-stamp", url: "https://example.com/stamp.mp3")

    let routed = try XCTUnwrap(
      AVQueueBuilder.makeItem(for: track, queueItemId: "q1", config: routableConfig(), server: server))
    let direct = try XCTUnwrap(
      AVQueueBuilder.makeItem(for: track, queueItemId: "q2", config: routableConfig(), server: nil))

    XCTAssertTrue(routed.isRoutedThroughReadThroughServer)
    XCTAssertFalse(direct.isRoutedThroughReadThroughServer)
  }

  /// A remote track binds the local server, not the origin — whether or not
  /// the cache holds it yet. That is the point: the URL is chosen once and the
  /// cache question is answered per request, later, when it can be answered.
  func testARemoteTrackBindsTheReadThroughServer() async throws {
    let server = await startedServer()
    defer { server.stop() }
    let track = makeTrack(id: "remote-1", url: "https://example.com/track.mp3")

    let item = AVQueueBuilder.makeItem(
      for: track, queueItemId: UUID().uuidString, config: routableConfig(), server: server)

    let asset = try XCTUnwrap(item?.asset as? AVURLAsset)
    XCTAssertEqual(asset.url.host, "127.0.0.1", "a remote track must read through the server")
  }

  /// The media's own URL survives on the item. Everything that reads a
  /// container, a codec or a track identity out of a URL reads this one — the
  /// asset URL now names the transport.
  func testTheSourceURLRemainsTheOriginAfterRouting() async throws {
    let server = await startedServer()
    defer { server.stop() }
    let track = makeTrack(id: "remote-2", url: "https://example.com/track.flac")

    let item = AVQueueBuilder.makeItem(
      for: track, queueItemId: UUID().uuidString, config: routableConfig(), server: server)

    XCTAssertEqual(item?.sourceURL, "https://example.com/track.flac")
  }

  /// One URL per track for the session, so a window rebuild does not re-point
  /// an asset the player is already reading.
  func testRebuildingTheSameTrackBindsTheSameURL() async throws {
    let server = await startedServer()
    defer { server.stop() }
    let track = makeTrack(id: "remote-3", url: "https://example.com/stable.mp3")
    let config = routableConfig()

    let first = AVQueueBuilder.makeItem(
      for: track, queueItemId: UUID().uuidString, config: config, server: server)
    let second = AVQueueBuilder.makeItem(
      for: track, queueItemId: UUID().uuidString, config: config, server: server)

    let a = try XCTUnwrap(first?.asset as? AVURLAsset).url
    let b = try XCTUnwrap(second?.asset as? AVURLAsset).url
    XCTAssertEqual(a, b)
  }

  func testAFileTrackIsNeverRouted() async throws {
    let server = await startedServer()
    defer { server.stop() }
    let track = makeTrack(id: "local-1", url: "file:///tmp/song.mp3")

    let item = AVQueueBuilder.makeItem(
      for: track, queueItemId: UUID().uuidString, config: routableConfig(), server: server)

    let asset = try XCTUnwrap(item?.asset as? AVURLAsset)
    XCTAssertTrue(asset.url.isFileURL, "a local file has nothing to read through")
  }

  /// AVFoundation resolves a playlist's relative segment paths against the URL
  /// it fetched the playlist from, so a proxied playlist would point every
  /// segment at a token that names none of them.
  func testAnHLSPlaylistIsNeverRouted() async throws {
    let server = await startedServer()
    defer { server.stop() }
    let track = makeTrack(id: "hls-1", url: "https://example.com/stream.m3u8")

    let item = AVQueueBuilder.makeItem(
      for: track, queueItemId: UUID().uuidString, config: routableConfig(), server: server)

    let asset = try XCTUnwrap(item?.asset as? AVURLAsset)
    XCTAssertEqual(asset.url.absoluteString, "https://example.com/stream.m3u8")
  }

  /// A consumer running without the lookahead cache has no server, and gets
  /// exactly today's behaviour rather than a local hop that could never hit.
  func testWithNoServerTheOriginIsBoundDirectly() throws {
    let track = makeTrack(id: "remote-4", url: "https://example.com/direct.mp3")

    let item = AVQueueBuilder.makeItem(
      for: track, queueItemId: UUID().uuidString, config: routableConfig(), server: nil)

    let asset = try XCTUnwrap(item?.asset as? AVURLAsset)
    XCTAssertEqual(asset.url.absoluteString, "https://example.com/direct.mp3")
  }

  func testRoutabilityIsSchemeAndManifestAware() {
    XCTAssertTrue(AVQueueBuilder.isRoutable("https://example.com/a.mp3"))
    XCTAssertTrue(AVQueueBuilder.isRoutable("http://example.com/a.mp3"))
    XCTAssertTrue(AVQueueBuilder.isRoutable("https://example.com/no-extension"))
    XCTAssertFalse(AVQueueBuilder.isRoutable("file:///tmp/a.mp3"))
    XCTAssertFalse(AVQueueBuilder.isRoutable("https://example.com/stream.m3u8"))
    XCTAssertFalse(AVQueueBuilder.isRoutable("HTTPS://example.com/stream.M3U8"))
  }

  func testSetMaxSizeBytesShrinkProtectsCurrentlyPlayingTrack() async throws {
    // The cache's setMaxSizeBytes(_:protectedUrls:) variant
    // skips matching entries from the eviction candidate set. Even
    // when the protected entry is the LRU-oldest, it stays put.
    let tempDir = FileManager.default.temporaryDirectory
      .appendingPathComponent("AVQueueBuilderTests-protect-\(UUID().uuidString)", isDirectory: true)
    defer { try? FileManager.default.removeItem(at: tempDir) }

    StubURLProtocol.reset()
    defer { StubURLProtocol.reset() }
    let sessionConfig = URLSessionConfiguration.ephemeral
    sessionConfig.protocolClasses = [StubURLProtocol.self] + (sessionConfig.protocolClasses ?? [])
    let session = URLSession(configuration: sessionConfig)
    defer { session.invalidateAndCancel() }

    let cache = LookaheadCache(
      directory: tempDir, maxSizeBytes: 10_000, urlSession: session
    )
    defer { cache.tearDown() }

    let payload = Data(repeating: 0xAB, count: 3000)
    for id in ["playing-now", "newer-1", "newer-2"] {
      let url = URL(string: "https://example.com/\(id).m4a")!
      StubURLProtocol.stub(url: url, data: payload, statusCode: 200)
      try await cache.download(
        track: makeTrack(id: id, url: url.absoluteString), headers: nil
      )
    }
    XCTAssertEqual(cache.indexSnapshot.count, 3)

    // Without protection: shrink to 4_000 evicts oldest two
    // (`playing-now` + `newer-1`). With protection, `playing-now`
    // stays cached + the eviction takes `newer-1` then bails when
    // budget would shrink further (only protected entries remain).
    let playingNowUrl = "https://example.com/playing-now.m4a"
    cache.setMaxSizeBytes(4_000, protectedUrls: [playingNowUrl])

    let remaining = Set(cache.indexSnapshot.map { $0.trackId })
    XCTAssertTrue(
      remaining.contains(playingNowUrl),
      "protected entry must stay even though it's the LRU-oldest"
    )
  }

  // MARK: - queueItemId identity contracts

  /// Two `makeItem` calls with IDENTICAL `track.id` AND `track.url`
  /// must produce AVPlayerItems with DISTINCT `queueItemId`s.
  /// Lib-internal identity is independent of consumer-supplied data,
  /// even when the consumer supplies indistinguishable input across
  /// positions.
  ///
  /// Realistic consumer scenario: a playlist with the same track in
  /// multiple positions (Apple-Music-style "play this song again, add
  /// to queue"). Both positions must be navigable independently
  /// without the lib confusing them.
  func testDuplicateTrackProducesDistinctQueueItemIds() {
    let track = makeTrack(id: "shared", url: "https://example.com/same.m4a")
    let queueItemIdA = UUID().uuidString
    let queueItemIdB = UUID().uuidString
    let itemA = AVQueueBuilder.makeItem(
      for: track, queueItemId: queueItemIdA,
      config: PlayerConfig(httpHeaders: nil, userAgent: nil, placeholderArtworkUri: nil, autoRetries: nil, retryBackoffMs: nil, networkTimeoutMs: nil, audioContentType: nil, audioCategoryOptions: nil, voiceVocabularyDonationEnabled: nil, allowsExternalPlayback: nil,  visualizationEnabled: nil, clampSeekToBuffered: nil, skipToPreviousBehavior: nil, lookaheadCacheMaxSizeMb: nil, lookaheadCacheEvictionPolicy: nil, progressUpdateIntervalMs: nil, backgroundProgressUpdateIntervalMs: nil, enableDiagnosticLogs: nil),
      server: nil
    )
    let itemB = AVQueueBuilder.makeItem(
      for: track, queueItemId: queueItemIdB,
      config: PlayerConfig(httpHeaders: nil, userAgent: nil, placeholderArtworkUri: nil, autoRetries: nil, retryBackoffMs: nil, networkTimeoutMs: nil, audioContentType: nil, audioCategoryOptions: nil, voiceVocabularyDonationEnabled: nil, allowsExternalPlayback: nil,  visualizationEnabled: nil, clampSeekToBuffered: nil, skipToPreviousBehavior: nil, lookaheadCacheMaxSizeMb: nil, lookaheadCacheEvictionPolicy: nil, progressUpdateIntervalMs: nil, backgroundProgressUpdateIntervalMs: nil, enableDiagnosticLogs: nil),
      server: nil
    )
    XCTAssertNotNil(itemA)
    XCTAssertNotNil(itemB)
    XCTAssertEqual(itemA?.queueItemId, queueItemIdA,
      "first item's queueItemId must echo the lib-supplied UUID")
    XCTAssertEqual(itemB?.queueItemId, queueItemIdB,
      "second item's queueItemId must echo the lib-supplied UUID")
    XCTAssertNotEqual(itemA?.queueItemId, itemB?.queueItemId,
      "two AVPlayerItems built from the same TrackItem MUST have distinct queueItemIds — the lib-internal identity is per-construction, not per-content")
  }

  /// queueItemId is set EVEN when consumer-supplied `track.id` is
  /// nil/empty. Only `track.url` is required; the lib never falls
  /// back to or relies on `track.id` for internal identity.
  func testQueueItemIdIsSetEvenForNilConsumerId() {
    let track = TrackItem(
      id: nil, url: "https://example.com/nil-id.m4a",
      title: nil, artist: nil, album: nil,
      duration: nil, artworkUrl: nil, extras: nil,
      replayGainTrackGain: nil, replayGainTrackPeak: nil,
      replayGainAlbumGain: nil, replayGainAlbumPeak: nil
    )
    let queueItemId = UUID().uuidString
    let item = AVQueueBuilder.makeItem(
      for: track, queueItemId: queueItemId,
      config: PlayerConfig(httpHeaders: nil, userAgent: nil, placeholderArtworkUri: nil, autoRetries: nil, retryBackoffMs: nil, networkTimeoutMs: nil, audioContentType: nil, audioCategoryOptions: nil, voiceVocabularyDonationEnabled: nil, allowsExternalPlayback: nil,  visualizationEnabled: nil, clampSeekToBuffered: nil, skipToPreviousBehavior: nil, lookaheadCacheMaxSizeMb: nil, lookaheadCacheEvictionPolicy: nil, progressUpdateIntervalMs: nil, backgroundProgressUpdateIntervalMs: nil, enableDiagnosticLogs: nil),
      server: nil
    )
    XCTAssertNotNil(item)
    XCTAssertEqual(item?.queueItemId, queueItemId,
      "queueItemId must be set even when track.id is nil — lib-internal identity is independent of consumer data")
  }

  /// queueItemId is set EVEN when consumer-supplied `track.id` is
  /// empty string. Same contract as nil — consumer-provided value is
  /// untrusted in lib code.
  func testQueueItemIdIsSetEvenForEmptyConsumerId() {
    let track = makeTrack(id: "", url: "https://example.com/empty-id.m4a")
    let queueItemId = UUID().uuidString
    let item = AVQueueBuilder.makeItem(
      for: track, queueItemId: queueItemId,
      config: PlayerConfig(httpHeaders: nil, userAgent: nil, placeholderArtworkUri: nil, autoRetries: nil, retryBackoffMs: nil, networkTimeoutMs: nil, audioContentType: nil, audioCategoryOptions: nil, voiceVocabularyDonationEnabled: nil, allowsExternalPlayback: nil,  visualizationEnabled: nil, clampSeekToBuffered: nil, skipToPreviousBehavior: nil, lookaheadCacheMaxSizeMb: nil, lookaheadCacheEvictionPolicy: nil, progressUpdateIntervalMs: nil, backgroundProgressUpdateIntervalMs: nil, enableDiagnosticLogs: nil),
      server: nil
    )
    XCTAssertNotNil(item)
    XCTAssertEqual(item?.queueItemId, queueItemId,
      "queueItemId must be set even when track.id is empty string")
  }


  private func makeTrack(id: String, url: String) -> TrackItem {
    return TrackItem(
      id: id, url: url, title: nil, artist: nil, album: nil,
      duration: nil, artworkUrl: nil, extras: nil,
      replayGainTrackGain: nil, replayGainTrackPeak: nil,
      replayGainAlbumGain: nil, replayGainAlbumPeak: nil
    )
  }


  // MARK: - Cancelling loads on discarded items

  /// A cancelled asset can never become playable, so cancellation may only
  /// touch assets that no surviving item reuses.
  func testEveryOutgoingItemIsDroppedWhenNothingSurvives() {
    let outgoing = [makeItem("a"), makeItem("b")]
    let dropped = AVQueueBuilder.itemsBeingDropped(outgoing: outgoing, keeping: [])
    XCTAssertEqual(dropped.count, 2)
  }

  func testAnItemWhoseAssetIsReusedIsNotDropped() {
    let shared = AVURLAsset(url: URL(string: "https://example.com/shared.m4a")!)
    let outgoing = [AVPlayerItem(asset: shared), makeItem("other")]
    let rebuiltOnTheSameAsset = AVPlayerItem(asset: shared)

    let dropped = AVQueueBuilder.itemsBeingDropped(
      outgoing: outgoing, keeping: [rebuiltOnTheSameAsset])

    XCTAssertEqual(
      dropped.count, 1,
      "an asset a surviving item rebuilt on must keep its loads — cancelling it "
        + "leaves that item permanently unplayable")
    XCTAssertFalse(
      dropped.contains { $0.asset === shared },
      "the reused asset must not be handed to cancellation")
  }

  func testKeepingIsComparedByAssetIdentityNotByItem() {
    let shared = AVURLAsset(url: URL(string: "https://example.com/shared.m4a")!)
    let outgoing = [AVPlayerItem(asset: shared)]
    // A different item object wrapping the same asset still protects it.
    let dropped = AVQueueBuilder.itemsBeingDropped(
      outgoing: outgoing, keeping: [AVPlayerItem(asset: shared)])
    XCTAssertTrue(dropped.isEmpty)
  }

  func testAnUnrelatedSurvivorDoesNotProtectOtherAssets() {
    let outgoing = [makeItem("a"), makeItem("b")]
    let dropped = AVQueueBuilder.itemsBeingDropped(
      outgoing: outgoing, keeping: [makeItem("c")])
    XCTAssertEqual(dropped.count, 2)
  }

  private func makeItem(_ name: String) -> AVPlayerItem {
    AVPlayerItem(asset: AVURLAsset(url: URL(string: "https://example.com/\(name).m4a")!))
  }

}
