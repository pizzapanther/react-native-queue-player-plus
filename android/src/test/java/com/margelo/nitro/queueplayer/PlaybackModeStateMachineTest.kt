package com.margelo.nitro.queueplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Transition-matrix coverage for `PlaybackModeStateMachine`. The
 * machine is pure data (no platform calls); Robolectric only
 * provides the @Test JUnit harness.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackModeStateMachineTest {

  private val gapless = PlaybackMode(PlaybackModeKind.GAPLESS, null)
  private val crossfade5s = PlaybackMode(PlaybackModeKind.CROSSFADE, 5_000.0)
  private val crossfade7s = PlaybackMode(PlaybackModeKind.CROSSFADE, 7_000.0)

  private val machine = PlaybackModeStateMachine()

  @Test
  fun `gapless to gapless is no-op`() {
    val (next, effect) = machine.setRequested(
      state = PlaybackModeStateMachine.State(active = gapless),
      requested = gapless,
    )
    assertSame(PlaybackModeStateMachine.TransitionEffect.NoOp, effect)
    assertEquals(gapless, next.active)
  }

  @Test
  fun `crossfade to identical crossfade is no-op`() {
    val (next, effect) = machine.setRequested(
      state = PlaybackModeStateMachine.State(active = crossfade5s),
      requested = PlaybackMode(PlaybackModeKind.CROSSFADE, 5_000.0),
    )
    assertSame(PlaybackModeStateMachine.TransitionEffect.NoOp, effect)
    assertEquals(crossfade5s, next.active)
  }

  @Test
  fun `gapless to crossfade swaps immediately`() {
    val (next, effect) = machine.setRequested(
      state = PlaybackModeStateMachine.State(active = gapless),
      requested = crossfade5s,
    )
    assertSame(PlaybackModeStateMachine.TransitionEffect.ImmediateSwap, effect)
    assertEquals(crossfade5s, next.active)
  }

  @Test
  fun `crossfade to gapless swaps immediately`() {
    val (next, effect) = machine.setRequested(
      state = PlaybackModeStateMachine.State(active = crossfade5s),
      requested = gapless,
    )
    assertSame(PlaybackModeStateMachine.TransitionEffect.ImmediateSwap, effect)
    assertEquals(gapless, next.active)
  }

  @Test
  fun `crossfade duration change swaps immediately`() {
    val (next, effect) = machine.setRequested(
      state = PlaybackModeStateMachine.State(active = crossfade5s),
      requested = crossfade7s,
    )
    assertSame(PlaybackModeStateMachine.TransitionEffect.ImmediateSwap, effect)
    assertEquals(crossfade7s, next.active)
  }

  @Test
  fun `crossfade mode with null duration is rejected`() {
    val invalid = PlaybackMode(PlaybackModeKind.CROSSFADE, null)
    assertThrows(IllegalArgumentException::class.java) {
      machine.setRequested(
        state = PlaybackModeStateMachine.State(active = gapless),
        requested = invalid,
      )
    }
  }

  @Test
  fun `crossfade duration below 1000ms is rejected`() {
    listOf(-100.0, 0.0, 500.0, 999.0).forEach { ms ->
      val invalid = PlaybackMode(PlaybackModeKind.CROSSFADE, ms)
      assertThrows(IllegalArgumentException::class.java) {
        machine.setRequested(
          state = PlaybackModeStateMachine.State(active = gapless),
          requested = invalid,
        )
      }
    }
  }

  @Test
  fun `crossfade duration above 12000ms is rejected`() {
    listOf(12_001.0, 15_000.0, 60_000.0).forEach { ms ->
      val invalid = PlaybackMode(PlaybackModeKind.CROSSFADE, ms)
      assertThrows(IllegalArgumentException::class.java) {
        machine.setRequested(
          state = PlaybackModeStateMachine.State(active = gapless),
          requested = invalid,
        )
      }
    }
  }

  @Test
  fun `crossfade duration not on 500ms step is rejected`() {
    listOf(1_001.0, 1_234.0, 5_500.5, 7_777.0).forEach { ms ->
      val invalid = PlaybackMode(PlaybackModeKind.CROSSFADE, ms)
      assertThrows(IllegalArgumentException::class.java) {
        machine.setRequested(
          state = PlaybackModeStateMachine.State(active = gapless),
          requested = invalid,
        )
      }
    }
  }

  @Test
  fun `crossfade non-finite duration is rejected`() {
    listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { ms ->
      val invalid = PlaybackMode(PlaybackModeKind.CROSSFADE, ms)
      assertThrows(IllegalArgumentException::class.java) {
        machine.setRequested(
          state = PlaybackModeStateMachine.State(active = gapless),
          requested = invalid,
        )
      }
    }
  }

  @Test
  fun `crossfade duration on every 500ms boundary in range is accepted`() {
    var ms = CrossfadeDurationBounds.MIN_MS
    while (ms <= CrossfadeDurationBounds.MAX_MS) {
      val mode = PlaybackMode(PlaybackModeKind.CROSSFADE, ms)
      val (next, _) = machine.setRequested(
        state = PlaybackModeStateMachine.State(active = gapless),
        requested = mode,
      )
      assertEquals(ms, next.active.crossfadeDurationMs)
      ms += CrossfadeDurationBounds.STEP_MS
    }
  }

  @Test
  fun `Initial seed state is gapless`() {
    assertEquals(gapless, PlaybackModeStateMachine.Initial.active)
  }
}
