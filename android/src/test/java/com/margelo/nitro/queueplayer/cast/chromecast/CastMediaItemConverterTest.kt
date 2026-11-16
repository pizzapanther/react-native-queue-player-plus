package com.margelo.nitro.queueplayer.cast.chromecast

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.common.images.WebImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [CastMediaItemConverter.toMediaItem] is the sole source of each cast
 * queue item's metadata for the MediaSession notification (the queue is
 * loaded via RemoteMediaClient.queueLoad, so CastPlayer's content-id map
 * is empty). It must read back the Cast MediaMetadata keys MediaInfoBuilder
 * writes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@UnstableApi
class CastMediaItemConverterTest {

  private val converter = CastMediaItemConverter()

  private fun queueItem(
    url: String,
    title: String?,
    artist: String?,
    album: String?,
    artworkUrl: String?,
  ): MediaQueueItem {
    val meta = MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
      title?.let { putString(MediaMetadata.KEY_TITLE, it) }
      artist?.let { putString(MediaMetadata.KEY_ARTIST, it) }
      album?.let { putString(MediaMetadata.KEY_ALBUM_TITLE, it) }
      artworkUrl?.let { addImage(WebImage(Uri.parse(it))) }
    }
    val info = MediaInfo.Builder(url)
      .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
      .setContentType("audio/mpeg")
      .setMetadata(meta)
      .build()
    return MediaQueueItem.Builder(info).build()
  }

  @Test
  fun `toMediaItem maps title artist album artwork and contentId`() {
    val mediaItem = converter.toMediaItem(
      queueItem(
        url = "http://h/a.mp3", title = "Song", artist = "Artist",
        album = "Album", artworkUrl = "http://h/art.jpg",
      )
    )
    assertEquals("Song", mediaItem.mediaMetadata.title?.toString())
    assertEquals("Artist", mediaItem.mediaMetadata.artist?.toString())
    assertEquals("Album", mediaItem.mediaMetadata.albumTitle?.toString())
    assertEquals(Uri.parse("http://h/art.jpg"), mediaItem.mediaMetadata.artworkUri)
    assertEquals("http://h/a.mp3", mediaItem.mediaId)
  }

  @Test
  fun `toMediaItem tolerates missing metadata fields`() {
    val mediaItem = converter.toMediaItem(
      queueItem(
        url = "http://h/b.mp3", title = null, artist = null,
        album = null, artworkUrl = null,
      )
    )
    assertEquals("http://h/b.mp3", mediaItem.mediaId)
    assertNull(mediaItem.mediaMetadata.title)
    assertNull(mediaItem.mediaMetadata.artworkUri)
  }
}
