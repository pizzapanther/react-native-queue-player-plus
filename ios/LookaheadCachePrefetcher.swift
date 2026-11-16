import Foundation

/// Sliding-window prefetcher for the iOS lookahead cache. Mirrors
/// the Android `LookaheadCacheWriter.kt` shape: a single long-lived
/// orchestrator drains a `pendingTargets` queue one URL at a time,
/// downloading each via [LookaheadCache.download].
/// `reschedule(tracks:currentIndex:lookaheadCount:)` enqueues new
/// desired URLs and prunes ones that have left the window. The
/// in-flight download is kept whenever its URL is still desired,
/// only cancelled when it's left the window. A "look-back" tail
/// (the queue head) is appended after the forward window when the
/// head is remote and isn't the current track, so a skip back to
/// `t0` lands on cached bytes.
///
/// Wired from TrackPlayer at every queue + skip mutation point.
/// Default lookahead window is `DEFAULT_LOOKAHEAD_COUNT` (3);
/// `setLookaheadCache` lets consumers override at runtime.
///
/// `currentlyDownloadingUrl` exposes the single in-flight URL for
/// the cache-status snapshot. Multiple parallel downloads are not
/// supported in v1; the snapshot's `currentlyCaching` field stays
/// single-element.
///
/// **Error-recovery contract.** The prefetcher is designed for
/// flaky-network resilience:
///
/// - Per-track download failure (HTTP error, timeout, transient
///   network drop): swallowed via `logSwallowed`; the orchestrator
///   continues to the next pending target. The failed URL is NOT
///   marked as cached or in-flight, so it remains eligible for
///   re-attempt on a subsequent reschedule.
/// - Complete network outage (every target throws): orchestrator
///   drains pendingTargets cleanly, leaves `inFlightUrl == nil`,
///   and exits. The next `reschedule` call re-enqueues the still-
///   desired URLs, retrying each one. There is no in-prefetcher
///   retry loop or backoff — retry is driven by the existing
///   reschedule trigger points (every queue mutation, every skip,
///   every natural auto-advance). When the user advances through a
///   tunnel of cached tracks and connectivity returns, the next
///   track-end fires `rescheduleLookahead()` and the prefetcher
///   retries the missing window.
/// - Cancellation (URL leaves the window): drops just the slot,
///   discards the in-flight URLSession bytes (URLSession download
///   cancellation removes the temp file), and continues. The
///   cancelled URL re-enters pendingTargets only if a future
///   reschedule re-includes it in the desired window.
public final class LookaheadCachePrefetcher {

  /// The cache to populate. Construction-time bound; release()
  /// invalidates the cache's URLSession so any in-flight downloads
  /// abort. Typed as the protocol so tests can inject a stub
  /// conformer (via the SPI-gated init); production callers reach
  /// the non-SPI init that takes a concrete `LookaheadCache`.
  private let cache: LookaheadCacheDownloader

  /// Headers + UA snapshot used for prefetch downloads. Read on
  /// every download call so a runtime-config update can swap
  /// the snapshot atomically. Written from `configure`, which runs on the
  /// same queue this prefetcher is confined to.
  internal var configHeaders: [String: String]?
  internal var configUserAgent: String?

  /// The queue every access to this prefetcher's state runs on. Nothing here
  /// touches UI — the work is URLSession downloads and file IO through a cache
  /// that serialises itself — so it is confined to whichever queue owns player
  /// state rather than to main. That keeps it on the same queue as the callers
  /// which cancel and tear it down, whose ordering against the cache's
  /// URLSession invalidation has to hold.
  private let queue: DispatchQueue

  /// Source URL of the in-flight download, or `nil` when nothing is
  /// mid-flight. The orchestrator reads it to decide whether to cancel, and
  /// `reschedule` to know which URL is already covered.
  ///
  /// Guarded by `queue`, not by an isolation annotation: a `@MainActor` or
  /// actor declaration here would cascade through every caller.
  private var inFlightUrl: String?

  /// The Task running the in-flight download. Held so a `reschedule`
  /// that drops the in-flight URL from the desired set can cancel it.
  private var inFlightTask: Task<Void, Error>?

