package com.margelo.nitro.queueplayer

/**
 * Lifecycle + state-mutation surface shared by [EqualizerEngine]
 * (API 28+, `DynamicsProcessing`) and [EqualizerLegacyEngine]
 * (API <28, legacy `Equalizer`). The HybridObject in
 * `Equalizer.kt` picks one impl on construction and routes every
 * call through the interface; consumers never see which backend
 * is active.
 *
 * Threading: see the implementations — attach / detach arrive on the
 * service main looper and on the Equalizer-constructing thread, the
 * mutators on the `Promise.async` pool, the getters on the JS thread.
 */
interface IEqualizerEngine {

  /**
   * Attach the engine to every session ID in [sessionIds],
   * detaching from any session ID currently attached but no
   * longer present in the new array. Diffing semantics so the
   * caller can fire this method with the fresh `IntArray` from
   * `PlaybackEngine.addAudioSessionIdListener` on every change
   * without per-ID bookkeeping.
   *
   * Single-session callers pass a 1-element array. Crossfade-
   * window callers pass the 2-element [outgoing, incoming]
   * array; both sessions end up holding their own
   * `DynamicsProcessing` (or legacy `Equalizer` AudioEffect)
   * instance carrying identical band gains so EQ continuity is
   * preserved across the fade.
   *
   * Returns the per-session attach result so callers can log /
   * diagnose individual session failures. An empty input array
   * is equivalent to [detachAll].
   */
  fun attachAll(sessionIds: IntArray): Map<Int, Boolean>

  /**
   * Release every active per-session instance. Band-gain +
   * enabled state survive — a subsequent [attachAll] re-attaches
   * the same configured EQ to whichever sessions arrive next.
   */
  fun detachAll()

  /**
   * Attach to a single [audioSessionId]. Convenience wrapper
   * around [attachAll] for legacy single-session callers; the
   * crossfade-engine path goes through [attachAll] directly.
   *
   * Returns `true` on success, `false` when the underlying
   * audio-effect framework rejects the session — failure leaves
   * the engine cleanly detached so a retry on a fresh session
   * ID is safe.
   *
   * [channelCount] is ignored on both backends — modern fans
   * out internally via `setPreEqBandAllChannelsTo`, legacy is
   * session-wide.
   */
  fun attach(audioSessionId: Int, channelCount: Int = 2): Boolean =
    attachAll(intArrayOf(audioSessionId))[audioSessionId] ?: false

  /**
   * Convenience wrapper for legacy single-session callers —
   * equivalent to [detachAll] when only one session is held.
   */
  fun detach() = detachAll()

  /** True when the engine is attached to at least one audio session. */
  fun isAttached(): Boolean

  /**
   * Set the gain (dB) for [bandIndex] in [0, 10). Out-of-range
   * indices are silently ignored. The gain is clamped to the
   * project-wide spec range so values outside the documented spec
   * can never reach the hardware.
   */
  fun setBandGain(bandIndex: Int, gainDb: Float)

  /**
   * Replace every band gain at once. Each entry is clamped to the
   * project-wide spec range. Extra entries past 10 are ignored;
   * missing entries leave the previous value unchanged.
   */
  fun setAllBandGains(gains: FloatArray)

  /**
   * Snapshot of current band gains (dB), low-to-high frequency
   * indexed by [EqualizerPresets.frequencies]. Remains callable
   * after [release] for telemetry / diagnostics.
   */
  fun getBandGains(): FloatArray

  fun setEnabled(value: Boolean)

  fun isEnabled(): Boolean

  /** Permanent teardown — subsequent calls become no-ops. */
  fun release()
}
