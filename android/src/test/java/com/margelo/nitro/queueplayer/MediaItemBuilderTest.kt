package com.margelo.nitro.queueplayer

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * MediaItemBuilder unit tests.
 *
 * Mirrors the shape of iOS `AVQueueBuilderTests`:
 * - `mergeHttpRequestProperties` is the header/UA merging core.
 *   Eight tests cover the iOS merge-invariant set (empty, headers
 *   only, UA only, merged, empty-UA, UA-in-headers-overridden-by-
 *   explicit-field, null safety).
 * - `buildMediaItems` covers MediaItem construction (id + uri
 *   preserved; empty input → empty output; remote + file URIs
 *   both produce items — the file/remote routing difference is a
 *   DataSource.Factory concern, not a MediaItem concern).
 *
 * Factory-building is checked by type only (the internal
 * `DefaultHttpDataSource.Factory` is configured via a builder chain
 * whose effective state is hard to read back without reflection —
 * the factory is wired into ExoPlayer at construction time).
 *
 * @Config sdk=34 — Robolectric 4.11.1's max supported SDK.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@UnstableApi
class MediaItemBuilderTest {

  // --- mergeHttpRequestProperties

  @Test
  fun `mergeHttpRequestProperties returns empty when nothing supplied`() {
    val merged = MediaItemBuilder.mergeHttpRequestProperties(
      headers = null, userAgent = null
    )
    assertTrue("no headers + no UA should produce empty map", merged.isEmpty())
  }

  @Test
  fun `mergeHttpRequestProperties passes headers through verbatim when no UA`() {
    val merged = MediaItemBuilder.mergeHttpRequestProperties(
      headers = mapOf("Authorization" to "Bearer t0ken", "X-Custom" to "42"),
      userAgent = null
    )
    assertEquals("Bearer t0ken", merged["Authorization"])
    assertEquals("42", merged["X-Custom"])
    assertNull("no UA configured → no UA injected", merged["User-Agent"])
  }

  @Test
  fun `mergeHttpRequestProperties injects UA as User-Agent header when headers null`() {
    val merged = MediaItemBuilder.mergeHttpRequestProperties(
      headers = null, userAgent = "RNQPDemo/1.0"
    )
    assertEquals("RNQPDemo/1.0", merged["User-Agent"])
    assertEquals(1, merged.size)
  }

  @Test
  fun `mergeHttpRequestProperties merges headers and explicit UA`() {
    val merged = MediaItemBuilder.mergeHttpRequestProperties(
      headers = mapOf("Authorization" to "Bearer t0ken"),
      userAgent = "RNQPDemo/1.0"
    )
    assertEquals("Bearer t0ken", merged["Authorization"])
    assertEquals("RNQPDemo/1.0", merged["User-Agent"])
  }

  @Test
  fun `mergeHttpRequestProperties ignores empty-string UA`() {
    val merged = MediaItemBuilder.mergeHttpRequestProperties(
      headers = mapOf("Authorization" to "Bearer t0ken"),
      userAgent = ""
    )
    assertNull(
      "empty-string UA must be treated the same as no UA",
      merged["User-Agent"]
    )
    assertEquals("Bearer t0ken", merged["Authorization"])
  }

  @Test
  fun `mergeHttpRequestProperties explicit UA overrides headers User-Agent`() {
    // Matches iOS AVQueueBuilder contract: explicit typed field is
    // canonical and wins over any User-Agent key already present in
    // the headers dict.
    val merged = MediaItemBuilder.mergeHttpRequestProperties(
      headers = mapOf("User-Agent" to "stale-value"),
      userAgent = "canonical-value"
    )
    assertEquals("canonical-value", merged["User-Agent"])
  }

  @Test
  fun `mergeHttpRequestProperties preserves headers User-Agent when UA is null`() {
    // Inverse of the previous case: when the caller hasn't set
    // `userAgent`, a User-Agent key in `headers` is kept as-is.
    val merged = MediaItemBuilder.mergeHttpRequestProperties(
      headers = mapOf("User-Agent" to "from-headers"),
      userAgent = null
    )
    assertEquals("from-headers", merged["User-Agent"])
  }

