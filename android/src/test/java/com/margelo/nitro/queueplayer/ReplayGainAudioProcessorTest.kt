package com.margelo.nitro.queueplayer

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessingPipeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import com.google.common.collect.ImmutableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Unit coverage for [ReplayGainAudioProcessor] — the per-sample gain
 * stage Media3 wires into the AudioSink chain. Exercises the
 * [AudioProcessor] contract: configure, isActive gating, in-place multiply
 * on both 16-bit and float PCM, the 16-bit full-scale clamp, the
 * encoding gate, and the unity pass-through that keeps the processor in
 * the [AudioProcessingPipeline] while it applies nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@UnstableApi
class ReplayGainAudioProcessorTest {

  @Test
  fun `isActive is true at unity gain on a Float32 input`() {
    val proc = ReplayGainAudioProcessor()
    proc.configure(floatFormat(48000, 2))
    assertTrue(proc.isActive)

    proc.setLinearGain(1.0f)
    assertTrue(proc.isActive)
  }

  @Test
  fun `isActive is true at unity gain on a 16-bit input`() {
    val proc = ReplayGainAudioProcessor()
    proc.configure(int16Format(48000, 2))
    proc.setLinearGain(1.0f)
    assertTrue(proc.isActive)
  }

  @Test
  fun `isActive is true with non-unity gain on a Float32 input`() {
    val proc = ReplayGainAudioProcessor()
    proc.configure(floatFormat(48000, 2))
    proc.setLinearGain(0.5f)
    assertTrue(proc.isActive)
  }

  @Test
  fun `isActive is true with non-unity gain on a 16-bit input`() {
    val proc = ReplayGainAudioProcessor()
    proc.configure(int16Format(48000, 2))
    proc.setLinearGain(0.5f)
    assertTrue(proc.isActive)
  }

  @Test
  fun `isActive is false before configure`() {
    val proc = ReplayGainAudioProcessor()
    proc.setLinearGain(0.5f)
    assertFalse(proc.isActive)
  }

