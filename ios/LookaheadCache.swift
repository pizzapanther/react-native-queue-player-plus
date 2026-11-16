import Foundation
import CryptoKit

/// On-disk lookahead cache for the iOS player. Mirrors the Android
/// `LookaheadCache.kt` shape: directory + per-track download +
/// lookup, paired with the `TrackPlayer` lifecycle.
///
/// iOS has no Media3-equivalent built-in cache, so we own the
/// download orchestration directly (`URLSessionDownloadTask` per
/// track, atomic move into `cacheDirectory`, plist metadata index
/// tracking `(trackId, fileName, sizeBytes, lastAccessTick)`).
/// The plist is persisted lazily — every successful download or
/// eviction rewrites it; concurrent reads use the in-memory
/// snapshot guarded by a serial dispatch queue.
///
/// `LookaheadCachePrefetcher` orchestrates downloads on every queue
/// + skip mutation. LRU eviction enforces the budget set via
/// `setMaxSizeBytes`. Mid-download tracks aren't in the index until
/// the move-into-destination block completes, so `cachedURL`
/// returns nil and the consumer reads remote.
///
/// Cross-platform parity with Android:
/// - cache directory name: `audio_lookahead`
/// - bootstrap budget: 100 MB (`DEFAULT_MAX_SIZE_BYTES`)
/// - file:// / asset:// URIs bypass the cache (only http/https go to disk)
/// - lifecycle paired with TrackPlayer.configure / .destroy
/// - per-track failures don't poison the rest of the prefetch window
public final class LookaheadCache: @unchecked Sendable {

  /// On-disk root for cache files. Defaults to
  /// `tmpDir/audio_lookahead/`. The plist index lives at
  /// `cacheDirectory.appendingPathComponent("index.plist")`.
  public let cacheDirectory: URL

  /// Cache budget (bytes). Mutable — the runtime config
  /// API can shrink/grow the budget. Internal because Swift's
  /// `public private(set) var` auto-synthesizes a setter that
  /// collides with the explicit [setMaxSizeBytes] method in the
  /// generated Obj-C bridge header. Consumers read the value via
  /// [getMaxSizeBytes]; the explicit method wraps the eviction pass.
  internal var maxBytes: UInt64

  /// Eviction policy, fixed at construction. `.lru` sorts victims by
  /// `lastAccessTick` (playback-touched); `.fifo` by `insertionOrderTick`
  /// (download order). See `LookaheadCacheEviction`.
  internal let evictionPolicy: EvictionPolicy

  /// Read the current cache budget. Public accessor so the field
  /// itself can stay internal (avoids the bridge-header method
  /// collision documented on `maxBytes`).
  public func getMaxSizeBytes() -> UInt64 {
    return queue.sync { self.maxBytes }
  }

  /// URLSession used for foreground downloads. Constructor injects so
  /// tests can pass a session backed by a `URLProtocol` stub.
  internal let urlSession: URLSession

  /// Serial queue protecting `index` + on-disk plist writes. Reads
  /// from arbitrary threads (the prefetcher hops to its own task)
  /// funnel through `queue.sync`; writes use `queue.async` after
  /// the in-memory mutation so the plist write doesn't block the
  /// download-completion callback.
  private let queue = DispatchQueue(label: "queueplayer.lookahead-cache.index")

  /// In-memory plist index, mirrored to disk after every mutation.
  private var index: Index

  /// Per-trackId dedup of in-flight downloads. The prefetcher
  /// serializes per-chain so concurrent same-trackId
  /// paths are rare in practice, but `tearDown → reschedule`
  /// during a hot KVO window can produce the race + waste
  /// bandwidth on duplicate downloads. With the dedup, the second
  /// caller observes the trackId in the set + returns
  /// immediately; the first caller's eventual cache write makes
  /// the file available for the next `cachedURL` lookup. Guarded
  /// by `queue` like `index`.
  private var pendingDownloads: Set<String> = []

  /// Quiescence flag. When false, `download` no-ops before touching
  /// the URLSession so a `setLookaheadCache(enabled:false)` toggle
  /// can stop new schedule without invalidating the session. Live
  /// downloads are cancelled in `disable()` via
  /// `getAllTasks { $0.cancel() }` — the session itself stays valid
  /// for the cache's lifetime, so an `enable()` flip back can resume
  /// scheduling immediately. Guarded by `queue`. The URLSession is
  /// only invalidated by `tearDown()` at full destroy.
  private var isEnabled: Bool = true
  /// Downloads admitted and not yet finished; `tearDown` waits on it before
  /// invalidating the session.
  private let inFlightDownloads = DispatchGroup()

  // MARK: - Init / lifecycle

