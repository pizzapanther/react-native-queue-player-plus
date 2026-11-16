package com.margelo.nitro.queueplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * pure Kotlin port of iOS `SkipIndexTests`.
 *
 * Exercises [QueueSkipArithmetic.computeNext] and
 * [QueueSkipArithmetic.computePrevious] across every [QueueSkipRepeatMode]
 * plus wrap behaviour, empty-queue, and not-started guards. The
 * contract is identical on iOS / Android / C++ (same three enum
 * values, same value order 0/1/2, same boundary semantics).
 */
class QueueSkipArithmeticTest {

  // --- raw-value parity (catches spec reordering)

  @Test
  fun `QueueSkipRepeatMode raw values match the nitro-ts RepeatMode spec`() {
    // OFF=0, TRACK=1, QUEUE=2 — the same order as the C++ enum
    // and iOS QueueSkipRepeatMode. The raw integers are part of the
    // cross-platform contract, so pin them.
    assertEquals(0, QueueSkipRepeatMode.OFF.rawValue)
    assertEquals(1, QueueSkipRepeatMode.TRACK.rawValue)
    assertEquals(2, QueueSkipRepeatMode.QUEUE.rawValue)
  }

  // --- computeNext — REPEAT OFF

  @Test
  fun `computeNext — empty queue returns null`() {
    assertNull(
      QueueSkipArithmetic.computeNext(0, -1, QueueSkipRepeatMode.OFF)
    )
  }

  @Test
  fun `computeNext — cur is less than 0 returns null regardless of mode`() {
    for (mode in QueueSkipRepeatMode.entries) {
      assertNull(QueueSkipArithmetic.computeNext(5, -1, mode))
    }
  }

  @Test
  fun `computeNext OFF — mid-queue advances by 1`() {
    assertEquals(
      3,
      QueueSkipArithmetic.computeNext(5, 2, QueueSkipRepeatMode.OFF)
    )
  }

  @Test
  fun `computeNext OFF — at last index returns null (no wrap)`() {
    assertNull(
      QueueSkipArithmetic.computeNext(5, 4, QueueSkipRepeatMode.OFF)
    )
  }

  // --- computeNext — REPEAT TRACK

  @Test
  fun `computeNext TRACK — advances like OFF with no wrap`() {
    // TRACK navigates identically to OFF: advance by 1, null at the
    // last track. Repeat-one persists so the new track loops on
    // natural end (handled in TrackPlayer, not the arithmetic).
    assertEquals(
      1,
      QueueSkipArithmetic.computeNext(5, 0, QueueSkipRepeatMode.TRACK)
    )
    assertEquals(
      3,
      QueueSkipArithmetic.computeNext(5, 2, QueueSkipRepeatMode.TRACK)
    )
    assertEquals(
      null,
      QueueSkipArithmetic.computeNext(5, 4, QueueSkipRepeatMode.TRACK)
    )
  }

  // --- computeNext — REPEAT QUEUE

  @Test
  fun `computeNext QUEUE — mid-queue advances by 1`() {
    assertEquals(
      3,
      QueueSkipArithmetic.computeNext(5, 2, QueueSkipRepeatMode.QUEUE)
    )
  }

  @Test
  fun `computeNext QUEUE — at last index wraps to 0`() {
    assertEquals(
      0,
      QueueSkipArithmetic.computeNext(5, 4, QueueSkipRepeatMode.QUEUE)
    )
  }

  @Test
  fun `computeNext QUEUE — single-item queue wraps to self`() {
    assertEquals(
      0,
      QueueSkipArithmetic.computeNext(1, 0, QueueSkipRepeatMode.QUEUE)
    )
  }

  // --- computePrevious — REPEAT OFF

  @Test
  fun `computePrevious — empty queue returns null`() {
    assertNull(
      QueueSkipArithmetic.computePrevious(0, -1, QueueSkipRepeatMode.OFF)
    )
  }

  @Test
  fun `computePrevious — cur is less than 0 returns null regardless of mode`() {
    for (mode in QueueSkipRepeatMode.entries) {
      assertNull(QueueSkipArithmetic.computePrevious(5, -1, mode))
    }
  }

  @Test
  fun `computePrevious OFF — mid-queue goes back by 1`() {
    assertEquals(
      2,
      QueueSkipArithmetic.computePrevious(5, 3, QueueSkipRepeatMode.OFF)
    )
  }

  @Test
  fun `computePrevious OFF — at index 0 returns null (no wrap)`() {
    // The "tap Previous at track 0 restarts current" UX behaviour
    // is applied by `skipToPrevious`, not here.
    assertNull(
      QueueSkipArithmetic.computePrevious(5, 0, QueueSkipRepeatMode.OFF)
    )
  }

