import Foundation
import NitroModules

/// 10-band parametric equalizer HybridObject for iOS.
///
/// Routes the JS-facing surface through:
///  - `AudioTapProvider` (shared per-AVPlayerItem cascade attach +
///    post-EQ frame fan-out to other consumers)
///  - `EQProcessor` (the actual vDSP_biquad pipeline, instantiated
///    per item by the provider)
///  - `CustomPresetStore` (UserDefaults-backed custom presets)
///
/// The provider is module-internal (`AudioTapProvider.shared`); the
/// playback engine reaches it at `setAudioMixProvider` time via the
/// `wireToPlaybackEngine` call below.
///
/// Reconstruction contract: when JS drops the `getEqualizer()`
/// singleton and a fresh Hybrid is constructed (e.g. after a JS
/// reload), the new instance resets the provider to `enabled=false`
/// + flat gains. Consumer apps that persist EQ state across reloads
/// must reapply it after the new Hybrid is ready — the lib does not
/// fire `onEnabledChange` / `onBandChange` for this implicit reset
/// because the JS side already knows it just dropped the singleton.
final class Equalizer: HybridEqualizerSpec {

  private static let currentLock = NSLock()
  /// The most recently constructed Equalizer instance. The iOS
  /// playback engine reaches for this at attach time so it can
  /// wire the shared `AudioTapProvider` to the active player without
  /// threading the Hybrid reference through Nitro's bridge.
  ///
  /// Acquire `currentLock` when reading or writing — `init()` runs
  /// on the JS bridge while a prior instance may still be mid-
  /// `audioMix(for:)` or mid-`deliverAudioFrame` on main / render.
  /// Mirrors the locked-static pattern on `Visualizer.current`.
  ///
  /// Strong reference: lifecycle is JS-managed (Nitro releases the
  /// Hybrid when the JS-side `getEqualizer()` singleton drops),
  /// so the strong static doesn't cause a leak — JS reload zeroes
  /// it via the second-construction teardown path below.
  private static var _current: Equalizer?
  static var current: Equalizer? {
    get {
      currentLock.lock(); defer { currentLock.unlock() }
      return _current
    }
    set {
      currentLock.lock(); defer { currentLock.unlock() }
      _current = newValue
    }
  }

  let provider: AudioTapProvider
  private let presetStore: CustomPresetStore
  private var consumer: AudioTapProvider.Consumer?

  /// Master gain state. Source of truth on the JS side; the
  /// provider replicates this to per-item processors at attach
  /// time. Each mutation (band gain, preset apply) updates this
  /// AND the provider's master state in lockstep.
  ///
  /// Read + written under [stateQueue].
  private var gainsDb: [Double] = Array(repeating: 0, count: 10)

  /// Enabled state mirrored to the provider; the provider's
  /// `audioMix(for:)` short-circuits when no consumer wants the tap.
  ///
  /// Read + written under [stateQueue].
  private var enabled: Bool = false

  /// Serialises every state-mutating path so JS callers firing
  /// `setBandGain` and `setEnabled` concurrently from
  /// `Promise.async` (which runs on Swift's cooperative pool, not
  /// main) don't tear `gainsDb` or race the provider's master
  /// gains. Reads from `getBands` and `isEnabled` also take the
  /// queue so they observe a consistent snapshot.
  private let stateQueue = DispatchQueue(label: "com.margelo.nitro.queueplayer.equalizerState")

  private let onBandChangeListeners = ListenerRegistry<([EqualizerBand]) -> Void>()
  private let onEnabledChangeListeners = ListenerRegistry<(Bool) -> Void>()

  init(provider: AudioTapProvider = .shared,
       presetStore: CustomPresetStore = CustomPresetStore()) {
    self.provider = provider
    self.presetStore = presetStore
    super.init()
    // Tear the prior instance's consumer registration down before it
    // loses the static reference — otherwise the prior instance's
    // `wantsTap` predicate keeps the tap installed on the engine
    // even after JS has dropped that Equalizer Hybrid.
    Equalizer.current?.detachConsumerOnRefresh()
    Equalizer.current = self
    provider.setEnabled(false)
    provider.setGains(gainsDb)
    registerConsumer()
  }

