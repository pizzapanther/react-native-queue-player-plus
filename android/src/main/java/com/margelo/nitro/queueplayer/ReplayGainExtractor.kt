package com.margelo.nitro.queueplayer

import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.MdtaMetadataEntry
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment

/**
 * Pure parser that walks a Media3 [Metadata] and produces a
 * [ReplayGainData] when any of the four ReplayGain tags is present.
 * Returns `null` for Opus containers — `OpusHead.output_gain` is
 * decoder-applied; tag-applied gain on top is double-correction.
 *
 * Tag-format coverage:
 * - **MP3 ID3v2 TXXX**: [TextInformationFrame] with `id == "TXXX"`,
 *   description in [TextInformationFrame.description], value in
 *   [TextInformationFrame.values] (first entry).
 * - **FLAC / OGG-Vorbis**: [VorbisComment] with the bare tag name
 *   in [VorbisComment.key].
 * - **MP4 / M4A iTunes-style atoms**: [MdtaMetadataEntry] with the
 *   colon-prefixed atom key (e.g. `com.apple.iTunes:replaygain_track_gain`)
 *   and a UTF-8 string payload in [MdtaMetadataEntry.value].
 *
 * Parser tolerance per NOTES.md section 25 — `" dB"` suffix optional, `+/-`
 * prefix optional, U+2212 minus sign normalised, period decimal,
 * whitespace tolerant, NaN/Inf rejected. Tag-name match is
 * case-insensitive.
 */
@UnstableApi
internal object ReplayGainExtractor {

  // Tag descriptions matched (case-insensitive).
  internal const val TRACK_GAIN_KEY = "REPLAYGAIN_TRACK_GAIN"
  internal const val TRACK_PEAK_KEY = "REPLAYGAIN_TRACK_PEAK"
  internal const val ALBUM_GAIN_KEY = "REPLAYGAIN_ALBUM_GAIN"
  internal const val ALBUM_PEAK_KEY = "REPLAYGAIN_ALBUM_PEAK"

  /**
   * Walk every entry in [metadata] and parse any ReplayGain tags.
   * Returns `null` when [mimeType] identifies an Opus container or
   * when no usable ReplayGain tag was found.
   *
   * [mimeType] is the active [androidx.media3.common.Format.sampleMimeType]
   * (or container MIME when sample MIME is unavailable). The Opus
   * guard matches `audio/opus` and `audio/ogg` case-insensitively.
   */
  fun extract(metadata: Metadata?, mimeType: String?): ReplayGainData? {
    if (metadata == null) return null
    if (isOpusContainer(mimeType)) return null

    var trackGainDb: Float? = null
    var trackPeak: Float? = null
    var albumGainDb: Float? = null
    var albumPeak: Float? = null

    for (i in 0 until metadata.length()) {
      val entry = metadata.get(i)
      val (canonical, raw) = canonicalKeyAndValue(entry) ?: continue
      when (canonical) {
        TRACK_GAIN_KEY -> parseGainDb(raw)?.let { trackGainDb = it }
        TRACK_PEAK_KEY -> parsePeak(raw)?.let { trackPeak = it }
        ALBUM_GAIN_KEY -> parseGainDb(raw)?.let { albumGainDb = it }
        ALBUM_PEAK_KEY -> parsePeak(raw)?.let { albumPeak = it }
      }
    }

    if (trackGainDb == null && trackPeak == null
       && albumGainDb == null && albumPeak == null) {
      return null
    }
    return ReplayGainData(
      trackGainDb = trackGainDb,
      trackPeak = trackPeak,
      albumGainDb = albumGainDb,
      albumPeak = albumPeak,
    )
  }

  // region Key + value extraction

  /**
   * Map a Media3 [Metadata.Entry] to one of the four ReplayGain tag
   * names plus its raw string value. Returns `null` when the entry
   * isn't a ReplayGain tag we recognise.
   *
   * Container shapes:
   * - [TextInformationFrame] with `id == "TXXX"`: description is the
   *   user tag name, first `values` entry is the payload.
   * - [VorbisComment]: `key` is the bare tag name, `value` is the
   *   payload.
   * - [MdtaMetadataEntry]: `key` is colon-prefixed
   *   (`com.apple.iTunes:replaygain_track_gain`); the `value` byte
   *   array is UTF-8-decoded.
   */
  internal fun canonicalKeyAndValue(entry: Metadata.Entry): Pair<String, String>? {
    val rawKey: String
    val rawValue: String
    when (entry) {
      is TextInformationFrame -> {
        if (entry.id != "TXXX") return null
        rawKey = entry.description ?: return null
        rawValue = entry.values.firstOrNull() ?: return null
      }
      is VorbisComment -> {
        rawKey = entry.key
        rawValue = entry.value
      }
      is MdtaMetadataEntry -> {
        rawKey = entry.key
        rawValue = String(entry.value, Charsets.UTF_8)
      }
      else -> return null
    }
    val canonical = canonicalKeyForName(rawKey) ?: return null
    return canonical to rawValue
  }

