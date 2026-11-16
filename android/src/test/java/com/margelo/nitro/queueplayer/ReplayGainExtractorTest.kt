package com.margelo.nitro.queueplayer

import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.MdtaMetadataEntry
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import com.google.common.collect.ImmutableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pure-parser coverage for [ReplayGainExtractor] + the
 * [ReplayGainData.selectGain] mode-aware selector. Mirrors the iOS
 * test matrix — same input strings, same expected outputs — so a
 * fixture tagged once parses identically across platforms.
 *
 * Robolectric runner is required because the Media3 metadata-entry
 * classes ([TextInformationFrame], [MdtaMetadataEntry]) reach into
 * Android utility code at construction time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@UnstableApi
class ReplayGainExtractorTest {

  // region parseGainDb tolerance

  @Test
  fun `parseGainDb reads the standard '-6_54 dB' format`() {
    assertGain("-6.54 dB", -6.54f)
    assertGain("+3.21 dB", 3.21f)
    assertGain("0.00 dB", 0.0f)
  }

  @Test
  fun `parseGainDb accepts a bare number with no dB suffix`() {
    assertGain("-6.54", -6.54f)
    assertGain("+3.21", 3.21f)
  }

  @Test
  fun `parseGainDb accepts a missing space before dB`() {
    assertGain("-6.54dB", -6.54f)
  }

  @Test
  fun `parseGainDb tolerates leading and trailing whitespace`() {
    assertGain("  -1.0 dB  ", -1.0f)
    assertGain("\t-6.54 dB\n", -6.54f)
  }

  @Test
  fun `parseGainDb tolerates extra-internal whitespace`() {
    // Some taggers emit the sign separated from the digits, or the
    // integer part separated from the decimal. NOTES.md section 25 requires
    // both shapes to parse to the same Float as the canonical form.
    assertGain("- 6.54 dB", -6.54f)
    assertGain("+ 3.21 dB", 3.21f)
    assertGain("-6 .54 dB", -6.54f)
  }

  @Test
  fun `parseGainDb is case-insensitive on the dB suffix`() {
    assertGain("-6.54 DB", -6.54f)
    assertGain("-6.54 Db", -6.54f)
  }

  @Test
  fun `parseGainDb normalises U+2212 minus sign to ASCII '-'`() {
    // U+2212 MINUS SIGN — emitted by some mac-based taggers.
    assertGain("−6.54 dB", -6.54f)
  }

  @Test
  fun `parseGainDb rejects comma decimals`() {
    // RG2 spec is period-decimal only.
    assertNull(ReplayGainExtractor.parseGainDb("-6,54 dB"))
  }

  @Test
  fun `parseGainDb rejects non-numeric values`() {
    assertNull(ReplayGainExtractor.parseGainDb("abc"))
    assertNull(ReplayGainExtractor.parseGainDb(""))
    assertNull(ReplayGainExtractor.parseGainDb("dB"))
    assertNull(ReplayGainExtractor.parseGainDb(" "))
  }

  @Test
  fun `parseGainDb rejects NaN and Inf`() {
    assertNull(ReplayGainExtractor.parseGainDb("nan dB"))
    assertNull(ReplayGainExtractor.parseGainDb("inf dB"))
    assertNull(ReplayGainExtractor.parseGainDb("-inf"))
  }

  // endregion

  // region parsePeak acceptance band

  @Test
  fun `parsePeak reads the standard linear format`() {
    assertPeak("0.987654", 0.987654f)
    assertPeak("0.5", 0.5f)
    assertPeak("1.234567", 1.234567f)
  }

  @Test
  fun `parsePeak accepts a peak of exactly 1_0`() {
    // 1.0 is the RG2 encoding of digital full scale — a real and
    // common measurement for a modern master, and the value most in
    // need of the 1/peak clip cap.
    assertPeak("1.000000", 1.0f)
    assertPeak("1", 1.0f)
  }

  @Test
  fun `parsePeak accepts real measurements either side of unity`() {
    assertPeak("0.923366", 0.923366f)
    assertPeak("1.160082", 1.160082f)
  }

