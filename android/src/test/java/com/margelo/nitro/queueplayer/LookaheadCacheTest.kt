package com.margelo.nitro.queueplayer

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * [LookaheadCache] unit tests.
 *
 * Exercises the cache directly without going through ExoPlayer:
 * - Constructor creates the cache directory under `context.cacheDir`.
 * - `wrapDataSourceFactory` hands out a [CacheDataSource] while the cache
 *   serves playback and the bare upstream source while it does not.
 * - `release()` is idempotent, and a fresh cache can be constructed
 *   at the same directory afterwards (the SimpleCache one-instance-
 *   per-directory invariant is restored on release).
 *
 * @Config sdk=34 — Robolectric 4.11.1's max supported SDK.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@UnstableApi
class LookaheadCacheTest {

  private lateinit var cache: LookaheadCache

  private fun context() = ApplicationProvider.getApplicationContext<android.content.Context>()

  @Before
  fun setUp() {
    cache = LookaheadCache(
      context = context(),
      maxSizeBytes = 4L * 1024L * 1024L,
      cacheDirName = "test-lookahead-${System.nanoTime()}"
    )
  }

  @After
  fun tearDown() {
    runCatching { cache.release() }
  }

  @Test
  fun `cacheDir resolves under context cacheDir with the supplied name`() {
    // SimpleCache initialises its on-disk index on a background
    // thread — we don't gate on `dir.isDirectory` because the
    // mkdirs() call inside SimpleCache.init() may not have run by
    // the time this assertion fires. Verifying the resolved path is
    // sufficient: SimpleCache won't successfully construct against
    // an unwritable parent, and the @Before's construction call
    // already returned without throwing.
    val dir = cache.cacheDir
    assertEquals(
      "cache dir must be rooted at context.cacheDir",
      File(context().cacheDir, dir.name).absolutePath,
      dir.absolutePath
    )
  }

  @Test
  fun `the wrapped factory reads through the cache while it serves playback`() {
    val upstream = DefaultHttpDataSource.Factory()
    val wrapped = cache.wrapDataSourceFactory(upstream)
    val source = wrapped.createDataSource()
    assertTrue(
      "a data source opened while the cache is enabled checks the disk first",
      source is CacheDataSource
    )
  }

  /**
   * Only the prefetcher fills the cache. The playback factory is built with a
   * null cache-write sink, which is what Media3 reads as `cacheIsReadOnly`, so
   * a body pulled all the way through it leaves nothing on disk.
   */
  @Test
  fun `a body read through the playback factory is not written to the cache`() {
    val body = ByteArray(4096) { it.toByte() }
    val url = "https://example.com/playback-read.mp3"
    val source = cache.wrapDataSourceFactory(FixedBodyDataSource.Factory(body))
      .createDataSource()

    source.open(DataSpec.Builder().setUri(url).setKey(url).build())
    val buffer = ByteArray(1024)
    var total = 0
    while (true) {
      val read = source.read(buffer, 0, buffer.size)
      if (read == -1) break
      total += read
    }
    source.close()

    assertEquals("the whole body reaches the caller", body.size, total)
    assertEquals("nothing is written to disk", 0L, cache.currentSizeBytes())
    assertFalse("and the url is not held as cached", cache.isFullyCached(url))
  }

  /** Serves a fixed body for any [DataSpec], honouring its position. */
  private class FixedBodyDataSource(private val body: ByteArray) : DataSource {
    class Factory(private val body: ByteArray) : DataSource.Factory {
      override fun createDataSource(): DataSource = FixedBodyDataSource(body)
    }

    private var uri: Uri? = null
    private var position = 0

    override fun open(dataSpec: DataSpec): Long {
      uri = dataSpec.uri
      position = dataSpec.position.toInt()
      return (body.size - position).toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
      if (position >= body.size) return -1
      val take = minOf(length, body.size - position)
      body.copyInto(buffer, offset, position, position + take)
      position += take
      return take
    }

    override fun getUri(): Uri? = uri
    override fun close() {}
    override fun addTransferListener(transferListener: TransferListener) {}
  }