  /**
   * Strip a leading `namespace:` prefix (e.g. `com.apple.iTunes:`)
   * and uppercase-match the suffix against the four known tag
   * names. Returns the canonical constant or `null` when no match.
   */
  internal fun canonicalKeyForName(rawKey: String): String? {
    val upper = rawKey.uppercase()
    val colonIdx = upper.lastIndexOf(':')
    val suffix = if (colonIdx >= 0) upper.substring(colonIdx + 1) else upper
    return when (suffix) {
      TRACK_GAIN_KEY -> TRACK_GAIN_KEY
      TRACK_PEAK_KEY -> TRACK_PEAK_KEY
      ALBUM_GAIN_KEY -> ALBUM_GAIN_KEY
      ALBUM_PEAK_KEY -> ALBUM_PEAK_KEY
      else -> null
    }
  }

  // endregion

  // region Opus container guard

  /**
   * Best-effort container detection. Matches `audio/opus` and
   * `audio/ogg` (the Opus-in-Ogg container). Streaming endpoints
   * that serve Opus over a content-typed URL without a recognised
   * MIME bypass this guard and may double-correct against
   * `OpusHead.output_gain`; documented in NOTES.md section 25.
   */
  internal fun isOpusContainer(mimeType: String?): Boolean {
    if (mimeType == null) return false
    val lower = mimeType.lowercase()
    return lower == MimeTypes.AUDIO_OPUS || lower == "audio/ogg"
  }

  // endregion

  // region Gain + peak parsing

  /**
   * Parse a `REPLAYGAIN_*_GAIN` string (e.g. `"-6.54 dB"`,
   * `"+3.21"`, `"−1.0 dB"` with U+2212 minus) into a Float dB.
   * Returns `null` for unparseable input; the parsed number is then
   * put through [acceptGainDb], so text handling is all this adds
   * over the numeric entry point.
   */
  internal fun parseGainDb(rawInput: String): Float? {
    var trimmed = rawInput.trim()
    if (trimmed.isEmpty()) return null

    // Normalise U+2212 MINUS SIGN → ASCII '-'.
    trimmed = trimmed.replace('−', '-')

    // Strip case-insensitive trailing "dB" + any whitespace separator.
    val lower = trimmed.lowercase()
    if (lower.endsWith("db")) {
      trimmed = trimmed.substring(0, trimmed.length - 2).trim()
    }
    if (trimmed.isEmpty()) return null

    // Collapse extra-internal whitespace per NOTES.md section 25 — some
    // taggers emit `"- 6.54"` (sign-digit) or `"-6 .54"` (digit-
    // decimal). `Float.parseFloat` rejects both; collapsing makes
    // them parse.
    trimmed = trimmed.filterNot { it.isWhitespace() }

    // Reject comma decimals (RG2 spec — period only).
    if (trimmed.contains(',')) return null

    val value = trimmed.toDoubleOrNull() ?: return null
    return acceptGainDb(value)
  }

  /**
   * Shared numeric acceptance for a peak value, whatever entry point
   * it arrived through — a tag string or a consumer-supplied number.
   * Accepts `0.001 ... 2.0` inclusive (`1.0` is digital full scale and
   * a legitimate measurement); rejects anything outside that band and
   * anything non-finite. The band is checked on the `Double`, before the
   * narrowing to `Float`, so a value just past it cannot round in.
   */
  internal fun acceptPeak(value: Double): Float? {
    if (value.isNaN() || value.isInfinite()) return null
    // Band-check the Double: 2.0000001 narrows to 2.0f, so a check
    // applied after the narrowing would let it through.
    if (value < 0.001 || value > 2.0) return null
    return value.toFloat()
  }

  /**
   * Shared numeric acceptance for a gain in dB, whatever entry point
   * it arrived through. There is no plausibility band on gain — only
   * non-finite values are rejected, including a magnitude that
   * narrows to an infinite [Float].
   */
  internal fun acceptGainDb(value: Double): Float? {
    if (value.isNaN() || value.isInfinite()) return null
    val narrowed = value.toFloat()
    if (narrowed.isInfinite()) return null
    return narrowed
  }

  /**
   * Parse a `REPLAYGAIN_*_PEAK` string (linear, period decimal).
   * Returns `null` for unparseable input; the parsed number is then
   * put through [acceptPeak], which holds the `0.001 ... 2.0`
   * acceptance band — a full-scale `1.0` included.
   */
  internal fun parsePeak(rawInput: String): Float? {
    val trimmed = rawInput.trim()
    val value = trimmed.toDoubleOrNull() ?: return null
    return acceptPeak(value)
  }

  // endregion
}
