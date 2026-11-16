package com.margelo.nitro.queueplayer

/**
 * Pure-Kotlin index-shift arithmetic for queue mutations, kept out of
 * [TrackPlayer] so the shift semantics stand alone without an
 * ExoPlayer. Its iOS counterpart is `QueueMutationArithmetic.swift`,
 * with the same function shapes and input/output semantics for these
 * five helpers.
 *
 * The helper only computes the new `currentTrackIndex` and (for
 * `removeFromQueue`) which original indices apply after dedup /
 * range-filter. Actual mutation of the track list + forwarding to
 * ExoPlayer happens in [TrackPlayer].
 */
internal object QueueMutationArithmetic {

  /**
   * New `currentTrackIndex` after inserting [count] items at
   * [insertAt]. If [count] is 0, returns [currentIndex] unchanged.
   *
   * Rules:
   * - empty queue (cur < 0): stays at -1 — inserting into an
   *   empty queue does NOT auto-start playback; consumers call
   *   skipToIndex(0) explicitly.
   * - insertAt <= cur: cur shifts forward by `count` (items slid
   *   under it).
   * - insertAt > cur: cur unchanged.
   */
  fun currentIndexAfterAdd(
    currentIndex: Int,
    insertAt: Int,
    count: Int
  ): Int {
    if (count <= 0) return currentIndex
    if (currentIndex < 0) return currentIndex
    return if (insertAt <= currentIndex) currentIndex + count else currentIndex
  }

  /**
   * Clamp an `insertBefore` argument to a valid insertion point in
   * a track list of size [trackCount]. `null` → append (return
   * trackCount). Out-of-range values clamp into [0, trackCount].
   */
  fun clampInsertBefore(insertBefore: Int?, trackCount: Int): Int {
    if (insertBefore == null) return trackCount
    if (insertBefore < 0) return 0
    if (insertBefore > trackCount) return trackCount
    return insertBefore
  }

  /**
   * Filter + dedup a removal-index input, dropping out-of-range and
   * negative entries. Returned list is ASCENDING — callers iterate
   * it descending when applying removals to the underlying list,
   * but the ascending shape is easier to reason about for the shift
   * math downstream.
   */
  fun sanitiseRemoveIndices(
    rawIndices: List<Int>,
    trackCount: Int
  ): List<Int> {
    return rawIndices
      .filter { it in 0 until trackCount }
      .toSortedSet()
      .toList()
  }

  /**
   * New `currentTrackIndex` after removing the given (sanitised,
   * ascending) indices from the track list.
   *
   * Rules (matches iOS `removeFromQueueBody`):
   * - cur < 0 (empty / not-started): stays -1.
   * - Every `i < cur` that was removed shifts cur down by 1.
   * - If cur itself was removed AND the list becomes empty, cur → -1.
   * - If cur itself was removed but other tracks remain, cur stays
   *   at the SAME index value (which now points at the track that
   *   was previously at cur+1 — iOS auto-advance semantics).
   * - If cur itself was removed AND cur >= newTrackCount, clamp to
   *   newTrackCount - 1.
   */
  fun currentIndexAfterRemove(
    currentIndex: Int,
    sanitisedIndices: List<Int>,
    trackCountBefore: Int
  ): Int {
    if (currentIndex < 0) return -1
    val indexSet = sanitisedIndices.toSet()
    val removedBelowCur = sanitisedIndices.count { it < currentIndex }
    val newTrackCount = trackCountBefore - sanitisedIndices.size
    if (newTrackCount <= 0) return -1

    var newCur = currentIndex - removedBelowCur
    if (currentIndex in indexSet) {
      // cur was removed; player auto-advances to what was at cur+1,
      // which now occupies the same index value after the earlier-
      // below shift has been applied. Clamp in case cur was at the
      // tail.
      if (newCur >= newTrackCount) newCur = newTrackCount - 1
    }
    return newCur
  }

  /**
   * New `currentTrackIndex` after moving the track at `fromIndex`
   * to `toIndex`. Inputs are assumed validated (in range, from != to).
   *
   * Rules (matches iOS `moveInQueueBody`):
   * - cur < 0 → -1.
   * - cur == from → newCur = to (cur moved with the track).
   * - from < cur && to >= cur → newCur -= 1 (track slid past cur
   *   from below, cur is now one slot earlier).
   * - from > cur && to <= cur → newCur += 1 (track slid past cur
   *   from above, cur is now one slot later).
   * - otherwise cur unchanged.
   */
  fun currentIndexAfterMove(
    currentIndex: Int,
    fromIndex: Int,
    toIndex: Int
  ): Int {
    if (currentIndex < 0) return currentIndex
    return when {
      currentIndex == fromIndex -> toIndex
      fromIndex < currentIndex && toIndex >= currentIndex -> currentIndex - 1
      fromIndex > currentIndex && toIndex <= currentIndex -> currentIndex + 1
      else -> currentIndex
    }
  }
}
