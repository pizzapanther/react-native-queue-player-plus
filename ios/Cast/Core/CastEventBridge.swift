import Foundation
import NitroModules

/// Bridges cast `CastSessionRemotePlayer` session state back into the
/// lib's existing event surface (`onStateChange` / etc.). Observes
/// `PlaybackStateRouter.shared`: when a session becomes active, the
/// bridge subscribes to its state listener + fans receiver updates
/// through `TrackPlayer.emitRemoteState`. When the session ends, the
/// bridge captures a snapshot of the receiver's last-known
/// `(positionMs, currentTrackIndex, isPlaying)` so the local engine
/// can pick up where the receiver left off, then tears down its per-
/// session subscriptions cleanly.
///
/// Stale-event guarantee comes from atomic listener detachment in
/// `setActive`: once a session is removed from the router and its
/// listeners are disposed, no further events from it can fire
/// `owner.emitRemoteState`.
///
/// The listener and session fields are main-thread state: the Cast SDK
/// delivers session and listener callbacks on main, and the owner's `start`
/// and `stop` hop there from its player queue rather than touching the fields
/// where they are called. The deactivation snapshot is the exception — it is
/// written from the player queue at handoff and has to be visible to a
/// deactivation that lands on main right after, so it sits behind a lock.
/// `seedStartMetadata` reads the owner's track list on the owner's queue.
final class CastEventBridge {

  private weak var owner: TrackPlayer?

  /// Disposer for the active-change listener on `PlaybackStateRouter.
  /// shared`. Held for the bridge's lifetime so the active flow
  /// observation stops when `stop()` is called.
  private var activeChangeDisposer: (() -> Void)?

  /// Disposers for the listeners currently attached to the active
  /// session. Cleared atomically when the session changes.
  private var sessionListenerDisposers: [() -> Void] = []

  /// Currently-active session, captured at the moment the router
  /// fires `activeRemote = X`. Held weakly because the session is
  /// owned by `CastManager` (and ultimately `ChromecastBackend`);
  /// the bridge is an observer, not an owner.
  private weak var currentActiveSession: CastSessionRemotePlayer?

  /// Receiver position at the moment the active session was cleared.
  /// The local engine can read this to resume from where the
  /// receiver was. Cleared when a new session activates.
  private(set) var lastPositionMsOnDeactivate: Int64 {
    get { snapshotLock.withLock { positionMsOnDeactivate } }
    set { snapshotLock.withLock { positionMsOnDeactivate = newValue } }
  }

  /// Receiver-side current track index at deactivation. `-1` when
  /// unknown.
  private(set) var lastTrackIndexOnDeactivate: Int {
    get { snapshotLock.withLock { trackIndexOnDeactivate } }
    set { snapshotLock.withLock { trackIndexOnDeactivate = newValue } }
  }

  private let snapshotLock = NSLock()
  private var positionMsOnDeactivate: Int64 = 0
  private var trackIndexOnDeactivate: Int = -1

  /// Last lib index emitted for the receiver's current item. Dedups
  /// repeat receiver statuses carrying the same resolved index so
  /// `onTrackChange` + the lockscreen push fire only on real advances.
  /// Reset when the active session changes.
  private var lastEmittedRemoteIndex: Int = -1

  init(owner: TrackPlayer) {
    self.owner = owner
  }

  func start() {
    onMain { [self] in
      if self.activeChangeDisposer != nil { return }
      self.activeChangeDisposer = PlaybackStateRouter.shared.addActiveChangeListener {
        [weak self] session in
        self?.handleActiveSessionChange(to: session)
      }
    }
  }

  func stop() {
    onMain {
      self.activeChangeDisposer?()
      self.activeChangeDisposer = nil
      self.sessionListenerDisposers.forEach { $0() }
      self.sessionListenerDisposers.removeAll()
      self.currentActiveSession = nil
    }
  }

  /// Runs `body` on main, in order with the SDK callbacks that own this
  /// object's state; inline when already there so a caller on main sees its
  /// effect before returning.
  private func onMain(_ body: @escaping () -> Void) {
    if Thread.isMainThread {
      body()
    } else {
      DispatchQueue.main.async(execute: body)
    }
  }

  /// Seed the deactivation snapshot from the local pre-handoff position +
  /// index so a disconnect before the receiver reports a resolvable item
  /// (rapid connect/disconnect, before the load round-trip settles) still
  /// lands local on the right spot. A receiver item resolved at
  /// deactivation takes precedence.
  func seedDeactivationSnapshot(trackIndex: Int, positionMs: Int64) {
    snapshotLock.withLock {
      trackIndexOnDeactivate = trackIndex
      positionMsOnDeactivate = positionMs
    }
  }

  // MARK: - Active-flow observer

