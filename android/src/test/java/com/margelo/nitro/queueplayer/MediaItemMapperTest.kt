package com.margelo.nitro.queueplayer

import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Coverage for [MediaItemMapper]. Robolectric is required because
 * [MediaMetadata.setArtworkUri] reads `Uri.parse` which depends on
 * Android's Uri parser shadow.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@UnstableApi
class MediaItemMapperTest {

  private fun item(
    id: String = "i1",
    title: String = "Title",
    subtitle: String? = null,
    artworkUrl: String? = null,
    playable: Boolean = false,
    hasChildren: Boolean? = null
  ) = BrowseItem(
    id = id,
    title = title,
    subtitle = subtitle,
    artworkUrl = artworkUrl,
    playable = playable,
    hasChildren = hasChildren
  )

  @Test
  fun `playable-only item maps to IS_PLAYABLE music`() {
    val media = MediaItemMapper.toMediaItem(item(playable = true))
    assertEquals("i1", media.mediaId)
    assertEquals(true, media.mediaMetadata.isPlayable)
    assertEquals(false, media.mediaMetadata.isBrowsable)
    assertEquals(MediaMetadata.MEDIA_TYPE_MUSIC, media.mediaMetadata.mediaType)
  }

  @Test
  fun `browsable-only item maps to IS_BROWSABLE folder`() {
    val media = MediaItemMapper.toMediaItem(item(playable = false, hasChildren = true))
    assertEquals(false, media.mediaMetadata.isPlayable)
    assertEquals(true, media.mediaMetadata.isBrowsable)
    assertEquals(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, media.mediaMetadata.mediaType)
  }

  @Test
  fun `both flags set defensively maps to mixed type`() {
    // Consumer error per the BrowseItem contract, but the mapper
    // shouldn't crash — pick a defensive mediaType that lets
    // Media3 controllers render whichever side they support.
    val media = MediaItemMapper.toMediaItem(item(playable = true, hasChildren = true))
    assertEquals(true, media.mediaMetadata.isPlayable)
    assertEquals(true, media.mediaMetadata.isBrowsable)
    assertEquals(MediaMetadata.MEDIA_TYPE_MIXED, media.mediaMetadata.mediaType)
  }

  @Test
  fun `neither flag set maps to non-tappable mixed`() {
    val media = MediaItemMapper.toMediaItem(item(playable = false, hasChildren = false))
    assertEquals(false, media.mediaMetadata.isPlayable)
    assertEquals(false, media.mediaMetadata.isBrowsable)
    assertEquals(MediaMetadata.MEDIA_TYPE_MIXED, media.mediaMetadata.mediaType)
  }

  @Test
  fun `null hasChildren is treated as not browsable`() {
    val media = MediaItemMapper.toMediaItem(item(playable = true, hasChildren = null))
    assertEquals(true, media.mediaMetadata.isPlayable)
    assertEquals(false, media.mediaMetadata.isBrowsable)
  }

  @Test
  fun `subtitle is propagated when present`() {
    val media = MediaItemMapper.toMediaItem(
      item(title = "Recently Played", subtitle = "Last 50 tracks")
    )
    assertEquals("Recently Played", media.mediaMetadata.title)
    assertEquals("Last 50 tracks", media.mediaMetadata.subtitle)
  }

  @Test
  fun `subtitle null leaves the metadata subtitle null`() {
    val media = MediaItemMapper.toMediaItem(item(subtitle = null))
    assertNull(media.mediaMetadata.subtitle)
  }

  @Test
  fun `artwork URI is parsed when present and non-empty`() {
    val media = MediaItemMapper.toMediaItem(
      item(artworkUrl = "https://example.test/cover.jpg")
    )
    assertNotNull(media.mediaMetadata.artworkUri)
    assertEquals(
      "https://example.test/cover.jpg",
      media.mediaMetadata.artworkUri.toString()
    )
  }

  @Test
  fun `artwork URI accepts file scheme for local artwork`() {
    val media = MediaItemMapper.toMediaItem(
      item(artworkUrl = "file:///var/data/cover.jpg")
    )
    assertEquals(
      "file:///var/data/cover.jpg",
      media.mediaMetadata.artworkUri.toString()
    )
  }

  @Test
  fun `empty artwork URL is treated as missing`() {
    val media = MediaItemMapper.toMediaItem(item(artworkUrl = ""))
    assertNull(media.mediaMetadata.artworkUri)
  }

  @Test
  fun `null artwork URL leaves the metadata artworkUri null`() {
    val media = MediaItemMapper.toMediaItem(item(artworkUrl = null))
    assertNull(media.mediaMetadata.artworkUri)
  }

  @Test
  fun `toMediaItems maps every input + preserves order`() {
    val items = listOf(
      item(id = "a", title = "Alpha", playable = true),
      item(id = "b", title = "Beta", hasChildren = true),
      item(id = "c", title = "Gamma", playable = true)
    )
    val mapped = MediaItemMapper.toMediaItems(items)
    assertEquals(3, mapped.size)
    assertEquals("a", mapped[0].mediaId)
    assertEquals("b", mapped[1].mediaId)
    assertEquals("c", mapped[2].mediaId)
  }
}