  @Test
  fun `mergeHttpRequestProperties does not mutate the input headers map`() {
    val input = mapOf("Authorization" to "Bearer t0ken")
    MediaItemBuilder.mergeHttpRequestProperties(
      headers = input, userAgent = "RNQPDemo/1.0"
    )
    assertNull(
      "mutation would corrupt the caller's PlayerConfig.httpHeaders",
      input["User-Agent"]
    )
  }

  // --- buildMediaItems

  @Test
  fun `buildMediaItems returns empty list for empty input`() {
    val result = MediaItemBuilder.buildMediaItems(context, emptyList())
    assertTrue(result.isEmpty())
  }

  @Test
  fun `buildMediaItems preserves id and uri for one track`() {
    val track = makeTrack(id = "t1", url = "https://example.com/track.m4a")
    val items = MediaItemBuilder.buildMediaItems(context, listOf(track))

    assertEquals(1, items.size)
    assertEquals("t1", items[0].mediaId)
    assertEquals(
      "https://example.com/track.m4a",
      items[0].localConfiguration?.uri?.toString()
    )
  }

  @Test
  fun `buildMediaItems builds one MediaItem per track preserving order`() {
    val tracks = (1..5).map {
      makeTrack(id = "t$it", url = "https://example.com/$it.m4a")
    }
    val items = MediaItemBuilder.buildMediaItems(context, tracks)

    assertEquals(5, items.size)
    items.forEachIndexed { index, mediaItem ->
      assertEquals("t${index + 1}", mediaItem.mediaId)
      assertEquals(
        "https://example.com/${index + 1}.m4a",
        mediaItem.localConfiguration?.uri?.toString()
      )
    }
  }

  @Test
  fun `buildMediaItems accepts file URIs verbatim`() {
    // Parity with iOS `AVQueueBuilderTests.testMakeItemForFileURL
    // SkipsHeaderDictionary`: the test asserts the URI scheme is
    // preserved, but does NOT assert the file/remote routing
    // downstream. What the factory built is unreadable post-build on
    // both platforms (AVURLAsset options can't be read back on
    // iOS; DefaultDataSource.Factory wiring is internal on
    // Android), so the documented contract is the test.
    val track = makeTrack(id = "local", url = "file:///tmp/track.m4a")
    val items = MediaItemBuilder.buildMediaItems(context, listOf(track))

    assertEquals(1, items.size)
    val uri = items[0].localConfiguration?.uri
    assertNotNull("file URI must be parsed into localConfiguration", uri)
    assertEquals("file", uri?.scheme)
  }

  // --- buildMediaItems MIME handling

  @Test
  fun `buildMediaItems sets MIME from URL extension for http urls`() {
    val track = makeTrack(id = "t", url = "https://example.com/song.mp3")
    val items = MediaItemBuilder.buildMediaItems(context, listOf(track), lookaheadCache = null)
    assertEquals("audio/mpeg", items[0].localConfiguration?.mimeType)
  }

  @Test
  fun `buildMediaItems leaves MIME unset for file URIs even when extension is recognisable`() {
    // file:// bypasses the lookahead cache; setting MIME on these
    // is meaningless and could mis-cue Media3. Mirrors iOS where
    // the file-extension trick fires only on HTTP downloads.
    val track = makeTrack(id = "local", url = "file:///tmp/song.mp3")
    val items = MediaItemBuilder.buildMediaItems(context, listOf(track), lookaheadCache = null)
    assertNull(items[0].localConfiguration?.mimeType)
  }

  // --- buildMediaItems MediaMetadata coverage

  private fun metadataFor(track: TrackItem): MediaMetadata =
    MediaItemBuilder.buildMediaItems(context, listOf(track))[0].mediaMetadata

  @Test
  fun `MediaMetadata sets title artist album from track fields`() {
    val track = makeTrack(
      id = "t",
      url = "https://example.com/s.mp3",
      title = "Song",
      artist = "Artist",
      album = "Album"
    )
    val metadata = metadataFor(track)
    assertEquals("Song", metadata.title?.toString())
    assertEquals("Artist", metadata.artist?.toString())
    assertEquals("Album", metadata.albumTitle?.toString())
  }

  @Test
  fun `MediaMetadata coerces null title artist album to empty string`() {
    val metadata = metadataFor(makeTrack(id = "t", url = "https://example.com/s.mp3"))
    assertEquals("", metadata.title?.toString())
    assertEquals("", metadata.artist?.toString())
    assertEquals("", metadata.albumTitle?.toString())
  }

