package com.margelo.nitro.queueplayer

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Media3 [AudioProcessor] that time-stretches a PCM stream with the Signalsmith
 * Stretch engine, preserving pitch — the `music` pitch-correction mode. Speed is
 * pushed in by the owning [PitchAwareAudioProcessorChain] via [setSpeed]; the
 * chain only feeds a non-unity speed in `music` mode, so in `none`/`voice` (and
 * at unity speed) this processor just passes audio through.
 *
 * It is **always active** on a supported PCM encoding once configured, NOT gated
 * on the current speed. Media3's [androidx.media3.common.audio.AudioProcessingPipeline]
 * fixes the active-processor set at `configure()` time and never re-evaluates it
 * on a later speed/mode change, so gating `isActive` on the (later-set) speed
 * would exclude this processor from the pipeline and it could never start
 * stretching mid-stream. Staying active and passing through at unity speed keeps
 * it present and ready, at the cost of one buffer copy per block when not
 * stretching (no native engine, no latency).
 *
 * Both 16-bit and float PCM are handled. The Signalsmith engine is float-native,
 * so a 16-bit stream is converted to/from float around the stretch; a float
 * stream is fed straight through (the common high-res path). The output encoding
 * always equals the input encoding — the processor is encoding-transparent.
 *
 * Signalsmith has no speed setter — the time-stretch ratio is `outFrames /
 * inFrames` per process call. Playback speed `s` means producing `1/s` as many
 * frames (faster = fewer), with a fractional carry so blocks average to the
 * target ratio. Internal latency is drained on end-of-stream ([onQueueEndOfStream]).
 */
@UnstableApi
internal class SignalsmithStretchAudioProcessor : BaseAudioProcessor() {

  /** Live playback speed, set by the chain. `1f` ⇒ pass-through (no stretch). */
  @Volatile
  private var speed: Float = 1f

  @Volatile
  private var encoding: Int = C.ENCODING_INVALID

  private var channelCount: Int = 0
  private var sampleRateHz: Int = 0
  private var bytesPerFrame: Int = 0

  private var nativeStretch: SignalsmithStretchNative? = null

  // Direct float scratch (interleaved, native order) for the 16-bit path — the
  // JNI works in float, so a 16-bit block is widened in / narrowed out here.
  private var floatIn: ByteBuffer? = null
  private var floatOut: ByteBuffer? = null

  // True once a stretched block has been produced, so the engine's tail
  // latency awaits a drain. [onQueueEndOfStream] drains it exactly once and
  // clears this — the native `outputLatency()` reports the fixed algorithmic
  // latency (not the live buffer fill), so re-draining re-emits the same tail
  // frames (an audible stutter) and never ends the stream.
  private var pendingDrain = false

  // Fractional frame remainder so per-block outFrames average to inFrames/speed.
  private var frameCarry: Double = 0.0

  fun setSpeed(value: Float) {
    speed = value
  }

  /** True only when actually applying a stretch (non-unity speed in music). */
  fun isStretching(): Boolean = isActive && speed != 1f

  /**
   * Map a playout duration back to media duration. For a pure time-stretch,
   * `s` seconds of media play per playout second at speed `s`, so media =
   * playout * speed — computed from the live speed rather than a cumulative
   * input/output byte ratio, which mis-maps while the engine's latency is
   * drained (the drained tail inflates output-vs-input) and freezes the
   * position clock on a speed change.
   */
  fun getMediaDuration(playoutDuration: Long): Long =
    if (speed == 1f) playoutDuration
    else Util.scaleLargeTimestamp(playoutDuration, (speed * 1_000_000f).toLong(), 1_000_000L)

