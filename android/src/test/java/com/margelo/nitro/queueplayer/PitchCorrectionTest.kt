package com.margelo.nitro.queueplayer

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit coverage for [PitchCorrection.playbackParameters], the pure mapping
 * from a [PitchCorrectionMode] + speed to Media3 [androidx.media3.common.PlaybackParameters].
 *
 * Engine wiring (which player the params land on) is out of scope for
 * this pure test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PitchCorrectionTest {

  @Test
  fun `unity speed yields neutral params for every mode`() {
    for (mode in PitchCorrectionMode.values()) {
      val p = PitchCorrection.playbackParameters(mode, 1.0f)
      assertEquals(1.0f, p.speed, 0f)
      assertEquals(1.0f, p.pitch, 0f)
    }
  }

  @Test
  fun `none lets pitch follow rate`() {
    val up = PitchCorrection.playbackParameters(PitchCorrectionMode.NONE, 1.5f)
    assertEquals(1.5f, up.speed, 0f)
    assertEquals(1.5f, up.pitch, 0f)
    val down = PitchCorrection.playbackParameters(PitchCorrectionMode.NONE, 0.5f)
    assertEquals(0.5f, down.speed, 0f)
    assertEquals(0.5f, down.pitch, 0f)
  }

  @Test
  fun `voice preserves pitch off unity`() {
    val up = PitchCorrection.playbackParameters(PitchCorrectionMode.VOICE, 1.5f)
    assertEquals(1.5f, up.speed, 0f)
    assertEquals(1.0f, up.pitch, 0f)
    val down = PitchCorrection.playbackParameters(PitchCorrectionMode.VOICE, 0.5f)
    assertEquals(0.5f, down.speed, 0f)
    assertEquals(1.0f, down.pitch, 0f)
  }

  @Test
  fun `music preserves pitch off unity`() {
    val p = PitchCorrection.playbackParameters(PitchCorrectionMode.MUSIC, 2.0f)
    assertEquals(2.0f, p.speed, 0f)
    assertEquals(1.0f, p.pitch, 0f)
  }
}
