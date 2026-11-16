import Accelerate
import XCTest
import NitroModules
@testable import QueuePlayer

/// Unit coverage for the iOS vDSP-backed `FFTProcessor`. Synthesises
/// inputs with known spectra and asserts the magnitude bins land
/// where they should — same contract as Android's `RadixTwoFFTTest`.
final class FFTProcessorTests: XCTestCase {

  private func arrayBufferToFloats(_ buffer: ArrayBuffer) -> [Float] {
    let count = buffer.size / MemoryLayout<Float>.size
    let raw = buffer.data
    return raw.withMemoryRebound(to: Float.self, capacity: count) { ptr in
      Array(UnsafeBufferPointer(start: ptr, count: count))
    }
  }

  func testEmitsAFrameOnceEnoughSamplesLand() throws {
    let exp = expectation(description: "frame emitted")
    var capturedSampleRate: Double = 0
    let proc = try FFTProcessor(
      fftSize: 256, intervalMs: 1, includeSamples: false,
    ) { _, _, sampleRate in
      capturedSampleRate = sampleRate
      exp.fulfill()
    }
    let n = 256
    var samples = [Float](repeating: 0, count: n)
    for i in 0..<n {
      samples[i] = sin(2.0 * .pi * 8 * Float(i) / Float(n))
    }
    samples.withUnsafeBufferPointer { p in
      proc.ingest(samples: p.baseAddress!, frameCount: n, sampleRate: 48000)
    }
    wait(for: [exp], timeout: 5.0)
    XCTAssertEqual(capturedSampleRate, 48000, accuracy: 0.001)
  }

  func testDoesNotEmitUntilFftSizeSamplesAccumulate() throws {
    var calls = 0
    // Fulfilled by the emit itself so the wait tracks the actual main-queue
    // callback rather than a fixed delay that can lose the race under load.
    let emitted = expectation(description: "emit landed")
    let proc = try FFTProcessor(
      fftSize: 512, intervalMs: 1, includeSamples: false,
    ) { _, _, _ in
      calls += 1
      emitted.fulfill()
    }
    let half = [Float](repeating: 0, count: 256)
    half.withUnsafeBufferPointer { p in
      proc.ingest(samples: p.baseAddress!, frameCount: 256, sampleRate: 44100)
    }
    XCTAssertEqual(calls, 0, "halfway full ring should not emit")
    let rest = [Float](repeating: 0.1, count: 256)
    rest.withUnsafeBufferPointer { p in
      proc.ingest(samples: p.baseAddress!, frameCount: 256, sampleRate: 44100)
    }
    wait(for: [emitted], timeout: 5.0)
    XCTAssertEqual(calls, 1)
  }

  func testIncludeSamplesPopulatesSamplesPayload() throws {
    var capturedSamples: ArrayBuffer?
    let exp = expectation(description: "frame emitted")
    let proc = try FFTProcessor(
      fftSize: 128, intervalMs: 1, includeSamples: true,
    ) { _, samples, _ in
      capturedSamples = samples
      exp.fulfill()
    }
    let buffer = [Float](repeating: 0.5, count: 128)
    buffer.withUnsafeBufferPointer { p in
      proc.ingest(samples: p.baseAddress!, frameCount: 128, sampleRate: 44100)
    }
    wait(for: [exp], timeout: 5.0)
    let samples = try XCTUnwrap(capturedSamples)
    XCTAssertEqual(samples.size, 128 * MemoryLayout<Float>.size)
  }

  func testIncludeSamplesFalseLeavesSamplesNil() throws {
    var capturedSamples: ArrayBuffer?
    var captured = false
    let exp = expectation(description: "frame emitted")
    let proc = try FFTProcessor(
      fftSize: 128, intervalMs: 1, includeSamples: false,
    ) { _, samples, _ in
      capturedSamples = samples
      captured = true
      exp.fulfill()
    }
    let buffer = [Float](repeating: 0.5, count: 128)
    buffer.withUnsafeBufferPointer { p in
      proc.ingest(samples: p.baseAddress!, frameCount: 128, sampleRate: 44100)
    }
    wait(for: [exp], timeout: 5.0)
    XCTAssertTrue(captured)
    XCTAssertNil(capturedSamples)
  }

