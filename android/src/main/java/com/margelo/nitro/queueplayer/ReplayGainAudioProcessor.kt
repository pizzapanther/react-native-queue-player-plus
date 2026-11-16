package com.margelo.nitro.queueplayer

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * Standalone Media3 [AudioProcessor] that applies a per-track linear
 * gain to a PCM stream. No knowledge of the engine, the EQ stage, or the
 * visualizer pipeline — purely "given a PCM buffer, multiply by
 * [linearGain]". The owning engine pushes a new gain via [setLinearGain]
 * from its `Player.Listener` callbacks and from the ReplayGain-mode
 * setter; `onMediaItemTransition` resets to unity until the next track's
 * tags arrive.
 *
 * It is **always active** on a supported PCM encoding once configured, NOT
 * gated on the current gain. Media3's
 * [androidx.media3.common.audio.AudioProcessingPipeline] fixes the
 * active-processor set in `configure()` / `flush()` and never re-evaluates
 * it while a stream plays, so gating `isActive` on the (later-pushed) gain
 * would exclude this processor from the pipeline for any queue loaded at
 * unity and no gain could ever reach the audio afterwards. Staying active
 * and passing the block straight through at unity keeps it present and
 * ready, at the cost of one bulk buffer copy per block when no gain is
 * applied.
 *
 * Handles both 16-bit and float PCM. The sink runs at 16-bit (see
 * [AudioPipelineRenderersFactory] — float output would drop the whole
 * chain on hi-res sources), so a 16-bit block is scaled in float precision
 * and narrowed back with a round + full-scale clamp; a float block is
 * scaled in place. The gain is already clip-capped against the track peak
 * (`ReplayGainGain`), so the clamp only guards sentinel/missing-peak tags.
 */
@UnstableApi
internal class ReplayGainAudioProcessor : BaseAudioProcessor() {

  /**
   * Live linear gain. Volatile because the audio render thread reads
   * it on every [queueInput] while the engine's main thread writes it
   * from the metadata listener. The value is `pow(10, gainDb / 20)`
   * already clip-capped against the track peak (caller's job); a
   * value of `1.0f` passes the stream through untouched.
   */
  @Volatile
  private var linearGain: Float = 1.0f

  /**
   * Input PCM encoding captured at [onConfigure]. Volatile for the same
   * render-thread / main-thread split as [linearGain].
   */
  @Volatile
  private var encoding: Int = C.ENCODING_INVALID

  /**
   * Push a new linear gain, observed from the next [queueInput] onwards.
   * `1.0f` skips the per-sample multiply and passes the block through.
   */
  fun setLinearGain(value: Float) {
    linearGain = value
  }

  override fun onConfigure(input: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
    encoding = input.encoding
    // Pass-through: this stage doesn't change rate, channel count, or
    // encoding. Returning [AudioProcessor.AudioFormat.NOT_SET] would
    // tell Media3 the processor consumes the stream and drops the
    // output AudioSink, which is wrong — RG is in-place.
    return input
  }

  override fun isActive(): Boolean =
    super.isActive() &&
      (encoding == C.ENCODING_PCM_FLOAT || encoding == C.ENCODING_PCM_16BIT)

  override fun queueInput(input: ByteBuffer) {
    val gain = linearGain
    val byteCount = input.remaining()
    if (byteCount == 0) return
    val out = replaceOutputBuffer(byteCount)
    if (gain == 1.0f) {
      // Unity — present in the pipeline but applying nothing. A bulk copy
      // is ~25x cheaper per block than walking the samples for a multiply
      // by one, and both consume the input and produce identical bytes.
      out.put(input)
      out.flip()
      return
    }
    if (encoding == C.ENCODING_PCM_FLOAT) {
      val inF = input.asFloatBuffer()
      val outF = out.asFloatBuffer()
      while (inF.hasRemaining()) {
        outF.put(inF.get() * gain)
      }
    } else {
      // 16-bit: scale in float precision, narrow back with round + clamp.
      // The gain is peak-capped upstream, so the clamp only guards
      // sentinel/missing-peak tags against wraparound.
      val inS = input.asShortBuffer()
      val outS = out.asShortBuffer()
      while (inS.hasRemaining()) {
        val scaled = inS.get() * gain
        outS.put(scaled.roundToInt().coerceIn(-32768, 32767).toShort())
      }
    }
    input.position(input.limit())
    // Advance the byte-level position so [getOutput] hands back a
    // buffer with the produced bytes between position and limit — the
    // typed-view writes mutate the underlying bytes but don't move the
    // parent ByteBuffer's position.
    out.position(byteCount)
    out.flip()
  }
}