  /// - Parameters:
  ///   - directory: root directory for cache files. Defaults to
  ///     `defaultDirectory()` (`tmpDir/audio_lookahead/`).
  ///   - maxSizeBytes: cache budget; eviction reads this value to enforce LRU bounds.
  ///
  /// Production callers use this init; the `URLSession` is built
  /// internally with `URLSessionConfiguration.default`. Tests that
  /// need to inject a `URLProtocol`-stubbed session use the SPI init
  /// below.
  ///
  /// `URLSession` is deliberately NOT exposed on the public init
  /// signature — Swift's C++ interop generator (enabled via
  /// `cxx-interoperability-mode=default` per the podspec build flags)
  /// chokes on `URLSession` references in public Swift API on some
  /// toolchains, surfacing as `use of undeclared identifier
  /// 'NSURLSession'` in the auto-generated `QueuePlayer-Swift.h`.
  /// Keeping the URLSession parameter on a separate SPI init dodges
  /// the bridge-generator pathology.
  // `internal` (not `public`): the `evictionPolicy` parameter is a generated
  // enum, and cxx-interop drops public API referencing it from the emitted
  // module interface (same pathology as `computeEvictions`). Nothing external
  // constructs `LookaheadCache` — production builds it in-module and tests reach
  // it via `@testable import`.
  internal convenience init(
    directory: URL = LookaheadCache.defaultDirectory(),
    maxSizeBytes: UInt64 = LookaheadCache.DEFAULT_MAX_SIZE_BYTES,
    evictionPolicy: EvictionPolicy = .lru,
    allowsCellularAccess: Bool = true
  ) {
    // `.ephemeral` rather than `.default` so the OS shared URL
    // response cache doesn't double-cache our prefetch bytes (we
    // already store the downloaded body in the lookahead cache
    // directory ourselves; the OS-level URLCache would just shadow
    // those bytes in `~/Library/Caches/...` for no benefit). Saves
    // a modest amount of disk + memory and avoids surprising the
    // consumer with two on-disk copies of the same audio.
    let cfg = URLSessionConfiguration.ephemeral
    // `timeoutIntervalForRequest` is an inactivity timer (reset on each byte).
    // A long-track prefetch from a slow or throttling server can idle past the
    // 60s default and get killed mid-download; raise it well past a normal
    // stall. `timeoutIntervalForResource` keeps its 7-day default as the
    // overall backstop, and the cache's own retry/eviction handles a genuinely
    // dead download.
    cfg.timeoutIntervalForRequest = 300
    self.init(
      directory: directory,
      maxSizeBytes: maxSizeBytes,
      evictionPolicy: evictionPolicy,
      urlSession: URLSession(configuration: cfg),
      allowsCellularAccess: allowsCellularAccess
    )
  }

  /// Whether prefetch downloads may use cellular. Set on each request rather
  /// than baked into the session, so a change applies from the next request
  /// without disturbing anything in flight — that one keeps the value it was
  /// issued with. `URLRequest.allowsCellularAccess = false` is an
  /// unconditional refusal, where the session's own value is only a
  /// permissive default.
  internal var allowsCellularAccess: Bool {
    get { queue.sync { cellularAllowed } }
    set { queue.sync { cellularAllowed = newValue } }
  }

  /// Guarded by `queue`, like the rest of the mutable cache state.
  private var cellularAllowed: Bool = true

  /// Test-only init — tests inject a `URLSession` wired to a
  /// `URLProtocol` stub here. `internal` (not `@_spi public`)
  /// because `public` symbols still emit C++ interop bindings even
  /// behind SPI, and `URLSession` in any public signature trips
  /// the bridge generator (`use of undeclared identifier 'NSURLSession'`).
  /// Tests reach this via `@testable import QueuePlayer`.
  internal init(
    directory: URL,
    maxSizeBytes: UInt64,
    evictionPolicy: EvictionPolicy = .lru,
    urlSession: URLSession,
    allowsCellularAccess: Bool = true
  ) {
    self.cacheDirectory = directory
    self.maxBytes = maxSizeBytes
    self.evictionPolicy = evictionPolicy
    self.urlSession = urlSession
    self.cellularAllowed = allowsCellularAccess
    Self.ensureDirectoryExists(at: directory)
    self.index = Self.loadOrCreateIndex(at: directory)
  }

  /// Cancel every in-flight download + invalidate the URLSession.
  /// Idempotent — calling twice on the same instance is safe.
  /// Must be called from `TrackPlayer.destroy` BEFORE any consumer
  /// drops their reference to the cache instance.
  ///
  /// Clears `isEnabled` before invalidating, so a prefetcher Task
  /// that reaches `download` after the teardown returns at the
  /// quiescence gate instead of asking an invalidated session for a
  /// task — which raises the Obj-C exception `Task created in a
  /// session that has been invalidated`, uncatchable in Swift's
  /// `do-catch`. Unlike `disable()` the session is not reusable
  /// afterwards; a teardown ends the instance's life.
  ///
  /// Named `tearDown` (not `release`) to avoid the Obj-C
  /// `NSObject.release` selector collision; pure Swift class so
  /// there is no actual NSObject to inherit, but staying clear
  /// of the name keeps things friendly if a future @objc bridge
  /// gets added.
  public func tearDown() {
    queue.sync {
      self.isEnabled = false
      self.pendingDownloads.removeAll()
    }
    // A download admitted before the flag flipped may not have reached the
    // session yet; asking an invalidated session for a task raises an
    // Objective-C exception Swift cannot catch. The prefetcher is cancelled
    // before this is called, so admitted downloads leave the group as their
    // cancellation lands — the wait is a bound, not a normal cost.
    _ = inFlightDownloads.wait(timeout: .now() + .seconds(2))
    urlSession.invalidateAndCancel()
  }

  /// Quiesce the cache without invalidating its URLSession.
  /// `setLookaheadCache(enabled:false)` calls this rather than
  /// `tearDown` so a subsequent `enable()` can reuse the session
  /// without the bridge between disable + the next download
  /// scheduling against an invalidated session (Obj-C exception
  /// `Task created in a session that has been invalidated`,
  /// uncatchable in Swift's `do-catch`).
  ///
  /// Effects: clears the `isEnabled` flag so `download` no-ops, `cachedURL`
  /// answers nil (the read-through server then serves from the origin) + the
  /// in-flight dedup set so a re-enable starts clean, and cancels
  /// any URLSession tasks already mid-flight via `getAllTasks`. The
  /// session itself stays alive — a later `enable()` reuses it.
  public func disable() {
    queue.sync {
      self.isEnabled = false
      self.pendingDownloads.removeAll()
    }
    urlSession.getAllTasks { tasks in
      tasks.forEach { $0.cancel() }
    }
  }

