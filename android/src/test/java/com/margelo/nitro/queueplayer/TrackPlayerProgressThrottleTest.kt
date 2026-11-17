package com.margelo.nitro.queueplayer

import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import com.margelo.nitro.queueplayer.cast.FakeRemotePlayer
import com.margelo.nitro.queueplayer.cast.PlaybackStateRouter
import com.margelo.nitro.queueplayer.cast.RemotePlaybackState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * Drives the progress-emission throttle through the real `emitProgressTick`
 * body across an advancing uptime clock. The tick body is invoked directly
 * (Robolectric doesn't run the mainHandler loop without an explicit idle), and
 * the gate reads `SystemClock.uptimeMillis()`, which `ShadowSystemClock`
 * controls — so N direct ticks spaced by the native 500ms period reproduce the
 * production cadence deterministically.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@UnstableApi
class TrackPlayerProgressThrottleTest {

  private val remote = FakeRemotePlayer()
  private lateinit var player: TrackPlayer
  private lateinit var serviceHandle: PlaybackServiceTestHandle

  private fun context() = ApplicationProvider.getApplicationContext<android.content.Context>()

  private fun config(foregroundMs: Double?, backgroundMs: Double? = null) = PlayerConfig(
    enableDiagnosticLogs = null,
    httpHeaders = null, userAgent = null, autoRetries = null, retryBackoffMs = null,
    networkTimeoutMs = null, audioContentType = null, audioCategoryOptions = null,
    voiceVocabularyDonationEnabled = null,
    allowsExternalPlayback = null,
      visualizationEnabled = null, clampSeekToBuffered = null, lookaheadCacheMaxSizeMb = null, lookaheadCacheEvictionPolicy = null,
    progressUpdateIntervalMs = foregroundMs, backgroundProgressUpdateIntervalMs = backgroundMs, placeholderArtworkUri = null, skipToPreviousBehavior = null
  )

  @Before
  fun setUp() {
    player = TrackPlayer()
    serviceHandle = bindPlaybackServiceForTest(player, context())
    // A controllable playing receiver exercises the real tick and cadence
    // without requiring Robolectric to decode audio to STATE_READY.
    remote.reportState(RemotePlaybackState.PLAYING)
    PlaybackStateRouter.setActive(remote)
  }

  @After
  fun tearDown() {
    PlaybackStateRouter.setActive(null)
    player.destroyInternal()
    serviceHandle.destroy()
  }

  // Count onProgress emissions across `tickCount` direct ticks, each followed
  // by a `tickPeriodMs` advance of the uptime clock.
  private fun countEmitsOverTicks(tickCount: Int, tickPeriodMs: Long): Int {
    var emits = 0
    val dispose = player.onProgress { emits++ }
    repeat(tickCount) {
      player.emitProgressTick()
      ShadowSystemClock.advanceBy(Duration.ofMillis(tickPeriodMs))
    }
    dispose()
    return emits
  }

  @Test
  fun `default interval emits on every 500ms tick`() {
    player.configureInternal(config(foregroundMs = null), context())
    // 10 ticks 500ms apart; the default 500ms interval + half-sampler tolerance
    // emits every tick (no ~half drop from jitter).
    assertEquals(10, countEmitsOverTicks(tickCount = 10, tickPeriodMs = 500))
  }

  @Test
  fun `configured 1000ms interval throttles to roughly half the ticks`() {
    player.configureInternal(config(foregroundMs = 1000.0), context())
    // 11 ticks 500ms apart over 5s; a 1000ms interval emits every other tick.
    val emits = countEmitsOverTicks(tickCount = 11, tickPeriodMs = 500)
    assertTrue("expected ~6 emits at 1Hz over 5s, got $emits", emits in 5..7)
  }

  @Test
  fun `sub-500 interval clamps to the 500ms tick floor`() {
    // 200ms requested, but the native tick is 500ms — the floor clamp means
    // every tick still emits (no faster-than-2Hz foreground rate).
    player.configureInternal(config(foregroundMs = 200.0), context())
    assertEquals(10, countEmitsOverTicks(tickCount = 10, tickPeriodMs = 500))
  }
  @Test
  fun `inactive receiver states stay silent without consuming the next playing tick`() {
    player.configureInternal(config(foregroundMs = 1000.0), context())
    val received = mutableListOf<PlayerProgress>()
    val dispose = player.onProgress { received.add(it) }
    for (state in RemotePlaybackState.entries.filter { it != RemotePlaybackState.PLAYING }) {
      remote.reportState(state)
      remote.backingPositionMs.value += 1000
      player.emitProgressTick()
    }
    assertTrue(received.isEmpty())
    // No clock advance: the skipped ticks must not spend the first emit slot.
    remote.reportState(RemotePlaybackState.PLAYING)
    player.emitProgressTick()
    assertEquals(1, received.size)
    remote.reportState(RemotePlaybackState.PAUSED)
    ShadowSystemClock.advanceBy(Duration.ofMillis(1000))
    player.emitProgressTick()
    assertEquals(1, received.size)
    remote.reportState(RemotePlaybackState.PLAYING)
    player.emitProgressTick()
    assertEquals(2, received.size)
    dispose()
  }

}