  @Test
  fun `MediaMetadata uses artworkUri when artworkUrl is non-empty`() {
    val track = makeTrack(
      id = "t",
      url = "https://example.com/s.mp3",
      artworkUrl = "https://example.com/cover.jpg"
    )
    val metadata = metadataFor(track)
    assertEquals("https://example.com/cover.jpg", metadata.artworkUri?.toString())
    assertNull(metadata.artworkData)
  }

  @Test
  fun `MediaMetadata uses placeholder resource URI when artworkUrl is null`() {
    val metadata = metadataFor(makeTrack(id = "t", url = "https://example.com/s.mp3"))
    val expected =
      "android.resource://${context.packageName}/${R.raw.rnqp_placeholder}"
    assertEquals(expected, metadata.artworkUri?.toString())
    assertNull(metadata.artworkData)
  }

  @Test
  fun `MediaMetadata uses placeholder resource URI when artworkUrl is empty`() {
    val track = makeTrack(
      id = "t",
      url = "https://example.com/s.mp3",
      artworkUrl = ""
    )
    val metadata = metadataFor(track)
    val expected =
      "android.resource://${context.packageName}/${R.raw.rnqp_placeholder}"
    assertEquals(expected, metadata.artworkUri?.toString())
  }

  @Test
  fun `MediaMetadata uses placeholder resource URI when artworkUrl is whitespace only`() {
    val track = makeTrack(
      id = "t",
      url = "https://example.com/s.mp3",
      artworkUrl = "   "
    )
    val metadata = metadataFor(track)
    val expected =
      "android.resource://${context.packageName}/${R.raw.rnqp_placeholder}"
    assertEquals(expected, metadata.artworkUri?.toString())
  }

  @Test
  fun `MediaMetadata uses the custom placeholder URI when artworkUrl is null and a file URI is configured`() {
    val customUri = "file:///data/user/0/com.example/files/logo.png"
    val metadata = MediaItemBuilder.buildMediaItems(
      context,
      listOf(makeTrack(id = "t", url = "https://example.com/s.mp3")),
      customPlaceholderUri = customUri
    )[0].mediaMetadata
    val expected = ArtworkContentProvider.mapFileUri(Uri.parse(customUri)).toString()
    assertEquals(expected, metadata.artworkUri?.toString())
  }

  @Test
  fun `MediaMetadata ignores a non-file custom placeholder URI and uses the built-in`() {
    val metadata = MediaItemBuilder.buildMediaItems(
      context,
      listOf(makeTrack(id = "t", url = "https://example.com/s.mp3")),
      customPlaceholderUri = "https://example.com/logo.png"
    )[0].mediaMetadata
    val expected =
      "android.resource://${context.packageName}/${R.raw.rnqp_placeholder}"
    assertEquals(expected, metadata.artworkUri?.toString())
  }

  @Test
  fun `MediaMetadata prefers the real artworkUrl over a configured custom placeholder`() {
    val metadata = MediaItemBuilder.buildMediaItems(
      context,
      listOf(
        makeTrack(
          id = "t",
          url = "https://example.com/s.mp3",
          artworkUrl = "https://example.com/cover.jpg"
        )
      ),
      customPlaceholderUri = "file:///data/user/0/com.example/files/logo.png"
    )[0].mediaMetadata
    assertEquals("https://example.com/cover.jpg", metadata.artworkUri?.toString())
  }

  @Test
  fun `MediaMetadata normalizes an uppercase FILE scheme custom placeholder to lowercase file`() {
    val customUri = "FILE:///data/user/0/com.example/files/logo.png"
    val metadata = MediaItemBuilder.buildMediaItems(
      context,
      listOf(makeTrack(id = "t", url = "https://example.com/s.mp3")),
      customPlaceholderUri = customUri
    )[0].mediaMetadata
    val normalized = Uri.parse(customUri).buildUpon().scheme("file").build()
    val expected = ArtworkContentProvider.mapFileUri(normalized).toString()
    assertEquals(expected, metadata.artworkUri?.toString())
    assertEquals("file", metadata.artworkUri?.scheme)
  }

  @Test
  fun `MediaMetadata sets durationMs from positive seconds`() {
    val track = makeTrack(
      id = "t",
      url = "https://example.com/s.mp3",
      duration = 12.5
    )
    assertEquals(12_500L, metadataFor(track).durationMs)
  }

