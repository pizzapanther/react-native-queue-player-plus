package com.margelo.nitro.queueplayer

import androidx.media3.common.PlaybackParameters

/**
 * Maps the public [PitchCorrectionMode] to Media3 [PlaybackParameters].
 *
 * - `none` → pitch follows rate (`pitch == speed`): Media3's built-in
 *   time-stretch resamples, so pitch shifts with speed.
 * - `voice` → pitch preserved (`pitch == 1`): the built-in (speech-tuned)
 *   time-stretch holds pitch while changing speed.
 * - `music` → the same parameters as `voice` (`pitch == 1`); which
 *   stretcher applies them is `PitchAwareAudioProcessorChain`'s choice —
 *   `SignalsmithStretchAudioProcessor` for `music`, Sonic otherwise.
 *
 * At `speed == 1.0` every mode yields `(1.0, 1.0)`, so the built-in
 * stretch is inactive — the auto-bypass at unity rate.
 */
internal object PitchCorrection {
  fun playbackParameters(mode: PitchCorrectionMode, speed: Float): PlaybackParameters {
    val pitch = when (mode) {
      PitchCorrectionMode.NONE -> speed
      PitchCorrectionMode.VOICE -> 1.0f
      PitchCorrectionMode.MUSIC -> 1.0f
    }
    return PlaybackParameters(speed, pitch)
  }
}
