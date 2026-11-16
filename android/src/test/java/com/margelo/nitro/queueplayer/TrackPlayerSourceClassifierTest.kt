package com.margelo.nitro.queueplayer

import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit coverage for [TrackPlayer.classifyTrackSource]. Pure function
 * on `(url, isUrlCached)` — no Media3 wiring needed. Mirrors what the
 * iOS half does in `dispatchTrackChange`; cross-platform parity rests
 * on these two implementations agreeing on the matrix.
 *
 * Matrix:
 * - `null` URL → `null` (no active track)
 * - `file://` URL → LOCAL (cache check skipped)
 * - `http(s)://` URL with cache hit → CACHED
 * - `http(s)://` URL with cache miss → STREAMING
 * - non-`file://` non-`http(s)://` URL → `null`
 *   (`data:`, `blob:`, custom schemes — no lib contract today)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@UnstableApi
class TrackPlayerSourceClassifierTest {

  @Test
  fun `null url classifies as null`() {
    assertNull(TrackPlayer.classifyTrackSource(url = null) { false })
  }

  @Test
  fun `file URL classifies as LOCAL without invoking cache check`() {
    var cacheChecked = false
    val source = TrackPlayer.classifyTrackSource(url = "file:///tmp/song.mp3") {
      cacheChecked = true
      true
    }
    assertEquals(TrackSource.LOCAL, source)
    assertEquals(false, cacheChecked)
  }

  @Test
  fun `http URL with cache miss classifies as STREAMING`() {
    assertEquals(
      TrackSource.STREAMING,
      TrackPlayer.classifyTrackSource(url = "http://server.local/song.mp3") { false }
    )
  }

  @Test
  fun `https URL with cache hit classifies as CACHED`() {
    assertEquals(
      TrackSource.CACHED,
      TrackPlayer.classifyTrackSource(url = "https://server.local/song.mp3") { true }
    )
  }

  @Test
  fun `data URL classifies as null without invoking cache check`() {
    var cacheChecked = false
    val source = TrackPlayer.classifyTrackSource(url = "data:audio/mpeg;base64,SUQz") {
      cacheChecked = true
      true
    }
    assertNull(source)
    assertEquals(false, cacheChecked)
  }

  @Test
  fun `custom scheme classifies as null`() {
    assertNull(TrackPlayer.classifyTrackSource(url = "asset://song.mp3") { true })
  }

  @Test
  fun `empty string classifies as null`() {
    assertNull(TrackPlayer.classifyTrackSource(url = "") { true })
  }
}
