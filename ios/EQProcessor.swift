import Accelerate
import Foundation

/// 10-band biquad cascade backed by `vDSP_biquad`.
///
/// One cascade per audio channel runs the 10 RBJ peaking biquads in
/// series. State is per-channel: `vDSP` keeps delay lines (2 floats
/// per section + 2 padding) for IIR continuity across calls. The
/// process closure that `EQTap` invokes per buffer routes through
/// `process(input:output:frames:channel:)`.
///
/// Threading + lifecycle contract: configuration methods
/// (`setBandGains`, `setSampleRate`, `setChannelCount`) recreate the
/// underlying vDSP setup. They MUST NOT be invoked while the
/// process callback is firing — the caller (typically the
/// equalizer's playback-engine attach path) is responsible for
/// detaching the tap before reconfiguring. The cooperative-detach
/// pattern matches the engine.detach lifecycle on Android. A
/// lock-free hot-swap is a future optimisation.
///
/// `process` runs on the audio render thread. Internal state is
/// designed to keep the render path allocation-free under steady
/// state — every allocation lives behind a configuration method.
final class EQProcessor {

  static let bandCount = 10
  // Each biquad section needs 2 floats of state plus 2 padding floats
  // at each end of the per-channel delay array per the vDSP_biquad
  // contract.
  private static let delaysPerChannel = 2 * bandCount + 2

  /// Per-section coefficient layout the vDSP cascade expects:
  /// `[b0, b1, b2, a1, a2]` per band, concatenated, all Float.
  private static let coefficientsLength = 5 * bandCount

  /// Active sample rate. Set before `process` runs; default 44.1k
  /// keeps the engine usable without a configure step.
  private var sampleRate: Double = 44_100

  /// Active channel count. `setChannelCount` allocates per-channel
  /// state lazily so the render thread doesn't do work on first
  /// process.
  private var channelCount: Int = 0

  /// Cached gain (dB) per band. Used to recompute coefficients on
  /// sample-rate change; the canonical state lives here.
  private var gainsDb: [Double] = Array(repeating: 0, count: EQProcessor.bandCount)

  /// vDSP biquad setup. Recreated on coefficient or sample-rate
  /// change; the render thread reads through `currentSetup`.
  private var setup: vDSP_biquad_Setup?

  /// Per-channel delay array — `vDSP_biquad` writes/reads here in
  /// place each call. Length is `delaysPerChannel * channelCount`.
  private var delays: [Float] = []

  /// Pulled from on every render-thread `process` call to detect
  /// gain mutations and recompute coefficients in place. Held weak
  /// because the shared `AudioTapProvider` is module-internal — JS
  /// teardown shouldn't extend its lifetime via a tap captured on a
  /// stale AVPlayerItem.
  private weak var provider: AudioTapProvider?

  /// Last observed gains version from the provider; the tap
  /// recomputes coefficients only when the provider's version
  /// advances, so steady-state playback (no slider drag) hits the
  /// fast path of "version compare under lock + early return".
  private var lastSeenGainsVersion: UInt64 = 0

  /// Scratch buffer for the post-EQ mono mixdown (`(L+R+...+N)/N`)
  /// fed to the visualizer. Allocated once by `tapPrepare` (fired
  /// from `MTAudioProcessingTapPrepareCallback`) at the tap's
  /// `maxFrames` budget; the render-thread process path never
  /// grows this buffer.
  private var monoMixPtr: UnsafeMutablePointer<Float>?
  private var monoMixCapacity: Int = 0

  /// True once `setup` is non-nil and ready to process.
  var isReady: Bool { setup != nil && channelCount > 0 }

  init(provider: AudioTapProvider? = nil) {
    self.provider = provider
  }

  deinit {
    if let setup = setup {
      vDSP_biquad_DestroySetup(setup)
    }
    if let ptr = monoMixPtr {
      ptr.deinitialize(count: monoMixCapacity)
      ptr.deallocate()
    }
  }

  /// Ensure the mono-mix scratch buffer can hold at least
  /// `maxFrames` samples. Caller-side: invoked when the tap's
  /// processing format is known (via `MTAudioProcessingTap`'s
  /// prepare callback) so allocation never lands on the render
  /// thread in steady state. A subsequent call with the same or
  /// smaller `maxFrames` is a no-op.
  func tapPrepare(maxFrames: Int) {
    if maxFrames <= monoMixCapacity { return }
    if let prior = monoMixPtr {
      prior.deinitialize(count: monoMixCapacity)
      prior.deallocate()
    }
    let ptr = UnsafeMutablePointer<Float>.allocate(capacity: maxFrames)
    ptr.initialize(repeating: 0, count: maxFrames)
    monoMixPtr = ptr
    monoMixCapacity = maxFrames
  }

