package com.margelo.nitro.queueplayer

import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * lifecycle tests for the Kotlin [TrackPlayer].
 *
 * Exercises `configureInternal` / `destroyInternal` directly. The
 * public `configure()` / `destroy()` Promise-returning overloads
 * can't be tested from JVM units because Nitro's `Promise`
 * constructor calls `initHybrid()` → JNI; the native library isn't
 * loaded in the Robolectric runtime. This mirrors iOS
   * @_spi(QueuePlayerTests) pattern: expose primitive-arg internal
 * helpers, run them directly, leave the Promise adapter untouched
 * (it's a thin body that hops to main + calls the helper).
 *
 * @Config sdk=34 — Robolectric 4.11.1's max supported SDK, while
 * the library's compileSdkVersion is 36.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@UnstableApi
class TrackPlayerLifecycleTest {

  private lateinit var player: TrackPlayer
  private lateinit var serviceHandle: PlaybackServiceTestHandle

  private fun context() = ApplicationProvider.getApplicationContext<android.content.Context>()

  @Before
  fun setUp() {
    player = TrackPlayer()
    serviceHandle = bindPlaybackServiceForTest(player, context())
  }

  /**
   * Every test guarantees the ExoPlayer (if any) is released, so a
   * mid-test assertion failure doesn't leak a player into the next
   * Robolectric run. `destroyInternal` is safe on an already-null
   * player. The service controller is destroyed afterwards so the
   * MediaSession session-id slot is freed for the next test.
   */
  @After
  fun tearDown() {
    player.destroyInternal()
    serviceHandle.destroy()
  }

  @Test
  fun `a failure on a track that is not playing is reported, not swallowed`() {
    seedThreeTrackQueue()

    var reported = 0
    val dispose = player.onError { reported += 1 }
    val states = mutableListOf<PlayerState>()
    val disposeState = player.onStateChange { state, _ -> states.add(state) }
    // Index 2 is not the current track — the shape a standby preroll failure
    // arrives in. The engine argument is not read by the handler.
    player.onError(
      serviceHandle.engine,
      androidx.media3.common.PlaybackException(
        "boom", null,
        androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED),
      2)
    dispose()
    disposeState()

    assertEquals("the failure is surfaced rather than swallowed", 1, reported)
    assertFalse(
      "the state is the current track's, which is still playing",
      states.contains(PlayerState.ERROR))
  }

  /**
   * A repeat of the same failure while the attempt is pending stays inside the
   * library.
   */
  @Test
  fun `an echo of the pending failure is not reported`() {
    seedThreeTrackQueue()

    var reported = 0
    val dispose = player.onError { reported += 1 }
    player.onError(serviceHandle.engine, networkFailure(), 0)
    player.onError(serviceHandle.engine, networkFailure(), 0)
    dispose()

    assertEquals("the pending attempt owns both fires", 0, reported)
  }

  /** A failure after the attempt has run is the consumer's to see. */
  @Test
  fun `a failure after the attempt is reported`() {
    seedThreeTrackQueue()

    var reported = 0
    val dispose = player.onError { reported += 1 }
    player.onError(serviceHandle.engine, networkFailure(), 0)
    player.retryFailedItem(0)
    player.onError(serviceHandle.engine, networkFailure(), 0)
    dispose()

    assertEquals("the attempt was spent and the track is still broken", 1, reported)
  }

  /**
   * Inserting ahead of a track moves it, and its retry budget moves with it.
   * A budget of two with one attempt spent is the one value that tells the
   * moved track apart from both a fresh entry (two left) and a dropped one
   * (none left) at its new index.
   */
  @Test
  fun `a track keeps its retry budget when a track is inserted before it`() {
    seedThreeTrackQueue(autoRetries = 2.0)
    player.onError(serviceHandle.engine, networkFailure(), 0)
    player.retryFailedItem(0)

    player.addToQueueInternal(listOf(track("https://example.com/new.mp3")), insertBefore = 0)
    assertEquals("the playing track moved to index 1", 1, player.currentTrackIndex)

    var reported = 0
    val dispose = player.onError { reported += 1 }
    player.onError(serviceHandle.engine, networkFailure(), 1)
    assertEquals(
      "one attempt is left at the new index, so the failure is retried silently; "
        + "a dropped budget would have reported it",
      0, reported)
    player.retryFailedItem(1)
    player.onError(serviceHandle.engine, networkFailure(), 1)
    dispose()

    assertEquals(
      "the second attempt was spent, so this failure is reported; "
        + "a fresh budget of two would still be silent",
      1, reported)
  }

  /**
   * A user's retry withdraws the automatic attempt scheduled for the same
   * track: left posted, it would fire after the backoff and rebuild the track
   * a second time.
   */
  @Test
  fun `a retry withdraws the scheduled automatic attempt`() {
    player.configureInternal(config(retryBackoffMs = 500.0), context())
    player.setQueueInternal((0 until 3).map { track("https://example.com/$it.mp3") })
    player.currentTrackIndex = 0
    player.onError(serviceHandle.engine, networkFailure(), 0)

    player.retryFailedItem(0)
    val looper = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
    looper.idle()
    // The retry rebuilt the item once (a remove and an add on the playlist);
    // whatever the looper still holds for the backoff must not rebuild again.
    // Only playlist changes count: the playback thread reports its own
    // source refresh after the prepare, on its own schedule.
    var timelineChanges = 0
    val listener = object : androidx.media3.common.Player.Listener {
      override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
        if (reason == androidx.media3.common.Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
          timelineChanges += 1
        }
      }
    }
    player.player!!.addListener(listener)
    looper.idleFor(java.time.Duration.ofMillis(1_000))
    player.player!!.removeListener(listener)

    assertEquals(
      "the automatic attempt fired after the user's retry and rebuilt the track again",
      0, timelineChanges)
  }

  /**
   * The track that ends up at a position after a queue change reports its own
   * failure instead of being taken for an echo of the old queue's attempt.
   */
  @Test
  fun `a failure on the replacement queue is not taken for an echo of the old one`() {
    seedThreeTrackQueue()
    player.onError(serviceHandle.engine, networkFailure(), 0)

    // The replacement queue gets no automatic attempt, so a failure on it can
    // only be suppressed by a marker left over from the queue before it.
    player.config = config(autoRetries = 0.0)
    player.setQueueInternal((0 until 2).map {
      track("https://example.com/next-$it.mp3")
    })
    player.currentTrackIndex = 0

    var reported = 0
    val dispose = player.onError { reported += 1 }
    player.onError(serviceHandle.engine, networkFailure(), 0)
    dispose()

    assertEquals("the attempt belonged to a queue that is gone", 1, reported)
  }

  private fun config(
    httpHeaders: Map<String, String>? = null,
    userAgent: String? = null,
    autoRetries: Double? = null,
    retryBackoffMs: Double? = null,
  ) = PlayerConfig(
    enableDiagnosticLogs = null,
    httpHeaders = httpHeaders, userAgent = userAgent, autoRetries = autoRetries,
    retryBackoffMs = retryBackoffMs, networkTimeoutMs = null, audioContentType = null,
    audioCategoryOptions = null, voiceVocabularyDonationEnabled = null, allowsExternalPlayback = null,
    visualizationEnabled = null, clampSeekToBuffered = null, lookaheadCacheMaxSizeMb = null,
    lookaheadCacheEvictionPolicy = null, progressUpdateIntervalMs = null,
    backgroundProgressUpdateIntervalMs = null, placeholderArtworkUri = null,
    skipToPreviousBehavior = null)

  private fun track(url: String) = TrackItem(
    id = null, url = url, title = null, artist = null, album = null, duration = null,
    artworkUrl = null, extras = null, replayGainTrackGain = null, replayGainTrackPeak = null,
    replayGainAlbumGain = null, replayGainAlbumPeak = null)

  private fun networkFailure() = androidx.media3.common.PlaybackException(
    "boom", null,
    androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)

  /**
   * Under crossfade the standby leg prerolls the next track, so a broken one
   * fails while the leading track is still playing and is surfaced there. The
   * engine then seats it and it fails again as the current track — the listener
   * asked for one thing and must be told once.
   */
  @Test
  fun `a track reported while it was prerolling does not report again when it is seated`() {
    seedThreeTrackQueue(autoRetries = 0.0)

    var reported = 0
    val dispose = player.onError { reported += 1 }

    // The standby leg prerolling track 1 fails while track 0 is playing.
    player.onError(serviceHandle.engine, fatalFailure(), 1)
    assertEquals("the listener is told their next track is broken", 1, reported)

    // The fade begins, so track 1 is the one being heard, and it fails again as
    // the current track.
    player.onCrossfadeBegin(serviceHandle.engine, 1)
    assertEquals("the fade seats the track that was prerolling", 1, player.currentTrackIndex)
    player.onError(serviceHandle.engine, fatalFailure(), 1)
    dispose()

    assertEquals("and not told a second time about the same track", 1, reported)
  }

  /**
   * A shuffle renumbers every position, so a record against the pre-shuffle
   * current index names a different track afterwards. Nothing is carried.
   */
  @Test
  fun `a shuffle leaves no track marked as stopped`() {
    seedThreeTrackQueue(autoRetries = 0.0)
    player.onError(serviceHandle.engine, fatalFailure(), 0)

    player.shuffleQueueInternal()

    assertEquals("a shuffle seats position 0", 0, player.currentTrackIndex)
    // A stop standing against the current track silences every other track's
    // failure, so a report for one of them is what shows the set is empty.
    var reported = 0
    val dispose = player.onError { reported += 1 }
    player.onError(serviceHandle.engine, fatalFailure(), 1)
    dispose()

    assertEquals("no track is holding the queue after a shuffle", 1, reported)
  }

  /**
   * `-1` means "no track", so a failure that carries no index — an engine that
   * reports without one, or a queue that has been cleared — must not enter the
   * set. A member equal to `currentTrackIndex` is what "stopped" means, and at
   * `-1` that would read as stopped and silence every later failure.
   */
  @Test
  fun `a failure with no track does not mark the queue as stopped`() {
    player.configureInternal(config(autoRetries = 0.0), context())
    player.setQueueInternal(emptyList())
    assertEquals("an empty queue seats no track", -1, player.currentTrackIndex)

    // An engine that reports without an index — AirPlay does — while the queue
    // is empty. There is no position to record.
    player.onError(serviceHandle.engine, fatalFailure())

    // The add empties the set and restores the current track's entry, so with
    // -1 a member that restore is what carries it forward. It leaves the index
    // at -1 by design, which is what keeps it a member.
    player.addToQueueInternal(
      (0 until 2).map { track("https://example.com/$it.mp3") }, insertBefore = 0)
    assertEquals("an add into an empty queue seats nothing", -1, player.currentTrackIndex)

    var reported = 0
    val dispose = player.onError { reported += 1 }
    player.onError(serviceHandle.engine, fatalFailure(), 1)
    dispose()

    assertEquals(
      "a track's failure still reaches the listener — nothing is holding the queue",
      1, reported)
  }

  private fun fatalFailure() = androidx.media3.common.PlaybackException(
    "boom", null,
    androidx.media3.common.PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE)

  private fun seedThreeTrackQueue(autoRetries: Double? = null) {
    player.configureInternal(config(autoRetries = autoRetries), context())
    player.setQueueInternal((0 until 3).map { track("https://example.com/$it.mp3") })
    player.currentTrackIndex = 0
  }

  @Test
  fun `the automatic retry budget is one attempt by default`() {
    assertEquals(1, player.effectiveAutoRetries())
  }

  @Test
  fun `the consumer can ask for none or more`() {
    player.config = config(autoRetries = 0.0)
    assertEquals(0, player.effectiveAutoRetries())
    player.config = config(autoRetries = 4.0)
    assertEquals(4, player.effectiveAutoRetries())
    player.config = config(autoRetries = 99.0)
    assertEquals(5, player.effectiveAutoRetries())
    player.config = config()
    assertEquals(1, player.effectiveAutoRetries())
  }

  @Test
  fun `configureInternal builds an ExoPlayer and stores config`() {
    assertNull("player not yet built before configure", player.player)

    val config = config(httpHeaders = mapOf("X-Foo" to "bar"), userAgent = "rnqp-test/0.0")
    player.configureInternal(config, context())

    assertNotNull("configure must build ExoPlayer", player.player)
    assertEquals(config, player.config)
    assertEquals(-1, player.currentTrackIndex)
    assertTrue(player.tracks.isEmpty())
    assertEquals(RepeatMode.OFF, player.repeatModeState)
    assertNotNull(
      "configure must build the lookahead cache",
      player.lookaheadCache
    )
  }

  @Test
  fun `configure destroys then rebuilds — second call yields a fresh ExoPlayer`() {
    // Universal `configure()` auto-destroy contract: every call to
    // the public configure tears down any prior state before
    // reinitialising. The internal `configureInternal` entry point is
    // purely constructive (assumes `player == null` on entry); the
    // public path owns the destroy step. This test drives the
    // destroy + reconstruct shape directly to mirror what
    // `configure()` does end-to-end.
    val empty = config()

    player.configureInternal(empty, context())
    val firstInstance = player.player
    val firstCache = player.lookaheadCache
    assertNotNull(firstInstance)
    assertNotNull(firstCache)

    player.destroyInternal()
    bindPlaybackServiceForTest(player, context())
    player.configureInternal(empty, context())

    assertNotNull("second configure must produce a fresh ExoPlayer", player.player)
    assertNotEquals(
      "second configure must not reuse the prior ExoPlayer",
      firstInstance, player.player
    )
    // SimpleCache enforces a one-instance-per-directory invariant.
    // The destroy + reconstruct path releases the in-memory cache
    // (preserving on-disk entries) so the second open succeeds with
    // a fresh `SimpleCache` instance pointing at the same dir.
    assertNotNull("second configure must produce a fresh lookahead cache", player.lookaheadCache)
    assertNotEquals(
      "second configure must rebuild the lookahead cache",
      firstCache, player.lookaheadCache
    )
  }

  @Test
  fun `configureInternal stores the latest config on repeat call`() {
    val first = config(httpHeaders = mapOf("A" to "1"), userAgent = "ua-1")
    val second = config(httpHeaders = mapOf("B" to "2"), userAgent = "ua-2")

    player.configureInternal(first, context())
    player.configureInternal(second, context())

    assertEquals(second, player.config)
  }

  @Test
  fun `destroyInternal releases ExoPlayer + cache, persists user config`() {
    val configured = config(httpHeaders = mapOf("h" to "v"), userAgent = "ua")
    player.configureInternal(configured, context())
    // Non-default playback state the consumer set post-configure.
    player.repeatModeState = RepeatMode.QUEUE
    assertNotNull(player.player)
    assertNotNull(player.lookaheadCache)

    player.destroyInternal()

    assertNull("player must be null after destroy", player.player)
    assertNull(
      "lookahead cache must be released so a subsequent configure() can rebuild it",
      player.lookaheadCache
    )
    assertEquals(-1, player.currentTrackIndex)
    assertTrue(player.tracks.isEmpty())
    // User-set runtime config (config + repeatModeState +
    // lookaheadConfig) PERSISTS across destroy → configure so a
    // consumer who called setRepeatMode() / setLookaheadCache() once
    // doesn't lose those settings on a reconfigure cycle (matches
    // iOS).
    assertEquals(configured, player.config)
    assertEquals(RepeatMode.QUEUE, player.repeatModeState)
  }

  @Test
  fun `destroyInternal is safe to call before configure`() {
    // No throw — destroy on a never-configured player is a no-op.
    player.destroyInternal()
    assertNull(player.player)
  }

  @Test
  fun `destroyInternal is safe to call twice`() {
    player.configureInternal(
      config(), context()
    )
    player.destroyInternal()
    player.destroyInternal()
    assertNull(player.player)
  }

  @Test
  fun `configureInternal after destroyInternal rebuilds the ExoPlayer`() {
    val cfg = config()

    player.configureInternal(cfg, context())
    val first = player.player
    player.destroyInternal()
    serviceHandle.destroy()
    // destroyInternal unbinds the service; rebind a fresh
    // controller before the second configureInternal.
    serviceHandle = bindPlaybackServiceForTest(player, context())
    player.configureInternal(cfg, context())

    assertNotNull(player.player)
    assertNotEquals(
      "rebuilt ExoPlayer must be a fresh instance",
      first, player.player
    )
  }
}