  /**
   * Disabled means every read goes to the origin and nothing is written,
   * whatever the disk holds — the wrapper hands out the bare upstream source.
   * Decided per data source, so a change reaches the next item load.
   */
  @Test
  fun `the wrapped factory bypasses the cache while it does not serve playback`() {
    val upstream = DefaultHttpDataSource.Factory()
    val wrapped = cache.wrapDataSourceFactory(upstream)

    cache.servesPlayback = false
    val bypassed = wrapped.createDataSource()
    cache.servesPlayback = true
    val served = wrapped.createDataSource()

    assertTrue("disabled: the upstream source, no cache in front", bypassed !is CacheDataSource)
    assertTrue("re-enabled: the next source goes through the cache again", served is CacheDataSource)
  }

  @Test
  fun `release is safe to call before any usage`() {
    // Smoke test that release on a freshly constructed cache returns
    // the directory to a re-constructible state without throwing.
    cache.release()
    val secondCache = LookaheadCache(
      context = context(),
      maxSizeBytes = 1L * 1024L * 1024L,
      cacheDirName = cache.cacheDir.name
    )
    assertNotNull(secondCache.simpleCache)
    secondCache.release()
  }

  @Test
  fun `default cache directory resolves under context cacheDir`() {
    val defaultCache = LookaheadCache(
      context = context(),
      maxSizeBytes = 1L * 1024L * 1024L
    )
    try {
      assertEquals(
        LookaheadCache.DEFAULT_CACHE_DIR_NAME,
        defaultCache.cacheDir.name
      )
      assertEquals(
        context().cacheDir.absolutePath,
        defaultCache.cacheDir.parentFile?.absolutePath
      )
    } finally {
      defaultCache.release()
    }
  }

  @Test
  fun `default max size is 100 MB`() {
    // The default is what a consumer that omits `lookaheadCacheMaxSizeMb`
    // from `configure()` gets, so it is part of the public contract.
    assertEquals(
      "default disk budget",
      100L * 1024L * 1024L,
      LookaheadCache.DEFAULT_MAX_SIZE_BYTES
    )
  }

  @Test
  fun `maxBytesForMb multiplies before cast for sub-1MB requests`() {
    // Bare-cast-then-multiply would produce 0 (0.5.toLong() = 0,
    // then 0 * BYTES_PER_MB = 0). Multiply-first produces 524288
    // bytes, then floored to BYTES_PER_MB.
    assertEquals(LookaheadCache.BYTES_PER_MB, LookaheadCache.maxBytesForMb(0.5))
  }

  @Test
  fun `maxBytesForMb passes through requests at and above 1 MB`() {
    assertEquals(
      100L * LookaheadCache.BYTES_PER_MB,
      LookaheadCache.maxBytesForMb(100.0)
    )
    assertEquals(
      (1.5 * LookaheadCache.BYTES_PER_MB.toDouble()).toLong(),
      LookaheadCache.maxBytesForMb(1.5)
    )
  }

  @Test
  fun `maxBytesForMb floors zero and negative inputs to 1 MB`() {
    assertEquals(LookaheadCache.BYTES_PER_MB, LookaheadCache.maxBytesForMb(0.0))
    assertEquals(LookaheadCache.BYTES_PER_MB, LookaheadCache.maxBytesForMb(-10.0))
  }

  @Test
  fun `maxBytesForMb defends against non-finite inputs from JS bridge`() {
    // Number.NaN / Number.POSITIVE_INFINITY are reachable from JS;
    // raw Double.toLong on +Infinity returns Long.MAX_VALUE which
    // would request a multi-exabyte cache. Helper must coerce
    // non-finite inputs to BYTES_PER_MB. Mirrors iOS's
    // `LookaheadCache.maxBytes(forMb:)` defence.
    assertEquals(LookaheadCache.BYTES_PER_MB, LookaheadCache.maxBytesForMb(Double.NaN))
    assertEquals(
      LookaheadCache.BYTES_PER_MB,
      LookaheadCache.maxBytesForMb(Double.POSITIVE_INFINITY)
    )
    assertEquals(
      LookaheadCache.BYTES_PER_MB,
      LookaheadCache.maxBytesForMb(Double.NEGATIVE_INFINITY)
    )
  }
}
