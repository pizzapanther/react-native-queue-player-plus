package com.margelo.nitro.queueplayer

import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * runtime lookahead cache config + status surface
 * tests for [TrackPlayer]. Drives the `*Internal` helpers directly
 * (the Promise/Nitro wrappers can't be exercised from Robolectric;
 * the lifecycle test pattern mirrors here).
 *
 * Coverage:
 * - default config + status snapshot from a freshly-configured player
 * - setLookaheadCacheInternal: disable releases the writer, keeps the cache
 * - setLookaheadCacheInternal: enabled toggles the writer live (no configure)
 * - configure with cache disabled still builds the cache for a later enable
 * - cache size comes from PlayerConfig at configure (configure-time only)
 * - setLookaheadCacheInternal: lookaheadCount change updates writer live
 * - configure accepts the fifo eviction policy + a policy change rebuilds the cache
 * - clearLookaheadCacheInternal: tears cache state down
 * - getLookaheadCacheStatus: shape + computed fields
 * - re-configure with changed httpHeaders: writer rebuilt
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@UnstableApi
class TrackPlayerLookaheadConfigTest {

  private lateinit var player: TrackPlayer
  private lateinit var serviceHandle: PlaybackServiceTestHandle

  private fun context() = ApplicationProvider.getApplicationContext<android.content.Context>()

  @Before
  fun setUp() {
    player = TrackPlayer()
    serviceHandle = bindPlaybackServiceForTest(player, context())
  }

  @After
  fun tearDown() {
    player.destroyInternal()
    serviceHandle.destroy()
  }

  // --- Default config

  @Test
  fun `fresh player has default lookahead config — enabled, count 3`() {
    val cfg = player.lookaheadConfig
    assertEquals(true, cfg.enabled)
    assertEquals(3.0, cfg.lookaheadCount, 0.001)
  }

  @Test
  fun `cache size comes from PlayerConfig at configure and defaults to 100 MB`() {
    // Size is configure-time (PlayerConfig.lookaheadCacheMaxSizeMb), not a
    // runtime dimension. Unset → 100 MB default; set → reflected read-only.
    player.configureInternal(emptyConfig(), context())
    assertEquals(100.0, player.buildCacheStatusSnapshot().maxSizeMb, 0.001)

    player.destroyInternal()
    serviceHandle.destroy()
    serviceHandle = bindPlaybackServiceForTest(player, context())
    player.configureInternal(
      PlayerConfig(
        enableDiagnosticLogs = null,
        httpHeaders = null, userAgent = null, autoRetries = null, retryBackoffMs = null,
        networkTimeoutMs = null, audioContentType = null, audioCategoryOptions = null,
        voiceVocabularyDonationEnabled = null,
        allowsExternalPlayback = null,
        visualizationEnabled = null, clampSeekToBuffered = null,
        lookaheadCacheMaxSizeMb = 250.0, lookaheadCacheEvictionPolicy = null,
        progressUpdateIntervalMs = null, backgroundProgressUpdateIntervalMs = null, placeholderArtworkUri = null, skipToPreviousBehavior = null
      ),
      context()
    )
    assertEquals(250.0, player.buildCacheStatusSnapshot().maxSizeMb, 0.001)
  }

  @Test
  fun `configureInternal with default config builds the cache + writer`() {
    player.configureInternal(emptyConfig(), context())
    assertNotNull(player.lookaheadCache)
    assertNotNull(player.lookaheadCacheWriter)
  }

  @Test
  fun `configureInternal accepts the fifo eviction policy and builds the cache`() {
    player.configureInternal(configWithPolicy(EvictionPolicy.FIFO), context())
    assertNotNull(player.lookaheadCache)
  }

  @Test
  fun `changing the eviction policy at configure clears + rebuilds the cache`() {
    player.configureInternal(configWithPolicy(EvictionPolicy.FIFO), context())
    assertNotNull(player.lookaheadCache)
    // Production configure() runs destroyInternal() between configures; do the
    // same so this exercises the real reconfigure sequence.
    player.destroyInternal()
    serviceHandle.destroy()
    serviceHandle = bindPlaybackServiceForTest(player, context())
    // fifo → lru: the policy change releases the shared SimpleCache, deletes the
    // cache dir, and rebuilds under the new evictor without throwing.
    player.configureInternal(configWithPolicy(EvictionPolicy.LRU), context())
    assertNotNull(player.lookaheadCache)
  }

