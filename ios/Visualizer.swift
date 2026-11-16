import AVFoundation
import Foundation
import NitroModules

/// FFT extractor HybridObject. Each `subscribe` call creates a
/// per-subscription [FFTProcessor]; the shared [AudioTapProvider]
/// forwards every post-EQ-tap audio buffer into `deliverAudioFrame`,
/// which fans out to every active subscriber.
///
/// Threading: subscribe / dispose run on the JS bridge thread;
/// `deliverAudioFrame` runs on the audio render thread under
/// [subsLock] for the snapshot, then ingests outside the lock. The
/// per-frame hot path is allocation-free until the FFT emit, which
/// hops to main and builds a Nitro ArrayBuffer.
final class Visualizer: HybridVisualizerSpec {

  private static let currentLock = NSLock()
  /// The most recently constructed Visualizer instance. Held for
  /// JS-reload teardown parity with Android — a fresh construction
  /// tears the prior instance's subscriptions + consumer
  /// registration down so its processors don't keep dispatching into
  /// a torn-down JS context.
  ///
  /// Acquire `currentLock` when reading or writing [current] —
  /// `init()` runs on the JS bridge while a prior instance may be
  /// mid-dispatch on the render thread.
  private static var _current: Visualizer?
  static var current: Visualizer? {
    get {
      currentLock.lock(); defer { currentLock.unlock() }
      return _current
    }
    set {
      currentLock.lock(); defer { currentLock.unlock() }
      _current = newValue
    }
  }

  /// One subscription record. The processor is held strongly here;
  /// `deliverAudioFrame` snapshots the subscription array under the
  /// lock and iterates the snapshot — that snapshot retains every
  /// processor for the duration of the ingest call, so a JS-side
  /// disposer running concurrently cannot deinit a processor while
  /// it's mid-ingest.
  private struct Subscription {
    let id: Int
    let processor: FFTProcessor
    let onError: (VisualizerErrorReason) -> Void
    // Set by the disposer (JS-bridge thread); read by emit blocks already
    // queued on main, so a frame marshaled before dispose is dropped instead
    // of firing onFrame after the subscription is gone.
    let disposed: DisposedFlag
  }

  /// Thread-safe "disposed" flag shared by a subscription and the emit blocks
  /// it has already enqueued onto the main queue. `mark()` sets it; nothing
  /// clears it.
  private final class DisposedFlag {
    private let lock = NSLock()
    private var value = false
    func mark() { lock.lock(); value = true; lock.unlock() }
    var isSet: Bool { lock.lock(); defer { lock.unlock() }; return value }
  }

  private let subsLock = NSLock()
  private var subscriptions: [Subscription] = []
  private var nextId: Int = 1

  private let assetLock = NSLock()
  /// Snapshot of the active asset — set from the audio-tap provider's
  /// `audioMix(for:)` path so `subscribe` can report `hls-source` for
  /// HLS streams without poking the TrackPlayer Hybrid. Held under its
  /// own lock so the render-thread-hot `subsLock` covers only the
  /// subscription array; main-thread asset writes never block render.
  private var activeAsset: AVAsset?

  private let provider: AudioTapProvider
  private var consumer: AudioTapProvider.Consumer?

  init(provider: AudioTapProvider = .shared) {
    self.provider = provider
    super.init()
    // Mirror the Android pattern: a new construction tears prior
    // wiring down so the dead instance's processors don't keep
    // dispatching into a torn-down JS context.
    if let prior = Visualizer.current {
      prior.tearDownOnRefresh()
    }
    Visualizer.current = self
    registerConsumer()
  }

  /// Internal helper for the construction-time replace path. Drops
  /// every subscription and unregisters the prior consumer from the
  /// shared provider so its predicates stop influencing tap install.
  private func tearDownOnRefresh() {
    subsLock.lock()
    subscriptions.removeAll()
    subsLock.unlock()
    if let consumer = self.consumer {
      provider.unregister(consumer)
      self.consumer = nil
    }
  }

  private func registerConsumer() {
    // Capture `self` weakly so the provider's consumer registration
    // doesn't extend the Visualizer Hybrid's lifetime past JS-side
    // teardown. The render-thread `deliverAudioFrame` path is
    // allocation-free aside from the weak-deref check.
    //
    // `provider` is captured strongly via a local: it's `AudioTapProvider.shared`
    // (singleton) in production, or a test-supplied long-lived instance
    // under unit tests. Either way it outlives any individual Visualizer
    // Hybrid; weak-capturing only adds a deref hop.
    let provider = self.provider
    let consumer = AudioTapProvider.Consumer(
      // Tap install demand: gated on at least one active subscriber
      // AND the `visualizationEnabled` flag from PlayerConfig. When
      // the consumer app configures `visualizationEnabled: false`,
      // this predicate is unconditionally false even if a subscriber
      // somehow exists — `audioMix(for:)`'s `anyConsumerWantsTap()`
      // short-circuit then skips tap construction unless EQ is on.
      wantsTap: { [weak self] in
        guard provider.isVisualizationEnabledSnapshot() else { return false }
        return self?.hasActiveSubscribersInstance() ?? false
      },
      onAudioFrame: { [weak self] output, frameCount, sampleRate in
        self?.deliverAudioFrame(output: output, frameCount: frameCount, sampleRate: sampleRate)
      },
      onActiveAssetChange: { [weak self] asset in
        guard let self = self else { return }
        self.assetLock.lock()
        self.activeAsset = asset
        self.assetLock.unlock()
      }
    )
    self.consumer = consumer
    provider.register(consumer)
  }

