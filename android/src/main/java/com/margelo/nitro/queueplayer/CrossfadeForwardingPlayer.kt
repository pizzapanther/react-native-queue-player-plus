package com.margelo.nitro.queueplayer

import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/**
 * `MediaSession`-stable wrapper around a [CrossfadeEngine]'s two
 * underlying `ExoPlayer` instances.
 *
 * `MediaSession.Builder` binds to a single Player for its lifetime
 * — but inside [CrossfadeEngine] the leading / standby legs swap
 * roles at every fade midpoint. Without a forwarding wrapper the
 * session would either stay bound to a now-standby player
 * (lock-screen scrubber freezes mid-fade) or require a noisy
 * `setPlayer(...)` mid-fade swap (controller reconnect glitch).
 *
 * `ForwardingSimpleBasePlayer` (Media3 1.4+) is the supported
 * runtime-swappable Player base. The legacy `ForwardingPlayer`
 * stores the wrapped player in a `private final` field that every
 * base method reads directly — overriding `getWrappedPlayer()`
 * does NOT route base-class calls. Use this class for any
 * runtime-swappable Player binding.
 *
 * Mid-fade role swap: this wrapper invalidates its cached state
 * via [invalidateState] so `MediaSession` consumers see the
 * post-swap leading leg's view. Outside fade windows the wrapped
 * delegate stays put.
 */
@UnstableApi
internal class CrossfadeForwardingPlayer(
  private val engine: CrossfadeEngine,
) : ForwardingSimpleBasePlayer(engine.leadingPlayer) {

  /**
   * Re-target the wrapped player to the engine's new leading leg
   * after a `CrossfadeEngine.swapRoles()` flips `currentRoleIsA`.
   *
   * `ForwardingSimpleBasePlayer.setPlayer(...)` is the supported
   * runtime-swap entry point — `invalidateState()` alone only
   * refreshes cached state from the still-bound construction-time
   * leg. Pair `setPlayer` with `invalidateState` so the new leg's
   * state propagates to `MediaSession` consumers immediately
   * instead of after the next state-changing event.
   */
  fun onRoleSwapped() {
    setPlayer(engine.leadingPlayer)
    invalidateState()
  }

  /**
   * Re-target the wrapped player to the engine's standby (incoming)
   * leg the instant a crossfade begins. MediaSession publishes the
   * standby leg's `MediaMetadataCompat` + `PlaybackState` so the
   * lock-screen / Auto / BT scrubber + title/artwork flip to the
   * incoming track from 00:00 the moment it starts audibly
   * playing. The standby `ExoPlayer` instance becomes the new
   * leading on `swapRoles` at fade-end, so the [onRoleSwapped]
   * re-bind a moment later is a no-op (already pointing at the
   * same instance).
   */
  fun onCrossfadeBegin() {
    setPlayer(engine.standbyPlayer)
    invalidateState()
  }
}
