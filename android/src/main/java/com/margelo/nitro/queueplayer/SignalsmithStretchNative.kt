package com.margelo.nitro.queueplayer

import java.nio.ByteBuffer

/**
 * JNI binding to the vendored Signalsmith Stretch time-stretch engine, used by
 * the `music` pitch-correction mode. One instance per AudioProcessor (per
 * crossfade leg). All buffers are direct [ByteBuffer]s of interleaved float PCM
 * in native byte order; the native side de-/re-interleaves to Signalsmith's
 * planar layout.
 *
 * Buffer contract: the native side reads/writes from absolute index 0 and
 * IGNORES [ByteBuffer.position] (it uses GetDirectBufferAddress, which returns
 * the buffer base). Callers must place the active region at offset 0 —
 * `slice()` or copy a positioned Media3 buffer before handing it in.
 *
 * Stretch ratio is set per [process] call by the input/output frame counts —
 * Signalsmith has no ratio setter; `outFrames / inFrames` IS the time-stretch.
 */
internal class SignalsmithStretchNative private constructor(private var handle: Long) {

  /**
   * Consume [inFrames] interleaved frames from [input] and produce exactly
   * [outFrames] interleaved frames into [output]; returns frames written.
   * Both buffers are read/written from index 0 (see the class buffer contract).
   */
  fun process(input: ByteBuffer, inFrames: Int, output: ByteBuffer, outFrames: Int): Int =
    nativeProcess(handle, input, inFrames, output, outFrames)

  /**
   * Drain the internal output-latency tail at end-of-stream into [output],
   * written from index 0 (see the class buffer contract).
   */
  fun flush(output: ByteBuffer, outFrames: Int): Int = nativeFlush(handle, output, outFrames)

  /** Output latency in frames — how many tail frames remain to drain on flush. */
  fun outputLatency(): Int = nativeOutputLatency(handle)

  fun reset() = nativeReset(handle)

  fun release() {
    if (handle != 0L) {
      nativeDestroy(handle)
      handle = 0L
    }
  }

  companion object {
    init {
      System.loadLibrary("signalsmith_stretch")
    }

    fun create(
      channels: Int,
      sampleRate: Float,
      splitComputation: Boolean = true,
    ): SignalsmithStretchNative {
      val handle = nativeCreate(channels, sampleRate, splitComputation)
      check(handle != 0L) {
        "Signalsmith native init failed (channels=$channels, sampleRate=$sampleRate)"
      }
      return SignalsmithStretchNative(handle)
    }

    @JvmStatic
    private external fun nativeCreate(
      channels: Int,
      sampleRate: Float,
      splitComputation: Boolean,
    ): Long

    @JvmStatic
    private external fun nativeProcess(
      handle: Long,
      input: ByteBuffer,
      inFrames: Int,
      output: ByteBuffer,
      outFrames: Int,
    ): Int

    @JvmStatic
    private external fun nativeFlush(handle: Long, output: ByteBuffer, outFrames: Int): Int

    @JvmStatic
    private external fun nativeOutputLatency(handle: Long): Int

    @JvmStatic
    private external fun nativeReset(handle: Long)

    @JvmStatic
    private external fun nativeDestroy(handle: Long)
  }
}
