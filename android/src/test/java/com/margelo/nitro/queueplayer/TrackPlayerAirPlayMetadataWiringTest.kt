package com.margelo.nitro.queueplayer

import android.media.AudioDeviceInfo
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import androidx.test.core.app.ApplicationProvider
import com.margelo.nitro.queueplayer.cast.CastSession
import com.margelo.nitro.queueplayer.cast.CastSinkRouter
import com.margelo.nitro.queueplayer.cast.MetadataAwareSession
import kotlinx.coroutines.CoroutineScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.nio.ByteBuffer

/**
 * Receiver-display metadata sync wiring around the dedicated `AirPlayEngine`.
 *
 * When an AirPlay (push-PCM) session becomes active, `TrackPlayer` swaps in the
 * `AirPlayEngine` and starts the active session's receiver metadata sync against
 * that engine's ExoPlayer (`MetadataAwareSession.onActivated`); the sync is
 * stopped when the AirPlay engine is released or the session closes
 * (`MetadataAwareSession.onDeactivated`).
 *
 * The route flip is driven through the production path — `CastSinkRouter`'s
 * activate/deactivate transitions, which `TrackPlayer`'s route listener turns
 * into a posted `applyEngineSwapForMode`. The sync itself is observed through a
 * fake [CastSession.PcmStream] that also implements [MetadataAwareSession] and
 * records the `onActivated`/`onDeactivated` calls plus the `player`/`scope` args.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@UnstableApi
class TrackPlayerAirPlayMetadataWiringTest {

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
        httpHeaders = null, userAgent = null, autoRetries = null,
        retryBackoffMs = null, networkTimeoutMs = null, audioContentType = null,
        audioCategoryOptions = null, voiceVocabularyDonationEnabled = null, allowsExternalPlayback = null,  visualizationEnabled = null,
        clampSeekToBuffered = null,
      lookaheadCacheMaxSizeMb = null, lookaheadCacheEvictionPolicy = null, progressUpdateIntervalMs = null, backgroundProgressUpdateIntervalMs = null, placeholderArtworkUri = null, skipToPreviousBehavior = null,
      ),
      context(),
    )
    // A populated queue so the swap re-installs a real queue on the new engine,
    // matching the production path where `onActivated` is fired after the queue
    // install reads a populated player.
    player.tracks = listOf(
      TrackItem("t1", "https://example.com/1.m4a", null, null, null, null, null, null, null, null, null, null),
      TrackItem("t2", "https://example.com/2.m4a", null, null, null, null, null, null, null, null, null, null),
    )
    player.currentTrackIndex = 0
  }

  @After
  fun tearDown() {
    // Clear the active session first so any AirPlay engine swaps back to local
    // and its sync is torn down, then release the player + service. The
    // CastSinkRouter singleton survives across tests, so reset it explicitly.
    CastSinkRouter.clearActiveSession()
    idle()
    player.destroyInternal()
    idle()
    serviceHandle.destroy()
    CastSinkRouter.clearActiveSession()
  }

  // ----- 1. Activation on AirPlay swap-in -----

  @Test
  fun `activating an AirPlay session starts the receiver metadata sync against the AirPlay engine player`() {
    val session = FakeMetadataAwarePcmSession()

    CastSinkRouter.setActiveSession(session)
    idle()

    assertEquals("metadata sync started exactly once on swap-in", 1, session.activatedCount)
    val airplayPlayer = CastSinkRouter.player()
    assertNotNull("AirPlay engine registered its ExoPlayer", airplayPlayer)
    assertSame(
      "sync bound to the AirPlay engine's ExoPlayer, not null",
      airplayPlayer, session.lastActivatedPlayer,
    )
    assertSame(
      "sync bound to the AirPlay engine that TrackPlayer swapped in",
      player.player, session.lastActivatedPlayer,
    )
    assertNotNull("sync received a coroutine scope", session.lastActivatedScope)
  }

  // ----- 2. Deactivation on swap-away to local -----

  @Test
  fun `disconnecting back to local stops the metadata sync exactly once`() {
    val session = FakeMetadataAwarePcmSession()
    CastSinkRouter.setActiveSession(session)
    idle()
    assertEquals(1, session.activatedCount)

    // Production disconnect (`CastManager.closeAirplaySession`): clear the route
    // (swaps the AirPlay engine out for local), then close the session.
    CastSinkRouter.clearActiveSession()
    idle()
    session.close()

    assertEquals("metadata sync stopped on disconnect", 1, session.deactivatedCount)
    assertTrue("no live sync after swap-away", session.activatedCount == session.deactivatedCount)
  }

  // ----- 3. Receiver death -----

  @Test
  fun `receiver death stops the metadata sync exactly once`() {
    val session = FakeMetadataAwarePcmSession()
    CastSinkRouter.setActiveSession(session)
    idle()
    assertEquals(1, session.activatedCount)

    // Production death (`CastManager.onAirplaySessionDied`): the native death
    // path closes the session (stopping its sync) and the route is cleared so
    // the AirPlay engine swaps back to local.
    CastSinkRouter.clearActiveSession()
    session.simulateRemoteDied()
    idle()

    assertEquals("single deactivation on receiver death", 1, session.deactivatedCount)
    assertTrue("no live sync after receiver death", session.activatedCount == session.deactivatedCount)
  }

  // ----- 4. AirPlay -> AirPlay rebuild (double-activation guard) -----

  @Test
  fun `rebuilding the AirPlay engine deactivates the prior sync and reactivates against the new engine player`() {
    val session = FakeMetadataAwarePcmSession()
    CastSinkRouter.setActiveSession(session)
    idle()
    assertEquals(1, session.activatedCount)
    val firstPlayer = session.lastActivatedPlayer
    assertNotNull(firstPlayer)

    // Re-fire activation while the session is still active — the route listener
    // rebuilds the AirPlayEngine. The swap releases the OLD engine (its
    // release() deactivates the still-active session's sync) before the NEW
    // engine's onActivated re-binds, so a single live sync is preserved.
    CastSinkRouter.setActiveSession(session)
    idle()

    assertEquals("prior sync deactivated on rebuild", 1, session.deactivatedCount)
    assertEquals("a fresh sync activated on rebuild", 2, session.activatedCount)
    assertNotSame(
      "fresh sync bound to the NEW AirPlay engine's ExoPlayer",
      firstPlayer, session.lastActivatedPlayer,
    )
    assertSame(
      "fresh sync bound to the engine TrackPlayer now holds",
      player.player, session.lastActivatedPlayer,
    )
    // Re-entrancy: at no point were two activations live without a matching
    // deactivation. The rebuild path is the production trigger for a repeat
    // `MetadataAwareSession.onActivated`, and the real sessions stop the prior
    // sync before creating the next one.
    assertTrue(
      "never two live syncs across the rebuild",
      session.activatedCount - session.deactivatedCount == 1,
    )
  }

  // ----- 5. No activation for a non-AirPlay swap -----

  @Test
  fun `a non-AirPlay engine swap does not start a metadata sync`() {
    val session = FakeMetadataAwarePcmSession()

    // No active session: firing the deactivate route change drives a plain
    // gapless engine swap (airplaySink == null), so the new engine is not an
    // AirPlayEngine and no session is activated.
    CastSinkRouter.clearActiveSession()
    idle()

    assertEquals("no metadata sync for a local engine swap", 0, session.activatedCount)
    assertEquals(0, session.deactivatedCount)
  }

  // ----- Test fakes -----

  /**
   * A push-PCM session that also displays metadata on the receiver — the shape
   * of the real `AirPlaySession` / `AirPlay2Session`. Records the metadata-sync
   * lifecycle so the wiring can be asserted, and mirrors the real sessions'
   * re-entrant `onActivated` (stop prior before starting next) and
   * `close()` -> `onDeactivated()` behaviour.
   */
  private class FakeMetadataAwarePcmSession :
    CastSession.PcmStream(), MetadataAwareSession {

    private val sink = FakeAudioSink()

    var activatedCount = 0
      private set
    var deactivatedCount = 0
      private set
    var lastActivatedPlayer: Player? = null
      private set
    var lastActivatedScope: CoroutineScope? = null
      private set

    /** Non-null while a sync is "running", mirroring `metadataSync != null`. */
    private var syncRunning = false
    private var closed = false

    override fun audioSink(): AudioSink = sink
    override fun setVolume(value: Float) { /* no-op */ }

    override fun onActivated(player: Player, scope: CoroutineScope) {
      // Re-entrant safe, exactly like the real sessions: stop any prior sync
      // before rebinding so a repeat activation never leaks an orphaned sync.
      if (syncRunning) {
        syncRunning = false
        deactivatedCount++
      }
      activatedCount++
      lastActivatedPlayer = player
      lastActivatedScope = scope
      syncRunning = true
    }

    override fun onDeactivated() {
      if (!syncRunning) return
      syncRunning = false
      deactivatedCount++
    }

    override fun close() {
      if (closed) return
      closed = true
      onDeactivated()
    }

    /** Mirror the native receiver-death path: the session closes itself. */
    fun simulateRemoteDied() {
      close()
    }
  }

  /** Minimal no-op [AudioSink] — only needed to satisfy `audioSink()`. */
  private class FakeAudioSink : AudioSink {
    override fun setListener(listener: AudioSink.Listener) {}
    override fun supportsFormat(format: Format): Boolean = true
    override fun getFormatSupport(format: Format): Int = AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {}
    override fun play() {}
    override fun handleDiscontinuity() {}
    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean = true
    override fun playToEndOfStream() {}
    override fun isEnded(): Boolean = false
    override fun hasPendingData(): Boolean = false
    override fun getCurrentPositionUs(sourceEnded: Boolean): Long = 0L
    override fun flush() {}
    override fun reset() {}
    override fun release() {}
    override fun pause() {}
    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {}
    override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters.DEFAULT
    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {}
    override fun getSkipSilenceEnabled(): Boolean = false
    override fun setAudioAttributes(audioAttributes: AudioAttributes) {}
    override fun getAudioAttributes(): AudioAttributes? = null
    override fun setAudioSessionId(audioSessionId: Int) {}
    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) {}
    override fun setPreferredDevice(audioDeviceInfo: AudioDeviceInfo?) {}
    override fun setVolume(volume: Float) {}
    override fun enableTunnelingV21() {}
    override fun disableTunneling() {}
    override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport = AudioOffloadSupport.DEFAULT_UNSUPPORTED
    override fun getAudioTrackBufferSizeUs(): Long = 0L
  }
}