  @Test
  fun `parsePeak accepts the 0_001 and 2_0 boundaries and rejects just outside them`() {
    assertPeak("0.001", 0.001f)
    assertPeak("2.0", 2.0f)
    assertNull(
      "0.000999 is below the encoder-zero floor",
      ReplayGainExtractor.parsePeak("0.000999"),
    )
    assertNull(
      "2.000001 is an implausible true peak",
      ReplayGainExtractor.parsePeak("2.000001"),
    )
  }

  @Test
  fun `parsePeak rejects peaks below 0_001`() {
    assertNull(ReplayGainExtractor.parsePeak("0.0001"))
    assertNull(ReplayGainExtractor.parsePeak("0"))
  }

  @Test
  fun `parsePeak rejects values outside the 0-to-2 range`() {
    assertNull(ReplayGainExtractor.parsePeak("-0.5"))
    assertNull(ReplayGainExtractor.parsePeak("3.0"))
  }

  @Test
  fun `parsePeak rejects unparseable input`() {
    assertNull(ReplayGainExtractor.parsePeak("abc"))
    assertNull(ReplayGainExtractor.parsePeak(""))
    assertNull(ReplayGainExtractor.parsePeak("nan"))
    assertNull(ReplayGainExtractor.parsePeak("inf"))
  }

  // endregion

  // region acceptPeak — the numeric entry point

  @Test
  fun `acceptPeak accepts full scale and the real-measurement band`() {
    assertAcceptedPeak(1.0, 1.0f)
    assertAcceptedPeak(0.923366, 0.923366f)
    assertAcceptedPeak(1.160082, 1.160082f)
    assertAcceptedPeak(0.5, 0.5f)
  }

  @Test
  fun `acceptPeak accepts the 0_001 and 2_0 boundaries and rejects just outside them`() {
    assertAcceptedPeak(0.001, 0.001f)
    assertAcceptedPeak(2.0, 2.0f)
    assertNull("0.000999 is below the encoder-zero floor", ReplayGainExtractor.acceptPeak(0.000999))
    assertNull("2.000001 is an implausible true peak", ReplayGainExtractor.acceptPeak(2.000001))
    assertNull(ReplayGainExtractor.acceptPeak(0.0))
    assertNull(ReplayGainExtractor.acceptPeak(-0.5))
    assertNull(ReplayGainExtractor.acceptPeak(3.0))
  }

  @Test
  fun `acceptPeak rejects a value above 2_0 that narrows to 2_0 as a Float`() {
    // 2.0000001 is inside half a Float ulp of 2.0, so a range check
    // applied after the Double -> Float narrowing sees 2.0f and lets
    // it through. The 2.0 case is the positive control: this test
    // must not be satisfiable by rejecting everything.
    assertAcceptedPeak(2.0, 2.0f)
    assertNull(ReplayGainExtractor.acceptPeak(2.0000001))
  }

  @Test
  fun `acceptPeak rejects non-finite input`() {
    assertAcceptedPeak(1.0, 1.0f)
    assertNull(ReplayGainExtractor.acceptPeak(Double.NaN))
    assertNull(ReplayGainExtractor.acceptPeak(Double.POSITIVE_INFINITY))
    assertNull(ReplayGainExtractor.acceptPeak(Double.NEGATIVE_INFINITY))
  }

  // endregion

  // region acceptGainDb — the numeric entry point

  @Test
  fun `acceptGainDb accepts every finite value with no plausibility band`() {
    assertAcceptedGain(-20.0, -20.0f)
    assertAcceptedGain(-6.54, -6.54f)
    assertAcceptedGain(0.0, 0.0f)
    assertAcceptedGain(6.0, 6.0f)
    assertAcceptedGain(3.21, 3.21f)
    // Physically absurd, still accepted — gain carries no range guard;
    // clip safety is the clamp's job, not the acceptor's.
    assertAcceptedGain(100.0, 100.0f)
    assertAcceptedGain(-100.0, -100.0f)
  }

  @Test
  fun `acceptGainDb rejects non-finite input`() {
    assertAcceptedGain(-20.0, -20.0f)
    assertNull(ReplayGainExtractor.acceptGainDb(Double.NaN))
    assertNull(ReplayGainExtractor.acceptGainDb(Double.POSITIVE_INFINITY))
    assertNull(ReplayGainExtractor.acceptGainDb(Double.NEGATIVE_INFINITY))
  }

