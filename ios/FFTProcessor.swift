import Accelerate
import Foundation
import NitroModules

/// Cached `mach_timebase_info`. The Darwin contract documents this as
/// constant for the life of the process, but `mach_timebase_info` is
/// a syscall — at 60 Hz fan-out per visualizer subscriber the cache
/// matters for the render-thread budget.
private let machTimebase: mach_timebase_info_data_t = {
  var info = mach_timebase_info_data_t()
  mach_timebase_info(&info)
  return info
}()

@inline(__always)
private func nowNanos() -> UInt64 {
  let raw = mach_absolute_time()
  return raw * UInt64(machTimebase.numer) / UInt64(machTimebase.denom)
}

/// Per-subscriber visualizer processor for iOS. Wraps a vDSP FFT
/// setup over a ring buffer of samples ingested from the audio
/// render thread. Calls `onFrame` on the main actor at
/// `intervalMs` cadence with float32 magnitudes (+ optional raw
/// pre-window PCM samples) and the stream sample rate.
///
/// When `includeSamples == true` the `samples` payload carries the
/// raw mono-mix PCM read from the same ring window the FFT just
/// consumed — pre-Hann, suitable for an oscilloscope / Lissajous
/// consumer. Matches the Android contract.
///
/// The processor only ingests the first deinterleaved channel
/// (bufferIndex 0) — iOS tap callbacks fire once per channel and
/// summing across channels would require tracking partial frames.
/// Single-channel visualization is documented behavior; matches the
/// "left channel" convention common to most desktop visualizers.
///
/// Real-time thread hygiene: every per-emit buffer is allocated once
/// at construction and reused. The `ingest` and `emitFrame` paths
/// are allocation-free until the JS-bridge handoff at the end of
/// `emitFrame`, where we copy the magnitudes into a Nitro
/// `ArrayBuffer` for the bridge crossing.
final class FFTProcessor {

  let fftSize: Int
  let intervalMs: Int
  let includeSamples: Bool
  /// Receives the FFT magnitudes (and optionally raw samples) as
  /// Nitro `ArrayBuffer`s — single memcpy per emit, no `[Float]`
  /// intermediates. Caller is responsible for dispatching the
  /// downstream JS-bridge call off the audio render thread.
  private let onFrame: (ArrayBuffer, ArrayBuffer?, Double) -> Void

  // vDSP setup
  private let log2N: vDSP_Length
  private let fftSetup: FFTSetup

  // All per-emit scratch held as long-lived raw pointers so the
  // render-thread hot path never triggers Array's CoW allocation.
  private let ringPtr: UnsafeMutablePointer<Float>
  private let windowPtr: UnsafeMutablePointer<Float>
  private let windowedPtr: UnsafeMutablePointer<Float>
  private let realPtr: UnsafeMutablePointer<Float>
  private let imagPtr: UnsafeMutablePointer<Float>
  private let magsPtr: UnsafeMutablePointer<Float>
  private let outputPtr: UnsafeMutablePointer<Float>
  /// Raw pre-window PCM scratch. Allocated only when `includeSamples`
  /// is true; populated in a separate post-windowing loop in
  /// `emitFrame` so the per-iteration windowing loop stays branch-free.
  private let samplesPtr: UnsafeMutablePointer<Float>?

  private let ringCap: Int
  private var writeIdx: Int = 0
  private var lastEmitNs: UInt64 = 0

  init(
    fftSize: Int,
    intervalMs: Int,
    includeSamples: Bool,
    onFrame: @escaping (ArrayBuffer, ArrayBuffer?, Double) -> Void
  ) throws {
    guard fftSize > 1 && (fftSize & (fftSize - 1)) == 0 else {
      throw RuntimeError("fftSize must be a power of two > 1, got \(fftSize)")
    }
    guard intervalMs > 0 else {
      throw RuntimeError("intervalMs must be > 0, got \(intervalMs)")
    }
    self.fftSize = fftSize
    self.intervalMs = intervalMs
    self.includeSamples = includeSamples
    self.onFrame = onFrame

    let log2N = vDSP_Length(fftSize.trailingZeroBitCount)
    self.log2N = log2N
    guard let setup = vDSP_create_fftsetup(log2N, FFTRadix(kFFTRadix2)) else {
      throw RuntimeError("Failed to allocate vDSP FFT setup for fftSize=\(fftSize)")
    }
    self.fftSetup = setup

    let cap = fftSize * 2
    self.ringCap = cap
    self.ringPtr = UnsafeMutablePointer<Float>.allocate(capacity: cap)
    self.ringPtr.initialize(repeating: 0, count: cap)
    self.windowPtr = UnsafeMutablePointer<Float>.allocate(capacity: fftSize)
    vDSP_hann_window(self.windowPtr, vDSP_Length(fftSize), 0)
    self.windowedPtr = UnsafeMutablePointer<Float>.allocate(capacity: fftSize)
    self.windowedPtr.initialize(repeating: 0, count: fftSize)
    let half = fftSize / 2
    self.realPtr = UnsafeMutablePointer<Float>.allocate(capacity: half)
    self.realPtr.initialize(repeating: 0, count: half)
    self.imagPtr = UnsafeMutablePointer<Float>.allocate(capacity: half)
    self.imagPtr.initialize(repeating: 0, count: half)
    self.magsPtr = UnsafeMutablePointer<Float>.allocate(capacity: half)
    self.magsPtr.initialize(repeating: 0, count: half)
    self.outputPtr = UnsafeMutablePointer<Float>.allocate(capacity: half)
    self.outputPtr.initialize(repeating: 0, count: half)
    if includeSamples {
      let ptr = UnsafeMutablePointer<Float>.allocate(capacity: fftSize)
      ptr.initialize(repeating: 0, count: fftSize)
      self.samplesPtr = ptr
    } else {
      self.samplesPtr = nil
    }
  }

