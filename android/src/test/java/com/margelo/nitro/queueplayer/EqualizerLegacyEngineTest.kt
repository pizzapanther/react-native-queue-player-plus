package com.margelo.nitro.queueplayer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import android.media.audiofx.Equalizer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadow.api.Shadow
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioEffect

/**
 * State-mirror coverage for [EqualizerLegacyEngine] on the API <28 path:
 * the band mapping, the dB↔mB conversion, and what reaches each attached
 * session. [ShadowEqualizer] supplies the hardware-side band count, centre
 * frequencies and gain range that legacy AudioEffect varies per OEM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], shadows = [ShadowEqualizer::class])
class EqualizerLegacyEngineTest {

  @Test
  fun `default state is disabled with all bands at zero gain`() {
    val engine = EqualizerLegacyEngine()
    assertFalse(engine.isEnabled())
    assertFalse(engine.isAttached())
    assertArrayEquals(FloatArray(10) { 0f }, engine.getBandGains(), 0f)
  }

  @Test
  fun `setBandGain stores per-band gain before any attach`() {
    val engine = EqualizerLegacyEngine()
    engine.setBandGain(2, 6f)
    engine.setBandGain(8, -3f)
    val gains = engine.getBandGains()
    assertEquals(6f, gains[2])
    assertEquals(-3f, gains[8])
  }

  @Test
  fun `setBandGain ignores out-of-range indices`() {
    val engine = EqualizerLegacyEngine()
    engine.setBandGain(-1, 9f)
    engine.setBandGain(10, 9f)
    assertArrayEquals(FloatArray(10) { 0f }, engine.getBandGains(), 0f)
  }

  @Test
  fun `setBandGain clamps gains outside the project spec range`() {
    val engine = EqualizerLegacyEngine()
    engine.setBandGain(0, 99f)
    engine.setBandGain(1, -99f)
    assertEquals(12f, engine.getBandGains()[0])
    assertEquals(-12f, engine.getBandGains()[1])
  }

  @Test
  fun `setAllBandGains clamps each entry into the project spec range`() {
    val engine = EqualizerLegacyEngine()
    engine.setAllBandGains(floatArrayOf(-99f, 99f, 5f, 0f, 0f, 0f, 0f, 0f, 0f, 0f))
    val gains = engine.getBandGains()
    assertEquals(-12f, gains[0])
    assertEquals(12f, gains[1])
    assertEquals(5f, gains[2])
  }

  @Test
  fun `setEnabled mirrors the toggle in state`() {
    val engine = EqualizerLegacyEngine()
    engine.setEnabled(false)
    assertFalse(engine.isEnabled())
    engine.setEnabled(true)
    assertTrue(engine.isEnabled())
  }

  @Test
  fun `getBandGains returns a defensive copy so callers cannot mutate state`() {
    val engine = EqualizerLegacyEngine()
    engine.setBandGain(0, 5f)
    engine.getBandGains()[0] = 99f
    assertEquals(5f, engine.getBandGains()[0])
  }

  @Test
  fun `getBandGains remains callable after release for diagnostics`() {
    val engine = EqualizerLegacyEngine()
    engine.setBandGain(0, 4f)
    engine.release()
    assertEquals(4f, engine.getBandGains()[0])
  }

  @Test
  fun `release is idempotent and freezes mutations`() {
    val engine = EqualizerLegacyEngine()
    engine.setBandGain(0, 3f)
    engine.release()
    engine.setBandGain(0, 7f)
    engine.release()
    assertEquals(3f, engine.getBandGains()[0])
  }

  @Test
  fun `attach after release stays detached`() {
    // Robolectric's audio-effect shadow returns numBands=0, so a
    // happy-path attach also returns false on this layer; coverage
    // of the released-state branch is via behaviour parity.
    val engine = EqualizerLegacyEngine()
    engine.release()
    assertFalse(engine.attach(audioSessionId = 123, channelCount = 2))
    assertFalse(engine.isAttached())
  }

  @Test
  fun `attach on a fresh engine returns cleanly without throwing`() {
    val engine = EqualizerLegacyEngine()
    // Either outcome is contractually fine — Robolectric's shadow
    // may report the session as attachable or not. The pin here is
    // that the call returns cleanly and leaves the engine in a
    // consistent state (attached or detached, never half-state).
    val attached = engine.attach(audioSessionId = 0, channelCount = 2)
    assertEquals(attached, engine.isAttached())
  }

  @Test
  fun `mapping with a 5-band hardware layout collapses our 10 ISO targets`() {
    // Hardware centres in milli-Hz: 60 / 230 / 910 / 3600 / 14000 Hz
    // (a typical AOSP 5-band layout).
    val centresMHz = longArrayOf(60_000, 230_000, 910_000, 3_600_000, 14_000_000)
    val mapping = EqualizerLegacyEngine.mapOurBandsToLegacy(numBands = 5) { centresMHz[it] }
    assertEquals(0, mapping[0])  // 31 Hz → 60 Hz centre
    assertEquals(0, mapping[1])  // 63 Hz → 60 Hz centre
    assertEquals(0, mapping[2])  // 125 Hz → 60 Hz centre (closer than 230)
    assertEquals(1, mapping[3])  // 250 Hz → 230 Hz centre
    assertEquals(1, mapping[4])  // 500 Hz → 230 Hz centre (closer than 910)
    assertEquals(2, mapping[5])  // 1000 Hz → 910 Hz centre
    assertEquals(2, mapping[6])  // 2000 Hz → 910 Hz centre (closer than 3600)
    assertEquals(3, mapping[7])  // 4000 Hz → 3600 Hz centre
    assertEquals(3, mapping[8])  // 8000 Hz → 3600 Hz centre (closer than 14000)
    assertEquals(4, mapping[9])  // 16000 Hz → 14000 Hz centre
  }

  @Test
  fun `mapping with our exact 10 ISO frequencies is the identity`() {
    val ourFreqs = EqualizerPresets.frequencies
    val mapping = EqualizerLegacyEngine.mapOurBandsToLegacy(numBands = 10) {
      ourFreqs[it].toLong() * 1_000L
    }
    for (i in 0 until 10) {
      assertEquals(i, mapping[i])
    }
  }

  @Test
  fun `mapping with tied distances picks the lower band index`() {
    // Two centres at 100 Hz and 400 Hz. 250 Hz target sits exactly
    // halfway → both distances are 150_000 mHz → lower index wins.
    val centresMHz = longArrayOf(100_000, 400_000)
    val mapping = EqualizerLegacyEngine.mapOurBandsToLegacy(numBands = 2) { centresMHz[it] }
    assertEquals(0, mapping[0])  // 31 → 100
    assertEquals(0, mapping[1])  // 63 → 100
    assertEquals(0, mapping[2])  // 125 → 100
    assertEquals(0, mapping[3])  // 250 → tie → lower index
    assertEquals(1, mapping[4])  // 500 → 400
  }

  @Test
  fun `attachAll filters out unset and error session sentinels`() {
    val engine = EqualizerLegacyEngine()
    val results = engine.attachAll(
      intArrayOf(
        androidx.media3.common.C.AUDIO_SESSION_ID_UNSET,
        android.media.AudioManager.ERROR,
      )
    )
    assertEquals("sentinels are not attempted", 0, results.size)
    assertFalse("nothing attached", engine.isAttached())
  }

  @Test
  fun `attachAll on empty array detaches every active instance`() {
    val engine = EqualizerLegacyEngine()
    val first = engine.attachAll(intArrayOf(501, 502))
    assertEquals("both sessions attach", mapOf(501 to true, 502 to true), first)
    val second = engine.attachAll(intArrayOf())
    assertEquals("empty array clears the map", 0, second.size)
    assertFalse("no sessions held after empty attachAll", engine.isAttached())
  }

  @Test
  fun `detachAll releases every per-session instance`() {
    val engine = EqualizerLegacyEngine()
    val results = engine.attachAll(intArrayOf(601, 602))
    assertEquals("both sessions attach", mapOf(601 to true, 602 to true), results)
    engine.detachAll()
    assertFalse("no sessions held after detachAll", engine.isAttached())
  }

  @Test
  fun `setBandGain fans gain across every attached session`() {
    val engine = EqualizerLegacyEngine()
    val results = engine.attachAll(intArrayOf(701, 702))
    assertEquals("both sessions attach", mapOf(701 to true, 702 to true), results)

    engine.setBandGain(4, 5f)

    assertEquals("buffered band gain mirrors the call", 5f, engine.getBandGains()[4])
    // Our 500 Hz band maps onto the shadow's 230 Hz legacy band, and +5 dB
    // is 500 mB against a ±1500 mB device range.
    val legacyBand: Short = 1
    assertEquals("session 701 receives the gain", 500, levelFor(701, legacyBand))
    assertEquals("session 702 receives the gain", 500, levelFor(702, legacyBand))
  }

  /** Band level the session's own equalizer reports, in millibels. */
  private fun levelFor(sessionId: Int, band: Short): Int {
    val effect = ShadowAudioEffect.getAudioEffects().firstOrNull {
      Shadow.extract<ShadowEqualizer>(it).audioSession == sessionId
    } ?: error("no equalizer attached for session $sessionId")
    return (effect as Equalizer).getBandLevel(band).toInt()
  }

  /** A failed attach leaves no trace: nothing attached, and a retry is possible. */
  @Test
  @Config(sdk = [27], shadows = [ShadowEqualizerRejectingBandWrites::class])
  fun `attach that fails while configuring leaves the engine detached`() {
    val engine = EqualizerLegacyEngine()

    val attached = engine.attach(audioSessionId = 801, channelCount = 2)

    assertFalse("the attach reports its failure", attached)
    assertFalse("nothing is attached after a failed configure", engine.isAttached())
    assertEquals(
      "the failed session is not carried as attached on the next diff",
      mapOf(801 to false),
      engine.attachAll(intArrayOf(801))
    )
  }
}
