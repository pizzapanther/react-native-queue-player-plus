import AVFoundation
import Foundation
import NitroModules

extension TrackPlayer {
  // MARK: - Lookahead cache — runtime config + status surface
  func setLookaheadCache(config: LookaheadCacheConfig) throws -> Promise<Void> {
    return enqueueThrowing {
      try self.applyLookaheadConfig(config)
    }
  }

  func getLookaheadCacheStatus() throws -> Promise<CacheStatus> {
    // The snapshot counts fully-cached entries, which is a filesystem check
    // per entry — the Promise keeps that work off the calling JS thread.
    return enqueueValue { self.buildCacheStatusSnapshot() }
  }

  func clearLookaheadCache() throws -> Promise<Void> {
    return enqueue {
      // Cancel any in-flight prefetch + drop everything on disk.
      // We deliberately do NOT call rescheduleLookahead() here —
      // an immediate refill makes `clearLookaheadCache` impossible
      // to observe at zero from JS, and "clear" semantically means
      // "wipe and stop" (the consumer can re-trigger prefetch by
      // calling setLookaheadCache, skipToIndex, or setQueue again).
      self.lookaheadCachePrefetcher?.cancel()
      self.lookaheadCache?.clear()
      self.emitCacheStatus()
    }
  }

  func onCacheStatusChange(callback: @escaping (_ status: CacheStatus) -> Void) throws -> () -> Void {
    // ListenerRegistry.add is internally serialized, so a JS consumer
    // that does
    //   const unsub = onCacheStatusChange(cb)
    //   await setLookaheadCache(cfg)
    // is guaranteed to see the post-config-change emit (the
    // setLookaheadCache call synchronously crosses the bridge after
    // the listener is in the registry).
    let id = cacheStatusListeners.add(callback)
    // Deliver the current status once. Otherwise a settings screen shows its
    // placeholder until the next prefetch completes — which never happens for a
    // fully-cached queue or a consumer running with prefetch off.
    playerQueue.async { [weak self] in
      guard let self else { return }
      guard self.cacheStatusListeners.contains(id: id) else { return }
      callback(self.buildCacheStatusSnapshot())
    }
    return { [weak self] in self?.cacheStatusListeners.remove(id: id) }
  }

  /// Apply a runtime config update. All three runtime dimensions are
  /// live (no configure() needed):
  /// - `enabled` toggle: build/teardown the prefetcher against the cache
  /// - `lookaheadCount` change: live update on the prefetcher
  /// - `allowsCellularAccess` toggle: live on the cache, applied per request
  ///
  /// Cache SIZE + eviction policy are NOT runtime dimensions — they are fixed
  /// at `configure()` from `PlayerConfig` (kept consistent with Android, whose
  /// SimpleCache is immutable after construction).
  internal func applyLookaheadConfig(_ config: LookaheadCacheConfig) throws {
    let previous = self.lookaheadConfig
    self.lookaheadConfig = config

    let enableChanged = config.enabled != previous.enabled
    let countChanged = config.lookaheadCount != previous.lookaheadCount

    if enableChanged {
      rebuildCacheStack(config: config)
    } else if config.enabled {
      applyLiveConfigUpdates(config: config, countChanged: countChanged)
    }

    // The cache applies this per request, so it lands whether or not the
    // branches above ran. When there is no cache yet the stored config is what
    // the next construction reads.
    lookaheadCache?.allowsCellularAccess = config.allowsCellularAccess ?? true

    emitCacheStatus()
  }

  /// Configure-time cache disk budget in MB — `PlayerConfig.lookaheadCacheMaxSizeMb`
  /// or the 100 MB default. Fixed at cache construction; reported read-only via
  /// `getLookaheadCacheStatus().maxSizeMb`.
  internal func configuredCacheMaxSizeMb() -> Double {
    self.config.lookaheadCacheMaxSizeMb
      ?? Double(LookaheadCache.DEFAULT_MAX_SIZE_BYTES) / Double(LookaheadCache.BYTES_PER_MB)
  }

  /// Configure-time cache eviction policy — `PlayerConfig.lookaheadCacheEvictionPolicy`
  /// or `.lru`. Fixed at cache construction; a change at `configure()` clears the
  /// cache and rebuilds under the new policy (see `configure`).
  internal func configuredEvictionPolicy() -> EvictionPolicy {
    self.config.lookaheadCacheEvictionPolicy ?? .lru
  }

