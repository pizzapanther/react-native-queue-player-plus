package com.margelo.nitro.queueplayer

import android.util.Base64
import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.extractor.metadata.id3.ApicFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.id3.UrlLinkFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import java.util.Locale

/** Parses an individual playback-timed sample, never static Format.metadata. */
@UnstableApi
internal object TimedMetadataParser {
  fun parse(metadata: Metadata): TimedMetadata? {
    var title: String? = null
    var artist: String? = null
    var album: String? = null
    var artwork: String? = null
    var streamTitle: String? = null

    fun text(key: String?, value: String?) {
      if (value == null) return
      when (key?.lowercase(Locale.ROOT)?.replace("_", "")) {
        "title", "tit2", "tt2" -> title = value
        "artist", "tpe1", "tp1" -> artist = value
        "album", "albumname", "albumtitle", "talb", "tal" -> album = value
        "artworkurl", "coverarturl" -> artwork = value
      }
    }

    for (i in 0 until metadata.length()) {
      when (val entry = metadata[i]) {
        is TextInformationFrame -> text(
          if (entry.id == "TXXX" || entry.id == "TXX") entry.description else entry.id,
          entry.values.firstOrNull(),
        )
        is UrlLinkFrame -> {
          if (entry.id == "WXXX" || entry.id == "WXX") text(entry.description, entry.url)
        }
        is VorbisComment -> text(entry.key, entry.value)
        is IcyInfo -> streamTitle = entry.title // StreamUrl is a station/page URL, not cover art.
        is ApicFrame -> {
          if (entry.mimeType == "-->") {
            artwork = entry.pictureData.toString(Charsets.ISO_8859_1).trimEnd('\u0000')
          } else if (entry.pictureData.isNotEmpty() &&
            (entry.mimeType == "image/jpeg" || entry.mimeType == "image/png")) {
            artwork = "data:${entry.mimeType};base64," +
              Base64.encodeToString(entry.pictureData, Base64.NO_WRAP)
          }
        }
      }
    }
    streamTitle?.let {
      val separator = it.indexOf(" - ")
      if (separator >= 0) {
        artist = artist ?: it.substring(0, separator).trim()
        title = title ?: it.substring(separator + 3).trim()
      } else {
        title = title ?: it
      }
    }
    if (title == null && artist == null && album == null && artwork == null) return null
    return TimedMetadata(title, artist, album, artwork)
  }
}