  /// Re-arm the cache after a `disable()`. Just flips the flag —
  /// `download()` will start scheduling against the existing
  /// URLSession again. Idempotent on already-enabled caches.
  public func enable() {
    queue.sync {
      self.isEnabled = true
    }
  }

  /// URLs that must survive eviction: the playing track and the window
  /// around it. Every eviction path consults this, not just a shrink —
  /// `commitDownloadedFile` evicts after each prefetch commit, so without a
  /// live set the entry backing the playing item can be deleted underneath
  /// it and playback stops with a file that no longer exists.
  ///
  /// Written by `TrackPlayer` on track change and on prefetch reschedule.
  /// Bounded by the caller: protecting the whole remaining queue would leave
  /// nothing evictable and turn every commit into a full cache wipe.
  private var liveProtectedUrls: Set<String> = []

  /// Replace the set of URLs eviction must not touch.
  ///
  /// Asynchronous by design. The caller is `rescheduleLookahead`, which runs on
  /// the queue serialising player state from every queue and skip path, and the
  /// cache queue also serves download commits — a synchronous hop would park
  /// that queue behind disk work on a hot path. The set is advisory for the next eviction, and
  /// evictions run on this same serial queue, so a write enqueued here is
  /// visible to every eviction that follows it.
  public func setProtectedUrls(_ urls: Set<String>) {
    queue.async { self.liveProtectedUrls = urls }
  }

  /// Update the cache budget at runtime. On shrink, runs
  /// `evictUnderBudgetLocked` immediately so the new ceiling is enforced
  /// before the call returns. On grow, the new budget takes effect on the next
  /// download — no immediate eviction.
  ///
  /// `protectedUrls` names the source URLs whose items the engine currently
  /// holds. Without it a shrink can evict the file backing a live item and
  /// surface as `AVPlayerItemFailedToPlayToEndTime` mid-playback. Always keyed
  /// on URL: `track.id` is consumer-supplied and unreliable.
  public func setMaxSizeBytes(_ newSize: UInt64, protectedUrls: Set<String> = []) {
    let urlsToDeleteAfterUnlock: [URL] = queue.sync {
      let shrinking = newSize < self.maxBytes
      self.maxBytes = newSize
      guard shrinking else { return [] }
      let urls = self.evictUnderBudgetLocked(protectedUrls: protectedUrls)
      self.persistIndexLocked()
      return urls
    }
    for url in urlsToDeleteAfterUnlock {
      try? FileManager.default.removeItem(at: url)
    }
  }

  /// Sum of cached bytes across every entry in the index. Reads
  /// `index.entries.sizeBytes` — does NOT stat the on-disk files
  /// (the recorded size is the source of truth).
  public func currentSizeBytes() -> UInt64 {
    return queue.sync {
      self.index.entries.reduce(UInt64(0)) { $0 + $1.sizeBytes }
    }
  }

  /// Whether the cache holds the entire content for the given source URL.
  /// True iff an index entry exists AND the on-disk file is still
  /// present.
  public func isFullyCached(url: String) -> Bool {
    return queue.sync {
      guard let entry = self.index.entries.first(where: { $0.trackId == url }) else {
        return false
      }
      let fileURL = cacheDirectory.appendingPathComponent(entry.fileName)
      return FileManager.default.fileExists(atPath: fileURL.path)
    }
  }

  /// Number of entries the cache index holds (queue-agnostic). Mirrors the
  /// semantics of `currentSizeBytes()` — cache state, independent of any
  /// current queue. Used by `TrackPlayer.buildCacheStatusSnapshot` for the
  /// `tracksFullyCached` field, so the count reflects the cache itself, not
  /// the intersection with `self.tracks`.
  ///
  /// The index is the source of truth, exactly as it is for
  /// `currentSizeBytes()`, so this is O(1) and holds the lock briefly: a
  /// cache-status subscriber has it fire after every prefetch iteration, and
  /// stating the disk per entry would park the caller on the queue committing
  /// those downloads. It therefore over-reports a file removed out from under
  /// the index. Where the answer has to be exact about a single file — deciding
  /// whether to play from cache — `isFullyCached(url:)` stats it.
  ///
  /// Android's counterpart verifies bytes on disk per key, because Media3
  /// caches partial chunks and counting keys there would over-report a
  /// half-downloaded track rather than a deleted one.
  public func fullyCachedCount() -> Int {
    return queue.sync { self.index.entries.count }
  }

  /// Wipe every cached file + reset the index.
  ///
  /// Safe during playback. A playing item is bound to the read-through server
  /// rather than to a cache file, so a track whose file is deleted underneath
  /// it reads through to the origin on its next request instead of failing —
  /// the cost is re-fetching the remainder, not an
  /// `AVPlayerItemFailedToPlayToEndTime`. A response already in flight
  /// completes, because the open handle holds the unlinked inode.
  public func clear() {
    let urlsToDelete: [URL] = queue.sync {
      let urls = self.index.entries.map {
        cacheDirectory.appendingPathComponent($0.fileName)
      }
      self.index = Index(version: 2, entries: [], nextAccessTick: 1, nextInsertionTick: 1)
      self.persistIndexLocked()
      return urls
    }
    // Defer the FS deletes outside `queue.sync` for consistency
    // with `download` + `setMaxSizeBytes`. The deletes can't
    // collide with concurrent reads (the entries are gone from the
    // in-memory index) and slow FS removals shouldn't block
    // concurrent `cachedURL` lookups.
    for url in urlsToDelete {
      try? FileManager.default.removeItem(at: url)
    }
  }

  // MARK: - Lookup