  private fun configWithPolicy(policy: EvictionPolicy, maxSizeMb: Double? = null) = PlayerConfig(
    enableDiagnosticLogs = null,
    httpHeaders = null, userAgent = null, autoRetries = null, retryBackoffMs = null,
    networkTimeoutMs = null, audioContentType = null, audioCategoryOptions = null,
    voiceVocabularyDonationEnabled = null,
    allowsExternalPlayback = null,
    visualizationEnabled = null, clampSeekToBuffered = null, lookaheadCacheMaxSizeMb = maxSizeMb,
    lookaheadCacheEvictionPolicy = policy,
    progressUpdateIntervalMs = null, backgroundProgressUpdateIntervalMs = null, placeholderArtworkUri = null, skipToPreviousBehavior = null
  )

  @Test
  fun `re-configuring with an unchanged policy keeps the cache`() {
    // The policy comparison reads what the live cache was built with, not the
    // in-memory config, which starts at the LRU default on every player.
    player.configureInternal(configWithPolicy(EvictionPolicy.FIFO), context())
    val first = player.lookaheadCache?.simpleCache
    assertNotNull(first)

    player.destroyInternal()
    serviceHandle.destroy()
    player = TrackPlayer()
    serviceHandle = bindPlaybackServiceForTest(player, context())
    player.configureInternal(configWithPolicy(EvictionPolicy.FIFO), context())

    assertSame(
      "an unchanged policy must not discard the shared cache",
      first, player.lookaheadCache?.simpleCache
    )
  }

  /**
   * With no live cache in the process, the policy the directory was built
   * with is read from prefs. A previous process is stood in for by a cache
   * built and populated here, then released from the process-wide map; the
   * span it holds is what a discard removes and a kept cache still serves.
   */
  @Test
  fun `a policy recorded by a previous process is read from disk`() {
    val ctx = context()
    val url = "https://example.com/previous.m4a"
    seedCacheFromAPreviousProcess(url, EvictionPolicy.FIFO)

    player.configureInternal(configWithPolicy(EvictionPolicy.LRU), ctx)

    assertTrue(
      "the recorded policy differs from the requested one, so the old cache is discarded",
      player.lookaheadCache!!.simpleCache.getCachedSpans(url).isEmpty())
  }

  @Test
  fun `a policy recorded by a previous process that matches keeps the cache`() {
    val ctx = context()
    val url = "https://example.com/previous.m4a"
    seedCacheFromAPreviousProcess(url, EvictionPolicy.FIFO)

    player.configureInternal(configWithPolicy(EvictionPolicy.FIFO), ctx)

    assertFalse(
      "an unchanged policy keeps the cache and its spans",
      player.lookaheadCache!!.simpleCache.getCachedSpans(url).isEmpty())
  }

  /**
   * Build a cache under [policy], commit one span for [url], then leave the
   * process with no live instance and prefs recording [policy] — the state
   * the next launch finds.
   */
  private fun seedCacheFromAPreviousProcess(url: String, policy: EvictionPolicy) {
    val ctx = context()
    player.configureInternal(configWithPolicy(policy), ctx)
    player.setLookaheadCacheInternal(
      LookaheadCacheConfig(enabled = false, lookaheadCount = 0.0, allowsCellularAccess = null)
    )
    writeSpansAndListSurvivors(listOf(url))
    val dir = player.lookaheadCache!!.cacheDir
    player.destroyInternal()
    serviceHandle.destroy()
    LookaheadCache.releaseSharedInstance(dir)
    ctx.getSharedPreferences("rnqp_lookahead_cache", android.content.Context.MODE_PRIVATE)
      .edit().putString("eviction_policy", policy.name).commit()
    player = TrackPlayer()
    serviceHandle = bindPlaybackServiceForTest(player, ctx)
  }

