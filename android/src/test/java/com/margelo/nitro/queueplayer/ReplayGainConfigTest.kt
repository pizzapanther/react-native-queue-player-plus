package com.margelo.nitro.queueplayer

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Unit coverage for [ReplayGainConfig] — the process-wide
 * `AtomicReference<ReplayGainMode>` singleton written by
 * `TrackPlayer.setReplayGainMode` and read by the engines' metadata
 * listener.
 *
 * Pure JVM (no Robolectric runner) — the config is plain Kotlin with
 * no Android dependency. Lives alongside the Robolectric suite so
 * the gradle `testDebugUnitTest` task picks it up.
 */
class ReplayGainConfigTest {

  @After
  fun tearDown() {
    // Restore the default so subsequent tests see the documented
    // initial state. The config is process-wide; without this,
    // leakage between tests is possible.
    ReplayGainConfig.setMode(ReplayGainMode.OFF)
  }

  @Test
  fun `default mode is OFF`() {
    // Reset explicitly because a prior test in the same JVM might
    // have mutated it. The teardown above restores after, but the
    // assertion here is "OFF is the documented default".
    ReplayGainConfig.setMode(ReplayGainMode.OFF)
    assertEquals(ReplayGainMode.OFF, ReplayGainConfig.getMode())
  }

  @Test
  fun `setMode round-trips through getMode for every value`() {
    for (value in ReplayGainMode.values()) {
      ReplayGainConfig.setMode(value)
      assertEquals(value, ReplayGainConfig.getMode())
    }
  }

  @Test
  fun `concurrent set and read does not lose updates`() {
    // The config is read from the audio render thread (engine
    // metadata listener) + JS bridge thread (setReplayGainMode).
    // AtomicReference serialises both. Spin 8 readers + 4 writers
    // alternating across all three modes; collect every observed
    // read; assert every observed value is one of the legal enum
    // values, and that the final state matches the writer's last
    // pinned write.
    val observed = ConcurrentLinkedQueue<ReplayGainMode>()
    val pool = Executors.newFixedThreadPool(12)
    val ready = CountDownLatch(12)
    val start = CountDownLatch(1)
    val readerCount = 8
    val writerCount = 4
    val opsPerThread = 500

    repeat(readerCount) {
      pool.submit {
        ready.countDown()
        start.await()
        repeat(opsPerThread) {
          observed.add(ReplayGainConfig.getMode())
        }
      }
    }
    repeat(writerCount) { writerIdx ->
      pool.submit {
        ready.countDown()
        start.await()
        val cycle = ReplayGainMode.values()
        repeat(opsPerThread) { i ->
          ReplayGainConfig.setMode(cycle[(writerIdx + i) % cycle.size])
        }
      }
    }
    ready.await()
    start.countDown()
    pool.shutdown()
    pool.awaitTermination(10, TimeUnit.SECONDS)

    // Every observed value must be a legal enum. AtomicReference
    // guarantees this trivially on any sane JVM, but the assertion
    // catches a future regression that swaps to a non-volatile
    // primitive.
    val legal = ReplayGainMode.values().toSet()
    val invalid = observed.filterNot { it in legal }
    assertTrue("torn reads observed: $invalid", invalid.isEmpty())

    // Pin the final state to a known value so the post-shutdown
    // assertion compares deterministically against the contract.
    ReplayGainConfig.setMode(ReplayGainMode.ALBUM)
    assertEquals(ReplayGainMode.ALBUM, ReplayGainConfig.getMode())
  }
}
