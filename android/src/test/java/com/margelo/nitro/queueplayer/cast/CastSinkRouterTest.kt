package com.margelo.nitro.queueplayer.cast

import android.media.AudioDeviceInfo
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer

/**
 * Unit coverage for [CastSinkRouter] — the route-change signal hub +
 * active-session holder for AirPlay push-PCM sessions. The audio itself is
 * carried by the dedicated `AirPlayEngine`; this object just records the active
 * session and broadcasts activate/deactivate transitions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@UnstableApi
class CastSinkRouterTest {

  @After
  fun teardown() {
    // Singleton state survives across tests; clear so the next starts clean.
    CastSinkRouter.clearActiveSession()
  }

  @Test
  fun `setActiveSession marks remote active and records the session`() {
    assertFalse("no remote before activate", CastSinkRouter.isRemoteActive())

    val session = FakePcmStreamSession(FakeAudioSink())
    CastSinkRouter.setActiveSession(session)

    assertTrue("remote active after setActiveSession", CastSinkRouter.isRemoteActive())
    assertSame("activeSession returns the recorded session", session, CastSinkRouter.activeSession())

    CastSinkRouter.clearActiveSession()
    assertFalse("no remote after clear", CastSinkRouter.isRemoteActive())
    assertEquals("activeSession null after clear", null, CastSinkRouter.activeSession())
  }

  @Test
  fun `setActiveSession + clearActiveSession fire route-change listeners`() {
    val transitions = mutableListOf<Boolean>()
    val dispose = CastSinkRouter.addRouteChangeListener { active -> transitions.add(active) }
    try {
      CastSinkRouter.setActiveSession(FakePcmStreamSession(FakeAudioSink()))
      CastSinkRouter.clearActiveSession()
      assertEquals("router fires activate then deactivate", listOf(true, false), transitions)
    } finally {
      dispose()
    }
  }

  @Test
  fun `addRouteChangeListener does not replay current state on subscribe`() {
    CastSinkRouter.setActiveSession(FakePcmStreamSession(FakeAudioSink()))

    val seen = mutableListOf<Boolean>()
    val dispose = CastSinkRouter.addRouteChangeListener { active -> seen.add(active) }
    try {
      assertTrue("no synchronous replay — subscribe is a pure transition stream", seen.isEmpty())
    } finally {
      dispose()
    }
  }

  // ----- Test fakes -----

  private class FakePcmStreamSession(private val sink: AudioSink) : CastSession.PcmStream() {
    override fun audioSink(): AudioSink = sink
    override fun setVolume(value: Float) { /* no-op */ }
    override fun close() { /* no-op */ }
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
