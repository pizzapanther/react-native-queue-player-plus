package com.margelo.nitro.queueplayer

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.MediaSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/**
 * Verifies that [PlaybackService]'s `BitmapLoader` chain is wired
 * correctly for cover-art rendering surfaces — automotive browse
 * rows (Auto / Wear / Assistant), the lock-screen scrubber, and the
 * Media3 notification expanded view.
 *
 * The chain is `PlaceholderFallbackBitmapLoader → CacheBitmapLoader
 * → DataSourceBitmapLoader`. The outer loader guarantees a bitmap
 * always reaches the notification (closes the OEM-skin null-bitmap
 * failure mode); the cache layer dedups repeat requests in-session;
 * the data-source layer resolves `file://` and `https://` URIs
 * through the same data-source stack the player uses, so consumer
 * `userAgent` + custom HTTP headers propagate.
 *
 * Per Media3 1.10 docs, `DataSourceBitmapLoader` accepts both
 * `file://` and `https://` URIs natively — no per-scheme branching
 * needed at the lib level. This file pins the wiring as a sanity
 * check that future BitmapLoader edits don't regress.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@UnstableApi
class PlaybackServiceArtworkLoaderTest {

  private lateinit var controller: ServiceController<PlaybackService>
  private lateinit var service: PlaybackService

  @Before
  fun setUp() {
    controller = Robolectric.buildService(PlaybackService::class.java).create()
    service = controller.get()
  }

  @After
  fun tearDown() {
    controller.destroy()
  }

  @Test
  fun `buildBitmapLoader returns a non-null placeholder-fallback loader`() {
    val loader = service.buildBitmapLoader()
    assertNotNull(loader)
  }

  @Test
  fun `chain wraps CacheBitmapLoader over DataSourceBitmapLoader`() {
    val loader = service.buildBitmapLoader()
    val delegate = loader.javaClass.getDeclaredField("delegate")
      .apply { isAccessible = true }.get(loader)
    assertTrue(
      "second layer dedups repeat URI requests via CacheBitmapLoader",
      delegate is CacheBitmapLoader
    )
    val cached = delegate as CacheBitmapLoader
    val backing = cached.javaClass.getDeclaredField("bitmapLoader")
      .apply { isAccessible = true }.get(cached)
    assertTrue(
      "innermost layer resolves URIs through Media3's DataSource pipeline " +
        "— same HTTP stack as ExoPlayer so userAgent + headers propagate",
      backing is DataSourceBitmapLoader
    )
  }

  @Test
  fun `DataSourceBitmapLoader mirrors Media3's default cap + shared-memory bitmap`() {
    val loader = service.buildBitmapLoader()
    val cached = loader.javaClass.getDeclaredField("delegate")
      .apply { isAccessible = true }.get(loader) as CacheBitmapLoader
    val backing = cached.javaClass.getDeclaredField("bitmapLoader")
      .apply { isAccessible = true }.get(cached) as DataSourceBitmapLoader
    val maxDim = DataSourceBitmapLoader::class.java
      .getDeclaredField("maximumOutputDimension")
      .apply { isAccessible = true }
      .getInt(backing)
    assertEquals(
      "artwork decode is capped to Media3's device-adaptive limit (no full-res allocation)",
      MediaSession.getBitmapDimensionLimit(service),
      maxDim
    )
    val makeShared = DataSourceBitmapLoader::class.java
      .getDeclaredField("makeShared")
      .apply { isAccessible = true }
      .getBoolean(backing)
    assertTrue(
      "decoded bitmap is shared-memory so the System UI process reuses it instead of copying",
      makeShared
    )
  }

  @Test
  fun `repeat builds reuse the same placeholder bitmap`() {
    // The loader holds a placeholder *provider* closure; invoking it
    // reads the process-cached PlaceholderArtwork bitmap so the ~10 KB
    // JPEG decode runs once — service-recreate cycles + multiple
    // consumer-side reconfigures do not multiply the memory cost.
    val first = service.buildBitmapLoader()
    val second = service.buildBitmapLoader()
    val firstProvider = first.javaClass.getDeclaredField("placeholder")
      .apply { isAccessible = true }.get(first) as kotlin.jvm.functions.Function0<*>
    val secondProvider = second.javaClass.getDeclaredField("placeholder")
      .apply { isAccessible = true }.get(second) as kotlin.jvm.functions.Function0<*>
    assertSame(firstProvider.invoke(), secondProvider.invoke())
  }
}
