package com.margelo.nitro.queueplayer

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * gapless + bulk queue surface.
 *
 * Covers:
 * - `configureInternal` sets `pauseAtEndOfMediaItems = false`
 *   (Media3 default, but pinning it makes the intent explicit at
 *   the configuration site).
 * - `setQueueInternal` — track list stashed, currentTrackIndex
 *   set to 0 / -1, MediaItem batch handed to ExoPlayer via
 *   `setMediaItems`, `prepare()` lifts the player out of IDLE.
 * - `clearQueueInternal` — clears both authoritative state and
 *   ExoPlayer's internal timeline.
 * - `getQueue` / `getCurrentTrackIndex` — cheap reads.
 * - `setRepeatModeInternal` — state write + ExoPlayer native
 *   repeat mode mirror (OFF→OFF, TRACK→ONE, QUEUE→ALL) so natural
 *   gapless auto-advance agrees with the skip arithmetic at
 *   queue boundaries.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@UnstableApi
class TrackPlayerQueueTest {

  private lateinit var player: TrackPlayer

  private fun context() = ApplicationProvider.getApplicationContext<android.content.Context>()

  private fun track(id: String) = TrackItem(
    id = id, url = "https://example.com/$id.m4a",
    title = null, artist = null, album = null,
    duration = null, artworkUrl = null, extras = null,
    replayGainTrackGain = null, replayGainTrackPeak = null,
    replayGainAlbumGain = null, replayGainAlbumPeak = null, isVideo = null
  )

  private lateinit var serviceHandle: PlaybackServiceTestHandle

  @Before
  fun setUp() {
    player = TrackPlayer()
    serviceHandle = bindPlaybackServiceForTest(player, context())
    player.configureInternal(
      PlayerConfig(enableDiagnosticLogs = null, httpHeaders = null, userAgent = null, autoRetries = null, retryBackoffMs = null, networkTimeoutMs = null, audioContentType = null, audioCategoryOptions = null, voiceVocabularyDonationEnabled = null, allowsExternalPlayback = null,  visualizationEnabled = null, clampSeekToBuffered = null, lookaheadCacheMaxSizeMb = null, lookaheadCacheEvictionPolicy = null, progressUpdateIntervalMs = null, backgroundProgressUpdateIntervalMs = null, placeholderArtworkUri = null, skipToPreviousBehavior = null), context()
    )
  }

  @After
  fun tearDown() {
    player.destroyInternal()
    serviceHandle.destroy()
  }

  // --- Gapless config on configure

  @Test
  fun `configure sets pauseAtEndOfMediaItems to false`() {
    assertFalse(
      "gapless pipeline requires no pause at item boundary",
      player.player!!.pauseAtEndOfMediaItems
    )
  }

  // --- setQueueInternal

  @Test
  fun `setQueueInternal stores tracks and sets currentTrackIndex to 0`() {
    val tracks = (1..3).map { track("t$it") }
    player.setQueueInternal(tracks)

    assertEquals(tracks, player.tracks)
    assertEquals(0, player.currentTrackIndex)
  }

  @Test
  fun `setQueueInternal hands MediaItems to ExoPlayer and lifts out of IDLE`() {
    val tracks = (1..3).map { track("t$it") }
    player.setQueueInternal(tracks)

    val p = player.player!!
    assertEquals(3, p.mediaItemCount)
    // prepare() transitions IDLE → BUFFERING (or READY if already
    // cached); either way no longer IDLE.
    assertTrue(
      "setQueue must call prepare() — stays IDLE without it",
      p.playbackState != Player.STATE_IDLE
    )
  }

  @Test
  fun `setQueueInternal with empty list clears state and leaves player IDLE`() {
    // Populate first so we can verify the reset branch.
    player.setQueueInternal((1..3).map { track("t$it") })

    player.setQueueInternal(emptyList())

    assertEquals(-1, player.currentTrackIndex)
    assertTrue(player.tracks.isEmpty())
    assertEquals(0, player.player!!.mediaItemCount)
    // Empty queue skips the prepare() call — staying in IDLE /
    // ENDED is consistent with "nothing to play".
  }

  @Test
  fun `setQueueInternal replaces the previous queue`() {
    player.setQueueInternal((1..3).map { track("first$it") })
    player.setQueueInternal((1..5).map { track("second$it") })

    assertEquals(5, player.tracks.size)
    assertEquals("second1", player.tracks[0].id)
    assertEquals(5, player.player!!.mediaItemCount)
  }

  // --- getQueue / getCurrentTrackIndex

  @Test
  fun `setQueueInternal stores the tracks in order`() {
    // `getQueue()` itself returns a Nitro Promise, which cannot be resolved
    // from Robolectric; it reads this list on main, and the demo cycle covers
    // the bridged call.
    val tracks = (1..3).map { track("t$it") }
    player.setQueueInternal(tracks)

    assertEquals(3, player.tracks.size)
    assertEquals("t1", player.tracks[0].id)
  }

  @Test
  fun `getCurrentTrackIndex returns -1 initially`() {
    assertEquals(-1.0, player.getCurrentTrackIndex(), 0.001)
  }

