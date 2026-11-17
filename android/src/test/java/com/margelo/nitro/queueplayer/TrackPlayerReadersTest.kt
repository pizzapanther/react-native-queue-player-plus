package com.margelo.nitro.queueplayer

import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Synchronous state readers + subscribe-time auto-fire for the buffer /
 * sleep-timer / remote-control surface. These paths read lib-owned fields and
 * fire once on subscribe with the current value, so they're reachable under
 * Robolectric without a real decoder pipeline (which never reaches
 * STATE_READY). Driving actual buffer/sleep transitions is a device concern.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@UnstableApi
class TrackPlayerReadersTest {

  private lateinit var player: TrackPlayer
  private lateinit var serviceHandle: PlaybackServiceTestHandle

  private fun context() = ApplicationProvider.getApplicationContext<android.content.Context>()

  private fun idle() = shadowOf(Looper.getMainLooper()).idle()

  @Before
  fun setUp() {
    player = TrackPlayer()
    serviceHandle = bindPlaybackServiceForTest(player, context())
    player.configureInternal(
      PlayerConfig(
        enableDiagnosticLogs = null,
        httpHeaders = null, userAgent = null, autoRetries = null, retryBackoffMs = null,
        networkTimeoutMs = null, audioContentType = null, audioCategoryOptions = null,
        voiceVocabularyDonationEnabled = null,
        allowsExternalPlayback = null,
      visualizationEnabled = null, clampSeekToBuffered = null,
        lookaheadCacheMaxSizeMb = null, lookaheadCacheEvictionPolicy = null, progressUpdateIntervalMs = null, backgroundProgressUpdateIntervalMs = null, placeholderArtworkUri = null, skipToPreviousBehavior = null
      ),
      context()
    )
  }

  @After
  fun tearDown() {
    player.destroyInternal()
    serviceHandle.destroy()
  }

  // --- Remote controls

  @Test
  fun `getProgress returns zeros before configuration and with an empty queue`() {
    val zero = PlayerProgress(0.0, 0.0, 0.0)
    assertEquals(zero, TrackPlayer().getProgress())
    assertEquals(zero, player.getProgress())
  }

  @Test
  fun `getProgress reads paused position without subscribers and does not consume the next event`() {
    val track = TrackItem(
      id = "local", url = "file:///queue-player-test/progress.m4a",
      title = null, artist = null, album = null, duration = null, artworkUrl = null,
      extras = null, replayGainTrackGain = null, replayGainTrackPeak = null,
      replayGainAlbumGain = null, replayGainAlbumPeak = null)
    player.setQueueInternal(listOf(track))
    serviceHandle.engine.seekTo(0, 12_500)
    val snapshot = player.getProgress()
    assertEquals(12.5, snapshot.position, 0.001)
    assertEquals(0.0, snapshot.duration, 0.0)
    assertEquals(0.0, snapshot.buffered, 0.0)

    val seen = mutableListOf<PlayerProgress>()
    val dispose = player.onProgress { seen.add(it) }
    assertEquals(snapshot, player.getProgress())
    assertTrue(seen.isEmpty())
    player.emitProgressTick()
    assertEquals(listOf(snapshot), seen)
    dispose()

    player.clearQueueInternal()
    assertEquals(PlayerProgress(0.0, 0.0, 0.0), player.getProgress())
  }

  @Test
  fun `getRemoteControls defaults to track mode with 15s intervals`() {
    val rc = player.getRemoteControls()
    assertEquals(RemoteSkipMode.TRACK, rc.skipMode)
    assertEquals(15.0, rc.forwardJumpInterval, 0.001)
    assertEquals(15.0, rc.backwardJumpInterval, 0.001)
  }

  @Test
  fun `setRemoteControls round-trips through getRemoteControls`() {
    player.setRemoteControlsInternal(
      RemoteControlOptions(RemoteSkipMode.INTERVAL, 30.0, 45.0)
    )
    val rc = player.getRemoteControls()
    assertEquals(RemoteSkipMode.INTERVAL, rc.skipMode)
    assertEquals(30.0, rc.forwardJumpInterval, 0.001)
    assertEquals(45.0, rc.backwardJumpInterval, 0.001)
  }

  @Test
  fun `setRemoteControls clamps negative intervals to zero`() {
    player.setRemoteControlsInternal(
      RemoteControlOptions(RemoteSkipMode.INTERVAL, -5.0, -10.0)
    )
    val rc = player.getRemoteControls()
    assertEquals(0.0, rc.forwardJumpInterval, 0.001)
    assertEquals(0.0, rc.backwardJumpInterval, 0.001)
  }

  // --- Buffer / seek / fully-buffered readers

  @Test
  fun `getBufferState defaults to EMPTY before playback`() {
    assertEquals(BufferState.EMPTY, player.getBufferState())
  }

  @Test
  fun `isFullyBuffered defaults to false before playback`() {
    assertFalse(player.isFullyBuffered())
  }

  @Test
  fun `getIsSeekable is false on an empty timeline`() {
    // No current media item => nothing to seek within.
    assertFalse(player.getIsSeekable())
  }

  // --- Sleep timer reader

  @Test
  fun `getSleepTimer defaults to inactive`() {
    val timer = player.getSleepTimer()
    assertFalse(timer.active)
    assertFalse(timer.endOfTrack)
  }

  // --- Subscribe-time auto-fire (fires once with the current value)

  @Test
  fun `onBufferStateChange fires once on subscribe with the current state`() {
    val seen = mutableListOf<BufferState>()
    val unsubscribe = player.onBufferStateChange { seen.add(it) }
    idle()
    assertEquals(1, seen.size)
    assertEquals(BufferState.EMPTY, seen[0])
    unsubscribe()
    // A disposed listener receives nothing further even if the looper turns.
    idle()
    assertEquals(1, seen.size)
  }

  @Test
  fun `onFullyBufferedChange fires once on subscribe with the current value`() {
    val seen = mutableListOf<Boolean>()
    val unsubscribe = player.onFullyBufferedChange { seen.add(it) }
    idle()
    assertEquals(1, seen.size)
    assertFalse(seen[0])
    unsubscribe()
  }

  @Test
  fun `onSleepTimerChange fires once on subscribe with the current snapshot`() {
    val seen = mutableListOf<SleepTimerState>()
    val unsubscribe = player.onSleepTimerChange { seen.add(it) }
    idle()
    assertEquals(1, seen.size)
    assertFalse(seen[0].active)
    unsubscribe()
    idle()
    assertEquals(1, seen.size)
  }
}
