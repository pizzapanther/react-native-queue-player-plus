package com.margelo.nitro.queueplayer

import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import com.margelo.nitro.queueplayer.cast.FakeRemotePlayer
import com.margelo.nitro.queueplayer.cast.PlaybackStateRouter
import com.margelo.nitro.queueplayer.cast.RemotePlaybackState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * OS-surface transport commands — hardware and Bluetooth media keys, and the
 * media-session skip callbacks — drive the receiver while a cast session owns
 * playback. Routing them to the local engine would play the phone speaker over
 * the receiver, or skip a queue nobody is listening to.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@UnstableApi
class TrackPlayerCastCommandRoutingTest {

  private lateinit var player: TrackPlayer
  private lateinit var serviceHandle: PlaybackServiceTestHandle
  private val remote = FakeRemotePlayer()

  private fun context() = ApplicationProvider.getApplicationContext<android.content.Context>()

  private fun handler() = player.mediaSessionCommandHandler

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
  fun `media play key drives the receiver`() {
    PlaybackStateRouter.setActive(remote)

    handler().onMediaPlay()

    assertEquals(listOf("play"), remote.transportCalls)
  }

  @Test
  fun `media pause key drives the receiver`() {
    PlaybackStateRouter.setActive(remote)

    handler().onMediaPause()

    assertEquals(listOf("pause"), remote.transportCalls)
  }

  @Test
  fun `toggle key pauses a playing receiver`() {
    remote.reportState(RemotePlaybackState.PLAYING)
    PlaybackStateRouter.setActive(remote)

    handler().onMediaTogglePlayPause()

    assertEquals(listOf("pause"), remote.transportCalls)
  }

  @Test
  fun `toggle key pauses a buffering receiver`() {
    remote.reportState(RemotePlaybackState.BUFFERING)
    PlaybackStateRouter.setActive(remote)

    handler().onMediaTogglePlayPause()

    assertEquals(listOf("pause"), remote.transportCalls)
  }

  @Test
  fun `toggle key pauses a loading receiver`() {
    remote.reportState(RemotePlaybackState.LOADING)
    PlaybackStateRouter.setActive(remote)

    handler().onMediaTogglePlayPause()

    assertEquals(listOf("pause"), remote.transportCalls)
  }

  @Test
  fun `toggle key resumes a paused receiver`() {
    remote.reportState(RemotePlaybackState.PAUSED)
    PlaybackStateRouter.setActive(remote)

    handler().onMediaTogglePlayPause()

    assertEquals(listOf("play"), remote.transportCalls)
  }

  @Test
  fun `media skip keys drive the receiver queue`() {
    PlaybackStateRouter.setActive(remote)

    handler().onMediaSkipNext()
    handler().onMediaSkipPrevious()

    assertEquals(listOf("queueNext", "queuePrev"), remote.transportCalls)
  }

  @Test
  fun `session skip commands drive the receiver queue and stay claimed`() {
    PlaybackStateRouter.setActive(remote)

    assertEquals(true, handler().onSkipToNextCommand())
    assertEquals(true, handler().onSkipToPreviousCommand())
    assertEquals(listOf("queueNext", "queuePrev"), remote.transportCalls)
  }

  @Test
  fun `play key drives the local engine when no cast session is active`() {
    handler().onMediaPlay()

    assertEquals(true, serviceHandle.engine.mediaSessionPlayer.playWhenReady)
    assertEquals(emptyList<String>(), remote.transportCalls)
  }

  @Test
  fun `pause key drives the local engine when no cast session is active`() {
    handler().onMediaPlay()
    handler().onMediaPause()

    assertEquals(false, serviceHandle.engine.mediaSessionPlayer.playWhenReady)
    assertEquals(emptyList<String>(), remote.transportCalls)
  }

  @Test
  fun `toggle key drives the local engine when no cast session is active`() {
    handler().onMediaPlay()
    handler().onMediaTogglePlayPause()

    assertEquals(false, serviceHandle.engine.mediaSessionPlayer.playWhenReady)
    assertEquals(emptyList<String>(), remote.transportCalls)
  }
}