  /// Bring up the server that reads through `cache`. Called from both sites
  /// that build a cache — `configure()` and the runtime `setLookaheadCache`
  /// — because a cache without a server is one nothing ever reads from. A
  /// server already reading through this same cache instance is kept, its
  /// headers refreshed: replacing it would change the port seated items
  /// address. A kept server is probed and restarted if it does not answer
  /// (`reviveReadThroughServerIfDead`). Blocks for the bind (milliseconds) so every item built after
  /// this call routes; an item built before the bind is bound to its origin.
  internal func startReadThroughServer(for cache: LookaheadCache) {
    if let existing = readThroughServer, readThroughServerCache === cache {
      existing.setHeaders(
        AVQueueBuilder.mergedHeaders(
          headers: self.config.httpHeaders, userAgent: self.config.userAgent))
      // Kept, but not trusted: a listener the OS reclaimed still reports a port.
      reviveReadThroughServerIfDead()
      reinstallAfterReadThroughRestart()
      return
    }
    readThroughServer?.stop()
    let server = ReadThroughServer(
      cachedFileForOrigin: { [weak cache] sourceURL in
        cache?.cachedURL(forUrl: sourceURL)
      },
      onFirstDiskHit: { [weak cache] sourceURL in
        cache?.touch(url: sourceURL)
      }
    )
    server.setHeaders(
      AVQueueBuilder.mergedHeaders(
        headers: self.config.httpHeaders, userAgent: self.config.userAgent))
    server.start()
    if !server.waitUntilBound() {
      NSLog("[QueuePlayer] read-through server did not bind; remote tracks play from their origin")
    }
    self.readThroughServer = server
    self.readThroughServerCache = cache
  }

  /// Probe the read-through server and restart it when it does not answer.
  /// Returns true when it restarted. Never rebuilds: the caller decides whether
  /// installed items need `reinstallAfterReadThroughRestart()`.
  ///
  /// iOS can reclaim a suspended app's listening socket while the listener
  /// keeps reporting `.ready`, so `boundPort` alone says nothing. Skipped while
  /// audio plays: an app rendering audio is not suspended, and a restart
  /// cancels the server's open connections. Either leg counts, because
  /// mid-crossfade `timeControlStatus` reads the incoming leg and `isPlaying`
  /// the outgoing one. Caller is on `playerQueue`.
  @discardableResult
  internal func reviveReadThroughServerIfDead() -> Bool {
    guard let server = readThroughServer else { return false }
    if let engine = self.engine,
       engine.timeControlStatus == .playing || engine.isPlaying {
      return false
    }
    guard !server.isAnswering() else { return false }
    server.restart()
    // Only routed items address the server; a run of origin-bound items, as
    // a disabled cache builds, keeps its buffer.
    if self.engine?.allMediaItems.contains(where: { $0.isRoutedThroughReadThroughServer }) == true {
      readThroughReinstallPending = true
    }
    return true
  }

  /// Reinstall the run when a restart left items that may address the replaced
  /// listener. Keeps `reportedFailureQueueItemIds`, which the forced rebuild
  /// empties: this runs without the user asking for a failed track again, so a
  /// reported track stays held and its next failure stays unreported, as
  /// `stopOnDroppedTrack` does. Not while audio plays, which a reinstall would
  /// cut: the flag stays set for the next trigger. Caller is on `playerQueue`.
  internal func reinstallAfterReadThroughRestart() {
    guard readThroughReinstallPending, let engine = self.engine,
          engine.timeControlStatus != .playing, !engine.isPlaying,
          !self.reachedQueueEnd,
          self.currentTrackIndex >= 0, self.currentTrackIndex < self.tracks.count
    else { return }
    let reported = self.reportedFailureQueueItemIds
    self.performingMutation { self.fullRebuildPlayerQueue(forcingReinstall: true) }
    self.reportedFailureQueueItemIds = reported
  }

  /// Whether a routed item was built against a listener that has since been
  /// replaced: its URL names a port the server no longer listens on.
  internal func addressesReplacedListener(_ item: AVPlayerItem) -> Bool {
    guard let server = readThroughServer,
          let port = (item.asset as? AVURLAsset)?.url.port else { return false }
    return port != Int(server.boundPortValue())
  }

