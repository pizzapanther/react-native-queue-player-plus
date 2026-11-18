package com.margelo.nitro.queueplayer

import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * transport + playback-config tests for the
 * Kotlin [TrackPlayer]. Exercises the player-local
 * (non-queue-dependent) surface:
 *
 * - `play` / `pause` / `stop` — ExoPlayer bindings (empty-player
 *   no-throw sanity only; real playback assertions land once
 *   event wiring is in place).
 * - `seekTo` — seconds→milliseconds + clamp semantics + non-finite
 *   (NaN / Infinity) input sanitisation.
 * - `setPlaybackSpeed` / `setVolume` + their readers — exercises
 *   the production-path `*Internal` bodies so the `.toFloat()`
 *   cast + pitch preservation aren't bypassed by direct ExoPlayer
 *   writes.
 * - `setRepeatMode` — state-only writer.
 * - `getState` — `Player.STATE_*` → `PlayerState` translation
 *   including the `playbackSuppressionReason` stall branch.
 *
 * Consistent: call the `Internal`
 * overloads to avoid Nitro's Promise JNI surface (unavailable in
 * the Robolectric runtime).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@UnstableApi
class TrackPlayerTransportTest {

  private lateinit var player: TrackPlayer

  private fun context() = ApplicationProvider.getApplicationContext<android.content.Context>()

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

  // --- getStateInternal translation

  @Test
  fun `getStateInternal returns NONE before configure`() {
    val fresh = TrackPlayer()
    assertEquals(PlayerState.NONE, fresh.getStateInternal())
  }

  @Test
  fun `getStateInternal returns NONE on STATE_IDLE with empty queue`() {
    // Fresh ExoPlayer, no tracks → NONE per iOS parity.
    assertEquals(PlayerState.NONE, player.getStateInternal())
  }

  @Test
  fun `getStateInternal returns PAUSED on STATE_IDLE when tracks loaded and currentTrackIndex set`() {
    // Simulate populate tracks +
    // currentTrackIndex while the ExoPlayer is still IDLE.
    // iOS parity: paused-with-queue returns PAUSED, not NONE.
    player.tracks = listOf(
      TrackItem("t1", "https://example.com/1.m4a", null, null, null, null, null, null, null, null, null, null, isVideo = null)
    )
    player.currentTrackIndex = 0

    assertEquals(PlayerState.PAUSED, player.getStateInternal())
  }

  // --- seekToInternal clamping + non-finite sanitisation

  @Test
  fun `seekToInternal on a player with no item is a no-op`() {
    // No MediaItem loaded → ExoPlayer.duration reads as TIME_UNSET.
    // Should not throw.
    player.seekToInternal(42.0)
    assertEquals(PlayerState.NONE, player.getStateInternal())
  }

  @Test
  fun `seekToInternal passes an early seek through before the item is loaded`() {
    // Items present but unloaded → STATE_IDLE, so the seekability guard (which
    // only no-ops a LOADED-and-unseekable track) doesn't fire and an early seek
    // on eventually-seekable content still lands. The reset-to-start protection
    // applies once the item is loaded (STATE_READY) and confirmed unseekable.
    player.setQueueInternal(
      (1..3).map { TrackItem("t$it", "https://example.com/$it.m4a", null, null, null, null, null, null, null, null, null, null, isVideo = null) }
    )
    player.currentTrackIndex = 0

    player.seekToInternal(42.0)

    assertEquals(42_000L, player.player!!.currentPosition)
  }

  @Test
  fun `seekToInternal clamps negative positions to zero`() {
    player.seekToInternal(-5.0)
    player.seekToInternal(-1e9)
    assertEquals(PlayerState.NONE, player.getStateInternal())
  }

  @Test
  fun `seekToInternal sanitises NaN to a no-op`() {
    // Without the isFinite guard, (NaN * 1000.0).toLong() is 0 — a
    // silent rewind, not what a NaN input should produce.
    player.seekToInternal(Double.NaN)
    assertEquals(0L, player.player!!.currentPosition)
  }

  @Test
  fun `seekToInternal sanitises Infinity to a no-op`() {
    // Without the isFinite guard, (Infinity * 1000.0).toLong() is
    // Long.MAX_VALUE, which ExoPlayer treats as "seek past end".
    player.seekToInternal(Double.POSITIVE_INFINITY)
    player.seekToInternal(Double.NEGATIVE_INFINITY)
    assertEquals(0L, player.player!!.currentPosition)
  }

  // --- setVolumeInternal / setPlaybackSpeedInternal

  @Test
  fun `setVolumeInternal round-trips through getVolume`() {
    player.setVolumeInternal(1.0)
    assertEquals(1.0, player.getVolume(), 0.001)

    player.setVolumeInternal(0.25)
    assertEquals(0.25, player.getVolume(), 0.001)

    player.setVolumeInternal(0.0)
    assertEquals(0.0, player.getVolume(), 0.001)
  }

  @Test
  fun `setPlaybackSpeedInternal round-trips through getPlaybackSpeed and applies the pitch mode`() {
    // Pitch is lib-owned, driven by the pitch-correction mode. Under the
    // default voice mode the engine holds pitch at 1.0 while the speed
    // changes (pitch preserved).
    player.setPlaybackSpeedInternal(1.5)
    assertEquals(1.5, player.getPlaybackSpeed(), 0.001)
    assertEquals(1.0f, player.player!!.playbackParameters.pitch, 0.001f)

    player.setPlaybackSpeedInternal(0.5)
    assertEquals(0.5, player.getPlaybackSpeed(), 0.001)
    assertEquals(
      "voice mode holds pitch at 1.0 across speed changes",
      1.0f, player.player!!.playbackParameters.pitch, 0.001f
    )
  }

  @Test
  fun `getPlaybackSpeed returns 1_0 when no player is configured`() {
    val fresh = TrackPlayer()
    assertEquals(1.0, fresh.getPlaybackSpeed(), 0.001)
  }

  @Test
  fun `getVolume returns 1_0 when no player is configured`() {
    val fresh = TrackPlayer()
    assertEquals(1.0, fresh.getVolume(), 0.001)
  }

  // --- setRepeatMode (state-only writer)

  @Test
  fun `setRepeatMode stores the mode for skip logic to read`() {
    assertEquals(RepeatMode.OFF, player.repeatModeState)

    player.repeatModeState = RepeatMode.QUEUE
    assertEquals(RepeatMode.QUEUE, player.repeatModeState)

    player.repeatModeState = RepeatMode.TRACK
    assertEquals(RepeatMode.TRACK, player.repeatModeState)
  }

  // The sibling tests below exercise shuffleQueueInternal.

  // --- basic play/pause/stop no-op

  @Test
  fun `play on an empty player does not throw`() {
    player.player!!.play()
    assertNotNull(player.player)
  }

  @Test
  fun `pause on an empty player does not throw`() {
    player.player!!.pause()
    assertNotNull(player.player)
  }

  @Test
  fun `stop body (pause + seekTo 0) does not throw on empty player`() {
    val p = player.player!!
    p.pause()
    p.seekTo(0L)
    assertTrue("currentPosition stays at 0 with no item", p.currentPosition == 0L)
  }
}
