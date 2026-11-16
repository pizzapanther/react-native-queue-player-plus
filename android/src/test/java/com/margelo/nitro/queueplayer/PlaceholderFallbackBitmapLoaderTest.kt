package com.margelo.nitro.queueplayer

import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric coverage for [PlaceholderFallbackBitmapLoader] — the
 * decorator that hands the placeholder bitmap to Media3 whenever the
 * delegate would otherwise produce null or fail. The OEM-skin failure
 * mode (notification controls drop on null artwork) is closed at this
 * boundary.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@UnstableApi
class PlaceholderFallbackBitmapLoaderTest {

  private lateinit var placeholder: Bitmap
  private lateinit var delegateBitmap: Bitmap

  @Before
  fun setUp() {
    placeholder = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
    delegateBitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
  }

  @Test
  fun `loadBitmapFromMetadata returns placeholder when artwork URI and data are absent`() {
    val loader = PlaceholderFallbackBitmapLoader(NeverCalledLoader, { placeholder })
    val metadata = MediaMetadata.Builder().setTitle("no artwork").build()

    val future = loader.loadBitmapFromMetadata(metadata)

    assertNotNull(future)
    assertSame(placeholder, future!!.get())
  }

  @Test
  fun `loadBitmapFromMetadata reads the placeholder provider on every call so a live swap is reflected`() {
    val swapped = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
    var current = placeholder
    val loader = PlaceholderFallbackBitmapLoader(NeverCalledLoader) { current }
    val metadata = MediaMetadata.Builder().setTitle("no artwork").build()

    assertSame(placeholder, loader.loadBitmapFromMetadata(metadata)!!.get())
    current = swapped
    assertSame(swapped, loader.loadBitmapFromMetadata(metadata)!!.get())
  }

  @Test
  fun `loadBitmapFromMetadata returns delegate result when artwork URI is present`() {
    val delegate = StubLoader(metadataResult = Futures.immediateFuture(delegateBitmap))
    val loader = PlaceholderFallbackBitmapLoader(delegate, { placeholder })
    val metadata = MediaMetadata.Builder()
      .setArtworkUri(Uri.parse("https://example.com/cover.jpg"))
      .build()

    val future = loader.loadBitmapFromMetadata(metadata)

    assertNotNull(future)
    assertSame(delegateBitmap, future!!.get())
  }

  @Test
  fun `loadBitmapFromMetadata returns placeholder when delegate future fails`() {
    val delegate = StubLoader(
      metadataResult = Futures.immediateFailedFuture(RuntimeException("decode failed"))
    )
    val loader = PlaceholderFallbackBitmapLoader(delegate, { placeholder })
    val metadata = MediaMetadata.Builder()
      .setArtworkUri(Uri.parse("https://example.com/broken.jpg"))
      .build()

    val future = loader.loadBitmapFromMetadata(metadata)

    assertNotNull(future)
    assertSame(placeholder, future!!.get())
  }

  @Test
  fun `loadBitmapFromMetadata returns placeholder when delegate returns null future`() {
    val delegate = StubLoader(metadataResult = null)
    val loader = PlaceholderFallbackBitmapLoader(delegate, { placeholder })
    val metadata = MediaMetadata.Builder()
      .setArtworkUri(Uri.parse("https://example.com/cover.jpg"))
      .build()

    val future = loader.loadBitmapFromMetadata(metadata)

    assertNotNull(future)
    assertSame(placeholder, future!!.get())
  }

  @Test
  fun `loadBitmap returns placeholder when delegate fails`() {
    val delegate = StubLoader(
      uriResult = Futures.immediateFailedFuture(RuntimeException("network error"))
    )
    val loader = PlaceholderFallbackBitmapLoader(delegate, { placeholder })

    val result = loader.loadBitmap(Uri.parse("https://example.com/cover.jpg")).get()

    assertSame(placeholder, result)
  }

  @Test
  fun `loadBitmap returns delegate result on success`() {
    val delegate = StubLoader(uriResult = Futures.immediateFuture(delegateBitmap))
    val loader = PlaceholderFallbackBitmapLoader(delegate, { placeholder })

    val result = loader.loadBitmap(Uri.parse("https://example.com/cover.jpg")).get()

    assertSame(delegateBitmap, result)
  }

  @Test
  fun `decodeBitmap returns placeholder when delegate fails`() {
    val delegate = StubLoader(
      bytesResult = Futures.immediateFailedFuture(RuntimeException("malformed bytes"))
    )
    val loader = PlaceholderFallbackBitmapLoader(delegate, { placeholder })

    val result = loader.decodeBitmap(byteArrayOf(0x00, 0x01)).get()

    assertSame(placeholder, result)
  }

  @Test
  fun `decodeBitmap returns delegate result on success`() {
    val delegate = StubLoader(bytesResult = Futures.immediateFuture(delegateBitmap))
    val loader = PlaceholderFallbackBitmapLoader(delegate, { placeholder })

    val result = loader.decodeBitmap(byteArrayOf(0x00, 0x01)).get()

    assertSame(delegateBitmap, result)
  }

  @Test
  fun `supportsMimeType proxies through to delegate`() {
    val delegate = StubLoader(supportedMimeTypes = setOf("image/jpeg"))
    val loader = PlaceholderFallbackBitmapLoader(delegate, { placeholder })

    assertTrue(loader.supportsMimeType("image/jpeg"))
    assertFalse(loader.supportsMimeType("image/heic"))
  }

  /**
   * Hand-rolled fake — the lib's HARD RULE forbids reshaping the
   * production class for test ergonomics, so the stub implements the
   * full [BitmapLoader] surface and the test wires the per-method
   * result it cares about.
   */
  private class StubLoader(
    private val supportedMimeTypes: Set<String> = emptySet(),
    private val bytesResult: ListenableFuture<Bitmap>? = null,
    private val uriResult: ListenableFuture<Bitmap>? = null,
    private val metadataResult: ListenableFuture<Bitmap>? = null,
  ) : BitmapLoader {
    override fun supportsMimeType(mimeType: String): Boolean =
      mimeType in supportedMimeTypes

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
      bytesResult ?: error("decodeBitmap not configured for this stub")

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> =
      uriResult ?: error("loadBitmap not configured for this stub")

    override fun loadBitmapFromMetadata(metadata: MediaMetadata): ListenableFuture<Bitmap>? =
      metadataResult
  }

  private object NeverCalledLoader : BitmapLoader {
    override fun supportsMimeType(mimeType: String): Boolean =
      error("supportsMimeType should not be reached when artwork is absent")

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
      error("decodeBitmap should not be reached when artwork is absent")

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> =
      error("loadBitmap should not be reached when artwork is absent")

    override fun loadBitmapFromMetadata(metadata: MediaMetadata): ListenableFuture<Bitmap>? =
      error("loadBitmapFromMetadata delegate should not be reached when artwork is absent")
  }
}
