package com.margelo.nitro.queueplayer

import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/**
 * Reads the delegate a [ForwardingSimpleBasePlayer] currently wraps.
 *
 * `getPlayer()` is `protected final` on the Media3 base class — the
 * production code swaps the delegate via `retarget(...)` while the
 * bound session keeps the stable wrapper identity, so tests assert the
 * post-swap engine/cast player by reading it back here. Test-only
 * reflection; no production accessor is added.
 */
internal val ForwardingSimpleBasePlayer.wrappedPlayer: Player
  @UnstableApi
  get() = ForwardingSimpleBasePlayer::class.java
    .getDeclaredMethod("getPlayer")
    .apply { isAccessible = true }
    .invoke(this) as Player
