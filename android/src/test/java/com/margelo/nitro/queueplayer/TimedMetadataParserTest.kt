package com.margelo.nitro.queueplayer

import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.extractor.metadata.id3.ApicFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.id3.UrlLinkFrame
import com.google.common.collect.ImmutableList
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TimedMetadataParserTest {
  private fun text(id: String, value: String, description: String? = null) =
    TextInformationFrame(id, description, ImmutableList.of(value))

  @Test fun `ID3 sample supplies all four fields`() {
    val parsed = TimedMetadataParser.parse(Metadata(
      text("TIT2", "Song"), text("TPE1", "Artist"), text("TALB", "Album"),
      UrlLinkFrame("WXXX", "artworkUrl", "https://example.com/art.jpg"),
    ))
    assertEquals(TimedMetadata("Song", "Artist", "Album", "https://example.com/art.jpg"), parsed)
  }

  @Test fun `partial samples do not retain fields from previous samples`() {
    TimedMetadataParser.parse(Metadata(text("TIT2", "Old song")))
    assertEquals(TimedMetadata(null, null, null, "https://example.com/new.jpg"),
      TimedMetadataParser.parse(Metadata(text("TXXX", "https://example.com/new.jpg", "COVER_ART_URL"))))
    assertEquals(TimedMetadata("", null, null, null),
      TimedMetadataParser.parse(Metadata(text("TIT2", ""))))
  }

  @Test fun `unrelated samples do not emit empty events`() {
    assertNull(TimedMetadataParser.parse(Metadata(text("TXXX", "-3 dB", "REPLAYGAIN_TRACK_GAIN"))))
    assertNull(TimedMetadataParser.parse(Metadata()))
  }

  @Test fun `ICY splits only the first separator and does not use StreamUrl as artwork`() {
    assertEquals(TimedMetadata("Song - Live", "Artist", null, null),
      TimedMetadataParser.parse(Metadata(IcyInfo(byteArrayOf(), "Artist - Song - Live", "https://station.com"))))
    assertEquals(TimedMetadata("Station ID", null, null, null),
      TimedMetadataParser.parse(Metadata(IcyInfo(byteArrayOf(), "Station ID", null))))
    assertNull(TimedMetadataParser.parse(Metadata(IcyInfo(byteArrayOf(), null, "https://station.com"))))
  }

  @Test fun `explicit artist and title take precedence over ICY fallback`() {
    assertEquals(TimedMetadata("Song", "Artist", null, null), TimedMetadataParser.parse(Metadata(
      text("TIT2", "Song"), text("TPE1", "Artist"), IcyInfo(byteArrayOf(), "Station - Show", null),
    )))
  }

  @Test fun `linked and embedded artwork become URLs`() {
    val url = "https://example.com/cover.png"
    assertEquals(url, TimedMetadataParser.parse(Metadata(
      ApicFrame("-->", "", 3, url.toByteArray()),
    ))?.artworkUrl)
    assertEquals("data:image/png;base64,AQID", TimedMetadataParser.parse(Metadata(
      ApicFrame("image/png", "", 3, byteArrayOf(1, 2, 3)),
    ))?.artworkUrl)
  }
}