  @Test
  fun `isActive is false on an unsupported encoding`() {
    val proc = ReplayGainAudioProcessor()
    proc.configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_24BIT))
    proc.setLinearGain(0.5f)
    assertFalse(proc.isActive)
  }

  @Test
  fun `queueInput at unity gain passes float samples through unchanged`() {
    val proc = ReplayGainAudioProcessor()
    proc.configure(floatFormat(48000, 1))
    proc.setLinearGain(1.0f)

    val input = floatBuffer(floatArrayOf(1.0f, 0.5f, -0.25f, 0.0f, -1.0f))
    proc.queueInput(input)

    val output = readFloats(proc.output, 5)
    assertEquals(1.0f, output[0], 1e-6f)
    assertEquals(0.5f, output[1], 1e-6f)
    assertEquals(-0.25f, output[2], 1e-6f)
    assertEquals(0.0f, output[3], 1e-6f)
    assertEquals(-1.0f, output[4], 1e-6f)
    assertEquals(0, input.remaining())
  }

  @Test
  fun `queueInput at unity gain passes 16-bit samples through unchanged`() {
    val proc = ReplayGainAudioProcessor()
    proc.configure(int16Format(48000, 1))
    proc.setLinearGain(1.0f)

    val input = shortBuffer(shortArrayOf(32767, -32768, 10000, 0, -3))
    proc.queueInput(input)

    val output = readShorts(proc.output, 5)
    assertEquals(32767, output[0].toInt())
    assertEquals(-32768, output[1].toInt())
    assertEquals(10000, output[2].toInt())
    assertEquals(0, output[3].toInt())
    assertEquals(-3, output[4].toInt())
    assertEquals(0, input.remaining())
  }

  @Test
  fun `a pipeline configured at unity gain stays operational`() {
    val proc = ReplayGainAudioProcessor()
    val pipeline = newPipeline(proc, int16Format(48000, 1))

    assertTrue(
      "the gain stage must stay in the active set so a later gain can reach the audio",
      pipeline.isOperational
    )
  }

  @Test
  fun `a gain pushed after the pipeline is configured reaches the audio`() {
    val proc = ReplayGainAudioProcessor()
    // configure() + flush() is where Media3 fixes the active-processor set,
    // and it runs at unity for a queue loaded while ReplayGain is off.
    val pipeline = newPipeline(proc, int16Format(48000, 1))

    // The gain arrives afterwards, as it does when the mode is selected
    // mid-playback. Nothing reconfigures or flushes the sink.
    proc.setLinearGain(0.5f)

    val input = shortBuffer(shortArrayOf(10000, -8000, 4))
    pipeline.queueInput(input)

    assertTrue(pipeline.isOperational)
    val output = drainShorts(pipeline, 3)
    assertEquals(5000, output[0].toInt())
    assertEquals(-4000, output[1].toInt())
    assertEquals(2, output[2].toInt())
  }

  @Test
  fun `a gain pushed then returned to unity stops reaching the audio`() {
    val proc = ReplayGainAudioProcessor()
    val pipeline = newPipeline(proc, int16Format(48000, 1))

    proc.setLinearGain(0.5f)
    pipeline.queueInput(shortBuffer(shortArrayOf(10000)))
    assertEquals(5000, drainShorts(pipeline, 1)[0].toInt())

    proc.setLinearGain(1.0f)
    pipeline.queueInput(shortBuffer(shortArrayOf(10000)))
    assertEquals(10000, drainShorts(pipeline, 1)[0].toInt())
  }

  @Test
  fun `queueInput multiplies every sample by the linear gain in place`() {
    val proc = ReplayGainAudioProcessor()
    proc.configure(floatFormat(48000, 1))
    proc.setLinearGain(0.5f)

    val input = floatBuffer(floatArrayOf(1.0f, 0.5f, -0.25f, 0.0f, -1.0f))
    proc.queueInput(input)

    val output = readFloats(proc.output, 5)
    assertEquals(0.5f, output[0], 1e-6f)
    assertEquals(0.25f, output[1], 1e-6f)
    assertEquals(-0.125f, output[2], 1e-6f)
    assertEquals(0.0f, output[3], 1e-6f)
    assertEquals(-0.5f, output[4], 1e-6f)
  }

  @Test
  fun `queueInput with gain above unity applies the boost in place`() {
    val proc = ReplayGainAudioProcessor()
    proc.configure(floatFormat(44100, 1))
    proc.setLinearGain(2.0f)

    val input = floatBuffer(floatArrayOf(0.1f, -0.2f, 0.3f))
    proc.queueInput(input)

    val output = readFloats(proc.output, 3)
    assertEquals(0.2f, output[0], 1e-6f)
    assertEquals(-0.4f, output[1], 1e-6f)
    assertEquals(0.6f, output[2], 1e-6f)
  }

  @Test
  fun `queueInput scales 16-bit samples by the linear gain`() {
    val proc = ReplayGainAudioProcessor()
    proc.configure(int16Format(48000, 1))
    proc.setLinearGain(0.5f)

    val input = shortBuffer(shortArrayOf(10000, -8000, 4, 0, -10000))
    proc.queueInput(input)

    val output = readShorts(proc.output, 5)
    assertEquals(5000, output[0].toInt())
    assertEquals(-4000, output[1].toInt())
    assertEquals(2, output[2].toInt())
    assertEquals(0, output[3].toInt())
    assertEquals(-5000, output[4].toInt())
  }

  @Test
  fun `queueInput clamps a 16-bit boost that exceeds full scale`() {
    val proc = ReplayGainAudioProcessor()
    proc.configure(int16Format(44100, 1))
    proc.setLinearGain(2.0f)

    // 20000*2 = 40000 > 32767 and -20000*2 = -40000 < -32768 both clamp.
    val input = shortBuffer(shortArrayOf(20000, -20000, 1000))
    proc.queueInput(input)

    val output = readShorts(proc.output, 3)
    assertEquals(32767, output[0].toInt())
    assertEquals(-32768, output[1].toInt())
    assertEquals(2000, output[2].toInt())
  }

  @Test
  fun `queueInput consumes the input buffer`() {
    val proc = ReplayGainAudioProcessor()
    proc.configure(floatFormat(48000, 1))
    proc.setLinearGain(0.5f)

    val input = floatBuffer(floatArrayOf(1.0f, 1.0f, 1.0f, 1.0f))
    proc.queueInput(input)

    assertEquals(0, input.remaining())
  }

  @Test
  fun `setLinearGain is observed on the next queueInput`() {
    val proc = ReplayGainAudioProcessor()
    proc.configure(floatFormat(48000, 1))
    proc.setLinearGain(0.5f)
    val first = floatBuffer(floatArrayOf(1.0f))
    proc.queueInput(first)
    val firstOut = readFloats(proc.output, 1)
    assertEquals(0.5f, firstOut[0], 1e-6f)

    // Mid-stream gain change — the next queueInput observes it.
    proc.setLinearGain(0.25f)
    val second = floatBuffer(floatArrayOf(1.0f))
    proc.queueInput(second)
    val secondOut = readFloats(proc.output, 1)
    assertEquals(0.25f, secondOut[0], 1e-6f)
  }

  // region Helpers

  /** A single-stage pipeline taken through Media3's configure-then-flush setup. */
  private fun newPipeline(
    proc: ReplayGainAudioProcessor,
    format: AudioProcessor.AudioFormat,
  ): AudioProcessingPipeline {
    val pipeline = AudioProcessingPipeline(ImmutableList.of<AudioProcessor>(proc))
    pipeline.configure(format)
    pipeline.flush(AudioProcessor.StreamMetadata.DEFAULT)
    return pipeline
  }

  /**
   * Read [count] shorts of pipeline output and consume them the way
   * `DefaultAudioSink` does — an undrained output buffer blocks the next
   * [AudioProcessingPipeline.queueInput] from reaching the processors.
   */
  private fun drainShorts(pipeline: AudioProcessingPipeline, count: Int): ShortArray {
    val buffer = pipeline.output
    val values = readShorts(buffer, count)
    buffer.position(buffer.position() + count * Short.SIZE_BYTES)
    return values
  }

  private fun floatFormat(sampleRate: Int, channelCount: Int) =
    AudioProcessor.AudioFormat(sampleRate, channelCount, C.ENCODING_PCM_FLOAT)

  private fun int16Format(sampleRate: Int, channelCount: Int) =
    AudioProcessor.AudioFormat(sampleRate, channelCount, C.ENCODING_PCM_16BIT)

  private fun floatBuffer(values: FloatArray): ByteBuffer {
    val buffer = ByteBuffer
      .allocateDirect(values.size * Float.SIZE_BYTES)
      .order(ByteOrder.nativeOrder())
    val asFloat = buffer.asFloatBuffer()
    for (v in values) asFloat.put(v)
    buffer.position(0).limit(values.size * Float.SIZE_BYTES)
    return buffer
  }

  private fun readFloats(buffer: ByteBuffer, count: Int): FloatArray {
    val out = FloatArray(count)
    val asFloat = buffer.asFloatBuffer()
    for (i in 0 until count) out[i] = asFloat.get()
    return out
  }

  private fun shortBuffer(values: ShortArray): ByteBuffer {
    val buffer = ByteBuffer
      .allocateDirect(values.size * Short.SIZE_BYTES)
      .order(ByteOrder.nativeOrder())
    val asShort = buffer.asShortBuffer()
    for (v in values) asShort.put(v)
    buffer.position(0).limit(values.size * Short.SIZE_BYTES)
    return buffer
  }

  private fun readShorts(buffer: ByteBuffer, count: Int): ShortArray {
    val out = ShortArray(count)
    val asShort = buffer.asShortBuffer()
    for (i in 0 until count) out[i] = asShort.get()
    return out
  }

  // endregion
}