  /// Return the local file URL for `url` (the source URL — the same
  /// string `isFullyCached(url:)` keys on) if the cache is enabled and a
  /// fully-downloaded entry exists, otherwise nil. Pure read — does NOT touch the LRU
  /// access tick. The external production caller is the read-through server's
  /// per-request `cachedFileForOrigin` lookup (built in
  /// `TrackPlayer.startReadThroughServer`), which calls `touch(url:)`
  /// once per token on its first disk hit, so a track's many range
  /// requests cost one index write rather than one per read.
  ///
  /// Defensive: if the file got pruned out from under us (consumer
  /// ran `rm -rf tmp`, or eviction then crashed before
  /// updating the plist), drop the stale entry + return nil. The
  /// in-memory removal is deliberate; we deliberately do NOT
  /// persist the plist on the defensive removal because doing so
  /// would re-introduce a write on every read of an evicted entry.
  /// The next mutation (`download` / `setMaxSizeBytes` / `clear`)
  /// will persist anyway, and the in-memory state is consistent
  /// for the lifetime of this process.
  public func cachedURL(forUrl url: String) -> URL? {
    return queue.sync {
      // A disabled cache serves nothing: every request goes to the origin,
      // whatever is on disk. The files stay for the next enable.
      guard self.isEnabled else { return nil }
      guard let entryIdx = self.index.entries.firstIndex(where: { $0.trackId == url }) else {
        return nil
      }
      let entry = self.index.entries[entryIdx]
      let fileURL = cacheDirectory.appendingPathComponent(entry.fileName)
      guard FileManager.default.fileExists(atPath: fileURL.path) else {
        self.index.entries.remove(at: entryIdx)
        return nil
      }
      return fileURL
    }
  }

  /// Bump the LRU access tick for the cache entry keyed on `url`.
  /// Callers invoke this separately from `cachedURL(forUrl:)` so a
  /// read-heavy playback path doesn't trigger a plist write per
  /// resolution. No-op for unknown URLs.
  public func touch(url: String) {
    queue.async {
      guard let entryIdx = self.index.entries.firstIndex(where: { $0.trackId == url }) else {
        return
      }
      var bumped = self.index.entries[entryIdx]
      bumped.lastAccessTick = self.index.nextAccessTick
      self.index.nextAccessTick &+= 1
      self.index.entries[entryIdx] = bumped
      self.persistIndexLocked()
    }
  }

  // MARK: - Download

  /// Download `track.url` to the cache directory with the supplied
  /// HTTP headers. Atomic: a partial download (interrupted by cancel
  /// or network failure) is removed before the function returns —
  /// `cachedURL(forUrl:)` only resolves to a complete file.
  ///
  /// Idempotent on already-cached tracks: returns immediately if the
  /// trackId is already in the index + the file exists. Skips any
  /// non-http(s) URL (file:// / asset:// don't need caching; iOS
  /// reads them off the local filesystem directly).
  ///
  /// Throws `CancellationError` on cooperative cancellation,
  /// `LookaheadCacheError.invalidUrl` for malformed URLs, or any
  /// `URLSession` error from the download itself.
  public func download(
    track: TrackItem,
    headers: [String: String]?
  ) async throws {
    guard let url = URL(string: track.url) else {
      throw LookaheadCacheError.invalidUrl
    }
    let scheme = url.scheme?.lowercased()
    guard scheme == "http" || scheme == "https" else { return }

    // Quiescence gate: a `disable()` toggle no-ops new schedule
    // before touching the URLSession, so a stale prefetcher Task
    // that survived `disable()` + tried to download against the
    // (intentionally NOT invalidated) session still skips the
    // request rather than competing with a re-enable cycle.
    //
    // The cellular policy is read here too, so one download cannot straddle a
    // change: it is issued wholly under the policy in force when it started.
    let (enabled, cellularAllowed) = queue.sync {
      (self.isEnabled, self.cellularAllowed)
    }
    guard enabled else { return }

    // Cache key = source URL. The `Entry` struct's `trackId` field
    // holds the URL (kept for on-disk plist back-compat); the
    // public APIs all use `url:` parameter labels.
    if let _ = self.cachedURL(forUrl: track.url) {
      return
    }

    // Per-URL dedup. Atomically check + insert: if
    // another caller is already downloading this URL, return
    // immediately without spawning a duplicate URLSession task.
    // The first caller's eventual cache write makes the file
    // available on the next `cachedURL` lookup. Cleanup happens
    // via `defer` below so a thrown error / cancellation also
    // releases the dedup slot.
    //
    // Throw-path semantics: if the first caller throws before the
    // cache write block (e.g. HTTP error or task cancellation),
    // a concurrent second caller's `cachedURL` check would already
    // have returned nil + then observed the dedup slot still
    // occupied → second caller no-ops and skips this iteration.
    // The next prefetch reschedule re-queues the download.
    // Aligns with the existing per-track-failure-swallowed
    // contract in `LookaheadCachePrefetcher` (failed tracks retry
    // on the next reschedule).
    let dedupeKey = track.url
    // Re-read `isEnabled` in the same hop that claims the dedup slot. The read
    // above is separated from here by two more hops, and `tearDown` takes this
    // same queue to invalidate the session — asking an invalidated session for
    // a task raises an Objective-C exception Swift cannot catch. Admission and
    // the enabled check have to be one decision, not two.
    let admitted: Bool = queue.sync {
      guard self.isEnabled, !self.pendingDownloads.contains(dedupeKey) else {
        return false
      }
      self.pendingDownloads.insert(dedupeKey)
      // Entered under the same decision, so `tearDown` sees this download as
      // in flight from the moment it is admitted.
      self.inFlightDownloads.enter()
      return true
    }
    guard admitted else { return }
    defer {
      inFlightDownloads.leave()
      queue.async { _ = self.pendingDownloads.remove(dedupeKey) }
    }

    // Teardown cancels the prefetcher before invalidating the session, and
    // cancellation is cooperative — this is the checkpoint that honours it, so
    // a task admitted a moment before teardown does not reach the session.
    try Task.checkCancellation()

    let (tempLocation, sizeBytes, contentType) = try await performDownload(
      url: url, headers: headers, allowsCellularAccess: cellularAllowed
    )
    try Task.checkCancellation()

    let fileName = Self.fileName(forUrl: track.url, contentType: contentType)
    try commitDownloadedFile(
      tempLocation: tempLocation,
      urlKey: track.url,
      fileName: fileName,
      sizeBytes: sizeBytes
    )
  }

