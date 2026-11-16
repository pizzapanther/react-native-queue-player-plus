package com.margelo.nitro.queueplayer

import android.media.audiofx.DynamicsProcessing
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.media3.common.C
import java.util.concurrent.ConcurrentHashMap

/**
 * 10-band pre-EQ for API 28+ backed by [DynamicsProcessing].
 *
 * The audio session ID isn't known at construction time (ExoPlayer
 * only surfaces it once the renderers are ready), so the engine
 * holds gain state as the source of truth and applies it to the
 * underlying [DynamicsProcessing] instance once [attach] runs.
 * Pre-attach mutations from JS (`setBandGain`, `setAllBandGains`,
 * `setEnabled`) are buffered and replayed.
 *
 * Bands are centred on [EqualizerPresets.frequencies]; the same
 * pre-EQ chain is applied to every channel in the source so stereo
 * (and 5.1 / 7.1) content gets coherent processing across channels.
 *
 * Multi-channel sources: the [DynamicsProcessing.Config] is built
 * with `channelCount = 1` and the framework duplicates that single-
 * channel pre-EQ chain across every real channel of the in-effect
 * channel array at attach time. Subsequent gain mutations call
 * [DynamicsProcessing.setPreEqBandAllChannelsTo], which fans the
 * updated band config out to every channel in one shot. The
 * [attach] `channelCount` parameter is therefore ignored on this
 * backend — retained on the interface for future per-track engines.
 *
 * Threading: not serialised. [attachAll] / [detachAll] run on the
 * service main looper from the audio-session-id listener, and on the
 * thread constructing an [Equalizer] (its init wires the engine, and
 * the listener registration replays the current session ids
 * synchronously). [setBandGain] / [setAllBandGains] / [setEnabled] run
 * on the `Promise.async` pool, so two JS calls may run concurrently;
 * [isEnabled] / [getBandGains] run synchronously on the JS thread.
 * `instances` is a `ConcurrentHashMap` and the flags are `@Volatile`,
 * which rules out structural corruption and stale reads. What remains
 * is documented on the fields.
 * [release] briefly blocks while the native effect tears down (a
 * few ms typical, longer on slow devices) — callers may dispatch
 * off-thread if a teardown burst risks a UI hitch.
 */
@RequiresApi(Build.VERSION_CODES.P)
class EqualizerEngine : IEqualizerEngine {

  companion object {
    /** `AudioEffect` priority — 0 is the default for non-privileged effect clients. */
    private const val PRIORITY = 0

    private const val BAND_COUNT = 10

    /** Per project spec — UI clamps band gain into this dB range. */
    private const val MIN_GAIN_DB = -12f
    private const val MAX_GAIN_DB = 12f

    private const val LOG_TAG = "EqualizerEngine"
  }

  // Written by the setters and by attach, all on the main looper:
  // `Equalizer` hops every JS-facing mutation there, and the playback
  // engine's session-id listener drives attach there. Nothing touches
  // this off the looper.
  private val gainsDb: FloatArray = FloatArray(BAND_COUNT)
  @Volatile private var enabled: Boolean = false
  /**
   * One [DynamicsProcessing] instance per attached audio session.
   * Crossfade-window callers can hold two simultaneously
   * (outgoing + incoming legs) so EQ continuity is preserved
   * across the fade. Single-session callers see exactly one entry.
   * Iteration order is never relied on: the attach diff is set
   * arithmetic and every other loop applies one operation per entry.
   * Inserts use `putIfAbsent` and removals go through `remove`, so two
   * attaches racing on one session release the loser instead of
   * leaking it, and a detach cannot drop an instance inserted between
   * its iteration and its clear.
   */
  private val instances: MutableMap<Int, DynamicsProcessing> = ConcurrentHashMap()
  @Volatile private var released: Boolean = false