  @Test
  fun `acceptGainDb rejects a magnitude that narrows to Float infinity`() {
    // Finite as a Double, infinite as a Float — the returned value has
    // to be finite, so the check belongs after the narrowing.
    assertAcceptedGain(-20.0, -20.0f)
    assertNull(ReplayGainExtractor.acceptGainDb(1e40))
    assertNull(ReplayGainExtractor.acceptGainDb(-1e40))
  }

  // endregion

  // region String and numeric entry points share one rule

  @Test
  fun `parsePeak and acceptPeak agree on every value in the peak acceptance table`() {
    val table = listOf(
      "1.000000" to 1.0,
      "1" to 1.0,
      "0.923366" to 0.923366,
      "1.160082" to 1.160082,
      "0.5" to 0.5,
      "2.0" to 2.0,
      "2.000001" to 2.000001,
      "0.001" to 0.001,
      "0.000999" to 0.000999,
      "0" to 0.0,
      "-0.5" to -0.5,
      "3.0" to 3.0,
      "nan" to Double.NaN,
      "inf" to Double.POSITIVE_INFINITY,
      "-inf" to Double.NEGATIVE_INFINITY,
    )
    for ((raw, numeric) in table) {
      assertSamePeakVerdict(raw, numeric)
    }
  }

  @Test
  fun `parseGainDb and acceptGainDb agree on every value in the gain acceptance table`() {
    val table = listOf(
      "-20.00 dB" to -20.0,
      "-6.54" to -6.54,
      "+3.21 dB" to 3.21,
      "0.00 dB" to 0.0,
      "100.0" to 100.0,
      "-100.0" to -100.0,
      "nan dB" to Double.NaN,
      "inf" to Double.POSITIVE_INFINITY,
      "-inf" to Double.NEGATIVE_INFINITY,
    )
    for ((raw, numeric) in table) {
      assertSameGainVerdict(raw, numeric)
    }
  }

  @Test
  fun `comma decimals reject on the string path while the numeric path accepts the value`() {
    // The period-decimal rule is a property of RG2 text, not of the
    // number — so it lives in the string parser and the numeric
    // acceptor takes the same value happily.
    assertNull(ReplayGainExtractor.parsePeak("0,5"))
    assertAcceptedPeak(0.5, 0.5f)
    assertNull(ReplayGainExtractor.parseGainDb("-6,54 dB"))
    assertAcceptedGain(-6.54, -6.54f)
  }

  // endregion

  // region canonicalKeyForName mapping

  @Test
  fun `canonicalKeyForName matches the four bare tag names`() {
    val names = listOf(
      ReplayGainExtractor.TRACK_GAIN_KEY,
      ReplayGainExtractor.TRACK_PEAK_KEY,
      ReplayGainExtractor.ALBUM_GAIN_KEY,
      ReplayGainExtractor.ALBUM_PEAK_KEY,
    )
    for (name in names) {
      assertEquals(name, ReplayGainExtractor.canonicalKeyForName(name))
    }
  }

  @Test
  fun `canonicalKeyForName is case-insensitive`() {
    assertEquals(
      ReplayGainExtractor.TRACK_GAIN_KEY,
      ReplayGainExtractor.canonicalKeyForName("replaygain_track_gain"),
    )
    assertEquals(
      ReplayGainExtractor.ALBUM_PEAK_KEY,
      ReplayGainExtractor.canonicalKeyForName("ReplayGain_Album_Peak"),
    )
  }

  @Test
  fun `canonicalKeyForName strips iTunes namespace prefix`() {
    assertEquals(
      ReplayGainExtractor.TRACK_GAIN_KEY,
      ReplayGainExtractor.canonicalKeyForName("com.apple.iTunes:replaygain_track_gain"),
    )
  }

  @Test
  fun `canonicalKeyForName returns null for non-ReplayGain tags`() {
    assertNull(ReplayGainExtractor.canonicalKeyForName("TITLE"))
    assertNull(ReplayGainExtractor.canonicalKeyForName("com.apple.iTunes:something_else"))
  }

  // endregion

  // region isOpusContainer