  /// Enough of a body to carry any container signature the media check reads.
  private static let mediaCheckHeadBytes = 64

  /// The first `bytes` of a file, or empty when it cannot be read. An
  /// unreadable head is not itself a rejection — the size check above has
  /// already established the file is there.
  private static func head(ofFileAt url: URL, bytes: Int) -> Data {
    guard let handle = try? FileHandle(forReadingFrom: url) else { return Data() }
    defer { try? handle.close() }
    return (try? handle.read(upToCount: bytes)) ?? Data()
  }

  /// Remove a rejected download's temp file. Cleanup failures are logged
  /// rather than thrown — the caller is already failing the download, and a
  /// leaked temp file should be diagnosable without masking that reason.
  private func discardTemp(at location: URL, reason: String) {
    do {
      try FileManager.default.removeItem(at: location)
    } catch {
      NSLog(
        "[LookaheadCache] temp-file cleanup failed (%@) at %@: %@",
        reason, location.path, String(describing: error)
      )
    }
  }

  /// Run the URLSession download and HTTP-status validation. Returns the temp
  /// location URLSession wrote to plus the measured size in bytes, so the
  /// commit step does not re-stat the file. Throws
  /// `LookaheadCacheError.httpError` for non-2xx responses, cleaning up the
  /// temp file before throwing.
  private func performDownload(
    url: URL, headers: [String: String]?, allowsCellularAccess: Bool
  ) async throws -> (tempLocation: URL, sizeBytes: UInt64, contentType: String?) {
    var request = URLRequest(url: url)
    headers?.forEach { request.addValue($1, forHTTPHeaderField: $0) }
    // Prefetch downloads whole upcoming tracks, so on a metered connection it
    // can spend a lot of data on tracks the listener skips past. `false` here
    // is an unconditional refusal — the request fails with
    // `NSURLErrorDataNotAllowed` rather than falling back to cellular.
    request.allowsCellularAccess = allowsCellularAccess

    let (tempLocation, response) = try await urlSession.download(for: request)

    guard let httpResponse = response as? HTTPURLResponse,
          (200..<300).contains(httpResponse.statusCode) else {
      // Log temp-file cleanup failures (rare on iOS but possible on
      // shared containers across volumes or sandboxed scenarios) so
      // a leaked temp file is diagnosable in production logs.
      do {
        try FileManager.default.removeItem(at: tempLocation)
      } catch {
        NSLog(
          "[LookaheadCache] HTTP-error temp-file cleanup failed at %@: %@",
          tempLocation.path, String(describing: error)
        )
      }
      throw LookaheadCacheError.httpError(
        statusCode: (response as? HTTPURLResponse)?.statusCode ?? -1
      )
    }

    // A 206 to a request that carried no `Range` is a partial body nobody
    // asked for — short by construction.
    if httpResponse.statusCode == 206,
       request.value(forHTTPHeaderField: "Range") == nil {
      discardTemp(at: tempLocation, reason: "unexpected 206")
      throw LookaheadCacheError.unexpectedPartialContent
    }

    // Read the size explicitly rather than defaulting a failure to zero: a
    // `?? 0` turns an unreadable attribute on a perfectly good download into
    // a rejection once the size is checked below.
    guard let attrs = try? FileManager.default.attributesOfItem(atPath: tempLocation.path),
          let sizeNumber = attrs[.size] as? NSNumber else {
      discardTemp(at: tempLocation, reason: "size unreadable")
      throw LookaheadCacheError.sizeUnreadable
    }
    let sizeBytes = sizeNumber.uint64Value

    // Reject only when the body is SHORTER than declared. Stated this way the
    // check is immune to Content-Encoding: a decoded body longer than the
    // declared compressed length is not a truncation. `expectedContentLength`
    // is -1 when the server chunked the response, which carries no length to
    // compare against.
    let expected = httpResponse.expectedContentLength
    if expected > 0, sizeBytes < UInt64(expected) {
      discardTemp(at: tempLocation, reason: "short body")
      throw LookaheadCacheError.truncatedBody(expected: expected, received: sizeBytes)
    }

    let contentType = httpResponse.value(forHTTPHeaderField: "Content-Type")
    // A 2xx of the right length can still be a server's error envelope rather
    // than a track. Committing one puts a permanent non-audio file behind a
    // "fully cached" entry, which outlives the server being well again.
    if let rejection = MediaResponseCheck.rejection(
      contentType: contentType,
      head: Self.head(ofFileAt: tempLocation, bytes: Self.mediaCheckHeadBytes),
      headIsStartOfResource: request.value(forHTTPHeaderField: "Range") == nil
    ) {
      discardTemp(at: tempLocation, reason: "non-media body")
      throw LookaheadCacheError.nonMediaBody(rejection)
    }
    return (tempLocation, sizeBytes, contentType)
  }

