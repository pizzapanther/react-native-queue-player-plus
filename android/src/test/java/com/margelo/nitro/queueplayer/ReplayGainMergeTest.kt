package com.margelo.nitro.queueplayer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Coverage for [ReplayGainData.merge] — the all-or-nothing, per-item
 * choice between consumer-supplied ReplayGain and values detected from
 * the file. Mirrors the iOS `ReplayGainMergeTests` matrix row for row,
 * with the same fixtures, so both platforms hold the same rule.
 *
 * The rule is one predicate on the item, not two pair decisions and not
 * four field decisions: a claimed track's ReplayGain comes solely from
 * the consumer and detection is discarded in full — including for the
 * mode the consumer said nothing about. `merge` takes the claim and the
 * surviving values as two separate inputs; which fields make a claim and
 * which values survive is [SuppliedReplayGain]'s job, covered in
 * `SuppliedReplayGainTest`.
 *
 * Supplied and detected fixtures carry different values in every field,
 * so a row that returned the wrong source cannot pass.
 */
class ReplayGainMergeTest {

  // region Fixtures

  private val detectedAll = ReplayGainData(
    trackGainDb = -20.0f, trackPeak = 0.5f,
    albumGainDb = -6.0f, albumPeak = 0.6f,
  )
  private val detectedTrackOnly = ReplayGainData(
    trackGainDb = -20.0f, trackPeak = 0.5f,
    albumGainDb = null, albumPeak = null,
  )
  private val detectedAlbumOnly = ReplayGainData(
    trackGainDb = null, trackPeak = null,
    albumGainDb = -6.0f, albumPeak = 0.6f,
  )

  private val suppliedAll = ReplayGainData(
    trackGainDb = -3.0f, trackPeak = 0.25f,
    albumGainDb = -9.0f, albumPeak = 0.3f,
  )
  private val suppliedTrackGain = ReplayGainData(
    trackGainDb = -3.0f, trackPeak = null,
    albumGainDb = null, albumPeak = null,
  )
  private val suppliedAlbumGain = ReplayGainData(
    trackGainDb = null, trackPeak = null,
    albumGainDb = -9.0f, albumPeak = null,
  )
  private val suppliedTrackPeak = ReplayGainData(
    trackGainDb = null, trackPeak = 0.25f,
    albumGainDb = null, albumPeak = null,
  )
  private val suppliedAlbumPeak = ReplayGainData(
    trackGainDb = null, trackPeak = null,
    albumGainDb = null, albumPeak = 0.3f,
  )
  private val suppliedTrackPair = ReplayGainData(
    trackGainDb = -3.0f, trackPeak = 0.25f,
    albumGainDb = null, albumPeak = null,
  )
  private val suppliedAlbumPair = ReplayGainData(
    trackGainDb = null, trackPeak = null,
    albumGainDb = -9.0f, albumPeak = 0.3f,
  )

  // endregion

  // region No claim — detection is used verbatim

  @Test
  fun `an unclaimed track uses every detected field`() {
    assertMerge(
      claimed = false, supplied = null, detected = detectedAll,
      expected = detectedAll,
      message = "no supplied field means detection is used, all four",
    )
  }

  @Test
  fun `an unclaimed track with no detection has no ReplayGain`() {
    assertMerge(
      claimed = false, supplied = null, detected = null,
      expected = null,
      message = "nothing supplied and nothing detected is no ReplayGain",
    )
  }

  @Test
  fun `an unclaimed track keeps a track-only detection`() {
    assertMerge(
      claimed = false, supplied = null, detected = detectedTrackOnly,
      expected = detectedTrackOnly,
      message = "a partial detection is used as-is, not padded",
    )
  }

  @Test
  fun `an unclaimed track keeps an album-only detection`() {
    assertMerge(
      claimed = false, supplied = null, detected = detectedAlbumOnly,
      expected = detectedAlbumOnly,
      message = "the mirrored partial-detection case",
    )
  }

  // endregion

  // region Claimed — one supplied field discards all detection