  func testPureSinePeaksAtExpectedBin() throws {
    var capturedFft: ArrayBuffer?
    let exp = expectation(description: "frame emitted")
    let n = 256
    let k = 8
    let proc = try FFTProcessor(
      fftSize: n, intervalMs: 1, includeSamples: false,
    ) { fft, _, _ in
      capturedFft = fft
      exp.fulfill()
    }
    var samples = [Float](repeating: 0, count: n)
    for i in 0..<n {
      samples[i] = cos(2.0 * .pi * Float(k) * Float(i) / Float(n))
    }
    samples.withUnsafeBufferPointer { p in
      proc.ingest(samples: p.baseAddress!, frameCount: n, sampleRate: 48000)
    }
    wait(for: [exp], timeout: 5.0)
    let fft = try XCTUnwrap(capturedFft)
    let mags = arrayBufferToFloats(fft)
    let peakBin = mags.indices.max(by: { mags[$0] < mags[$1] }) ?? -1
    XCTAssertEqual(peakBin, k, "peak should land at bin \(k)")
    // The Hann window's main lobe spans ~3 bins, so immediate
    // neighbours of the peak carry ~50% of the peak energy. Test
    // that everything OUTSIDE the main lobe (k-1 .. k+1) is far
    // below the peak.
    for i in mags.indices {
      if i >= k - 1 && i <= k + 1 { continue }
      XCTAssertLessThan(
        mags[i], mags[k] / 50,
        "bin \(i) (\(mags[i])) should be far below bin \(k) (\(mags[k]))",
      )
    }
  }

  func testStereoInputProducesArithmeticMonoMix() throws {
    // Drive the EQProcessor's tap closure with two deinterleaved
    // buffers (channel 0 = +0.5, channel 1 = -0.3). The closure
    // collapses the post-EQ buffers into `(L+R)/2 = +0.1` and hands
    // a single mono frame to `Visualizer.deliverAudioFrame`. With
    // band gains all zero the cascade is identity, so the mono mix
    // is the arithmetic mean of the two channel inputs.
    //
    // Verification path: subscribe a real `FFTProcessor` via
    // `Visualizer.subscribe(includeSamples:)`. The captured samples
    // payload is the raw pre-window mono-mix PCM, so every bin
    // must equal +0.1 directly.
    // Construct an isolated AudioTapProvider for this test so a
    // shared-singleton subscription elsewhere can't tear samples
    // we're capturing here. Both the Visualizer and the EQProcessor
    // route through this same provider — the visualizer registers as
    // a consumer, and the processor delivers frames into the
    // provider's fan-out.
    let priorVisualizer = Visualizer.current
    let provider = AudioTapProvider()
    let viz = Visualizer(provider: provider)
    Visualizer.current = viz
    addTeardownBlock {
      Visualizer.current = priorVisualizer
    }

    let n = 256
    let exp = expectation(description: "frame emitted")
    var capturedSamples: ArrayBuffer?
    let dispose = try viz.subscribe(
      config: VisualizerConfig(
        fftSize: Double(n), updateIntervalMs: 1, includeSamples: true,
      ),
      onFrame: { frame in
        capturedSamples = frame.samples
        exp.fulfill()
      },
      onError: { _ in },
    )

    let proc = EQProcessor(provider: provider)
    proc.setSampleRate(48_000)
    proc.setChannelCount(2)
    proc.setBandGains(Array(repeating: 0, count: 10))
    // `tapPrepare` is what the live tap fires from
    // `MTAudioProcessingTapPrepareCallback`; the test invokes it
    // directly to set up the mono-mix scratch ahead of the
    // render-thread closure body.
    proc.tapPrepare(maxFrames: n)
    let closure = proc.tapProcessClosure()

    let leftIn = UnsafeMutablePointer<Float>.allocate(capacity: n)
    let leftOut = UnsafeMutablePointer<Float>.allocate(capacity: n)
    let rightIn = UnsafeMutablePointer<Float>.allocate(capacity: n)
    let rightOut = UnsafeMutablePointer<Float>.allocate(capacity: n)
    defer {
      leftIn.deallocate()
      leftOut.deallocate()
      rightIn.deallocate()
      rightOut.deallocate()
    }
    for i in 0..<n {
      leftIn[i] = 0.5
      rightIn[i] = -0.3
      leftOut[i] = -99
      rightOut[i] = -99
    }
    closure(leftIn, leftOut, n, 1, 0, 2)
    closure(rightIn, rightOut, n, 1, 1, 2)

    wait(for: [exp], timeout: 5.0)
    let samples = try XCTUnwrap(capturedSamples)
    XCTAssertEqual(samples.size, n * MemoryLayout<Float>.size)

    let count = samples.size / MemoryLayout<Float>.size
    let recovered = samples.data.withMemoryRebound(to: Float.self, capacity: count) { ptr in
      Array(UnsafeBufferPointer(start: ptr, count: count))
    }
    for i in 0..<n {
      XCTAssertEqual(recovered[i], 0.1, accuracy: 1e-4,
        "samples[\(i)] should be the raw +0.1 mono mix")
    }

    dispose()
  }

