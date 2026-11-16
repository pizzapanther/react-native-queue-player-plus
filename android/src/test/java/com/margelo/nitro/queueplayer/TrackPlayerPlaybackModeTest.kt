package com.margelo.nitro.queueplayer

import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `setPlaybackMode` / `getPlaybackMode` imperative spec coverage —
 * the engine-swap path can't be fully bound under Robolectric, which
 * can't stand up the service + ExoPlayer to verify a real swap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@UnstableApi
class TrackPlayerPlaybackModeTest {

  @Test
  fun `default playback mode is gapless before any setPlaybackMode call`() {
    val player = TrackPlayer()
    val mode = player.getPlaybackMode()
    assertEquals(PlaybackModeKind.GAPLESS, mode.kind)
    assertEquals(null, mode.crossfadeDurationMs)
  }
}