  // --- computePrevious — REPEAT TRACK

  @Test
  fun `computePrevious TRACK — goes back like OFF with no wrap`() {
    assertEquals(
      null,
      QueueSkipArithmetic.computePrevious(5, 0, QueueSkipRepeatMode.TRACK)
    )
    assertEquals(
      2,
      QueueSkipArithmetic.computePrevious(5, 3, QueueSkipRepeatMode.TRACK)
    )
    assertEquals(
      3,
      QueueSkipArithmetic.computePrevious(5, 4, QueueSkipRepeatMode.TRACK)
    )
  }

  // --- computePrevious — REPEAT QUEUE

  @Test
  fun `computePrevious QUEUE — mid-queue goes back by 1`() {
    assertEquals(
      2,
      QueueSkipArithmetic.computePrevious(5, 3, QueueSkipRepeatMode.QUEUE)
    )
  }

  @Test
  fun `computePrevious QUEUE — at index 0 wraps to last`() {
    assertEquals(
      4,
      QueueSkipArithmetic.computePrevious(5, 0, QueueSkipRepeatMode.QUEUE)
    )
  }

  @Test
  fun `computePrevious QUEUE — single-item queue wraps to self`() {
    assertEquals(
      0,
      QueueSkipArithmetic.computePrevious(1, 0, QueueSkipRepeatMode.QUEUE)
    )
  }

  // --- trackCount edge cases

  @Test
  fun `computeNext — cur at or past trackCount returns null in OFF`() {
    // Defensive: the caller should keep cur in range, but the
    // helper must still be total — no crash, just a null bail-out.
    assertNull(
      QueueSkipArithmetic.computeNext(5, 5, QueueSkipRepeatMode.OFF)
    )
    assertNull(
      QueueSkipArithmetic.computeNext(5, 10, QueueSkipRepeatMode.OFF)
    )
  }

  @Test
  fun `computeNext QUEUE — cur at or past trackCount wraps to 0`() {
    // Consistent with the "last → 0" wrap branch: any out-of-range
    // cur past trackCount produces currentIndex + 1 > trackCount →
    // falls through to 0. This is the caller's responsibility to
    // avoid, but the helper remains total.
    assertEquals(
      0,
      QueueSkipArithmetic.computeNext(5, 5, QueueSkipRepeatMode.QUEUE)
    )
  }

  @Test
  fun `computePrevious QUEUE — cur at or past trackCount walks back by 1`() {
    // Symmetric to the computeNext out-of-range branch above.
    // Defensive totality, not a contract consumers should rely on.
    assertEquals(
      4,
      QueueSkipArithmetic.computePrevious(5, 5, QueueSkipRepeatMode.QUEUE)
    )
  }

  // --- canSkipNext

  @Test
  fun `canSkipNext — empty queue is false`() {
    assertFalse(QueueSkipArithmetic.canSkipNext(0, -1, QueueSkipRepeatMode.OFF))
  }

  @Test
  fun `canSkipNext — nothing playing is false in any mode`() {
    for (mode in QueueSkipRepeatMode.entries) {
      assertFalse(QueueSkipArithmetic.canSkipNext(5, -1, mode))
    }
  }

  @Test
  fun `canSkipNext OFF — mid-queue is true`() {
    assertTrue(QueueSkipArithmetic.canSkipNext(5, 2, QueueSkipRepeatMode.OFF))
  }

  @Test
  fun `canSkipNext OFF — at last index is false`() {
    assertFalse(QueueSkipArithmetic.canSkipNext(5, 4, QueueSkipRepeatMode.OFF))
  }

  @Test
  fun `canSkipNext OFF — single-track queue is false`() {
    assertFalse(QueueSkipArithmetic.canSkipNext(1, 0, QueueSkipRepeatMode.OFF))
  }

  @Test
  fun `canSkipNext QUEUE — at last wraps so true`() {
    assertTrue(QueueSkipArithmetic.canSkipNext(5, 4, QueueSkipRepeatMode.QUEUE))
  }

  @Test
  fun `canSkipNext QUEUE — single-track wraps to self so true`() {
    assertTrue(QueueSkipArithmetic.canSkipNext(1, 0, QueueSkipRepeatMode.QUEUE))
  }

  @Test
  fun `canSkipNext TRACK — true except at the last track (no wrap)`() {
    for (idx in 0 until 4) {
      assertTrue(
        "canSkipNext should be true at index $idx under TRACK",
        QueueSkipArithmetic.canSkipNext(5, idx, QueueSkipRepeatMode.TRACK)
      )
    }
    assertFalse(
      "canSkipNext should be false at the last track under TRACK",
      QueueSkipArithmetic.canSkipNext(5, 4, QueueSkipRepeatMode.TRACK)
    )
  }

