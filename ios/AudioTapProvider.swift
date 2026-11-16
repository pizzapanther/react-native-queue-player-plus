import AVFoundation
import Foundation

/// Module-internal `AudioMixProvider` shared by the equalizer and
/// visualizer Hybrids on iOS.
///
/// One tap per `AVPlayerItem`, one set of post-EQ samples, fanned out
/// to every registered consumer. Both `Equalizer` and `Visualizer`
/// register at Hybrid construction; neither references the other.
///
/// Responsibilities:
///  - Owns the per-item `AVAudioMix` build (the EQ tap + DSP closure).
///  - Holds the equalizer master gain state (gains + version + enabled
///    flag) consulted by the per-item `EQProcessor` cascade.
///  - Decides whether to install a tap on a given item: install when
///    any registered consumer's `wantsTap` returns true. The tap runs
///    EQ in pass-through when only the visualizer wants frames.
///  - Fans out post-EQ audio frames to every registered consumer's
///    `onAudioFrame` on the audio render thread.
///  - Notifies registered consumers when the active asset changes so
///    HLS detection lands at subscribe time.
///  - Asks the playback engine to re-evaluate per-item mixes when a
///    consumer's `wantsTap` would change (EQ enable toggle, first
///    visualizer subscriber, last subscriber drop).
///
/// Threading:
///  - Consumer registration runs on the JS bridge thread at Hybrid
///    construction.
///  - `audioMix(for:)` runs on the mix dispatch target — main by
///    default, `TrackPlayer`'s `playerQueue` once it wires in.
///  - `deliverAudioFrame` runs on the audio render thread; the
///    per-frame fan-out walks an immutable consumer snapshot under
///    [consumersLock] and is allocation-free.
final class AudioTapProvider: AudioMixProvider {

  /// Module-internal singleton. Both `Equalizer` and `Visualizer`
  /// register against this instance at Hybrid construction time. The
  /// constructor (`AudioTapProvider()`) stays available for tests
  /// that need an isolated provider.
  static let shared = AudioTapProvider()

  /// Consumer of the post-EQ audio pipeline. Equalizer + Visualizer
  /// each construct one of these and register it with the provider
  /// at Hybrid init. Identity is the consumer reference itself —
  /// duplicate registrations replace the prior entry.
  final class Consumer {
    /// Returns true when this consumer needs the tap installed on
    /// the active player items right now (EQ enabled / visualizer
    /// has subscribers). Polled from `audioMix(for:)` on the mix
    /// dispatch target.
    let wantsTap: () -> Bool
    /// Render-thread callback fired once per render batch with the
    /// post-EQ mono mixdown. Must not allocate, lock, or
    /// synchronously dispatch.
    let onAudioFrame: (UnsafePointer<Float>, Int, Double) -> Void
    /// Fired on the mix dispatch target from `audioMix(for:)` whenever
    /// a new item becomes the candidate for tap install. Fires for EVERY
    /// candidate item the engine queues, including items where the
    /// tap will not install (HLS, format-detection failure, no
    /// consumer wantsTap). Consumers that need post-install
    /// confirmation must check `EQTap.isHLS(asset)` themselves.
    /// Used by the visualizer to surface `hls-source` synchronously
    /// at subscribe time without poking the player.
    let onActiveAssetChange: (AVAsset?) -> Void

    init(
      wantsTap: @escaping () -> Bool,
      onAudioFrame: @escaping (UnsafePointer<Float>, Int, Double) -> Void,
      onActiveAssetChange: @escaping (AVAsset?) -> Void
    ) {
      self.wantsTap = wantsTap
      self.onAudioFrame = onAudioFrame
      self.onActiveAssetChange = onActiveAssetChange
    }
  }

  /// Lock protecting `currentGains` + `enabled` + `consumers`.
  /// Acquired on the audio render thread (gain snapshot), the JS
  /// bridge (consumer registration + state mutation), and the
  /// playback engine's mix path (`audioMix(for:)` on the mix dispatch
  /// target); short critical section keeps render-thread cost
  /// negligible.
  private let stateLock = NSLock()

