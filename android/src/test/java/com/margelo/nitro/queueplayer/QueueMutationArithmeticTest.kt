package com.margelo.nitro.queueplayer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * pure Kotlin tests for the queue-mutation
 * index-shift arithmetic. Mirrors the shape of
 * [QueueSkipArithmeticTest]: no Android framework needed, just
 * the helper under test.
 *
 * Covers each of the three mutation arithmetic helpers — add /
 * remove / move — against the cur-empty, cur-below-shift-point,
 * cur-at-shift-point, cur-above-shift-point, and boundary cases.
 */
class QueueMutationArithmeticTest {

  // --- clampInsertBefore

  @Test
  fun `clampInsertBefore null appends to the end of the queue`() {
    assertEquals(5, QueueMutationArithmetic.clampInsertBefore(null, 5))
  }

  @Test
  fun `clampInsertBefore negative clamps to 0`() {
    assertEquals(0, QueueMutationArithmetic.clampInsertBefore(-1, 5))
    assertEquals(0, QueueMutationArithmetic.clampInsertBefore(-100, 5))
  }

  @Test
  fun `clampInsertBefore past trackCount clamps to trackCount`() {
    assertEquals(5, QueueMutationArithmetic.clampInsertBefore(100, 5))
  }

  @Test
  fun `clampInsertBefore in-range passes through`() {
    assertEquals(3, QueueMutationArithmetic.clampInsertBefore(3, 5))
    assertEquals(0, QueueMutationArithmetic.clampInsertBefore(0, 5))
    assertEquals(5, QueueMutationArithmetic.clampInsertBefore(5, 5))
  }

  // --- currentIndexAfterAdd

  @Test
  fun `currentIndexAfterAdd — empty queue cur stays -1`() {
    assertEquals(-1, QueueMutationArithmetic.currentIndexAfterAdd(-1, 0, 3))
  }

  @Test
  fun `currentIndexAfterAdd — insert count 0 is a no-op`() {
    assertEquals(2, QueueMutationArithmetic.currentIndexAfterAdd(2, 0, 0))
  }

  @Test
  fun `currentIndexAfterAdd — insert at exactly cur shifts cur forward`() {
    // Insert of 3 items at position 2 when cur == 2 moves cur to 5.
    assertEquals(5, QueueMutationArithmetic.currentIndexAfterAdd(2, 2, 3))
  }

  @Test
  fun `currentIndexAfterAdd — insert before cur shifts cur forward`() {
    assertEquals(4, QueueMutationArithmetic.currentIndexAfterAdd(2, 1, 2))
  }

  @Test
  fun `currentIndexAfterAdd — insert after cur leaves cur unchanged`() {
    assertEquals(2, QueueMutationArithmetic.currentIndexAfterAdd(2, 4, 3))
  }

  @Test
  fun `currentIndexAfterAdd — insert at queue tail (past cur) leaves cur unchanged`() {
    assertEquals(2, QueueMutationArithmetic.currentIndexAfterAdd(2, 5, 3))
  }

  // --- sanitiseRemoveIndices

  @Test
  fun `sanitiseRemoveIndices drops negative and out-of-range entries`() {
    val result = QueueMutationArithmetic.sanitiseRemoveIndices(
      rawIndices = listOf(-1, 0, 2, 5, 100),
      trackCount = 5
    )
    assertEquals(listOf(0, 2), result)
  }

  @Test
  fun `sanitiseRemoveIndices dedups and sorts ascending`() {
    val result = QueueMutationArithmetic.sanitiseRemoveIndices(
      rawIndices = listOf(3, 1, 3, 2, 1),
      trackCount = 5
    )
    assertEquals(listOf(1, 2, 3), result)
  }

  @Test
  fun `sanitiseRemoveIndices returns empty for empty input`() {
    val result = QueueMutationArithmetic.sanitiseRemoveIndices(
      emptyList(), 5
    )
    assertEquals(emptyList<Int>(), result)
  }

  @Test
  fun `sanitiseRemoveIndices returns empty when trackCount is 0`() {
    assertEquals(
      emptyList<Int>(),
      QueueMutationArithmetic.sanitiseRemoveIndices(listOf(0, 1), 0)
    )
  }

  // --- currentIndexAfterRemove

  @Test
  fun `currentIndexAfterRemove — cur less than 0 stays -1`() {
    assertEquals(
      -1,
      QueueMutationArithmetic.currentIndexAfterRemove(-1, listOf(0, 1), 5)
    )
  }

