package com.margelo.nitro.queueplayer

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * queue-mutation integration tests
 * (addToQueue / removeFromQueue / moveInQueue) against a live
 * ExoPlayer via Robolectric. Tests exercise both the Kotlin-side
 * authoritative state (`tracks`, `currentTrackIndex`) AND the
 * ExoPlayer timeline synchronisation (`mediaItemCount`,
 * `currentMediaItemIndex` where applicable).
 *
 * These tests focus on wiring correctness — that the arithmetic
 * result is actually applied to both state structures in concert.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@UnstableApi
class TrackPlayerMutationTest {

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
    player.setQueueInternal((1..5).map { track("t$it") })
    // cur defaults to 0 after setQueue; tests needing mid-queue
    // explicitly set player.currentTrackIndex.
  }

  @After
  fun tearDown() {
    player.destroyInternal()
    serviceHandle.destroy()
  }

  // --- addToQueueInternal

  @Test
  fun `addToQueueInternal appends by default (insertBefore null)`() {
    player.addToQueueInternal(listOf(track("new1"), track("new2")), insertBefore = null)

    assertEquals(7, player.tracks.size)
    assertEquals("t5", player.tracks[4].id)
    assertEquals("new1", player.tracks[5].id)
    assertEquals("new2", player.tracks[6].id)
    assertEquals(7, player.player!!.mediaItemCount)
    // cur was 0, insert past cur → unchanged.
    assertEquals(0, player.currentTrackIndex)
  }

  @Test
  fun `addToQueueInternal insert-at-cur shifts cur forward`() {
    player.currentTrackIndex = 2

    player.addToQueueInternal(listOf(track("new1"), track("new2")), insertBefore = 2)

    assertEquals(7, player.tracks.size)
    assertEquals(4, player.currentTrackIndex) // 2 + 2 inserted
    assertEquals("new1", player.tracks[2].id)
    assertEquals("t3", player.tracks[4].id)
  }

  @Test
  fun `addToQueueInternal insert-after-cur leaves cur unchanged`() {
    player.currentTrackIndex = 1

    player.addToQueueInternal(listOf(track("new1")), insertBefore = 4)

    assertEquals(1, player.currentTrackIndex)
    assertEquals(6, player.tracks.size)
    assertEquals("new1", player.tracks[4].id)
  }

  @Test
  fun `addToQueueInternal with empty newTracks is a no-op`() {
    val preSize = player.tracks.size
    val preCur = player.currentTrackIndex

    player.addToQueueInternal(emptyList(), insertBefore = null)

    assertEquals(preSize, player.tracks.size)
    assertEquals(preCur, player.currentTrackIndex)
  }

  @Test
  fun `addToQueueInternal into empty queue leaves cur at -1 and primes the pipeline`() {
    // Build a fresh TrackPlayer that has never seen setQueue — the
    // ExoPlayer sits in STATE_IDLE so the prepare() branch is
    // reachable. The @Before-built `player` has already run a
    // setQueue which transitions past IDLE, and clearQueueInternal
    // doesn't return to IDLE (it leaves the player in READY / ENDED
    // with a zero-item timeline).
    //
    // Tear the @Before player down first. SimpleCache enforces a
    // one-instance-per-directory invariant for the JVM lifetime;
    // constructing `fresh` while `player` still holds the lock
    // throws IllegalStateException from inside SimpleCache.
    player.destroyInternal()
    serviceHandle.destroy()

    val fresh = TrackPlayer()
    val freshHandle = bindPlaybackServiceForTest(fresh, context())
    try {
      fresh.configureInternal(
        PlayerConfig(enableDiagnosticLogs = null, httpHeaders = null, userAgent = null, autoRetries = null, retryBackoffMs = null, networkTimeoutMs = null, audioContentType = null, audioCategoryOptions = null, voiceVocabularyDonationEnabled = null, allowsExternalPlayback = null,  visualizationEnabled = null, clampSeekToBuffered = null, lookaheadCacheMaxSizeMb = null, lookaheadCacheEvictionPolicy = null, progressUpdateIntervalMs = null, backgroundProgressUpdateIntervalMs = null, placeholderArtworkUri = null, skipToPreviousBehavior = null), context()
      )
      assertEquals(-1, fresh.currentTrackIndex)
      assertEquals(Player.STATE_IDLE, fresh.player!!.playbackState)

      fresh.addToQueueInternal(
        listOf(track("a"), track("b")), insertBefore = null
      )

      assertEquals(2, fresh.tracks.size)
      // cur stays at -1 — consumer must explicitly skipToIndex(0) to
      // begin playback, per the documented contract.
      assertEquals(-1, fresh.currentTrackIndex)
      // The `prepare()` branch fires when mediaItemCount > 0 and
      // the player was IDLE. Without it, adding into an empty
      // queue leaves the player IDLE until play() re-primes — a
      // user-observable latency regression.
      assertTrue(
        "addToQueue into empty IDLE player must lift it out of IDLE via prepare()",
        fresh.player!!.playbackState != Player.STATE_IDLE
      )
    } finally {
      fresh.destroyInternal()
      freshHandle.destroy()
    }
  }

  @Test
  fun `addToQueueInternal clamps negative insertBefore to 0`() {
    player.currentTrackIndex = 2

    player.addToQueueInternal(listOf(track("new")), insertBefore = -99)

    // Insert at 0, before cur: cur shifts forward by 1.
    assertEquals(3, player.currentTrackIndex)
    assertEquals("new", player.tracks[0].id)
  }

  @Test
  fun `addToQueueInternal clamps past-end insertBefore to trackCount`() {
    player.currentTrackIndex = 2

    player.addToQueueInternal(listOf(track("tail")), insertBefore = 999)

    // Insert at end, no cur shift.
    assertEquals(2, player.currentTrackIndex)
    assertEquals("tail", player.tracks.last().id)
  }

  // --- removeFromQueueInternal

  @Test
  fun `removeFromQueueInternal drops the specified indices`() {
    // cur on a track outside the removed set so all three indices drop
    // (the current track is pinned and would otherwise be filtered out).
    player.currentTrackIndex = 1

    player.removeFromQueueInternal(listOf(0, 2, 4))

    assertEquals(2, player.tracks.size)
    assertEquals("t2", player.tracks[0].id)
    assertEquals("t4", player.tracks[1].id)
    assertEquals(2, player.player!!.mediaItemCount)
  }

  @Test
  fun `removeFromQueueInternal shifts cur down when lower indices removed`() {
    player.currentTrackIndex = 3

    player.removeFromQueueInternal(listOf(0, 1))

    assertEquals(1, player.currentTrackIndex)
  }

  @Test
  fun `removeFromQueueInternal leaves cur alone when only higher indices removed`() {
    player.currentTrackIndex = 1

    player.removeFromQueueInternal(listOf(3, 4))

    assertEquals(1, player.currentTrackIndex)
  }

  @Test
  fun `removeFromQueueInternal pins the current track (removing only cur is a no-op)`() {
    player.currentTrackIndex = 2

    player.removeFromQueueInternal(listOf(2))

    // The current track is pinned, so the request is a no-op: queue +
    // index unchanged, current still t3 at index 2.
    assertEquals(5, player.tracks.size)
    assertEquals(2, player.currentTrackIndex)
    assertEquals("t3", player.tracks[2].id)
  }

  @Test
  fun `removeFromQueueInternal pins the current track when it is the tail`() {
    player.currentTrackIndex = 4 // last

    player.removeFromQueueInternal(listOf(4))

    // No-op — the pinned current (last) track cannot be removed.
    assertEquals(5, player.tracks.size)
    assertEquals(4, player.currentTrackIndex)
  }

  @Test
  fun `removeFromQueueInternal removing all including current keeps the pinned current`() {
    player.currentTrackIndex = 2

    player.removeFromQueueInternal(listOf(0, 1, 2, 3, 4))

    // Every non-current track drops; the pinned current (t3) survives,
    // now the only track at index 0. clearQueue is the way to empty.
    assertEquals(1, player.tracks.size)
    assertEquals("t3", player.tracks[0].id)
    assertEquals(0, player.currentTrackIndex)
    assertEquals(1, player.player!!.mediaItemCount)
  }

  @Test
  fun `removeFromQueueInternal dedups duplicate indices`() {
    player.removeFromQueueInternal(listOf(2, 2, 2))

    assertEquals(4, player.tracks.size)
    assertEquals(4, player.player!!.mediaItemCount)
  }

  @Test
  fun `removeFromQueueInternal drops out-of-range and negative indices`() {
    val preSize = player.tracks.size

    player.removeFromQueueInternal(listOf(-1, 100, 999))

    assertEquals(preSize, player.tracks.size)
  }

  @Test
  fun `removeFromQueueInternal with empty input is a no-op`() {
    val preSize = player.tracks.size
    val preCur = player.currentTrackIndex

    player.removeFromQueueInternal(emptyList())

    assertEquals(preSize, player.tracks.size)
    assertEquals(preCur, player.currentTrackIndex)
  }

  // --- moveInQueueInternal

  @Test
  fun `moveInQueueInternal moves one track to the target position`() {
    // cur off the moved track so this exercises a normal (non-pinned)
    // reorder rather than the move-current no-op.
    player.currentTrackIndex = 2

    player.moveInQueueInternal(0, 4)

    assertEquals("t2", player.tracks[0].id)
    assertEquals("t1", player.tracks[4].id)
  }

  @Test
  fun `moveInQueueInternal pins the current track (moving cur is a no-op)`() {
    player.currentTrackIndex = 1

    player.moveInQueueInternal(1, 4)

    // No-op — the pinned current track cannot be moved; queue + index
    // unchanged.
    assertEquals(1, player.currentTrackIndex)
    assertEquals("t2", player.tracks[1].id)
    assertEquals("t5", player.tracks[4].id)
  }

  @Test
  fun `moveInQueueInternal shifts cur down when a lower track slides past`() {
    // cur=3; move [0 → 4]. from<cur && to>=cur → cur -= 1.
    player.currentTrackIndex = 3

    player.moveInQueueInternal(0, 4)

    assertEquals(2, player.currentTrackIndex)
  }

  @Test
  fun `moveInQueueInternal shifts cur up when a higher track slides past`() {
    // cur=1; move [3 → 0]. from>cur && to<=cur → cur += 1.
    player.currentTrackIndex = 1

    player.moveInQueueInternal(3, 0)

    assertEquals(2, player.currentTrackIndex)
  }

  @Test
  fun `moveInQueueInternal leaves cur alone when move does not cross cur`() {
    player.currentTrackIndex = 1

    // both endpoints above cur
    player.moveInQueueInternal(3, 4)
    assertEquals(1, player.currentTrackIndex)
  }

  @Test
  fun `moveInQueueInternal out-of-range from or to is a no-op`() {
    val pre = player.tracks.toList()
    val preCur = player.currentTrackIndex

    player.moveInQueueInternal(-1, 2)
    player.moveInQueueInternal(2, -1)
    player.moveInQueueInternal(100, 2)
    player.moveInQueueInternal(2, 100)

    assertEquals(pre, player.tracks)
    assertEquals(preCur, player.currentTrackIndex)
  }

  @Test
  fun `moveInQueueInternal from == to is a no-op`() {
    val pre = player.tracks.toList()

    player.moveInQueueInternal(2, 2)

    assertEquals(pre, player.tracks)
  }

  // --- cross-method: state consistency with ExoPlayer

  @Test
  fun `add then remove then move leaves Kotlin tracks and ExoPlayer timeline in lock-step`() {
    // add 2 at start
    player.addToQueueInternal(listOf(track("x1"), track("x2")), insertBefore = 0)
    // remove the one that was originally t2 (now at index 3)
    player.removeFromQueueInternal(listOf(3))
    // move tail to middle
    player.moveInQueueInternal(5, 2)

    // Mirror the steps manually.
    // Start: [t1, t2, t3, t4, t5]
    // After add at 0:          [x1, x2, t1, t2, t3, t4, t5]
    // After remove index 3:    [x1, x2, t1, t3, t4, t5]
    // After move 5 → 2:        [x1, x2, t5, t1, t3, t4]
    assertEquals(
      listOf("x1", "x2", "t5", "t1", "t3", "t4"),
      player.tracks.map { it.id }
    )
    assertEquals(
      "ExoPlayer timeline must match tracks 1:1",
      player.tracks.size, player.player!!.mediaItemCount
    )
  }

  // --- retryFailedItem timeline preservation

  @Test
  fun `retryFailedItem rebuilds the failed item without collapsing the queue`() {
    // 5-item queue from setUp; current = idx 0.
    player.currentTrackIndex = 0
    assertEquals(5, player.player!!.mediaItemCount)

    player.retryFailedItem(0)

    // remove + re-add rebuilds ONLY the failed item's source and keeps
    // every other item in place. A single-item setMediaItem path collapses
    // the timeline to 1, breaking auto-advance + the crossfade full-queue
    // look-ahead model.
    assertEquals(5, player.player!!.mediaItemCount)
    assertEquals(0, player.player!!.currentMediaItemIndex)
    assertEquals(5, player.tracks.size)
  }

  @Test
  fun `retryFailedItem is a no-op when the requested index is no longer current`() {
    player.currentTrackIndex = 0
    player.retryFailedItem(3)
    assertEquals(5, player.player!!.mediaItemCount)
    assertEquals(0, player.player!!.currentMediaItemIndex)
  }

  // --- unconfigured guards

  @Test
  fun `mutation methods are no-ops when player is null`() {
    val fresh = TrackPlayer()
    fresh.tracks = listOf(track("a"), track("b"))
    fresh.currentTrackIndex = 0

    fresh.addToQueueInternal(listOf(track("c")), insertBefore = null)
    fresh.removeFromQueueInternal(listOf(0))
    fresh.moveInQueueInternal(0, 1)

    // Nothing mutated (no throws, but also state untouched).
    assertEquals(2, fresh.tracks.size)
    assertEquals(0, fresh.currentTrackIndex)
  }

  // --- internal-mutator return contract (the cast router mirrors these
  // exact values to the receiver, so they are load-bearing).

  @Test
  fun `addToQueueInternal returns the clamped insert index`() {
    player.currentTrackIndex = 2
    assertEquals(1, player.addToQueueInternal(listOf(track("n")), insertBefore = 1))
  }

  @Test
  fun `addToQueueInternal returns -1 on empty input`() {
    assertEquals(-1, player.addToQueueInternal(emptyList(), insertBefore = null))
  }

  @Test
  fun `removeFromQueueInternal returns the sanitised pin-filtered removed indices`() {
    player.currentTrackIndex = 2
    // The pinned current (index 2) is dropped from the removed set.
    assertEquals(listOf(0, 4), player.removeFromQueueInternal(listOf(0, 2, 4)))
  }

  @Test
  fun `removeFromQueueInternal returns empty when only the pinned current is requested`() {
    player.currentTrackIndex = 2
    assertEquals(emptyList<Int>(), player.removeFromQueueInternal(listOf(2)))
  }

  @Test
  fun `moveInQueueInternal returns false when moving the pinned current track`() {
    player.currentTrackIndex = 1
    assertEquals(false, player.moveInQueueInternal(1, 4))
  }

  @Test
  fun `moveInQueueInternal returns false on a no-op move`() {
    assertEquals(false, player.moveInQueueInternal(2, 2))
  }

  @Test
  fun `moveInQueueInternal returns true on a real move`() {
    player.currentTrackIndex = 2
    assertEquals(true, player.moveInQueueInternal(0, 4))
  }
}
