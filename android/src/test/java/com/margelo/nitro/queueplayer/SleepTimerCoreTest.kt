package com.margelo.nitro.queueplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic coverage for [SleepTimerCore] — the wall-clock countdown, the 10s
 * linear fade, the 60s "don't cut a track off" tail rule, and end-of-track
 * conversion. No Android or player dependencies. Mirrors the iOS
 * `SleepTimerCoreTests`.
 */
class SleepTimerCoreTest {

  // No track context (unknown duration) unless a test supplies one.
  private fun SleepTimerCore.tickBare(nowMs: Long) = tick(nowMs, trackDurationSec = 0.0, trackPositionSec = 0.0)

  @Test
  fun `inactive is idle`() {
    val t = SleepTimerCore()
    val r = t.tickBare(1000)
    assertFalse(t.isActive)
    assertEquals(1.0, r.fadeFraction, 0.0)
    assertFalse(r.pauseNow)
    assertFalse(r.stateChanged)
  }

  @Test
  fun `duration counts down at full volume until the fade window`() {
    val t = SleepTimerCore()
    t.armDuration(seconds = 100.0, nowMs = 0)
    assertTrue(t.isActive)
    // 50s in: 50s left, well outside the 10s fade — full volume, no pause.
    val mid = t.tickBare(50_000)
    assertEquals(1.0, mid.fadeFraction, 0.0)
    assertFalse(mid.pauseNow)
    assertEquals(50, t.remainingSeconds(50_000))
  }

  @Test
  fun `volume fades linearly over the final 10 seconds`() {
    val t = SleepTimerCore()
    t.armDuration(seconds = 100.0, nowMs = 0)
    // 5s remaining → 0.5 of original volume.
    assertEquals(0.5, t.tickBare(95_000).fadeFraction, 1e-9)
    // exactly 10s remaining → still full (fade starts strictly inside 10s).
    assertEquals(1.0, t.tickBare(90_000).fadeFraction, 0.0)
  }

  @Test
  fun `duration fires a pause at the deadline when no track tail applies`() {
    val t = SleepTimerCore()
    t.armDuration(seconds = 30.0, nowMs = 0)
    // Expired, unknown track duration → fire immediately.
    val r = t.tickBare(30_000)
    assertTrue(r.pauseNow)
    assertTrue(r.stateChanged)
    assertFalse(t.isActive) // cleared after firing
  }

  @Test
  fun `tail rule defers the pause to the end of a nearly-finished track`() {
    val t = SleepTimerCore()
    t.armDuration(seconds = 30.0, nowMs = 0)
    // Deadline hits with 20s left on a 200s track (<= 60s grace): defer, don't pause.
    val deferred = t.tick(30_000, trackDurationSec = 200.0, trackPositionSec = 180.0)
    assertFalse(deferred.pauseNow)
    assertTrue(deferred.stateChanged) // deadline moved
    assertTrue(t.isActive)
    // Track then ends (advanced to a fresh long track) → fire on the pushed deadline.
    val fired = t.tick(51_500, trackDurationSec = 300.0, trackPositionSec = 1.0)
    assertTrue(fired.pauseNow)
    assertFalse(t.isActive)
  }

  @Test
  fun `tail rule extends only once - a stalled position still fires`() {
    val t = SleepTimerCore()
    t.armDuration(seconds = 10.0, nowMs = 0)
    // First expiry with 5s track tail → defer once.
    val first = t.tick(10_000, trackDurationSec = 100.0, trackPositionSec = 95.0)
    assertFalse(first.pauseNow)
    // Position stalled at track end (would loop forever without the guard) → fires.
    val second = t.tick(16_000, trackDurationSec = 100.0, trackPositionSec = 100.0)
    assertTrue(second.pauseNow)
  }

  @Test
  fun `does not fade or pause outside the tail when track has plenty left`() {
    val t = SleepTimerCore()
    t.armDuration(seconds = 30.0, nowMs = 0)
    // Expired but track has 120s left (> 60 grace) → fire (don't ride a long track out).
    val r = t.tick(30_000, trackDurationSec = 200.0, trackPositionSec = 80.0)
    assertTrue(r.pauseNow)
  }

  @Test
  fun `end-of-track waits then converts near the track end`() {
    val t = SleepTimerCore()
    t.armEndOfTrack()
    assertTrue(t.isEndOfTrack)
    assertNull(t.remainingSeconds(1000)) // no concrete deadline yet
    // Far from the end → still waiting, no state change.
    val waiting = t.tick(1000, trackDurationSec = 200.0, trackPositionSec = 10.0)
    assertFalse(waiting.stateChanged)
    assertTrue(t.isEndOfTrack)
    // Within 60s of the end → convert to a concrete deadline.
    val converted = t.tick(2000, trackDurationSec = 200.0, trackPositionSec = 150.0)
    assertTrue(converted.stateChanged)
    assertFalse(t.isEndOfTrack)
    assertTrue(t.isActive)
    // Deadline lands AT the boundary (tail=50s), not 1s past it.
    assertEquals(2000L + 50_000L, t.deadlineEpochMs)
  }

  @Test
  fun `clear resets to inactive`() {
    val t = SleepTimerCore()
    t.armDuration(seconds = 60.0, nowMs = 0)
    t.clear()
    assertFalse(t.isActive)
    assertNull(t.deadlineEpochMs)
    assertNull(t.remainingSeconds(0))
  }

  @Test
  fun `end-of-track fires a pause at the track-end signal`() {
    val t = SleepTimerCore()
    t.armEndOfTrack()
    // Fires at the real track-end signal, before any tail conversion and even
    // when the duration was never known (never ticked).
    val fired = t.fireAtTrackEnd()
    assertTrue(fired.pauseNow)
    assertTrue(fired.stateChanged)
    assertFalse(t.isActive)
  }

  @Test
  fun `end-of-track signal fires after tail conversion`() {
    val t = SleepTimerCore()
    t.armEndOfTrack()
    t.tick(1000, trackDurationSec = 200.0, trackPositionSec = 150.0) // converts to fade
    val fired = t.fireAtTrackEnd()
    assertTrue(fired.pauseNow)
    assertFalse(t.isActive)
  }

  @Test
  fun `fireAtTrackEnd is idempotent`() {
    val t = SleepTimerCore()
    t.armEndOfTrack()
    t.fireAtTrackEnd()
    assertFalse(t.fireAtTrackEnd().pauseNow)
  }

  @Test
  fun `fireAtTrackEnd ignores a duration timer`() {
    val t = SleepTimerCore()
    t.armDuration(seconds = 60.0, nowMs = 0)
    val fired = t.fireAtTrackEnd()
    assertFalse(fired.pauseNow)
    assertTrue(t.isActive)
  }
}
