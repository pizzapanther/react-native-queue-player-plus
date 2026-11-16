package com.margelo.nitro.queueplayer

import androidx.media3.common.Player

/**
 * Pure-Kotlin translator from ExoPlayer's `(playbackState,
 * isPlaying, playWhenReady, playbackSuppressionReason, hasTracks,
 * currentTrackIndex)` tuple to the library's [PlayerState] enum.
 *
 * Extracted from [TrackPlayer.getStateInternal] so every branch
 * stands alone without a live ExoPlayer. Matches the pattern of
 * [QueueSkipArithmetic] + [QueueMutationArithmetic] — TrackPlayer
 * is a thin adapter that reads the live player's state and
 * delegates the translation here.
 */
internal object TransportStateTranslator {

  /**
   * Translate a full ExoPlayer state snapshot into [PlayerState].
   *
   * - No tracks loaded ([hasTracks] false OR [currentTrackIndex]
   *   `< 0`) → `NONE`. Pre-empts the raw `playbackState` read
   *   because Media3 can briefly retain `STATE_ENDED` after
   *   `setMediaItems(emptyList())` until the player transitions
   *   itself to idle.
   * - `STATE_IDLE` (with tracks) → `PAUSED` (iOS parity —
   *   paused-with-queue vs nothing-loaded are different user-
   *   observable states).
   * - `STATE_BUFFERING` → `BUFFERING`.
   * - `STATE_READY` + `isPlaying` → `PLAYING`.
   * - `STATE_READY` + `playWhenReady` + non-zero suppression
   *   reason → `BUFFERING` (transient focus-loss / unsuitable-
   *   output stall; iOS parity via
   *   `AVPlayer.waitingToPlayAtSpecifiedRate`).
   * - `STATE_READY` otherwise → `PAUSED`.
   * - `STATE_ENDED` → `ENDED`.
   * - unknown playbackState → `NONE` (defensive total; Media3 may
   *   add future states).
   */
  fun translate(
    playbackState: Int,
    isPlaying: Boolean,
    playWhenReady: Boolean,
    playbackSuppressionReason: Int,
    hasTracks: Boolean,
    currentTrackIndex: Int
  ): PlayerState {
    // Lib canonical state takes precedence over the underlying
    // ExoPlayer's `playbackState`. After `clearQueue` the player
    // can briefly retain `STATE_ENDED` (Media3 doesn't auto-
    // transition to `STATE_IDLE` when the playlist drains via
    // `setMediaItems(emptyList())`), so reading the raw
    // `playbackState` would surface `ENDED` for an empty queue.
    // The library-facing contract is: nothing loaded → `NONE`.
    if (!hasTracks || currentTrackIndex < 0) return PlayerState.NONE
    return when (playbackState) {
      Player.STATE_IDLE -> PlayerState.PAUSED
      Player.STATE_BUFFERING -> PlayerState.BUFFERING
      Player.STATE_READY -> when {
        isPlaying -> PlayerState.PLAYING
        playWhenReady &&
          playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE ->
          PlayerState.BUFFERING
        else -> PlayerState.PAUSED
      }
      Player.STATE_ENDED -> PlayerState.ENDED
      else -> PlayerState.NONE
    }
  }
}
