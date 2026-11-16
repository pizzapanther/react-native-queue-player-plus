package com.margelo.nitro.queueplayer

import android.media.audiofx.Equalizer
import android.util.Log
import androidx.media3.common.C
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 10-band pre-EQ for API <28 backed by the platform-legacy
 * `android.media.audiofx.Equalizer`. The legacy class exposes a
 * platform-defined number of bands (often 5, sometimes 10) at
 * platform-defined centre frequencies — the engine queries the
 * available bands on attach and maps each of our 10 ISO targets
 * (see [EqualizerPresets.frequencies]) to the closest available
 * legacy band index.
 *
 * Multiple of our bands may collapse onto the same legacy band
 * when the device exposes fewer than 10 — last writer wins per
 * legacy band, which matches the platform's own behaviour when a
 * UI tries to drive a sparser hardware EQ from a denser model.
 *
 * Gain is in dB at the public surface; the legacy `Equalizer`
 * uses millibels (1 dB = 100 mB) — conversion happens at the
 * boundary. Range is the project-wide [MIN_GAIN_DB, MAX_GAIN_DB]
 * regardless of what the device's own range allows; values are
 * also clamped to the device's reported `getBandLevelRange()` to
 * avoid IllegalArgumentException on aggressive devices.
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
 * [release] briefly blocks while the native effect tears down.
 */
class EqualizerLegacyEngine : IEqualizerEngine {

  companion object {
    private const val PRIORITY = 0
    private const val BAND_COUNT = 10
    private const val MIN_GAIN_DB = -12f
    private const val MAX_GAIN_DB = 12f
    private const val MILLIBELS_PER_DB = 100
    private const val LOG_TAG = "EqualizerLegacyEngine"

    /**
     * Pure-function band mapping — extracted so unit tests can pin
     * the closest-centre selection without standing up a real
     * `Equalizer` instance. For each of our 10 ISO target
     * frequencies, find the legacy band whose centre frequency
     * (returned in milli-Hz by the platform `Equalizer.getCenterFreq`)
     * is closest. Tied distances resolve to the lower band index.
     */
    fun mapOurBandsToLegacy(numBands: Int, getCenterFreqMHz: (Int) -> Long): IntArray {
      val mapping = IntArray(BAND_COUNT)
      val ourFreqsHz = EqualizerPresets.frequencies
      for (i in 0 until BAND_COUNT) {
        val targetMHz = ourFreqsHz[i] * 1_000L
        var bestIdx = 0
        var bestDist = Long.MAX_VALUE
        for (b in 0 until numBands) {
          val dist = abs(getCenterFreqMHz(b) - targetMHz)
          if (dist < bestDist) {
            bestDist = dist
            bestIdx = b
          }
        }
        mapping[i] = bestIdx
      }
      return mapping
    }
  }

  /**
   * Per-session equalizer instance + the per-device-derived
   * mapping & gain bounds read once at attach time. Each session
   * derives independently so AOSP-fork variance across sessions
   * is handled correctly.
   */
  private data class Slot(
    val equalizer: Equalizer,
    val ourToLegacyBand: IntArray,
    val deviceMinMb: Short,
    val deviceMaxMb: Short,
  )

  // Written by the setters and by attach, all on the main looper:
  // `Equalizer` hops every JS-facing mutation there, and the playback
  // engine's session-id listener drives attach there. Nothing touches
  // this off the looper.
  private val gainsDb: FloatArray = FloatArray(BAND_COUNT)
  @Volatile private var enabled: Boolean = false
  // Iteration order is never relied on: the attach diff is set arithmetic
  // and every other loop applies one operation per entry. Inserts use
  // `putIfAbsent` and removals go through `remove`, so two attaches
  // racing on one session release the loser instead of leaking it, and a
  // detach cannot drop a slot inserted between its iteration and clear.
  private val instances: MutableMap<Int, Slot> = ConcurrentHashMap()
  @Volatile private var released: Boolean = false