  func subscribe(
    config: VisualizerConfig,
    onFrame: @escaping (_ frame: VisualizerFrame) -> Void,
    onError: @escaping (_ reason: VisualizerErrorReason) -> Void
  ) throws -> () -> Void {
    // Pin-at-configure visualization-disabled gate. The flag is
    // written by `TrackPlayer.configure(_:)` from
    // `PlayerConfig.visualizationEnabled`. When false, fire onError
    // on the next main-thread tick (matches the `hls-source` shape
    // below) and return a no-op dispose. The subscriber is not added
    // to `subscriptions`, so `getSubscriberCount()` stays accurate.
    if !provider.isVisualizationEnabledSnapshot() {
      DispatchQueue.main.async {
        onError(.visualizationDisabled)
      }
      return { /* no-op dispose */ }
    }
    let fftSize = Int(config.fftSize)
    let intervalMs = Int(config.updateIntervalMs)
    let includeSamples = config.includeSamples ?? false

    // Set by the disposer; read inside the emit block so a frame already
    // queued on main at dispose time is dropped instead of crossing the JS
    // bridge after the subscription is gone.
    let disposed = DisposedFlag()

    let processor = try FFTProcessor(
      fftSize: fftSize,
      intervalMs: intervalMs,
      includeSamples: includeSamples,
    ) { fftBuffer, samplesBuffer, sampleRate in
      // FFTProcessor builds the ArrayBuffers on the audio render
      // thread (single memcpy each). We hop to main to call the
      // JS-bridge callback so a slow JS handler doesn't block the
      // next render-thread emit. The processor's own intervalMs
      // throttle (default 16ms) caps the emit rate, which puts a
      // natural ceiling on main-queue accumulation.
      DispatchQueue.main.async {
        // Drop frames queued before dispose — the disposer sets this flag
        // before removing the subscription, so anything already on the main
        // queue short-circuits here rather than firing onFrame post-dispose.
        if disposed.isSet { return }
        let frame = VisualizerFrame(
          fft: fftBuffer,
          samples: samplesBuffer,
          sampleRate: sampleRate,
        )
        onFrame(frame)
      }
    }

    subsLock.lock()
    let wasEmpty = subscriptions.isEmpty
    let subId = nextId
    nextId += 1
    let sub = Subscription(id: subId, processor: processor, onError: onError, disposed: disposed)
    subscriptions.append(sub)
    subsLock.unlock()

    assetLock.lock()
    let asset = activeAsset
    assetLock.unlock()

    // First subscriber arriving — the active AVPlayerItem may have
    // `audioMix == nil` from when the provider was queried before
    // this subscriber existed. Ask the provider to refresh the active
    // item's audio mix so the tap installs and frames start flowing
    // for the currently-playing track.
    if wasEmpty {
      provider.refreshActiveItemMixes()
    }

    // Synchronous HLS detection — fire onError on main if the
    // currently-known asset is HLS. The hook will not deliver
    // frames either way (EQTap.audioMix returns nil for HLS), so
    // surfacing the reason is the only feedback path.
    if let asset = asset, EQTap.isHLS(asset) {
      DispatchQueue.main.async {
        onError(.hlsSource)
      }
    }

    return { [weak self] in
      // Mark disposed BEFORE removing so any emit block already queued on main
      // short-circuits instead of firing onFrame post-dispose. Runs even if
      // `self` is gone — the queued blocks hold `disposed` directly.
      disposed.mark()
      guard let self = self else { return }
      self.subsLock.lock()
      self.subscriptions.removeAll { $0.id == subId }
      self.subsLock.unlock()
    }
  }

  func getSubscriberCount() throws -> Double {
    subsLock.lock()
    let count = subscriptions.count
    subsLock.unlock()
    return Double(count)
  }

  // MARK: - Render-thread fan-out

  /// True when at least one subscriber is active. Used by the
  /// AudioTapProvider's consumer predicate to decide whether to
  /// install a tap (running EQ in pass-through mode if needed) even
  /// when the EQ itself is disabled.
  private func hasActiveSubscribersInstance() -> Bool {
    subsLock.lock()
    let active = !subscriptions.isEmpty
    subsLock.unlock()
    return active
  }

  /// Render-thread entry point. Called once per render batch with a
  /// `(L+R+...+N)/N` mono mixdown of the post-EQ samples; the caller
  /// (`AudioTapProvider.deliverAudioFrame`) collapses the deinterleaved
  /// buffers across channels before invoking.
  func deliverAudioFrame(
    output: UnsafePointer<Float>,
    frameCount: Int,
    sampleRate: Double
  ) {
    // Snapshot the subscription list under the lock; ingest fires
    // outside the lock so a long-running FFT doesn't block JS-side
    // subscribe/dispose calls.
    subsLock.lock()
    let snapshot = subscriptions
    subsLock.unlock()
    for sub in snapshot {
      sub.processor.ingest(samples: output, frameCount: frameCount, sampleRate: sampleRate)
    }
  }
}
