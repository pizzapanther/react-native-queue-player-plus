package com.margelo.nitro.queueplayer

import androidx.annotation.MainThread
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import com.margelo.nitro.core.NullType

/**
 * Resolves the active item's runtime audio format from a Media3 [Player].
 * Pure object — no shared state. Caller owns invocation cadence + emit
 * dedup.
 *
 * Codec maps from `Format.sampleMimeType`; container maps from
 * `Format.containerMimeType`, with a URL-extension fallback for path-only
 * MediaItems whose container MIME isn't populated by the demuxer. Each
 * field falls back to its `null` sentinel when Media3 hasn't determined
 * it (`Format.NO_VALUE`).
 *
 * The optional `engine` parameter surfaces the four ReplayGain data
 * fields + `replayGainActive` from the engine's per-leg cached
 * [ReplayGainData]. When `engine` is null (off-engine call paths,
 * tests that don't construct an engine) all four data fields surface
 * null and `replayGainActive` is false.
 */
@UnstableApi
internal object NowPlayingFormatExtractor {

  /**
   * Average encoded bitrate of a `file://` source, from its size and duration.
   *
   * The figure is an average over the whole file, which is what "bitrate"
   * means for a variable-rate codec anyway, and it includes the container's
   * headers and any embedded artwork — a few kbps on a typical track.
   *
   * Returns null for any non-local source, for a file that cannot be measured,
   * and for a duration the player has not resolved yet: a bitrate derived from
   * an unknown duration would be arbitrary, and reporting nothing is the
   * honest answer until the player knows.
   */
  private fun deriveLocalBitrate(player: Player, durationSec: Double?): Double? {
    val uri = player.currentMediaItem?.localConfiguration?.uri ?: return null
    if (!uri.scheme.equals("file", ignoreCase = true)) return null
    return averageBitrateOf(uri.path, durationSec)
  }

  /**
   * Average bits per second of the file at [path] over [durationSec].
   *
   * Split from its caller so the arithmetic is reachable without a player.
   * Null for an unusable duration, an unreadable path, or an empty file —
   * every case where the answer would be invented rather than measured.
   */
  internal fun averageBitrateOf(path: String?, durationSec: Double?): Double? {
    if (durationSec == null || durationSec <= 0.0) return null
    if (path == null) return null
    // One `stat` of a local file, taken on the looper with the rest of the
    // player snapshot; the answer is not worth an extra hop.
    val bytes = runCatching { java.io.File(path).length() }.getOrNull() ?: return null
    if (bytes <= 0L) return null
    val bitsPerSecond = bytes * 8.0 / durationSec
    return if (bitsPerSecond.isFinite() && bitsPerSecond > 0.0) bitsPerSecond else null
  }

  /**
   * Extract the format snapshot from `player`'s currently selected
   * audio track. Returns `null` only when no audio track exists in the
   * timeline; otherwise every field is best-effort with per-field null
   * fallback.
   *
   * `engine` is the currently-active [PlaybackEngine]. When non-null
   * the extractor reads ReplayGain data + active flag from the
   * engine's per-leg cache (see [GaplessEngine.currentReplayGainData]
   * / [CrossfadeEngine.currentReplayGainData]).
   */
  @MainThread
  fun extract(player: Player, engine: PlaybackEngine? = null): NowPlayingFormat? {
    val (group, trackIndex) = pickAudioTrack(player.currentTracks) ?: return null
    val format = group.getTrackFormat(trackIndex)
    val codec = mapCodec(format.sampleMimeType)
    val urlExtension = player.currentMediaItem
      ?.localConfiguration?.uri?.toString()
      ?.substringAfterLast('.', missingDelimiterValue = "")
      ?.lowercase()
      ?.takeIf { it.isNotEmpty() }
    val container = mapContainer(format, urlExtension)
    val mimeType = format.sampleMimeType
    val sampleRate = format.sampleRate.takeIf { it != Format.NO_VALUE }?.toDouble()
    val channelCount = format.channelCount.takeIf { it != Format.NO_VALUE }?.toDouble()
    val reportedBitrate = format.bitrate.takeIf { it != Format.NO_VALUE }?.toDouble()
    val peakBitrate = format.peakBitrate.takeIf { it != Format.NO_VALUE }?.toDouble()
    val pcmEncoding = format.pcmEncoding.takeIf { it != Format.NO_VALUE }?.toDouble()
    val codecsString = format.codecs
    val durationMs = player.duration
    val durationSec = if (durationMs == C.TIME_UNSET || durationMs <= 0) null
                      else durationMs / 1000.0

    // Media3 leaves both bitrate fields unset for some codecs — FLAC reports
    // NO_VALUE for average and peak alike, on local files as well as remote —
    // so a consumer asking what it is playing gets nothing back. Where the
    // source is on disk the average is arithmetic: encoded bytes over playing
    // time. Not gated on codec; it fills in whatever the extractor did not
    // report, and does nothing when Media3 already answered.
    //
    // A local stat only. A remote source would need its Content-Length, which
    // costs a request the library deliberately does not make.
    val bitrate = reportedBitrate ?: deriveLocalBitrate(player, durationSec)

    val rgData = engine?.currentReplayGainData()
    val rgActive = engine?.currentReplayGainActive() ?: false

    return NowPlayingFormat(
      codec = codec,
      container = container,
      mimeType = stringOrNullVariant(mimeType),
      sampleRate = doubleOrNullVariant(sampleRate),
      channelCount = doubleOrNullVariant(channelCount),
      bitrate = doubleOrNullVariant(bitrate),
      durationSec = doubleOrNullVariant(durationSec),
      replayGainTrackDb = doubleOrNullVariant(rgData?.trackGainDb?.toDouble()),
      replayGainTrackPeak = doubleOrNullVariant(rgData?.trackPeak?.toDouble()),
      replayGainAlbumDb = doubleOrNullVariant(rgData?.albumGainDb?.toDouble()),
      replayGainAlbumPeak = doubleOrNullVariant(rgData?.albumPeak?.toDouble()),
      replayGainActive = rgActive,
      ios = null,
      android = NowPlayingFormatAndroidExtras(
        peakBitrate = doubleOrNullVariant(peakBitrate),
        codecs = stringOrNullVariant(codecsString),
        pcmEncoding = doubleOrNullVariant(pcmEncoding)
      )
    )
  }

