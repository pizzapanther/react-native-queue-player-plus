package com.margelo.nitro.queueplayer

import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi

/**
 * Custom [AudioProcessorChain] that gates the pitch-correction time-stretch on
 * the active [PitchCorrectionMode]. It holds BOTH Media3's built-in
 * [SonicAudioProcessor] (none/voice) and the [SignalsmithStretchAudioProcessor]
 * (music) in one chain, so switching modes at runtime never rebuilds the sink.
 * Exactly one stretcher is ever active: the owning engine pushes the mode via
 * [setMode], and [applyPlaybackParameters] routes the speed to the matching one
 * and neutralizes the other (speed == 1 ⇒ inactive). none/voice are
 * indistinguishable in [PlaybackParameters] alone, which is why the mode is
 * pushed explicitly rather than inferred.
 *
 * Supplied to `DefaultAudioSink.Builder.setAudioProcessorChain`, which — unlike
 * `setAudioProcessors` — does NOT auto-append Sonic + silence-skipping. This
 * chain owns the full composition: [preProcessors] (ReplayGain, visualizer Tee)
 * run first, then the two stretchers. Silence-skipping is intentionally absent
 * (unused on this path), so [applySkipSilenceEnabled] returns false.
 */
@UnstableApi
internal class PitchAwareAudioProcessorChain(
  preProcessors: Array<AudioProcessor>,
) : AudioProcessorChain {

  private val sonic = SonicAudioProcessor()
  private val signalsmith = SignalsmithStretchAudioProcessor()
  private val processors: Array<AudioProcessor> =
    preProcessors + arrayOf<AudioProcessor>(sonic, signalsmith)

  @Volatile
  private var mode: PitchCorrectionMode = PitchCorrectionMode.VOICE

  fun setMode(value: PitchCorrectionMode) {
    mode = value
  }

  override fun getAudioProcessors(): Array<AudioProcessor> = processors

  override fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters {
    if (mode == PitchCorrectionMode.MUSIC) {
      // Signalsmith does the music-grade stretch; Sonic neutralized.
      signalsmith.setSpeed(playbackParameters.speed)
      sonic.setSpeed(1f)
      sonic.setPitch(1f)
    } else {
      // none/voice → Sonic applies speed (+ pitch); Signalsmith idle.
      sonic.setSpeed(playbackParameters.speed)
      sonic.setPitch(playbackParameters.pitch)
      signalsmith.setSpeed(1f)
    }
    return playbackParameters
  }

  override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean): Boolean = false

  override fun getMediaDuration(playoutDuration: Long): Long = when {
    signalsmith.isStretching() -> signalsmith.getMediaDuration(playoutDuration)
    sonic.isActive -> sonic.getMediaDuration(playoutDuration)
    else -> playoutDuration
  }

  override fun getSkippedOutputFrameCount(): Long = 0L
}