  @Test
  fun `isOpusContainer matches audio_opus and audio_ogg case-insensitively`() {
    assertTrue(ReplayGainExtractor.isOpusContainer(MimeTypes.AUDIO_OPUS))
    assertTrue(ReplayGainExtractor.isOpusContainer("audio/opus"))
    assertTrue(ReplayGainExtractor.isOpusContainer("AUDIO/OPUS"))
    assertTrue(ReplayGainExtractor.isOpusContainer("audio/ogg"))
  }

  @Test
  fun `isOpusContainer returns false for non-Opus mime types`() {
    assertFalse(ReplayGainExtractor.isOpusContainer(null))
    assertFalse(ReplayGainExtractor.isOpusContainer(MimeTypes.AUDIO_MPEG))
    assertFalse(ReplayGainExtractor.isOpusContainer(MimeTypes.AUDIO_FLAC))
    assertFalse(ReplayGainExtractor.isOpusContainer(MimeTypes.AUDIO_AAC))
  }

  // endregion

  // region extract — Metadata walking across all three entry shapes

  @Test
  fun `extract returns null when metadata is absent`() {
    assertNull(ReplayGainExtractor.extract(null, MimeTypes.AUDIO_MPEG))
  }

  @Test
  fun `extract returns null when no ReplayGain tags are present`() {
    val metadata = Metadata(
      txxx("TIT2", "Track Title"),
    )
    assertNull(ReplayGainExtractor.extract(metadata, MimeTypes.AUDIO_MPEG))
  }

  @Test
  fun `extract reads all four tags from ID3 TXXX frames`() {
    val metadata = Metadata(
      txxx("REPLAYGAIN_TRACK_GAIN", "-12.0 dB"),
      txxx("REPLAYGAIN_TRACK_PEAK", "0.95"),
      txxx("REPLAYGAIN_ALBUM_GAIN", "-8.0 dB"),
      txxx("REPLAYGAIN_ALBUM_PEAK", "0.90"),
    )
    val data = ReplayGainExtractor.extract(metadata, MimeTypes.AUDIO_MPEG)
    assertNotNull(data)
    requireNotNull(data)
    assertEquals(-12.0f, data.trackGainDb!!, 0.001f)
    assertEquals(0.95f, data.trackPeak!!, 0.001f)
    assertEquals(-8.0f, data.albumGainDb!!, 0.001f)
    assertEquals(0.90f, data.albumPeak!!, 0.001f)
  }

  @Test
  fun `extract ignores ID3 frames whose id is not TXXX`() {
    // Only TXXX user-text frames carry ReplayGain on MP3. A T??? frame
    // (e.g. TIT2) with description matching one of the RG keys must
    // not be picked up.
    val metadata = Metadata(
      TextInformationFrame(
        /* id = */ "TIT2",
        /* description = */ "REPLAYGAIN_TRACK_GAIN",
        /* values = */ ImmutableList.of("-6.54 dB"),
      ),
    )
    assertNull(ReplayGainExtractor.extract(metadata, MimeTypes.AUDIO_MPEG))
  }

  @Test
  fun `extract is case-insensitive on the TXXX description`() {
    val metadata = Metadata(
      txxx("replaygain_track_gain", "-6.54 dB"),
    )
    val data = ReplayGainExtractor.extract(metadata, MimeTypes.AUDIO_MPEG)
    assertNotNull(data)
    assertEquals(-6.54f, data!!.trackGainDb!!, 0.001f)
  }

  @Test
  fun `extract reads tags from VorbisComment entries`() {
    val metadata = Metadata(
      VorbisComment("REPLAYGAIN_TRACK_GAIN", "-6.54 dB"),
      VorbisComment("REPLAYGAIN_TRACK_PEAK", "0.987654"),
    )
    val data = ReplayGainExtractor.extract(metadata, MimeTypes.AUDIO_FLAC)
    assertNotNull(data)
    requireNotNull(data)
    assertEquals(-6.54f, data.trackGainDb!!, 0.001f)
    assertEquals(0.987654f, data.trackPeak!!, 0.0001f)
    assertNull(data.albumGainDb)
    assertNull(data.albumPeak)
  }

  @Test
  fun `extract reads MP4 iTunes atoms via MdtaMetadataEntry`() {
    val metadata = Metadata(
      mdta("com.apple.iTunes:replaygain_track_gain", "-6.54 dB"),
      mdta("com.apple.iTunes:replaygain_album_gain", "-3.21 dB"),
    )
    val data = ReplayGainExtractor.extract(metadata, "audio/mp4")
    assertNotNull(data)
    requireNotNull(data)
    assertEquals(-6.54f, data.trackGainDb!!, 0.001f)
    assertEquals(-3.21f, data.albumGainDb!!, 0.001f)
  }

