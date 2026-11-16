import AVFoundation
import Foundation
import MediaToolbox

/// `MTAudioProcessingTap` wrapper for the 10-band equalizer.
///
/// Owns the tap creation + lifecycle callbacks and exposes a single
/// per-item entry point: `audioMix(for:)` returns an `AVAudioMix`
/// with the tap installed on the audio track, or `nil` for sources
/// that don't expose a process-able audio track (HLS, track-less
/// assets).
///
/// The process callback runs the caller-supplied closure on every
/// audio buffer. The default closure is `passThrough` (input copied
/// to output unchanged); the equalizer DSP plugs into the same
/// boundary by passing a different closure.
///
/// Threading: the tap callbacks fire on the audio render thread
/// (a real-time priority thread). The default pass-through closure
/// is allocation-free and lock-free. Composed-stage closures
/// (e.g. ReplayGain pre-gain in front of the EQ cascade) may take
/// short-held NSLock critical sections to read freshly-published
/// coefficients; the project accepts that cost in exchange for
/// cross-class consistency with the rest of the audio path
/// (`AudioTapProvider.gainsSnapshot`, `Visualizer.subsLock`).
///
/// AVFoundation delivers samples as deinterleaved float32: each
/// `AudioBuffer` in the `AudioBufferList` carries a single channel
/// with `mNumberChannels == 1`. The process closure's `channels`
/// argument is therefore the per-buffer channel count (always 1
/// in the deinterleaved path); a multi-channel cascade reads from
/// each buffer in turn.
final class EQTap {

  /// Caller-supplied per-item process body. Default pass-through.
  /// Closure receives the input pointer / output pointer / frame
  /// count / per-buffer channel count / buffer index / live buffer
  /// count in the AudioBufferList. AVFoundation delivers samples
  /// deinterleaved (one channel per buffer, `channels == 1`); the
  /// closure routes each buffer through its own per-channel cascade
  /// slot using `bufferIndex`. `liveBufferCount` is the actual
  /// `mNumberBuffers` from the live `AudioBufferList` — visualizer
  /// fan-out gates "deliver the mono mix on the final buffer" off
  /// this value rather than the configured channel count, so a
  /// short-buffer-list delivery still produces a frame.
  typealias ProcessFn = (
    _ input: UnsafeMutablePointer<Float>,
    _ output: UnsafeMutablePointer<Float>,
    _ numberFrames: Int,
    _ channels: Int,
    _ bufferIndex: Int,
    _ liveBufferCount: Int
  ) -> Void

  /// Optional render-thread prepare hook. Fired once per tap from
  /// `MTAudioProcessingTapPrepareCallback` with the tap's `maxFrames`
  /// budget and the format the tap will actually deliver, so DSP closures
  /// can size scratch buffers and configure themselves before the first
  /// process callback lands. Allocation here is safe because prepare runs
  /// synchronously on tap setup, ahead of the render-thread process loop.
  ///
  /// The format is the tap's own processing format, which is what the DSP
  /// sees — not the encoded source format the asset's track advertises.
  typealias PrepareFn = (
    _ maxFrames: Int, _ format: AudioStreamBasicDescription
  ) -> Void

  /// Default pass-through process body. Copies input → output.
  static let passThrough: ProcessFn = { input, output, numberFrames, channels, _, _ in
    let count = numberFrames * channels
    output.update(from: input, count: count)
  }

  /// Build an `AVAudioMix` for [item] with the EQ tap attached to
  /// the first audio track. Returns `nil` for HLS sources, items
  /// without an audio track, or items whose asset hasn't loaded
  /// `tracks` yet — caller is expected to retry after the asset
  /// publishes its tracks via KVO.
  ///
  /// The supplied [process] closure is captured into the tap's
  /// client info; when the tap fires its process callback, the
  /// closure is invoked synchronously on the render thread. The
  /// optional [prepare] closure fires once on the render thread
  /// from the tap's prepare callback with the tap's `maxFrames`
  /// budget — DSP code that needs to size scratch buffers should
  /// allocate there rather than on the process path.
  static func audioMix(
    for item: AVPlayerItem,
    process: @escaping ProcessFn = passThrough,
    prepare: PrepareFn? = nil
  ) -> AVAudioMix? {
    let asset = item.asset
    if isHLS(asset) { return nil }

    guard case .loaded(let tracks) = asset.status(of: .tracks) else { return nil }

    guard let track = tracks.first(where: { $0.mediaType == .audio }) else {
      return nil
    }

    guard let tap = makeTap(process: process, prepare: prepare) else { return nil }

    let inputParams = AVMutableAudioMixInputParameters(track: track)
    inputParams.audioTapProcessor = tap

    let mix = AVMutableAudioMix()
    mix.inputParameters = [inputParams]
    return mix
  }

  // MARK: - Tap creation