  // --- canSkipPrevious
  //
  // Symmetric with canSkipNext — defined as `computePrevious(...) !=
  // null`. Apps that want "tap Previous restarts current at >3s"
  // implement that themselves in their button handler.

  @Test
  fun `canSkipPrevious — empty queue is false`() {
    assertFalse(QueueSkipArithmetic.canSkipPrevious(0, -1, QueueSkipRepeatMode.OFF))
  }

  @Test
  fun `canSkipPrevious — nothing playing is false in any mode`() {
    for (mode in QueueSkipRepeatMode.entries) {
      assertFalse(QueueSkipArithmetic.canSkipPrevious(5, -1, mode))
    }
  }

  @Test
  fun `canSkipPrevious OFF — mid-queue is true`() {
    assertTrue(QueueSkipArithmetic.canSkipPrevious(5, 2, QueueSkipRepeatMode.OFF))
  }

  @Test
  fun `canSkipPrevious OFF — at index 0 is false`() {
    // No previous track in OFF at index 0 — computePrevious returns
    // null. App can implement restart-on-tap-prev itself by reading
    // position; the lib reports the primitive.
    assertFalse(QueueSkipArithmetic.canSkipPrevious(5, 0, QueueSkipRepeatMode.OFF))
  }

  @Test
  fun `canSkipPrevious OFF — single-track is false`() {
    assertFalse(QueueSkipArithmetic.canSkipPrevious(1, 0, QueueSkipRepeatMode.OFF))
  }

  @Test
  fun `canSkipPrevious QUEUE — at index 0 wraps so true`() {
    assertTrue(QueueSkipArithmetic.canSkipPrevious(5, 0, QueueSkipRepeatMode.QUEUE))
  }

  @Test
  fun `canSkipPrevious QUEUE — single-track wraps to self so true`() {
    assertTrue(QueueSkipArithmetic.canSkipPrevious(1, 0, QueueSkipRepeatMode.QUEUE))
  }

  @Test
  fun `canSkipPrevious TRACK — true except at index 0 (no wrap)`() {
    assertFalse(
      "canSkipPrevious should be false at index 0 under TRACK",
      QueueSkipArithmetic.canSkipPrevious(5, 0, QueueSkipRepeatMode.TRACK)
    )
    for (idx in 1 until 5) {
      assertTrue(
        "canSkipPrevious should be true at index $idx under TRACK",
        QueueSkipArithmetic.canSkipPrevious(5, idx, QueueSkipRepeatMode.TRACK)
      )
    }
  }

  @Test
  fun `TRACK single-track queue — no next, no previous (no wrap)`() {
    assertEquals(null, QueueSkipArithmetic.computeNext(1, 0, QueueSkipRepeatMode.TRACK))
    assertEquals(null, QueueSkipArithmetic.computePrevious(1, 0, QueueSkipRepeatMode.TRACK))
    assertFalse(QueueSkipArithmetic.canSkipNext(1, 0, QueueSkipRepeatMode.TRACK))
    assertFalse(QueueSkipArithmetic.canSkipPrevious(1, 0, QueueSkipRepeatMode.TRACK))
  }

  // --- resolveSkipToPrevious — mode OFF (position ignored, current behavior)

  private fun resolve(
    count: Int = 3, index: Int, mode: QueueSkipRepeatMode,
    restart: Boolean, positionMs: Long
  ) = QueueSkipArithmetic.resolveSkipToPrevious(count, index, mode, restart, positionMs)

  private val previous1 = QueueSkipArithmetic.SkipToPreviousAction.Previous(1)
  private val previous2 = QueueSkipArithmetic.SkipToPreviousAction.Previous(2)
  private val restart = QueueSkipArithmetic.SkipToPreviousAction.RestartCurrent
  private val noOp = QueueSkipArithmetic.SkipToPreviousAction.NoOp

  @Test
  fun `resolve mode OFF ignores position and goes previous`() {
    assertEquals(previous1, resolve(index = 2, mode = QueueSkipRepeatMode.OFF, restart = false, positionMs = 99_000L))
  }

  @Test
  fun `resolve mode OFF first track is noop`() {
    assertEquals(noOp, resolve(index = 0, mode = QueueSkipRepeatMode.OFF, restart = false, positionMs = 99_000L))
  }

  @Test
  fun `resolve mode OFF queue wraps`() {
    assertEquals(previous2, resolve(index = 0, mode = QueueSkipRepeatMode.QUEUE, restart = false, positionMs = 0L))
  }