  private var currentGains: [Double]
  private var gainsVersion: UInt64 = 0
  private var enabled: Bool = false
  /// Pin-at-configure flag honoured by the visualizer Hybrid. When
  /// false, `Visualizer.subscribe` rejects with `'visualization-disabled'`
  /// and the visualizer's Consumer's `wantsTap` returns false even when
  /// JS subscribers exist — `audioMix(for:)`'s `anyConsumerWantsTap()`
  /// short-circuit then skips tap construction unless EQ also wants
  /// frames. Written by `TrackPlayer.configure(_:)` on every call;
  /// default `true` preserves existing behaviour for consumers who
  /// never set the new field.
  private var visualizationEnabled: Bool = true
  private var consumers: [Consumer] = []

  /// Weak reference to the active playback engine. Wired in by
  /// `wireToPlaybackEngine`; held weak so the provider never extends
  /// the engine's lifetime (iOS rebuilds the engine on TrackPlayer
  /// destroy + reconfigure).
  private weak var playbackEngine: PlaybackEngine?

  /// Dispatch target for the engine-facing mix work in
  /// `wireToPlaybackEngine`, `detachFromEngine` and
  /// `refreshActiveItemMixes`. Defaults to the
  /// main queue; `setMixDispatchTarget` repoints it at whichever
  /// queue serialises player access and `resetMixDispatchTarget`
  /// puts it back.
  private var mixQueue: DispatchQueue = .main
  /// Reports whether the calling thread is already executing on
  /// `mixQueue`, so an in-queue caller runs inline.
  private var mixQueueIsCurrent: () -> Bool = { Thread.isMainThread }

  /// Live `ReplayGain` state for every queued `AVPlayerItem`. Each
  /// `audioMix(for:)` allocates a fresh `ItemRGState`, attaches it
  /// to the item via objc associated-object so the state's lifetime
  /// matches the item's, and registers it here for mode-driven
  /// recompute. The hash table holds weak references — entries
  /// disappear automatically when the engine drops the underlying
  /// item from its queue.
  private let activeItemStates = NSHashTable<ItemRGState>.weakObjects()

  /// Currently-selected ReplayGain mode. Read on every mode-driven
  /// recompute path (item-state-extract completion, mode change).
  private var replayGainMode: ReplayGainMode = .off

  /// Fired after the per-item async metadata extract completes and
  /// `ItemRGState.setData` lands. TrackPlayer subscribes to re-resolve
  /// the now-playing format for the affected item — without this, the
  /// initial format snapshot taken at `\.currentItem` KVO time captures
  /// `replayGainDataForItem == nil` (the extract is still in flight)
  /// and the cached format never refreshes when RG values eventually
  /// land. Receives the AVPlayerItem the extract completed for.
  var onItemReplayGainDataPopulated: ((AVPlayerItem) -> Void)? {
    get { stateLock.withLock { replayGainPopulatedCallback } }
    set { stateLock.withLock { replayGainPopulatedCallback = newValue } }
  }
  /// Written by `TrackPlayer` on its player queue; read under `stateLock`
  /// where the extract completes.
  private var replayGainPopulatedCallback: ((AVPlayerItem) -> Void)?

  init(initialGains: [Double] = Array(repeating: 0, count: EQProcessor.bandCount)) {
    self.currentGains = initialGains
    // A consumer that always wants the tap, registered for the provider's
    // life, so the tap is installed regardless of EQ / visualizer state and
    // a `setReplayGainMode` off → on transition affects the currently-playing
    // item without re-installing `AVPlayerItem.audioMix` — AVPlayer silently
    // drops a fresh `audioMix` on an item that has begun rendering (see
    // `GaplessEngine.refreshAllItemMixes`). The cost with RG off is
    // negligible: `ReplayGainProcessor.process` early-returns on a unity-gain
    // test at the head of each callback, leaving the pass-through EQ and the
    // mono mixdown. EQ and Visualizer consumers gate their own contribution
    // independently; this one only keeps the tap in place so the RG gain
    // `recomputeWithLock` updates is read by the audio render thread on
    // every buffer batch.
    register(
      Consumer(
        wantsTap: { true },
        onAudioFrame: { _, _, _ in },
        onActiveAssetChange: { _ in }
      ))
  }

  // MARK: - Engine wiring

