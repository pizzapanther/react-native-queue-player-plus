import AVFoundation

/// Lib-generated unique identifier per AVPlayerItem instance.
///
/// Set by `AVQueueBuilder.makeItem` at construction to a fresh
/// UUID supplied by the caller (`TrackPlayer` pre-allocates one
/// per queue position alongside `self.tracks`). Used by
/// `TrackPlayer.matchTrackIndex` (paired with the `queueItemIds`
/// parallel array) to resolve `player.currentItem` back to a
/// position in `self.tracks`.
///
/// **CRITICAL — never derive from consumer-supplied data.** Only
/// `track.url` is required + reliable on `TrackItem`. Every other
/// consumer field — `id`, `title`, `artist`, `album`, `duration`,
/// `artworkUrl`, `extras` — may be empty / missing / duplicate /
/// colliding. Internal identity keys must be lib-generated. The
/// naming `queueItemId` (not `trackId`) is deliberate: it signals
/// lib-internal-and-per-item purpose, distinguishing from the
/// consumer-facing `track.id`.
///
/// Why associated-object: AVPlayerItem is an NSObject subclass
/// with no Swift stored properties for arbitrary metadata.
/// Subclassing is heavy + brittle (would need to route every API
/// returning AVPlayerItem through a custom-subclass cast). The
/// Obj-C runtime API (imported into Swift via Foundation) is the
/// standard idiom — no `.m` / `.h` source files introduced.
private var queueItemIdKey: UInt8 = 0
private var sourceURLKey: UInt8 = 0
private var routedThroughReadThroughServerKey: UInt8 = 0

/// `nonisolated` throughout: the SDK marks `AVPlayerItem` main-actor isolated
/// because `asset` is not Sendable, but these stamps are written once at
/// construction and read on the player queue.
extension AVPlayerItem {
  /// Lib-internal identity for this AVPlayerItem. Set once at
  /// construction by `AVQueueBuilder.makeItem`; never mutated
  /// after. nil only for AVPlayerItems that did not flow through
  /// the lib's queue construction path (e.g. built directly via
  /// `makeItemRaw`).
  nonisolated var queueItemId: String? {
    get { objc_getAssociatedObject(self, &queueItemIdKey) as? String }
    set {
      objc_setAssociatedObject(
        self, &queueItemIdKey, newValue, .OBJC_ASSOCIATION_RETAIN_NONATOMIC)
    }
  }

  /// The exact `track.url` string this item was built from, stored
  /// verbatim. A routed item's `AVURLAsset.url` is the read-through
  /// server's `127.0.0.1` address and an unrouted one is a
  /// parse/re-serialize round-trip of the original, so anything that
  /// reads a container, codec or track identity out of a URL reads this:
  /// `NowPlayingFormatExtractor`, `AssetReplayGainReader`, the error
  /// `url` field, and the source diagnostic log.
  nonisolated var sourceURL: String? {
    get { objc_getAssociatedObject(self, &sourceURLKey) as? String }
    set {
      objc_setAssociatedObject(
        self, &sourceURLKey, newValue, .OBJC_ASSOCIATION_RETAIN_NONATOMIC)
    }
  }

  /// Whether this item's asset URL is the read-through server's loopback
  /// address rather than `sourceURL` itself. Set once by
  /// `AVQueueBuilder.makeItem`; the routing decision is made at build time
  /// and an item is never re-pointed, so the stamp is what source
  /// classification reads instead of guessing from the server's presence.
  nonisolated var isRoutedThroughReadThroughServer: Bool {
    get { (objc_getAssociatedObject(self, &routedThroughReadThroughServerKey) as? Bool) ?? false }
    set {
      objc_setAssociatedObject(
        self, &routedThroughReadThroughServerKey, newValue, .OBJC_ASSOCIATION_RETAIN_NONATOMIC)
    }
  }
}
