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
 * Wiring coverage for the `Equalizer` HybridObject — engine
 * selection, JS-facing surface routing through the synchronous
 * `*Internal` core, listener registry behaviour.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@UnstableApi
class EqualizerKtTest {

  private fun makeEq(): Equalizer =
    Equalizer(storeContext = ApplicationProvider.getApplicationContext())

  @After
  fun clearCustomPresets() {
    val context: android.content.Context = ApplicationProvider.getApplicationContext()
    context
      .getSharedPreferences("com.queueplayer.equalizer", android.content.Context.MODE_PRIVATE)
      .edit()
      .clear()
      .apply()
    // Make the static singleton contract explicit at test boundaries —
    // otherwise the next test sees a stale reference.
    Equalizer.current = null
  }

  @Test
  fun `getPresets returns all 16 built-in presets`() {
    val eq = makeEq()
    val presets = eq.getPresets()
    assertEquals(16, presets.size)
    assertTrue(presets.all { it.builtin })
    assertTrue(presets.any { it.name == "Flat" })
    assertTrue(presets.any { it.name == "Rock" })
  }

  @Test
  fun `getBands returns 10 bands at ISO frequencies with current gains`() {
    val eq = makeEq()
    val bands = eq.getBands()
    assertEquals(10, bands.size)
    assertEquals(31.0, bands[0].frequencyHz, 0.0)
    assertEquals(16000.0, bands[9].frequencyHz, 0.0)
    assertTrue(bands.all { it.gainDb == 0.0 })
  }

  @Test
  fun `applyPresetInternal Rock surfaces via getBands`() {
    val eq = makeEq()
    assertTrue(eq.applyPresetInternal("Rock"))
    val bands = eq.getBands()
    assertEquals(5.0, bands[0].gainDb, 0.0001)
    assertEquals(3.0, bands[1].gainDb, 0.0001)
  }

  @Test
  fun `applyPresetInternal with unknown name returns false and is a no-op`() {
    val eq = makeEq()
    assertFalse(eq.applyPresetInternal("DoesNotExist"))
    assertTrue(eq.getBands().all { it.gainDb == 0.0 })
  }

  @Test
  fun `setBandGainInternal routes to the engine`() {
    val eq = makeEq()
    eq.setBandGainInternal(3, 5f)
    assertEquals(5.0, eq.getBands()[3].gainDb, 0.0001)
  }

  @Test
  fun `setAllBandGainsInternal replaces every band`() {
    val eq = makeEq()
    eq.setAllBandGainsInternal(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f, 10f))
    val bands = eq.getBands()
    for (i in 0 until 10) {
      assertEquals((i + 1).toDouble(), bands[i].gainDb, 0.0001)
    }
  }

  @Test
  fun `resetInternal returns all bands to zero via the Flat preset`() {
    val eq = makeEq()
    eq.applyPresetInternal("Rock")
    eq.resetInternal()
    assertTrue(eq.getBands().all { it.gainDb == 0.0 })
  }

  @Test
  fun `setEnabledInternal mirrors through isEnabled`() {
    val eq = makeEq()
    assertFalse(eq.isEnabled())
    eq.setEnabledInternal(true)
    assertTrue(eq.isEnabled())
    eq.setEnabledInternal(false)
    assertFalse(eq.isEnabled())
  }

  @Test
  fun `onBandChange fires with the snapshot when bands mutate`() {
    val eq = makeEq()
    var fired: Array<EqualizerBand>? = null
    eq.onBandChange { snapshot -> fired = snapshot }
    eq.setBandGainInternal(0, 4f)
    assertNotNull(fired)
    assertEquals(10, fired!!.size)
    assertEquals(4.0, fired!![0].gainDb, 0.0001)
  }

  @Test
  fun `onBandChange unsubscribe stops further fires`() {
    val eq = makeEq()
    var count = 0
    // Registration auto-fires once with the current snapshot.
    val unsubscribe = eq.onBandChange { count++ }
    assertEquals(1, count)
    eq.setBandGainInternal(0, 1f)
    unsubscribe()
    eq.setBandGainInternal(0, 2f)
    assertEquals(2, count)
  }

  @Test
  fun `onEnabledChange fires when setEnabledInternal toggles`() {
    val eq = makeEq()
    val received = mutableListOf<Boolean>()
    // Registration seeds with current state (default enabled=false).
    eq.onEnabledChange { received.add(it) }
    received.clear()
    eq.setEnabledInternal(true)
    eq.setEnabledInternal(false)
    assertEquals(listOf(true, false), received)
  }

  @Test
  fun `setEnabledInternal does not fire listeners on idempotent re-apply`() {
    val eq = makeEq()
    val received = mutableListOf<Boolean>()
    eq.onEnabledChange { received.add(it) }
    // Discard the auto-fire-on-registration entry.
    received.clear()
    // Default is false; re-applying false must not fire.
    eq.setEnabledInternal(false)
    assertEquals(emptyList<Boolean>(), received)
    eq.setEnabledInternal(true)
    eq.setEnabledInternal(true)
    assertEquals(listOf(true), received)
  }

  @Test
  fun `saveCustomPreset persists the current gains and surfaces via getPresets`() {
    val eq = makeEq()
    eq.setAllBandGainsInternal(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f, 10f))
    eq.saveCustomPresetInternal("Studio")
    val presets = eq.getPresets()
    val custom = presets.firstOrNull { !it.builtin && it.name == "Studio" }
    assertNotNull(custom)
    assertEquals(10, custom!!.gains.size)
    assertEquals(1.0, custom.gains[0], 0.0001)
    assertEquals(10.0, custom.gains[9], 0.0001)
  }

  @Test
  fun `deleteCustomPreset removes the named preset`() {
    val eq = makeEq()
    eq.saveCustomPresetInternal("Temp")
    assertNotNull(eq.getPresets().firstOrNull { it.name == "Temp" })
    eq.deleteCustomPresetInternal("Temp")
    assertNull(eq.getPresets().firstOrNull { it.name == "Temp" && !it.builtin })
  }

  @Test
  fun `Equalizer constructor publishes itself as the static current reference`() {
    val eq = makeEq()
    assertTrue(Equalizer.current === eq)
  }

  @Test
  fun `wireToPlaybackEngine subscribes to audio session ids and detaches on unwire`() {
    val eq = makeEq()
    val fakeEngine = FakePlaybackEngine()
    eq.wireToPlaybackEngine(fakeEngine)
    assertEquals(1, fakeEngine.listenerCount)
    eq.unwireFromPlaybackEngine()
    assertEquals(0, fakeEngine.listenerCount)
  }

  @Test
  fun `re-wiring tears the prior subscription down before resubscribing`() {
    val eq = makeEq()
    val first = FakePlaybackEngine()
    val second = FakePlaybackEngine()
    eq.wireToPlaybackEngine(first)
    eq.wireToPlaybackEngine(second)
    assertEquals(0, first.listenerCount)
    assertEquals(1, second.listenerCount)
    eq.unwireFromPlaybackEngine()
  }

  @Test
  fun `getPresets returns to 16 built-ins after the only custom preset is deleted`() {
    val eq = makeEq()
    eq.saveCustomPresetInternal("Temp")
    assertEquals(17, eq.getPresets().size)
    eq.deleteCustomPresetInternal("Temp")
    val presets = eq.getPresets()
    assertEquals(16, presets.size)
    assertTrue(presets.all { it.builtin })
  }
}