  /// Route the provider's engine calls through [queue]. [queue] must
  /// serialise all player access, and [isCurrent] must report whether
  /// the calling thread is already on it — a caller that is runs the
  /// work inline instead of deferring it behind an async hop. Defaults
  /// to the main queue.
  func setMixDispatchTarget(_ queue: DispatchQueue, isCurrent: @escaping () -> Bool) {
    stateLock.lock()
    mixQueue = queue
    mixQueueIsCurrent = isCurrent
    stateLock.unlock()
  }

  /// Put the engine-facing mix work back on the main queue. The provider
  /// outlives any one player, so a teardown that leaves a player-owned queue
  /// installed here would dispatch the next player's mix work onto a dead one.
  func resetMixDispatchTarget() {
    stateLock.lock()
    mixQueue = .main
    mixQueueIsCurrent = { Thread.isMainThread }
    stateLock.unlock()
  }

  /// Wire the provider to [engine] so per-item `audioMix(for:)`
  /// queries route through this provider, and so consumers can ask
  /// for an engine-wide refresh when their `wantsTap` flips. Hops to
  /// the mix dispatch target if needed — AVFoundation expects
  /// `setAudioMixProvider` on the queue that serialises player access.
  func wireToPlaybackEngine(_ engine: PlaybackEngine) {
    stateLock.lock()
    self.playbackEngine = engine
    let queue = mixQueue
    let isCurrent = mixQueueIsCurrent
    stateLock.unlock()
    // `setAudioMixProvider` refreshes every item's mix, which walks the
    // player, so it takes the same hop `detachFromEngine` does.
    if isCurrent() {
      engine.setAudioMixProvider(self)
    } else {
      queue.async {
        engine.setAudioMixProvider(self)
      }
    }
  }

  /// Detach the provider from any previously-wired engine. Hops to
  /// the mix dispatch target if needed — AVFoundation expects
  /// `setAudioMixProvider` on the queue that serialises player access.
  func detachFromEngine() {
    stateLock.lock()
    let engine = self.playbackEngine
    self.playbackEngine = nil
    let queue = mixQueue
    let isCurrent = mixQueueIsCurrent
    stateLock.unlock()
    guard let engine = engine else { return }
    if isCurrent() {
      engine.setAudioMixProvider(nil)
    } else {
      queue.async {
        engine.setAudioMixProvider(nil)
      }
    }
  }

  /// Ask the active engine to re-evaluate every item's audioMix. A
  /// consumer calls this when its `wantsTap` would change (EQ enable
  /// toggle, first visualizer subscriber arriving, last subscriber
  /// dropping). Hops to the mix dispatch target; AVFoundation expects
  /// `AVPlayer.items()` + `AVPlayerItem.audioMix` on the queue that
  /// serialises player access.
  func refreshActiveItemMixes() {
    stateLock.lock()
    let engine = self.playbackEngine
    let queue = mixQueue
    let isCurrent = mixQueueIsCurrent
    stateLock.unlock()
    guard let engine = engine else { return }
    if isCurrent() {
      engine.refreshAllItemMixes()
    } else {
      queue.async {
        engine.refreshAllItemMixes()
      }
    }
  }

  // MARK: - Consumer registration

  /// Broadcast which asset is actually active, without touching audio mixes.
  ///
  /// `audioMix(for:)` announces its argument, which is right when the engine
  /// is attaching the item about to play and wrong when something walks the
  /// queue: the last item walked would be left as the active asset. Callers
  /// that refresh several items re-assert the playing one through here.
  func noteActiveAsset(_ asset: AVAsset?) {
    stateLock.lock()
    let consumerSnapshot = consumers
    stateLock.unlock()
    for consumer in consumerSnapshot {
      consumer.onActiveAssetChange(asset)
    }
  }

  /// Register [consumer] for post-EQ audio frames and active-asset
  /// notifications. The provider holds the consumer strongly until
  /// `unregister` runs. Order of registration does not matter — both callbacks
  /// fire under the same snapshot iteration.
  func register(_ consumer: Consumer) {
    stateLock.lock()
    consumers.append(consumer)
    stateLock.unlock()
  }

