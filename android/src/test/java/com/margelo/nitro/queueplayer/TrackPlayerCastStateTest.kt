package com.margelo.nitro.queueplayer

import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import com.margelo.nitro.queueplayer.cast.FakeRemotePlayer
import com.margelo.nitro.queueplayer.cast.PlaybackStateRouter
import com.margelo.nitro.queueplayer.cast.RemotePlaybackState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `getState()` reports the receiver while a cast session owns playback. The
 * local player is parked and paused for the session's duration, so reading it
 * would describe nothing the user can hear.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@UnstableApi
class TrackPlayerCastStateTest {

  private lateinit var player: TrackPlayer
  private lateinit var serviceHandle: PlaybackServiceTestHandle
  private val remote = FakeRemotePlayer()

  private fun context() = ApplicationProvider.getApplicationContext<android.content.Context>()

  @Before
  fun setUp() {
    PlaybackStateRouter.setActive(null)
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
        lookaheadCacheMaxSizeMb = null, lookaheadCacheEvictionPolicy = null,
        progressUpdateIntervalMs = null, backgroundProgressUpdateIntervalMs = null,
        placeholderArtworkUri = null, skipToPreviousBehavior = null
      ),
      context()
    )
  }

  @After
  fun tearDown() {
    PlaybackStateRouter.setActive(null)
    player.destroyInternal()
    serviceHandle.destroy()
  }

  @Test
  fun `getProgress uses receiver values without a local queue or subscription`() {
    remote.reportState(RemotePlaybackState.PLAYING)
    remote.backingPositionMs.value = 12_500
    remote.backingDurationMs.value = 90_000
    PlaybackStateRouter.setActive(remote)
    val snapshot = player.getProgress()
    assertEquals(PlayerProgress(12.5, 90.0, 0.0), snapshot)
    val seen = mutableListOf<PlayerProgress>()
    val dispose = player.onProgress { seen.add(it) }
    player.emitProgressTick()
    assertEquals(listOf(snapshot), seen)
    remote.backingDurationMs.value = -1
    assertEquals(0.0, player.getProgress().duration, 0.0)
    dispose()
  }

  @Test
  fun `reports the receiver's playing state during a cast session`() {
    remote.reportState(RemotePlaybackState.PLAYING)
    PlaybackStateRouter.setActive(remote)

    assertEquals(PlayerState.PLAYING, player.getState())
  }

  @Test
  fun `reports the receiver's paused state during a cast session`() {
    remote.reportState(RemotePlaybackState.PAUSED)
    PlaybackStateRouter.setActive(remote)

    assertEquals(PlayerState.PAUSED, player.getState())
  }

  @Test
  fun `maps a buffering receiver to buffering`() {
    remote.reportState(RemotePlaybackState.BUFFERING)
    PlaybackStateRouter.setActive(remote)

    assertEquals(PlayerState.BUFFERING, player.getState())
  }

  @Test
  fun `maps an ended receiver to ended`() {
    remote.reportState(RemotePlaybackState.ENDED)
    PlaybackStateRouter.setActive(remote)

    assertEquals(PlayerState.ENDED, player.getState())
  }

  @Test
  fun `reads the local player until the receiver reports`() {
    // Session activating: the receiver holds a state it has not reported, so
    // the local player answers. The staged PLAYING makes the two distinct —
    // with no queue configured the local read is NONE.
    remote.backingState.value = RemotePlaybackState.PLAYING
    PlaybackStateRouter.setActive(remote)

    assertFalse(remote.hasReportedState)
    assertEquals(PlayerState.NONE, player.getState())
  }

  @Test
  fun `keeps reading the receiver once it has reported`() {
    // A receiver that has spoken stays authoritative: a later idle means it
    // genuinely has nothing loaded, not that it is still activating.
    remote.reportState(RemotePlaybackState.PLAYING)
    remote.backingState.value = RemotePlaybackState.IDLE
    PlaybackStateRouter.setActive(remote)

    assertTrue(remote.hasReportedState)
    assertEquals(PlayerState.NONE, player.getState())
  }

  @Test
  fun `reads the local player when no cast session is active`() {
    // An unregistered session must not be consulted, however it reports.
    remote.reportState(RemotePlaybackState.PLAYING)

    assertEquals(PlayerState.NONE, player.getState())
  }
}