  @Test
  fun `extract returns null when the only present tag fails to parse`() {
    val cases = listOf("garbage", "-6,54 dB", "NaN dB", "inf dB")
    for (raw in cases) {
      val metadata = Metadata(
        txxx("REPLAYGAIN_TRACK_GAIN", raw),
      )
      assertNull(
        "expected extract to reject value '$raw'",
        ReplayGainExtractor.extract(metadata, MimeTypes.AUDIO_MPEG),
      )
    }
  }

  @Test
  fun `extract still returns data when one of several tags fails to parse`() {
    // Mixed-validity bag — the parser keeps the good values and
    // silently discards the bad one. Mirrors how real tagger output
    // sometimes carries one corrupted tag alongside three clean
    // siblings.
    val metadata = Metadata(
      txxx("REPLAYGAIN_TRACK_GAIN", "-6.54 dB"),
      txxx("REPLAYGAIN_TRACK_PEAK", "garbage"),
    )
    val data = ReplayGainExtractor.extract(metadata, MimeTypes.AUDIO_MPEG)
    assertNotNull(data)
    requireNotNull(data)
    assertEquals(-6.54f, data.trackGainDb!!, 0.001f)
    assertNull(data.trackPeak)
  }

  @Test
  fun `extract handles partial tag presence`() {
    val metadata = Metadata(
      txxx("REPLAYGAIN_TRACK_GAIN", "-6.0 dB"),
    )
    val data = ReplayGainExtractor.extract(metadata, MimeTypes.AUDIO_MPEG)
    assertNotNull(data)
    requireNotNull(data)
    assertEquals(-6.0f, data.trackGainDb!!, 0.001f)
    assertNull(data.trackPeak)
    assertNull(data.albumGainDb)
    assertNull(data.albumPeak)
  }

  // endregion

  // region Opus container guard

  @Test
  fun `extract returns null for Opus regardless of present tags`() {
    val metadata = Metadata(
      txxx("REPLAYGAIN_TRACK_GAIN", "-6.54 dB"),
    )
    assertNull(ReplayGainExtractor.extract(metadata, MimeTypes.AUDIO_OPUS))
    assertNull(ReplayGainExtractor.extract(metadata, "audio/ogg"))
  }

  // endregion

  // region selectGain matrix on ReplayGainData

  @Test
  fun `selectGain off returns null pair`() {
    val data = ReplayGainData(
      trackGainDb = -12f, trackPeak = 0.95f,
      albumGainDb = -8f, albumPeak = 0.90f,
    )
    val (g, p) = data.selectGain(ReplayGainMode.OFF)
    assertNull(g)
    assertNull(p)
  }

  @Test
  fun `selectGain track returns the track pair`() {
    val data = ReplayGainData(
      trackGainDb = -12f, trackPeak = 0.95f,
      albumGainDb = -8f, albumPeak = 0.90f,
    )
    val (g, p) = data.selectGain(ReplayGainMode.TRACK)
    assertEquals(-12f, g!!, 0.001f)
    assertEquals(0.95f, p!!, 0.001f)
  }

  @Test
  fun `selectGain album returns the album pair`() {
    val data = ReplayGainData(
      trackGainDb = -12f, trackPeak = 0.95f,
      albumGainDb = -8f, albumPeak = 0.90f,
    )
    val (g, p) = data.selectGain(ReplayGainMode.ALBUM)
    assertEquals(-8f, g!!, 0.001f)
    assertEquals(0.90f, p!!, 0.001f)
  }

  @Test
  fun `selectGain album does not fall back to track when album tag is absent`() {
    val data = ReplayGainData(
      trackGainDb = -6f, trackPeak = 0.92f,
      albumGainDb = null, albumPeak = null,
    )
    val (g, p) = data.selectGain(ReplayGainMode.ALBUM)
    assertNull(g)
    assertNull(p)

    // Track mode on the same data still returns the track values.
    val (gT, pT) = data.selectGain(ReplayGainMode.TRACK)
    assertEquals(-6f, gT!!, 0.001f)
    assertEquals(0.92f, pT!!, 0.001f)
  }