  /// Quiesce or re-arm the cache, prefetcher and read-through server
  /// when the `enabled` flag flipped. Disable cancels in-flight prefetch
  /// and quiesces the cache (URLSession STAYS alive — it is only invalidated
  /// by full `destroy`); the server stays up too, serving seated items from
  /// the origin while the cache answers nothing, and `routingServer` binds
  /// new items to their origin. Enable rebuilds the prefetcher (or constructs
  /// the pair from scratch on first enable), keeps the server and reschedules.
  ///
  /// Keeping the cache instance + URLSession alive across disable
  /// avoids `Task created in a session that has been invalidated`
  /// (Obj-C exception, uncatchable in Swift's `do-catch`) when a
  /// prefetcher Task survived disable + tried to download against
  /// a freshly-invalidated session before the dispatch barrier saw
  /// the cancellation.
  private func rebuildCacheStack(config: LookaheadCacheConfig) {
    if !config.enabled {
      lookaheadCachePrefetcher?.tearDown()
      lookaheadCachePrefetcher = nil
      lookaheadCache?.disable()
      return
    }
    if lookaheadCache == nil {
      // Size is fixed at construction from the configure-time budget.
      lookaheadCache = LookaheadCache(
        maxSizeBytes: LookaheadCache.maxBytes(forMb: configuredCacheMaxSizeMb()),
        evictionPolicy: configuredEvictionPolicy(),
        allowsCellularAccess: config.allowsCellularAccess ?? true
      )
    } else {
      lookaheadCache?.enable()
    }
    guard let cache = lookaheadCache else { return }
    // The cache is built here as well as at `configure()`, and a cache with no
    // server is a cache nothing reads: every item would bind its origin and
    // route past it. Whatever builds the cache brings the server with it.
    startReadThroughServer(for: cache)
    let prefetcher = LookaheadCachePrefetcher(
      cache: cache,
      confinedTo: self.playerQueue,
      configHeaders: self.config.httpHeaders,
      configUserAgent: self.config.userAgent
    )
    prefetcher.defaultLookaheadCount = InputGuards.validLookaheadCount(config.lookaheadCount)
    prefetcher.onTrackProcessed = { [weak self] in self?.emitCacheStatus() }
    lookaheadCachePrefetcher = prefetcher
    rescheduleLookahead()
  }

  /// Apply in-place config updates when the cache stack already exists
  /// (no enable-flag change). Only `lookaheadCount` reaches here — it updates
  /// the prefetcher's default + reschedules. `allowsCellularAccess` is set on
  /// the cache by the caller, and cache size is configure-time.
  private func applyLiveConfigUpdates(
    config: LookaheadCacheConfig, countChanged: Bool
  ) {
    if countChanged {
      lookaheadCachePrefetcher?.defaultLookaheadCount =
        InputGuards.validLookaheadCount(config.lookaheadCount)
      rescheduleLookahead()
    }
  }

  /// Build a [CacheStatus] snapshot from current cache + prefetcher
  /// state. Synchronous — the caller is already on the queue that serialises
  /// player state.
  internal func buildCacheStatusSnapshot() -> CacheStatus {
    // `currentlyDownloadingUrl` is prefetcher state confined to `playerQueue`,
    // so it is read here and handed to the builder as a value.
    return buildCacheStatusSnapshot(
      inFlightUrl: lookaheadCachePrefetcher?.currentlyDownloadingUrl)
  }

  /// [CacheStatus] from cache + config state plus the caller-supplied
  /// in-flight prefetch URL. Reads no prefetcher state of its own.
  internal func buildCacheStatusSnapshot(inFlightUrl: String?) -> CacheStatus {
    let cache = self.lookaheadCache
    let currentBytes = cache?.currentSizeBytes() ?? 0
    // Queue-agnostic: count fully-cached entries in the cache itself,
    // not the intersection with `self.tracks`. Mirrors the semantics
    // of `currentSizeBytes` (total cache disk usage) and the
    // prefetcher's `currentlyDownloadingUrl` (active downloads) — all
    // three reflect cache state independent of any current queue.
    // Per-track local/stream/cache attribution for the active queue
    // is exposed via `getCurrentTrackSource()`.
    let fullyCached = cache?.fullyCachedCount() ?? 0
    return CacheStatus(
      enabled: lookaheadConfig.enabled,
      currentSizeMb: Double(currentBytes) / Double(LookaheadCache.BYTES_PER_MB),
      maxSizeMb: configuredCacheMaxSizeMb(),
      tracksFullyCached: Double(fullyCached),
      currentlyCaching: inFlightUrl.map { [$0] } ?? []
    )
  }

  /// Fire the cache-status callback with a fresh snapshot. Caller
  /// must already be on the queue that serialises player state.
  internal func emitCacheStatus() {
    guard !cacheStatusListeners.isEmpty else { return }
    let snapshot = buildCacheStatusSnapshot()
    cacheStatusListeners.forEach { $0(snapshot) }
  }
}
