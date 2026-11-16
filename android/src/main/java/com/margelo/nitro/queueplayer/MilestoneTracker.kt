package com.margelo.nitro.queueplayer

/**
 * Tracks fixed playback-progress milestones (25/50/75/90%) for the
 * current track playthrough. Pure + deterministic (no Media3).
 *
 * A threshold emits only when forward playback crosses it: [seek] moves
 * the baseline WITHOUT emitting (so thresholds a seek jumps over are
 * suppressed), the first [tick] only establishes the baseline (so a
 * mid-track start doesn't back-fire), and each threshold emits at most
 * once per playthrough. [reset] begins a new playthrough (track change /
 * repeat-one loop). With no computable duration (<= 0) nothing fires.
 */
internal class MilestoneTracker {
  private val fired = HashSet<Int>()
  private var lastFraction: Double? = null

  /** Begin a fresh playthrough. */
  fun reset() {
    fired.clear()
    lastFraction = null
  }

  /** Move the baseline to a seek target and CONSUME (without emitting)
   *  every threshold at/below it — those were jumped, not played, so they
   *  must not fire this playthrough. Consuming into `fired` (rather than
   *  only moving the baseline) makes the suppress robust against a
   *  progress tick that observes the post-seek position around the same
   *  time as the seek. */
  fun seek(durationSec: Double, positionSec: Double) {
    val frac = fraction(durationSec, positionSec)
    if (frac == null) {
      lastFraction = null
      return
    }
    for (m in MILESTONES) {
      if (m / 100.0 <= frac) fired.add(m)
    }
    lastFraction = frac
  }

  /** Advance from a progress tick; returns the milestones crossed by
   *  forward playback since the previous observation, ascending. */
  fun tick(durationSec: Double, positionSec: Double): List<Int> {
    val frac = fraction(durationSec, positionSec) ?: return emptyList()
    val prev = lastFraction
    if (prev == null) {
      lastFraction = frac
      return emptyList()
    }
    val crossed = MILESTONES.filter { m ->
      m !in fired && prev < m / 100.0 && m / 100.0 <= frac
    }
    fired.addAll(crossed)
    lastFraction = frac
    return crossed
  }

  private fun fraction(durationSec: Double, positionSec: Double): Double? =
    if (durationSec > 0) positionSec / durationSec else null

  companion object {
    val MILESTONES = listOf(25, 50, 75, 90)
  }
}
