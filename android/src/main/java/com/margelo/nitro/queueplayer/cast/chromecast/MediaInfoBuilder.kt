package com.margelo.nitro.queueplayer.cast.chromecast

import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.common.images.WebImage
import com.margelo.nitro.queueplayer.cast.CastMediaItem
import com.margelo.nitro.queueplayer.cast.http.MediaServerHandle
import java.io.File

/**
 * Translate a [CastMediaItem] into a Cast SDK [MediaInfo] ready for
 * `RemoteMediaClient.load(MediaLoadRequestData.Builder().setMediaInfo(...))`.
 *
 * URL decision table:
 * - `http://` / `https://` → pass through unchanged. Host app's
 *   network config is responsible for cleartext permission if relevant.
 * - `file://` URI or on-disk path → register the file with
 *   [MediaServerHandle] and use the returned LAN HTTP URL so the
 *   receiver can fetch it.
 * - Anything else → `IllegalArgumentException`. Data URIs, resource
 *   URIs, and bundle-packed assets are not streamable to a remote
 *   receiver and should be rejected loudly.
 */
internal object MediaInfoBuilder {

  fun build(
    item: CastMediaItem,
    handle: MediaServerHandle,
    lanAddress: String?,
  ): MediaInfo {
    val uri = item.uri
    val scheme = uri.scheme?.lowercase()

    val url: String = when {
      scheme == "http" || scheme == "https" -> uri.toString()
      scheme == "file" || scheme == null -> {
        val path = uri.path
          ?: throw IllegalArgumentException("Local URI has no path: $uri")
        val file = File(path)
        if (!file.isFile) {
          throw IllegalArgumentException("Local file does not exist: $path")
        }
        val address = lanAddress
          ?: throw IllegalStateException(
            "No LAN address available yet — cannot serve local file to receiver"
          )
        handle.register(file, address, item.contentType)
      }
      else -> throw IllegalArgumentException(
        "Unsupported URI scheme '$scheme' for Chromecast: $uri"
      )
    }

    val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
      item.title?.let { putString(MediaMetadata.KEY_TITLE, it) }
      item.artist?.let {
        putString(MediaMetadata.KEY_ARTIST, it)
        putString(MediaMetadata.KEY_ALBUM_ARTIST, it)
      }
      item.album?.let { putString(MediaMetadata.KEY_ALBUM_TITLE, it) }
      val artwork = item.artwork?.toString()
      if (!artwork.isNullOrEmpty() &&
        (artwork.startsWith("http://") || artwork.startsWith("https://"))
      ) {
        addImage(WebImage(android.net.Uri.parse(artwork)))
      }
    }

    val contentType = item.contentType ?: guessContentType(url)

    return MediaInfo.Builder(url)
      .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
      .setContentType(contentType)
      .setMetadata(metadata)
      .apply { item.durationMs?.takeIf { it > 0L }?.let { setStreamDuration(it) } }
      .build()
  }

  private fun guessContentType(url: String): String {
    val path = url.substringBefore('?').substringBefore('#')
    val ext = path.substringAfterLast('.', "").lowercase()
    return when (ext) {
      "mp3" -> "audio/mpeg"
      "m4a", "mp4" -> "audio/mp4"
      "aac" -> "audio/aac"
      "flac" -> "audio/flac"
      "ogg", "oga", "opus" -> "audio/ogg"
      "wav", "wave" -> "audio/x-wav"
      else -> "application/octet-stream"
    }
  }
}