  /// Detach the prior Equalizer instance's consumer + EQ state when
  /// a fresh instance takes over [Equalizer.current]. Called from the
  /// new instance's `init` before it claims the static reference.
  internal func detachConsumerOnRefresh() {
    if let consumer = self.consumer {
      provider.unregister(consumer)
      self.consumer = nil
    }
  }

  private func registerConsumer() {
    // Tap install demand reads the provider's own enabled flag; the
    // closure does not capture `self`, so the consumer outlives an
    // Equalizer Hybrid replacement without holding the prior Hybrid
    // alive. The EQ's post-DSP frame hook fires regardless of the
    // enabled state — when enabled is false but another consumer
    // (visualizer) wants the tap, the EQProcessor runs pass-through
    // and still produces a valid mono mix.
    let provider = self.provider
    let consumer = AudioTapProvider.Consumer(
      wantsTap: { provider.isEnabledSnapshot() },
      // The Equalizer produces the post-EQ pipeline (its EQProcessor
      // runs the DSP inside the tap). It does not need a separate
      // frame-receive callback; consumed-by-EQ frames are delivered
      // to other consumers through the provider's `deliverAudioFrame`.
      onAudioFrame: { _, _, _ in },
      // The EQ doesn't need active-asset notifications — its DSP
      // path is per-item and the provider already gates HLS bypass
      // before constructing an EQProcessor.
      onActiveAssetChange: { _ in }
    )
    self.consumer = consumer
    provider.register(consumer)
  }

  // MARK: - JS-facing async surface

  func setEnabled(enabled: Bool) throws -> Promise<Void> {
    return Promise.async {
      self.setEnabledInternal(enabled)
    }
  }

  func isEnabled() throws -> Bool {
    return stateQueue.sync { enabled }
  }

  func getBands() throws -> [EqualizerBand] {
    return stateQueue.sync { currentBandsSnapshot() }
  }

  func setBandGain(index: Double, gainDb: Double) throws -> Promise<Void> {
    return Promise.async {
      self.setBandGainInternal(index: Int(index), gainDb: gainDb)
    }
  }

  func setAllBandGains(gains: [Double]) throws -> Promise<Void> {
    return Promise.async {
      self.setAllBandGainsInternal(gains)
    }
  }

  func applyPreset(name: String) throws -> Promise<Void> {
    return Promise.async {
      if !self.applyPresetInternal(name: name) {
        throw RuntimeError("Equalizer preset not found: \(name)")
      }
    }
  }

  func getPresets() throws -> [EqualizerPreset] {
    let custom = presetStore.loadAll().sorted { $0.name < $1.name }
    return builtinPresets() + custom
  }

  func saveCustomPreset(name: String) throws -> Promise<Void> {
    return Promise.async {
      if EqualizerPresets.getByName(name) != nil {
        throw RuntimeError("Cannot overwrite built-in preset: \(name)")
      }
      // Snapshot current gains under the state lock so a JS-side
      // setBandGain in flight doesn't tear the array we persist.
      let snapshot = self.stateQueue.sync { self.gainsDb }
      self.presetStore.save(name: name, gains: snapshot)
    }
  }

  func deleteCustomPreset(name: String) throws -> Promise<Void> {
    return Promise.async {
      self.presetStore.delete(name: name)
    }
  }

  func reset() throws -> Promise<Void> {
    return Promise.async {
      _ = self.applyPresetInternal(name: "Flat")
    }
  }

  func onBandChange(
    callback: @escaping (_ bands: [EqualizerBand]) -> Void
  ) throws -> () -> Void {
    let id = onBandChangeListeners.add(callback)
    // Seed the new subscriber with the current snapshot so consumers
    // don't have to call getBands first. Spec contract.
    callback(stateQueue.sync { currentBandsSnapshot() })
    return { [weak self] in self?.onBandChangeListeners.remove(id: id) }
  }

