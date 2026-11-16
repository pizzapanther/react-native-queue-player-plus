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
 * Coverage for the JS-facing pitch-correction mode surface on
 * [TrackPlayer].
 *
 * The Promise-returning [TrackPlayer.setPitchCorrectionMode] cannot be
 * driven from the JVM unit harness — Nitro's `Promise` constructor calls
 * `initHybrid()` → JNI which the Robolectric runtime doesn't load. This
 * file pins the default and the synchronous read path
 * ([TrackPlayer.getPitchCorrectionMode]) against the canonical state
 * field.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@UnstableApi
class TrackPlayerPitchCorrectionTest {

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
  }

  @Test
  fun `default getPitchCorrectionMode is VOICE`() {
    assertEquals(PitchCorrectionMode.VOICE, player.getPitchCorrectionMode())
  }

  @Test
  fun `getPitchCorrectionMode reflects the canonical state field`() {
    player.pitchCorrectionModeState = PitchCorrectionMode.MUSIC
    assertEquals(PitchCorrectionMode.MUSIC, player.getPitchCorrectionMode())

    player.pitchCorrectionModeState = PitchCorrectionMode.NONE
    assertEquals(PitchCorrectionMode.NONE, player.getPitchCorrectionMode())
  }
}