  @Test
  fun `MediaMetadata omits duration when null`() {
    val metadata = metadataFor(makeTrack(id = "t", url = "https://example.com/s.mp3"))
    assertNull(metadata.durationMs)
  }

  @Test
  fun `MediaMetadata omits duration when zero`() {
    val track = makeTrack(
      id = "t",
      url = "https://example.com/s.mp3",
      duration = 0.0
    )
    assertNull(metadataFor(track).durationMs)
  }

  @Test
  fun `MediaMetadata omits duration when negative`() {
    val track = makeTrack(
      id = "t",
      url = "https://example.com/s.mp3",
      duration = -3.0
    )
    assertNull(metadataFor(track).durationMs)
  }

  @Test
  fun `buildMediaItems attaches MediaMetadata to every item`() {
    val track = makeTrack(
      id = "t",
      url = "https://example.com/s.mp3",
      title = "Song"
    )
    val items = MediaItemBuilder.buildMediaItems(context, listOf(track))
    assertEquals("Song", items[0].mediaMetadata.title?.toString())
  }

  @Test
  fun `resolveMimeTypeForBuild returns null for non-http schemes`() {
    val resolved = MediaItemBuilder.resolveMimeTypeForBuild(
      url = "asset:///bundle/song.m4a",
      lookaheadCache = null
    )
    assertNull(resolved)
  }

  @Test
  fun `resolveMimeTypeForBuild returns null for opaque http URLs without extension`() {
    val resolved = MediaItemBuilder.resolveMimeTypeForBuild(
      url = "https://example.com/api/stream?id=1",
      lookaheadCache = null
    )
    assertNull(resolved)
  }

  // --- buildMediaSourceFactoryRaw

  @Test
  fun `buildMediaSourceFactoryRaw returns a non-null factory with no config`() {
    val factory = MediaItemBuilder.buildMediaSourceFactoryRaw(
      context = ApplicationProvider.getApplicationContext(),
      headers = null,
      userAgent = null
    )
    assertNotNull(factory)
  }

  @Test
  fun `buildMediaSourceFactoryRaw returns a non-null factory with headers and UA`() {
    val factory = MediaItemBuilder.buildMediaSourceFactoryRaw(
      context = ApplicationProvider.getApplicationContext(),
      headers = mapOf("Authorization" to "Bearer t"),
      userAgent = "rnqp/test"
    )
    assertNotNull(factory)
  }

  private val context: Context
    get() = ApplicationProvider.getApplicationContext()

  // --- durationToBackfill: the System UI seekbar duration derivation decision

  @Test
  fun `durationToBackfill keeps a positive consumer duration and never overwrites`() {
    assertNull(
      MediaItemBuilder.durationToBackfill(existingDurationMs = 42_000L, playerDurationMs = 50_000L),
    )
  }

  @Test
  fun `durationToBackfill derives from the player when the consumer supplied none`() {
    assertEquals(
      180_000L,
      MediaItemBuilder.durationToBackfill(existingDurationMs = null, playerDurationMs = 180_000L),
    )
  }

  @Test
  fun `durationToBackfill treats a non-positive existing duration as absent`() {
    assertEquals(
      180_000L,
      MediaItemBuilder.durationToBackfill(existingDurationMs = 0L, playerDurationMs = 180_000L),
    )
  }

  @Test
  fun `durationToBackfill skips a live or not-yet-decoded player duration`() {
    assertNull(
      MediaItemBuilder.durationToBackfill(existingDurationMs = null, playerDurationMs = C.TIME_UNSET),
    )
    assertNull(
      MediaItemBuilder.durationToBackfill(existingDurationMs = null, playerDurationMs = 0L),
    )
  }

  private fun makeTrack(
    id: String,
    url: String,
    title: String? = null,
    artist: String? = null,
    album: String? = null,
    duration: Double? = null,
    artworkUrl: String? = null,
  ): TrackItem =
    TrackItem(
      id = id,
      url = url,
      title = title,
      artist = artist,
      album = album,
      duration = duration,
      artworkUrl = artworkUrl,
      extras = null,
      replayGainTrackGain = null, replayGainTrackPeak = null,
      replayGainAlbumGain = null, replayGainAlbumPeak = null
    )
}