  /// FIFO of URLs the orchestrator hasn't started yet, in the
  /// order they should download. `reschedule` prunes entries whose
  /// URL has left the desired window and appends new desired URLs
  /// in window order with the look-back tail last.
  private var pendingTargets: [TrackItem] = []

  /// Long-lived orchestrator Task that drains `pendingTargets` one
  /// URL at a time. Re-launched lazily by `reschedule` whenever
  /// `pendingTargets` becomes non-empty and the previous orchestrator
  /// has finished or doesn't exist.
  private var orchestrator: Task<Void, Never>?

  /// Explicit running flag for the orchestrator's lifecycle. Set
  /// true synchronously before the Task is constructed; cleared in
  /// the Task's trailing main hop after the loop body returns.
  /// Decouples liveness from `Task.isCancelled` (which doesn't
  /// distinguish "running" from "body finished but final main hop
  /// not yet observed").
  private var isOrchestratorRunning: Bool = false

  /// Generation token bumped each time `ensureOrchestratorRunning`
  /// launches a fresh orchestrator. The launched Task captures its
  /// generation; the trailing main hop only clears ownership if the
  /// captured generation still matches. Cancel-then-reschedule
  /// sequences move the generation forward so a stale trailing hook
  /// can't clobber the new orchestrator's tracking state. Wraps via
  /// `&+=` (overflow saturating).
  private var orchestratorGeneration: UInt64 = 0

  /// Source URL of the currently-downloading entry, or `nil` when
  /// no download is mid-flight. Used by `getLookaheadCacheStatus()`
  /// to populate the `currentlyCaching` field. Mirrors `inFlightUrl`
  /// — exposed publicly so JS consumers correlating "what's
  /// downloading" against "what's cached" through the snapshot see
  /// consistent URL keys.
  public var currentlyDownloadingUrl: String? { inFlightUrl }

  /// Default lookahead window applied when `reschedule` is called
  /// without an explicit count. Mutable so
  /// `setLookaheadCache(lookaheadCount:)` can update the live
  /// default. Tracks should NOT see a window shift mid-flight — the
  /// change applies on the next reschedule call.
  public var defaultLookaheadCount: Int = LookaheadCachePrefetcher.DEFAULT_LOOKAHEAD_COUNT

  /// Optional callback fired after each per-track download iteration
  /// completes (success, failure, or cancellation handled). Used by
  /// [TrackPlayer.onCacheStatusChange] surface to emit a
  /// status snapshot whenever the cache state changes.
  public var onTrackProcessed: (() -> Void)?

  // MARK: - Init / lifecycle

  /// Production initializer — takes a concrete `LookaheadCache`.
  /// SPI consumers (tests) reach the protocol-typed init below to
  /// inject a stub conformer.
  ///
  /// - Parameters:
  ///   - cache: the on-disk cache to populate.
  ///   - configHeaders / configUserAgent: snapshot of the player
  ///     config's auth context, used as `URLRequest` headers on
  ///     each download.
  public init(
    cache: LookaheadCache,
    confinedTo queue: DispatchQueue,
    configHeaders: [String: String]? = nil,
    configUserAgent: String? = nil
  ) {
    self.cache = cache
    self.queue = queue
    self.configHeaders = configHeaders
    self.configUserAgent = configUserAgent
  }

  /// SPI initializer — accepts any `LookaheadCacheDownloader`
  /// conformer so callers can inject an alternate. Off the
  /// consumer-facing surface; production calls reach the concrete
  /// `LookaheadCache`-typed init above.
  @_spi(QueuePlayerTests)
  public init(
    downloader: LookaheadCacheDownloader,
    confinedTo queue: DispatchQueue,
    configHeaders: [String: String]? = nil,
    configUserAgent: String? = nil
  ) {
    self.cache = downloader
    self.queue = queue
    self.configHeaders = configHeaders
    self.configUserAgent = configUserAgent
  }