  /// Move the downloaded temp file into the cache directory and
  /// update the index under `queue.sync`: drop any stale entry for
  /// `urlKey`, append the new Entry, bump `nextAccessTick`, run
  /// `evictUnderBudgetLocked` (with `urlKey` protected), persist
  /// the plist. After the lock releases, delete any evicted files.
  ///
  /// `moveItem` failures are lifted out of `queue.sync` and rethrown
  /// as `LookaheadCacheError.moveFailed` so a sandbox / cross-volume
  /// failure surfaces to the prefetcher cleanly.
  private func commitDownloadedFile(
    tempLocation: URL, urlKey: String, fileName: String, sizeBytes: UInt64
  ) throws {
    let destination = cacheDirectory.appendingPathComponent(fileName)
    var moveError: Error?
    let urlsToDeleteAfterUnlock: [URL]? = queue.sync {
      // Remove any prior file at this destination (re-download path).
      try? FileManager.default.removeItem(at: destination)
      do {
        try FileManager.default.moveItem(at: tempLocation, to: destination)
      } catch {
        moveError = error
        try? FileManager.default.removeItem(at: tempLocation)
        return nil
      }
      // Drop any stale entry for the same key (re-download).
      self.index.entries.removeAll { $0.trackId == urlKey }
      self.index.entries.append(Entry(
        trackId: urlKey,
        fileName: fileName,
        sizeBytes: sizeBytes,
        lastAccessTick: self.index.nextAccessTick,
        insertionOrderTick: self.index.nextInsertionTick
      ))
      self.index.nextAccessTick &+= 1
      self.index.nextInsertionTick &+= 1
      // Protect the just-appended URL from eviction so an oversized
      // download (single track > maxBytes) doesn't silently
      // evict itself and leave the consumer with a successful-but-
      // immediately-discarded prefetch.
      let urlsToDelete = self.evictUnderBudgetLocked(protectedUrls: [urlKey])
      self.persistIndexLocked()
      return urlsToDelete
    }
    if let error = moveError {
      throw LookaheadCacheError.moveFailed(underlying: error)
    }
    for url in urlsToDeleteAfterUnlock ?? [] {
      try? FileManager.default.removeItem(at: url)
    }
  }

  /// Apply LRU eviction so cumulative cached bytes drop to
  /// [maxBytes] or below. Mutates the in-memory index but
  /// returns the file URLs the caller must delete from disk
  /// AFTER releasing `queue.sync` — slow FS removals shouldn't
  /// block concurrent `cachedURL` lookups. The defensive
  /// "stale entry on cachedURL" branch handles the brief window
  /// where the index says "evicted" but the file still exists on
  /// disk.
  ///
  /// `protectedUrls` skips matching entries from the eviction
  /// candidate set entirely — the download body uses this to
  /// protect the just-appended entry (see [download]).
  ///
  /// MUST be called inside `queue.sync`. Caller is responsible for
  /// the subsequent `persistIndexLocked()` — coalescing the persist
  /// with an appending caller avoids double-writing the plist.
  ///
  /// `internal` (not `private`) so `setLookaheadCache(maxBytes:)`
  /// shrink path can call it directly without duplicating the
  /// orchestration. Tests reach via `@testable import`.
  internal func evictUnderBudgetLocked(
    protectedUrls: Set<String> = []
  ) -> [URL] {
    let evicted = LookaheadCacheEviction.computeEvictions(
      entries: self.index.entries,
      maxBytes: self.maxBytes,
      useFifoOrder: self.evictionPolicy == .fifo,
      protectedUrls: protectedUrls.union(self.liveProtectedUrls)
    )
    if evicted.isEmpty { return [] }
    let evictedIds = Set(evicted.map { $0.trackId })
    let urls = evicted.map { cacheDirectory.appendingPathComponent($0.fileName) }
    self.index.entries.removeAll { evictedIds.contains($0.trackId) }
    return urls
  }

  // MARK: - Test introspection

  /// Snapshot of the current index. Test-only — production code
  /// reaches into the cache via `cachedURL` / `download`.
  @_spi(QueuePlayerTests)
  public var indexSnapshot: [Entry] {
    return queue.sync { self.index.entries }
  }

  /// Whether `index.plist` exists on disk. Test-only.
  @_spi(QueuePlayerTests)
  public var indexFileExists: Bool {
    return FileManager.default.fileExists(atPath: indexFileURL.path)
  }

  // MARK: - Internals

  private var indexFileURL: URL {
    return cacheDirectory.appendingPathComponent("index.plist")
  }

  /// MUST be called inside `queue.sync` — the in-memory `self.index`
  /// is the authoritative source and the plist is its mirror.
  private func persistIndexLocked() {
    let encoder = PropertyListEncoder()
    encoder.outputFormat = .binary
    do {
      let data = try encoder.encode(self.index)
      try data.write(to: indexFileURL, options: .atomic)
    } catch {
      // Persistence failure is non-fatal — the in-memory index is
      // authoritative for the lifetime of this process. Next launch
      // will fall back to whatever last persisted successfully.
    }
  }

  private static func loadOrCreateIndex(at directory: URL) -> Index {
    let url = directory.appendingPathComponent("index.plist")
    guard let data = try? Data(contentsOf: url) else {
      return Index(version: 2, entries: [], nextAccessTick: 1, nextInsertionTick: 1)
    }
    do {
      return try PropertyListDecoder().decode(Index.self, from: data)
    } catch {
      // Unreadable index (an older on-disk schema or genuine corruption).
      // Without a valid index the on-disk files are unreferenced, so purge
      // the directory to avoid orphans, then start fresh.
      Self.purgeCachedFiles(at: directory)
      return Index(version: 2, entries: [], nextAccessTick: 1, nextInsertionTick: 1)
    }
  }

