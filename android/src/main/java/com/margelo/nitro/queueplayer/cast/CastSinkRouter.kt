package com.margelo.nitro.queueplayer.cast

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/**
 * Route-change signal hub + active-session / player holder for cast
 * `PcmStream` sessions (AirPlay 1 + AirPlay 2).
 *
 * The audio for an AirPlay session is carried by the dedicated `AirPlayEngine`,
 * which `TrackPlayer` swaps in when [setActiveSession] fires the route change
 * and binds the session's `audioSink()` to its renderer directly. This object
 * just holds the active session + the active ExoPlayer reference (for
 * metadata-aware sessions) and broadcasts activate/deactivate transitions — it
 * does not touch the audio pipeline itself.
 */
@UnstableApi
object CastSinkRouter {

  /**
   * The active PcmStream session (AirPlay 1/2), or `null` for local playback.
   * `@Volatile` because the death path clears it off the main thread
   * (`CastManager.onAirplaySessionDied` runs on the native drain thread) while
   * `activeSession()`/`isRemoteActive()` are read on main.
   */
  @Volatile
  private var activeSession: CastSession.PcmStream? = null

  /**
   * Listeners notified on every push-PCM route activate (`true`) / deactivate
   * (`false`) transition. `TrackPlayer` uses this to swap the dedicated
   * `AirPlayEngine` in/out. Does NOT replay the current state on subscribe —
   * callers that need a starting snapshot read [isRemoteActive] directly, so
   * subscribers registered during class construction don't run against
   * half-initialized state.
   */
  private val routeChangeListeners =
    java.util.concurrent.CopyOnWriteArraySet<(Boolean) -> Unit>()

  fun addRouteChangeListener(listener: (Boolean) -> Unit): () -> Unit {
    routeChangeListeners.add(listener)
    return { routeChangeListeners.remove(listener) }
  }

  private fun fireRouteChange(active: Boolean) {
    for (l in routeChangeListeners) l(active)
  }

  /**
   * Reference to the active ExoPlayer, held so metadata-aware session subtypes
   * (`MetadataAwareSession.onActivated`) can hook player events. Set by the
   * engine on construction via [registerPlayer].
   */
  @Volatile
  private var playerRef: Player? = null

  fun registerPlayer(player: Player) {
    playerRef = player
  }

  // Read at AirPlay engine activation (TrackPlayer.applyEngineSwapForMode) to
  // hand the engine's ExoPlayer to the active session's receiver metadata sync.
  fun player(): Player? = playerRef

  /**
   * Record an active PcmStream session (AirPlay 1/2) and fire the route
   * change. The audio is carried by the dedicated `AirPlayEngine` that
   * `TrackPlayer` swaps in on this signal. Must be called on the main thread
   * (the same thread ExoPlayer is driven from).
   */
  fun setActiveSession(session: CastSession.PcmStream) {
    activeSession = session
    fireRouteChange(true)
  }

  /**
   * Clear the active session and fire the route change. `TrackPlayer`'s
   * route-change listener swaps the dedicated `AirPlayEngine` back out for the
   * user's gapless/crossfade engine.
   */
  fun clearActiveSession() {
    activeSession = null
    fireRouteChange(false)
  }

  fun activeSession(): CastSession.PcmStream? = activeSession

  /**
   * `true` when an AirPlay (push-PCM) session is active. Used by the engine
   * selector: while AirPlay is active the dedicated `AirPlayEngine` is in use,
   * so crossfade is unavailable (the wire protocol has no sender-side
   * cross-stream mixing).
   */
  fun isRemoteActive(): Boolean = activeSession != null
}