  deinit {
    ringPtr.deinitialize(count: ringCap)
    ringPtr.deallocate()
    windowPtr.deallocate()
    windowedPtr.deinitialize(count: fftSize)
    windowedPtr.deallocate()
    let half = fftSize / 2
    realPtr.deinitialize(count: half)
    realPtr.deallocate()
    imagPtr.deinitialize(count: half)
    imagPtr.deallocate()
    magsPtr.deinitialize(count: half)
    magsPtr.deallocate()
    outputPtr.deinitialize(count: half)
    outputPtr.deallocate()
    if let samplesPtr = samplesPtr {
      samplesPtr.deinitialize(count: fftSize)
      samplesPtr.deallocate()
    }
    vDSP_destroy_fftsetup(fftSetup)
  }

  /// Append `frameCount` samples from `samples` to the ring buffer
  /// and emit a frame if `intervalMs` has elapsed and at least
  /// `fftSize` samples are buffered. Caller is responsible for
  /// passing only the first deinterleaved channel — multi-channel
  /// inputs are handled by skipping non-zero `bufferIndex` values
  /// at the call site (see `Visualizer.deliverAudioFrame`).
  func ingest(samples: UnsafePointer<Float>, frameCount: Int, sampleRate: Double) {
    // ringCap is fftSize * 2 — both powers of two, so wrap-around can
    // be a free bitmask instead of a 64-bit signed modulo per sample.
    let mask = ringCap - 1
    var idx = writeIdx
    for i in 0..<frameCount {
      ringPtr[idx & mask] = samples[i]
      idx += 1
    }
    writeIdx = idx

    if writeIdx < fftSize { return }
    let now = nowNanos()
    if now - lastEmitNs < UInt64(intervalMs) * 1_000_000 { return }
    lastEmitNs = now

    emitFrame(sampleRate: sampleRate)
  }

  private func emitFrame(sampleRate: Double) {
    let mask = ringCap - 1
    let n = fftSize
    let half = n / 2

    // Window the latest n samples into the persistent windowed
    // buffer.
    let start = writeIdx - n
    for i in 0..<n {
      windowedPtr[i] = ringPtr[(start + i) & mask] * windowPtr[i]
    }

    // Capture the raw pre-window PCM in a separate pass when the
    // subscriber asked for it. Kept out of the windowing loop above
    // so the hot loop stays branch-free.
    if let samples = samplesPtr {
      for i in 0..<n {
        samples[i] = ringPtr[(start + i) & mask]
      }
    }

    // Pack interleaved real → split-complex via vDSP_ctoz. The
    // even-index samples land in real, odd in imag — the standard
    // vDSP_fft_zrip preprocessing for real-valued FFTs.
    var split = DSPSplitComplex(realp: realPtr, imagp: imagPtr)
    windowedPtr.withMemoryRebound(to: DSPComplex.self, capacity: half) { complexSrc in
      vDSP_ctoz(complexSrc, 2, &split, 1, vDSP_Length(half))
    }
    vDSP_fft_zrip(fftSetup, &split, 1, log2N, FFTDirection(FFT_FORWARD))
    // After the in-place real FFT, bin 0 holds DC in `realp[0]`
    // and Nyquist in `imagp[0]`. We drop the Nyquist to keep
    // length === n/2 (matches Android).
    vDSP_zvmags(&split, 1, magsPtr, 1, vDSP_Length(half))

    // sqrt + scale to one-sided amplitude (* 2/n). DC gets the
    // same scaling — matches Android's RadixTwoFFT.magnitudes
    // convention (documented "DC double-scaled" trade-off).
    // vvsqrtf (vForce) + vDSP_vsmul vectorise on Apple silicon's NEON.
    var halfCount = Int32(half)
    vvsqrtf(outputPtr, magsPtr, &halfCount)
    var scaleVar = Float(2) / Float(n)
    vDSP_vsmul(outputPtr, 1, &scaleVar, outputPtr, 1, vDSP_Length(half))

    // Single memcpy per emit at the bridge boundary — output goes
    // directly into a Nitro ArrayBuffer; no `[Float]` Array
    // intermediate. The ArrayBuffer is reference-counted + safe to
    // hand across the dispatch boundary downstream.
    let fftBytes = half * MemoryLayout<Float>.size
    let fftBuffer = outputPtr.withMemoryRebound(to: UInt8.self, capacity: fftBytes) { rawPtr in
      ArrayBuffer.copy(of: rawPtr, size: fftBytes)
    }
    let samplesBuffer: ArrayBuffer?
    if let samples = samplesPtr {
      let samplesBytes = n * MemoryLayout<Float>.size
      samplesBuffer = samples.withMemoryRebound(to: UInt8.self, capacity: samplesBytes) {
        rawPtr in
        ArrayBuffer.copy(of: rawPtr, size: samplesBytes)
      }
    } else {
      samplesBuffer = nil
    }

    onFrame(fftBuffer, samplesBuffer, sampleRate)
  }
}
