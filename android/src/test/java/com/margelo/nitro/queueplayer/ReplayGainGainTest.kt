package com.margelo.nitro.queueplayer

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.pow

/**
 * Pure-math unit coverage for [computeLinearGain]. Mirrors the iOS
 * `AudioTapProvider.computeLinearGain(data:mode:)` matrix — same
 * fixtures must produce the same linear gain on both platforms.
 */
class ReplayGainGainTest {

  private fun lin(db: Double): Float = 10.0.pow(db / 20.0).toFloat()

  @Test
  fun `off mode returns unity regardless of tags`() {
    val both = ReplayGainData(
      trackGainDb = -12.0f,
      trackPeak = 0.95f,
      albumGainDb = -8.0f,
      albumPeak = 0.92f,
    )
    assertEquals(1.0f, computeLinearGain(both, ReplayGainMode.OFF), 0f)
  }

  @Test
  fun `track mode picks the track gain`() {
    val data = ReplayGainData(
      trackGainDb = -6.0f,
      trackPeak = null,
      albumGainDb = null,
      albumPeak = null,
    )
    assertEquals(lin(-6.0), computeLinearGain(data, ReplayGainMode.TRACK), 1e-6f)
  }

  @Test
  fun `album mode picks the album gain`() {
    val data = ReplayGainData(
      trackGainDb = -12.0f,
      trackPeak = null,
      albumGainDb = -8.0f,
      albumPeak = null,
    )
    assertEquals(lin(-8.0), computeLinearGain(data, ReplayGainMode.ALBUM), 1e-6f)
  }

  @Test
  fun `album mode returns unity when only track tag present (strict, no fallback)`() {
    val data = ReplayGainData(
      trackGainDb = -6.0f,
      trackPeak = 0.95f,
      albumGainDb = null,
      albumPeak = null,
    )
    assertEquals(1.0f, computeLinearGain(data, ReplayGainMode.ALBUM), 0f)
  }

  @Test
  fun `track mode returns unity when only album tag present`() {
    val data = ReplayGainData(
      trackGainDb = null,
      trackPeak = null,
      albumGainDb = -8.0f,
      albumPeak = 0.92f,
    )
    assertEquals(1.0f, computeLinearGain(data, ReplayGainMode.TRACK), 0f)
  }

  @Test
  fun `peak cap engages when raw gain would clip the peak`() {
    // +6 dB raw ≈ 1.995; peak 0.5 → 1/peak = 2.0 — raw fits, no cap.
    // +6 dB raw ≈ 1.995; peak 0.8 → 1/peak = 1.25 — cap at 1.25.
    val data = ReplayGainData(
      trackGainDb = 6.0f,
      trackPeak = 0.8f,
      albumGainDb = null,
      albumPeak = null,
    )
    assertEquals(1.25f, computeLinearGain(data, ReplayGainMode.TRACK), 1e-6f)
  }

  @Test
  fun `peak cap is no-op when raw gain is below 1 over peak`() {
    // -6 dB raw ≈ 0.501; peak 0.95 → 1/peak ≈ 1.053 — raw < cap.
    val data = ReplayGainData(
      trackGainDb = -6.0f,
      trackPeak = 0.95f,
      albumGainDb = null,
      albumPeak = null,
    )
    assertEquals(lin(-6.0), computeLinearGain(data, ReplayGainMode.TRACK), 1e-6f)
  }

  @Test
  fun `a full-scale peak caps the boost at unity`() {
    // 1.0 is digital full scale, so 1/peak == 1.0 and a +6 dB boost
    // (raw ≈ 1.995) has nowhere to go: any boost on a full-scale
    // master clips. The cap binds to exactly unity.
    val data = ReplayGainData(
      trackGainDb = 6.0f,
      trackPeak = 1.0f,
      albumGainDb = null,
      albumPeak = null,
    )
    assertEquals(1.0f, computeLinearGain(data, ReplayGainMode.TRACK), 1e-6f)
  }

  @Test
  fun `a full-scale peak leaves attenuation alone`() {
    // -20 dB raw = 0.100, well under the 1.0 cap — the cap is present
    // and simply does not bind. Attenuation can never clip.
    val data = ReplayGainData(
      trackGainDb = -20.0f,
      trackPeak = 1.0f,
      albumGainDb = null,
      albumPeak = null,
    )
    assertEquals(lin(-20.0), computeLinearGain(data, ReplayGainMode.TRACK), 1e-6f)
  }

  @Test
  fun `an unusable peak clamps the boost to unity`() {
    // A peak below 0.001 carries no usable measurement, and a peak
    // cannot be derived from a gain — so boost is suppressed rather
    // than run uncapped.
    for (peak in listOf(0.0005f, 0.0001f)) {
      val data = ReplayGainData(
        trackGainDb = 6.0f,
        trackPeak = peak,
        albumGainDb = null,
        albumPeak = null,
      )
      assertEquals(
        "peak $peak carries no usable measurement",
        1.0f,
        computeLinearGain(data, ReplayGainMode.TRACK),
        1e-6f,
      )
    }
  }

