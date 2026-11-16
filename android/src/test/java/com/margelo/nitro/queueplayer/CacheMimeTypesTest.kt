package com.margelo.nitro.queueplayer

import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-mapping coverage for [CacheMimeTypes]. Mirrors iOS's
 * `LookaheadCacheTests.testFileNameUsesContentTypeDerivedExtension`
 * + `testFileNameFallsBackToUrlPathExtension` so the lookahead
 * cache's MIME signal stays consistent across platforms.
 */
@UnstableApi
class CacheMimeTypesTest {

  @Test
  fun fromContentTypeRecognisesCommonAudio() {
    assertEquals("audio/mpeg", CacheMimeTypes.fromContentType("audio/mpeg"))
    assertEquals("audio/mp4", CacheMimeTypes.fromContentType("audio/mp4"))
    assertEquals("audio/mp4a-latm", CacheMimeTypes.fromContentType("audio/mp4a-latm"))
    assertEquals("audio/aac", CacheMimeTypes.fromContentType("audio/aac"))
    assertEquals("audio/wav", CacheMimeTypes.fromContentType("audio/wav"))
    assertEquals("audio/flac", CacheMimeTypes.fromContentType("audio/flac"))
    assertEquals("audio/ogg", CacheMimeTypes.fromContentType("audio/ogg"))
    assertEquals("audio/opus", CacheMimeTypes.fromContentType("audio/opus"))
    assertEquals(
      "application/x-mpegurl",
      CacheMimeTypes.fromContentType("application/x-mpegurl")
    )
  }

  @Test
  fun fromContentTypeStripsParameters() {
    assertEquals(
      "audio/flac",
      CacheMimeTypes.fromContentType("audio/flac; charset=utf-8")
    )
    assertEquals(
      "audio/mpeg",
      CacheMimeTypes.fromContentType("Audio/MPEG; boundary=foo")
    )
  }

  @Test
  fun fromContentTypeNullForOpaqueOrEmpty() {
    assertNull(CacheMimeTypes.fromContentType(null))
    assertNull(CacheMimeTypes.fromContentType(""))
    assertNull(CacheMimeTypes.fromContentType("application/octet-stream"))
    assertNull(CacheMimeTypes.fromContentType("text/plain"))
    assertNull(CacheMimeTypes.fromContentType("audio/made-up"))
  }

  @Test
  fun fromUrlMapsKnownExtensions() {
    assertEquals("audio/mpeg", CacheMimeTypes.fromUrl("https://example.com/song.mp3"))
    assertEquals("audio/mp4", CacheMimeTypes.fromUrl("https://example.com/song.m4a"))
    assertEquals("audio/mp4", CacheMimeTypes.fromUrl("https://example.com/audiobook.m4b"))
    assertEquals("audio/aac", CacheMimeTypes.fromUrl("https://example.com/raw.aac"))
    assertEquals("audio/wav", CacheMimeTypes.fromUrl("https://example.com/song.wav"))
    assertEquals("audio/flac", CacheMimeTypes.fromUrl("https://example.com/song.flac"))
    assertEquals("audio/ogg", CacheMimeTypes.fromUrl("https://example.com/song.ogg"))
    assertEquals(
      "application/x-mpegurl",
      CacheMimeTypes.fromUrl("https://example.com/playlist.m3u8")
    )
  }

  @Test
  fun fromUrlIgnoresUnknownOrParameterisedPaths() {
    assertNull(CacheMimeTypes.fromUrl("https://example.com/api/stream?id=1"))
    assertNull(CacheMimeTypes.fromUrl("https://example.com/api/stream"))
    assertNull(CacheMimeTypes.fromUrl("https://example.com/song.xyz"))
  }

  @Test
  fun fromUrlHandlesPercentEncodedPaths() {
    assertEquals(
      "audio/mpeg",
      CacheMimeTypes.fromUrl("https://example.com/foo%20bar.mp3")
    )
    assertEquals(
      "audio/mp4",
      CacheMimeTypes.fromUrl("https://example.com/album%20name/song.m4a")
    )
  }

  @Test
  fun resolvePrefersContentTypeOverUrlExtension() {
    assertEquals(
      "audio/mpeg",
      CacheMimeTypes.resolve("https://example.com/song.ogg", "audio/mpeg"),
      )
  }

  @Test
  fun resolveFallsBackToUrlWhenContentTypeOpaque() {
    assertEquals(
      "audio/mpeg",
      CacheMimeTypes.resolve("https://example.com/song.mp3", null)
    )
    assertEquals(
      "audio/mpeg",
      CacheMimeTypes.resolve(
        "https://example.com/song.mp3",
        "application/octet-stream"
      )
    )
  }

  @Test
  fun resolveNullWhenNeitherSignalRecognisable() {
    assertNull(CacheMimeTypes.resolve("https://example.com/api/stream", null))
    assertNull(CacheMimeTypes.resolve("https://example.com/api/stream", "text/plain"))
  }
}
