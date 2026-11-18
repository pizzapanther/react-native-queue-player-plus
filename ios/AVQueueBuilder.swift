import AVFoundation

/// Builder utility for `[AVPlayerItem]` arrays, with HTTP-header +
/// user-agent injection on remote URLs.
///
/// The `Raw`-suffix helpers take primitive arguments so they stay clear
/// of the Swift ↔ C++ interop boundary. They are gated behind
/// `@_spi(QueuePlayerTests)` so consumers who don't opt into the SPI
/// never see them — the API surface consumers care about is the
/// Nitrogen-integrated TrackPlayer methods, not this builder directly.
///
/// Constructs AVPlayerItem instances from TrackItem descriptors,
/// applying the PlayerConfig's custom HTTP headers + user agent.
///
/// Headers carry the consumer app's auth on every remote fetch —
/// asset read, lookahead-cache download, artwork fetch. An unrouted
/// asset gets them through `AVURLAssetHTTPHeaderFieldsKey` on its
/// options dictionary; a routed one is built with no asset options,
/// and the read-through server sends them on its origin leg instead.
///
/// User-Agent handling: AVURLAsset has no dedicated UA option. The
/// standard workaround is to include `User-Agent: <ua>` inside the
/// headers dictionary — AVFoundation forwards it through verbatim.
///
/// File URLs (`file://...`) skip headers entirely (local assets
/// don't do HTTP) and opt out of the lookahead cache.
public enum AVQueueBuilder {

  /// Build AVPlayerItems for every track in `tracks`, applying config
  /// headers + UA on remote URLs. Returns an empty array for an empty
  /// input. Never throws — a track whose URL does not parse is dropped
  /// from the result (`makeItem` returns nil), so the engine never sees
  /// it.
  ///
  /// With a non-nil `server`, every routable remote track (`isRoutable`)
  /// is built on the read-through server's local URL, and the server
  /// decides per request whether bytes come from the lookahead cache or
  /// the origin. `file://` tracks, `.m3u8` manifests, and every track when
  /// `server` is nil bind `track.url` directly — see `makeItem`.
  static func buildPlayerItems(
    tracks: [TrackItem],
    queueItemIds: [String],
    config: PlayerConfig,
    server: ReadThroughServer? = nil
  ) -> [AVPlayerItem] {
    // Parallel-array invariant: a violation is a programming error
    // we own (QueueState uphold-able at construction). `precondition`
    // fires in Debug + Release; soft-failing would leave caller state
    // inconsistent (player empty, `self.tracks` populated).
    precondition(
      tracks.count == queueItemIds.count,
      "AVQueueBuilder.buildPlayerItems requires queueItemIds parallel to tracks (1:1)"
    )
    return zip(tracks, queueItemIds).prefix { $0.0.isVideo != true }.compactMap { track, id in
      makeItem(for: track, queueItemId: id, config: config, server: server)
    }
  }

  /// Cancel outstanding key loads on the assets of items being discarded.
  ///
  /// `AVURLAsset.dealloc` cancels them implicitly and **blocks the deallocating
  /// thread while it does** — a dispatch-group wait on the asset inspector,
  /// measured at tens of seconds for a queue of 200 with loads in flight.
  /// Cancelling first makes that wait a no-op. That thread is
  /// `playerQueue`, so the block stalls every queue operation behind it.
  ///
  /// Only assets that are genuinely being dropped may be cancelled: a
  /// cancelled asset can never become playable, so an item rebuilt on a reused
  /// asset would stall silently. `keeping` is the set that survives, compared
  /// by identity.
  static func cancelLoading(dropping outgoing: [AVPlayerItem], keeping: [AVPlayerItem] = []) {
    for item in itemsBeingDropped(outgoing: outgoing, keeping: keeping) {
      item.asset.cancelLoading()
    }
  }