  /// Delete every file in the cache directory. Used when the index is
  /// reset (unreadable/older schema) so orphaned audio files don't linger.
  private static func purgeCachedFiles(at directory: URL) {
    let fm = FileManager.default
    guard let contents = try? fm.contentsOfDirectory(
      at: directory, includingPropertiesForKeys: nil
    ) else { return }
    for file in contents {
      try? fm.removeItem(at: file)
    }
  }

  /// Delete every file in the default cache directory without a live instance.
  /// Used at configure time when the eviction policy changes while the cache is
  /// disabled (no instance to `clear()`), so the change still starts empty —
  /// parity with Android's unconditional clear.
  internal static func purgeDefaultDirectory() {
    purgeCachedFiles(at: defaultDirectory())
  }

  private static func ensureDirectoryExists(at directory: URL) {
    try? FileManager.default.createDirectory(
      at: directory, withIntermediateDirectories: true
    )
  }

  /// Default cache root: `tmpDir/audio_lookahead/`. Matches the
  /// Android sibling's `context.cacheDir/audio_lookahead/`.
  public static func defaultDirectory() -> URL {
    return FileManager.default.temporaryDirectory
      .appendingPathComponent("audio_lookahead", isDirectory: true)
  }

  /// Filesystem-safe filename derived from a source URL. Uses a SHA256
  /// hex digest of the UTF-8 bytes so collision risk is cryptographic
  /// (two URLs colliding → SHA256 collision found) rather than
  /// dependent on the URL's character set. Earlier versions did
  /// alphanumeric collapse, which collided non-trivially: `"a/b"`
  /// and `"a_b"` mapped to the same filename + clobbered each other.
  ///
  /// A short readable prefix is prepended for human debuggability when
  /// inspecting the cache directory — derived from the same alphanumeric
  /// collapse but for display only, not used for collision checks.
  ///
  /// `contentType` (HTTP `Content-Type` header from the download
  /// response, when known) drives the file extension. AVFoundation
  /// uses the extension as one signal when picking a demuxer for a
  /// local-file AVURLAsset; a `.bin` extension breaks decoding for
  /// some assets, so the cached file MUST end in the right format
  /// suffix for AVPlayer to play it back correctly. Falls back to
  /// the URL's path extension, then `.bin` as a last resort.
  ///
  /// Exposed via `@_spi(QueuePlayerTests)` so unit tests can verify
  /// the derivation; production code reaches it through `download`.
  @_spi(QueuePlayerTests)
  public static func fileName(
    forUrl url: String, contentType: String? = nil
  ) -> String {
    let hash = SHA256.hash(data: Data(url.utf8))
    let hex = hash.map { String(format: "%02x", $0) }.joined()
    let prefix = url.unicodeScalars.prefix(16).map { scalar -> Character in
      let c = Character(scalar)
      if c.isLetter || c.isNumber || c == "-" || c == "_" { return c }
      return "_"
    }
    let prefixString = String(prefix)
    let ext = extensionFromMimeType(contentType)
      ?? extensionFromUrl(url)
      ?? "bin"
    return "\(prefixString.isEmpty ? "track" : prefixString)-\(hex).\(ext)"
  }

  /// Map an HTTP `Content-Type` value to a file-extension hint that
  /// AVFoundation recognises. Returns `nil` for unknown / missing
  /// types so the caller can fall through to URL-derived hints.
  /// `application/octet-stream` (the default on some media servers)
  /// is treated as unknown — caller falls back to URL hints.
  @_spi(QueuePlayerTests)
  public static func extensionFromMimeType(_ contentType: String?) -> String? {
    guard let raw = contentType, !raw.isEmpty else { return nil }
    // Strip parameter suffix (e.g. `audio/flac; charset=utf-8`).
    // `prefix(while:)` returns the entire string when there's no `;`.
    let head = raw.prefix(while: { $0 != ";" })
    let normalised = String(head).trimmingCharacters(in: .whitespaces).lowercased()
    switch normalised {
    case "audio/mpeg": return "mp3"
    case "audio/mp4", "audio/mp4a-latm": return "m4a"
    case "audio/aac": return "aac"
    case "audio/wav", "audio/wave", "audio/x-wav": return "wav"
    case "audio/flac", "audio/x-flac": return "flac"
    case "audio/ogg", "application/ogg": return "ogg"
    case "audio/opus": return "opus"
    case "audio/x-m4a": return "m4a"
    case "application/x-mpegurl", "application/vnd.apple.mpegurl": return "m3u8"
    default: return nil
    }
  }

  /// URL-side extension fallback — the URL's path extension when it
  /// names a known audio format (`song.mp3`, `track.m4a`,
  /// `playlist.m3u8`). Returns `nil` for opaque / parameterised URLs
  /// so the caller can fall through to the `.bin` default.
  @_spi(QueuePlayerTests)
  public static func extensionFromUrl(_ url: String) -> String? {
    guard let components = URLComponents(string: url) else { return nil }
    let path = components.path
    if let dotIndex = path.lastIndex(of: ".") {
      let ext = String(path[path.index(after: dotIndex)...]).lowercased()
      // Canonicalise `.mp4` → `m4a` to match `extensionFromMimeType`'s
      // `audio/mp4 → m4a` mapping. Both extensions are AVFoundation-
      // playable but the canonical audio form keeps URL-derived and
      // Content-Type-derived names symmetric so the same cached file
      // never appears under two suffixes across re-cache cycles.
      if ext == "mp4" { return "m4a" }
      if Self.knownAudioExtensions.contains(ext) { return ext }
    }
    return nil
  }

