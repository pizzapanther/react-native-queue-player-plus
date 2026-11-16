package com.margelo.nitro.queueplayer.cast

import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope

/**
 * Optional interface for [CastSession.PcmStream] sessions that can
 * display metadata (title/artist/album/artwork/progress) on the
 * receiver. Protocol-agnostic — describes WHAT to display, not HOW.
 */
interface MetadataAwareSession {
  /** Begin syncing metadata from [player] to the receiver. */
  fun onActivated(player: Player, scope: CoroutineScope)

  /** Stop syncing. Safe to call multiple times. */
  fun onDeactivated()
}