  /// Drop [consumer] from the provider's fan-out list. Identity is
  /// the consumer reference. Safe to call from any thread.
  func unregister(_ consumer: Consumer) {
    stateLock.lock()
    consumers.removeAll { $0 === consumer }
    stateLock.unlock()
  }

  /// True when at least one registered consumer's `wantsTap` returns
  /// true. Snapshots the consumer list under the lock and evaluates
  /// each predicate outside it so a misbehaving predicate cannot
  /// block render-thread fan-out.
  func anyConsumerWantsTap() -> Bool {
    stateLock.lock()
    let snapshot = consumers
    stateLock.unlock()
    for consumer in snapshot {
      if consumer.wantsTap() { return true }
    }
    return false
  }

  // MARK: - EQ master gain state

  /// Lock-protected snapshot of the enabled flag. Called on the mix
  /// dispatch target on every `audioMix(for:)` invocation.
  func isEnabledSnapshot() -> Bool {
    stateLock.lock()
    let value = enabled
    stateLock.unlock()
    return value
  }

  /// Lock-protected snapshot of the `visualizationEnabled` flag.
  /// Read by the visualizer Hybrid on every `subscribe` and by its
  /// Consumer's `wantsTap` predicate; written by
  /// `TrackPlayer.configure(_:)` on every call.
  func isVisualizationEnabledSnapshot() -> Bool {
    stateLock.lock()
    let value = visualizationEnabled
    stateLock.unlock()
    return value
  }

  /// Lock-protected `visualizationEnabled` write. Called from
  /// `TrackPlayer.configure(_:)`. Idempotent re-write per configure
  /// call so the latest value is always live for subsequent
  /// `subscribe` / `wantsTap` queries.
  func setVisualizationEnabled(_ value: Bool) {
    stateLock.lock()
    visualizationEnabled = value
    stateLock.unlock()
  }

  /// Lock-protected enabled-flag write. The Equalizer Hybrid mirrors
  /// its own enabled state through here; the caller decides whether
  /// to follow up with `refreshActiveItemMixes`.
  func setEnabled(_ value: Bool) {
    stateLock.lock()
    enabled = value
    stateLock.unlock()
  }

  /// Replace the master band-gain state and bump `gainsVersion`.
  /// Active per-item EQProcessor cascades observe the version
  /// advance on their next render-thread callback and recompute
  /// coefficients in place.
  func setGains(_ gains: [Double]) {
    stateLock.lock()
    currentGains = gains
    gainsVersion &+= 1
    stateLock.unlock()
  }

  /// Snapshot the master gains + their version + EQ enabled flag
  /// under one lock acquisition. Per-item cascades compare the version
  /// against their last-seen value to decide whether to recompute
  /// coefficients; the enabled flag drives the pass-through gate.
  /// Single locked read keeps the render-thread cost to one critical
  /// section per buffer batch.
  func gainsSnapshot() -> (version: UInt64, gains: [Double], enabled: Bool) {
    stateLock.lock()
    let snapshot = (version: gainsVersion, gains: currentGains, enabled: enabled)
    stateLock.unlock()
    return snapshot
  }

  /// Diagnostic + provider-internal read of the current gains.
  func getGains() -> [Double] {
    return gainsSnapshot().gains
  }

  // MARK: - ReplayGain mode + per-item recompute

  /// Snapshot the current ReplayGain mode. Read by the TrackPlayer
  /// Hybrid's `getReplayGainMode`.
  func getReplayGainMode() -> ReplayGainMode {
    stateLock.lock()
    let mode = replayGainMode
    stateLock.unlock()
    return mode
  }

  /// Update the ReplayGain mode and recompute gain on every active
  /// item's processor from its cached `ReplayGainData`. Items whose
  /// extraction is still pending pick up the new mode automatically
  /// when their extract completes — the completion handler runs the
  /// same locked recompute helper, which always reads the live mode
  /// inside the critical section.
  ///
  /// Also asks the engine to re-evaluate per-item mixes: the
  /// internal RG Consumer's `wantsTap` predicate flips whenever the
  /// mode crosses the off / non-off boundary, so a pre-flip item
  /// that skipped tap install (no other consumer wanted it) needs
  /// a fresh `audioMix(for:)` walk to pick up the tap. Mirrors the
  /// `refreshActiveItemMixes` path EQ + Visualizer use when their
  /// own `wantsTap` flips.
  func setReplayGainMode(_ mode: ReplayGainMode) {
    stateLock.lock()
    replayGainMode = mode
    let states = activeItemStates.allObjects
    stateLock.unlock()
    for state in states {
      recomputeWithLock(state: state)
    }
    refreshActiveItemMixes()
  }