  /// Drain pending targets, cancel the in-flight download, and
  /// stop the orchestrator. Does NOT invalidate the cache's
  /// URLSession — `LookaheadCache.tearDown` owns that.
  ///
  /// **Cache lifecycle interaction:** the spawned download Task
  /// captures `cache` strongly while `[weak self]` lets the
  /// prefetcher release mid-loop. So `cancel()` stops the
  /// orchestrator from launching further downloads but doesn't
  /// halt bytes already in flight against URLSession. The
  /// intended teardown order is:
  ///   1. `prefetcher.tearDown()` — orchestrator + pendingTargets
  ///      cleared, in-flight Task cancelled.
  ///   2. `cache.tearDown()` — URLSession invalidated; any bytes
  ///      still on the wire abort.
  public func cancel() {
    dispatchPrecondition(condition: .onQueue(queue))
    pendingTargets.removeAll()
    inFlightTask?.cancel()
    inFlightTask = nil
    inFlightUrl = nil
    orchestrator?.cancel()
    orchestrator = nil
    isOrchestratorRunning = false
    // Bump generation so any in-flight orchestrator's trailing main
    // hop sees a stale token and skips clobbering the post-cancel
    // state.
    orchestratorGeneration &+= 1
  }

  /// Cancel + clear. Call from `TrackPlayer.destroy` BEFORE
  /// `LookaheadCache.tearDown` so the prefetch loop can observe
  /// cancellation cleanly before its underlying URLSession goes away.
  public func tearDown() {
    dispatchPrecondition(condition: .onQueue(queue))
    cancel()
  }

  // MARK: - Reschedule

  /// Reconcile the prefetcher against a new (tracks, currentIndex,
  /// lookaheadCount) snapshot. Computes the desired window
  /// `[cur+1, cur+1+lookaheadCount)` of remote tracks plus an
  /// optional look-back tail (`tracks[0]` when remote and not the
  /// current track). Prunes pending entries that have left the
  /// window, cancels the in-flight download only when its URL is
  /// no longer desired, and appends any newly-desired URL not
  /// already cached, in-flight, or pending. Re-launches the
  /// orchestrator if it's dormant.
  public func reschedule(
    tracks: [TrackItem],
    currentIndex: Int,
    lookaheadCount: Int? = nil
  ) {
    dispatchPrecondition(condition: .onQueue(queue))
    let count = lookaheadCount ?? defaultLookaheadCount
    var desired = computeTargets(
      tracks: tracks, currentIndex: currentIndex, lookaheadCount: count
    )
    if let lb = lookbackTarget(tracks: tracks, currentIndex: currentIndex) {
      desired.append(lb)
    }
    let desiredUrls = Set(desired.map { $0.url })

    // Prune pending entries whose URL is no longer desired,
    // preserving the existing order for retained ones.
    pendingTargets = pendingTargets.filter { desiredUrls.contains($0.url) }

    // Cancel the in-flight download only when its URL is no longer
    // desired. Keeping an in-flight download whose URL is still
    // desired means a track-change reschedule (auto-advance,
    // skipToIndex within the window) doesn't waste the bytes
    // already on the wire.
    if let inFlight = inFlightUrl, !desiredUrls.contains(inFlight) {
      inFlightTask?.cancel()
    }

    // Compute the set of URLs already accounted for so we don't
    // re-enqueue them. The in-flight URL is included only if its
    // task is alive (not cancelled). A cancelled in-flight URL
    // won't actually complete, so it must be free to re-enqueue
    // when the next reschedule re-includes it (e.g. tight skip-back
    // pattern that re-targets the URL we just cancelled).
    var knownUrls = Set(pendingTargets.map { $0.url })
    if let inFlight = inFlightUrl,
       let task = inFlightTask,
       !task.isCancelled {
      knownUrls.insert(inFlight)
    }

    for track in desired {
      if knownUrls.contains(track.url) { continue }
      if cache.isFullyCached(url: track.url) { continue }
      pendingTargets.append(track)
      knownUrls.insert(track.url)
    }

    if !pendingTargets.isEmpty {
      ensureOrchestratorRunning()
    }
  }

  /// Look-back target: the queue head (`tracks[0]`) when remote
  /// and not the current track. Returns nil for an empty queue,
  /// a non-remote head, or when the head IS the current track
  /// (cur == 0).
  @_spi(QueuePlayerTests)
  public func lookbackTarget(
    tracks: [TrackItem],
    currentIndex: Int
  ) -> TrackItem? {
    guard let head = tracks.first else { return nil }
    if !Self.isRemote(head.url) { return nil }
    if currentIndex >= 0 && currentIndex < tracks.count {
      if tracks[currentIndex].url == head.url { return nil }
    }
    return head
  }