  /// The items in `outgoing` that no entry of `keeping` reuses. Identity, not
  /// equality: two items can wrap the same asset, and it is the asset's
  /// survival that decides whether cancelling it is safe.
  @_spi(QueuePlayerTests)
  public static func itemsBeingDropped(
    outgoing: [AVPlayerItem], keeping: [AVPlayerItem]
  ) -> [AVPlayerItem] {
    guard !keeping.isEmpty else { return outgoing }
    let survivingAssets = keeping.map { ObjectIdentifier($0.asset) }
    let surviving = Set(survivingAssets)
    return outgoing.filter { !surviving.contains(ObjectIdentifier($0.asset)) }
  }

  /// Single-item builder behind `buildPlayerItems`. Routes a routable
  /// remote track through `server` when one is supplied.
  ///
  /// `queueItemId` is the lib-generated identity for this AVPlayerItem
  /// instance — set on the constructed item via the
  /// `AVPlayerItem.queueItemId` associated-object extension.
  /// `TrackPlayer` owns the parallel `queueItemIds: [String]` array
  /// (1:1 with `self.tracks`) and pre-allocates a UUID per queue
  /// position; the value passed here is the corresponding entry.
  /// Never derived from any consumer-supplied TrackItem field —
  /// only `track.url` is required + reliable on consumer data.
  ///
  /// The consumer's ReplayGain claim over [track] is resolved and
  /// validated here, once, and stamped on the item alongside those two.
  /// The item is the only thing the audio path holds, and this is the
  /// one place that has both it and the `TrackItem` it came from.
  static func makeItem(
    for track: TrackItem,
    queueItemId: String,
    config: PlayerConfig,
    server: ReadThroughServer? = nil
  ) -> AVPlayerItem? {
    guard track.isVideo != true else { return nil }
    // Remote tracks bind the read-through server's URL, which is stable for
    // the session and never re-pointed. Whether the bytes come from the cache
    // or the origin is the server's decision, made per request — the only
    // moment it can be answered, because the cache fills long after the queue
    // is built. Headers move to the origin leg with it.
    //
    // HLS is excluded: proxying a playlist would re-base its relative segment
    // paths against the local URL. No server means no routing, which is what a
    // consumer running without the lookahead cache gets.
    let routed: URL? = Self.isRoutable(track.url) ? server.flatMap { srv in
      URL(string: track.url).flatMap { srv.localURL(for: $0, sourceURL: track.url) }
    } : nil

    let item: AVPlayerItem?
    if let routed {
      let asset = AVURLAsset(url: routed)
      let built = AVPlayerItem(
        asset: asset,
        automaticallyLoadedAssetKeys: ["playable", "duration"]
      )
      pinForwardBufferDuration(on: built)
      built.isRoutedThroughReadThroughServer = true
      item = built
    } else {
      item = makeItemRaw(
        urlString: track.url,
        headers: config.httpHeaders,
        userAgent: config.userAgent)
    }
    item?.queueItemId = queueItemId
    // The media's own URL, always — never the transport's. Everything that
    // reads a container, a codec or a track identity out of a URL reads this.
    item?.sourceURL = track.url
    item?.suppliedReplayGain = SuppliedReplayGain.from(track: track)
    return item
  }

  /// Whether a URL is one the read-through server can carry.
  ///
  /// `http(s)` only, and not a manifest: AVFoundation resolves an HLS
  /// playlist's relative segment paths against the URL it fetched the playlist
  /// from, so a proxied playlist would point every segment at the local server
  /// under a token that names none of them.
  static func isRoutable(_ urlString: String) -> Bool {
    guard let url = URL(string: urlString), let scheme = url.scheme?.lowercased(),
          scheme == "http" || scheme == "https"
    else { return false }
    return url.pathExtension.lowercased() != "m3u8"
  }


