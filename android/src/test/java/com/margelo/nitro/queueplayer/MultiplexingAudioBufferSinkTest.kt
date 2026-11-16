package com.margelo.nitro.queueplayer

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Unit coverage for [MultiplexingAudioBufferSink] — fan-out across
 * the supplied sinks list + late-attach flush replay via
 * [MultiplexingAudioBufferSink.primeNewSink].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@UnstableApi
class MultiplexingAudioBufferSinkTest {

  /**
   * Records every `flush` + `handleBuffer` callback so tests can
   * assert what landed.
   */
  private class RecordingSink : TeeAudioProcessor.AudioBufferSink {
    data class Flush(val sampleRateHz: Int, val channelCount: Int, val encoding: Int)

    val flushes = mutableListOf<Flush>()
    var handleBufferCount: Int = 0

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
      flushes.add(Flush(sampleRateHz, channelCount, encoding))
    }

    override fun handleBuffer(buffer: ByteBuffer) {
      handleBufferCount++
    }
  }

  @Test
  fun `primeNewSink replays cached params onto sinks attached after first flush`() {
    val sinks = CopyOnWriteArrayList<TeeAudioProcessor.AudioBufferSink>()
    val mux = MultiplexingAudioBufferSink(sinks)
    mux.flush(48000, 2, C.ENCODING_PCM_16BIT)

    val late = RecordingSink()
    sinks.add(late)
    mux.primeNewSink(late)

    assertEquals(1, late.flushes.size)
    assertEquals(
      RecordingSink.Flush(48000, 2, C.ENCODING_PCM_16BIT),
      late.flushes[0],
    )
  }

  @Test
  fun `primeNewSink is a no-op before any flush`() {
    val sinks = CopyOnWriteArrayList<TeeAudioProcessor.AudioBufferSink>()
    val mux = MultiplexingAudioBufferSink(sinks)

    val sink = RecordingSink()
    sinks.add(sink)
    mux.primeNewSink(sink)

    assertEquals(0, sink.flushes.size)
  }

  @Test
  fun `primeNewSink replays the most recent flush after multiple stream changes`() {
    val sinks = CopyOnWriteArrayList<TeeAudioProcessor.AudioBufferSink>()
    val mux = MultiplexingAudioBufferSink(sinks)
    mux.flush(48000, 2, C.ENCODING_PCM_16BIT)
    mux.flush(44100, 1, C.ENCODING_PCM_FLOAT)

    val late = RecordingSink()
    sinks.add(late)
    mux.primeNewSink(late)

    assertEquals(1, late.flushes.size)
    assertEquals(
      RecordingSink.Flush(44100, 1, C.ENCODING_PCM_FLOAT),
      late.flushes[0],
    )
  }

  @Test
  fun `flush propagates to every sink already in the list`() {
    val sinks = CopyOnWriteArrayList<TeeAudioProcessor.AudioBufferSink>()
    val a = RecordingSink()
    val b = RecordingSink()
    sinks.add(a)
    sinks.add(b)
    val mux = MultiplexingAudioBufferSink(sinks)

    mux.flush(44100, 1, C.ENCODING_PCM_16BIT)

    assertEquals(1, a.flushes.size)
    assertEquals(1, b.flushes.size)
    assertEquals(
      RecordingSink.Flush(44100, 1, C.ENCODING_PCM_16BIT),
      a.flushes[0],
    )
    assertEquals(
      RecordingSink.Flush(44100, 1, C.ENCODING_PCM_16BIT),
      b.flushes[0],
    )
  }
}
