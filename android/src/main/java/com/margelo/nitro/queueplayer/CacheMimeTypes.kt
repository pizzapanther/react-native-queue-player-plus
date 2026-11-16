package com.margelo.nitro.queueplayer

import java.net.URI

/**
 * MIME-type handling for the lookahead cache. Mirrors iOS's
 * `LookaheadCache.extensionFromMimeType` / `extensionFromUrl` —
 * lets the Android cache record the same Content-Type signal so
 * Media3 can be told the format up front via
 * [androidx.media3.common.MediaItem.Builder.setMimeType] instead of
 * falling back on extractor sniffing.
 *
 * Pure helpers — no allocator, no state. Safe to call from any
 * thread.
 */
internal object CacheMimeTypes {

  /**
   * Canonical MIME type for an HTTP `Content-Type` header value, or
   * `null` when the type isn't recognised as audio. Strips any
   * `; charset=...` or other parameters before matching.
   * `application/octet-stream` is treated as opaque (returns null)
   * so the caller can fall back to URL-derived hints.
   */
  fun fromContentType(contentType: String?): String? {
    if (contentType.isNullOrEmpty()) return null
    val mime = contentType.substringBefore(';').trim().lowercase()
    return when (mime) {
      "audio/mpeg",
      "audio/mp4",
      "audio/mp4a-latm",
      "audio/aac",
      "audio/wav",
      "audio/wave",
      "audio/x-wav",
      "audio/flac",
      "audio/x-flac",
      "audio/ogg",
      "application/ogg",
      "audio/opus",
      "audio/x-m4a",
      "application/x-mpegurl",
      "application/vnd.apple.mpegurl" -> mime
      else -> null
    }
  }

  /**
   * URL-derived MIME fallback. Reads the URI's path extension and
   * maps known audio extensions to their canonical MIME type.
   * Returns `null` for opaque / parameterised paths so callers can
   * fall through to extractor sniffing.
   */
  fun fromUrl(url: String): String? {
    val path = try { URI(url).path } catch (_: Throwable) { return null }
      ?: return null
    val dot = path.lastIndexOf('.')
    if (dot < 0 || dot == path.length - 1) return null
    val ext = path.substring(dot + 1).lowercase()
    return when (ext) {
      "mp3" -> "audio/mpeg"
      "m4a", "m4b", "m4r", "mp4" -> "audio/mp4"
      "aac" -> "audio/aac"
      "wav", "wave" -> "audio/wav"
      "flac" -> "audio/flac"
      "ogg", "opus" -> "audio/ogg"
      "m3u8" -> "application/x-mpegurl"
      else -> null
    }
  }

  /**
   * Resolve the best-known MIME for `url` given an optional response
   * `Content-Type`. Content-Type wins; URL extension is the fallback;
   * `null` when neither yields anything recognisable.
   */
  fun resolve(url: String, contentType: String?): String? =
    fromContentType(contentType) ?: fromUrl(url)
}