  /// Set the sample rate the cascade should target. Must be called
  /// before `process`. Calling with an unchanged value is a no-op
  /// to avoid coefficient churn during the same playback session.
  func setSampleRate(_ hz: Double) {
    if hz <= 0 || hz == sampleRate { return }
    sampleRate = hz
    recomputeCoefficients()
  }

  /// Resize per-channel state. Calling with the same count is a
  /// no-op. Existing delay lines are dropped on resize — a
  /// channel-count change is rare (engine swap, source change)
  /// and a half-second of impulse settling is acceptable.
  /// Triggers a coefficient recompute if gains were configured
  /// before channels arrived.
  func setChannelCount(_ count: Int) {
    if count <= 0 || count == channelCount { return }
    let wasZero = channelCount == 0
    channelCount = count
    delays = Array(
      repeating: 0,
      count: EQProcessor.delaysPerChannel * count
    )
    if wasZero { recomputeCoefficients() }
  }

  /// Replace every band gain. Length-tolerant: extra entries are
  /// ignored, missing entries hold the previous value.
  func setBandGains(_ db: [Double]) {
    let n = min(db.count, EQProcessor.bandCount)
    for i in 0..<n {
      gainsDb[i] = db[i]
    }
    recomputeCoefficients()
  }

  /// Recompute the 50-coefficient block from `gainsDb` and the
  /// current sample rate. Recreates the vDSP setup; the prior
  /// setup is destroyed in the same call so memory is bounded.
  /// Skipped when no channels are configured — the setup would be
  /// half-state and can't be exercised until `setChannelCount`.
  private func recomputeCoefficients() {
    if channelCount <= 0 { return }
    let freqs = EqualizerPresets.frequencies
    var coeffs = [Double](repeating: 0, count: EQProcessor.coefficientsLength)
    for i in 0..<EQProcessor.bandCount {
      let block = BiquadCoefficients.peaking(
        centerFreqHz: freqs[i],
        q: 1.0,
        gainDb: gainsDb[i],
        sampleRateHz: sampleRate
      )
      let base = i * 5
      coeffs[base] = block.b0
      coeffs[base + 1] = block.b1
      coeffs[base + 2] = block.b2
      coeffs[base + 3] = block.a1
      coeffs[base + 4] = block.a2
    }
    if let prior = setup {
      vDSP_biquad_DestroySetup(prior)
      setup = nil
    }
    coeffs.withUnsafeBufferPointer { dPtr in
      setup = vDSP_biquad_CreateSetup(dPtr.baseAddress!, vDSP_Length(EQProcessor.bandCount))
    }
  }

  /// Run the cascade in place on `data` for `frames` samples on
  /// `channel` (0..<channelCount). The buffer is single-channel
  /// (deinterleaved float32) — `EQTap` calls this once per
  /// AudioBuffer in the AudioBufferList.
  func process(
    input: UnsafePointer<Float>,
    output: UnsafeMutablePointer<Float>,
    frames: Int,
    channel: Int
  ) {
    guard let setup = setup else {
      // Not ready; pass-through.
      if input != UnsafePointer(output) {
        output.update(from: input, count: frames)
      }
      return
    }
    if channel < 0 || channel >= channelCount { return }
    let delayBase = channel * EQProcessor.delaysPerChannel
    delays.withUnsafeMutableBufferPointer { delayPtr in
      let perChannel = delayPtr.baseAddress!.advanced(by: delayBase)
      vDSP_biquad(
        setup,
        perChannel,
        input,
        1,
        output,
        1,
        vDSP_Length(frames)
      )
    }
  }