  /// Pure index/URL-scheme arithmetic — exposed for unit testing.
  /// Returns the prefix of remote tracks in the lookahead window
  /// `[currentIndex + 1, currentIndex + 1 + lookaheadCount)` clamped
  /// to `tracks.count`. Local URIs (file:// / asset:// / any
  /// non-http(s) scheme) are filtered out — they bypass the cache,
  /// so prefetching them would waste disk + bandwidth on local data.
  /// Mirrors Android's `LookaheadCacheWriter.computeTargets`.
  @_spi(QueuePlayerTests)
  public func computeTargets(
    tracks: [TrackItem],
    currentIndex: Int,
    lookaheadCount: Int
  ) -> [TrackItem] {
    if lookaheadCount <= 0 || tracks.isEmpty { return [] }
    let start = max(0, currentIndex + 1)
    if start >= tracks.count { return [] }
    let end = min(start + lookaheadCount, tracks.count)
    return tracks[start..<end].filter { Self.isRemote($0.url) }
  }

  /// Test introspection — whether the orchestrator or an in-flight
  /// download is currently active.
  @_spi(QueuePlayerTests)
  public var hasInFlightTask: Bool {
    return orchestrator != nil || inFlightTask != nil
  }

  /// Test introspection — await the orchestrator so test
  /// assertions can fire after the prefetch chain drains.
  @_spi(QueuePlayerTests)
  public func awaitCurrent() async {
    _ = await orchestrator?.value
  }

  // MARK: - Orchestrator

  /// Run `body` on the confinement queue and await its result. The orchestrator
  /// itself runs on the cooperative pool, so every touch of this object's state
  /// comes back through here. Bodies keep their own `[weak self]` capture.
  private func onQueue<T>(_ body: @escaping () -> T) async -> T {
    await withCheckedContinuation { (cont: CheckedContinuation<T, Never>) in
      queue.async { cont.resume(returning: body()) }
    }
  }

  /// Launch a fresh orchestrator if none is running. Capturing
  /// `self` weakly keeps the prefetcher releaseable mid-loop —
  /// tearDown nils the orchestrator reference and the loop body
  /// observes via `self == nil` on the next main hop.
  private func ensureOrchestratorRunning() {
    if isOrchestratorRunning {
      return
    }
    orchestratorGeneration &+= 1
    let myGeneration = orchestratorGeneration
    isOrchestratorRunning = true
    orchestrator = Task { [weak self] in
      await self?.runOrchestrator()
      await self?.onQueue { [weak self] in
        guard let self else { return }
        // Identity check: only clear ownership if this trailing hook
        // belongs to the still-current orchestrator. If a `cancel()`
        // + `reschedule()` ran between the loop body returning and
        // this hop, `orchestratorGeneration` has moved on and a fresh
        // orchestrator already owns the slot; clobbering it would
        // leak the new orchestrator out of `isOrchestratorRunning`'s
        // tracking and let a subsequent `reschedule` launch a second
        // concurrent loop.
        guard self.orchestratorGeneration == myGeneration else { return }
        self.isOrchestratorRunning = false
        self.orchestrator = nil
        // If a reschedule appended new targets between the loop's
        // body returning and this hook running, the running flag's
        // observed-as-true window let that reschedule short-circuit
        // ensureOrchestratorRunning; relaunch immediately so the
        // new targets drain.
        if !self.pendingTargets.isEmpty && !Task.isCancelled {
          self.ensureOrchestratorRunning()
        }
      }
    }
  }

