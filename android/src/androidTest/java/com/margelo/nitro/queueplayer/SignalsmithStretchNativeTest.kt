package com.margelo.nitro.queueplayer

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * On-device smoke test for the Signalsmith Stretch JNI binding. Runs on a
 * device/emulator — the native .so cannot be loaded on the JVM, so this is an
 * instrumented test, not Robolectric. Proves the library loads, a stretcher
 * can be created, and process()/flush() honour the requested frame counts and
 * produce audible output.
 */
@RunWith(AndroidJUnit4::class)
class SignalsmithStretchNativeTest {

  private val sampleRate = 48000f
  private val channels = 2

  private fun directFloatBuffer(frames: Int): ByteBuffer =
    ByteBuffer.allocateDirect(frames * channels * 4).order(ByteOrder.nativeOrder())

  // Fill `buf` with `frames` of a stereo 440 Hz sine at amplitude 0.5.
  private fun fillSine(buf: ByteBuffer, frames: Int) {
    val floats = buf.asFloatBuffer()
    for (i in 0 until frames) {
      val s = (0.5 * sin(2.0 * PI * 440.0 * i / sampleRate)).toFloat()
      floats.put(i * channels, s)
      floats.put(i * channels + 1, s)
    }
  }

  // Peak absolute sample over the frame range [fromFrame, toFrame).
  private fun maxAbs(buf: ByteBuffer, fromFrame: Int, toFrame: Int): Float {
    val floats = buf.asFloatBuffer()
    var peak = 0f
    for (i in fromFrame * channels until toFrame * channels) {
      peak = maxOf(peak, abs(floats.get(i)))
    }
    return peak
  }

  @Test
  fun processAtUnityRatioProducesAudibleOutput() {
    val n = 48000
    val input = directFloatBuffer(n).also { fillSine(it, n) }
    val output = directFloatBuffer(n)
    val stretch = SignalsmithStretchNative.create(channels, sampleRate)
    try {
      assertEquals(n, stretch.process(input, n, output, n))
      // The first outputLatency frames are priming; the tail is the delayed
      // signal and must be audibly non-silent.
      assertTrue("output tail is silent", maxAbs(output, n / 2, n) > 0.05f)
    } finally {
      stretch.release()
    }
  }

  @Test
  fun processAtStretchRatioProducesRequestedFrames() {
    val inN = 48000
    val outN = 72000 // 1.5x slowdown — ratio is outFrames/inFrames
    val input = directFloatBuffer(inN).also { fillSine(it, inN) }
    val output = directFloatBuffer(outN)
    val stretch = SignalsmithStretchNative.create(channels, sampleRate)
    try {
      assertEquals(outN, stretch.process(input, inN, output, outN))
      assertTrue("stretched tail is silent", maxAbs(output, outN / 2, outN) > 0.05f)
    } finally {
      stretch.release()
    }
  }

  @Test
  fun outputLatencyIsPositiveAndFlushDrainsTail() {
    val n = 48000
    val input = directFloatBuffer(n).also { fillSine(it, n) }
    val output = directFloatBuffer(n)
    val stretch = SignalsmithStretchNative.create(channels, sampleRate)
    try {
      stretch.process(input, n, output, n)
      val latency = stretch.outputLatency()
      assertTrue("expected positive output latency", latency > 0)
      val tail = directFloatBuffer(latency)
      assertEquals(latency, stretch.flush(tail, latency))
      // The drained tail is attenuated vs the body, so use a lower floor than
      // the process() assertions — but still meaningfully above silence.
      assertTrue("flushed tail is silent", maxAbs(tail, 0, latency) > 0.01f)
    } finally {
      stretch.release()
    }
  }

  @Test
  fun resetAllowsReuseAfterProcessing() {
    val n = 24000
    val input = directFloatBuffer(n).also { fillSine(it, n) }
    val output = directFloatBuffer(n)
    val stretch = SignalsmithStretchNative.create(channels, sampleRate)
    try {
      stretch.process(input, n, output, n)
      stretch.reset()
      assertEquals(n, stretch.process(input, n, output, n))
      assertTrue("post-reset output is silent", maxAbs(output, n / 2, n) > 0.05f)
    } finally {
      stretch.release()
    }
  }

  @Test(expected = IllegalStateException::class)
  fun createWithZeroChannelsThrows() {
    SignalsmithStretchNative.create(0, sampleRate)
  }

  @Test
  fun processRejectsUndersizedOutputBuffer() {
    val n = 4096
    val input = directFloatBuffer(n).also { fillSine(it, n) }
    val tooSmall = directFloatBuffer(n / 2)
    val stretch = SignalsmithStretchNative.create(channels, sampleRate)
    try {
      // Asking for n out frames into a buffer sized for n/2 must be rejected
      // (return 0) rather than writing past the buffer.
      assertEquals(0, stretch.process(input, n, tooSmall, n))
    } finally {
      stretch.release()
    }
  }
}
