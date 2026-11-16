package com.margelo.nitro.queueplayer

/**
 * Pure-Kotlin skip-index arithmetic. Mirrors the iOS sibling
 * `ios/QueueSkipArithmetic.swift` 1-for-1.
 *
 * Kept separate from [TrackPlayer] so the logic runs without an
 * ExoPlayer. [TrackPlayer.computeNextIndex] is the thin adapter that
 * translates the Nitrogen-generated [RepeatMode] enum into
 * [QueueSkipRepeatMode] and delegates; the skip-previous path goes
 * through [QueueSkipArithmetic.resolveSkipToPrevious], which wraps
 * [QueueSkipArithmetic.computePrevious].
 *
 * Value order (0/1/2) is held fixed across the iOS enum and this one
 * so a test that asserts the raw integer catches any accidental
 * reordering in the spec.
 */
internal enum class QueueSkipRepeatMode(val rawValue: Int) {
  OFF(0),
  TRACK(1),
  QUEUE(2)
}

internal object QueueSkipArithmetic {

  /**
   * Next-track index. Returns null when nothing should play next
   * (empty queue, no next track under OFF/TRACK at the end, nothing
   * currently playing).
   *
   * TRACK navigates identically to OFF: a user Next advances to the
   * next track with no wrap, and repeat-one persists so the newly
   * current track is the one that loops on natural end. Only the
   * end-of-track auto-advance (handled in [TrackPlayer], not here)
   * replays the current track under TRACK.
   *
   * `cur < 0` early-bails — when nothing is currently playing,
   * skipToNext is a no-op; callers start playback explicitly via
   * `setQueue + play` or `skipToIndex(0)`.
   */
  fun computeNext(
    trackCount: Int,
    currentIndex: Int,
    repeatMode: QueueSkipRepeatMode
  ): Int? {
    if (trackCount <= 0 || currentIndex < 0) return null
    return when (repeatMode) {
      QueueSkipRepeatMode.OFF, QueueSkipRepeatMode.TRACK -> {
        val next = currentIndex + 1
        if (next < trackCount) next else null
      }
      QueueSkipRepeatMode.QUEUE -> {
        val next = currentIndex + 1
        if (next < trackCount) next else 0
      }
    }
  }

  /**
   * Previous-track index. Same early-bail rules as [computeNext].
   *
   * OFF and TRACK both return null at index 0 (no previous track, no
   * wrap). The "restart current when past the threshold" model is
   * available via `PlayerConfig.skipToPreviousBehavior` (resolved by
   * [resolveSkipToPrevious]).
   */
  fun computePrevious(
    trackCount: Int,
    currentIndex: Int,
    repeatMode: QueueSkipRepeatMode
  ): Int? {
    if (trackCount <= 0 || currentIndex < 0) return null
    return when (repeatMode) {
      QueueSkipRepeatMode.OFF, QueueSkipRepeatMode.TRACK -> {
        val prev = currentIndex - 1
        if (prev >= 0) prev else null
      }
      QueueSkipRepeatMode.QUEUE -> {
        if (currentIndex == 0) trackCount - 1 else currentIndex - 1
      }
    }
  }

  /**
   * Whether the user-facing Next button should be enabled. Defined
   * as `computeNext(...) != null` so all three repeat modes are
   * handled in one place: OFF and TRACK are false at the last track
   * (no wrap); QUEUE is always true with an active track (wraps).
   */
  fun canSkipNext(
    trackCount: Int,
    currentIndex: Int,
    repeatMode: QueueSkipRepeatMode
  ): Boolean = computeNext(trackCount, currentIndex, repeatMode) != null

  /**
   * Whether the user-facing Previous button should be enabled.
   * Symmetric with [canSkipNext] — defined as `computePrevious(...)
   * != null`, so all three repeat modes are handled in one place:
   * OFF and TRACK are false at index 0 (no wrap); QUEUE is always
   * true with an active track (wraps).
   *
   * When [restartEnabled] is true (`PlayerConfig.skipToPreviousBehavior
   * == RESTART_OR_PREVIOUS`), Previous is enabled whenever a track is
   * loaded — even at index 0, where past the threshold it restarts the
   * current track — so the control never greys out while something is
   * playing. With no loaded track (`currentIndex < 0`) there is nothing
   * to restart, matching what resolveSkipToPrevious does for that state.
   */
  fun canSkipPrevious(
    trackCount: Int,
    currentIndex: Int,
    repeatMode: QueueSkipRepeatMode,
    restartEnabled: Boolean = false
  ): Boolean =
    if (restartEnabled) trackCount > 0 && currentIndex >= 0
    else computePrevious(trackCount, currentIndex, repeatMode) != null

  /**
   * Position (ms) at or below which a restart-or-previous skip-back goes
   * to the previous track; past it, the current track restarts. Matches
   * Media3's default `maxSeekToPreviousPosition` (industry-standard 3 s).
   */
  const val RESTART_THRESHOLD_MS: Long = 3000L

  /** What a skip-to-previous should do, resolved from position + behavior. */
  sealed interface SkipToPreviousAction {
    /** Navigate to this queue index (a real track change). */
    data class Previous(val index: Int) : SkipToPreviousAction
    /** Restart the current track (seek to 0) — not a track change. */
    object RestartCurrent : SkipToPreviousAction
    /** Nothing to do (empty queue, or no loaded track). */
    object NoOp : SkipToPreviousAction
  }

  /**
   * Resolves a skip-to-previous. When [restartEnabled] is false this is
   * exactly [computePrevious] (previous track, or nothing). When true it
   * applies the threshold model, matching Media3 `BasePlayer.seekToPrevious`:
   * a previous track exists AND `positionMs <= thresholdMs` -> previous;
   * otherwise (past threshold, or first track with no previous) -> restart
   * the current track.
   */
  fun resolveSkipToPrevious(
    trackCount: Int,
    currentIndex: Int,
    repeatMode: QueueSkipRepeatMode,
    restartEnabled: Boolean,
    positionMs: Long,
    thresholdMs: Long = RESTART_THRESHOLD_MS
  ): SkipToPreviousAction {
    if (trackCount <= 0 || currentIndex < 0) return SkipToPreviousAction.NoOp
    val prev = computePrevious(trackCount, currentIndex, repeatMode)
    if (!restartEnabled) {
      return if (prev != null) SkipToPreviousAction.Previous(prev) else SkipToPreviousAction.NoOp
    }
    return if (prev != null && positionMs <= thresholdMs) {
      SkipToPreviousAction.Previous(prev)
    } else {
      SkipToPreviousAction.RestartCurrent
    }
  }
}