  func testIntervalThrottlesBackToBackEmissions() throws {
    var calls = 0
    let proc = try FFTProcessor(
      fftSize: 64, intervalMs: 1000, includeSamples: false,
    ) { _, _, _ in calls += 1 }
    let buffer = [Float](repeating: 0.25, count: 64)
    buffer.withUnsafeBufferPointer { p in
      proc.ingest(samples: p.baseAddress!, frameCount: 64, sampleRate: 48000)
      proc.ingest(samples: p.baseAddress!, frameCount: 64, sampleRate: 48000)
    }
    let exp = expectation(description: "drained")
    DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) { exp.fulfill() }
    // Generous timeout: the 0.1s drain only needs to outlast the 1000ms
    // throttle window; the ceiling is headroom for a loaded CI main queue.
    wait(for: [exp], timeout: 10.0)
    XCTAssertEqual(calls, 1, "second back-to-back call must be throttled")
  }

  func testFrameEmissionResumesAfterIngestQuietWindow() throws {
    // Contract: a quiet window (no `ingest` calls) does NOT leave the
    // processor in a state that suppresses subsequent emits — once
    // `ingest` resumes, `onFrame` fires again at the documented
    // intervalMs cadence. Live audio chains express "pause" by
    // ceasing tap callbacks; the unit-test analogue is a sleep
    // window.
    let n = 256
    let intervalMs = 1
    let lock = NSLock()
    var frames = 0
    let proc = try FFTProcessor(
      fftSize: n,
      intervalMs: intervalMs,
      includeSamples: false,
    ) { _, _, _ in
      lock.lock(); frames += 1; lock.unlock()
    }

    let buffer = UnsafeMutablePointer<Float>.allocate(capacity: n)
    defer { buffer.deallocate() }
    for i in 0..<n {
      buffer[i] = sin(Float(i) * 0.05)
    }

    // Steady-state pre-pause: each `ingest` emits at most once per
    // `intervalMs` window, so back-to-back calls with millisecond-
    // scale sleeps drive a measurable cadence.
    for _ in 0..<8 {
      proc.ingest(samples: buffer, frameCount: n, sampleRate: 48_000)
      Thread.sleep(forTimeInterval: 0.005)
    }
    let framesBeforePause = readFrames(lock: lock, frames: &frames)
    XCTAssertGreaterThan(
      framesBeforePause, 0,
      "steady-state delivery should produce at least one frame",
    )

    // Pause: stop calling `ingest`. Wait well past the interval —
    // no new frames should land.
    Thread.sleep(forTimeInterval: 0.020)
    let framesAfterPause = readFrames(lock: lock, frames: &frames)
    XCTAssertEqual(
      framesAfterPause, framesBeforePause,
      "frame count must not advance while delivery is paused",
    )

    // Resume: deliver again. The next interval window must produce
    // at least one new frame.
    for _ in 0..<8 {
      proc.ingest(samples: buffer, frameCount: n, sampleRate: 48_000)
      Thread.sleep(forTimeInterval: 0.005)
    }
    let framesAfterResume = readFrames(lock: lock, frames: &frames)
    XCTAssertGreaterThan(
      framesAfterResume, framesAfterPause,
      "frame count must advance once delivery resumes",
    )
  }

  private func readFrames(lock: NSLock, frames: inout Int) -> Int {
    lock.lock(); defer { lock.unlock() }
    return frames
  }
}