  private fun track(id: String) = TrackItem(
    id = id, url = "https://example.com/$id.m4a",
    title = null, artist = null, album = null,
    duration = null, artworkUrl = null, extras = null,
    replayGainTrackGain = null, replayGainTrackPeak = null,
    replayGainAlbumGain = null, replayGainAlbumPeak = null
  )

  // --- cache protection window

  /**
   * The window is observed through the evictor: five 400 KB tracks against a
   * 1 MB budget under FIFO leave exactly the protected ones — the one behind the
   * playhead, the playing one and `lookaheadCount` ahead — on disk.
   */
  @Test
  fun `the protection window keeps the playing track and its neighbours through eviction`() {
    player.configureInternal(configWithPolicy(EvictionPolicy.FIFO, maxSizeMb = 1.0), context())
    // The window is refreshed whether or not prefetch is enabled; disabled,
    // the writer fetches nothing and the evictor is the only thing that
    // decides which spans survive.
    player.setLookaheadCacheInternal(
      LookaheadCacheConfig(enabled = false, lookaheadCount = 1.0, allowsCellularAccess = null)
    )
    player.setQueueInternal((1..5).map { track("t$it") }, startAtIndex = 2)

    val survivors = writeSpansAndListSurvivors((1..5).map { "https://example.com/t$it.m4a" })

    assertEquals(
      "t1 is the oldest unprotected entry and t5 lands over budget with nothing else to take",
      listOf("https://example.com/t2.m4a", "https://example.com/t3.m4a", "https://example.com/t4.m4a"),
      survivors)
  }

  @Test
  fun `an out-of-range lookahead count clamps the protection window`() {
    // lookaheadCount crosses from JS as a Double. The window drops non-finite
    // values before any `toInt()`, and clamps a finite one to the queue and
    // the protected-ahead ceiling before it is added to the index — so every
    // one of these yields a well-formed set rather than a thrown subList.
    player.configureInternal(configWithPolicy(EvictionPolicy.FIFO, maxSizeMb = 1.0), context())
    player.setLookaheadCacheInternal(
      LookaheadCacheConfig(enabled = false, lookaheadCount = 0.0, allowsCellularAccess = null)
    )
    player.setQueueInternal((1..5).map { track("t$it") }, startAtIndex = 2)

    listOf(Double.POSITIVE_INFINITY, Double.NaN, Double.MAX_VALUE, -1.0).forEach { count ->
      player.setLookaheadCacheInternal(
        LookaheadCacheConfig(enabled = false, lookaheadCount = count, allowsCellularAccess = null)
      )
    }
    // -1.0 was applied last: a count of zero ahead protects t2 and t3 only.
    val survivors = writeSpansAndListSurvivors((1..5).map { "https://example.com/t$it.m4a" })

    assertEquals(listOf("https://example.com/t2.m4a", "https://example.com/t3.m4a"), survivors)
  }

  /** Commit one 400 KB span per url in order and return the urls still cached. */
  private fun writeSpansAndListSurvivors(urls: List<String>): List<String> {
    val cache = player.lookaheadCache!!.simpleCache
    val spanBytes = 400L * 1024
    for (url in urls) {
      // The write lock a CacheDataSink would hold: startFile requires it.
      val hole = cache.startReadWrite(url, 0, spanBytes)
      val file = cache.startFile(url, 0, spanBytes)
      file.writeBytes(ByteArray(spanBytes.toInt()))
      cache.commitFile(file, spanBytes)
      cache.releaseHoleSpan(hole)
    }
    return urls.filter { cache.getCachedSpans(it).isNotEmpty() }
  }

  // --- setLookaheadCacheInternal

