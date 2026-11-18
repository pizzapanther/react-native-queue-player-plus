package com.margelo.nitro.queueplayer

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
 * Engine-level coverage for the ReplayGain wiring on [GaplessEngine].
 *
 * Real Media3 metadata dispatch is not emulated by Robolectric
 * without playback, so the listener body composes its work through
 * the engine-internal `handleReplayGainParse` helper which these
 * tests drive directly with synthetic [ReplayGainData].
 * This file covers the no-data baseline,
 * mode-change-without-data behaviour, the
 * `handleReplayGainParse` cache + recompute contract, and the
 * consumer's supplied claim outranking the parsed tags.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@UnstableApi
class GaplessEngineReplayGainTest {

  @After
  fun tearDown() {
    // Each test mutates the process-wide ReplayGainConfig; reset it
    // so subsequent tests in the same JVM aren't dependent on order.
    ReplayGainConfig.setMode(ReplayGainMode.OFF)
  }

  private fun makeEngine(): GaplessEngine = GaplessEngine(
    context = ApplicationProvider.getApplicationContext(),
  )

  @Test
  fun `fresh engine reports no replay-gain data and inactive`() {
    val engine = makeEngine()
    assertNull(engine.currentReplayGainData())
    assertFalse(engine.currentReplayGainActive())
    engine.release()
  }

  @Test
  fun `recompute with no cached data does not crash and leaves engine inactive`() {
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
  fun `handleReplayGainParse caches data and activates the engine in track mode`() {
    val engine = makeEngine()
    ReplayGainConfig.setMode(ReplayGainMode.TRACK)

    val data = ReplayGainData(
      trackGainDb = -6.0f,
      trackPeak = 0.5f,
      albumGainDb = -3.0f,
      albumPeak = 0.8f,
    )
    engine.handleReplayGainParse(data)

    assertEquals(data, engine.currentReplayGainData())
    assertTrue(engine.currentReplayGainActive())
    engine.release()
  }

  /**
   * The consumer's claim over the active item outranks the file's tags: a
   * supplied value replaces detection in full, and a claim whose values were
   * all rejected still discards detection and plays at unity.
   */
  @Test
  fun `a supplied claim on the active item replaces the detected tags`() {
    val engine = makeEngine()
    ReplayGainConfig.setMode(ReplayGainMode.TRACK)
    engine.setMediaItems(listOf(androidx.media3.common.MediaItem.fromUri("https://example.com/a.mp3")), 0, 0L)
    val detected = ReplayGainData(trackGainDb = -6.0f, trackPeak = 0.5f, albumGainDb = null, albumPeak = null)
    engine.handleReplayGainParse(detected)
    assertEquals(detected, engine.currentReplayGainData())

    var claim: TrackItem = suppliedTrack(trackGain = -3.0)
    engine.suppliedReplayGainResolver = { SuppliedReplayGain.from(claim) }
    assertEquals(
      ReplayGainData(trackGainDb = -3.0f, trackPeak = null, albumGainDb = null, albumPeak = null),
      engine.currentReplayGainData())
    assertTrue(engine.currentReplayGainActive())

    claim = suppliedTrack(trackGain = Double.NaN)
    assertNull("a claim with no usable value discards the detection too", engine.currentReplayGainData())
    assertFalse(engine.currentReplayGainActive())
    engine.release()
  }

  private fun suppliedTrack(trackGain: Double) = TrackItem(
    id = null, url = "https://example.com/a.mp3", title = null, artist = null, album = null,
    duration = null, artworkUrl = null, extras = null,
    replayGainTrackGain = trackGain, replayGainTrackPeak = null,
    replayGainAlbumGain = null, replayGainAlbumPeak = null, isVideo = null)

  @Test
  fun `handleReplayGainParse with album-only data is inactive in track mode`() {
    val engine = makeEngine()
    ReplayGainConfig.setMode(ReplayGainMode.TRACK)

    val data = ReplayGainData(
      trackGainDb = null,
      trackPeak = null,
      albumGainDb = -3.0f,
      albumPeak = 0.8f,
    )
    engine.handleReplayGainParse(data)

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

    val data = ReplayGainData(
      trackGainDb = -6.0f,
      trackPeak = 0.5f,
      albumGainDb = null,
      albumPeak = null,
    )
    engine.handleReplayGainParse(data)

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
    engine.handleReplayGainParse(data)
    assertNotNull(engine.currentReplayGainData())

    // Subsequent null parse (e.g. unrelated TIT2 frame) does not wipe
    // the previously-cached track-load RG block.
    engine.handleReplayGainParse(null)
    assertEquals(data, engine.currentReplayGainData())
    assertTrue(engine.currentReplayGainActive())
    engine.release()
  }

  // The cache-clear-on-transition path lives inline in the
  // `Player.Listener.onMediaItemTransition` body. Robolectric does not
  // drive that callback without real playback.
}
