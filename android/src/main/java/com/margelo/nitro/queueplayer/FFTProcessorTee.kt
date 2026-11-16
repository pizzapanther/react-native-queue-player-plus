package com.margelo.nitro.queueplayer

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Per-subscriber visualizer processor. Implements
 * [TeeAudioProcessor.AudioBufferSink] so it can attach to the engine's
 * `bufferSinks` accumulator and receive raw post-decode PCM frames
 * from the audio chain (PRE the OS-level audiofx EQ, since Tee sits
 * inside the AudioSink processor chain).
 *
 * Multi-channel input is mixed to mono before windowing — visualisers
 * only ever care about the summed signal, and FFT compute scales
 * linearly in n.
 *
 * Frame emission is throttled to `intervalMs` regardless of how
 * frequently `handleBuffer` fires — the audio render thread feeds us
 * at chunk cadence (typically a few ms apart for a 44.1 kHz stream),
 * far faster than any visualiser needs.
 */
@UnstableApi
internal class FFTProcessorTee(
  private val fftSize: Int,
  private val intervalMs: Int,
  private val includeSamples: Boolean,
  private val onFrame: (magnitudes: FloatArray, samples: FloatArray?, sampleRate: Int) -> Unit,
) : TeeAudioProcessor.AudioBufferSink {

  init {
    require(fftSize > 1 && (fftSize and (fftSize - 1)) == 0) {
      "fftSize must be a power of two > 1, got $fftSize"
    }
    require(intervalMs > 0) { "intervalMs must be > 0, got $intervalMs" }
  }

  // 2× ring so we can copy the latest fftSize-sample window without
  // worrying about mid-window wraparound. handleBuffer is the only
  // writer and reader of the ring, and it runs on the audio render
  // thread. Length is a power of two so wrap is a free bitmask.
  private val ring = FloatArray(fftSize * 2)
  private val ringMask: Int = ring.size - 1
  private var writeIdx = 0L

  private val window = RadixTwoFFT.hannWindow(fftSize)
  private val real = FloatArray(fftSize)
  private val imag = FloatArray(fftSize)
  // Output buffers handed to the consumer each emit. The consumer's
  // contract is "build any external snapshot (e.g. ArrayBuffer)
  // synchronously inside onFrame"; subsequent emits overwrite these
  // arrays in place so the render-thread allocation cost stays at
  // zero per frame.
  private val magsOut = FloatArray(fftSize / 2)
  private val samplesOut: FloatArray? = if (includeSamples) FloatArray(fftSize) else null

  /**
   * The stream format a buffer is decoded under. Immutable and published
   * as a whole through the single [format] reference, so a decode never
   * mixes one flush's channel count with another's encoding.
   */
  private class Format(val sampleRate: Int, val channelCount: Int, val encoding: Int) {
    val bytesPerSample: Int = bytesPerSample(encoding)
    val bytesPerFrame: Int = bytesPerSample * channelCount
  }

  // flush() runs on whichever thread attaches the sink (see
  // MultiplexingAudioBufferSink.primeNewSink) and on the render thread;
  // handleBuffer runs on the render thread and reads this once per call.
  @Volatile private var format = Format(44100, 2, C.ENCODING_PCM_16BIT)

  // `writeIdx` (above) and `lastEmitNs` are written by handleBuffer and
  // reset by flush, with no ordering between the two. A flush racing a
  // handleBuffer can lose the `writeIdx` reset, so exactly one emitted
  // frame carries up to fftSize pre-flush samples — comparable to the
  // delayed first frame MultiplexingAudioBufferSink documents for a
  // double flush. A lost `lastEmitNs` reset delays the first post-flush
  // frame by at most intervalMs. On a 32-bit ABI the Long resets can
  // tear; the outcomes are the same one frame.
  private var lastEmitNs: Long = 0L

  override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
    format = Format(sampleRateHz, channelCount.coerceAtLeast(1), encoding)
    // Reset write index on stream change so the ring doesn't carry
    // samples from the previous track / sample-rate config across.
    writeIdx = 0L
    lastEmitNs = 0L
  }

  override fun handleBuffer(buffer: ByteBuffer) {
    // One read: every field this call uses comes from the same flush.
    val fmt = format
    if (fmt.bytesPerFrame == 0 || fmt.bytesPerSample == 0) return // unsupported encoding
    // Read via a duplicated view so the source buffer's position +
    // byte order are not mutated. Media3 documents the input as a
    // duplicated read-only buffer, but local-mutating it would
    // surprise any future consumer that relied on the contract.
    val view = buffer.duplicate().order(ByteOrder.nativeOrder())
    val frameCount = view.remaining() / fmt.bytesPerFrame
    var idxLo = (writeIdx and ringMask.toLong()).toInt()
    var i = 0
    while (i < frameCount) {
      ring[idxLo] = readMonoSample(view, fmt)
      idxLo = (idxLo + 1) and ringMask
      i++
    }
    writeIdx += frameCount.toLong()

    if (writeIdx < fftSize) return
    val nowNs = System.nanoTime()
    if (nowNs - lastEmitNs < intervalMs * 1_000_000L) return
    lastEmitNs = nowNs

    emitFrame(fmt.sampleRate)
  }

  private fun emitFrame(sampleRate: Int) {
    // Latest fftSize samples ending at writeIdx - 1.
    val startLo = ((writeIdx - fftSize) and ringMask.toLong()).toInt()
    var i = 0
    while (i < fftSize) {
      val s = ring[(startLo + i) and ringMask]
      samplesOut?.set(i, s)
      real[i] = s * window[i]
      imag[i] = 0f
      i++
    }
    RadixTwoFFT.forward(real, imag)
    RadixTwoFFT.magnitudes(real, imag, magsOut)
    // Hand the scratch buffers to the consumer; the consumer must
    // build any persistent snapshot (e.g. an ArrayBuffer copy)
    // synchronously inside onFrame — subsequent emits overwrite
    // these arrays in place.
    onFrame(magsOut, samplesOut, sampleRate)
  }

  /**
   * Read one mono-mixed sample from the next frame in [buffer]. Pulls
   * `channelCount` samples in the active encoding, normalises each to
   * [-1, 1] Float32, and averages them. Buffer position advances by
   * `bytesPerFrame`.
   *
   * Supported encodings: PCM_16BIT, PCM_24BIT_PACKED, PCM_32BIT,
   * PCM_FLOAT. Other encodings advance the position without producing
   * a sample (returns 0); the audio chain doesn't typically deliver
   * those to AudioSink.
   */
  private fun readMonoSample(buffer: ByteBuffer, fmt: Format): Float {
    var sum = 0f
    var ch = 0
    while (ch < fmt.channelCount) {
      sum += when (fmt.encoding) {
        C.ENCODING_PCM_16BIT -> buffer.short.toFloat() / Short.MAX_VALUE.toFloat()
        C.ENCODING_PCM_FLOAT -> buffer.float
        C.ENCODING_PCM_32BIT -> buffer.int.toFloat() / Int.MAX_VALUE.toFloat()
        C.ENCODING_PCM_24BIT -> read24BitPacked(buffer)
        else -> {
          // Skip unknown encoding: advance position by sample size guess.
          buffer.position(buffer.position() + fmt.bytesPerSample)
          0f
        }
      }
      ch++
    }
    return sum / fmt.channelCount
  }

  private fun read24BitPacked(buffer: ByteBuffer): Float {
    // Three bytes little-endian (native order on Android), sign-extend
    // the high byte to int, normalise by 2^23.
    val b0 = buffer.get().toInt() and 0xFF
    val b1 = buffer.get().toInt() and 0xFF
    val b2 = buffer.get().toInt() // sign bit
    val v = (b2 shl 16) or (b1 shl 8) or b0
    return v.toFloat() / 8_388_608f // 2^23
  }

  private companion object {
    fun bytesPerSample(encoding: Int): Int = when (encoding) {
      C.ENCODING_PCM_16BIT -> 2
      C.ENCODING_PCM_24BIT -> 3
      C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
      else -> 0
    }
  }
}