  @Test
  fun `currentIndexAfterRemove — removing entries below cur shifts cur down`() {
    // cur=3, remove [0,1] → cur shifts to 1 (3 - 2 = 1).
    assertEquals(
      1,
      QueueMutationArithmetic.currentIndexAfterRemove(3, listOf(0, 1), 5)
    )
  }

  @Test
  fun `currentIndexAfterRemove — removing entries above cur leaves cur unchanged`() {
    // cur=1, remove [3,4] → cur stays 1.
    assertEquals(
      1,
      QueueMutationArithmetic.currentIndexAfterRemove(1, listOf(3, 4), 5)
    )
  }

  @Test
  fun `currentIndexAfterRemove — removing cur itself keeps cur (auto-advance semantics)`() {
    // cur=2, remove [2] → newCur stays at 2 (now points to what
    // was previously at 3). Matches iOS auto-advance contract.
    assertEquals(
      2,
      QueueMutationArithmetic.currentIndexAfterRemove(2, listOf(2), 5)
    )
  }

  @Test
  fun `currentIndexAfterRemove — removing cur when cur was at tail clamps to new tail`() {
    // cur=4 (last), remove [4] → newTrackCount=4, cur clamps to 3.
    assertEquals(
      3,
      QueueMutationArithmetic.currentIndexAfterRemove(4, listOf(4), 5)
    )
  }

  @Test
  fun `currentIndexAfterRemove — removing everything returns -1`() {
    assertEquals(
      -1,
      QueueMutationArithmetic.currentIndexAfterRemove(2, listOf(0, 1, 2, 3, 4), 5)
    )
  }

  @Test
  fun `currentIndexAfterRemove — mixed below-and-cur case combines shift + clamp`() {
    // cur=3, remove [0, 3] → removedBelowCur=1, newCur=3-1=2;
    // cur was removed, but 2 < newTrackCount(3) so no clamp.
    assertEquals(
      2,
      QueueMutationArithmetic.currentIndexAfterRemove(3, listOf(0, 3), 5)
    )
  }

  // --- currentIndexAfterMove

  @Test
  fun `currentIndexAfterMove — cur less than 0 stays negative`() {
    assertEquals(
      -1,
      QueueMutationArithmetic.currentIndexAfterMove(-1, 0, 2)
    )
  }

  @Test
  fun `currentIndexAfterMove — moving the cur track tracks it`() {
    // cur=2, move [2 → 4]. newCur = 4.
    assertEquals(
      4,
      QueueMutationArithmetic.currentIndexAfterMove(2, 2, 4)
    )
  }

  @Test
  fun `currentIndexAfterMove — slide past cur from below shifts cur down`() {
    // cur=3, move [1 → 4] (from<cur, to>=cur). newCur = 2.
    assertEquals(
      2,
      QueueMutationArithmetic.currentIndexAfterMove(3, 1, 4)
    )
  }

  @Test
  fun `currentIndexAfterMove — slide past cur from above shifts cur up`() {
    // cur=1, move [3 → 0] (from>cur, to<=cur). newCur = 2.
    assertEquals(
      2,
      QueueMutationArithmetic.currentIndexAfterMove(1, 3, 0)
    )
  }

  @Test
  fun `currentIndexAfterMove — move entirely below cur leaves cur alone`() {
    // cur=4, move [0 → 2]. Neither from<cur && to>=cur nor
    // from>cur && to<=cur applies. newCur = 4.
    assertEquals(
      4,
      QueueMutationArithmetic.currentIndexAfterMove(4, 0, 2)
    )
  }

  @Test
  fun `currentIndexAfterMove — move entirely above cur leaves cur alone`() {
    // cur=1, move [3 → 4]. newCur = 1.
    assertEquals(
      1,
      QueueMutationArithmetic.currentIndexAfterMove(1, 3, 4)
    )
  }

  @Test
  fun `currentIndexAfterMove — move to exactly cur from below shifts cur down by 1`() {
    // cur=2, from=0, to=2. from<cur && to>=cur (to == cur).
    // newCur = 1.
    assertEquals(
      1,
      QueueMutationArithmetic.currentIndexAfterMove(2, 0, 2)
    )
  }

  @Test
  fun `currentIndexAfterMove — move to exactly cur from above shifts cur up by 1`() {
    // cur=2, from=4, to=2. from>cur && to<=cur (to == cur).
    // newCur = 3.
    assertEquals(
      3,
      QueueMutationArithmetic.currentIndexAfterMove(2, 4, 2)
    )
  }
}
