package com.margelo.nitro.queueplayer

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * Unit coverage for [FFTProcessorTee] — buffer ingestion across PCM
 * encodings, intervalMs throttling, includeSamples flag, and
 * multi-channel mixdown. The Tee callback contract is mocked
 * directly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@UnstableApi
class FFTProcessorTeeTest {

  private fun makeTee(
    fftSize: Int = 256,
    intervalMs: Int = 1,
    includeSamples: Boolean = false,
    onFrame: (FloatArray, FloatArray?, Int) -> Unit,
  ): FFTProcessorTee = FFTProcessorTee(fftSize, intervalMs, includeSamples, onFrame)

  private fun pcm16Buffer(samples: ShortArray): ByteBuffer {
    val bb = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.nativeOrder())
    for (s in samples) bb.putShort(s)
    bb.position(0)
    return bb
  }

  @Test
  fun `emits a frame once enough samples land`() {
    var calls = 0
    var lastSampleRate = 0
    val tee = makeTee { _, _, sampleRate ->
      calls++
      lastSampleRate = sampleRate
    }
    tee.flush(48000, 1, C.ENCODING_PCM_16BIT)
    val n = 256
    val samples = ShortArray(n) { (Short.MAX_VALUE * sin(2.0 * PI * 8 * it / n)).toInt().toShort() }
    tee.handleBuffer(pcm16Buffer(samples))
    assertEquals(1, calls)
    assertEquals(48000, lastSampleRate)
  }

  @Test
  fun `does not emit until fftSize samples accumulate`() {
    var calls = 0
    val tee = makeTee(fftSize = 512) { _, _, _ -> calls++ }
    tee.flush(44100, 1, C.ENCODING_PCM_16BIT)
    tee.handleBuffer(pcm16Buffer(ShortArray(256) { 0 }))
    assertEquals(0, calls) // halfway, no emit
    tee.handleBuffer(pcm16Buffer(ShortArray(256) { 100 }))
    assertEquals(1, calls)
  }

  @Test
  fun `intervalMs throttles back-to-back emissions`() {
    var calls = 0
    val tee = makeTee(fftSize = 64, intervalMs = 1000) { _, _, _ -> calls++ }
    tee.flush(48000, 1, C.ENCODING_PCM_16BIT)
    tee.handleBuffer(pcm16Buffer(ShortArray(64) { 1 }))
    tee.handleBuffer(pcm16Buffer(ShortArray(64) { 2 }))
    assertEquals("only the first window past the interval should emit", 1, calls)
  }

  @Test
  fun `includeSamples=false leaves samples null on the callback`() {
    var capturedSamples: FloatArray? = null
    var captured = false
    val tee = makeTee(fftSize = 128, intervalMs = 1, includeSamples = false) { _, samples, _ ->
      captured = true
      capturedSamples = samples
    }
    tee.flush(44100, 1, C.ENCODING_PCM_16BIT)
    tee.handleBuffer(pcm16Buffer(ShortArray(128) { 0 }))
    assertTrue(captured)
    assertNull(capturedSamples)
  }

  @Test
  fun `includeSamples=true populates samples with fftSize floats`() {
    var capturedSamples: FloatArray? = null
    val tee = makeTee(fftSize = 128, intervalMs = 1, includeSamples = true) { _, samples, _ ->
      capturedSamples = samples
    }
    tee.flush(44100, 1, C.ENCODING_PCM_16BIT)
    tee.handleBuffer(pcm16Buffer(ShortArray(128) { 1000 }))
    assertNotNull(capturedSamples)
    assertEquals(128, capturedSamples!!.size)
  }

  @Test
  fun `stereo input mixes to mono via average`() {
    var capturedSamples: FloatArray? = null
    val tee = makeTee(fftSize = 64, intervalMs = 1, includeSamples = true) { _, samples, _ ->
      capturedSamples = samples
    }
    tee.flush(44100, 2, C.ENCODING_PCM_16BIT)
    // Interleaved L/R: [+max, -max, +max, -max, …] → mono mix → 0.
    val interleaved = ShortArray(128)
    for (i in 0 until 64) {
      interleaved[i * 2] = Short.MAX_VALUE
      interleaved[i * 2 + 1] = (-Short.MAX_VALUE.toInt()).toShort()
    }
    tee.handleBuffer(pcm16Buffer(interleaved))
    assertNotNull(capturedSamples)
    for (s in capturedSamples!!) {
      assertTrue("mono mix of ±max should sum to ~0 (got $s)", kotlin.math.abs(s) < 0.01f)
    }
  }

  @Test
  fun `flush resets ring write index across stream changes`() {
    var calls = 0
    val tee = makeTee(fftSize = 128, intervalMs = 1) { _, _, _ -> calls++ }
    tee.flush(44100, 1, C.ENCODING_PCM_16BIT)
    tee.handleBuffer(pcm16Buffer(ShortArray(64) { 0 })) // half-fill
    assertEquals(0, calls)
    tee.flush(48000, 1, C.ENCODING_PCM_16BIT) // new stream
    tee.handleBuffer(pcm16Buffer(ShortArray(64) { 0 })) // still half-fill on new stream
    assertEquals("ring should reset; partial pre-flush data must not contribute", 0, calls)
    tee.handleBuffer(pcm16Buffer(ShortArray(64) { 0 }))
    assertEquals(1, calls)
  }

  @Test
  fun `non-power-of-two fftSize is rejected at construction`() {
    try {
      makeTee(fftSize = 100) { _, _, _ -> }
      throw AssertionError("expected IllegalArgumentException")
    } catch (e: IllegalArgumentException) {
      assertTrue(e.message?.contains("power of two") == true)
    }
  }

  @Test
  fun `24-bit packed PCM decodes to plausible Float32 range`() {
    var capturedSamples: FloatArray? = null
    val tee = makeTee(fftSize = 64, intervalMs = 1, includeSamples = true) { _, samples, _ ->
      capturedSamples = samples
    }
    tee.flush(48000, 1, C.ENCODING_PCM_24BIT)
    // Build a buffer of 64 mono frames at +half-scale (0x400000 little-endian).
    val frames = 64
    val bb = ByteBuffer.allocate(frames * 3).order(ByteOrder.nativeOrder())
    repeat(frames) {
      bb.put(0x00.toByte())
      bb.put(0x00.toByte())
      bb.put(0x40.toByte()) // 0x400000 = 2^22 = +half-scale
    }
    bb.position(0)
    tee.handleBuffer(bb)
    assertNotNull(capturedSamples)
    for (s in capturedSamples!!) {
      assertEquals("expected ~0.5 from +half-scale 24-bit input", 0.5f, s, 0.01f)
    }
  }

  @Test
  fun `does not mutate the source ByteBuffer's position or byte order`() {
    val tee = makeTee(fftSize = 64, intervalMs = 1, includeSamples = false) { _, _, _ -> }
    tee.flush(44100, 1, C.ENCODING_PCM_16BIT)
    val original = pcm16Buffer(ShortArray(64) { 0 })
    val expectedOrder = original.order()
    val expectedPosition = original.position()
    tee.handleBuffer(original)
    assertEquals(expectedPosition, original.position())
    assertEquals(expectedOrder, original.order())
  }

  /** A flush resets the emit throttle, so the first frame of a new stream is not delayed. */
  @Test
  fun `flush resets the emit throttle`() {
    val reported = mutableListOf<Int>()
    val tee = makeTee(fftSize = 256, intervalMs = 1000) { _, _, sampleRate -> reported += sampleRate }
    tee.flush(48000, 1, C.ENCODING_PCM_16BIT)
    tee.handleBuffer(pcm16Buffer(ShortArray(256) { 1000 }))
    assertEquals(listOf(48000), reported)

    // Well inside the 1000 ms window: without the reset this emit is throttled.
    tee.flush(96000, 1, C.ENCODING_PCM_16BIT)
    tee.handleBuffer(pcm16Buffer(ShortArray(256) { 1000 }))
    assertEquals(listOf(48000, 96000), reported)
  }
}
