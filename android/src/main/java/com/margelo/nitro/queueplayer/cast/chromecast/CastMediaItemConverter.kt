package com.margelo.nitro.queueplayer.cast.chromecast

import androidx.media3.cast.MediaItemConverter
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaQueueItem
import androidx.media3.common.MediaMetadata as Media3Metadata
import com.google.android.gms.cast.MediaMetadata as CastMetadata

/**
 * Translates the receiver's queue items into Media3 [MediaItem]s so the
 * MediaSession notification + lockscreen render the cast track's
 * metadata while a [CastPlayer] drives the session.
 *
 * The receiver queue is loaded via `RemoteMediaClient.queueLoad`
 * ([ChromecastSession.load]) rather than `CastPlayer.setMediaItems`, so
 * CastPlayer's content-id map is empty and its current-item slot would
 * be `MediaItem.EMPTY` without this converter. [toMediaItem] is the sole
 * source of each item's metadata for the session surface; it reads back
 * the Cast [CastMetadata] keys that [MediaInfoBuilder] wrote.
 */
@UnstableApi
internal class CastMediaItemConverter : MediaItemConverter {

  override fun toMediaItem(mediaQueueItem: MediaQueueItem): MediaItem {
    val info: MediaInfo? = mediaQueueItem.media
    val castMeta: CastMetadata? = info?.metadata

    val metadata = Media3Metadata.Builder()
      .setTitle(castMeta?.getString(CastMetadata.KEY_TITLE))
      .setArtist(castMeta?.getString(CastMetadata.KEY_ARTIST))
      .setAlbumTitle(castMeta?.getString(CastMetadata.KEY_ALBUM_TITLE))
      .apply { castMeta?.images?.firstOrNull()?.url?.let { setArtworkUri(it) } }
      .build()

    val contentId = info?.contentId.orEmpty()
    // The URI is carried only for item identity in CastPlayer's timeline;
    // the receiver decodes the stream, so this is never opened locally.
    return MediaItem.Builder()
      .setMediaId(contentId)
      .apply { if (contentId.isNotEmpty()) setUri(contentId) }
      .setMediaMetadata(metadata)
      .build()
  }

  override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem {
    // Not exercised by this integration — the queue is pushed to the
    // receiver via ChromecastSession.load → RemoteMediaClient.queueLoad,
    // never via CastPlayer.setMediaItems. Provide a minimal valid item
    // so the interface contract holds if CastPlayer ever calls it.
    val uri = mediaItem.localConfiguration?.uri?.toString() ?: mediaItem.mediaId
    val castMeta = CastMetadata(CastMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
      mediaItem.mediaMetadata.title?.let { putString(CastMetadata.KEY_TITLE, it.toString()) }
      mediaItem.mediaMetadata.artist?.let { putString(CastMetadata.KEY_ARTIST, it.toString()) }
      mediaItem.mediaMetadata.albumTitle?.let {
        putString(CastMetadata.KEY_ALBUM_TITLE, it.toString())
      }
    }
    val info = MediaInfo.Builder(uri)
      .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
      .setContentType("application/octet-stream")
      .setMetadata(castMeta)
      .build()
    return MediaQueueItem.Builder(info).setAutoplay(true).build()
  }
}
