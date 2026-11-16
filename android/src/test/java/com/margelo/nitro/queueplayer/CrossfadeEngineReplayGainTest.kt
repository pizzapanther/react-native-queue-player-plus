package com.margelo.nitro.queueplayer

import android.os.Build
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Engine-level coverage for the ReplayGain wiring on [CrossfadeEngine].
 *
 * The crossfade engine owns TWO [ReplayGainAudioProcessor] instances —
 * one per leg. Each leg's `Player.Listener.onTracksChanged` /
 * `onMetadata` is leg-shaped (NOT role-shaped), so a tag arrival on
 * the standby leg cannot stomp the leading leg's cached gain. Real
 * Media3 metadata dispatch is not emulated by Robolectric without
 * playback, so the listener body composes its work through the
 * engine-internal `handleReplayGainParse(isA, parsed)` helper which
 * these tests drive directly with synthetic [ReplayGainData].
 * This file covers the no-data baseline + the per-leg
 * `handleReplayGainParse` cache + recompute contract.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
@UnstableApi
class CrossfadeEngineReplayGainTest {

  @After
  fun tearDown() {
    ReplayGainConfig.setMode(ReplayGainMode.OFF)
  }

  private fun makeEngine(): CrossfadeEngine = CrossfadeEngine(
    context = ApplicationProvider.getApplicationContext(),
    crossfadeDurationMs = 0L,
  )

  @Test
  fun `fresh crossfade engine reports no replay-gain data and inactive`() {
    val engine = makeEngine()
    assertNull(engine.currentReplayGainData())
    assertFalse(engine.currentReplayGainActive())
    engine.release()
  }

  @Test
  fun `recompute with no cached data leaves engine inactive across modes`() {
    val engine = makeEngine()

    ReplayGainConfig.setMode(ReplayGainMode.TRACK)
    engine.recomputeReplayGainFromConfig()
    assertFalse(engine.currentReplayGainActive())

    ReplayGainConfig.setMode(ReplayGainMode.ALBUM)
    engine.recomputeReplayGainFromConfig()
    assertFalse(engine.currentReplayGainActive())

    engine.release()
  }

  @Test
  fun `handleReplayGainParse on the leading leg activates the engine in track mode`() {
    val engine = makeEngine()
    ReplayGainConfig.setMode(ReplayGainMode.TRACK)

    val data = ReplayGainData(
      trackGainDb = -6.0f,
      trackPeak = 0.5f,
      albumGainDb = -3.0f,
      albumPeak = 0.8f,
    )
    // Leg A is the default leading leg.
    engine.handleReplayGainParse(isA = true, parsed = data)

    assertEquals(data, engine.currentReplayGainData())
    assertTrue(engine.currentReplayGainActive())
    engine.release()
  }

  @Test
  fun `handleReplayGainParse on the standby leg does not stomp the leading leg`() {
    val engine = makeEngine()
    ReplayGainConfig.setMode(ReplayGainMode.TRACK)

    val leadingData = ReplayGainData(
      trackGainDb = -6.0f, trackPeak = 0.5f,
      albumGainDb = null, albumPeak = null,
    )
    val standbyData = ReplayGainData(
      trackGainDb = -2.0f, trackPeak = 0.7f,
      albumGainDb = null, albumPeak = null,
    )
    // Leading leg (A) caches its data; standby leg (B) caches its
    // own. The active reader follows the leading leg; standby's
    // tag arrival does not contaminate the leading leg's cache.
    engine.handleReplayGainParse(isA = true, parsed = leadingData)
    engine.handleReplayGainParse(isA = false, parsed = standbyData)

    assertEquals(leadingData, engine.currentReplayGainData())
    assertTrue(engine.currentReplayGainActive())
    engine.release()
  }

  @Test
  fun `handleReplayGainParse with album-only data is inactive in track mode`() {
    val engine = makeEngine()
    ReplayGainConfig.setMode(ReplayGainMode.TRACK)

    engine.handleReplayGainParse(
      isA = true,
      parsed = ReplayGainData(
        trackGainDb = null, trackPeak = null,
        albumGainDb = -3.0f, albumPeak = 0.8f,
      )
    )

    assertNotNull(engine.currentReplayGainData())
    assertFalse(
      "track mode requires trackGainDb; album-only data is inactive",
      engine.currentReplayGainActive()
    )
    engine.release()
  }

  @Test
  fun `handleReplayGainParse with track-only data is inactive in album mode (strict no-fallback)`() {
    val engine = makeEngine()
    ReplayGainConfig.setMode(ReplayGainMode.ALBUM)

    engine.handleReplayGainParse(
      isA = true,
      parsed = ReplayGainData(
        trackGainDb = -6.0f, trackPeak = 0.5f,
        albumGainDb = null, albumPeak = null,
      )
    )

    assertNotNull(engine.currentReplayGainData())
    assertFalse(
      "album mode is strict — track-only data does not fall back",
      engine.currentReplayGainActive()
    )
    engine.release()
  }

  @Test
  fun `handleReplayGainParse with null parse leaves the cache untouched`() {
    val engine = makeEngine()
    ReplayGainConfig.setMode(ReplayGainMode.TRACK)

    val data = ReplayGainData(
      trackGainDb = -6.0f, trackPeak = 0.5f,
      albumGainDb = null, albumPeak = null,
    )
    engine.handleReplayGainParse(isA = true, parsed = data)
    assertNotNull(engine.currentReplayGainData())

    engine.handleReplayGainParse(isA = true, parsed = null)
    assertEquals(data, engine.currentReplayGainData())
    assertTrue(engine.currentReplayGainActive())
    engine.release()
  }

  // The per-leg cache-clear-on-transition path lives inline in each
  // leg's `Player.Listener.onMediaItemTransition` body. Robolectric
  // does not drive that callback without real playback, so it is out
  // of scope here.
}