  private func handleActiveSessionChange(to newSession: CastSessionRemotePlayer?) {
    // The router fires listeners on the caller's thread; every caller today
    // is on main, and the fields below are read and written on that basis.
    dispatchPrecondition(condition: .onQueue(.main))
    let prior = currentActiveSession
    if newSession == nil, let prior = prior {
      // Capture deactivation snapshot BEFORE tearing down listeners so
      // the local engine's resume path can read the receiver's last-known
      // state. Use the receiver's resolved absolute track index; when it
      // resolved (>= 0) prefer the receiver's position + play-state. When
      // it never did (a disconnect before the first resolvable status),
      // keep the local pre-handoff snapshot seeded at handoff time.
      if prior.lastCurrentTrackIndex >= 0 {
        lastTrackIndexOnDeactivate = prior.lastCurrentTrackIndex
        lastPositionMsOnDeactivate = prior.lastPositionMs
      }
      // Seek local back to where the receiver left off and stay paused
      // (the user resumes locally) — never auto-blast the phone speaker.
      owner?.resumeLocalAfterCast(
        trackIndex: lastTrackIndexOnDeactivate,
        positionMs: lastPositionMsOnDeactivate
      )
    } else if newSession != nil {
      // New session — clear the deactivation cache (caches are only
      // valid in the window between session-end and the next start).
      lastPositionMsOnDeactivate = 0
      lastTrackIndexOnDeactivate = -1
      // Hand off the current local queue + playhead + play/pause to the
      // receiver so playback continues where it was, then silence the
      // local engine. The router short-circuits JS-side transport, but
      // continuity needs the position + play-state pushed explicitly.
      owner?.handoffCurrentPlaybackToCast()
    }

    sessionListenerDisposers.forEach { $0() }
    sessionListenerDisposers.removeAll()
    currentActiveSession = newSession
    // The dedup index is per-session: a fresh session must re-emit its
    // current track even if it resolves to the same lib index the prior
    // session last reported.
    lastEmittedRemoteIndex = -1

    guard let session = newSession else { return }

    let stateDisposer = session.addStateListener { [weak self] state in
      guard let self = self, let owner = self.owner else { return }
      owner.emitRemoteState(state.asPlayerState, reason: .system)
    }
    sessionListenerDisposers.append(stateDisposer)

    // Receiver advanced to a different queue item. The backend resolves the
    // current item's ABSOLUTE index against the full queue (via GCKMediaQueue
    // / the Cast Player), so this carries the lib-track index directly — no
    // window-relative itemId mapping.
    let trackIndexDisposer = session.addCurrentTrackIndexListener { [weak self] _ in
      self?.refreshCurrentRemoteTrack()
    }
    sessionListenerDisposers.append(trackIndexDisposer)

    // Receiver position / duration — fan to JS `onProgress` so the player
    // screen's progress bar, position, and duration track the receiver
    // while the cast session is active (the local engine is paused, so its
    // periodic progress emit is silent).
    let positionDisposer = session.addPositionListener { [weak self] positionMs, durationMs, bufferedMs in
      self?.owner?.emitRemoteProgress(
        positionMs: positionMs, durationMs: durationMs, bufferedMs: bufferedMs
      )
    }
    sessionListenerDisposers.append(positionDisposer)
  }

  // MARK: - Lockscreen metadata mirror

  /// Seed the start track's lockscreen metadata after a `setQueue` so the
  /// surface doesn't wait for the first receiver status.
  func seedStartMetadata(startIndex: Int) {
    pushMetadata(forIndex: startIndex)
  }

  /// Push the receiver's current track to the JS surface + lockscreen when
  /// it advances. The backend resolves the absolute lib-track index against
  /// the full queue (`lastCurrentTrackIndex`), so no window-relative itemId
  /// mapping is involved. Dedup'd on `lastEmittedRemoteIndex` so repeat
  /// statuses carrying the same index fire the surface only on real
  /// advances; `-1` (not yet resolvable) is ignored.
  private func refreshCurrentRemoteTrack() {
    guard let session = currentActiveSession else { return }
    let index = session.lastCurrentTrackIndex
    guard index >= 0, index != lastEmittedRemoteIndex else { return }
    lastEmittedRemoteIndex = index
    pushMetadata(forIndex: index)
    owner?.emitRemoteTrackChange(index: index)
  }

  /// Resolve a lib track index to its `TrackItem` and push its metadata
  /// to the cast lockscreen controller. Out-of-range indices are
  /// ignored (the controller keeps its prior snapshot). The track list is
  /// read on the queue that owns it, whichever thread this is called from.
  private func pushMetadata(forIndex index: Int) {
    guard let owner = owner else { return }
    owner.playerQueue.async {
      guard index >= 0, index < owner.tracks.count else { return }
      let track = owner.tracks[index]
      // The controller hops to main itself.
      CastNowPlayingController.shared.updateTrackMetadata(
        title: track.title,
        artist: track.artist,
        album: track.album,
        durationSec: track.duration ?? 0,
        artworkUrl: track.artworkUrl
      )
    }
  }

}

extension RemotePlaybackStateKind {
  /// Map the protocol-neutral receiver state to the JS-facing
  /// `PlayerState`. Used by `CastEventBridge` to fan state changes
  /// to `TrackPlayer.emitRemoteState`, and by `CastNowPlayingController`
  /// to decide the lockscreen playback rate.
  var asPlayerState: PlayerState {
    switch self {
    case .playing: return .playing
    case .paused: return .paused
    case .loading, .buffering: return .buffering
    case .idle: return .none
    case .ended: return .ended
    case .error: return .error
    }
  }
}
