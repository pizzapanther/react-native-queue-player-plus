package com.margelo.nitro.queueplayer

import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Routing coverage for [PitchAwareAudioProcessorChain]: which stretcher is
 * actually stretching for each [PitchCorrectionMode] and speed. The native
 * stretch itself runs on-device; these assert only the mode gate, which
 * runs without the native library.
 *
 * Note Signalsmith is intentionally ALWAYS active on the float path (so Media3's
 * configure-time pipeline never excludes it); "is it stretching" — not "is it
 * active" — is the meaningful per-mode assertion.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@UnstableApi
class PitchAwareAudioProcessorChainTest {

  private val floatStereo = AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_FLOAT)
  private val int16Stereo = AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT)

  private fun newConfiguredChain(
    format: AudioProcessor.AudioFormat = floatStereo,
  ): PitchAwareAudioProcessorChain {
    val chain = PitchAwareAudioProcessorChain(emptyArray())
    // configure() alone makes BaseAudioProcessor.isActive() reflect state (it
    // gates on pendingOutputAudioFormat, set by configure).
    for (processor in chain.audioProcessors) processor.configure(format)
    return chain
  }

  private val PitchAwareAudioProcessorChain.sonic: SonicAudioProcessor
    get() = audioProcessors.first { it is SonicAudioProcessor } as SonicAudioProcessor
  private val PitchAwareAudioProcessorChain.signalsmith: SignalsmithStretchAudioProcessor
    get() = audioProcessors.first { it is SignalsmithStretchAudioProcessor }
      as SignalsmithStretchAudioProcessor

  @Test
  fun `chain exposes pre-processors then sonic then signalsmith in order`() {
    val pre = ReplayGainAudioProcessor()
    val procs = PitchAwareAudioProcessorChain(arrayOf(pre)).audioProcessors
    assertEquals(3, procs.size)
    assertSame(pre, procs[0])
    assertTrue(procs[1] is SonicAudioProcessor)
    assertTrue(procs[2] is SignalsmithStretchAudioProcessor)
  }

  @Test
  fun `signalsmith stays active on the float path even when not stretching`() {
    // Media3's pipeline is static: Signalsmith must remain in the
    // active set regardless of mode/speed so it can stretch when music kicks in.
    val chain = newConfiguredChain()
    chain.setMode(PitchCorrectionMode.VOICE)
    chain.applyPlaybackParameters(PlaybackParameters(1f, 1f))
    assertTrue("signalsmith must stay active on the float path", chain.signalsmith.isActive)
    assertFalse("but it must not be stretching at unity voice", chain.signalsmith.isStretching())
  }

  @Test
  fun `music mode at non-unity speed stretches via signalsmith only`() {
    val chain = newConfiguredChain()
    chain.setMode(PitchCorrectionMode.MUSIC)
    chain.applyPlaybackParameters(PlaybackParameters(2f, 1f))
    assertTrue(chain.signalsmith.isStretching())
    assertFalse(chain.sonic.isActive)
  }

  @Test
  fun `signalsmith handles 16-bit PCM, the common decoder output`() {
    // Local tracks frequently decode to 16-bit PCM (Media3 keeps the chain at
    // 16-bit unless the source is float/high-res). Music mode must stretch
    // those too, so the processor stays active + stretches on 16-bit.
    val chain = newConfiguredChain(int16Stereo)
    chain.setMode(PitchCorrectionMode.MUSIC)
    chain.applyPlaybackParameters(PlaybackParameters(2f, 1f))
    assertTrue("signalsmith must stretch 16-bit input", chain.signalsmith.isStretching())
    assertFalse(chain.sonic.isActive)
  }

  @Test
  fun `voice mode at non-unity speed stretches via sonic only`() {
    val chain = newConfiguredChain()
    chain.setMode(PitchCorrectionMode.VOICE)
    chain.applyPlaybackParameters(PlaybackParameters(2f, 1f))
    assertTrue(chain.sonic.isActive)
    assertFalse(chain.signalsmith.isStretching())
  }

  @Test
  fun `none mode at non-unity speed stretches via sonic only`() {
    val chain = newConfiguredChain()
    chain.setMode(PitchCorrectionMode.NONE)
    chain.applyPlaybackParameters(PlaybackParameters(2f, 2f))
    assertTrue(chain.sonic.isActive)
    assertFalse(chain.signalsmith.isStretching())
  }

  @Test
  fun `unity speed leaves neither stretcher stretching in every mode`() {
    for (mode in PitchCorrectionMode.values()) {
      val chain = newConfiguredChain()
      chain.setMode(mode)
      chain.applyPlaybackParameters(PlaybackParameters(1f, 1f))
      assertFalse("sonic stretching at unity in $mode", chain.sonic.isActive)
      assertFalse("signalsmith stretching at unity in $mode", chain.signalsmith.isStretching())
    }
  }

  @Test
  fun `applyPlaybackParameters returns the input parameters unchanged`() {
    val chain = newConfiguredChain()
    chain.setMode(PitchCorrectionMode.MUSIC)
    val params = PlaybackParameters(1.5f, 1f)
    assertSame(params, chain.applyPlaybackParameters(params))
  }

  @Test
  fun `skip silence is never enabled`() {
    assertFalse(newConfiguredChain().applySkipSilenceEnabled(true))
  }

  @Test
  fun `skipped output frame count is zero`() {
    assertEquals(0L, newConfiguredChain().getSkippedOutputFrameCount())
  }

  @Test
  fun `media duration passes through when nothing is stretching`() {
    val chain = newConfiguredChain()
    chain.setMode(PitchCorrectionMode.MUSIC)
    chain.applyPlaybackParameters(PlaybackParameters(1f, 1f)) // unity ⇒ not stretching
    assertEquals(123_456L, chain.getMediaDuration(123_456L))
  }
}