  @Test
  fun `setLookaheadCacheInternal disable tears down writer and stops serving reads`() {
    // Disabling stops new prefetches and routes the player's reads to the
    // origin; the cache instance stays alive (one per directory) and is
    // released on the next destroy() / configure() cycle.
    player.configureInternal(emptyConfig(), context())
    val cacheBeforeDisable = player.lookaheadCache
    assertNotNull(cacheBeforeDisable)
    assertNotNull(player.lookaheadCacheWriter)
    assertTrue("enabled: playback reads go through the cache", cacheBeforeDisable!!.servesPlayback)

    player.setLookaheadCacheInternal(
      LookaheadCacheConfig(
        enabled = false, lookaheadCount = 3.0,
        allowsCellularAccess = null
      )
    )

    assertSame("the cache instance stays alive", cacheBeforeDisable, player.lookaheadCache)
    assertNull("writer torn down — no new prefetches", player.lookaheadCacheWriter)
    assertFalse("disabled: playback reads go to the origin", cacheBeforeDisable.servesPlayback)
    assertEquals(false, player.lookaheadConfig.enabled)

    player.setLookaheadCacheInternal(
      LookaheadCacheConfig(enabled = true, lookaheadCount = 3.0, allowsCellularAccess = null)
    )
    assertTrue("re-enabled: reads go through the cache again", cacheBeforeDisable.servesPlayback)
  }

  // --- allowsCellularAccess

  @Test
  fun `configure builds the writer permitting cellular by default`() {
    player.configureInternal(emptyConfig(), context())

    assertEquals(true, player.lookaheadCacheWriter?.allowsCellularAccess)
  }

  @Test
  fun `configure carries a non-default cellular policy into the writer`() {
    player.lookaheadConfig = LookaheadCacheConfig(
      enabled = true, lookaheadCount = 3.0, allowsCellularAccess = false
    )

    player.configureInternal(emptyConfig(), context())

    assertEquals(false, player.lookaheadCacheWriter?.allowsCellularAccess)
  }

  @Test
  fun `configure starts a transport monitor and destroy stops it`() {
    player.configureInternal(emptyConfig(), context())
    assertNotNull(player.cellularTransportMonitor)

    player.destroyInternal()

    assertNull(player.cellularTransportMonitor)
  }

  @Test
  fun `setLookaheadCacheInternal applies allowsCellularAccess to the live writer`() {
    player.configureInternal(emptyConfig(), context())
    val writer = player.lookaheadCacheWriter
    assertNotNull(writer)

    player.setLookaheadCacheInternal(
      LookaheadCacheConfig(
        enabled = true, lookaheadCount = 3.0, allowsCellularAccess = false
      )
    )

    assertSame("the writer is reused — only its policy changes", writer, player.lookaheadCacheWriter)
    assertEquals(false, writer?.allowsCellularAccess)

    player.setLookaheadCacheInternal(
      LookaheadCacheConfig(
        enabled = true, lookaheadCount = 3.0, allowsCellularAccess = true
      )
    )

    assertEquals(true, writer?.allowsCellularAccess)
  }

  @Test
  fun `a writer rebuilt by an enable flip carries the current cellular policy`() {
    player.configureInternal(emptyConfig(), context())
    player.setLookaheadCacheInternal(
      LookaheadCacheConfig(
        enabled = false, lookaheadCount = 3.0, allowsCellularAccess = false
      )
    )
    assertNull(player.lookaheadCacheWriter)

    player.setLookaheadCacheInternal(
      LookaheadCacheConfig(
        enabled = true, lookaheadCount = 3.0, allowsCellularAccess = false
      )
    )

    assertEquals(false, player.lookaheadCacheWriter?.allowsCellularAccess)
  }

