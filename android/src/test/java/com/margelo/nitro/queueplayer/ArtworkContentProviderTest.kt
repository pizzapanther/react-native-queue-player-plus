package com.margelo.nitro.queueplayer

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Coverage for [ArtworkContentProvider]'s public companion surface —
 * token registry, idempotent `mapFileUri`, and per-consumer-app
 * authority. The provider is exported with `grantUriPermissions`, so
 * the token-collision + missing-file paths are security-relevant.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@UnstableApi
class ArtworkContentProviderTest {

  private lateinit var tempDir: File

  @Before
  fun setUp() {
    // ContentProvider.onCreate must run so the companion captures the
    // app context. Robolectric's `buildContentProvider` mounts the
    // provider against the test application.
    Robolectric.buildContentProvider(ArtworkContentProvider::class.java).create()
    tempDir = File.createTempFile("rnqp-art", "").apply {
      delete()
      mkdirs()
    }
  }

  @Test
  fun `authority is namespaced to the consumer applicationId`() {
    val expected =
      ApplicationProvider.getApplicationContext<android.content.Context>()
        .packageName + ".rnqp.artwork"
    assertEquals(expected, ArtworkContentProvider.authority())
  }

  @Test
  fun `mapFileUri passes non-file URIs through unchanged`() {
    val http = Uri.parse("https://cdn.example.com/cover.jpg")
    assertEquals(http, ArtworkContentProvider.mapFileUri(http))

    val content = Uri.parse("content://media/external/audio/albumart/42")
    assertEquals(content, ArtworkContentProvider.mapFileUri(content))
  }

  @Test
  fun `mapFileUri passes through when the file does not exist`() {
    val missing = Uri.fromFile(File(tempDir, "missing.jpg"))
    assertEquals(missing, ArtworkContentProvider.mapFileUri(missing))
  }

  @Test
  fun `mapFileUri rewrites a readable file uri to content scheme`() {
    val file = File(tempDir, "cover.jpg").apply { writeBytes(byteArrayOf(0)) }
    val rewritten = ArtworkContentProvider.mapFileUri(Uri.fromFile(file))
    assertEquals("content", rewritten.scheme)
    assertEquals(ArtworkContentProvider.authority(), rewritten.authority)
    assertEquals(1, rewritten.pathSegments.size)
    assertEquals(32, rewritten.pathSegments[0].length)
  }

  @Test
  fun `mapFileUri is idempotent for the same path`() {
    val file = File(tempDir, "stable.jpg").apply { writeBytes(byteArrayOf(0)) }
    val first = ArtworkContentProvider.mapFileUri(Uri.fromFile(file))
    val second = ArtworkContentProvider.mapFileUri(Uri.fromFile(file))
    assertEquals(first, second)
  }

  @Test
  fun `different paths produce different tokens`() {
    val a = File(tempDir, "a.jpg").apply { writeBytes(byteArrayOf(0)) }
    val b = File(tempDir, "b.jpg").apply { writeBytes(byteArrayOf(0)) }
    val ua = ArtworkContentProvider.mapFileUri(Uri.fromFile(a))
    val ub = ArtworkContentProvider.mapFileUri(Uri.fromFile(b))
    assertNotEquals(ua.pathSegments[0], ub.pathSegments[0])
  }

  @Test
  fun `openFile returns null for an unregistered token`() {
    val provider = ArtworkContentProvider().apply { /* no onCreate */ }
    val unknownUri = Uri.Builder()
      .scheme("content")
      .authority("anything.rnqp.artwork")
      .appendPath("deadbeefdeadbeefdeadbeefdeadbeef")
      .build()
    assertNull(provider.openFile(unknownUri, "r"))
  }

  @Test
  fun `openFile returns null when the path is empty`() {
    val provider = ArtworkContentProvider().apply { /* no onCreate */ }
    val noPath = Uri.Builder()
      .scheme("content")
      .authority("anything.rnqp.artwork")
      .build()
    assertNull(provider.openFile(noPath, "r"))
  }

  @Test
  fun `openFile opens a registered file with a real ParcelFileDescriptor`() {
    val file = File(tempDir, "fd.jpg").apply { writeBytes("hi".toByteArray()) }
    val content = ArtworkContentProvider.mapFileUri(Uri.fromFile(file))
    val pfd = ArtworkContentProvider().openFile(content, "r")
    assertNotNull(pfd)
    pfd?.close()
  }
}
