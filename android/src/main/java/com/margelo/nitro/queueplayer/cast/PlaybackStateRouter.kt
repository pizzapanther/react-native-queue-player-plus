package com.margelo.nitro.queueplayer.cast

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single source of truth for "is a `RemotePlayer` cast session active
 * right now?". When yes, transport commands
 * (`play`/`pause`/`seekTo`/...) and playback-state reads route to the
 * active session instead of the local ExoPlayer; when no, the local
 * player handles them. `CastTransportRouter` (the playback-side shim)
 * reads through [activeRemote] / observes [activeFlow] to decide.
 *
 * First-come-wins: a second [setActive] call with a non-null session
 * while another is registered throws `IllegalStateException`. Multi-
 * receiver is out of scope. Clearing (via `setActive(null)` or
 * [clearActive]) is always safe.
 */
object PlaybackStateRouter {
  private val _active = MutableStateFlow<CastSession.RemotePlayer?>(null)

  /**
   * Reactive view of the currently-active [CastSession.RemotePlayer].
   * `CastEventBridge` collects this flow to attach / detach its
   * per-session subscriptions when a remote session becomes active or
   * is torn down. Emits the current value on subscribe.
   */
  val activeFlow: StateFlow<CastSession.RemotePlayer?> = _active.asStateFlow()

  /**
   * Snapshot the currently-active session, or `null` for local
   * playback. Callers treat the return as a snapshot — the active
   * session may change between calls.
   */
  fun activeRemote(): CastSession.RemotePlayer? = _active.value

  /**
   * Register [session] as the live remote-player session. Pass `null`
   * to clear unconditionally. Registering a non-null session while
   * another is already registered fails fast.
   */
  fun setActive(session: CastSession.RemotePlayer?) {
    if (session == null) {
      _active.value = null
      return
    }
    val existing = _active.value
    if (existing != null && existing !== session) {
      throw IllegalStateException(
        "A RemotePlayer session is already active. Clear it first via setActive(null)."
      )
    }
    _active.value = session
  }

  /**
   * Clear the active session only if it's still [expected]. Use from a
   * closing session's teardown path so a newer session that registered
   * in parallel isn't wiped out. Always safe.
   */
  fun clearActive(expected: CastSession.RemotePlayer) {
    _active.compareAndSet(expected, null)
  }
}