  /// Recompute [state]'s linear gain from its cached `ReplayGainData`
  /// under the live mode and push to its processor. `state.lock`
  /// (inside `state.snapshotData()`) and `processor.lock` (inside
  /// `processor.setLinearGain`) are each held briefly, sequentially —
  /// never simultaneously, never with `stateLock` co-held with
  /// `state.lock`. `stateLock` brackets the live-mode read + gain
  /// push so a concurrent `setReplayGainMode` cannot interleave
  /// between read + push and leave the processor at a stale gain.
  private func recomputeWithLock(state: ItemRGState) {
    let data = state.snapshotData()
    stateLock.lock()
    let selectorMode = Self.selectorMode(from: replayGainMode)
    let linearGain = Self.computeLinearGain(data: data, mode: selectorMode)
    state.processor.setLinearGain(linearGain)
    stateLock.unlock()
  }

  /// Pure gain math per NOTES.md section 25. Selects the `(gainDb, peak)`
  /// pair from [data] under [mode] (strict, no fallback), then caps
  /// the result for clip safety:
  ///
  /// ```
  /// cap = usable peak ? 1.0 / peak : 1.0
  /// linearGain = min(pow(10, gainDb / 20), cap)
  /// ```
  ///
  /// A peak is usable inside the `0.001 ... 2.0` band that
  /// `ReplayGainExtractor.acceptPeak` holds — full scale (`1.0`) and
  /// true peaks above it included. With no usable peak the cap sits at
  /// unity, so a boost is suppressed rather than applied unprotected: a
  /// peak cannot be derived from a gain. Attenuation is untouched
  /// either way, since a negative gain can never clip.
  ///
  /// Returns 1.0 (unity) whenever the selected mode's gain tag is
  /// absent — RG is inactive for that track.
  ///
  /// Takes the pure-Swift `ReplayGainSelectorMode` rather than the
  /// Nitrogen-generated `ReplayGainMode` so the math compiles off
  /// the Swift↔C++ interop graph (NOTES.md section 6); production callers
  /// translate at the call site via `selectorMode(from:)`.
  static func computeLinearGain(data: ReplayGainData?, mode: ReplayGainSelectorMode) -> Float {
    let (gainDb, peak) = data?.selectGain(mode: mode) ?? (nil, nil)
    guard let gainDb = gainDb else { return 1.0 }
    let raw = Float(pow(10.0, gainDb / 20.0))
    var cap: Float = 1.0
    if let peak = peak, peak >= 0.001, peak <= 2.0 {
      cap = Float(1.0 / peak)
    }
    return min(raw, cap)
  }

  /// Map the Nitrogen-generated `ReplayGainMode` to the pure-Swift
  /// `ReplayGainSelectorMode` consumed by `computeLinearGain` and
  /// `ReplayGainData.selectGain`.
  static func selectorMode(from mode: ReplayGainMode) -> ReplayGainSelectorMode {
    switch mode {
    case .off: return .off
    case .track: return .track
    case .album: return .album
    }
  }

  /// True when [state]'s cached `ReplayGainData` carries a tag for
  /// the active mode (i.e. the gain push would land at non-unity).
  /// Surfaced through `replayGainActiveForItem(_:)` so the
  /// NowPlayingFormat path can populate the `replayGainActive` field
  /// without recomputing the linear gain itself.
  func replayGainActiveForItem(_ item: AVPlayerItem) -> Bool {
    stateLock.lock()
    let mode = replayGainMode
    stateLock.unlock()
    guard let state = item.replayGainState else { return false }
    let data = state.snapshotData()
    let (gainDb, _) = data?.selectGain(mode: Self.selectorMode(from: mode)) ?? (nil, nil)
    return gainDb != nil
  }