  @Test
  fun `setLookaheadCacheInternal enabled toggles the writer at runtime without configure`() {
    // Enable/disable is a runtime switch: the cache is built at configure()
    // regardless of enabled (its size is fixed at construction), so toggling
    // enabled only builds/releases the writer against that existing cache — no
    // configure() cycle needed. The cache instance is stable across toggles.
    player.configureInternal(emptyConfig(), context())
    val cacheAtConfigure = player.lookaheadCache
    assertNotNull(cacheAtConfigure)
    assertNotNull("configure builds the writer while enabled", player.lookaheadCacheWriter)

    // Disable → writer released, cache kept for reads.
    player.setLookaheadCacheInternal(
      LookaheadCacheConfig(
        enabled = false, lookaheadCount = 3.0,
        allowsCellularAccess = null
      )
    )
    assertNull("disable releases the writer", player.lookaheadCacheWriter)
    assertSame("disable keeps the cache instance", cacheAtConfigure, player.lookaheadCache)

    // Re-enable → writer rebuilt at runtime, same cache, no configure().
    player.setLookaheadCacheInternal(
      LookaheadCacheConfig(
        enabled = true, lookaheadCount = 5.0,
        allowsCellularAccess = null
      )
    )
    assertNotNull(
      "re-enable rebuilds the writer at runtime — no configure() needed",
      player.lookaheadCacheWriter
    )
    assertSame(
      "re-enable reuses the same cache instance",
      cacheAtConfigure, player.lookaheadCache
    )
    assertEquals(5, player.lookaheadCacheWriter?.defaultLookaheadCount)
    assertEquals(true, player.lookaheadConfig.enabled)

    // Toggle off again → writer released once more (clean repeatable switch).
    player.setLookaheadCacheInternal(
      LookaheadCacheConfig(
        enabled = false, lookaheadCount = 5.0,
        allowsCellularAccess = null
      )
    )
    assertNull("second disable releases the writer again", player.lookaheadCacheWriter)
  }

  @Test
  fun `configure with cache disabled still builds the cache so runtime enable works`() {
    // The exact runtime-enable scenario: a consumer configures once at app init
    // with caching off, then turns it on later. The cache must exist up front
    // (built even when disabled) so the runtime enable can attach a writer.
    player.setLookaheadCacheInternal(
      LookaheadCacheConfig(
        enabled = false, lookaheadCount = 3.0,
        allowsCellularAccess = null
      )
    )
    player.configureInternal(emptyConfig(), context())
    assertNotNull("cache is built even when disabled at configure", player.lookaheadCache)
    assertNull("no writer while disabled", player.lookaheadCacheWriter)
    assertFalse(
      "built disabled: the player's reads go to the origin",
      player.lookaheadCache!!.servesPlayback)

    player.setLookaheadCacheInternal(
      LookaheadCacheConfig(
        enabled = true, lookaheadCount = 4.0,
        allowsCellularAccess = null
      )
    )
    assertNotNull(
      "runtime enable after a disabled configure builds the writer",
      player.lookaheadCacheWriter
    )
    assertEquals(4, player.lookaheadCacheWriter?.defaultLookaheadCount)
  }

  @Test
  fun `setLookaheadCacheInternal lookaheadCount change updates writer live without rebuild`() {
    player.configureInternal(emptyConfig(), context())
    val firstWriter = player.lookaheadCacheWriter
    val firstCache = player.lookaheadCache

    player.setLookaheadCacheInternal(
      LookaheadCacheConfig(
        enabled = true, lookaheadCount = 7.0,
        allowsCellularAccess = null
      )
    )

    assertSame(
      "count-only change must keep the same writer instance (no resize, no enable change)",
      firstWriter, player.lookaheadCacheWriter
    )
    assertSame(
      "count-only change must keep the same cache instance",
      firstCache, player.lookaheadCache
    )
    assertEquals(7, player.lookaheadCacheWriter?.defaultLookaheadCount)
  }

  // --- clearLookaheadCacheInternal

  @Test
  fun `clearLookaheadCacheInternal cancels writer + wipes cache index`() {
    player.configureInternal(emptyConfig(), context())
    val writer = player.lookaheadCacheWriter
    assertNotNull(writer)

    runBlocking { player.clearLookaheadCacheInternal()?.join() }

    assertNotNull("cache stays — clear is wipe, not destroy", player.lookaheadCache)
    assertNotNull("writer stays — clear is wipe, not destroy", player.lookaheadCacheWriter)
    assertNull("no in-flight prefetch immediately after clear", writer?.currentlyDownloadingUrl)
    assertEquals(
      "currentSizeBytes must drop to 0 after clear",
      0L, player.lookaheadCache?.currentSizeBytes()
    )
  }

  // --- getLookaheadCacheStatus snapshot shape