  @Test
  fun `a supplied track gain discards every detected field`() {
    assertMerge(
      claimed = true, supplied = suppliedTrackGain, detected = detectedAll,
      expected = suppliedTrackGain,
      message = "supplied trackGain applies; detected peak and album pair are dropped",
    )
  }

  @Test
  fun `a supplied album gain discards every detected field`() {
    assertMerge(
      claimed = true, supplied = suppliedAlbumGain, detected = detectedAll,
      expected = suppliedAlbumGain,
      message = "supplied albumGain applies; detected track pair and album peak are dropped",
    )
  }

  @Test
  fun `a supplied track peak alone leaves no gain anywhere`() {
    assertMerge(
      claimed = true, supplied = suppliedTrackPeak, detected = detectedAll,
      expected = suppliedTrackPeak,
      message = "a peak with no gain is ownership with nothing to apply",
    )
  }

  @Test
  fun `a supplied album peak alone leaves no gain anywhere`() {
    assertMerge(
      claimed = true, supplied = suppliedAlbumPeak, detected = detectedAll,
      expected = suppliedAlbumPeak,
      message = "the mirrored peak-only case",
    )
  }

  @Test
  fun `a supplied track pair discards the detected album pair`() {
    assertMerge(
      claimed = true, supplied = suppliedTrackPair, detected = detectedAll,
      expected = suppliedTrackPair,
      message = "album mode plays at unity even though the file carries album tags",
    )
  }

  @Test
  fun `a supplied album pair discards the detected track pair`() {
    assertMerge(
      claimed = true, supplied = suppliedAlbumPair, detected = detectedAll,
      expected = suppliedAlbumPair,
      message = "track mode plays at unity even though the file carries track tags",
    )
  }

  @Test
  fun `a fully supplied set replaces a fully detected set`() {
    assertMerge(
      claimed = true, supplied = suppliedAll, detected = detectedAll,
      expected = suppliedAll,
      message = "all four supplied values win over all four detected values",
    )
  }

  @Test
  fun `a supplied track gain discards an album-only detection`() {
    assertMerge(
      claimed = true, supplied = suppliedTrackGain, detected = detectedAlbumOnly,
      expected = suppliedTrackGain,
      message = "detection is discarded even where supplied and detected do not overlap",
    )
  }

  // endregion

  // region Claimed with no detection to discard

  @Test
  fun `a fully supplied set applies to an untagged file`() {
    assertMerge(
      claimed = true, supplied = suppliedAll, detected = null,
      expected = suppliedAll,
      message = "a supplied set is applied where the file carries no tags",
    )
  }

  @Test
  fun `a supplied track gain applies to an untagged file`() {
    assertMerge(
      claimed = true, supplied = suppliedTrackGain, detected = null,
      expected = suppliedTrackGain,
      message = "a single supplied field on an untagged file applies alone",
    )
  }

  // endregion

  // region Claimed but nothing usable — ownership without a value

  @Test
  fun `a claim with no usable value discards a full detection`() {
    assertMerge(
      claimed = true, supplied = null, detected = detectedAll,
      expected = null,
      message = "a claim discards detection even when it carries no value",
    )
  }

  @Test
  fun `a claim with no usable value discards a partial detection`() {
    assertMerge(
      claimed = true, supplied = null, detected = detectedTrackOnly,
      expected = null,
      message = "a partial detection is discarded by a claim just as a full one is",
    )
  }

  @Test
  fun `a claim with no usable value and no detection has no ReplayGain`() {
    assertMerge(
      claimed = true, supplied = null, detected = null,
      expected = null,
      message = "ownership claimed, nothing supplied, nothing detected",
    )
  }

  // endregion

  // region Helper

  private fun assertMerge(
    claimed: Boolean,
    supplied: ReplayGainData?,
    detected: ReplayGainData?,
    expected: ReplayGainData?,
    message: String,
  ) {
    val actual = ReplayGainData.merge(
      suppliedClaimed = claimed,
      supplied = supplied,
      detected = detected,
    )
    assertEquals(message, expected, actual)
  }

  // endregion
}
