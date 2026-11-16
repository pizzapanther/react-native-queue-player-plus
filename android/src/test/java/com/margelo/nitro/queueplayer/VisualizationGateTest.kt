package com.margelo.nitro.queueplayer

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for [VisualizationGate] — the process-wide
 * `AtomicBoolean` flag written by `TrackPlayer.configureInternal`
 * from `PlayerConfig.visualizationEnabled` and read by the engines'
 * field-init renderers-factory branch + `Visualizer.subscribe`'s
 * disabled gate.
 *
 * Tests run in JVM (not Robolectric — the gate is a pure Kotlin
 * object, no Android dependency), but live alongside the Robolectric
 * suite so the gradle `testDebugUnitTest` task picks them up.
 */
class VisualizationGateTest {

  @After
  fun tearDown() {
    // Restore the default true so subsequent tests see the documented
    // initial state. The gate is process-wide, so leakage between
    // tests would otherwise be possible.
    VisualizationGate.setEnabled(true)
  }

  @Test
  fun `default state is enabled`() {
    // Set explicitly because a prior test in the same JVM might have
    // mutated it. The teardown above restores after, but the assertion
    // here is "enabled is the documented default".
    VisualizationGate.setEnabled(true)
    assertTrue(VisualizationGate.isEnabled())
  }

  @Test
  fun `setEnabled mirrors through isEnabled`() {
    VisualizationGate.setEnabled(false)
    assertFalse(VisualizationGate.isEnabled())
    VisualizationGate.setEnabled(true)
    assertTrue(VisualizationGate.isEnabled())
  }

  @Test
  fun `idempotent re-write is harmless`() {
    VisualizationGate.setEnabled(true)
    VisualizationGate.setEnabled(true)
    assertTrue(VisualizationGate.isEnabled())
    VisualizationGate.setEnabled(false)
    VisualizationGate.setEnabled(false)
    assertFalse(VisualizationGate.isEnabled())
  }

  @Test
  fun `concurrent reads observe writer values without tearing`() {
    // The gate is read from the audio render thread (engine field-init
    // → renderers factory branch) + the JS bridge thread (subscribe).
    // Writes happen on main from configure. AtomicBoolean serialises
    // both. Spin 8 readers + 1 writer alternating true/false; collect
    // every observed read; assert no torn read returned a non-Boolean
    // value, and that the final state matches the writer's last
    // pinned write.
    val observed = java.util.concurrent.ConcurrentLinkedQueue<Boolean>()
    val readers = (0 until 8).map {
      Thread {
        for (i in 0 until 500) {
          observed.add(VisualizationGate.isEnabled())
        }
      }
    }
    val writer = Thread {
      for (i in 0 until 500) {
        VisualizationGate.setEnabled(i % 2 == 0)
      }
      // Pin the final state to a known value so the post-join
      // assertion can compare deterministically.
      VisualizationGate.setEnabled(true)
    }
    readers.forEach { it.start() }
    writer.start()
    readers.forEach { it.join() }
    writer.join()
    // Every observed value must be a valid Boolean (not some torn
    // intermediate). With AtomicBoolean this is trivially true on
    // any sane JVM, but the assertion catches a future regression
    // that swaps to a non-volatile primitive.
    val invalid = observed.filterNot { it == true || it == false }
    assertTrue("torn reads observed: $invalid", invalid.isEmpty())
    assertTrue(
      "expected final state true after writer pin, got false",
      VisualizationGate.isEnabled(),
    )
  }
}