  @Test
  fun `no peak at all clamps the boost to unity`() {
    val data = ReplayGainData(
      trackGainDb = 6.0f,
      trackPeak = null,
      albumGainDb = null,
      albumPeak = null,
    )
    assertEquals(1.0f, computeLinearGain(data, ReplayGainMode.TRACK), 1e-6f)
  }

  @Test
  fun `no peak at all leaves attenuation untouched`() {
    // The positive half of the clamp: the boost suppression must not
    // become a blanket clamp that also flattens cuts.
    val data = ReplayGainData(
      trackGainDb = -20.0f,
      trackPeak = null,
      albumGainDb = null,
      albumPeak = null,
    )
    assertEquals(lin(-20.0), computeLinearGain(data, ReplayGainMode.TRACK), 1e-6f)
  }

  @Test
  fun `a zero gain with no peak stays at unity`() {
    val data = ReplayGainData(
      trackGainDb = 0.0f,
      trackPeak = null,
      albumGainDb = null,
      albumPeak = null,
    )
    assertEquals(1.0f, computeLinearGain(data, ReplayGainMode.TRACK), 1e-6f)
  }

  @Test
  fun `a peak with headroom lets the full boost through`() {
    // The positive control for the clamp rows above: peak 0.5 gives
    // 1/peak = 2.0, so +6 dB (≈ 1.9953) fits underneath and is applied
    // in full. Without this, "expect 1.000" everywhere is satisfied by
    // an implementation that applies nothing at all.
    val data = ReplayGainData(
      trackGainDb = 6.0f,
      trackPeak = 0.5f,
      albumGainDb = null,
      albumPeak = null,
    )
    assertEquals(1.9953f, computeLinearGain(data, ReplayGainMode.TRACK), 1e-4f)
  }

  @Test
  fun `a boost beyond the headroom is capped at 1 over peak`() {
    // +12 dB raw ≈ 3.981; peak 0.5 → 1/peak = 2.0 — cap binds.
    val data = ReplayGainData(
      trackGainDb = 12.0f,
      trackPeak = 0.5f,
      albumGainDb = null,
      albumPeak = null,
    )
    assertEquals(2.0f, computeLinearGain(data, ReplayGainMode.TRACK), 1e-6f)
  }

  @Test
  fun `a true peak above unity caps the boost below unity`() {
    // Peaks above 1.0 are ordinary true-peak measurements: 1/1.16 =
    // 0.862, so a +6 dB boost ends up as an attenuation.
    val data = ReplayGainData(
      trackGainDb = 6.0f,
      trackPeak = 1.16f,
      albumGainDb = null,
      albumPeak = null,
    )
    assertEquals(1.0f / 1.16f, computeLinearGain(data, ReplayGainMode.TRACK), 1e-4f)
  }

  @Test
  fun `the clip cap applies in album mode as well as track mode`() {
    // Wiring the cap on the track pair alone passes every row above.
    val noPeak = ReplayGainData(
      trackGainDb = null,
      trackPeak = null,
      albumGainDb = 6.0f,
      albumPeak = null,
    )
    assertEquals(
      "album mode with no album peak clamps the boost to unity",
      1.0f,
      computeLinearGain(noPeak, ReplayGainMode.ALBUM),
      1e-6f,
    )
    val fullScale = ReplayGainData(
      trackGainDb = null,
      trackPeak = null,
      albumGainDb = 6.0f,
      albumPeak = 1.0f,
    )
    assertEquals(
      "album mode with a full-scale album peak caps the boost at unity",
      1.0f,
      computeLinearGain(fullScale, ReplayGainMode.ALBUM),
      1e-6f,
    )
  }

  @Test
  fun `each mode caps against its own peak`() {
    // Same gain in both modes, different peaks — the applied value has
    // to differ, which shows the cap reads the selected pair's peak
    // rather than whichever peak the data happens to carry.
    val data = ReplayGainData(
      trackGainDb = 6.0f,
      trackPeak = 0.5f,
      albumGainDb = 6.0f,
      albumPeak = 1.16f,
    )
    assertEquals(1.9953f, computeLinearGain(data, ReplayGainMode.TRACK), 1e-4f)
    assertEquals(1.0f / 1.16f, computeLinearGain(data, ReplayGainMode.ALBUM), 1e-4f)
  }

  @Test
  fun `null data returns unity for every mode`() {
    assertEquals(1.0f, computeLinearGain(null, ReplayGainMode.OFF), 0f)
    assertEquals(1.0f, computeLinearGain(null, ReplayGainMode.TRACK), 0f)
    assertEquals(1.0f, computeLinearGain(null, ReplayGainMode.ALBUM), 0f)
  }
}