  func onEnabledChange(
    callback: @escaping (_ enabled: Bool) -> Void
  ) throws -> () -> Void {
    let id = onEnabledChangeListeners.add(callback)
    callback(stateQueue.sync { enabled })
    return { [weak self] in self?.onEnabledChangeListeners.remove(id: id) }
  }

  // MARK: - Engine wiring

  /// Wire the shared `AudioTapProvider` onto [playbackEngine] so the
  /// engine can ask for an `AVAudioMix` per AVPlayerItem it queues.
  /// Called by `TrackPlayer` once its `GaplessEngine` is constructed;
  /// the provider's `audioMix(for:)` then applies the per-item EQ
  /// cascade. Mirrors the Android `wireToPlaybackEngine` pattern.
  func wireToPlaybackEngine(_ playbackEngine: PlaybackEngine) {
    provider.wireToPlaybackEngine(playbackEngine)
  }

  // MARK: - Synchronous core
  //
  // Every mutating method is serialised under [stateQueue] so JS
  // callers firing concurrently from `Promise.async` (Swift's
  // cooperative pool) don't tear `gainsDb`, `enabled`, or the
  // provider's master gains. Listener fires happen outside the
  // lock to keep the registry's own queue from interleaving.
  //
  // Band-gain mutations (`setBandGainInternal`,
  // `setAllBandGainsInternal`) skip the engine-refresh path: a
  // call to `provider.setGains` bumps the master gains version
  // counter, and each active per-item tap recomputes coefficients
  // on its next render-thread callback. Keeping the audioMix
  // attached avoids the audible cutout an attach/detach cycle
  // would cause on slider drag.

  func setEnabledInternal(_ value: Bool) {
    let changed: Bool = stateQueue.sync {
      if enabled == value { return false }
      enabled = value
      provider.setEnabled(value)
      return true
    }
    if changed {
      // Re-attach the audioMix on every queued AVPlayerItem so the
      // currently-playing track picks up the new enabled state. Without
      // this the user toggles EQ on but hears nothing — the active item's
      // audioMix was set to nil when the provider was disabled, and
      // AVPlayerItem.audioMix is a one-shot wire-up at queue time.
      provider.refreshActiveItemMixes()
      onEnabledChangeListeners.forEach { $0(value) }
    }
  }

  func setBandGainInternal(index: Int, gainDb: Double) {
    if index < 0 || index >= 10 { return }
    let snapshot: [EqualizerBand]? = stateQueue.sync {
      let clamped = max(-12, min(12, gainDb))
      gainsDb[index] = clamped
      provider.setGains(gainsDb)
      return currentBandsSnapshot()
    }
    if let snapshot = snapshot {
      onBandChangeListeners.forEach { $0(snapshot) }
    }
  }

  func setAllBandGainsInternal(_ gains: [Double]) {
    let snapshot: [EqualizerBand]? = stateQueue.sync {
      let n = min(gains.count, 10)
      for i in 0..<n {
        gainsDb[i] = max(-12, min(12, gains[i]))
      }
      provider.setGains(gainsDb)
      return currentBandsSnapshot()
    }
    if let snapshot = snapshot {
      onBandChangeListeners.forEach { $0(snapshot) }
    }
  }

  /// Returns true when the named preset existed and was applied.
  /// The async wrapper drops the boolean and resolves Promise<Void>.
  @discardableResult
  func applyPresetInternal(name: String) -> Bool {
    if let presetGains = EqualizerPresets.getByName(name) {
      setAllBandGainsInternal(presetGains)
      return true
    }
    let customs = presetStore.loadAll()
    if let custom = customs.first(where: { $0.name == name }) {
      setAllBandGainsInternal(custom.gains)
      return true
    }
    return false
  }

  // MARK: - Helpers

  private func currentBandsSnapshot() -> [EqualizerBand] {
    return EqualizerPresets.frequencies.enumerated().map { idx, freq in
      EqualizerBand(frequencyHz: freq, gainDb: gainsDb[idx])
    }
  }

  private func builtinPresets() -> [EqualizerPreset] {
    return EqualizerPresets.getNames().map { name in
      EqualizerPreset(
        name: name,
        gains: EqualizerPresets.getByName(name) ?? [],
        builtin: true
      )
    }
  }
}
