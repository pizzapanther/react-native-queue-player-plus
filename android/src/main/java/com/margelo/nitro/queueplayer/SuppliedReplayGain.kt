package com.margelo.nitro.queueplayer

import androidx.media3.common.util.UnstableApi

/**
 * The consumer's ReplayGain claim over one queue entry, resolved from
 * the `TrackItem` the engine is playing.
 *
 * The claim and the values it survived with are two separate facts, and
 * keeping them separate is the whole point of this type. A `TrackItem`
 * that supplies only an out-of-band peak has claimed its track just as
 * firmly as one that supplies all four values, so collapsing the claim
 * into "did any value survive acceptance" would hand that track back to
 * the file's own tags. Presence claims the track; validity only decides
 * what there is to apply.
 *
 * An instance therefore exists whenever any of the four fields was
 * present, and [data] is `null` when none of those values survived
 * [ReplayGainExtractor]'s acceptance rules — a track that owns its
 * ReplayGain and plays at unity.
 *
 * Mirrors the iOS `SuppliedReplayGain`, which rides on the
 * `AVPlayerItem` rather than being resolved per read; the shapes match
 * so one contract covers both platforms.
 */
@UnstableApi
internal class SuppliedReplayGain private constructor(val data: ReplayGainData?) {
  companion object {
    /**
     * Build the claim for [track], or `null` when the consumer supplied
     * none of the four ReplayGain fields and the track is left on
     * whatever its file carries.
     *
     * Each present value goes through the same
     * [ReplayGainExtractor.acceptGainDb] / [ReplayGainExtractor.acceptPeak]
     * pair the tag strings land on, so a supplied number and a tagged
     * number are held to one rule.
     */
    fun from(track: TrackItem): SuppliedReplayGain? {
      if (track.replayGainTrackGain == null &&
        track.replayGainTrackPeak == null &&
        track.replayGainAlbumGain == null &&
        track.replayGainAlbumPeak == null
      ) {
        return null
      }
      val trackGain = track.replayGainTrackGain?.let { ReplayGainExtractor.acceptGainDb(it) }
      val trackPeak = track.replayGainTrackPeak?.let { ReplayGainExtractor.acceptPeak(it) }
      val albumGain = track.replayGainAlbumGain?.let { ReplayGainExtractor.acceptGainDb(it) }
      val albumPeak = track.replayGainAlbumPeak?.let { ReplayGainExtractor.acceptPeak(it) }
      if (trackGain == null && trackPeak == null &&
        albumGain == null && albumPeak == null
      ) {
        return SuppliedReplayGain(null)
      }
      return SuppliedReplayGain(
        ReplayGainData(
          trackGainDb = trackGain,
          trackPeak = trackPeak,
          albumGainDb = albumGain,
          albumPeak = albumPeak,
        )
      )
    }
  }
}