  /// Snapshot [item]'s cached `ReplayGainData`. Returns `nil` when
  /// the item carries no RG state yet (HLS / track-less item that
  /// bypassed the tap, or extract not yet complete + no-tag track).
  func replayGainDataForItem(_ item: AVPlayerItem) -> ReplayGainData? {
    item.replayGainState?.snapshotData()
  }

  /// Attach per-item ReplayGain state and arm the read of the item's own
  /// metadata. Called from `audioMix(for:)` BEFORE the tap-mix bypass
  /// conditions so RG values populate independently of AVFoundation's
  /// track-loading lifecycle. Re-invocation on the same item is a no-op.
  ///
  /// Issues no network read of its own. `AssetReplayGainReader` reads the
  /// metadata AVPlayer has already prepared, which costs zero requests and
  /// zero bytes — so the read is bounded by AVPlayer's own preroll horizon
  /// (measured: exactly one prepared item out of eight enqueued) rather than
  /// by how many items the engine has queued.
  @discardableResult
  func attachReplayGainState(to item: AVPlayerItem) -> ItemRGState {
    if let existing = item.replayGainState { return existing }
    let rgState = ItemRGState(processor: ReplayGainProcessor(), item: item)
    item.replayGainState = rgState
    stateLock.lock()
    activeItemStates.add(rgState)
    stateLock.unlock()

    // `.new` only, never `.initial` (NOTES.md section 18), paired with the explicit
    // synchronous bootstrap below.
    rgState.statusObservation = item.observe(\.status, options: [.new]) {
      [weak self, weak rgState] observed, _ in
      guard let self = self, let rgState = rgState else { return }
      self.readReplayGainWhenPrepared(state: rgState, item: observed)
    }
    // Explicit synchronous bootstrap, because an observer waiting for a
    // transition that has already happened never fires. `audioMix(for:)`
    // reaches this method from `refreshActiveItemMixes()` as well as from
    // queue construction — on every ReplayGain mode crossing of the off /
    // non-off boundary, and on crossfade cross-attach — and those calls can
    // carry an item that is already `.readyToPlay`. Whether a given release's
    // engine wiring produces such a first attach is not something this method
    // can know, which is exactly why the bootstrap is unconditional.
    readReplayGainWhenPrepared(state: rgState, item: item)
    return rgState
  }

  /// Resolve [item]'s ReplayGain once it is prepared, and deliver it into
  /// [state]. No-op until `.readyToPlay`, and at most once per state — a
  /// prepared item's metadata does not change under it, and both the observer
  /// and the bootstrap can reach a ready item.
  ///
  /// The consumer's claim is stamped on the item at queue-build time, and a
  /// claimed item is never read: the merge discards detection in full, so
  /// reading the file's tags could only produce a value to throw away. That
  /// also means the merge happens before the first value is delivered rather
  /// than after — a listener never sees a detected value corrected to a
  /// supplied one.
  ///
  /// Delivery is unconditional on the result: a resolve that found nothing
  /// still lands, so the now-playing snapshot refreshes and reports the honest
  /// absence rather than staying at whatever the pre-read snapshot said.
  private func readReplayGainWhenPrepared(state: ItemRGState, item: AVPlayerItem) {
    guard item.status == .readyToPlay else { return }
    guard state.beginReplayGainRead() else { return }
    let supplied = item.suppliedReplayGain
    Task { [weak self] in
      let detected = supplied == nil ? await AssetReplayGainReader.read(item: item) : nil
      guard let self = self else { return }
      let data = ReplayGainData.merge(
        suppliedClaimed: supplied != nil, supplied: supplied?.data, detected: detected
      )
      state.setData(data)
      self.recomputeWithLock(state: state)
      // Required, not optional: this is what refreshes the emitted now-playing
      // snapshot when the read lands. Without it `onNowPlayingFormatChange`
      // reports null ReplayGain forever, while `getNowPlayingFormat()` stays
      // correct because it reads live — so a polling test would not notice.
      //
      // Hopped onto the mix dispatch target rather than fired inline: the
      // bootstrap path runs inside `audioMix(for:)`, which the engine calls
      // mid-`setItems` between `canInsert` and `insert`; the notification
      // reaches JS, and a listener mutating the queue there would mutate
      // the queue the engine is building. The callback is snapshotted here
      // rather than re-read on the target queue so the hop captures a
      // value, not the non-Sendable provider.
      // Both under the lock that guards them: this runs on the cooperative
      // pool, and `mixQueue` is rewritten by `setMixDispatchTarget` and reset
      // by teardown. Reading it unguarded can dispatch player-state work onto
      // a queue that is no longer the one serialising it.
      let (notify, queue) = stateLock.withLock { (self.replayGainPopulatedCallback, self.mixQueue) }
      // The consumer reads the active item and writes the last-emitted format,
      // which is player state — so this lands on the queue that serialises it,
      // not on main.
      queue.async { notify?(item) }
    }
  }

