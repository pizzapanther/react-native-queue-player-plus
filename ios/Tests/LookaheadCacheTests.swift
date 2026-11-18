import XCTest
@testable import QueuePlayer
@_spi(QueuePlayerTests) import QueuePlayer

/// `LookaheadCache` unit tests.
///
/// Exercises the cache directly:
/// - constructor creates the directory + ensures plist absent until
///   first persist
/// - `cachedURL(forUrl:)` returns nil for unknown trackIds
/// - `download(track:headers:)` populates the cache + persists the
///   plist + bumps the access tick
/// - file:// / asset:// URLs are no-ops (don't appear in the index)
/// - already-cached download is idempotent (no duplicate entries)
/// - HTTP error response is cleaned up + thrown
/// - clear wipes files + index
/// - file name derivation is filesystem-safe
///
/// URLSession download is exercised via a `URLProtocol` stub so the
/// tests don't hit the real network. Each test gets a per-test
/// directory under `tmpDir` so plist + file state doesn't leak
/// between cases.
final class LookaheadCacheTests: XCTestCase {

  private var tempDir: URL!
  private var session: URLSession!

  override func setUp() {
    super.setUp()
    tempDir = FileManager.default.temporaryDirectory
      .appendingPathComponent("LookaheadCacheTests-\(UUID().uuidString)", isDirectory: true)
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

  // MARK: - Constructor + directory

  func testInitCreatesCacheDirectory() {
    let cache = LookaheadCache(directory: tempDir, maxSizeBytes: LookaheadCache.DEFAULT_MAX_SIZE_BYTES, urlSession: session)
    XCTAssertTrue(
      FileManager.default.fileExists(atPath: cache.cacheDirectory.path),
      "cache directory must exist on disk after init"
    )
  }

  func testInitDoesNotCreatePlistUntilFirstWrite() {
    let cache = LookaheadCache(directory: tempDir, maxSizeBytes: LookaheadCache.DEFAULT_MAX_SIZE_BYTES, urlSession: session)
    XCTAssertFalse(
      cache.indexFileExists,
      "plist should not be persisted before any cache mutation"
    )
  }

  func testDefaultDirectoryIsUnderTmp() {
    let dir = LookaheadCache.defaultDirectory()
    XCTAssertEqual(dir.lastPathComponent, "audio_lookahead",
      "must match Android sibling")
    XCTAssertEqual(
      dir.deletingLastPathComponent().standardizedFileURL,
      FileManager.default.temporaryDirectory.standardizedFileURL,
      "must live under tmpDir"
    )
  }

  func testDefaultMaxSizeBytes() {
    XCTAssertEqual(
      LookaheadCache.DEFAULT_MAX_SIZE_BYTES,
      100 * 1024 * 1024,
      "bootstrap budget is 100 MB"
    )
  }

  // MARK: - Lookup before download

  func testCachedURLReturnsNilForUnknownTrack() {
    let cache = LookaheadCache(directory: tempDir, maxSizeBytes: LookaheadCache.DEFAULT_MAX_SIZE_BYTES, urlSession: session)
    XCTAssertNil(cache.cachedURL(forUrl: "unknown"))
  }

  // MARK: - Download

  func testDownloadPopulatesIndexAndPersistsPlist() async throws {
    let cache = LookaheadCache(directory: tempDir, maxSizeBytes: LookaheadCache.DEFAULT_MAX_SIZE_BYTES, urlSession: session)
    let payload = Data("audio bytes".utf8)
    StubURLProtocol.stub(
      url: URL(string: "https://example.com/track1.m4a")!,
      data: payload, statusCode: 200
    )
    let track = makeTrack(id: "t1", url: "https://example.com/track1.m4a")

    try await cache.download(track: track, headers: nil)

    XCTAssertNotNil(cache.cachedURL(forUrl: "https://example.com/track1.m4a"))
    let entries = cache.indexSnapshot
    XCTAssertEqual(entries.count, 1)
    XCTAssertEqual(entries.first?.trackId, "https://example.com/track1.m4a")
    XCTAssertEqual(entries.first?.sizeBytes, UInt64(payload.count))
    XCTAssertTrue(cache.indexFileExists, "plist must persist after first successful download")
  }

  func testDownloadSkipsFileScheme() async throws {
    let cache = LookaheadCache(directory: tempDir, maxSizeBytes: LookaheadCache.DEFAULT_MAX_SIZE_BYTES, urlSession: session)
    let track = makeTrack(id: "local", url: "file:///tmp/song.m4a")

    try await cache.download(track: track, headers: nil)

    XCTAssertNil(cache.cachedURL(forUrl: "file:///tmp/song.m4a"),
      "file:// URIs bypass the cache — they're already on disk")
    XCTAssertTrue(cache.indexSnapshot.isEmpty)
  }

  func testDownloadSkipsAssetAndContentSchemes() async throws {
    let cache = LookaheadCache(directory: tempDir, maxSizeBytes: LookaheadCache.DEFAULT_MAX_SIZE_BYTES, urlSession: session)
    let asset = makeTrack(id: "a", url: "asset:///bundled.mp3")
    let content = makeTrack(id: "c", url: "content://media/external/audio/123")

    try await cache.download(track: asset, headers: nil)
    try await cache.download(track: content, headers: nil)

    XCTAssertTrue(cache.indexSnapshot.isEmpty)
  }

  func testDownloadIsIdempotentForCachedTrack() async throws {
    let cache = LookaheadCache(directory: tempDir, maxSizeBytes: LookaheadCache.DEFAULT_MAX_SIZE_BYTES, urlSession: session)
    let url = URL(string: "https://example.com/dup.m4a")!
    StubURLProtocol.stub(url: url, data: Data("first".utf8), statusCode: 200)
    let track = makeTrack(id: "dup", url: url.absoluteString)

    try await cache.download(track: track, headers: nil)
    let firstCount = StubURLProtocol.requestCount(for: url)
    try await cache.download(track: track, headers: nil)
    let secondCount = StubURLProtocol.requestCount(for: url)

    XCTAssertEqual(firstCount, 1, "first download should hit the network")
    XCTAssertEqual(secondCount, 1, "second download should short-circuit on already-cached")
    XCTAssertEqual(cache.indexSnapshot.count, 1, "no duplicate index entry")
  }

  func testDownloadThrowsAndCleansUpOnHttpError() async throws {
    let cache = LookaheadCache(directory: tempDir, maxSizeBytes: LookaheadCache.DEFAULT_MAX_SIZE_BYTES, urlSession: session)
    let url = URL(string: "https://example.com/404.m4a")!
    StubURLProtocol.stub(url: url, data: Data(), statusCode: 404)
    let track = makeTrack(id: "404", url: url.absoluteString)

    do {
      try await cache.download(track: track, headers: nil)
      XCTFail("download should have thrown for HTTP 404")
    } catch LookaheadCacheError.httpError(let statusCode) {
      XCTAssertEqual(statusCode, 404)
    } catch {
      XCTFail("unexpected error: \(error)")
    }
    XCTAssertNil(cache.cachedURL(forUrl: "404"))
    XCTAssertTrue(cache.indexSnapshot.isEmpty)
  }

  func testDownloadAppliesHeadersToRequest() async throws {
    let cache = LookaheadCache(directory: tempDir, maxSizeBytes: LookaheadCache.DEFAULT_MAX_SIZE_BYTES, urlSession: session)
    let url = URL(string: "https://example.com/auth.m4a")!
    StubURLProtocol.stub(url: url, data: Data("ok".utf8), statusCode: 200)
    let track = makeTrack(id: "auth", url: url.absoluteString)

    try await cache.download(
      track: track,
      headers: ["Authorization": "Bearer t0ken", "User-Agent": "rnqp-test"]
    )

    let request = StubURLProtocol.lastRequest(for: url)
    XCTAssertEqual(request?.value(forHTTPHeaderField: "Authorization"), "Bearer t0ken")
    XCTAssertEqual(request?.value(forHTTPHeaderField: "User-Agent"), "rnqp-test")
  }

  // MARK: - Lookup after download

  func testCachedURLDoesNotBumpAccessTickAndTouchDoes() async throws {
    // `cachedURL(forUrl:)` is a pure read. LRU bookkeeping happens
    // via the explicit `touch(url:)` call.
    let cache = LookaheadCache(directory: tempDir, maxSizeBytes: LookaheadCache.DEFAULT_MAX_SIZE_BYTES, urlSession: session)
    let url = URL(string: "https://example.com/access.m4a")!
    StubURLProtocol.stub(url: url, data: Data("x".utf8), statusCode: 200)
    let track = makeTrack(id: "access", url: url.absoluteString)
    try await cache.download(track: track, headers: nil)

    let beforeTick = cache.indexSnapshot.first?.lastAccessTick ?? 0
    _ = cache.cachedURL(forUrl: track.url)
    let afterReadTick = cache.indexSnapshot.first?.lastAccessTick ?? 0
    XCTAssertEqual(afterReadTick, beforeTick,
      "pure read of cachedURL must NOT bump the LRU tick")

    cache.touch(url: track.url)
    // touch() is fire-and-forget over the cache's serial queue;
    // synchronise on a getter so the bump is observed.
    _ = cache.getMaxSizeBytes()
    let afterTouchTick = cache.indexSnapshot.first?.lastAccessTick ?? 0
    XCTAssertGreaterThan(afterTouchTick, afterReadTick,
      "touch(url:) must bump the LRU tick")
  }

  func testCachedURLDropsStaleEntryWhenFileMissing() async throws {
    let cache = LookaheadCache(directory: tempDir, maxSizeBytes: LookaheadCache.DEFAULT_MAX_SIZE_BYTES, urlSession: session)
    let url = URL(string: "https://example.com/ghost.m4a")!
    StubURLProtocol.stub(url: url, data: Data("x".utf8), statusCode: 200)
    let track = makeTrack(id: "ghost", url: url.absoluteString)
    try await cache.download(track: track, headers: nil)

    // External actor (consumer cleared tmp, eviction ran, …) deletes
    // the on-disk file out from under the index.
    let fileURL = cache.cachedURL(forUrl: track.url)!
    try FileManager.default.removeItem(at: fileURL)

    XCTAssertNil(cache.cachedURL(forUrl: track.url),
      "missing file must drop the stale entry, not return a dangling URL")
    XCTAssertTrue(cache.indexSnapshot.isEmpty)
  }

  // MARK: - Clear

  func testClearWipesFilesAndIndex() async throws {
    let cache = LookaheadCache(directory: tempDir, maxSizeBytes: LookaheadCache.DEFAULT_MAX_SIZE_BYTES, urlSession: session)
    let url = URL(string: "https://example.com/wipe.m4a")!
    StubURLProtocol.stub(url: url, data: Data("x".utf8), statusCode: 200)
    let track = makeTrack(id: "wipe", url: url.absoluteString)
    try await cache.download(track: track, headers: nil)
    let fileURL = cache.cachedURL(forUrl: track.url)!
    XCTAssertTrue(FileManager.default.fileExists(atPath: fileURL.path))

    cache.clear()

    XCTAssertFalse(FileManager.default.fileExists(atPath: fileURL.path))
    XCTAssertTrue(cache.indexSnapshot.isEmpty)
    XCTAssertNil(cache.cachedURL(forUrl: "wipe"))
  }

  // MARK: - Teardown quiescence

  func testDownloadAfterTearDownIsANoOp() async throws {
    // A prefetcher Task can reach `download` after the owning player has
    // torn the cache down. Creating a URLSession task on the invalidated
    // session raises an Obj-C exception that no Swift `do-catch` can
    // intercept, so the teardown has to quiesce the cache first.
    let cache = LookaheadCache(
      directory: tempDir, maxSizeBytes: LookaheadCache.DEFAULT_MAX_SIZE_BYTES,
      urlSession: session)
    let url = URL(string: "https://example.com/after-teardown.m4a")!
    StubURLProtocol.stub(url: url, data: Data("x".utf8), statusCode: 200)
    let track = makeTrack(id: "after-teardown", url: url.absoluteString)

    cache.tearDown()
    try await cache.download(track: track, headers: nil)

    XCTAssertNil(
      cache.cachedURL(forUrl: track.url),
      "a download issued after tearDown must not populate the cache")
    XCTAssertEqual(
      StubURLProtocol.requestCount(for: url), 0,
      "a download issued after tearDown must not reach the session")
  }

  // MARK: - Eviction integration

  func testDownloadEvictsOldestWhenBudgetExceeded() async throws {
    // Tiny 300-byte budget; 4 sequential 100-byte downloads — the
    // 4th must trigger eviction of the oldest entry to fit.
    let cache = LookaheadCache(
      directory: tempDir,
      maxSizeBytes: 300,
      urlSession: session
    )
    let payload = Data(repeating: 0xAB, count: 100)
    for id in ["t1", "t2", "t3", "t4"] {
      let url = URL(string: "https://example.com/\(id).m4a")!
      StubURLProtocol.stub(url: url, data: payload, statusCode: 200)
      try await cache.download(
        track: makeTrack(id: id, url: url.absoluteString), headers: nil
      )
    }

    let entries = cache.indexSnapshot
    let ids = Set(entries.map { $0.trackId })
    let trackUrl = { (n: String) in "https://example.com/\(n).m4a" }
    XCTAssertEqual(entries.count, 3, "budget=300 with 4 × 100-byte tracks must keep exactly 3")
    XCTAssertFalse(ids.contains(trackUrl("t1")), "oldest (t1) must be evicted")
    XCTAssertTrue(ids.isSuperset(of: [trackUrl("t2"), trackUrl("t3"), trackUrl("t4")]),
      "newer three must remain")
    XCTAssertNil(cache.cachedURL(forUrl: trackUrl("t1")),
      "evicted track must not resolve to a cached URL")
  }

  func testOversizedDownloadProtectsItselfFromEviction() async throws {
    // Single download larger than the budget — the just-appended
    // entry is `protectedTrackIds`-skipped so it survives even
    // though it busts the budget. Caller deliberately overshoots
    // rather than discarding the just-paid-for prefetch.
    let cache = LookaheadCache(
      directory: tempDir, maxSizeBytes: 50, urlSession: session
    )
    let payload = Data(repeating: 0xAB, count: 200)
    let url = URL(string: "https://example.com/big.m4a")!
    StubURLProtocol.stub(url: url, data: payload, statusCode: 200)
    try await cache.download(
      track: makeTrack(id: "big", url: url.absoluteString), headers: nil
    )

    let entries = cache.indexSnapshot
    XCTAssertEqual(entries.count, 1)
    XCTAssertEqual(entries.first?.trackId, "https://example.com/big.m4a",
      "oversized download must stay cached, not silently self-evict")
    XCTAssertNotNil(cache.cachedURL(forUrl: "https://example.com/big.m4a"))
  }

  func testEvictionRemovesFilesFromDisk() async throws {
    let cache = LookaheadCache(
      directory: tempDir,
      maxSizeBytes: 100,
      urlSession: session
    )
    let payload = Data(repeating: 0xAB, count: 100)
    StubURLProtocol.stub(
      url: URL(string: "https://example.com/old.m4a")!,
      data: payload, statusCode: 200
    )
    try await cache.download(
      track: makeTrack(id: "old", url: "https://example.com/old.m4a"),
      headers: nil
    )
    let oldFile = cache.cachedURL(forUrl: "https://example.com/old.m4a")!
    XCTAssertTrue(FileManager.default.fileExists(atPath: oldFile.path))

    StubURLProtocol.stub(
      url: URL(string: "https://example.com/new.m4a")!,
      data: payload, statusCode: 200
    )
    try await cache.download(
      track: makeTrack(id: "new", url: "https://example.com/new.m4a"),
      headers: nil
    )

    XCTAssertFalse(FileManager.default.fileExists(atPath: oldFile.path),
      "evicted entry's on-disk file must be removed, not orphaned")
  }

  // MARK: - Partial-download → remote-streaming fallback

  func testPartialDownloadIsNotResolvedAsCached() {
    // A track that's mid-download (or had its download cancelled)
    // must NOT be returned as cached. `cachedURL(forUrl:)` returns
    // nil so the consumer falls back to the remote URL automatically.
    let cache = LookaheadCache(
      directory: tempDir,
      maxSizeBytes: LookaheadCache.DEFAULT_MAX_SIZE_BYTES,
      urlSession: session
    )
    XCTAssertNil(cache.cachedURL(forUrl: "in-flight"),
      "no entry exists yet → cachedURL nil → consumer streams remote")
  }

  func testCancelledDownloadDoesNotLeaveStaleIndexEntry() async throws {
    // Simulate a download that throws — the partial state must be
    // cleaned up, and `cachedURL` must continue returning nil.
    let cache = LookaheadCache(
      directory: tempDir,
      maxSizeBytes: LookaheadCache.DEFAULT_MAX_SIZE_BYTES,
      urlSession: session
    )
    let url = URL(string: "https://example.com/bad.m4a")!
    StubURLProtocol.stub(url: url, data: Data(), statusCode: 503)
    let track = makeTrack(id: "bad", url: url.absoluteString)

    do {
      try await cache.download(track: track, headers: nil)
      XCTFail("503 should throw")
    } catch { /* expected */ }

    XCTAssertNil(cache.cachedURL(forUrl: "bad"))
    XCTAssertTrue(cache.indexSnapshot.isEmpty)
  }

  // MARK: - File name derivation

  func testFileNameIsFilesystemSafe() {
    let safe = LookaheadCache.fileName(forUrl: "track-123_abc")
    XCTAssertTrue(safe.hasSuffix(".bin"),
      "no Content-Type / no recognisable URL extension → .bin default")
    XCTAssertFalse(safe.contains("/"))
    XCTAssertFalse(safe.contains(":"))
    XCTAssertFalse(safe.contains(" "))
  }

  func testFileNameReplacesIllegalCharsInPrefix() {
    let safe = LookaheadCache.fileName(forUrl: "weird/track:id with spaces")
    XCTAssertFalse(safe.contains("/"))
    XCTAssertFalse(safe.contains(":"))
    XCTAssertFalse(safe.contains(" "))
    XCTAssertTrue(safe.hasSuffix(".bin"))
  }

  func testFileNameUsesContentTypeDerivedExtension() {
    let mp3 = LookaheadCache.fileName(
      forUrl: "https://example.com/opaque", contentType: "audio/mpeg"
    )
    XCTAssertTrue(mp3.hasSuffix(".mp3"))
    let m4a = LookaheadCache.fileName(
      forUrl: "https://example.com/opaque", contentType: "audio/mp4"
    )
    XCTAssertTrue(m4a.hasSuffix(".m4a"))
    let flac = LookaheadCache.fileName(
      forUrl: "https://example.com/opaque", contentType: "audio/flac; charset=utf-8"
    )
    XCTAssertTrue(flac.hasSuffix(".flac"),
      "Content-Type with parameters strips after `;`")
  }

  func testFileNameFallsBackToUrlPathExtension() {
    let mp3 = LookaheadCache.fileName(forUrl: "https://example.com/song.mp3")
    XCTAssertTrue(mp3.hasSuffix(".mp3"))
    let m4a = LookaheadCache.fileName(forUrl: "https://example.com/song.m4a?token=abc")
    XCTAssertTrue(m4a.hasSuffix(".m4a"),
      "query string doesn't mask the path extension")
    let bin = LookaheadCache.fileName(forUrl: "https://example.com/api/stream?id=1")
    XCTAssertTrue(bin.hasSuffix(".bin"),
      "URLs with no recognisable extension stay on .bin")
  }

  func testFileNameContentTypePreferredOverUrlExtension() {
    // URL says .ogg but server reports MP3 — trust the server.
    let preferred = LookaheadCache.fileName(
      forUrl: "https://example.com/song.ogg", contentType: "audio/mpeg"
    )
    XCTAssertTrue(preferred.hasSuffix(".mp3"))
  }

  func testFileNameIgnoresUnknownContentType() {
    let bin = LookaheadCache.fileName(
      forUrl: "https://example.com/opaque",
      contentType: "application/octet-stream"
    )
    XCTAssertTrue(bin.hasSuffix(".bin"),
      "octet-stream is opaque — fall through to URL/.bin")
  }

  func testFileNameIsDeterministic() {
    let a = LookaheadCache.fileName(forUrl: "stable-id")
    let b = LookaheadCache.fileName(forUrl: "stable-id")
    XCTAssertEqual(a, b, "same url must produce the same filename across calls")
  }

  func testFileNameDistinguishesNearCollisions() {
    // URLs that differ only in non-alphanumeric characters MUST
    // produce distinct filenames.
    let a = LookaheadCache.fileName(forUrl: "a/b")
    let b = LookaheadCache.fileName(forUrl: "a_b")
    XCTAssertNotEqual(a, b)
  }

  func testFileNameHandlesUnicodeWithoutCollision() {
    // Precomposed é (U+00E9) vs decomposed e + combining acute (U+0301).
    let precomposed = LookaheadCache.fileName(forUrl: "\u{00E9}")
    let decomposed = LookaheadCache.fileName(forUrl: "e\u{0301}")
    XCTAssertNotEqual(precomposed, decomposed,
      "different Unicode normalisations are different urls")
  }

  // MARK: - maxBytes(forMb:)

  func testMaxBytesForMbMultipliesBeforeCast() {
    // 0.5 MB request — bare-cast-then-multiply would have produced 0
    // (UInt64(0.5) = 0 then 0 * BYTES_PER_MB = 0). Multiply-first
    // produces 524288 bytes, then floored to BYTES_PER_MB = 1 MB.
    XCTAssertEqual(
      LookaheadCache.maxBytes(forMb: 0.5),
      LookaheadCache.BYTES_PER_MB,
      "sub-1MB request floors to BYTES_PER_MB"
    )
  }

  func testMaxBytesForMbAbove1MB() {
    XCTAssertEqual(
      LookaheadCache.maxBytes(forMb: 100.0),
      100 * LookaheadCache.BYTES_PER_MB
    )
    XCTAssertEqual(
      LookaheadCache.maxBytes(forMb: 1.5),
      UInt64(1.5 * Double(LookaheadCache.BYTES_PER_MB))
    )
  }

  func testMaxBytesForMbFloorsAtOneMB() {
    XCTAssertEqual(LookaheadCache.maxBytes(forMb: 0), LookaheadCache.BYTES_PER_MB)
    XCTAssertEqual(LookaheadCache.maxBytes(forMb: -10), LookaheadCache.BYTES_PER_MB,
      "negative inputs must clamp to BYTES_PER_MB, not crash")
  }

  func testMaxBytesForMbDefendsAgainstNonFiniteInputs() {
    // Bridged from JS as a plain Double; Number.NaN, +Infinity,
    // -Infinity are all reachable. UInt64(.nan) and UInt64(.infinity)
    // both trap in Swift, so the helper must short-circuit on
    // non-finite inputs. Mirrors Android's `LookaheadCache.maxBytesForMb`.
    XCTAssertEqual(
      LookaheadCache.maxBytes(forMb: .nan),
      LookaheadCache.BYTES_PER_MB,
      "NaN must clamp to the safe minimum, not crash"
    )
    XCTAssertEqual(
      LookaheadCache.maxBytes(forMb: .infinity),
      LookaheadCache.BYTES_PER_MB,
      "+Infinity must clamp to the safe minimum, not crash"
    )
    XCTAssertEqual(
      LookaheadCache.maxBytes(forMb: -.infinity),
      LookaheadCache.BYTES_PER_MB,
      "-Infinity must clamp to the safe minimum, not crash"
    )
  }

  // MARK: - URL → bytes round-trip correctness
  //
  // These tests cover the contract that downloading URL A's bytes
  // surfaces those exact bytes via cachedURL(forUrl: A) — and that
  // a sibling download of URL B doesn't disturb URL A's mapping.
  // The cache serves each URL its own bytes after any sequence of
  // forward and look-back prefetches.

  func testCachedFileBytesMatchSourceUrlBytes() async throws {
    // The fundamental contract: download URL A → cachedURL(A)'s file
    // contains A's bytes. No way for the cache to serve B's bytes
    // for A's URL. This is the core invariant.
    let cache = LookaheadCache(directory: tempDir, maxSizeBytes: LookaheadCache.DEFAULT_MAX_SIZE_BYTES, urlSession: session)
    let urlA = URL(string: "https://example.com/song-a.m4a")!
    let bytesA = Data("AAAAAAAAAAAAAAAAAAA-payload-for-track-A".utf8)
    StubURLProtocol.stub(url: urlA, data: bytesA, statusCode: 200)

    try await cache.download(track: makeTrack(id: "a", url: urlA.absoluteString), headers: nil)

    let resolved = cache.cachedURL(forUrl: urlA.absoluteString)
    XCTAssertNotNil(resolved, "cachedURL must resolve a freshly-downloaded URL")
    let onDisk = try Data(contentsOf: resolved!)
    XCTAssertEqual(onDisk, bytesA, "bytes at cachedURL(A) must equal the bytes downloaded for A")
  }

  func testMultipleDownloadsKeepPerUrlBytesIsolated() async throws {
    // Sibling downloads must not cross-contaminate. After
    // downloading 4 distinct URLs, cachedURL(N)'s file must contain
    // EXACTLY URL N's bytes.
    let cache = LookaheadCache(directory: tempDir, maxSizeBytes: LookaheadCache.DEFAULT_MAX_SIZE_BYTES, urlSession: session)
    let payloads: [(url: URL, bytes: Data)] = [
      (URL(string: "https://example.com/track-0.m4a")!, Data("payload-zero".utf8)),
      (URL(string: "https://example.com/track-1.m4a")!, Data("payload-one".utf8)),
      (URL(string: "https://example.com/track-2.m4a")!, Data("payload-two".utf8)),
      (URL(string: "https://example.com/track-3.m4a")!, Data("payload-three".utf8)),
    ]
    for (url, bytes) in payloads {
      StubURLProtocol.stub(url: url, data: bytes, statusCode: 200)
    }

    for (idx, (url, _)) in payloads.enumerated() {
      try await cache.download(track: makeTrack(id: "t\(idx)", url: url.absoluteString), headers: nil)
    }

    for (url, expectedBytes) in payloads {
      let resolved = cache.cachedURL(forUrl: url.absoluteString)
      XCTAssertNotNil(resolved, "every downloaded URL must resolve")
      let onDisk = try Data(contentsOf: resolved!)
      XCTAssertEqual(
        onDisk, expectedBytes,
        "cachedURL(\(url.absoluteString)) must contain exactly that URL's bytes"
      )
    }
  }

  func testLookbackPathPreservesPerUrlBytes() async throws {
    // Forward-prefetch t1/t2/t3,
    // then look-back-prefetch t0. After the sequence, every URL
    // resolves to its own bytes — the look-back doesn't clobber the
    // forward window's mappings.
    let cache = LookaheadCache(directory: tempDir, maxSizeBytes: LookaheadCache.DEFAULT_MAX_SIZE_BYTES, urlSession: session)
    let queue: [(url: URL, bytes: Data)] = (0..<4).map { i in
      let u = URL(string: "https://example.com/q-\(i).m4a")!
      let b = Data("track-\(i)-bytes".utf8)
      StubURLProtocol.stub(url: u, data: b, statusCode: 200)
      return (u, b)
    }

    // Forward prefetch (cur=0 → window [1,2,3]).
    for i in 1...3 {
      try await cache.download(track: makeTrack(id: "t\(i)", url: queue[i].url.absoluteString), headers: nil)
    }
    // Look-back: tracks[0] enqueued AFTER the forward window.
    try await cache.download(track: makeTrack(id: "t0", url: queue[0].url.absoluteString), headers: nil)

    for (idx, item) in queue.enumerated() {
      let resolved = cache.cachedURL(forUrl: item.url.absoluteString)
      XCTAssertNotNil(resolved, "queue[\(idx)] must resolve")
      let onDisk = try Data(contentsOf: resolved!)
      XCTAssertEqual(
        onDisk, item.bytes,
        "queue[\(idx)] bytes must match its own URL — look-back must not corrupt forward-window mappings"
      )
    }
  }

  func testFileNameUniquenessAcrossSimilarUrls() {
    // fileName(forUrl:) must not collide for URLs with similar
    // prefixes — e.g. stream URLs that differ only in an ?id=N query
    // parameter.
    let urls = [
      "https://music.example.com/rest/stream?id=1&u=test",
      "https://music.example.com/rest/stream?id=2&u=test",
      "https://music.example.com/rest/stream?id=10&u=test",
      "https://music.example.com/rest/stream?id=100&u=test",
    ]
    let names = urls.map { LookaheadCache.fileName(forUrl: $0) }
    XCTAssertEqual(Set(names).count, names.count,
      "every distinct URL must produce a distinct fileName")
  }

  func testCollisionCandidateUrlsRoundTripDistinctBytes() async throws {
    // End-to-end correctness: download four URLs whose only difference
    // is an `?id=N` query parameter and verify each resolves to its own
    // bytes: `download` and `cachedURL` must key on the same URL.
    let cache = LookaheadCache(directory: tempDir, maxSizeBytes: LookaheadCache.DEFAULT_MAX_SIZE_BYTES, urlSession: session)
    let urlBytes: [(url: URL, bytes: Data)] = [
      "https://music.example.com/rest/stream?id=1&u=test",
      "https://music.example.com/rest/stream?id=2&u=test",
      "https://music.example.com/rest/stream?id=10&u=test",
      "https://music.example.com/rest/stream?id=100&u=test",
    ].enumerated().map { (i, s) in
      let u = URL(string: s)!
      let b = Data("collision-track-\(i)".utf8)
      StubURLProtocol.stub(url: u, data: b, statusCode: 200)
      return (u, b)
    }

    for (i, pair) in urlBytes.enumerated() {
      try await cache.download(
        track: makeTrack(id: "t\(i)", url: pair.url.absoluteString),
        headers: nil
      )
    }
    for (i, pair) in urlBytes.enumerated() {
      let resolved = cache.cachedURL(forUrl: pair.url.absoluteString)
      XCTAssertNotNil(resolved, "url[\(i)] must resolve")
      let onDisk = try Data(contentsOf: resolved!)
      XCTAssertEqual(onDisk, pair.bytes,
        "url[\(i)] must round-trip its OWN bytes after concurrent downloads of similar-prefix URLs")
    }
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
}

// MARK: - URLProtocol stub

/// Intercepts URLSession requests so tests don't hit the real network.
/// Per-URL canned response set via `stub(url:data:statusCode:)`; reset
/// between tests via `reset()`. Records each request for assertions
/// (header forwarding, request count).
final class StubURLProtocol: URLProtocol {

  private struct Stub {
    let data: Data
    let statusCode: Int
    let headerFields: [String: String]?
  }

  private static let lock = NSLock()
  private static var stubs: [URL: Stub] = [:]
  private static var requests: [URL: [URLRequest]] = [:]

  static func stub(
    url: URL, data: Data, statusCode: Int, headerFields: [String: String]? = nil
  ) {
    lock.lock(); defer { lock.unlock() }
    stubs[url] = Stub(data: data, statusCode: statusCode, headerFields: headerFields)
  }

  static func reset() {
    lock.lock(); defer { lock.unlock() }
    stubs.removeAll()
    requests.removeAll()
  }

  static func requestCount(for url: URL) -> Int {
    lock.lock(); defer { lock.unlock() }
    return requests[url]?.count ?? 0
  }

  static func lastRequest(for url: URL) -> URLRequest? {
    lock.lock(); defer { lock.unlock() }
    return requests[url]?.last
  }

  override class func canInit(with request: URLRequest) -> Bool {
    return request.url != nil
  }

  override class func canonicalRequest(for request: URLRequest) -> URLRequest {
    return request
  }

  override func startLoading() {
    guard let url = request.url else {
      client?.urlProtocol(self, didFailWithError: URLError(.badURL))
      return
    }
    Self.lock.lock()
    Self.requests[url, default: []].append(request)
    let stub = Self.stubs[url]
    Self.lock.unlock()

    guard let stub = stub else {
      client?.urlProtocol(self, didFailWithError: URLError(.unsupportedURL))
      return
    }

    let response = HTTPURLResponse(
      url: url, statusCode: stub.statusCode,
      httpVersion: "HTTP/1.1", headerFields: stub.headerFields
    )!
    client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
    client?.urlProtocol(self, didLoad: stub.data)
    client?.urlProtocolDidFinishLoading(self)
  }

  override func stopLoading() {}
}