  override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
    encoding = inputAudioFormat.encoding
    channelCount = inputAudioFormat.channelCount
    sampleRateHz = inputAudioFormat.sampleRate
    bytesPerFrame = channelCount * if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2
    // A reconfigure invalidates any engine built for the previous format;
    // the next stretching queueInput lazily rebuilds it. Clear the drain flag
    // so the invariant holds locally, independent of the flush that Media3
    // issues after a (re)configure.
    releaseNative()
    pendingDrain = false
    // Pass-through format — the stretch changes duration, not rate/channels/
    // encoding. Returning NOT_SET would drop the sink.
    return inputAudioFormat
  }

  override fun isActive(): Boolean =
    super.isActive() &&
      (encoding == C.ENCODING_PCM_FLOAT || encoding == C.ENCODING_PCM_16BIT)

  override fun queueInput(input: ByteBuffer) {
    if (bytesPerFrame == 0) return
    val inFrames = input.remaining() / bytesPerFrame
    if (inFrames == 0) return

    if (speed == 1f) {
      // Pass-through copy — present in the pipeline but not stretching.
      val out = replaceOutputBuffer(input.remaining())
      out.put(input)
      out.flip()
      return
    }

    val exact = inFrames / speed.toDouble() + frameCarry
    var outFrames = exact.toInt()
    if (outFrames < 1) outFrames = 1 // never request a 0-frame block
    frameCarry = exact - outFrames

    val stretch = ensureNative()
    if (encoding == C.ENCODING_PCM_FLOAT) {
      // The JNI reads/writes from index 0 (it ignores ByteBuffer.position), but
      // Media3 hands a positioned input — slice so the active region starts at 0.
      val out = replaceOutputBuffer(outFrames * bytesPerFrame)
      stretch.process(input.slice(), inFrames, out, outFrames)
      // The native write went through the buffer's base address, not `out`'s
      // Java position, so advance it manually before flip() sets the limit.
      out.position(outFrames * bytesPerFrame)
      out.flip()
    } else {
      // 16-bit: widen input → float, stretch, narrow float → 16-bit output.
      val nIn = inFrames * channelCount
      val nOut = outFrames * channelCount
      val fin = ensureFloatScratch(true, nIn)
      val fout = ensureFloatScratch(false, nOut)
      val inShorts = input.asShortBuffer()
      val finFloats = fin.asFloatBuffer()
      for (i in 0 until nIn) finFloats.put(i, inShorts.get(i) / 32768f)
      stretch.process(fin, inFrames, fout, outFrames)
      val out = replaceOutputBuffer(outFrames * bytesPerFrame)
      val outShorts = out.asShortBuffer()
      val foutFloats = fout.asFloatBuffer()
      for (i in 0 until nOut) {
        val s = (foutFloats.get(i) * 32767f).coerceIn(-32768f, 32767f)
        outShorts.put(i, s.roundToInt().toShort())
      }
      out.position(outFrames * bytesPerFrame)
      out.flip()
    }

    input.position(input.limit())
    pendingDrain = true
  }

  override fun onQueueEndOfStream() {
    val stretch = nativeStretch ?: return
    // Drain the engine's tail latency exactly once per stretched span. Media3
    // can call this repeatedly around a reconfigure; `outputLatency()` reports
    // the fixed algorithmic latency, so re-draining re-emits the same tail
    // frames (stutter) and the stream never ends, stalling a speed change.
    if (!pendingDrain || speed == 1f) return
    pendingDrain = false
    val latency = stretch.outputLatency()
    if (latency <= 0) return
    if (encoding == C.ENCODING_PCM_FLOAT) {
      val out = replaceOutputBuffer(latency * bytesPerFrame)
      stretch.flush(out, latency)
      out.position(latency * bytesPerFrame)
      out.flip()
    } else {
      val nOut = latency * channelCount
      val fout = ensureFloatScratch(false, nOut)
      stretch.flush(fout, latency)
      val out = replaceOutputBuffer(latency * bytesPerFrame)
      val outShorts = out.asShortBuffer()
      val foutFloats = fout.asFloatBuffer()
      for (i in 0 until nOut) {
        val s = (foutFloats.get(i) * 32767f).coerceIn(-32768f, 32767f)
        outShorts.put(i, s.roundToInt().toShort())
      }
      out.position(latency * bytesPerFrame)
      out.flip()
    }
  }

  override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
    nativeStretch?.reset()
    frameCarry = 0.0
    pendingDrain = false
  }

  override fun onReset() {
    releaseNative()
    floatIn = null
    floatOut = null
    frameCarry = 0.0
    pendingDrain = false
  }

  private fun ensureNative(): SignalsmithStretchNative {
    var stretch = nativeStretch
    if (stretch == null) {
      stretch = SignalsmithStretchNative.create(channelCount, sampleRateHz.toFloat())
      nativeStretch = stretch
    }
    return stretch
  }

  // A direct, native-order float buffer holding at least `floats` samples,
  // positioned at 0 (the JNI reads/writes from the base address).
  private fun ensureFloatScratch(isInput: Boolean, floats: Int): ByteBuffer {
    val needBytes = floats * 4
    val current = if (isInput) floatIn else floatOut
    if (current != null && current.capacity() >= needBytes) {
      current.clear()
      return current
    }
    val fresh = ByteBuffer.allocateDirect(needBytes).order(ByteOrder.nativeOrder())
    if (isInput) floatIn = fresh else floatOut = fresh
    return fresh
  }

  private fun releaseNative() {
    nativeStretch?.release()
    nativeStretch = null
  }
}