  private static func makeTap(
    process: @escaping ProcessFn,
    prepare: PrepareFn?
  ) -> MTAudioProcessingTap? {
    // The closure outlives the tap callbacks via an Unmanaged
    // retained pointer; tapFinalize releases it. CoreFoundation
    // guarantees tapFinalize fires on every successful tap creation
    // regardless of whether the tap was ever attached to a player —
    // so the retain is balanced even when the caller drops the
    // returned mix without using it.
    let clientInfo = Unmanaged.passRetained(
      ProcessHolder(process: process, prepare: prepare)
    )

    var callbacks = MTAudioProcessingTapCallbacks(
      version: kMTAudioProcessingTapCallbacksVersion_0,
      clientInfo: clientInfo.toOpaque(),
      init: tapInit,
      finalize: tapFinalize,
      prepare: tapPrepare,
      unprepare: tapUnprepare,
      process: tapProcess
    )

    var tap: MTAudioProcessingTap?
    let status = MTAudioProcessingTapCreate(
      kCFAllocatorDefault,
      &callbacks,
      kMTAudioProcessingTapCreationFlag_PostEffects,
      &tap
    )
    if status != noErr {
      clientInfo.release()
      return nil
    }
    return tap
  }

  // MARK: - HLS detection

  /// True when the asset's URL is an HLS playlist. Exposed
  /// `internal` so callers (e.g. `AudioTapProvider`) can
  /// short-circuit before doing any per-item allocation; the
  /// `audioMix(for:)` path also calls this so the bypass is
  /// single-sourced.
  static func isHLS(_ asset: AVAsset) -> Bool {
    guard let urlAsset = asset as? AVURLAsset else { return false }
    return urlAsset.url.pathExtension.lowercased() == "m3u8"
  }
}

/// Box for the process + optional prepare closures; passed through
/// the tap's clientInfo pointer so the C-typed callbacks can recover
/// it on each fire.
private final class ProcessHolder {
  let process: EQTap.ProcessFn
  let prepare: EQTap.PrepareFn?
  init(process: @escaping EQTap.ProcessFn, prepare: EQTap.PrepareFn?) {
    self.process = process
    self.prepare = prepare
  }
}

// MARK: - C-typed tap callbacks

private let tapInit: MTAudioProcessingTapInitCallback = {
  tap, clientInfo, tapStorageOut in
  // Pass the holder pointer through tapStorage so the process
  // callback can recover it without re-resolving from clientInfo.
  tapStorageOut.pointee = clientInfo
}

private let tapFinalize: MTAudioProcessingTapFinalizeCallback = { tap in
  let storage = MTAudioProcessingTapGetStorage(tap)
  Unmanaged<ProcessHolder>.fromOpaque(storage).release()
}

private let tapPrepare: MTAudioProcessingTapPrepareCallback = {
  tap, maxFrames, processingFormat in
  // Forward the tap's per-render-batch frame budget and processing format
  // to the holder's prepare hook so DSP closures size scratch buffers and
  // configure their cascade here, not on the process callback. The default
  // pass-through closure has no prepare hook installed, so this is a no-op
  // for non-DSP taps.
  let storage = MTAudioProcessingTapGetStorage(tap)
  let holder = Unmanaged<ProcessHolder>.fromOpaque(storage).takeUnretainedValue()
  holder.prepare?(maxFrames, processingFormat.pointee)
}

private let tapUnprepare: MTAudioProcessingTapUnprepareCallback = { tap in
  // Symmetric to tapPrepare; nothing to free under the default
  // pass-through closure.
  _ = tap
}

private let tapProcess: MTAudioProcessingTapProcessCallback = {
  tap, numberFrames, flags, bufferListInOut, numberFramesOut, flagsOut in
  // Pull source frames into the supplied buffer list.
  let status = MTAudioProcessingTapGetSourceAudio(
    tap,
    numberFrames,
    bufferListInOut,
    flagsOut,
    nil,
    numberFramesOut
  )
  if status != noErr {
    numberFramesOut.pointee = 0
    return
  }

  let storage = MTAudioProcessingTapGetStorage(tap)
  let holder = Unmanaged<ProcessHolder>.fromOpaque(storage).takeUnretainedValue()

  let abl = UnsafeMutableAudioBufferListPointer(bufferListInOut)
  let frameCount = Int(numberFramesOut.pointee)
  let liveBufferCount = Int(bufferListInOut.pointee.mNumberBuffers)
  for (index, buffer) in abl.enumerated() {
    // assumingMemoryBound is the render-thread-idiomatic cast: the
    // tap's processingFormat is documented Linear-PCM-float by the
    // AVFoundation contract, so the underlying memory is already
    // bound to Float by the framework. bindMemory would force a
    // re-bind which is technically lock-free but adds runtime
    // type-rebind side-effects.
    guard let raw = buffer.mData else { continue }
    let dataPtr = raw.assumingMemoryBound(to: Float.self)
    holder.process(
      dataPtr, dataPtr, frameCount,
      Int(buffer.mNumberChannels), index, liveBufferCount
    )
  }
}