  /// Apple-acknowledged AVPlayer mitigation: explicitly setting
  /// `preferredForwardBufferDuration = 0.0` on every item closes a
  /// long-standing AVQueuePlayer bug where items can be dropped
  /// from the queue (or chain-advanced through while paused) when
  /// the player decides duration is "exhausted" for an asset whose
  /// HTTP load hasn't started yet. Apple Dev Forum thread 683744 +
  /// FB9221518 — leaving the value at its implicit default exposes
  /// the bug; explicit 0.0 (functionally the same default value,
  /// but a different code path inside AVPlayer) does not.
  ///
  /// Applied uniformly to local + remote items because the bug is
  /// independent of asset URL scheme; local file:// items are also
  /// vulnerable when AVQueuePlayer is mid-skip. Exposed via SPI so
  /// the AVQueueBuilderTests XCTarget can assert it directly on
  /// `makeItemRaw`-produced items.
  @_spi(QueuePlayerTests)
  public static func pinForwardBufferDuration(on item: AVPlayerItem) {
    item.preferredForwardBufferDuration = 0.0
  }

  /// Plain-Swift item builder. Takes primitive arguments so it stays clear
  /// of the Swift ↔ C++ interop boundary; `makeItem(for:, config:)` is the
  /// thin adapter that unpacks the Nitrogen-generated `TrackItem` +
  /// `PlayerConfig` and delegates here.
  ///
  /// Gated behind `@_spi(QueuePlayerTests)` so it stays off the library's
  /// consumer-facing public surface.
  @_spi(QueuePlayerTests)
  public static func makeItemRaw(
    urlString: String,
    headers: [String: String]?,
    userAgent: String?
  ) -> AVPlayerItem? {
    guard let url = URL(string: urlString) else { return nil }

    let asset: AVURLAsset
    if url.isFileURL {
      asset = AVURLAsset(url: url)
    } else {
      asset = AVURLAsset(
        url: url,
        options: httpOptionsRaw(headers: headers, userAgent: userAgent))
    }

    // Apple-preferred AVPlayerItem construction pattern (iOS 16+):
    // pre-load `playable` + `duration` keys asynchronously so the
    // first frame is reachable without the AVPlayerItem
    // round-tripping a synchronous load on first access.
    let item = AVPlayerItem(
      asset: asset,
      automaticallyLoadedAssetKeys: ["playable", "duration"]
    )
    pinForwardBufferDuration(on: item)
    return item
  }

  /// The consumer's headers with `userAgent` folded in, which is the request
  /// shape every outbound leg uses: asset loads, cache prefetches, and the
  /// read-through server's origin requests.
  @_spi(QueuePlayerTests)
  public static func mergedHeaders(
    headers: [String: String]?,
    userAgent: String?
  ) -> [String: String] {
    var merged: [String: String] = headers ?? [:]
    if let ua = userAgent, !ua.isEmpty {
      merged["User-Agent"] = ua
    }
    return merged
  }

  /// Plain-Swift options-dictionary builder — takes primitive arguments so
  /// it stays clear of the Swift↔C++ interop boundary.
  /// `makeItemRaw(urlString:headers:userAgent:)`
  /// calls this, and `makeItem(for:, config:)` unpacks its `PlayerConfig`
  /// argument into the same primitive pair and calls `makeItemRaw`.
  ///
  /// Gated behind `@_spi(QueuePlayerTests)` so it stays off the library's
  /// consumer-facing public surface.
  ///
  /// Key spelling: `AVURLAssetHTTPHeaderFieldsKey` is not a public
  /// Swift API. The raw Obj-C string still works at runtime. See
  /// NOTES.md section 7.
  ///
  /// No `AVAssetResourceLoaderDelegate` is installed, and none will be: an
  /// asset routed through a loader takes a custom URL scheme, and Apple states
  /// AirPlay is unsupported for one. Remote tracks reach the lookahead cache
  /// through a local HTTP server instead, which keeps AVFoundation's own HTTP
  /// stack — including the redirect following a loader would have to
  /// reimplement.
  @_spi(QueuePlayerTests)
  public static func httpOptionsRaw(
    headers: [String: String]?,
    userAgent: String?
  ) -> [String: Any] {
    let merged = mergedHeaders(headers: headers, userAgent: userAgent)
    guard !merged.isEmpty else { return [:] }
    return ["AVURLAssetHTTPHeaderFieldsKey": merged]
  }
}