  /**
   * Diff [sessionIds] against the current per-session map.
   * Releases instances no longer present, instantiates fresh
   * `Equalizer` instances for new sessions carrying the engine's
   * current band gains + enabled state. Returns per-session
   * attach result.
   */
  override fun attachAll(sessionIds: IntArray): Map<Int, Boolean> {
    if (released) return emptyMap()
    // Drop both ExoPlayer's `C.AUDIO_SESSION_ID_UNSET` (0) and
    // AudioManager's `ERROR` (-1) sentinels — neither is a real
    // session ID and constructing an `Equalizer` against them throws.
    val incoming = sessionIds
      .filter { it != C.AUDIO_SESSION_ID_UNSET && it != android.media.AudioManager.ERROR }
      .toSet()
    val current = instances.keys.toSet()

    for (gone in current - incoming) {
      instances.remove(gone)?.equalizer?.release()
    }

    val results = LinkedHashMap<Int, Boolean>()
    for (id in incoming) {
      if (instances.containsKey(id)) {
        results[id] = true
        continue
      }
      results[id] = attachOne(id)
    }
    return results
  }

  private fun attachOne(audioSessionId: Int): Boolean {
    var instance: Equalizer? = null
    return try {
      instance = Equalizer(PRIORITY, audioSessionId)
      val numBands = instance.numberOfBands.toInt()
      if (numBands <= 0) {
        Log.w(LOG_TAG, "Equalizer reported 0 bands for session $audioSessionId; skipping attach")
        instance.release()
        return false
      }
      val range = instance.bandLevelRange
      val mapping = mapOurBandsToLegacy(numBands) { idx ->
        instance!!.getCenterFreq(idx.toShort()).toLong()
      }
      val slot = Slot(
        equalizer = instance,
        ourToLegacyBand = mapping,
        deviceMinMb = range[0],
        deviceMaxMb = range[1],
      )
      applyAllBandsToSlot(slot)
      instance.enabled = enabled
      // Insert only once the slot is fully configured, so a throw above
      // leaves no entry for isAttached() to report against a released
      // native handle. A concurrent attach that got here first keeps its
      // slot; ours is released.
      val existing = instances.putIfAbsent(audioSessionId, slot)
      if (existing != null) instance.release()
      true
    } catch (e: RuntimeException) {
      Log.w(LOG_TAG, "Equalizer attach failed for session $audioSessionId", e)
      instance?.release()
      false
    }
  }

  override fun detachAll() {
    for (id in instances.keys) instances.remove(id)?.equalizer?.release()
  }

  override fun isAttached(): Boolean = instances.isNotEmpty()

  override fun setBandGain(bandIndex: Int, gainDb: Float) {
    if (released) return
    if (bandIndex < 0 || bandIndex >= BAND_COUNT) return
    val clamped = gainDb.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
    gainsDb[bandIndex] = clamped
    for (slot in instances.values) {
      val legacyBand = slot.ourToLegacyBand[bandIndex].toShort()
      slot.equalizer.setBandLevel(legacyBand, dbToMillibelsClamped(clamped, slot))
    }
  }

  override fun setAllBandGains(gains: FloatArray) {
    if (released) return
    val n = minOf(gains.size, BAND_COUNT)
    for (i in 0 until n) {
      gainsDb[i] = gains[i].coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
    }
    for (slot in instances.values) applyAllBandsToSlot(slot)
  }

  override fun getBandGains(): FloatArray = gainsDb.copyOf()

  override fun setEnabled(value: Boolean) {
    if (released) return
    enabled = value
    for (slot in instances.values) slot.equalizer.enabled = value
  }

  override fun isEnabled(): Boolean = enabled

  override fun release() {
    if (released) return
    released = true
    detachAll()
  }

  private fun applyAllBandsToSlot(slot: Slot) {
    for (i in 0 until BAND_COUNT) {
      val legacyBand = slot.ourToLegacyBand[i].toShort()
      slot.equalizer.setBandLevel(legacyBand, dbToMillibelsClamped(gainsDb[i], slot))
    }
  }

  /**
   * Convert dB → millibels, then clamp to the device-reported
   * legacy range. The device clamp is defensive: most chips report
   * ±1500 mB but a few outliers go ±900 or ±1800 and would throw
   * `IllegalArgumentException` on out-of-range writes.
   */
  private fun dbToMillibelsClamped(gainDb: Float, slot: Slot): Short {
    val raw = (gainDb * MILLIBELS_PER_DB).roundToInt()
    val clamped = raw.coerceIn(slot.deviceMinMb.toInt(), slot.deviceMaxMb.toInt())
    return clamped.toShort()
  }
}
