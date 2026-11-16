package com.margelo.nitro.queueplayer

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * pure Kotlin state-translation matrix for
 * [TransportStateTranslator]. Replaces the implicit matrix
 * scattered across [TrackPlayerTransportTest] with a dedicated
 * per-branch test set so:
 *
 * 1. Every combination of `(playbackState, isPlaying,
 *    playWhenReady, suppressionReason, hasTracks, cur)` that
 *    affects the output is covered exactly once.
 * 2. The translator runs without a live ExoPlayer, so a regression
 *    fails at ~1ms per case rather than the ~100ms Robolectric
 *    setup cost.
 */
class TransportStateTranslatorTest {

  // Default to a tracks-loaded queue so tests that exercise the
  // ExoPlayer-state branches don't have to repeat the queue-state
  // arguments. Tests that target the no-tracks branch override
  // both `hasTracks` and `currentTrackIndex` explicitly.
  private fun translate(
    playbackState: Int,
    isPlaying: Boolean = false,
    playWhenReady: Boolean = false,
    suppressionReason: Int = Player.PLAYBACK_SUPPRESSION_REASON_NONE,
    hasTracks: Boolean = true,
    currentTrackIndex: Int = 0
  ) = TransportStateTranslator.translate(
    playbackState = playbackState,
    isPlaying = isPlaying,
    playWhenReady = playWhenReady,
    playbackSuppressionReason = suppressionReason,
    hasTracks = hasTracks,
    currentTrackIndex = currentTrackIndex
  )

  // --- STATE_IDLE

  @Test
  fun `STATE_IDLE with no tracks returns NONE`() {
    assertEquals(
      PlayerState.NONE,
      translate(
        playbackState = Player.STATE_IDLE,
        hasTracks = false,
        currentTrackIndex = -1
      )
    )
  }

  @Test
  fun `STATE_IDLE with tracks but cur less than 0 returns NONE`() {
    // Add-into-empty-queue semantic: tracks populated but cur
    // stays at -1 until consumer calls skipToIndex(0).
    assertEquals(
      PlayerState.NONE,
      translate(
        playbackState = Player.STATE_IDLE,
        hasTracks = true,
        currentTrackIndex = -1
      )
    )
  }

  @Test
  fun `STATE_IDLE with tracks and cur greater than or equal to 0 returns PAUSED`() {
    // iOS-parity: paused-with-queue-loaded is distinct from
    // nothing-loaded.
    assertEquals(
      PlayerState.PAUSED,
      translate(playbackState = Player.STATE_IDLE)
    )
  }

  // --- STATE_BUFFERING

  @Test
  fun `STATE_BUFFERING returns BUFFERING`() {
    assertEquals(
      PlayerState.BUFFERING,
      translate(playbackState = Player.STATE_BUFFERING)
    )
  }

  @Test
  fun `STATE_BUFFERING with tracks returns BUFFERING`() {
    assertEquals(
      PlayerState.BUFFERING,
      translate(
        playbackState = Player.STATE_BUFFERING,
        hasTracks = true,
        currentTrackIndex = 3
      )
    )
  }

  @Test
  fun `STATE_BUFFERING with no tracks returns NONE`() {
    // Lib canonical state: nothing loaded → NONE, regardless of
    // ExoPlayer's residual playbackState.
    assertEquals(
      PlayerState.NONE,
      translate(
        playbackState = Player.STATE_BUFFERING,
        hasTracks = false,
        currentTrackIndex = -1
      )
    )
  }

  // --- STATE_READY

  @Test
  fun `STATE_READY isPlaying=true returns PLAYING`() {
    assertEquals(
      PlayerState.PLAYING,
      translate(playbackState = Player.STATE_READY, isPlaying = true)
    )
  }

  @Test
  fun `STATE_READY not playing and no suppression returns PAUSED`() {
    assertEquals(
      PlayerState.PAUSED,
      translate(
        playbackState = Player.STATE_READY,
        isPlaying = false,
        playWhenReady = false
      )
    )
  }