  private static let knownAudioExtensions: Set<String> = [
    "mp3", "m4a", "m4b", "m4r", "aac",
    "wav", "wave", "flac", "ogg", "opus", "m3u8"
  ]

  // MARK: - Constants

  /// Default cache budget. The bootstrap budget is deliberately
  /// smaller than the consumer-facing API's 500 MB default so a
  /// fresh install doesn't surprise users with half a gigabyte of
  /// automatic disk usage. Mirrors Android.
  public static let DEFAULT_MAX_SIZE_BYTES: UInt64 = 100 * 1024 * 1024

  /// 1 MB in bytes — the minimum cache budget enforced at every
  /// site that converts `PlayerConfig.lookaheadCacheMaxSizeMb` into
  /// a byte budget. Mirrors Android's `LookaheadCache.BYTES_PER_MB`
  /// minimum-floor.
  public static let BYTES_PER_MB: UInt64 = 1024 * 1024

  /// Convert a Double-typed cache budget (MB) from
  /// `PlayerConfig.lookaheadCacheMaxSizeMb` into a `UInt64` byte budget. Multiplies
  /// in Double space BEFORE the `UInt64` cast so a 0.5 MB request
  /// produces 524288 bytes intermediate (then floored to `BYTES_PER_MB`
  /// = 1 MB on the final return) rather than 0 bytes (which the
  /// bare `UInt64(0.5) * BYTES_PER_MB = 0` shape would produce).
  /// Floors at `BYTES_PER_MB` (1 MB) for cross-platform parity with
  /// Android.
  ///
  /// Defends against `NaN` / `±Infinity` from the JS bridge: Swift's
  /// `UInt64(.nan)` and `UInt64(.infinity)` both trap, so a JS caller
  /// passing `Number.NaN` or `Number.POSITIVE_INFINITY` would crash
  /// the app. Treat non-finite inputs as "garbage in → safe minimum"
  /// and return `BYTES_PER_MB` directly. Mirrors Android's
  /// `LookaheadCache.maxBytesForMb` defence.
  public static func maxBytes(forMb maxCacheSizeMb: Double) -> UInt64 {
    guard maxCacheSizeMb.isFinite else { return BYTES_PER_MB }
    let raw = max(0, maxCacheSizeMb) * Double(BYTES_PER_MB)
    let asBytes = UInt64(raw)
    return max(asBytes, BYTES_PER_MB)
  }

  // MARK: - Nested types

  /// Per-track metadata persisted in the plist index.
  public struct Entry: Codable, Equatable {
    /// Holds the source URL (the cache key). Named `trackId` for
    /// on-disk plist back-compat; the public APIs use the accurate
    /// `url:` label. Lib-internal state never derives from
    /// consumer-supplied TrackItem fields.
    public let trackId: String
    public let fileName: String
    public let sizeBytes: UInt64
    /// Bumped every time the entry is touched for playback — the `.lru` sort key.
    public var lastAccessTick: UInt64
    /// Stamped once at download, never bumped — the `.fifo` sort key.
    public let insertionOrderTick: UInt64
  }

  /// Plist-encoded index payload. `version` lets a future migration
  /// detect + upgrade old layouts; ships v1.
  internal struct Index: Codable {
    var version: Int
    var entries: [Entry]
    var nextAccessTick: UInt64
    var nextInsertionTick: UInt64
  }
}

/// Errors thrown by `LookaheadCache.download`. Non-throwing scheme
/// skip (file:// etc.) returns silently — this enum only covers
/// real failures the caller might want to log.
public enum LookaheadCacheError: Error {
  case invalidUrl
  case httpError(statusCode: Int)
  /// `FileManager.moveItem` from temp → cache directory failed
  /// (sandbox / cross-volume / permission). Without surfacing this,
  /// the prefetcher would log "track processed" with success
  /// semantics while no `Entry` was appended; subsequent
  /// `cachedURL` lookups return nil and the cache-status snapshot
  /// lies. See `download(track:headers:)` for the throw site.
  case moveFailed(underlying: Error)
  /// Body was shorter than the response declared. Committing it would put a
  /// truncated file behind a "fully cached" entry, and the item built from
  /// it fails to decode on every play and every retry.
  case truncatedBody(expected: Int64, received: UInt64)
  /// Server answered a partial body to a request that asked for the whole
  /// file, so the download is short by construction.
  case unexpectedPartialContent
  /// The downloaded file's size could not be read, so the truncation check
  /// cannot run; the file is discarded rather than committed unchecked.
  case sizeUnreadable
  /// The body is a document rather than media — typically a server reporting
  /// an API-level failure as a `200` carrying an XML or JSON envelope.
  /// Committing it would serve that envelope as the track on every later play.
  case nonMediaBody(MediaResponseCheck.Rejection)
}

/// Abstraction over `LookaheadCache.download` so the prefetcher
/// can accept an alternate conformer without touching `URLSession`.
/// Production code uses `LookaheadCache` itself (which conforms below).
///
/// Gated behind `@_spi(QueuePlayerTests)`. Production callers reach
/// `LookaheadCache` directly via `LookaheadCachePrefetcher.init(cache:)`.
@_spi(QueuePlayerTests)
public protocol LookaheadCacheDownloader: AnyObject {
  func download(track: TrackItem, headers: [String: String]?) async throws

  /// Reports whether the underlying cache holds a complete copy of
  /// `url`. The prefetcher reads this in `reschedule` to skip
  /// re-enqueuing already-cached URLs and inside the orchestrator
  /// loop to skip a popped target whose bytes landed via another
  /// path before the loop reached it.
  func isFullyCached(url: String) -> Bool
}

@_spi(QueuePlayerTests)
extension LookaheadCache: LookaheadCacheDownloader {}
