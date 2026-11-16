package com.margelo.nitro.queueplayer

import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors

/**
 * Decorates a [BitmapLoader] so the notification + lock-screen
 * surface always receives a bitmap, never the system "music note"
 * generic. Some Android OEM skins (Xiaomi/Oppo/Realme) drop the
 * notification controls entirely when the loader returns no bitmap;
 * the placeholder fallback keeps them on the artwork-present render
 * path.
 *
 * Three failure modes the decorator covers:
 *  - `MediaMetadata` carries neither an artwork URI nor artwork
 *    bytes — answered synchronously with the placeholder.
 *  - The delegate's `loadBitmapFromMetadata` returns null
 *    (older delegate impls) — answered synchronously with the
 *    placeholder.
 *  - The delegate's future fails (network error, decode failure,
 *    4xx/5xx response) — caught via [Futures.catching] and remapped
 *    to the placeholder.
 *
 * `loadBitmap` and `decodeBitmap` are also wrapped because Media3
 * consults them directly when `MediaMetadata#artworkUri` or
 * `MediaMetadata#artworkData` is set; without the wrap, a failing
 * delegate at those boundaries propagates an exception into the
 * notification render and reproduces the controls-drop OEM
 * behaviour the wrapper is here to prevent.
 */
@UnstableApi
internal class PlaceholderFallbackBitmapLoader(
  private val delegate: BitmapLoader,
  // Read on each fallback (not captured at construction) so a placeholder
  // swapped at `configure` — after the session/loader was built in
  // `onCreate` — is reflected without rebuilding the loader.
  private val placeholder: () -> Bitmap,
) : BitmapLoader {

  override fun supportsMimeType(mimeType: String): Boolean =
    delegate.supportsMimeType(mimeType)

  override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
    Futures.catching(
      delegate.decodeBitmap(data),
      Throwable::class.java,
      { placeholder() },
      MoreExecutors.directExecutor(),
    )

  override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> =
    Futures.catching(
      delegate.loadBitmap(uri),
      Throwable::class.java,
      { placeholder() },
      MoreExecutors.directExecutor(),
    )

  override fun loadBitmapFromMetadata(metadata: MediaMetadata): ListenableFuture<Bitmap>? {
    if (metadata.artworkUri == null && metadata.artworkData == null) {
      return Futures.immediateFuture(placeholder())
    }
    val delegated = delegate.loadBitmapFromMetadata(metadata)
      ?: return Futures.immediateFuture(placeholder())
    return Futures.catching(
      delegated,
      Throwable::class.java,
      { placeholder() },
      MoreExecutors.directExecutor(),
    )
  }
}
