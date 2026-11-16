package com.margelo.nitro.queueplayer

/**
 * Parsed ReplayGain tag values for a single track. All four fields
 * are independent — a track may carry only track tags, only album
 * tags, or both. The Opus container guard returns nil from
 * `ReplayGainExtractor.extract(...)` rather than producing a
 * `ReplayGainData` with one nil pair.
 *
 * `Float` rather than `Double` because Media3 audio processors operate
 * on Float32 PCM; the gain pushed into [ReplayGainAudioProcessor] is a
 * Float, and converting at the JS-bridge boundary instead of inside
 * the audio path keeps the processor allocation-free.
 *
 * Strict mode (`.album` requires album tag, `.track` requires track
 * tag) means there is no fallback — see [selectGain].
 */
data class ReplayGainData(
  val trackGainDb: Float?,
  val trackPeak: Float?,
  val albumGainDb: Float?,
  val albumPeak: Float?,
) {
  /**
   * Strict mode-aware selector. See NOTES.md section 25.
   *
   * - [ReplayGainMode.OFF]: returns `(null, null)` (no-op).
   * - [ReplayGainMode.TRACK]: returns `(trackGainDb, trackPeak)` —
   *   both may be null when the track tag is absent.
   * - [ReplayGainMode.ALBUM]: returns `(albumGainDb, albumPeak)` —
   *   both may be null when the album tag is absent. No fallback
   *   to track gain.
   *
   * Caller computes `linearGain = 10^(gainDb / 20)` and clip-caps it
   * at `1 / peak` when the peak is usable, at unity when it is not —
   * see [computeLinearGain]. When `gainDb` is null the caller treats
   * RG as inactive for this track (`linearGain = 1.0f`,
   * `replayGainActive = false`).
   */
  fun selectGain(mode: ReplayGainMode): Pair<Float?, Float?> = when (mode) {
    ReplayGainMode.OFF -> null to null
    ReplayGainMode.TRACK -> trackGainDb to trackPeak
    ReplayGainMode.ALBUM -> albumGainDb to albumPeak
  }

  companion object {
    /**
     * Resolve the ReplayGain a track plays with, all-or-nothing per
     * item. [suppliedClaimed] is `true` when the consumer put any of
     * the four fields on the `TrackItem`, computed before any value
     * is validated: a claim discards detection outright, including
     * for the mode the consumer never mentioned.
     * `suppliedClaimed && supplied == null` is a real state — the
     * consumer owns the track and gave nothing usable, so no gain is
     * applied and the file's own tags stay unused.
     */
    internal fun merge(
      suppliedClaimed: Boolean,
      supplied: ReplayGainData?,
      detected: ReplayGainData?,
    ): ReplayGainData? = if (suppliedClaimed) supplied else detected
  }
}
