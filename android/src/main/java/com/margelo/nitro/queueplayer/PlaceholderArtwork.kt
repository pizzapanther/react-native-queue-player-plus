package com.margelo.nitro.queueplayer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri

/**
 * Bundled fallback artwork used by the MediaSession + notification
 * surface when the consumer-supplied `track.artworkUrl` is missing
 * or unreachable. Lazy-loaded once per process; cached for lifetime.
 *
 * Real-world failure mode this guards against: passing `null` artwork
 * makes some Android OEM skins (Xiaomi, Oppo, Realme) drop the
 * notification controls entirely. By always handing a bitmap to the
 * `MediaNotification.Provider` BitmapLoader, the lib forces the OEM
 * skin into the well-tested artwork-present render path.
 *
 * The asset lives at `res/raw/rnqp_placeholder.jpg` so it bypasses
 * Android's drawable DPI scaling (it's a fixed 256×256 JPEG; we never
 * want it scaled or themed).
 */
internal object PlaceholderArtwork {

  /**
   * Returns the placeholder bitmap. Cached after first call; safe
   * to call from any thread (the bitmap is immutable once decoded).
   */
  fun bitmap(context: Context): Bitmap {
    customBitmap?.let { return it }
    cached?.let { return it }
    synchronized(this) {
      customBitmap?.let { return it }
      cached?.let { return it }
      val decoded = decode(context)
      cached = decoded
      return decoded
    }
  }

  /**
   * Set (or clear) the consumer-supplied placeholder from
   * `PlayerConfig.placeholderArtworkUri`. Decoded once from the local
   * `file://` path via `BitmapFactory.decodeFile` (scheme match is
   * case-insensitive); a non-`file://` URI, an unset URI, or a decode
   * failure leaves the bundled placeholder as the floor. Decode is
   * synchronous, so a very large consumer image briefly blocks the
   * `configure` call. Called from configure.
   */
  fun setCustom(uri: String?) {
    synchronized(this) {
      // Local file:// only — remote URLs would break offline + change
      // behaviour, so anything else leaves the built-in as the floor.
      customBitmap = uri
        ?.takeIf { Uri.parse(it).scheme.equals("file", ignoreCase = true) }
        ?.let { decodeUri(it) }
    }
  }

  private fun decodeUri(uri: String): Bitmap? =
    try {
      Uri.parse(uri).path?.let { BitmapFactory.decodeFile(it) }
    } catch (e: Exception) {
      null
    }

  /**
   * Returns the placeholder bytes (raw JPEG payload). Useful for
   * paths that want to write the bytes into a `MediaMetadata`
   * artworkData field rather than decoding to bitmap upfront.
   */
  fun bytes(context: Context): ByteArray {
    cachedBytes?.let { return it }
    synchronized(this) {
      cachedBytes?.let { return it }
      val stream = context.resources.openRawResource(R.raw.rnqp_placeholder)
      val out = stream.use { it.readBytes() }
      cachedBytes = out
      return out
    }
  }

  @Volatile private var cached: Bitmap? = null
  @Volatile private var cachedBytes: ByteArray? = null
  @Volatile private var customBitmap: Bitmap? = null

  private fun decode(context: Context): Bitmap {
    val stream = context.resources.openRawResource(R.raw.rnqp_placeholder)
    return stream.use { BitmapFactory.decodeStream(it) }
      ?: throw IllegalStateException(
        "RNQP placeholder asset missing or undecodable at res/raw/rnqp_placeholder.jpg — " +
          "verify the JPEG was packaged into the lib AAR"
      )
  }
}