  @Test
  fun `getCurrentTrackIndex reflects setQueue`() {
    player.setQueueInternal((1..3).map { track("t$it") })
    assertEquals(0.0, player.getCurrentTrackIndex(), 0.001)
  }

  // --- clearQueueInternal

  @Test
  fun `clearQueueInternal resets tracks and clears MediaItems`() {
    player.setQueueInternal((1..3).map { track("t$it") })
    assertTrue(
      "precondition: MediaItems populated before clear",
      player.player!!.mediaItemCount > 0
    )

    player.clearQueueInternal()

    assertTrue(player.tracks.isEmpty())
    assertEquals(-1, player.currentTrackIndex)
    assertEquals(0, player.player!!.mediaItemCount)
  }

  @Test
  fun `clearQueueInternal is a no-op on a never-populated queue`() {
    // No throw — just returns us to the already-clean state.
    player.clearQueueInternal()
    assertTrue(player.tracks.isEmpty())
    assertEquals(-1, player.currentTrackIndex)
    assertEquals(
      "ExoPlayer timeline must also be empty post-clear",
      0, player.player!!.mediaItemCount
    )
  }

  @Test
  fun `clearQueueInternal fires onTrackChange with null track and -1 index`() {
    // Regression for state-inspector / Player-tab "stuck on prior
    // track" bug: ExoPlayer.clearMediaItems() fires
    // onMediaItemTransition synchronously, which routes through
    // handleMediaItemTransition → trackChangeListeners.
    //
    // Authoritative state must be reset BEFORE the ExoPlayer call.
    // Capture `tracks.size` + `currentTrackIndex` INSIDE the
    // listener body (at fire time) to assert they're already 0 / -1
    // when the listener runs.
    player.setQueueInternal((1..3).map { track("t$it") })

    data class Snap(
      val track: TrackItem?,
      val idx: Double,
      val reason: TrackChangeReason,
      val tracksSizeAtFire: Int,
      val currentIndexAtFire: Int,
    )
    val received = mutableListOf<Snap>()
    player.onTrackChange { t, idx, reason, _ ->
      received.add(
        Snap(
          track = t,
          idx = idx,
          reason = reason,
          tracksSizeAtFire = player.tracks.size,
          currentIndexAtFire = player.currentTrackIndex,
        )
      )
    }

    player.clearQueueInternal()

    assertEquals(
      "clearQueue should fire onTrackChange exactly once",
      1, received.size
    )
    val snap = received.single()
    assertNull("track must be null after clear", snap.track)
    assertEquals("index must be -1.0 after clear", -1.0, snap.idx, 0.001)
    assertEquals(
      "reason must be QUEUE_REPLACED",
      TrackChangeReason.QUEUE_REPLACED, snap.reason
    )
    assertEquals(
      "tracks must already be cleared BEFORE the listener fires",
      0, snap.tracksSizeAtFire
    )
    assertEquals(
      "currentTrackIndex must already be -1 BEFORE the listener fires",
      -1, snap.currentIndexAtFire
    )
  }

  // --- setRepeatModeInternal mirrors ExoPlayer's native mode

  @Test
  fun `setRepeatModeInternal maps OFF to REPEAT_MODE_OFF`() {
    player.setRepeatModeInternal(RepeatMode.OFF)
    assertEquals(RepeatMode.OFF, player.repeatModeState)
    assertEquals(Player.REPEAT_MODE_OFF, player.player!!.repeatMode)
  }

  @Test
  fun `setRepeatModeInternal maps TRACK to REPEAT_MODE_ONE`() {
    player.setRepeatModeInternal(RepeatMode.TRACK)
    assertEquals(RepeatMode.TRACK, player.repeatModeState)
    assertEquals(Player.REPEAT_MODE_ONE, player.player!!.repeatMode)
  }

  @Test
  fun `setRepeatModeInternal maps QUEUE to REPEAT_MODE_ALL`() {
    player.setRepeatModeInternal(RepeatMode.QUEUE)
    assertEquals(RepeatMode.QUEUE, player.repeatModeState)
    assertEquals(
      "QUEUE repeat must enable ExoPlayer's native all-wrap so " +
        "natural end-of-queue advances cycle without hitting STATE_ENDED",
      Player.REPEAT_MODE_ALL, player.player!!.repeatMode
    )
  }

  @Test
  fun `setRepeatModeInternal is a no-op on an unconfigured player`() {
    val fresh = TrackPlayer()
    // No throw — both paths (field write + native mirror) guard
    // against null player gracefully.
    fresh.setRepeatModeInternal(RepeatMode.QUEUE)
    assertEquals(RepeatMode.QUEUE, fresh.repeatModeState)
  }

  // --- interaction with skip under populated queue

  @Test
  fun `skipToIndex after setQueue issues the ExoPlayer seek`() {
    // After setQueue the MediaItem timeline has 3 entries, so the
    // `target < mediaItemCount` guard in skipToIndexInternal no
    // longer short-circuits — confirms the skip → seek path
    // survives once the timeline is populated.
    player.setQueueInternal((1..3).map { track("t$it") })

    player.skipToIndexInternal(2)

    assertEquals(2, player.currentTrackIndex)
    assertEquals(
      "ExoPlayer's own currentMediaItemIndex must track the skip",
      2, player.player!!.currentMediaItemIndex
    )
  }
}