  // MARK: - Render-thread frame fan-out

  /// Render-thread entry point invoked by `EQProcessor.tapProcessClosure`
  /// once per render batch with a post-EQ mono mix. Snapshots the
  /// consumer list under the lock and dispatches outside it so a
  /// long-running FFT cannot block JS-side registration.
  @inline(__always)
  func deliverAudioFrame(
    output: UnsafePointer<Float>,
    frameCount: Int,
    sampleRate: Double
  ) {
    stateLock.lock()
    let snapshot = consumers
    stateLock.unlock()
    for consumer in snapshot {
      consumer.onAudioFrame(output, frameCount, sampleRate)
    }
  }

  // MARK: - AudioMixProvider

  /// Builds the per-`AVPlayerItem` `AVAudioMix` that carries the EQ
  /// tap + ReplayGain pre-gain stage. Called on the mix dispatch
  /// target from the engine's item construction path on every fresh
  /// item. The body
  /// briefly holds `stateLock` three times — once to snapshot the
  /// consumer list for the asset-change fan-out, once inside
  /// `anyConsumerWantsTap` to read the consumer snapshot, once to
  /// register the per-item ReplayGain state. All three critical
  /// sections are constant-time (consumer count is O(2): EQ +
  /// Visualizer) and never call out to JS while the lock is held,
  /// so the cost on the calling queue is sub-millisecond on a
  /// typical consumer config.
  func audioMix(for item: AVPlayerItem) -> AVAudioMix? {
    let asset = item.asset
    // Notify every registered consumer of the candidate asset before
    // the bypass checks. Visualizer uses this to fire `hls-source`
    // synchronously at subscribe time.
    stateLock.lock()
    let consumerSnapshot = consumers
    stateLock.unlock()
    for consumer in consumerSnapshot {
      consumer.onActiveAssetChange(asset)
    }

    // Eagerly attach RG state + arm the metadata read for this item,
    // regardless of the tap-mix bypass conditions. The read waits on
    // `.readyToPlay`, which is a different and later gate than this
    // method's tracks-loaded / format-descriptions / ASBD checks, so
    // attaching here rather than below them means a mode change or a
    // mix refresh can never leave an item without RG state.
    attachReplayGainState(to: item)

    if !anyConsumerWantsTap() { return nil }

    if EQTap.isHLS(asset) { return nil }

    // Tracks-loaded gate: `status(of:)` reports the tracks key's
    // current load state without loading it, and the mix has no
    // `AVAssetTrack` to key its input parameters to until that key
    // is loaded. Engine queue-mutation paths invoke this method on
    // the mix dispatch target for fresh items whose remote tracks
    // haven't loaded yet; returning nil rather than waiting on the load
    // keeps the JS bridge free. Once tracks load, the engine fires
    // `engineActiveItemFormatChanged` and the TrackPlayer-level
    // handler re-fires `refreshActiveItemMixes`, which re-evaluates
    // every queued item's mix off the now-loaded tracks key.
    guard case .loaded(let tracks) = asset.status(of: .tracks) else { return nil }

    // An audio track is what the mix keys its input parameters to. The
    // cascade's sample rate and channel count come from the tap's prepare
    // callback instead of the track's advertised format: the tap reports the
    // format it will actually deliver, which is what the DSP has to match,
    // and asking the track means reading a key nothing in the playback
    // lifecycle ever loads.
    guard tracks.contains(where: { $0.mediaType == .audio }) else { return nil }

    let processor = EQProcessor(provider: self)
    // The processor's first tap callback compares its initial
    // version (0) against the provider's current version and pulls
    // the gains under the lock — avoids a double-recompute on item
    // construction.

    // The audioMix entry point already attached the per-item rgState; reuse it
    // so the tap composition shares the same processor whose gain
    // `recomputeWithLock` updates as the mode flips.
    // Both creation sites route through one function so they cannot diverge:
    // this one is shadowed today only because the other always runs first.
    let rgState = attachReplayGainState(to: item)

    let eqClosure = processor.tapProcessClosure()
    let rgProcessor = rgState.processor
    let composedClosure: EQTap.ProcessFn = { input, output, frames, channels, bufferIndex, liveCount in
      // Tap delivers `input == output` (same pointer); RG mutates the
      // shared buffer in place, EQ then reads the post-RG samples.
      rgProcessor.process(buffer: input, frames: frames)
      eqClosure(input, output, frames, channels, bufferIndex, liveCount)
    }

    return EQTap.audioMix(
      for: item,
      process: composedClosure,
      // Configure the cascade and size the mono-mix scratch buffer on the
      // tap's prepare callback, which runs once ahead of the process loop, so
      // the render thread never allocates and never runs against a rate or
      // channel count the tap is not actually delivering.
      prepare: { frames, format in
        processor.setSampleRate(format.mSampleRate)
        processor.setChannelCount(Int(format.mChannelsPerFrame))
        processor.tapPrepare(maxFrames: frames)
      }
    )
  }

}

