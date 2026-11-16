package com.margelo.nitro.queueplayer

import kotlin.math.min
import kotlin.math.pow

/**
 * Pure gain math per NOTES.md section 25. Selects the `(gainDb, peak)` pair
 * from [data] under [mode] (strict, no fallback), then caps the result
 * for clip safety:
 *
 * ```
 * cap = usable peak ? 1.0 / peak : 1.0
 * linearGain = min(pow(10, gainDb / 20), cap)
 * ```
 *
 * A peak is usable inside the `0.001 .. 2.0` band that
 * [ReplayGainExtractor.acceptPeak] holds — full scale (`1.0`) and true
 * peaks above it included. With no usable peak the cap sits at unity,
 * so a boost is suppressed rather than applied unprotected: a peak
 * cannot be derived from a gain. Attenuation is untouched either way,
 * since a negative gain can never clip. The band is re-checked here so
 * the math stays correct for any caller that bypasses the extractor.
 *
 * Returns `1.0f` (unity) whenever the selected mode's gain tag is
 * absent — RG is inactive for that track. Mirrors the iOS
 * `AudioTapProvider.computeLinearGain(data:mode:)` contract so a tag
 * fixture parses to the same linear gain on both platforms.
 */
internal fun computeLinearGain(data: ReplayGainData?, mode: ReplayGainMode): Float {
  val (gainDb, peak) = data?.selectGain(mode) ?: (null to null)
  if (gainDb == null) return 1.0f
  val raw = 10.0.pow(gainDb.toDouble() / 20.0).toFloat()
  var cap = 1.0f
  if (peak != null && peak >= 0.001f && peak <= 2.0f) {
    cap = 1.0f / peak
  }
  return min(raw, cap)
}