/** Minimal [PlaybackEngine] stub that records listener registration. */
@UnstableApi
private class FakePlaybackEngine : PlaybackEngine {
  var listenerCount: Int = 0
    private set

  override val mediaSessionPlayer: androidx.media3.exoplayer.ExoPlayer
    get() = error("not used in test")

  override fun release() = error("not used in test")
  override fun setMediaItems(items: List<androidx.media3.common.MediaItem>, startIndex: Int, startPositionMs: Long) = error("not used in test")
  override fun addMediaItems(items: List<androidx.media3.common.MediaItem>, insertBefore: Int) = error("not used in test")
  override fun removeMediaItems(indices: IntArray) = error("not used in test")
  override fun moveMediaItem(fromIndex: Int, toIndex: Int) = error("not used in test")
  override fun play() = error("not used in test")
  override fun pause() = error("not used in test")
  override fun stop() = error("not used in test")
  override fun seekTo(index: Int, positionMs: Long) = error("not used in test")
  override fun skipToNext() = error("not used in test")
  override fun skipToPrevious() = error("not used in test")
  override fun setVolume(volume: Float) = error("not used in test")
  override fun setPlaybackSpeed(speed: Float) = error("not used in test")
  override fun setRepeatMode(mode: RepeatMode) = error("not used in test")
  override fun setPitchCorrectionMode(mode: PitchCorrectionMode) = error("not used in test")
  override fun setWakeMode(mode: Int) = error("not used in test")
  override val currentMediaItemIndex: Int get() = error("not used in test")
  override val currentPositionMs: Long get() = error("not used in test")
  override val currentDurationMs: Long get() = error("not used in test")
  override val bufferedPositionMs: Long get() = error("not used in test")
  override val isPlaying: Boolean get() = error("not used in test")
  override val currentMediaItem: androidx.media3.common.MediaItem? get() = error("not used in test")
  override val isCrossfadeActive: Boolean get() = false
  override val allMediaItems: List<androidx.media3.common.MediaItem> get() = error("not used in test")
  override fun currentAudioSessionIds(): IntArray = IntArray(0)
  override fun addAudioSessionIdListener(listener: (IntArray) -> Unit): () -> Unit {
    listenerCount++
    return { listenerCount-- }
  }
  override fun attachAudioBufferSink(sink: androidx.media3.exoplayer.audio.TeeAudioProcessor.AudioBufferSink) = error("not used in test")
  override fun detachAudioBufferSink(sink: androidx.media3.exoplayer.audio.TeeAudioProcessor.AudioBufferSink) = error("not used in test")
  override var delegate: PlaybackEngineDelegate? = null
}