  @Test
  fun `getLookaheadCacheStatus on a fresh player reports defaults`() {
    player.configureInternal(emptyConfig(), context())
    val status = player.buildCacheStatusSnapshot()
    assertEquals(true, status.enabled)
    assertEquals(0.0, status.currentSizeMb, 0.001)
    assertEquals(100.0, status.maxSizeMb, 0.001)
    assertEquals(0.0, status.tracksFullyCached, 0.001)
    assertEquals(0, status.currentlyCaching.size)
  }

  @Test
  fun `getLookaheadCacheStatus reports disabled state`() {
    player.configureInternal(emptyConfig(), context())
    player.setLookaheadCacheInternal(
      LookaheadCacheConfig(
        enabled = false, lookaheadCount = 0.0,
        allowsCellularAccess = null
      )
    )
    val status = player.buildCacheStatusSnapshot()
    assertEquals(false, status.enabled)
    assertEquals(0.0, status.currentSizeMb, 0.001)
    // maxSizeMb is the configure-time budget (100 MB default), reported
    // regardless of enabled — the cache exists, it just isn't prefetching.
    assertEquals(100.0, status.maxSizeMb, 0.001)
  }

  // --- re-configure with changed headers refreshes writer

  @Test
  fun `re-configure with changed httpHeaders rebuilds the player (writer + cache)`() {
    player.configureInternal(
      PlayerConfig(enableDiagnosticLogs = null, httpHeaders = mapOf("X-Auth" to "token-1"), userAgent = null, autoRetries = null, retryBackoffMs = null, networkTimeoutMs = null, audioContentType = null, audioCategoryOptions = null, voiceVocabularyDonationEnabled = null, allowsExternalPlayback = null,  visualizationEnabled = null, clampSeekToBuffered = null, lookaheadCacheMaxSizeMb = null, lookaheadCacheEvictionPolicy = null, progressUpdateIntervalMs = null, backgroundProgressUpdateIntervalMs = null, placeholderArtworkUri = null, skipToPreviousBehavior = null),
      context()
    )
    val firstWriter = player.lookaheadCacheWriter
    val firstCache = player.lookaheadCache
    assertNotNull(firstWriter)
    assertNotNull(firstCache)

    player.configureInternal(
      PlayerConfig(enableDiagnosticLogs = null, httpHeaders = mapOf("X-Auth" to "token-2"), userAgent = null, autoRetries = null, retryBackoffMs = null, networkTimeoutMs = null, audioContentType = null, audioCategoryOptions = null, voiceVocabularyDonationEnabled = null, allowsExternalPlayback = null,  visualizationEnabled = null, clampSeekToBuffered = null, lookaheadCacheMaxSizeMb = null, lookaheadCacheEvictionPolicy = null, progressUpdateIntervalMs = null, backgroundProgressUpdateIntervalMs = null, placeholderArtworkUri = null, skipToPreviousBehavior = null),
      context()
    )

    // A header change on re-configure rebuilds the entire player (and
    // the cache + writer with it), not just the writer, so the player's
    // MediaSource.Factory and every subsequent playback request carry
    // the new auth. The on-disk cache directory is
    // preserved across the rebuild — `SimpleCache.release()` only
    // frees the in-memory instance + file lock; entries stay on
    // disk and the next `LookaheadCache(...)` constructor reads
    // them back.
    assertNotEquals(
      "header change on re-configure must rebuild the writer",
      firstWriter, player.lookaheadCacheWriter
    )
    assertNotSame(
      "header change on re-configure rebuilds the cache too — on-disk entries persist via SimpleCache",
      firstCache, player.lookaheadCache
    )
  }

