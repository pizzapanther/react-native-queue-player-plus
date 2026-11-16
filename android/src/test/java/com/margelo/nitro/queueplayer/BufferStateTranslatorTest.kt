package com.margelo.nitro.queueplayer

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Per-branch matrix for [BufferStateTranslator] — the pure mapping from
 * `(playbackState, hasStartedPlaying, wantsToPlay)` to [BufferState]. Runs
 * without a live ExoPlayer so the initial-load vs mid-play-stall discriminator
 * is locked at ~1ms per case.
 */
class BufferStateTranslatorTest {

  private fun translate(state: Int, started: Boolean, wants: Boolean) =
    BufferStateTranslator.translate(state, started, wants)

  @Test
  fun `ready is full regardless of intent`() {
    assertEquals(BufferState.FULL, translate(Player.STATE_READY, started = false, wants = false))
    assertEquals(BufferState.FULL, translate(Player.STATE_READY, started = true, wants = true))
  }

  @Test
  fun `ended is full`() {
    assertEquals(BufferState.FULL, translate(Player.STATE_ENDED, started = true, wants = true))
  }

  @Test
  fun `idle is empty`() {
    assertEquals(BufferState.EMPTY, translate(Player.STATE_IDLE, started = false, wants = false))
    assertEquals(BufferState.EMPTY, translate(Player.STATE_IDLE, started = true, wants = true))
  }

  @Test
  fun `initial buffering before playback starts is buffering even when play was requested`() {
    assertEquals(
      BufferState.BUFFERING,
      translate(Player.STATE_BUFFERING, started = false, wants = true),
    )
  }

  @Test
  fun `mid-playback rebuffer while trying to play is stalled`() {
    assertEquals(
      BufferState.STALLED,
      translate(Player.STATE_BUFFERING, started = true, wants = true),
    )
  }

  @Test
  fun `buffering while paused after playback started is buffering, not stalled`() {
    assertEquals(
      BufferState.BUFFERING,
      translate(Player.STATE_BUFFERING, started = true, wants = false),
    )
  }
}
