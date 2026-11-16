package com.margelo.nitro.queueplayer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ProgressEmissionGate] — the pure throttle behind the
 * configurable / background-reduced `onProgress` cadence.
 *
 * Most tests pass `samplerPeriodMs = 0.0` to exercise exact interval semantics;
 * the jitter + quantization tests pass the production `500` to verify the
 * half-sampler tolerance.
 */
class ProgressEmissionGateTest {

  @Test
  fun `first tick always emits`() {
    val gate = ProgressEmissionGate()
    gate.setIntervals(500.0, 500.0, 0.0)
    assertTrue(gate.shouldEmit(0))
  }

  @Test
  fun `foreground cadence suppresses within the interval`() {
    val gate = ProgressEmissionGate()
    gate.setIntervals(1000.0, 1000.0, 0.0)
    assertTrue(gate.shouldEmit(0))
    assertFalse(gate.shouldEmit(500))
    assertFalse(gate.shouldEmit(999))
    assertTrue(gate.shouldEmit(1000))
    assertFalse(gate.shouldEmit(1499))
    assertTrue(gate.shouldEmit(2000))
  }

  @Test
  fun `background uses the slower interval`() {
    val gate = ProgressEmissionGate()
    gate.setIntervals(500.0, 2000.0, 0.0)
    assertTrue(gate.shouldEmit(0))
    gate.setBackgrounded(true)
    assertFalse(gate.shouldEmit(500))
    assertFalse(gate.shouldEmit(1000))
    assertTrue(gate.shouldEmit(2000))
  }

  @Test
  fun `foreground return flushes immediately`() {
    val gate = ProgressEmissionGate()
    gate.setIntervals(500.0, 10_000.0, 0.0)
    assertTrue(gate.shouldEmit(0))
    gate.setBackgrounded(true)
    assertFalse(gate.shouldEmit(5000)) // still within the 10s bg interval
    gate.setBackgrounded(false)
    assertTrue(gate.shouldEmit(5001)) // flush on return
    assertFalse(gate.shouldEmit(5100))
    assertTrue(gate.shouldEmit(5601))
  }

  @Test
  fun `redundant background transition does not flush`() {
    val gate = ProgressEmissionGate()
    gate.setIntervals(500.0, 2000.0, 0.0)
    assertTrue(gate.shouldEmit(0))
    gate.setBackgrounded(true)
    gate.setBackgrounded(true)
    assertFalse(gate.shouldEmit(500))
  }

  @Test
  fun `foreground to foreground does not flush`() {
    val gate = ProgressEmissionGate()
    gate.setIntervals(1000.0, 1000.0, 0.0)
    assertTrue(gate.shouldEmit(0))
    gate.setBackgrounded(false)
    assertFalse(gate.shouldEmit(500))
  }

  @Test
  fun `setIntervals takes effect preserving position`() {
    val gate = ProgressEmissionGate()
    gate.setIntervals(2000.0, 2000.0, 0.0)
    assertTrue(gate.shouldEmit(0))
    assertFalse(gate.shouldEmit(1000))
    gate.setIntervals(500.0, 500.0, 0.0)
    assertTrue(gate.shouldEmit(600))
  }

  @Test
  fun `default interval emits every tick despite jitter`() {
    // interval == the 500ms sampler period: the half-sampler tolerance keeps
    // ticks landing a hair under 500ms apart from dropping.
    val gate = ProgressEmissionGate()
    gate.setIntervals(500.0, 500.0, 500.0)
    assertTrue(gate.shouldEmit(0))
    assertTrue(gate.shouldEmit(480))
    assertTrue(gate.shouldEmit(955))
    assertTrue(gate.shouldEmit(1440))
  }

  @Test
  fun `larger interval quantizes to every nth tick`() {
    val gate = ProgressEmissionGate()
    gate.setIntervals(1000.0, 1000.0, 500.0)
    assertTrue(gate.shouldEmit(0))
    assertFalse(gate.shouldEmit(500))
    assertTrue(gate.shouldEmit(1000))
    assertFalse(gate.shouldEmit(1500))
    assertTrue(gate.shouldEmit(2000))
  }
}
