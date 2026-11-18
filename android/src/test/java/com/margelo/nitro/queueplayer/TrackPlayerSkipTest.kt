package com.margelo.nitro.queueplayer

import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * skip-method integration tests for
 * [TrackPlayer]. `QueueSkipArithmeticTest` covers the raw
 * arithmetic; these tests cover the adapter layer:
 *
 * - `computeNextIndex` — reads `repeatModeState` + `currentTrackIndex`
 *   + `tracks.size` and delegates to [QueueSkipArithmetic].
 * - `skipToNextInternal` / `skipToPreviousInternal` /
 *   `skipToIndexInternal` — advance `currentTrackIndex` when the
 *   arithmetic returns a target, no-op when it returns null. Under
 *   `RepeatMode.TRACK` these navigate with no wrap, exactly like
 *   `RepeatMode.OFF`.
 *
 * Tests reach into `tracks` / `currentTrackIndex` directly to
 * simulate a populated queue without driving `setQueue`. ExoPlayer's
 * own MediaItem timeline stays empty, which is handled by the
 * `target < mediaItemCount` guard in the skip methods.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@UnstableApi
class TrackPlayerSkipTest {

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
    player.tracks = (1..5).map { track("t$it") }
    player.currentTrackIndex = 2 // mid-queue default for most cases
  }

  @After
  fun tearDown() {
    player.destroyInternal()
    serviceHandle.destroy()
  }

  // --- computeNextIndex (adapter)

  @Test
  fun `computeNextIndex reads repeatModeState`() {
    player.repeatModeState = RepeatMode.OFF
    assertEquals(3, player.computeNextIndex())

    player.repeatModeState = RepeatMode.TRACK
    assertEquals(3, player.computeNextIndex())

    player.repeatModeState = RepeatMode.QUEUE
    assertEquals(3, player.computeNextIndex())
  }

  @Test
  fun `computeNextIndex returns null at end under REPEAT OFF`() {
    player.repeatModeState = RepeatMode.OFF
    player.currentTrackIndex = 4
    assertEquals(null, player.computeNextIndex())
  }

  @Test
  fun `computeNextIndex wraps to 0 at end under REPEAT QUEUE`() {
    player.repeatModeState = RepeatMode.QUEUE
    player.currentTrackIndex = 4
    assertEquals(0, player.computeNextIndex())
  }

  // --- skipToNextInternal / skipToPreviousInternal

  @Test
  fun `skipToNextInternal advances currentTrackIndex under REPEAT OFF`() {
    player.repeatModeState = RepeatMode.OFF
    player.currentTrackIndex = 2

    player.skipToNextInternal()
    assertEquals(3, player.currentTrackIndex)
  }

  @Test
  fun `skipToNextInternal is a no-op at end under REPEAT OFF`() {
    player.repeatModeState = RepeatMode.OFF
    player.currentTrackIndex = 4

    player.skipToNextInternal()
    assertEquals("index must stay pinned at the end", 4, player.currentTrackIndex)
  }

  @Test
  fun `skipToNextInternal wraps to 0 under REPEAT QUEUE`() {
    player.repeatModeState = RepeatMode.QUEUE
    player.currentTrackIndex = 4

    player.skipToNextInternal()
    assertEquals(0, player.currentTrackIndex)
  }

  @Test
  fun `skipToPreviousInternal goes back by 1`() {
    player.repeatModeState = RepeatMode.OFF
    player.currentTrackIndex = 3

    player.skipToPreviousInternal()
    assertEquals(2, player.currentTrackIndex)
  }

  @Test
  fun `skipToPreviousInternal is a no-op at index 0 under REPEAT OFF`() {
    player.repeatModeState = RepeatMode.OFF
    player.currentTrackIndex = 0

    player.skipToPreviousInternal()
    assertEquals("index must stay pinned at 0", 0, player.currentTrackIndex)
  }

  @Test
  fun `skipToPreviousInternal wraps to last under REPEAT QUEUE`() {
    player.repeatModeState = RepeatMode.QUEUE
    player.currentTrackIndex = 0

    player.skipToPreviousInternal()
    assertEquals(4, player.currentTrackIndex)
  }

  // --- REPEAT TRACK navigates like OFF (no wrap); repeat-one persists

  @Test
  fun `skipToNextInternal advances currentTrackIndex under REPEAT TRACK`() {
    player.repeatModeState = RepeatMode.TRACK
    player.currentTrackIndex = 2

    player.skipToNextInternal()
    assertEquals(3, player.currentTrackIndex)
  }

  @Test
  fun `skipToNextInternal is a no-op at the last track under REPEAT TRACK`() {
    player.repeatModeState = RepeatMode.TRACK
    player.currentTrackIndex = 4

    player.skipToNextInternal()
    assertEquals("no wrap under TRACK", 4, player.currentTrackIndex)
  }

  @Test
  fun `skipToPreviousInternal goes back under REPEAT TRACK`() {
    player.repeatModeState = RepeatMode.TRACK
    player.currentTrackIndex = 2

    player.skipToPreviousInternal()
    assertEquals(1, player.currentTrackIndex)
  }

  @Test
  fun `skipToPreviousInternal is a no-op at index 0 under REPEAT TRACK`() {
    player.repeatModeState = RepeatMode.TRACK
    player.currentTrackIndex = 0

    player.skipToPreviousInternal()
    assertEquals("no wrap under TRACK", 0, player.currentTrackIndex)
  }

  // --- skipToIndexInternal

  @Test
  fun `skipToIndexInternal sets currentTrackIndex when in range`() {
    player.skipToIndexInternal(4)
    assertEquals(4, player.currentTrackIndex)

    player.skipToIndexInternal(0)
    assertEquals(0, player.currentTrackIndex)
  }

  @Test
  fun `skipToIndexInternal is a no-op for negative index`() {
    player.currentTrackIndex = 2
    player.skipToIndexInternal(-1)
    assertEquals(2, player.currentTrackIndex)
  }

  @Test
  fun `skipToIndexInternal is a no-op for out-of-range index`() {
    player.currentTrackIndex = 2
    player.skipToIndexInternal(100)
    assertEquals(2, player.currentTrackIndex)
  }

  @Test
  fun `skipToIndexInternal honours target even under REPEAT TRACK — asymmetric with Next and Previous`() {
    // Explicit index = explicit intent; track-repeat doesn't
    // hijack skipToIndex the way it hijacks Next/Previous.
    player.repeatModeState = RepeatMode.TRACK
    player.skipToIndexInternal(4)
    assertEquals(4, player.currentTrackIndex)
  }

  // --- empty queue / uninitialised guards

  @Test
  fun `skipToNextInternal is a no-op when tracks is empty`() {
    player.tracks = emptyList()
    player.currentTrackIndex = -1

    player.skipToNextInternal()
    assertEquals(-1, player.currentTrackIndex)
  }

  @Test
  fun `skipToPreviousInternal is a no-op when tracks is empty`() {
    player.tracks = emptyList()
    player.currentTrackIndex = -1

    player.skipToPreviousInternal()
    assertEquals(-1, player.currentTrackIndex)
  }

  @Test
  fun `skipToIndexInternal is a no-op when tracks is empty`() {
    player.tracks = emptyList()
    player.currentTrackIndex = -1

    player.skipToIndexInternal(0)
    assertEquals(-1, player.currentTrackIndex)
  }

  @Test
  fun `skip methods are no-ops when player is not configured`() {
    val fresh = TrackPlayer()
    fresh.tracks = (1..3).map { track("t$it") }
    fresh.currentTrackIndex = 0

    // No throw — the `player ?: return` guard at the top of each
    // *Internal method short-circuits before the arithmetic runs.
    fresh.skipToNextInternal()
    fresh.skipToPreviousInternal()
    fresh.skipToIndexInternal(2)

    assertEquals(
      "currentTrackIndex is untouched when player is null",
      0, fresh.currentTrackIndex
    )
  }

  // --- skipToPreviousInternal with skipToPreviousBehavior = restart-or-previous
  //
  // The test harness leaves the ExoPlayer timeline empty, so engine position
  // reads 0 (<= the 3s threshold). Past-threshold restart with a real position
  // is covered by the pure `QueueSkipArithmeticTest` matrix + the demo suite.

  private fun configureSkipBehavior(behavior: SkipToPreviousBehavior?) {
    player.configureInternal(
      PlayerConfig(enableDiagnosticLogs = null, httpHeaders = null, userAgent = null, autoRetries = null, retryBackoffMs = null, networkTimeoutMs = null, audioContentType = null, audioCategoryOptions = null, voiceVocabularyDonationEnabled = null, allowsExternalPlayback = null,  visualizationEnabled = null, clampSeekToBuffered = null, lookaheadCacheMaxSizeMb = null, lookaheadCacheEvictionPolicy = null, progressUpdateIntervalMs = null, backgroundProgressUpdateIntervalMs = null, placeholderArtworkUri = null, skipToPreviousBehavior = behavior), context()
    )
    player.tracks = (1..5).map { track("t$it") }
  }

  @Test
  fun `skipToPreviousInternal restart mode under threshold goes to previous track`() {
    configureSkipBehavior(SkipToPreviousBehavior.RESTART_OR_PREVIOUS)
    player.repeatModeState = RepeatMode.OFF
    player.currentTrackIndex = 3
    player.skipToPreviousInternal()
    assertEquals("position 0 <= threshold → previous track", 2, player.currentTrackIndex)
  }

  @Test
  fun `skipToPreviousInternal restart mode at first track restarts, index unchanged`() {
    configureSkipBehavior(SkipToPreviousBehavior.RESTART_OR_PREVIOUS)
    player.repeatModeState = RepeatMode.OFF
    player.currentTrackIndex = 0
    // No previous track → restart current; the restart branch seeks to 0 and
    // leaves currentTrackIndex put (a seek, not a skip).
    player.skipToPreviousInternal()
    assertEquals("restart leaves the index unchanged", 0, player.currentTrackIndex)
  }
}
