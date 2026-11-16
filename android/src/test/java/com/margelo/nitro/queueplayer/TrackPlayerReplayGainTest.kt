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
 * Coverage for the JS-facing ReplayGain mode surface on [TrackPlayer].
 *
 * The Promise-returning [TrackPlayer.setReplayGainMode] cannot be
 * driven from the JVM unit harness — Nitro's `Promise` constructor
 * calls `initHybrid()` → JNI which the Robolectric runtime doesn't
 * load. The setter is a thin wrapper that writes through
 * [ReplayGainConfig.setMode] then asks the active engine to
 * recompute; this file pins the synchronous read path
 * ([TrackPlayer.getReplayGainMode]) round-trips with
 * [ReplayGainConfig].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@UnstableApi
class TrackPlayerReplayGainTest {

  private lateinit var player: TrackPlayer
  private lateinit var serviceHandle: PlaybackServiceTestHandle

  @Before
  fun setUp() {
    player = TrackPlayer()
    serviceHandle = bindPlaybackServiceForTest(
      player,
      ApplicationProvider.getApplicationContext()
    )
  }

  @After
  fun tearDown() {
    player.destroyInternal()
    serviceHandle.destroy()
    // Reset the process-wide config so subsequent tests in the same
    // JVM aren't dependent on order.
    ReplayGainConfig.setMode(ReplayGainMode.OFF)
  }

  @Test
  fun `default getReplayGainMode is OFF`() {
    assertEquals(ReplayGainMode.OFF, player.getReplayGainMode())
  }

  @Test
  fun `getReplayGainMode reflects latest ReplayGainConfig write`() {
    ReplayGainConfig.setMode(ReplayGainMode.TRACK)
    assertEquals(ReplayGainMode.TRACK, player.getReplayGainMode())

    ReplayGainConfig.setMode(ReplayGainMode.ALBUM)
    assertEquals(ReplayGainMode.ALBUM, player.getReplayGainMode())

    ReplayGainConfig.setMode(ReplayGainMode.OFF)
    assertEquals(ReplayGainMode.OFF, player.getReplayGainMode())
  }
}
