package com.margelo.nitro.queueplayer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [MilestoneTracker] — the pure 25/50/75/90% crossing
 * logic behind `onPlaybackMilestone`.
 */
class MilestoneTrackerTest {

  // 200s track => pct*2 seconds.
  private fun MilestoneTracker.playTo(pct: Double, duration: Double = 200.0): List<Int> =
    tick(duration, pct / 100.0 * duration)

  @Test
  fun `forward playthrough fires each once in order`() {
    val t = MilestoneTracker()
    val fired = mutableListOf<Int>()
    var pct = 0.0
    while (pct <= 100.0) {
      fired += t.playTo(pct)
      pct += 5.0
    }
    assertEquals(listOf(25, 50, 75, 90), fired)
  }

  @Test
  fun `first tick establishes baseline and does not fire`() {
    val t = MilestoneTracker()
    assertEquals(emptyList<Int>(), t.tick(200.0, 120.0)) // 60% mid-track start
    assertEquals(listOf(75), t.tick(200.0, 152.0))
    assertEquals(listOf(90), t.tick(200.0, 184.0))
  }

  @Test
  fun `seek past thresholds suppresses them`() {
    val t = MilestoneTracker()
    t.tick(200.0, 10.0) // baseline 5%
    t.seek(200.0, 160.0) // jump to 80%
    assertEquals(emptyList<Int>(), t.tick(200.0, 164.0))
    assertEquals(listOf(90), t.tick(200.0, 182.0)) // only 90 fires
  }

  @Test
  fun `each threshold fires only once per playthrough`() {
    val t = MilestoneTracker()
    t.tick(200.0, 0.0)
    assertEquals(listOf(25, 50), t.tick(200.0, 120.0)) // 60%
    t.seek(200.0, 20.0) // back to 10%
    assertEquals(listOf(75), t.tick(200.0, 160.0)) // 80%, 25/50 stay fired
  }

  @Test
  fun `reset re-arms for a new playthrough`() {
    val t = MilestoneTracker()
    t.tick(200.0, 0.0)
    assertEquals(listOf(25, 50, 75, 90), t.tick(200.0, 200.0))
    t.reset()
    t.tick(200.0, 0.0)
    assertEquals(listOf(25, 50, 75, 90), t.tick(200.0, 200.0))
  }

  @Test
  fun `unknown duration fires nothing`() {
    val t = MilestoneTracker()
    assertEquals(emptyList<Int>(), t.tick(0.0, 30.0))
    assertEquals(emptyList<Int>(), t.tick(0.0, 9999.0))
    assertEquals(emptyList<Int>(), t.tick(200.0, 100.0)) // baseline 50%
    assertEquals(listOf(75), t.tick(200.0, 152.0))
  }

  @Test
  fun `threshold exactly on tick fires`() {
    val t = MilestoneTracker()
    t.tick(200.0, 0.0)
    assertEquals(listOf(25, 50), t.tick(200.0, 100.0)) // exactly 50%
  }
}
