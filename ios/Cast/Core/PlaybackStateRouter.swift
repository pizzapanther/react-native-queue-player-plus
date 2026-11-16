import Foundation

/// Single source of truth for "is a `CastSessionRemotePlayer` cast
/// session active right now?". When yes, transport commands
/// (`play`/`pause`/`seekTo`/…) and playback-state reads route to the
/// active session instead of the local engine; when no, the local
/// engine handles them. `CastTransportRouter` reads through
/// `activeRemote()` to decide; `CastEventBridge` subscribes via
/// `addActiveChangeListener` to attach / detach per-session listeners.
///
/// First-come-wins: a second `setActive` call with a non-nil session
/// while another is registered throws `CastRouterError.alreadyActive`.
/// Multi-receiver is out of scope. Clearing (via `setActive(nil)` or
/// `clearActive(expected:)`) is always safe.
///
/// Thread-safety: an `NSLock` guards the active slot + listener
/// registry. Listeners fire OUTSIDE the lock to avoid deadlock if a
/// listener body re-enters the router.
final class PlaybackStateRouter {

  static let shared = PlaybackStateRouter()

  enum CastRouterError: Error {
    case alreadyActive
  }

  private let lock = NSLock()
  private var active: CastSessionRemotePlayer?
  private var listeners: [Int64: (CastSessionRemotePlayer?) -> Void] = [:]
  private var nextListenerId: Int64 = 0

  /// Snapshot the currently-active session, or `nil` for local
  /// playback. Callers treat the return as a snapshot — the active
  /// session may change between calls.
  func activeRemote() -> CastSessionRemotePlayer? {
    lock.lock(); defer { lock.unlock() }
    return active
  }

  /// Register `session` as the live remote-player session. Pass `nil`
  /// to clear unconditionally. Registering a non-nil session while
  /// another is already registered throws `.alreadyActive`. Re-
  /// registering the same instance is a no-op (no listener fire).
  func setActive(_ session: CastSessionRemotePlayer?) throws {
    let snapshotListeners: [(CastSessionRemotePlayer?) -> Void]
    let newValue: CastSessionRemotePlayer?
    lock.lock()
    if let session {
      if let existing = active {
        if existing === session {
          lock.unlock()
          return
        }
        lock.unlock()
        throw CastRouterError.alreadyActive
      }
      active = session
      newValue = session
      snapshotListeners = Array(listeners.values)
    } else {
      if active == nil {
        lock.unlock()
        return
      }
      active = nil
      newValue = nil
      snapshotListeners = Array(listeners.values)
    }
    lock.unlock()
    for l in snapshotListeners { l(newValue) }
  }

  /// Clear the active session only if it's still `expected`. Use from
  /// a closing session's teardown path so a newer session that
  /// registered in parallel isn't wiped out. Always safe.
  func clearActive(expected: CastSessionRemotePlayer) {
    let snapshotListeners: [(CastSessionRemotePlayer?) -> Void]
    lock.lock()
    guard let current = active, current === expected else {
      lock.unlock()
      return
    }
    active = nil
    snapshotListeners = Array(listeners.values)
    lock.unlock()
    for l in snapshotListeners { l(nil) }
  }

  /// Register a listener fired on every active-session transition
  /// (`nil → session`, `session → nil`). Listener is NOT fired with
  /// the current value on subscribe — call `activeRemote()` if a
  /// seed value is wanted. Returns a disposer.
  func addActiveChangeListener(
    _ cb: @escaping (CastSessionRemotePlayer?) -> Void
  ) -> () -> Void {
    lock.lock()
    nextListenerId += 1
    let id = nextListenerId
    listeners[id] = cb
    lock.unlock()
    return { [weak self] in
      guard let self else { return }
      self.lock.lock()
      self.listeners.removeValue(forKey: id)
      self.lock.unlock()
    }
  }
}

#if DEBUG
extension PlaybackStateRouter {
  /// Test-only hard reset. Clears the active slot and all listeners
  /// so a fresh test case starts with the same invariants as a fresh
  /// process. Not part of any shipped code path.
  func _resetForTesting() {
    lock.lock()
    active = nil
    listeners.removeAll()
    nextListenerId = 0
    lock.unlock()
  }
}
#endif