  @Test
  fun `STATE_READY playWhenReady + suppressed (transient focus loss) returns BUFFERING`() {
    // Audio focus loss. iOS parity:
    // AVPlayer.timeControlStatus.waitingToPlayAtSpecifiedRate.
    // Must NOT read as PAUSED (consumers would interpret as
    // user-intent pause).
    assertEquals(
      PlayerState.BUFFERING,
      translate(
        playbackState = Player.STATE_READY,
        isPlaying = false,
        playWhenReady = true,
        suppressionReason =
          Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS
      )
    )
  }

  @Test
  fun `STATE_READY playWhenReady + suppressed (unsuitable output) returns BUFFERING`() {
    // Media3 1.2+ adds PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_OUTPUT
    // for e.g. BT codec mismatch on route change. Same translation
    // as focus loss — the branch keys on the non-zero reason,
    // not the specific value.
    assertEquals(
      PlayerState.BUFFERING,
      translate(
        playbackState = Player.STATE_READY,
        isPlaying = false,
        playWhenReady = true,
        suppressionReason =
          Player.PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_OUTPUT
      )
    )
  }

  @Test
  fun `STATE_READY playWhenReady but not suppressed falls through to PAUSED`() {
    // Defensive branch: playWhenReady=true without suppression
    // would normally imply isPlaying=true, but Media3's listener
    // can fire onPlayWhenReadyChanged before onIsPlayingChanged.
    // Brief PAUSED emit is acceptable; the next isPlaying fire
    // catches up.
    assertEquals(
      PlayerState.PAUSED,
      translate(
        playbackState = Player.STATE_READY,
        isPlaying = false,
        playWhenReady = true,
        suppressionReason = Player.PLAYBACK_SUPPRESSION_REASON_NONE
      )
    )
  }

  @Test
  fun `STATE_READY not playWhenReady + suppressed returns PAUSED (not BUFFERING)`() {
    // If the consumer has explicitly NOT requested playback, a
    // suppression reason is informational — don't pretend we're
    // buffering to start. This mirrors iOS which only maps the
    // waiting-to-play condition when the consumer is actively
    // asking for playback.
    assertEquals(
      PlayerState.PAUSED,
      translate(
        playbackState = Player.STATE_READY,
        isPlaying = false,
        playWhenReady = false,
        suppressionReason =
          Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS
      )
    )
  }

  // --- STATE_ENDED

  @Test
  fun `STATE_ENDED returns ENDED`() {
    assertEquals(
      PlayerState.ENDED,
      translate(playbackState = Player.STATE_ENDED)
    )
  }

  @Test
  fun `STATE_ENDED with tracks returns ENDED`() {
    assertEquals(
      PlayerState.ENDED,
      translate(
        playbackState = Player.STATE_ENDED,
        hasTracks = true,
        currentTrackIndex = 4
      )
    )
  }

  @Test
  fun `STATE_ENDED with no tracks returns NONE`() {
    // After `clearQueue` Media3 can briefly retain `STATE_ENDED`
    // because `setMediaItems(emptyList())` doesn't synchronously
    // transition the player to `STATE_IDLE`. The lib-facing
    // contract is "nothing loaded → NONE", so the no-tracks
    // guard pre-empts the raw playbackState read.
    assertEquals(
      PlayerState.NONE,
      translate(
        playbackState = Player.STATE_ENDED,
        hasTracks = false,
        currentTrackIndex = -1
      )
    )
  }

  // --- unknown state

  @Test
  fun `unknown playbackState with tracks falls back to NONE`() {
    // Media3 may add future playback states. Defensive fallthrough
    // keeps the state machine total — critical because a
    // non-total state machine can starve the event stream.
    assertEquals(
      PlayerState.NONE,
      translate(playbackState = -999)
    )
    assertEquals(
      PlayerState.NONE,
      translate(playbackState = 0)
    )
  }

  // --- isPlaying wins over suppression

  @Test
  fun `STATE_READY isPlaying=true wins even when suppression is set`() {
    // Media3 may briefly report isPlaying=true with a non-zero
    // suppression reason during the cancel-of-suppression cycle.
    // PLAYING is the correct user-facing state; isPlaying is the
    // canonical truth-source for "audio is currently producing
    // frames".
    assertEquals(
      PlayerState.PLAYING,
      translate(
        playbackState = Player.STATE_READY,
        isPlaying = true,
        playWhenReady = true,
        suppressionReason =
          Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS
      )
    )
  }
}
