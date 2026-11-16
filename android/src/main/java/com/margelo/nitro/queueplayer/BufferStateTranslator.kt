package com.margelo.nitro.queueplayer

import androidx.media3.common.Player

/**
 * Pure-Kotlin translator from ExoPlayer's `playbackState` plus the
 * library's `(hasStartedPlaying, wantsToPlay)` intent flags to the
 * public [BufferState] enum.
 *
 * Extracted from [TrackPlayer.computeBufferState] so the discriminator
 * between an initial `BUFFERING` load and a mid-playback `STALLED`
 * rebuffer stands alone without a live ExoPlayer, matching the
 * [TransportStateTranslator] pattern.
 */
internal object BufferStateTranslator {

  /**
   * - `STATE_READY` / `STATE_ENDED` → [BufferState.FULL] (able to play now).
   * - `STATE_IDLE` (and any other) → [BufferState.EMPTY] (nothing loaded).
   * - `STATE_BUFFERING` → [BufferState.STALLED] when playback had started and
   *   is intended (a mid-play rebuffer), else [BufferState.BUFFERING] (the
   *   initial load, or loading while not trying to play).
   */
  fun translate(
    @Player.State playbackState: Int,
    hasStartedPlaying: Boolean,
    wantsToPlay: Boolean,
  ): BufferState =
    when (playbackState) {
      Player.STATE_READY, Player.STATE_ENDED -> BufferState.FULL
      Player.STATE_BUFFERING ->
        if (hasStartedPlaying && wantsToPlay) BufferState.STALLED else BufferState.BUFFERING
      else -> BufferState.EMPTY
    }
}
