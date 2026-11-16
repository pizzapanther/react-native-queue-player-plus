package com.margelo.nitro.queueplayer

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Single [TeeAudioProcessor.AudioBufferSink] that fans every
 * `flush` + `handleBuffer` callback out to a list of downstream
 * sinks. The lib needs this because [TeeAudioProcessor] only takes
 * ONE sink at construction; we want N visualizer subscribers + zero
 * cost when the list is empty.
 *
 * The list is the engine's own `bufferSinks` accumulator (passed by
 * reference). The Visualizer Hybrid manages list mutation via the
 * engine's `attachAudioBufferSink` / `detachAudioBufferSink` API; the
 * mux just walks the snapshot at each callback. The list is a
 * [CopyOnWriteArrayList] so iteration is stable even when a
 * subscriber detaches mid-stream.
 *
 * `flush` is forwarded once per sink with the current sample rate /
 * channel count / encoding so each downstream sink can size its
 * buffers correctly. `handleBuffer` hands each downstream sink an
 * independent [ByteBuffer.duplicate] so a slow consumer cannot leave
 * the shared view in a partially-consumed state for the next sink.
 *
 * Late-attach contract: Media3 fires `flush` once per AudioSink
 * lifetime, typically when `DefaultAudioSink.configure` runs against
 * the first decoded buffer. A subscriber that attaches AFTER that
 * point would otherwise receive `handleBuffer` chunks with no prior
 * `flush(rate, channels, encoding)` call and no way to size its
 * decoder. The mux caches the most recent flush params in
 * [lastFlush] (a single [FlushParams] reference, written
 * `@Volatile` so the three fields publish atomically — no torn-
 * triple risk vs three separate volatile ints) and replays them via
 * [primeNewSink] when the engine's attach path adds a sink. If
 * [lastFlush] is still null (no flush has fired yet on this mux)
 * the new sink simply waits for the next live flush, which it will
 * receive directly via the normal fan-out.
 */
@UnstableApi
internal class MultiplexingAudioBufferSink(
  private val sinks: CopyOnWriteArrayList<TeeAudioProcessor.AudioBufferSink>,
) : TeeAudioProcessor.AudioBufferSink {

  private data class FlushParams(val sampleRateHz: Int, val channelCount: Int, val encoding: Int)

  @Volatile
  private var lastFlush: FlushParams? = null

  override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
    lastFlush = FlushParams(sampleRateHz, channelCount, encoding)
    for (sink in sinks) {
      sink.flush(sampleRateHz, channelCount, encoding)
    }
  }

  override fun handleBuffer(buffer: ByteBuffer) {
    if (sinks.isEmpty()) return
    for (sink in sinks) {
      // duplicate() shares storage but gives each sink its own
      // position + limit + mark, so per-sink reads are independent.
      sink.handleBuffer(buffer.duplicate())
    }
  }

  /**
   * Replay the most recent `flush(rate, channels, encoding)` onto a
   * sink that just attached, so it has the encoding context every
   * subsequent [handleBuffer] needs to decode incoming PCM. No-op
   * when no flush has fired yet on this mux — the sink will be
   * driven directly by the next live flush via the fan-out loop.
   *
   * Caller must accept idempotent flush: if the engine attach happens
   * to interleave with a live `flush()` (the new sink lands in
   * [sinks] before the loop reaches it AND `primeNewSink` runs after
   * the loop), the sink may receive two `flush(rate, channels,
   * encoding)` calls back-to-back with identical params. Downstream
   * sinks treat repeat-flush as a state-reset, not a true no-op:
   * [FFTProcessorTee] zeroes its ring + writeIdx + lastEmitNs on
   * every flush call, so a double-flush forfeits up to one
   * `fftSize`-worth of accumulated audio — acceptable because a
   * sink that just attached has no historic accumulation to lose,
   * and the next handleBuffer will refill the ring within
   * `fftSize / sampleRate` seconds.
   */
  internal fun primeNewSink(sink: TeeAudioProcessor.AudioBufferSink) {
    val params = lastFlush ?: return
    sink.flush(params.sampleRateHz, params.channelCount, params.encoding)
  }
}