  /// Adapter closure that boxes the EQ cascade into the EQTap
  /// process boundary. Each AVFoundation deinterleaved buffer
  /// (one channel per buffer) routes through its own per-channel
  /// cascade slot via `bufferIndex`. Stereo and surround sources
  /// process every channel coherently. Pass-through fallback
  /// kicks in when the processor isn't yet configured or the
  /// buffer index exceeds the configured channel count.
  ///
  /// Consumer fan-out happens once per render batch with a
  /// `(L+R+...+N)/N` mono mixdown of the post-EQ buffers. The
  /// closure accumulates each per-channel buffer into
  /// `monoMixPtr`, then on the final channel divides by the
  /// channel count and hands the result to the
  /// `AudioTapProvider.deliverAudioFrame` fan-out. Mono sources
  /// skip the vector-add + divide and forward the single buffer
  /// directly.
  ///
  /// The closure captures `self` STRONGLY: the processor is the
  /// per-item DSP state, owned by the tap via this closure. The
  /// provider does not retain the processor — it is constructed
  /// inline inside `audioMix(for:)` and handed straight to the
  /// closure, so the closure is the sole strong owner. The closure
  /// in turn is retained by the tap's `ProcessHolder` until
  /// AVFoundation calls `tapFinalize`, at which point the holder
  /// releases, the closure releases, and the EQProcessor deinits
  /// (destroying its `vDSP_biquad` setup). A weak capture would
  /// drop the processor before the tap ever fires, leaving every
  /// buffer routing through the pass-through fallback regardless
  /// of band gains — i.e. the EQ would be silently inert.
  func tapProcessClosure() -> EQTap.ProcessFn {
    return { input, output, numberFrames, channels, bufferIndex, liveBufferCount in
      // Pull the latest master gains from the provider on every
      // render call. The version compare is cheap (lock + uint
      // compare); coefficients only recompute on actual mutation.
      // The `setBandGains` path inside the recompute does allocate
      // (vDSP_biquad_DestroySetup + CreateSetup), which is the
      // accepted cost given gain-change frequency tops out at the
      // slider drag rate (~60 Hz).
      var eqEnabled = true
      if let provider = self.provider {
        let snapshot = provider.gainsSnapshot()
        if snapshot.version != self.lastSeenGainsVersion {
          self.setBandGains(snapshot.gains)
          self.lastSeenGainsVersion = snapshot.version
        }
        eqEnabled = snapshot.enabled
      }
      let total = numberFrames * channels
      let passThrough: () -> Void = {
        if input != UnsafePointer(output) {
          output.update(from: input, count: total)
        }
      }
      // EQ disabled → pass-through. The tap may still be installed by
      // another consumer (RG / visualizer); the mono-mix fan-out below
      // continues to deliver the un-EQ'd output to those consumers.
      if eqEnabled, self.isReady, bufferIndex < self.channelCount {
        self.process(input: input, output: output, frames: numberFrames, channel: bufferIndex)
      } else {
        passThrough()
      }

      // Mono-mix accumulator for the consumer fan-out. The tap's
      // prepare callback sized `monoMixPtr` to its `maxFrames`
      // budget — if the pointer is still nil, the prepare path was
      // never wired (tap misconfigured) and we skip the consumer
      // delivery rather than allocate on the render thread.
      // Pass-through frames are likewise skipped: the consumer
      // contract is post-EQ samples, and an unconfigured cascade
      // produces undefined band content.
      guard self.isReady, let mono = self.monoMixPtr,
            let provider = self.provider else { return }

      // Drive the divide-and-deliver gate off the live AudioBufferList's
      // buffer count, not the configured channel count. AVFoundation
      // can deliver fewer buffers than the format declares (e.g.
      // mono buffer on a stereo-format track during a transient),
      // and gating off `channelCount` would leave the mono mix
      // un-delivered.
      let liveCount = max(1, liveBufferCount)
      let outPtr = UnsafePointer(output)
      if liveCount == 1 {
        // Single-channel source: copy the post-EQ buffer straight
        // into the mono scratch and deliver. Skip the vector-add
        // + divide entirely.
        cblas_scopy(Int32(numberFrames), outPtr, 1, mono, 1)
        provider.deliverAudioFrame(
          output: UnsafePointer(mono),
          frameCount: numberFrames,
          sampleRate: self.sampleRate
        )
      } else if bufferIndex == 0 {
        cblas_scopy(Int32(numberFrames), outPtr, 1, mono, 1)
      } else if bufferIndex < liveCount {
        vDSP_vadd(mono, 1, outPtr, 1, mono, 1, vDSP_Length(numberFrames))
        if bufferIndex == liveCount - 1 {
          var divisor = Float(liveCount)
          vDSP_vsdiv(mono, 1, &divisor, mono, 1, vDSP_Length(numberFrames))
          provider.deliverAudioFrame(
            output: UnsafePointer(mono),
            frameCount: numberFrames,
            sampleRate: self.sampleRate
          )
        }
      }
    }
  }
}