  /// Drain `pendingTargets` one URL at a time. Pops the next track
  /// on its owner queue, downloads it on the cooperative pool, then loops.
  /// Exits when `pendingTargets` is empty or the Task is cancelled.
  private func runOrchestrator() async {
    while !Task.isCancelled {
      let next: TrackItem? = await onQueue { [weak self] () -> TrackItem? in
        guard let self = self else { return nil }
        if self.pendingTargets.isEmpty { return nil }
        return self.pendingTargets.removeFirst()
      }
      guard let track = next else { return }
      if Task.isCancelled { return }

      // Skip if already cached — covers the race where another
      // `reschedule` enqueued the URL just before a download via a
      // different path completed.
      if cache.isFullyCached(url: track.url) {
        await onQueue { [weak self] in self?.onTrackProcessed?() }
        continue
      }

      let headers = await onQueue { [weak self] () -> [String: String] in
        guard let self = self else { return [:] }
        return Self.merge(
          headers: self.configHeaders, userAgent: self.configUserAgent
        )
      }

      // Build the Task suspended via an explicit start barrier so
      // `inFlightUrl` / `inFlightTask` publish before the download
      // body executes. Without the barrier the eager-start Task can
      // race the queue-hop setter — observers polling the
      // currently-downloading URL after the gated downloader fires
      // its onStart can otherwise see a stale `nil`.
      let startBarrier = AsyncBarrier()
      let downloadTask = Task<Void, Error> { [cache] in
        await startBarrier.wait()
        try await cache.download(track: track, headers: headers)
      }
      await onQueue { [weak self] in
        self?.inFlightUrl = track.url
        self?.inFlightTask = downloadTask
      }
      startBarrier.release()

      do {
        _ = try await downloadTask.value
      } catch is CancellationError {
        // Cancellation drops just the slot — orchestrator carries on
        // to the next pending target on the next loop iteration.
      } catch {
        logSwallowed(track: track, error: error)
      }

      await onQueue { [weak self] in
        self?.inFlightUrl = nil
        self?.inFlightTask = nil
        self?.onTrackProcessed?()
      }
    }
  }

  // MARK: - Helpers

  private static func isRemote(_ url: String) -> Bool {
    guard let schemeEnd = url.firstIndex(of: ":") else { return false }
    let scheme = url[..<schemeEnd].lowercased()
    return scheme == "http" || scheme == "https"
  }

  private static func merge(
    headers: [String: String]?, userAgent: String?
  ) -> [String: String] {
    AVQueueBuilder.mergedHeaders(headers: headers, userAgent: userAgent)
  }

  private func logSwallowed(track: TrackItem, error: Error) {
    // A consumer who turned `allowsCellularAccess` off gets this on every
    // reschedule made while the device is on cellular. It is the policy
    // working, not a fault, so it does not go to the log.
    let nsError = error as NSError
    if nsError.domain == NSURLErrorDomain,
       nsError.code == NSURLErrorDataNotAllowed { return }
    // Log by source URL (the trustworthy field). `track.id` is
    // optional consumer data — a missing or empty value would
    // produce an unhelpful log line.
    NSLog(
      "[LookaheadCachePrefetcher] prefetch failed for %@: %@",
      track.url, String(describing: error)
    )
  }

  // MARK: - Constants

  /// Default lookahead window size. Matches Android's
  /// `LookaheadCacheWriter.DEFAULT_LOOKAHEAD_COUNT`. Runtime
  /// consumers can override the count via `setLookaheadCache`.
  public static let DEFAULT_LOOKAHEAD_COUNT: Int = 3
}

/// Internal one-shot barrier used by the orchestrator so the
/// in-flight URL publishes on the owner queue BEFORE the download body
/// executes. `release()` is idempotent + non-blocking; `wait()`
/// suspends until the first `release()` and resumes immediately
/// for any subsequent `wait()` (no producer needed).
///
/// Cross-platform parity: Android uses `CoroutineStart.LAZY` on
/// the download Deferred to achieve the same publish-before-body
/// semantics. Swift Tasks have no equivalent lazy-start option, so
/// the orchestrator constructs the Task suspended on this barrier,
/// publishes `inFlightUrl` / `inFlightTask` on the owner queue, then releases.
private final class AsyncBarrier: @unchecked Sendable {
  private let lock = NSLock()
  private var continuation: CheckedContinuation<Void, Never>?
  private var released = false

  func wait() async {
    await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in
      lock.lock()
      if released {
        lock.unlock()
        cont.resume()
        return
      }
      continuation = cont
      lock.unlock()
    }
  }

  func release() {
    lock.lock()
    if released {
      lock.unlock()
      return
    }
    released = true
    let cont = continuation
    continuation = nil
    lock.unlock()
    cont?.resume()
  }
}
