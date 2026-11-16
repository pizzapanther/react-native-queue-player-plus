import Foundation

/// Receiver-queue repeat mode. Maps `MediaStatus.REPEAT_MODE_*` on
/// Android and `GCKMediaRepeatMode*` on iOS onto one enum.
/// `ALL_AND_SHUFFLE` intentionally absent — shuffle is a sender-side
/// concept exposed via the lib's existing playback-mode surface.
enum RemoteRepeatMode {
  case off, single, all
}

/// Live cast session — what cast backends mint when a receiver
/// connects. Two paradigms mirror the Android sealed class:
///
/// - **PcmStream**: sender keeps decoding and pushes PCM frames into
///   an `AudioSink` (AirPlay). Not yet implemented on iOS.
/// - **RemotePlayer**: receiver decodes; the sender forwards transport
///   commands. Chromecast (today) + future DLNA/Sonos backends.
protocol CastSession: AnyObject {
  /// `true` when the backend expects the sender to keep decoding and
  /// push PCM frames into a sink; `false` when the receiver pulls +
  /// decodes itself. Read by `CastTransportRouter` to decide whether
  /// to intercept `TrackPlayer.*` calls or let the local engine
  /// handle them.
  var isPushSink: Bool { get }

  /// Adjust receiver volume `0.0...1.0`. No-op when not supported.
  func setVolume(_ value: Float)

  /// Tear down. Safe to call multiple times.
  func close()
}

/// Paradigm B — backend is a remote player. Chromecast and future
/// DLNA / Sonos backends implement.
///
/// State, position, and queue snapshots are exposed as `last*` getters
/// (read at any time) plus `add*Listener` methods that register a
/// multi-callback and return a disposer. `CastEventBridge` subscribes
/// for the JS-event fan-out; `CastNowPlayingController` (7C.2) will
/// subscribe alongside for the lockscreen mirror. The session is
/// multi-listenable to support concurrent subscribers without forcing
/// one to multiplex for the others.
protocol CastSessionRemotePlayer: CastSession {

  /// Snapshot of most-recent receiver-driven state.
  var lastState: RemotePlaybackStateKind { get }

  /// `true` once the receiver has reported any state other than `.idle`.
  /// Set by the first such report and stays set for the session's lifetime;
  /// nothing clears it.
  ///
  /// State reads use this to decide whether the receiver can answer yet: a
  /// session that has just activated still holds its initial `.idle`, and the
  /// local engine is the better answer until the receiver speaks. Once it
  /// has, the receiver is authoritative — including a later `.idle`, which
  /// means it genuinely has nothing loaded.
  var hasReportedState: Bool { get }

  /// Most-recent receiver-reported stream position in milliseconds.
  /// `0` before the first status arrives.
  var lastPositionMs: Int64 { get }

  /// Most-recent receiver-reported stream duration in milliseconds.
  /// `0` for unknown / unbounded (e.g. live streams).
  var lastDurationMs: Int64 { get }

  /// Most-recent receiver-reported buffered position in milliseconds.
  /// Often tracks `lastPositionMs` closely for Chromecast — the SDK
  /// doesn't surface buffer-ahead as a first-class signal.
  var lastBufferedMs: Int64 { get }

  /// Most-recent ABSOLUTE index of the receiver's current item in the
  /// full queue (lib-track index), resolved by the backend against the
  /// whole queue (`GCKMediaQueue`) rather than the receiver's ~3-item
  /// status window. `-1` before the first resolvable status.
  var lastCurrentTrackIndex: Int { get }

  /// Most-recent receiver-reported device volume `0.0...1.0`, or
  /// `Float.nan` before the first `MediaStatus` has been seen.
  var lastVolume: Float { get }

  /// Subscribe to receiver-state transitions. Disposer removes the
  /// listener. Listener fires on every state change pushed by the
  /// receiver — not invoked on subscribe; read `lastState` for the
  /// current snapshot.
  func addStateListener(_ cb: @escaping (RemotePlaybackStateKind) -> Void) -> () -> Void

  /// Subscribe to position / duration / buffered ticks (typically
  /// 1 Hz). Callback receives `(positionMs, durationMs, bufferedMs)`.
  func addPositionListener(_ cb: @escaping (Int64, Int64, Int64) -> Void) -> () -> Void

  /// Subscribe to absolute-current-track-index changes — fires when the
  /// receiver advances to a different queue item, carrying the lib-track
  /// index resolved against the full queue (see `lastCurrentTrackIndex`).
  func addCurrentTrackIndexListener(_ cb: @escaping (Int) -> Void) -> () -> Void

  /// Subscribe to "track ended on the receiver" events.
  func addEndedReasonListener(_ cb: @escaping (RemoteEndReasonKind) -> Void) -> () -> Void

  /// Subscribe to receiver-reported media errors.
  func addMediaErrorListener(_ cb: @escaping (RemoteMediaErrorPayload) -> Void) -> () -> Void

  /// Subscribe to seek-completion events with the resolved position
  /// (ms).
  func addSeekCompletedListener(_ cb: @escaping (Int64) -> Void) -> () -> Void

  /// Subscribe to device-volume changes. Fires whenever the receiver
  /// reports a different `streamVolume` (dedup'd by the implementing
  /// session against the last emitted value).
  func addVolumeListener(_ cb: @escaping (Float) -> Void) -> () -> Void

  // MARK: - Transport (fire-and-forget)

  func play()
  func pause()
  func stop()
  func seekTo(positionMs: Int64)
  /// Seek the receiver by `deltaMs` relative to its current stream position
  /// (positive = forward). Uses the Cast SDK's native relative seek so the
  /// receiver computes the target — no reliance on the sender's polled
  /// position snapshot.
  func seekBy(deltaMs: Int64)
  func queueNext()
  func queuePrev()
  /// Jump the receiver to the queue item at absolute lib-track `index`.
  /// The backend resolves `index` to the receiver itemId against the full
  /// queue (`GCKMediaQueue`), so callers pass lib indices, never opaque
  /// receiver itemIds. Out-of-range / unresolved indices are a no-op.
  func skipToQueueIndex(index: Int)
  func setPlaybackRate(_ rate: Float)
  func setRepeatMode(_ mode: RemoteRepeatMode)

  // MARK: - Queue mutations (suspending)

  /// Push the full `items` queue to the receiver starting at
  /// `startIndex` + `startPositionMs`, beginning playback only when
  /// `playWhenReady` is true (a handoff from a paused local session
  /// starts the receiver paused at that position). Returns the receiver
  /// itemIds in the same order as `items` as the load's success signal.
  func load(
    items: [CastMediaItem],
    startIndex: Int,
    startPositionMs: Int64,
    playWhenReady: Bool
  ) async throws -> [Int]

  /// Insert `items` before the queue item at absolute lib-track
  /// `beforeIndex` (resolved to a receiver itemId against the full
  /// queue); `nil` / out-of-range appends.
  func queueInsert(items: [CastMediaItem], beforeIndex: Int?) async throws
  /// Remove the queue items at the given absolute lib-track indices.
  func queueRemove(indices: [Int]) async throws
  /// Move the queue item at `fromIndex` to `toIndex` (array-move
  /// semantics).
  func queueMove(fromIndex: Int, toIndex: Int) async throws
  /// Remove every item from the receiver queue.
  func queueClear() async throws
}