// MARK: - Per-item ReplayGain state

/// Pairs a `ReplayGainProcessor` with the cached `ReplayGainData`
/// extracted from its item's asset metadata. Owned by the
/// `AVPlayerItem` it's attached to (associated-object) so its
/// lifetime tracks the item.
final class ItemRGState {

  let processor: ReplayGainProcessor
  /// The state is an associated object OF the item, so a strong reference here
  /// would be a cycle.
  weak var item: AVPlayerItem?
  /// `\.status` observation on the owning item, installed by
  /// `AudioTapProvider.attachReplayGainState`. Held here so its lifetime
  /// matches the item's — the item owns this state, so the observation dies
  /// with the thing it observes.
  var statusObservation: NSKeyValueObservation?
  private let lock = NSLock()
  private var data: ReplayGainData?
  private var replayGainReadStarted = false

  init(processor: ReplayGainProcessor, item: AVPlayerItem? = nil) {
    self.processor = processor
    self.item = item
  }

  deinit {
    statusObservation?.invalidate()
  }

  /// Claim the one metadata read this state gets. Returns `true` to exactly
  /// one caller; the `\.status` observer and the synchronous bootstrap both
  /// reach a prepared item, and `refreshActiveItemMixes()` can re-run the
  /// bootstrap, so the claim has to be atomic rather than a plain flag check.
  func beginReplayGainRead() -> Bool {
    lock.lock()
    defer { lock.unlock() }
    if replayGainReadStarted { return false }
    replayGainReadStarted = true
    return true
  }

  /// Update the cached metadata. The render-thread gain push happens
  /// in `AudioTapProvider.recomputeWithLock(state:)` after this call.
  func setData(_ value: ReplayGainData?) {
    lock.lock()
    data = value
    lock.unlock()
  }

  /// Snapshot the cached data under the lock. Returns `nil` if the
  /// extractor produced no usable tags or the extract is still in
  /// flight.
  func snapshotData() -> ReplayGainData? {
    lock.lock()
    let value = data
    lock.unlock()
    return value
  }
}

// Associated-object key for `AVPlayerItem.replayGainState`. Distinct
// pointer per Cocoa convention (the address itself is the key — the
// content is unused, but the variable must outlive the items).
private var rgStateAssociatedObjectKey: UInt8 = 0

extension AVPlayerItem {
  /// The `ItemRGState` attached to this item by `AudioTapProvider`,
  /// or `nil` for items the provider hasn't seen (e.g. items
  /// constructed outside the tap path).
  var replayGainState: ItemRGState? {
    get {
      objc_getAssociatedObject(self, &rgStateAssociatedObjectKey) as? ItemRGState
    }
    set {
      objc_setAssociatedObject(
        self,
        &rgStateAssociatedObjectKey,
        newValue,
        .OBJC_ASSOCIATION_RETAIN_NONATOMIC
      )
    }
  }
}