  @Test
  fun `selectGain track returns null when only album tag is present`() {
    val data = ReplayGainData(
      trackGainDb = null, trackPeak = null,
      albumGainDb = -9f, albumPeak = 0.88f,
    )
    val (gT, pT) = data.selectGain(ReplayGainMode.TRACK)
    assertNull(gT)
    assertNull(pT)
    val (gA, pA) = data.selectGain(ReplayGainMode.ALBUM)
    assertEquals(-9f, gA!!, 0.001f)
    assertEquals(0.88f, pA!!, 0.001f)
  }

  @Test
  fun `selectGain returns null pair across all modes when all fields are null`() {
    val data = ReplayGainData(
      trackGainDb = null, trackPeak = null,
      albumGainDb = null, albumPeak = null,
    )
    for (mode in ReplayGainMode.values()) {
      val (g, p) = data.selectGain(mode)
      assertNull("mode $mode should return null gain", g)
      assertNull("mode $mode should return null peak", p)
    }
  }

  // endregion

  // region Helpers

  private fun assertGain(raw: String, expected: Float, accuracy: Float = 0.001f) {
    val actual = ReplayGainExtractor.parseGainDb(raw)
    assertNotNull("expected parseGainDb('$raw') to succeed", actual)
    assertEquals(expected, actual!!, accuracy)
  }

  private fun assertPeak(raw: String, expected: Float, accuracy: Float = 0.0001f) {
    val actual = ReplayGainExtractor.parsePeak(raw)
    assertNotNull("expected parsePeak('$raw') to succeed", actual)
    assertEquals(expected, actual!!, accuracy)
  }

  private fun assertAcceptedPeak(value: Double, expected: Float, accuracy: Float = 0.0001f) {
    val actual = ReplayGainExtractor.acceptPeak(value)
    assertNotNull("expected acceptPeak($value) to succeed", actual)
    assertEquals(expected, actual!!, accuracy)
  }

  private fun assertAcceptedGain(value: Double, expected: Float, accuracy: Float = 0.001f) {
    val actual = ReplayGainExtractor.acceptGainDb(value)
    assertNotNull("expected acceptGainDb($value) to succeed", actual)
    assertEquals(expected, actual!!, accuracy)
  }

  private fun assertSamePeakVerdict(raw: String, numeric: Double) {
    val fromString = ReplayGainExtractor.parsePeak(raw)
    val fromNumeric = ReplayGainExtractor.acceptPeak(numeric)
    assertEquals(
      "parsePeak('$raw') and acceptPeak($numeric) must agree on acceptance",
      fromString == null,
      fromNumeric == null,
    )
    if (fromString != null && fromNumeric != null) {
      assertEquals(
        "parsePeak('$raw') and acceptPeak($numeric) must agree on value",
        fromString,
        fromNumeric,
        0.0001f,
      )
    }
  }

  private fun assertSameGainVerdict(raw: String, numeric: Double) {
    val fromString = ReplayGainExtractor.parseGainDb(raw)
    val fromNumeric = ReplayGainExtractor.acceptGainDb(numeric)
    assertEquals(
      "parseGainDb('$raw') and acceptGainDb($numeric) must agree on acceptance",
      fromString == null,
      fromNumeric == null,
    )
    if (fromString != null && fromNumeric != null) {
      assertEquals(
        "parseGainDb('$raw') and acceptGainDb($numeric) must agree on value",
        fromString,
        fromNumeric,
        0.001f,
      )
    }
  }

  private fun txxx(description: String, value: String): TextInformationFrame =
    TextInformationFrame(
      /* id = */ "TXXX",
      /* description = */ description,
      /* values = */ ImmutableList.of(value),
    )

  private fun mdta(key: String, value: String): MdtaMetadataEntry =
    MdtaMetadataEntry(
      /* key = */ key,
      /* value = */ value.toByteArray(Charsets.UTF_8),
      /* localeIndicator = */ MdtaMetadataEntry.DEFAULT_LOCALE_INDICATOR,
      /* typeIndicator = */ MdtaMetadataEntry.TYPE_INDICATOR_STRING,
    )

  // endregion
}