  private fun pickAudioTrack(tracks: Tracks): Pair<Tracks.Group, Int>? {
    var firstAudioMatch: Pair<Tracks.Group, Int>? = null
    for (group in tracks.groups) {
      if (group.type != C.TRACK_TYPE_AUDIO) continue
      val mediaGroup = group.mediaTrackGroup
      for (i in 0 until mediaGroup.length) {
        if (firstAudioMatch == null) firstAudioMatch = group to i
        if (group.isTrackSelected(i)) return group to i
      }
    }
    return firstAudioMatch
  }

  internal fun mapCodec(mime: String?): AudioCodec {
    if (mime == null) return AudioCodec.UNKNOWN
    // Compare against lowercase string literals so the matcher tolerates
    // case variation in upstream MimeTypes constants (Media3 names some
    // constants like `audio/mpeg-L1` with mixed case).
    return when (mime.lowercase()) {
      "audio/aac", "audio/mp4a-latm" -> AudioCodec.AAC
      "audio/mpeg", "audio/mpeg-l1", "audio/mpeg-l2" -> AudioCodec.MP3
      "audio/flac", "audio/x-flac" -> AudioCodec.FLAC
      "audio/alac" -> AudioCodec.ALAC
      "audio/opus" -> AudioCodec.OPUS
      "audio/raw", "audio/wav", "audio/wave", "audio/x-wav" -> AudioCodec.PCM
      else -> AudioCodec.UNKNOWN
    }
  }

  internal fun mapContainer(
    format: Format,
    urlExtension: String? = null
  ): AudioContainer {
    val container = format.containerMimeType?.lowercase()
    when (container) {
      "application/x-mpegurl", "application/vnd.apple.mpegurl" -> return AudioContainer.HLS
      "audio/mpeg" -> return AudioContainer.MP3
      "audio/mp4", "video/mp4" -> return AudioContainer.MP4
      "audio/flac", "audio/x-flac" -> return AudioContainer.FLAC
      "audio/ogg", "application/ogg" -> return AudioContainer.OGG
      "audio/wav", "audio/wave", "audio/x-wav" -> return AudioContainer.WAV
      "audio/aac" -> return AudioContainer.AAC_ADTS
      else -> {}
    }
    // Container MIME may be null for path-only MediaItems (the demuxer
    // sets sampleMimeType reliably but not containerMimeType). Prefer
    // the URL extension when available — distinguishes AAC-in-MP4
    // (`.m4a`) from AAC-in-ADTS (`.aac`) where the codec alone can't.
    when (urlExtension) {
      "mp3" -> return AudioContainer.MP3
      "m4a", "m4b", "m4r", "mp4" -> return AudioContainer.MP4
      "flac" -> return AudioContainer.FLAC
      "ogg", "opus" -> return AudioContainer.OGG
      "wav", "wave" -> return AudioContainer.WAV
      "aac" -> return AudioContainer.AAC_ADTS
      "m3u8" -> return AudioContainer.HLS
      else -> {}
    }
    // Final fallback: codec → most-common container.
    return when (mapCodec(format.sampleMimeType)) {
      AudioCodec.AAC -> AudioContainer.MP4
      AudioCodec.MP3 -> AudioContainer.MP3
      AudioCodec.FLAC -> AudioContainer.FLAC
      AudioCodec.ALAC -> AudioContainer.MP4
      AudioCodec.OPUS -> AudioContainer.OGG
      AudioCodec.PCM -> AudioContainer.WAV
      AudioCodec.UNKNOWN -> AudioContainer.UNKNOWN
    }
  }

  private fun stringOrNullVariant(s: String?): Variant_NullType_String =
    if (s == null) Variant_NullType_String.create(NullType.NULL)
    else Variant_NullType_String.create(s)

  private fun doubleOrNullVariant(d: Double?): Variant_NullType_Double =
    if (d == null) Variant_NullType_Double.create(NullType.NULL)
    else Variant_NullType_Double.create(d)
}
