package com.margelo.nitro.queueplayer

import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [SuppliedReplayGain.from] separates the consumer's claim over a track
 * from the values that survive validation: any present field claims the
 * track, and only accepted values are carried.
 */
@UnstableApi
class SuppliedReplayGainTest {

  @Test
  fun `a track with no ReplayGain field makes no claim`() {
    assertNull(SuppliedReplayGain.from(track()))
  }

  @Test
  fun `one valid field claims the track and is carried`() {
    val claim = SuppliedReplayGain.from(track(trackGain = -3.0))

    assertNotNull(claim)
    assertEquals(
      ReplayGainData(trackGainDb = -3.0f, trackPeak = null, albumGainDb = null, albumPeak = null),
      claim?.data)
  }

  @Test
  fun `only invalid fields still claim the track but carry nothing`() {
    val claim = SuppliedReplayGain.from(
      track(trackGain = Double.NaN, trackPeak = 5.0, albumGain = Double.POSITIVE_INFINITY, albumPeak = 0.0))

    assertNotNull("presence claims the track, whatever the values", claim)
    assertNull("no value passed acceptance, so there is nothing to apply", claim?.data)
  }

  @Test
  fun `a mix carries the accepted fields and drops the rest`() {
    val claim = SuppliedReplayGain.from(
      track(trackGain = -3.0, trackPeak = 5.0, albumGain = -9.0, albumPeak = 0.3))

    assertEquals(
      ReplayGainData(trackGainDb = -3.0f, trackPeak = null, albumGainDb = -9.0f, albumPeak = 0.3f),
      claim?.data)
  }

  private fun track(
    trackGain: Double? = null,
    trackPeak: Double? = null,
    albumGain: Double? = null,
    albumPeak: Double? = null,
  ) = TrackItem(
    id = null, url = "https://example.com/a.mp3", title = null, artist = null, album = null,
    duration = null, artworkUrl = null, extras = null,
    replayGainTrackGain = trackGain, replayGainTrackPeak = trackPeak,
    replayGainAlbumGain = albumGain, replayGainAlbumPeak = albumPeak, isVideo = null)
}
