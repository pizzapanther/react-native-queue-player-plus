package com.margelo.nitro.queueplayer

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pure-mapping coverage for [NowPlayingFormatExtractor]. These
 * tests pin the codec / container tables so the JS-visible enum can't
 * drift without a test failure.
 */
@UnstableApi
class NowPlayingFormatExtractorTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  /**
   * FLAC declares no bitrate, so Media3 reports NO_VALUE for it on local files
   * as well as remote. For a file on disk the average is arithmetic — encoded
   * bytes over playing time — which is the same derivation iOS uses.
   */
  @Test
  fun averageBitrateIsBytesOverPlayingTime() {
    val file = tempFolder.newFile("track.flac")
    file.writeBytes(ByteArray(1_000_000))

    // 1,000,000 bytes over 40 s = 8,000,000 bits / 40 = 200 kbps.
    assertEquals(
      200_000.0,
      NowPlayingFormatExtractor.averageBitrateOf(file.absolutePath, 40.0)!!,
      0.001
    )
  }

  /**
   * Every input that would make the answer invented rather than measured
   * yields null: the player has not resolved a duration yet, the duration is
   * nonsense, the path is absent, or the file has no bytes.
   */
  @Test
  fun averageBitrateIsNullWhenItCannotBeMeasured() {
    val file = tempFolder.newFile("track.flac")
    file.writeBytes(ByteArray(1_000))

    assertNull(NowPlayingFormatExtractor.averageBitrateOf(file.absolutePath, null))
    assertNull(NowPlayingFormatExtractor.averageBitrateOf(file.absolutePath, 0.0))
    assertNull(NowPlayingFormatExtractor.averageBitrateOf(file.absolutePath, -5.0))
    assertNull(NowPlayingFormatExtractor.averageBitrateOf(null, 40.0))
    assertNull(
      NowPlayingFormatExtractor.averageBitrateOf(
        tempFolder.newFile("empty.flac").absolutePath, 40.0))
    assertNull(
      NowPlayingFormatExtractor.averageBitrateOf("/nonexistent/nope.flac", 40.0))
  }

  @Test
  fun mapCodecCoversAACAliases() {
    assertEquals(AudioCodec.AAC, NowPlayingFormatExtractor.mapCodec(MimeTypes.AUDIO_AAC))
    assertEquals(AudioCodec.AAC, NowPlayingFormatExtractor.mapCodec("audio/mp4a-latm"))
    assertEquals(AudioCodec.AAC, NowPlayingFormatExtractor.mapCodec("audio/aac"))
  }

  @Test
  fun mapCodecCoversMP3Layers() {
    assertEquals(AudioCodec.MP3, NowPlayingFormatExtractor.mapCodec(MimeTypes.AUDIO_MPEG))
    assertEquals(AudioCodec.MP3, NowPlayingFormatExtractor.mapCodec(MimeTypes.AUDIO_MPEG_L1))
    assertEquals(AudioCodec.MP3, NowPlayingFormatExtractor.mapCodec(MimeTypes.AUDIO_MPEG_L2))
  }

  @Test
  fun mapCodecCoversCommonFormats() {
    assertEquals(AudioCodec.FLAC, NowPlayingFormatExtractor.mapCodec(MimeTypes.AUDIO_FLAC))
    assertEquals(AudioCodec.FLAC, NowPlayingFormatExtractor.mapCodec("audio/x-flac"))
    assertEquals(AudioCodec.ALAC, NowPlayingFormatExtractor.mapCodec(MimeTypes.AUDIO_ALAC))
    assertEquals(AudioCodec.OPUS, NowPlayingFormatExtractor.mapCodec(MimeTypes.AUDIO_OPUS))
    assertEquals(AudioCodec.PCM, NowPlayingFormatExtractor.mapCodec(MimeTypes.AUDIO_RAW))
    assertEquals(AudioCodec.PCM, NowPlayingFormatExtractor.mapCodec("audio/wav"))
  }

  @Test
  fun mapCodecIsCaseInsensitive() {
    assertEquals(AudioCodec.AAC, NowPlayingFormatExtractor.mapCodec("AUDIO/AAC"))
    assertEquals(AudioCodec.MP3, NowPlayingFormatExtractor.mapCodec("Audio/Mpeg"))
  }

  @Test
  fun mapCodecUnknownForNullOrUnmapped() {
    assertEquals(AudioCodec.UNKNOWN, NowPlayingFormatExtractor.mapCodec(null))
    assertEquals(AudioCodec.UNKNOWN, NowPlayingFormatExtractor.mapCodec("video/mp4"))
    assertEquals(AudioCodec.UNKNOWN, NowPlayingFormatExtractor.mapCodec("audio/made-up"))
  }

  @Test
  fun mapContainerFromContainerMime() {
    assertEquals(AudioContainer.HLS, mapWithContainer(MimeTypes.APPLICATION_M3U8))
    assertEquals(AudioContainer.MP3, mapWithContainer(MimeTypes.AUDIO_MPEG))
    assertEquals(AudioContainer.MP4, mapWithContainer("audio/mp4"))
    assertEquals(AudioContainer.MP4, mapWithContainer("video/mp4"))
    assertEquals(AudioContainer.FLAC, mapWithContainer(MimeTypes.AUDIO_FLAC))
    assertEquals(AudioContainer.OGG, mapWithContainer("application/ogg"))
    assertEquals(AudioContainer.WAV, mapWithContainer("audio/wav"))
    // `MimeTypes.AUDIO_AAC` is `audio/mp4a-latm` (AAC inside MP4 LATM),
    // not the ADTS container. Raw ADTS comes through as `audio/aac`.
    assertEquals(AudioContainer.AAC_ADTS, mapWithContainer("audio/aac"))
  }

  @Test
  fun mapContainerFallsBackOnUrlExtensionWhenContainerMissing() {
    assertEquals(AudioContainer.AAC_ADTS, mapWithContainer(null, urlExtension = "aac"))
    assertEquals(AudioContainer.MP4, mapWithContainer(null, urlExtension = "m4a"))
    assertEquals(AudioContainer.MP4, mapWithContainer(null, urlExtension = "mp4"))
    assertEquals(AudioContainer.FLAC, mapWithContainer(null, urlExtension = "flac"))
    assertEquals(AudioContainer.OGG, mapWithContainer(null, urlExtension = "ogg"))
    assertEquals(AudioContainer.WAV, mapWithContainer(null, urlExtension = "wav"))
    assertEquals(AudioContainer.HLS, mapWithContainer(null, urlExtension = "m3u8"))
  }

  @Test
  fun mapContainerFallsBackOnCodecWhenContainerAndUrlMissing() {
    assertEquals(AudioContainer.MP4, mapWithContainer(null, sample = MimeTypes.AUDIO_AAC))
    assertEquals(AudioContainer.MP3, mapWithContainer(null, sample = MimeTypes.AUDIO_MPEG))
    assertEquals(AudioContainer.FLAC, mapWithContainer(null, sample = MimeTypes.AUDIO_FLAC))
    assertEquals(AudioContainer.OGG, mapWithContainer(null, sample = MimeTypes.AUDIO_OPUS))
    assertEquals(AudioContainer.WAV, mapWithContainer(null, sample = MimeTypes.AUDIO_RAW))
  }

  @Test
  fun mapContainerUnknownWhenNoSignal() {
    assertEquals(AudioContainer.UNKNOWN, mapWithContainer(null, sample = null))
    assertEquals(AudioContainer.UNKNOWN, mapWithContainer(null, sample = "audio/made-up"))
  }

  private fun mapWithContainer(
    container: String?,
    sample: String? = MimeTypes.AUDIO_AAC,
    urlExtension: String? = null
  ): AudioContainer = NowPlayingFormatExtractor.mapContainer(
    Format.Builder()
      .setContainerMimeType(container)
      .setSampleMimeType(sample)
      .build(),
    urlExtension
  )
}
