import Foundation

/// Transport-side shim — `TrackPlayer`'s per-call entry point for
/// "should this command route to the receiver, or hit the local
/// engine?". Each `routeXxx` helper returns `true` when the command
/// was accepted by a live remote session (caller skips local
/// handling); `false` when there's no active remote (caller proceeds
/// locally).
///
/// Reads the active remote through
/// `PlaybackStateRouter.shared.activeRemote()`. Snapshot semantics —
/// by the time the local fallback runs, the session may have ended,
/// but the local path is the right thing for the next call anyway.
///
/// Mirrors Android's `internal object CastTransportRouter`.
enum CastTransportRouter {

  /// Hands a receiver command to main. The Cast SDK is main-only, while the
  /// decision above it — whether a remote session is live — reads a
  /// lock-guarded snapshot and is safe from any thread, so only the command
  /// itself crosses.
  /// Always asynchronous, never inline-when-already-on-main: mixing the two
  /// lets a command issued later run before one issued earlier — a CarPlay tap
  /// arriving on main would overtake a JS seek that is still enqueued.
  private static func onMain(_ body: @escaping () -> Void) {
    DispatchQueue.main.async(execute: body)
  }

  static func routePlay() -> Bool {
    guard let remote = PlaybackStateRouter.shared.activeRemote() else { return false }
    onMain { remote.play() }
    return true
  }

  static func routePause() -> Bool {
    guard let remote = PlaybackStateRouter.shared.activeRemote() else { return false }
    onMain { remote.pause() }
    return true
  }

  static func routeStop() -> Bool {
    guard let remote = PlaybackStateRouter.shared.activeRemote() else { return false }
    onMain { remote.stop() }
    return true
  }

  static func routeSeekTo(positionMs: Int64) -> Bool {
    guard let remote = PlaybackStateRouter.shared.activeRemote() else { return false }
    onMain { remote.seekTo(positionMs: positionMs) }
    return true
  }

  /// Interval-skip helper: relative-seek the receiver by `deltaMs` (positive =
  /// forward) via the Cast SDK's native relative seek, so a lock-screen
  /// skip-forward/back during a Chromecast session jumps from the receiver's
  /// own position. Returns `true` when a remote handled it (no active GCK
  /// session for AirPlay/local, where the caller seeks the local engine).
  static func routeSeekBy(deltaMs: Int64) -> Bool {
    guard let remote = PlaybackStateRouter.shared.activeRemote() else { return false }
    onMain { remote.seekBy(deltaMs: deltaMs) }
    return true
  }

  static func routeSkipToNext() -> Bool {
    guard let remote = PlaybackStateRouter.shared.activeRemote() else { return false }
    onMain { remote.queueNext() }
    return true
  }

  static func routeSkipToPrevious() -> Bool {
    guard let remote = PlaybackStateRouter.shared.activeRemote() else { return false }
    onMain { remote.queuePrev() }
    return true
  }

  static func routeSkipToIndex(index: Int) -> Bool {
    guard let remote = PlaybackStateRouter.shared.activeRemote() else { return false }
    onMain { remote.skipToQueueIndex(index: index) }
    return true
  }

  static func routeSetVolume(value: Double) -> Bool {
    guard let remote = PlaybackStateRouter.shared.activeRemote() else { return false }
    let clamped = Float(max(0.0, min(1.0, value)))
    onMain { remote.setVolume(clamped) }
    return true
  }

  /// `setQueue` awaits the receiver's `load()` round-trip. Returns the
  /// receiver itemIds (in `tracks` order) as the load's success signal
  /// when the remote handled it; `nil` when no remote is active. Throws
  /// when remote was active but the load failed — caller decides whether
  /// to fall through to local or surface the error.
  static func routeSetQueue(
    tracks: [CastMediaItem],
    startIndex: Int,
    startPositionMs: Int64,
    playWhenReady: Bool
  ) async throws -> [Int]? {
    guard let remote = PlaybackStateRouter.shared.activeRemote() else { return nil }
    return try await remote.load(
      items: tracks,
      startIndex: startIndex,
      startPositionMs: startPositionMs,
      playWhenReady: playWhenReady
    )
  }

  // Queue mutations mirror to the receiver while the local queue stays the
  // source of truth for the consumer's UI. `false` when no remote is active
  // (the caller's local mutation is the only effect). Receiver errors are
  // swallowed — a failed mirror must not fail the local mutation.

  static func routeAddToQueue(items: [CastMediaItem], beforeIndex: Int?) async -> Bool {
    guard let remote = PlaybackStateRouter.shared.activeRemote() else { return false }
    try? await remote.queueInsert(items: items, beforeIndex: beforeIndex)
    return true
  }

  static func routeRemoveFromQueue(indices: [Int]) async -> Bool {
    guard let remote = PlaybackStateRouter.shared.activeRemote() else { return false }
    try? await remote.queueRemove(indices: indices)
    return true
  }

  static func routeMoveInQueue(fromIndex: Int, toIndex: Int) async -> Bool {
    guard let remote = PlaybackStateRouter.shared.activeRemote() else { return false }
    try? await remote.queueMove(fromIndex: fromIndex, toIndex: toIndex)
    return true
  }

  static func routeClearQueue() async -> Bool {
    guard let remote = PlaybackStateRouter.shared.activeRemote() else { return false }
    try? await remote.queueClear()
    return true
  }
}

extension CastMediaItem {
  /// Translate a `TrackItem` into a `CastMediaItem`. Consumer-supplied
  /// fields are best-effort — only `track.url` is treated as required.
  /// Empty / unparseable `track.url` returns `nil` so the caller can
  /// drop the entry or surface an error instead of pushing a bogus
  /// `file:///` to the receiver.
  static func from(track: TrackItem) -> CastMediaItem? {
    guard let url = URL(string: track.url), !track.url.isEmpty else {
      return nil
    }
    let title = trimmedOrNil(track.title)
    let artist = trimmedOrNil(track.artist)
    let album = trimmedOrNil(track.album)
    let artwork = trimmedOrNil(track.artworkUrl).flatMap { URL(string: $0) }
    let durationMs: Int64?
    if let dur = track.duration, dur > 0 {
      durationMs = Int64(dur * 1000.0)
    } else {
      durationMs = nil
    }
    return CastMediaItem(
      url: url,
      title: title,
      artist: artist,
      album: album,
      artwork: artwork,
      durationMs: durationMs,
      contentType: nil
    )
  }

  private static func trimmedOrNil(_ s: String?) -> String? {
    guard let s = s, !s.isEmpty else { return nil }
    return s
  }
}