  @Test
  fun `resolve mode OFF empty queue is noop`() {
    assertEquals(noOp, resolve(count = 0, index = -1, mode = QueueSkipRepeatMode.OFF, restart = false, positionMs = 0L))
  }

  // --- resolveSkipToPrevious — mode ON (3s threshold model)

  @Test
  fun `resolve past threshold restarts even with a previous`() {
    for (pos in listOf(3001L, 5000L, 600_000L)) {
      assertEquals("position $pos > 3000 restarts", restart,
        resolve(index = 2, mode = QueueSkipRepeatMode.OFF, restart = true, positionMs = pos))
    }
  }

  @Test
  fun `resolve threshold boundary goes previous, one past restarts`() {
    assertEquals(previous1, resolve(index = 2, mode = QueueSkipRepeatMode.OFF, restart = true, positionMs = 3000L))
    assertEquals(restart, resolve(index = 2, mode = QueueSkipRepeatMode.OFF, restart = true, positionMs = 3001L))
  }

  @Test
  fun `resolve under threshold goes previous`() {
    for (pos in listOf(0L, 1L, 2999L)) {
      assertEquals("position $pos <= 3000 goes previous", previous1,
        resolve(index = 2, mode = QueueSkipRepeatMode.OFF, restart = true, positionMs = pos))
    }
  }

  @Test
  fun `resolve first track under threshold restarts when no previous`() {
    assertEquals(restart, resolve(index = 0, mode = QueueSkipRepeatMode.OFF, restart = true, positionMs = 1000L))
    assertEquals(restart, resolve(index = 0, mode = QueueSkipRepeatMode.TRACK, restart = true, positionMs = 1000L))
  }

  @Test
  fun `resolve first track under threshold queue wraps`() {
    assertEquals(previous2, resolve(index = 0, mode = QueueSkipRepeatMode.QUEUE, restart = true, positionMs = 1000L))
  }

  @Test
  fun `resolve first track past threshold restarts under every mode`() {
    for (mode in QueueSkipRepeatMode.values()) {
      assertEquals("first track past threshold restarts under $mode", restart,
        resolve(index = 0, mode = mode, restart = true, positionMs = 9000L))
    }
  }

  @Test
  fun `resolve mode ON empty queue is noop`() {
    assertEquals(noOp, resolve(count = 0, index = -1, mode = QueueSkipRepeatMode.OFF, restart = true, positionMs = 9000L))
  }

  // --- canSkipPrevious with restartEnabled (forced true)

  @Test
  fun `canSkipPrevious forced true at every index and mode`() {
    for (mode in QueueSkipRepeatMode.values()) {
      for (index in 0 until 3) {
        assertTrue("restart mode forces canSkipPrevious true at index $index under $mode",
          QueueSkipArithmetic.canSkipPrevious(3, index, mode, restartEnabled = true))
      }
    }
  }

  @Test
  fun `canSkipPrevious restartEnabled false for empty queue`() {
    assertFalse(QueueSkipArithmetic.canSkipPrevious(0, -1, QueueSkipRepeatMode.OFF, restartEnabled = true))
  }

  @Test
  fun `canSkipPrevious restart disabled matches existing`() {
    assertFalse(QueueSkipArithmetic.canSkipPrevious(3, 0, QueueSkipRepeatMode.OFF, restartEnabled = false))
    assertTrue(QueueSkipArithmetic.canSkipPrevious(3, 1, QueueSkipRepeatMode.OFF, restartEnabled = false))
  }

  @Test
  fun `canSkipPrevious restartEnabled — nothing playing is false in any mode`() {
    // Nothing is loaded, so there is no track to restart and no previous
    // track to reach: resolveSkipToPrevious returns NoOp for this state.
    for (mode in QueueSkipRepeatMode.entries) {
      assertFalse(QueueSkipArithmetic.canSkipPrevious(5, -1, mode, restartEnabled = true))
    }
  }

  @Test
  fun `canSkipPrevious agrees with resolveSkipToPrevious`() {
    // canSkipPrevious is the enabled-state of the same decision
    // resolveSkipToPrevious makes; the two must never disagree.
    for (count in listOf(0, 1, 3)) {
      for (index in listOf(-1, 0, 1, 2)) {
        for (mode in QueueSkipRepeatMode.entries) {
          for (restart in listOf(false, true)) {
            val can = QueueSkipArithmetic.canSkipPrevious(count, index, mode, restart)
            val resolved = QueueSkipArithmetic.resolveSkipToPrevious(
              count, index, mode, restart, positionMs = 0
            )
            assertEquals(
              "count=$count index=$index mode=$mode restart=$restart",
              can,
              resolved != QueueSkipArithmetic.SkipToPreviousAction.NoOp
            )
          }
        }
      }
    }
  }
}
