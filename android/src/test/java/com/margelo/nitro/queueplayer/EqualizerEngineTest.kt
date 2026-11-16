package com.margelo.nitro.queueplayer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * State-mirror coverage for [EqualizerEngine] on the API 28+ path.
 * Robolectric stubs the audio-effect subsystem at this SDK level so
 * `attach` exercises the construction path without throwing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EqualizerEngineTest {

  @Test
  fun `default state is disabled with all bands at zero gain`() {
    val engine = EqualizerEngine()
    assertFalse(engine.isEnabled())
    assertFalse(engine.isAttached())
    assertArrayEquals(FloatArray(10) { 0f }, engine.getBandGains(), 0f)
  }

  @Test
  fun `setBandGain stores per-band gain before any attach`() {
    val engine = EqualizerEngine()
    engine.setBandGain(3, 4.5f)
    engine.setBandGain(7, -2.25f)
    val gains = engine.getBandGains()
    assertEquals(4.5f, gains[3])
    assertEquals(-2.25f, gains[7])
    assertEquals(0f, gains[0])
  }

  @Test
  fun `setBandGain ignores out-of-range indices`() {
    val engine = EqualizerEngine()
    engine.setBandGain(-1, 9f)
    engine.setBandGain(10, 9f)
    engine.setBandGain(99, 9f)
    assertArrayEquals(FloatArray(10) { 0f }, engine.getBandGains(), 0f)
  }

  @Test
  fun `setBandGain clamps gains outside the project spec range`() {
    val engine = EqualizerEngine()
    engine.setBandGain(0, 99f)
    engine.setBandGain(1, -99f)
    val gains = engine.getBandGains()
    assertEquals(12f, gains[0])
    assertEquals(-12f, gains[1])
  }

  @Test
  fun `setAllBandGains replaces every band`() {
    val engine = EqualizerEngine()
    val target = floatArrayOf(0f, 1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f)
    engine.setAllBandGains(target)
    assertArrayEquals(target, engine.getBandGains(), 0f)
  }

  @Test
  fun `setAllBandGains clamps each entry into the project spec range`() {
    val engine = EqualizerEngine()
    engine.setAllBandGains(floatArrayOf(-99f, 99f, 0f, 5f, -5f, 13f, -13f, 0f, 0f, 0f))
    val gains = engine.getBandGains()
    assertEquals(-12f, gains[0])
    assertEquals(12f, gains[1])
    assertEquals(0f, gains[2])
    assertEquals(5f, gains[3])
    assertEquals(-5f, gains[4])
    assertEquals(12f, gains[5])
    assertEquals(-12f, gains[6])
  }

  @Test
  fun `setAllBandGains tolerates short arrays without throwing`() {
    val engine = EqualizerEngine()
    engine.setAllBandGains(floatArrayOf(1f, 2f, 3f))
    val gains = engine.getBandGains()
    assertEquals(1f, gains[0])
    assertEquals(2f, gains[1])
    assertEquals(3f, gains[2])
    assertEquals(0f, gains[3])
  }

  @Test
  fun `setEnabled mirrors the toggle in state`() {
    val engine = EqualizerEngine()
    engine.setEnabled(false)
    assertFalse(engine.isEnabled())
    engine.setEnabled(true)
    assertTrue(engine.isEnabled())
  }

  @Test
  fun `getBandGains returns a defensive copy so callers cannot mutate state`() {
    val engine = EqualizerEngine()
    engine.setBandGain(0, 5f)
    val first = engine.getBandGains()
    first[0] = 99f
    val second = engine.getBandGains()
    assertEquals(5f, second[0])
  }

  @Test
  fun `getBandGains remains callable after release for diagnostics`() {
    val engine = EqualizerEngine()
    engine.setBandGain(0, 3f)
    engine.release()
    assertEquals(3f, engine.getBandGains()[0])
  }

  @Test
  fun `release is idempotent and freezes mutations`() {
    val engine = EqualizerEngine()
    engine.setBandGain(0, 3f)
    engine.release()
    engine.setBandGain(0, 7f)
    engine.release()
    assertEquals(3f, engine.getBandGains()[0])
  }

  @Test
  fun `attach after release stays detached`() {
    val engine = EqualizerEngine()
    engine.release()
    assertFalse(engine.attach(audioSessionId = 123, channelCount = 2))
    assertFalse(engine.isAttached())
  }

  @Test
  fun `attach failure on a hostile session leaves engine cleanly detached`() {
    val engine = EqualizerEngine()
    // Robolectric's audio-effect subsystem may reject construction
    // for a synthetic session; either outcome is contractually fine —
    // success → attached, failure → state cleared so a retry on a
    // fresh session can succeed without a stale `attachedAudioSession`
    // short-circuiting `attach`.
    engine.attach(audioSessionId = -1, channelCount = 2)
    val firstAttached = engine.isAttached()
    val secondTry = engine.attach(audioSessionId = -1, channelCount = 2)
    if (!firstAttached) {
      assertFalse("a failed attach must leave the engine detached", engine.isAttached())
      assertFalse("the same hostile session must be retry-safe", secondTry)
    }
  }

  @Test
  fun `setBandGain mutates buffered gain regardless of attach state`() {
    val engine = EqualizerEngine()
    // Robolectric's audio-effect shadow doesn't surface `setPreEqBandAllChannelsTo`
    // for inspection; assert the buffered state the attached path replays instead.
    engine.setBandGain(2, 6f)
    assertEquals(6f, engine.getBandGains()[2])
    engine.attach(audioSessionId = 42, channelCount = 2)
    engine.setBandGain(5, -3f)
    assertEquals(-3f, engine.getBandGains()[5])
  }

  @Test
  fun `reattach on same session is idempotent`() {
    val engine = EqualizerEngine()
    val first = engine.attach(audioSessionId = 123, channelCount = 2)
    assertTrue("first attach succeeds", first)
    val second = engine.attach(audioSessionId = 123, channelCount = 6)
    assertTrue("repeat attach on the same session succeeds", second)
    assertTrue(
      "channel-count change on the same session leaves the engine attached",
      engine.isAttached(),
    )
  }

  @Test
  fun `attachAll holds multiple sessions simultaneously for crossfade`() {
    val engine = EqualizerEngine()
    val results = engine.attachAll(intArrayOf(101, 102))
    assertTrue("session attaches", results[101] == true)
    assertTrue("incoming session also attached", results[102] == true)
    assertTrue("at least one session held", engine.isAttached())
  }

  @Test
  fun `attachAll diff releases sessions no longer in the input array`() {
    val engine = EqualizerEngine()
    val first = engine.attachAll(intArrayOf(201, 202))
    assertTrue("session attaches", first[201] == true)
    val second = engine.attachAll(intArrayOf(202))
    // 201 dropped, 202 retained.
    assertTrue("retained session marked succeeded", second[202] == true)
    assertEquals("only the retained session remains attached", null, second[201])
  }

  @Test
  fun `detachAll releases every per-session instance`() {
    val engine = EqualizerEngine()
    val results = engine.attachAll(intArrayOf(301, 302))
    assertTrue("session attaches", results[301] == true)
    engine.detachAll()
    assertTrue("no sessions held after detachAll", !engine.isAttached())
  }

  @Test
  fun `setBandGain buffers the gain while sessions are attached`() {
    val engine = EqualizerEngine()
    val results = engine.attachAll(intArrayOf(401, 402))
    assertTrue("session attaches", results[401] == true)
    engine.setBandGain(3, 4f)
    assertEquals(4f, engine.getBandGains()[3])
  }

  /** A failed attach leaves no trace: nothing attached, and a retry is possible. */
  @Test
  @Config(sdk = [34], shadows = [ShadowDynamicsProcessingRejectingEnable::class])
  fun `attach that fails while configuring leaves the engine detached`() {
    val engine = EqualizerEngine()

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
