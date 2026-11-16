package com.margelo.nitro.queueplayer

import android.content.Context
import android.os.Build
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import com.facebook.proguard.annotations.DoNotStrip
import com.margelo.nitro.NitroModules
import com.margelo.nitro.core.Promise
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 10-band parametric equalizer HybridObject. Picks
 * [EqualizerEngine] (API 28+, `DynamicsProcessing`) or
 * [EqualizerLegacyEngine] (API <28, legacy `Equalizer`) at
 * construction; consumers see a single surface regardless of
 * backend.
 *
 * The engine is parameterised on the audio session ID, which
 * arrives later from the playback service when ExoPlayer publishes
 * its `Format`. Until then the engine buffers band gains + enabled
 * state — JS callers can configure presets and bands freely; the
 * configuration applies the moment the service-level attach to the
 * session ID runs.
 *
 * The JS-facing methods that mutate state return `Promise<Unit>`
 * via `Promise.async` per the spec, but each delegates to a
 * synchronous `*Internal` method that owns the actual mutation.
 * The async wrapper is a thin JS-interface adapter.
 *
 * Listener registries follow the same shape as other event
 * listeners on this lib: stable numeric IDs returned via the
 * cancel closure so individual subscribers can dispose without
 * disturbing the others.
 */
@UnstableApi
@DoNotStrip
class Equalizer @JvmOverloads constructor(
  /**
   * Optional Context override for the persistent preset store.
   * `null` (the default Nitro construction path) resolves to
   * [NitroModules.applicationContext] at first store access.
   * Pass an explicit Context when constructing this Hybrid
   * outside the React Native bridge — e.g. unit tests, or a
   * future consumer-app override that wants a different
   * SharedPreferences scope.
   */
  storeContext: Context? = null,
) : HybridEqualizerSpec() {

  companion object {
    /**
     * The most recently constructed [Equalizer] instance.
     * `PlaybackService` reaches for this on engine boot so it
     * can wire `IEqualizerEngine` to ExoPlayer's audio session
     * without threading the Hybrid reference through Nitro's
     * JNI bridge. JS callers consume `getEqualizer()` which
     * caches the same Hybrid singleton in `clients.ts`, so the
     * static reference and the JS-visible instance always
     * coincide in practice.
     */
    @JvmStatic
    @Volatile
    internal var current: Equalizer? = null
  }

  private val storeContextOverride: Context? = storeContext

  /**
   * Module-internal escape hatch so the playback service can wire
   * the engine to ExoPlayer's audio session ID at attach time.
   * Pure JS-facing callers must not reach for this property.
   *
   * Declared BEFORE the `init` block + `sessionIdSubscription` so
   * the field initializer runs first. The init body calls
   * `current?.unwireFromPlaybackEngine()` on the prior Equalizer
   * instance — which dereferences that prior instance's `engine`.
   * Kotlin runs property initializers + init blocks in source
   * order; placing this val above `init` guarantees any
   * fully-constructed instance always has a non-null `engine`
   * before another construction can reach it through the static
   * `current` pointer. Past failure: NPE on
   * `IEqualizerEngine.detachAll()` when two Nitro Hybrid
   * constructions raced.
   */
  internal val engine: IEqualizerEngine =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      EqualizerEngine()
    } else {
      EqualizerLegacyEngine()
    }

  /**
   * Cancel closure for the engine audio-session-ID subscription
   * installed by [wireToPlaybackEngine]. Held so a re-wire (engine
   * swap during configure) tears the prior subscription down before
   * resubscribing.
   */
  private var sessionIdSubscription: (() -> Unit)? = null

  init {
    // A second Equalizer construction (test order, hot-restart) tears
    // the prior instance's playback wiring down before it loses the
    // static reference — otherwise the prior instance's
    // `sessionIdSubscription` keeps firing against a service that
    // calls `Equalizer.current.unwire` on the new instance only.
    current?.unwireFromPlaybackEngine()
    current = this
    // Late-construction recovery: same pattern as Visualizer.
    // PlaybackService's onCreate fires `Equalizer.current?.wire(...)`
    // unconditionally; if this Hybrid was constructed AFTER that
    // call, the wire is silently lost. Re-wire here so the engine
    // session-ID listener actually fires.
    PlaybackService.activeEngine?.let { wireToPlaybackEngine(it) }
  }

  private val onBandChangeListeners = ListenerRegistry<(Array<EqualizerBand>) -> Unit>()
  private val onEnabledChangeListeners = ListenerRegistry<(Boolean) -> Unit>()

  /**
   * Custom-preset persistence resolved lazily because
   * [NitroModules.applicationContext] may be null on construction
   * if the React Native runtime hasn't installed the bridge yet.
   * Returns null when neither the override nor the Nitro bridge
   * provides a Context — surfaces as "no custom presets" rather
   * than a crash.
   */
  private val customPresetStore: CustomPresetStore? by lazy {
    val ctx = storeContextOverride ?: NitroModules.applicationContext
    ctx?.let { CustomPresetStore(it) }
  }

  // --- Engine lifecycle wiring ---

  /**
   * Subscribe to [playbackEngine]'s audio session ID changes.
   * Forwards the full session-id snapshot from
   * `PlaybackEngine.addAudioSessionIdListener` to
   * `IEqualizerEngine.attachAll`. The engine diffs its per-session
   * map against the new array — single-session gapless callers see
   * one ID and one attached `DynamicsProcessing` (or legacy
   * `Equalizer`) instance; crossfade-window callers see
   * `[outgoing, incoming]` and the engine holds two simultaneous
   * instances so EQ continuity survives the fade. A re-wire (e.g.
   * configure rebuilding the playback engine) tears the prior
   * subscription down before resubscribing so the engine
   * reattaches cleanly.
   *
   * Channel count is ignored on both backends. The modern backend's
   * `setPreEqBandAllChannelsTo` fans each band write out to every real
   * channel of the in-effect channel array; the legacy `Equalizer`
   * AudioEffect is session-wide.
   */
  internal fun wireToPlaybackEngine(playbackEngine: PlaybackEngine) {
    unwireFromPlaybackEngine()
    sessionIdSubscription = playbackEngine.addAudioSessionIdListener { sessionIds ->
      // Forward the full session-id snapshot — the engine diffs
      // its per-session map against the new array and attaches /
      // detaches per-instance accordingly. Crossfade-window
      // callers receive [outgoing, incoming] and the engine
      // holds two simultaneous DynamicsProcessing instances; a
      // single-session caller receives one ID and the engine
      // holds exactly one.
      val sanitised = sessionIds.filter { it != C.AUDIO_SESSION_ID_UNSET }.toIntArray()
      engine.attachAll(sanitised)
    }
  }

  /**
   * Tear down any active subscription and detach the engine from
   * every underlying audio session. Gain + enabled state survive
   * — a subsequent [wireToPlaybackEngine] re-attaches the same
   * configured EQ to whichever sessions are presented next.
   */
  internal fun unwireFromPlaybackEngine() {
    sessionIdSubscription?.invoke()
    sessionIdSubscription = null
    engine.detachAll()
  }

  // --- Synchronous core ---

  internal fun setEnabledInternal(enabled: Boolean) {
    if (engine.isEnabled() == enabled) return
    engine.setEnabled(enabled)
    // Emit what the engine committed, not what was asked for: a released
    // engine drops the call, and `onEnabledChange` registration replays
    // `engine.isEnabled()`, so emitting the request here would hand
    // subscribers a value `isEnabled()` contradicts.
    onEnabledChangeListeners.forEach { it(engine.isEnabled()) }
  }

  internal fun setBandGainInternal(index: Int, gainDb: Float) {
    engine.setBandGain(index, gainDb)
    fireBandChange()
  }

  /**
   * Applies the gains as-is — the engine's documented contract is
   * "extra entries past 10 are ignored, missing entries hold the
   * previous value", so a short array partial-updates rather than
   * zero-padding.
   */
  internal fun setAllBandGainsInternal(gains: FloatArray) {
    engine.setAllBandGains(gains)
    fireBandChange()
  }

  /**
   * Returns `true` when the named preset existed and was applied.
   * The async surface ([applyPreset]) translates a `false` return
   * into an `IllegalArgumentException` so JS callers see a Promise
   * rejection on a typo; this `Boolean` form remains available for
   * callers that want a non-throwing apply (e.g. [resetInternal]
   * which always passes a known-good name).
   */
  internal fun applyPresetInternal(name: String): Boolean {
    val builtin = EqualizerPresets.getByName(name)
    if (builtin != null) {
      engine.setAllBandGains(builtin)
      fireBandChange()
      return true
    }
    val customGains = customPresetStore?.loadAll()
      ?.firstOrNull { it.name == name }
      ?.gains
    if (customGains != null) {
      val asFloats = FloatArray(customGains.size) { i -> customGains[i].toFloat() }
      engine.setAllBandGains(asFloats)
      fireBandChange()
      return true
    }
    return false
  }

  internal fun resetInternal() {
    applyPresetInternal("Flat")
  }

  // --- JS-facing async surface ---
  //
  // Every mutation of the engine runs on the main looper: `attachAll` is
  // driven there by the playback engine's session-id listener, and running
  // the setters on the `Promise.async` pool alongside it let a setter iterate
  // an instance the looper had just released, two setters interleave their
  // band writes, or a setter miss an instance an attach was inserting. One
  // executor for all of it closes those.

  override fun setEnabled(enabled: Boolean): Promise<Unit> =
    Promise.async { withContext(Dispatchers.Main.immediate) { setEnabledInternal(enabled) } }

  override fun isEnabled(): Boolean = engine.isEnabled()

  override fun getBands(): Array<EqualizerBand> = currentBandsSnapshot()

  override fun setBandGain(index: Double, gainDb: Double): Promise<Unit> =
    Promise.async {
      withContext(Dispatchers.Main.immediate) { setBandGainInternal(index.toInt(), gainDb.toFloat()) }
    }

  override fun setAllBandGains(gains: DoubleArray): Promise<Unit> =
    Promise.async {
      val asFloats = FloatArray(gains.size) { i -> gains[i].toFloat() }
      withContext(Dispatchers.Main.immediate) { setAllBandGainsInternal(asFloats) }
    }

  override fun applyPreset(name: String): Promise<Unit> =
    Promise.async {
      val applied = withContext(Dispatchers.Main.immediate) { applyPresetInternal(name) }
      if (!applied) {
        throw IllegalArgumentException("Equalizer preset not found: $name")
      }
    }

  override fun getPresets(): Array<EqualizerPreset> {
    val builtin = builtinPresetsArray()
    val custom = (customPresetStore?.loadAll() ?: emptyList())
      .sortedBy { it.name }
    if (custom.isEmpty()) return builtin
    return Array(builtin.size + custom.size) { i ->
      if (i < builtin.size) builtin[i] else custom[i - builtin.size]
    }
  }

  /**
   * Persist the current band gains as [name]. Overwrites a same-
   * name custom preset. Names that collide with a built-in preset
   * are rejected at the JS-facing [saveCustomPreset] entry point;
   * this internal core does not re-check.
   */
  internal fun saveCustomPresetInternal(name: String) {
    val store = customPresetStore ?: return
    store.save(name, engine.getBandGains())
  }

  /** Remove the named custom preset; no-op if absent. */
  internal fun deleteCustomPresetInternal(name: String) {
    customPresetStore?.delete(name)
  }

  override fun saveCustomPreset(name: String): Promise<Unit> =
    Promise.async {
      if (EqualizerPresets.getByName(name) != null) {
        throw IllegalArgumentException("Cannot overwrite built-in preset: $name")
      }
      // The gains are read on the looper that writes them; the `commit` to
      // preferences stays off it.
      val gains = withContext(Dispatchers.Main.immediate) { engine.getBandGains() }
      customPresetStore?.save(name, gains)
    }

  override fun deleteCustomPreset(name: String): Promise<Unit> =
    Promise.async { deleteCustomPresetInternal(name) }

  override fun reset(): Promise<Unit> =
    Promise.async { withContext(Dispatchers.Main.immediate) { resetInternal() } }

  override fun onBandChange(
    callback: (bands: Array<EqualizerBand>) -> Unit
  ): () -> Unit {
    val id = onBandChangeListeners.add(callback)
    // Seed the new subscriber with the current snapshot so
    // consumers don't have to call getBands first. Spec contract.
    callback(currentBandsSnapshot())
    return { onBandChangeListeners.remove(id) }
  }

  override fun onEnabledChange(callback: (enabled: Boolean) -> Unit): () -> Unit {
    val id = onEnabledChangeListeners.add(callback)
    callback(engine.isEnabled())
    return { onEnabledChangeListeners.remove(id) }
  }

  // --- Helpers ---

  private fun fireBandChange() {
    if (onBandChangeListeners.isEmpty) return
    val snapshot = currentBandsSnapshot()
    onBandChangeListeners.forEach { it(snapshot) }
  }

  private fun currentBandsSnapshot(): Array<EqualizerBand> {
    val freqs = EqualizerPresets.frequencies
    val gains = engine.getBandGains()
    return Array(freqs.size) { i ->
      EqualizerBand(
        frequencyHz = freqs[i].toDouble(),
        gainDb = gains[i].toDouble(),
      )
    }
  }

  private fun builtinPresetsArray(): Array<EqualizerPreset> {
    val names = EqualizerPresets.getNames()
    return Array(names.size) { i ->
      val name = names[i]
      val gains = EqualizerPresets.getByName(name)!!
      EqualizerPreset(
        name = name,
        gains = DoubleArray(gains.size) { j -> gains[j].toDouble() },
        builtin = true,
      )
    }
  }
}