  @Test
  fun `re-configure rebuilds the writer per the auto-destroy contract`() {
    // Per the universal `configure()` auto-destroy contract every
    // call destroys then reconstructs — there is no "skip rebuild
    // on same headers" optimisation any more. The test asserts the
    // new contract: each public configure cycle (destroy + rebuild,
    // simulated here by `destroyInternal` between
    // `configureInternal` calls) yields a fresh writer instance,
    // even when headers are unchanged.
    val cfg = PlayerConfig(enableDiagnosticLogs = null, httpHeaders = mapOf("X-Auth" to "token"), userAgent = "rnqp/test", autoRetries = null, retryBackoffMs = null, networkTimeoutMs = null, audioContentType = null, audioCategoryOptions = null, voiceVocabularyDonationEnabled = null, allowsExternalPlayback = null,  visualizationEnabled = null, clampSeekToBuffered = null, lookaheadCacheMaxSizeMb = null, lookaheadCacheEvictionPolicy = null, progressUpdateIntervalMs = null, backgroundProgressUpdateIntervalMs = null, placeholderArtworkUri = null, skipToPreviousBehavior = null)
    player.configureInternal(cfg, context())
    val firstWriter = player.lookaheadCacheWriter

    player.destroyInternal()
    bindPlaybackServiceForTest(player, context())
    player.configureInternal(cfg, context())

    assertNotSame(
      "auto-destroy contract: every configure cycle rebuilds the writer",
      firstWriter, player.lookaheadCacheWriter
    )
  }

  // --- End-to-end

  @Test
  fun `disable then re-enable then destroy then configure rebuilds with new state`() {
    // Full lifecycle exercise: configure (default) → disable (writer tears
    // down) → re-enable with a new count (writer rebuilds live) → destroy →
    // configure (state persists across the cycle).
    player.configureInternal(emptyConfig(), context())
    val firstCache = player.lookaheadCache
    val firstWriter = player.lookaheadCacheWriter
    assertNotNull(firstCache)
    assertNotNull(firstWriter)

    // Step 1: disable. Writer torn down, cache stays.
    player.setLookaheadCacheInternal(
      LookaheadCacheConfig(
        enabled = false, lookaheadCount = 3.0,
        allowsCellularAccess = null
      )
    )
    assertSame(firstCache, player.lookaheadCache)
    assertNull(player.lookaheadCacheWriter)

    // Step 2: re-enable with a new count. Writer rebuilds live against the
    // same cache instance.
    player.setLookaheadCacheInternal(
      LookaheadCacheConfig(
        enabled = true, lookaheadCount = 7.0,
        allowsCellularAccess = null
      )
    )
    assertSame("re-enable reuses the same cache instance", firstCache, player.lookaheadCache)
    assertNotNull("re-enable rebuilds the writer at runtime", player.lookaheadCacheWriter)
    assertEquals(7, player.lookaheadCacheWriter?.defaultLookaheadCount)

    // Step 3: destroy → configure cycle applies the deferred state.
    player.destroyInternal()
    serviceHandle.destroy()
    serviceHandle = bindPlaybackServiceForTest(player, context())
    player.configureInternal(emptyConfig(), context())

    assertNotNull(player.lookaheadCache)
    assertNotNull(player.lookaheadCacheWriter)
    assertEquals(7, player.lookaheadCacheWriter?.defaultLookaheadCount)
    assertEquals(true, player.lookaheadConfig.enabled)
    assertEquals(7.0, player.lookaheadConfig.lookaheadCount, 0.001)
  }

  @Test
  fun `clearLookaheadCacheInternal then status snapshot reports zero size + empty currentlyCaching`() {
    player.configureInternal(emptyConfig(), context())
    runBlocking { player.clearLookaheadCacheInternal()?.join() }

    val status = player.buildCacheStatusSnapshot()
    assertEquals(true, status.enabled)
    assertEquals(0.0, status.currentSizeMb, 0.001)
    assertEquals(0.0, status.tracksFullyCached, 0.001)
    assertEquals(0, status.currentlyCaching.size)
  }

  // --- Helpers

  private fun emptyConfig() = PlayerConfig(enableDiagnosticLogs = null, httpHeaders = null, userAgent = null, autoRetries = null, retryBackoffMs = null, networkTimeoutMs = null, audioContentType = null, audioCategoryOptions = null, voiceVocabularyDonationEnabled = null, allowsExternalPlayback = null,  visualizationEnabled = null, clampSeekToBuffered = null, lookaheadCacheMaxSizeMb = null, lookaheadCacheEvictionPolicy = null, progressUpdateIntervalMs = null, backgroundProgressUpdateIntervalMs = null, placeholderArtworkUri = null, skipToPreviousBehavior = null)
}
