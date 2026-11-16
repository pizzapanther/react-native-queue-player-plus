package com.margelo.nitro.queueplayer

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Coverage for [PlaceholderArtwork.setCustom] — the consumer-supplied
 * cover-art placeholder from `PlayerConfig.placeholderArtworkUri`. Only a
 * local `file://` URI is honoured; a remote or unset URI leaves the
 * bundled placeholder as the floor.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaceholderArtworkTest {

  private val context: Context
    get() = ApplicationProvider.getApplicationContext()

  @After
  fun tearDown() {
    // Process-wide singleton — reset so a set custom bitmap does not
    // leak into other suites that read the built-in placeholder.
    PlaceholderArtwork.setCustom(null)
  }

  @Test
  fun `setCustom with a file URI installs a custom bitmap that bitmap returns`() {
    PlaceholderArtwork.setCustom("file:///data/user/0/com.example/files/logo.png")
    val custom = customBitmap()
    assertNotNull(custom)
    assertSame(custom, PlaceholderArtwork.bitmap(context))
  }

  @Test
  fun `setCustom ignores a non-file URI and leaves the built-in placeholder`() {
    PlaceholderArtwork.setCustom("https://example.com/logo.png")
    assertNull(customBitmap())
  }

  @Test
  fun `setCustom ignores an empty string and leaves the built-in placeholder`() {
    PlaceholderArtwork.setCustom("")
    assertNull(customBitmap())
  }

  @Test
  fun `setCustom accepts an uppercase FILE scheme (case-insensitive)`() {
    PlaceholderArtwork.setCustom("FILE:///data/user/0/com.example/files/logo.png")
    assertNotNull(customBitmap())
  }

  @Test
  fun `setCustom with null clears a previously-installed custom bitmap`() {
    PlaceholderArtwork.setCustom("file:///data/user/0/com.example/files/logo.png")
    PlaceholderArtwork.setCustom(null)
    assertNull(customBitmap())
  }

  private fun customBitmap(): Bitmap? =
    PlaceholderArtwork.javaClass.getDeclaredField("customBitmap")
      .apply { isAccessible = true }
      .get(PlaceholderArtwork) as Bitmap?
}