  /**
   * Diff [sessionIds] against the current per-session map:
   * release instances whose session is no longer present, and
   * instantiate fresh `DynamicsProcessing` instances for new
   * sessions carrying the engine's current band gains + enabled
   * state.
   *
   * Detach-before-attach ordering for the released set so the
   * `AudioEffect` framework releases the prior session's effect
   * before any new one binds — avoids a brief contention window
   * where two effects share a session ID during a stale cleanup.
   *
   * Returns the per-session attach result (true = constructed
   * successfully; false = framework rejection or runtime
   * exception). Existing sessions that survived the diff are
   * marked `true` since their instance is already valid.
   */
  override fun attachAll(sessionIds: IntArray): Map<Int, Boolean> {
    if (released) return emptyMap()
    // Drop both ExoPlayer's `C.AUDIO_SESSION_ID_UNSET` (0) and
    // AudioManager's `ERROR` (-1) sentinels — neither is a real
    // session ID and constructing a `DynamicsProcessing` against
    // them throws.
    val incoming = sessionIds
      .filter { it != C.AUDIO_SESSION_ID_UNSET && it != android.media.AudioManager.ERROR }
      .toSet()
    val current = instances.keys.toSet()

    // Release sessions no longer present.
    for (gone in current - incoming) {
      instances.remove(gone)?.release()
    }

    val results = LinkedHashMap<Int, Boolean>()
    for (id in incoming) {
      if (instances.containsKey(id)) {
        results[id] = true
        continue
      }
      var instance: DynamicsProcessing? = null
      results[id] = try {
        val config = DynamicsProcessing.Config.Builder(
          DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
          /* channelCount = */ 1,
          /* preEqInUse = */ true, /* preEqBandCount = */ BAND_COUNT,
          /* mbcInUse = */ false, /* mbcBandCount = */ 0,
          /* postEqInUse = */ false, /* postEqBandCount = */ 0,
          /* limiterInUse = */ false,
        ).build()
        instance = DynamicsProcessing(PRIORITY, id, config)
        applyAllBandsToInstance(instance)
        instance.setEnabled(enabled)
        // Insert only once the instance is fully configured, so a throw
        // above leaves no entry for isAttached() to report. A concurrent
        // attach that got here first keeps its instance; ours is released.
        val existing = instances.putIfAbsent(id, instance)
        if (existing != null) instance.release()
        true
      } catch (e: RuntimeException) {
        Log.w(LOG_TAG, "DynamicsProcessing attach failed for session $id", e)
        instance?.release()
        false
      }
    }
    return results
  }

  /**
   * Release every per-session instance + clear the map. Band
   * gains + enabled state survive for the next [attachAll].
   */
  override fun detachAll() {
    for (id in instances.keys) instances.remove(id)?.release()
  }

  /** True when the engine is attached to at least one audio session. */
  override fun isAttached(): Boolean = instances.isNotEmpty()

  /**
   * Set the gain (dB) for [bandIndex]. Out-of-range indices are
   * silently ignored. The gain is clamped to the project-wide
   * [MIN_GAIN_DB], [MAX_GAIN_DB] band so values outside the
   * documented spec can never reach the hardware.
   */
  override fun setBandGain(bandIndex: Int, gainDb: Float) {
    if (released) return
    if (bandIndex < 0 || bandIndex >= BAND_COUNT) return
    val clamped = gainDb.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
    gainsDb[bandIndex] = clamped
    val freqHz = EqualizerPresets.frequencies[bandIndex].toFloat()
    // Fan to every active session-instance so a crossfade-window
    // EQ change applies to both legs simultaneously.
    for (instance in instances.values) {
      val band = instance.getPreEqByChannelIndex(0).getBand(bandIndex)
      band.cutoffFrequency = freqHz
      band.gain = clamped
      band.isEnabled = true
      // Android's framework duplicates the band config across every
      // real channel of the in-effect channel array.
      instance.setPreEqBandAllChannelsTo(bandIndex, band)
    }
  }

  /**
   * Replace every band gain at once. Each entry is clamped to the
   * project-wide range. Extra entries past [BAND_COUNT] are
   * ignored; missing entries leave the previous value unchanged.
   */
  override fun setAllBandGains(gains: FloatArray) {
    if (released) return
    val n = minOf(gains.size, BAND_COUNT)
    for (i in 0 until n) {
      gainsDb[i] = gains[i].coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
    }
    for (instance in instances.values) applyAllBandsToInstance(instance)
  }

  /**
   * Snapshot of current band gains (dB), low-to-high frequency.
   * Remains callable after [release] for telemetry / diagnostics.
   */
  override fun getBandGains(): FloatArray = gainsDb.copyOf()

  /**
   * Toggle the global EQ stage. The hardware effect is bypassed
   * when disabled — gains stay configured so re-enabling restores
   * the prior curve without a re-set.
   */
  override fun setEnabled(value: Boolean) {
    if (released) return
    enabled = value
    for (instance in instances.values) instance.setEnabled(value)
  }

  override fun isEnabled(): Boolean = enabled

  /**
   * Permanent teardown — releases every per-session
   * `DynamicsProcessing` instance + clears state. Subsequent
   * calls become no-ops.
   */
  override fun release() {
    if (released) return
    released = true
    detachAll()
  }

  private fun applyAllBandsToInstance(instance: DynamicsProcessing) {
    val freqs = EqualizerPresets.frequencies
    val gains = gainsDb.copyOf()
    for (i in 0 until BAND_COUNT) {
      // The Config.Builder constructor enables the PreEq stage; per-band
      // `isEnabled = true` plus `setPreEqBandAllChannelsTo` is what fans
      // each band's gain out across every real channel of the in-effect
      // channel array.
      val band = DynamicsProcessing.EqBand(true, freqs[i].toFloat(), gains[i])
      instance.setPreEqBandAllChannelsTo(i, band)
    }
  }
}
