import XCTest
@testable import QueuePlayer

/// Unit coverage for `EQProcessor` cascade.
final class EQProcessorTests: XCTestCase {

  func testIsNotReadyBeforeChannelCountSet() {
    let proc = EQProcessor()
    XCTAssertFalse(proc.isReady)
  }

  func testIsReadyOnceSampleRateAndChannelCountSet() {
    let proc = EQProcessor()
    proc.setSampleRate(44_100)
    proc.setChannelCount(2)
    proc.setBandGains(Array(repeating: 0, count: 10))
    XCTAssertTrue(proc.isReady)
  }

  func testProcessIsPassThroughBeforeReady() {
    let proc = EQProcessor()
    let frames = 16
    let input = UnsafeMutablePointer<Float>.allocate(capacity: frames)
    let output = UnsafeMutablePointer<Float>.allocate(capacity: frames)
    defer {
      input.deallocate()
      output.deallocate()
    }
    for i in 0..<frames {
      input[i] = Float(i) * 0.1
      output[i] = -1
    }
    proc.process(input: input, output: output, frames: frames, channel: 0)
    for i in 0..<frames {
      XCTAssertEqual(input[i], output[i], accuracy: 0.0001)
    }
  }

  func testFlatPresetProducesNearUnityOutput() {
    // All-zero gains compile to identity blocks; the cascade is a
    // numerical-noise pass-through. RBJ-from-zero coefficients in
    // BiquadCoefficients.peaking short-circuit to identity, so the
    // cascade output should match input within float precision.
    let proc = EQProcessor()
    proc.setSampleRate(44_100)
    proc.setChannelCount(1)
    proc.setBandGains(Array(repeating: 0, count: 10))

    let frames = 64
    let input = UnsafeMutablePointer<Float>.allocate(capacity: frames)
    let output = UnsafeMutablePointer<Float>.allocate(capacity: frames)
    defer {
      input.deallocate()
      output.deallocate()
    }
    // Generate a 1 kHz sine wave at 44.1k.
    for i in 0..<frames {
      input[i] = Float(sin(2 * .pi * 1000.0 * Double(i) / 44_100.0))
      output[i] = -99
    }
    proc.process(input: input, output: output, frames: frames, channel: 0)
    for i in 0..<frames {
      XCTAssertEqual(input[i], output[i], accuracy: 0.0001)
    }
  }

  func testNonZeroGainProducesDifferentOutput() {
    let proc = EQProcessor()
    proc.setSampleRate(44_100)
    proc.setChannelCount(1)
    var gains = [Double](repeating: 0, count: 10)
    gains[5] = 6  // boost 1 kHz band
    proc.setBandGains(gains)

    let frames = 256
    let input = UnsafeMutablePointer<Float>.allocate(capacity: frames)
    let output = UnsafeMutablePointer<Float>.allocate(capacity: frames)
    defer {
      input.deallocate()
      output.deallocate()
    }
    for i in 0..<frames {
      input[i] = Float(sin(2 * .pi * 1000.0 * Double(i) / 44_100.0))
    }
    proc.process(input: input, output: output, frames: frames, channel: 0)

    // After the cascade settles, the boosted-band output peak
    // amplitude should exceed the input peak amplitude. Sample the
    // last quarter (post-transient) for a peak comparison.
    var inMax: Float = 0
    var outMax: Float = 0
    for i in (frames * 3 / 4)..<frames {
      inMax = max(inMax, abs(input[i]))
      outMax = max(outMax, abs(output[i]))
    }
    XCTAssertGreaterThan(outMax, inMax)
  }

  func testBandZeroBoostsLowFrequencyAtSteadyState() {
    // Boosting band 0 amplifies a 31 Hz sine and not a 16 kHz sine.
    // Asserts band index 0 maps to 31 Hz.
    let proc = EQProcessor()
    proc.setSampleRate(44_100)
    proc.setChannelCount(1)
    var gains = [Double](repeating: 0, count: 10)
    gains[0] = 12  // boost 31 Hz band
    proc.setBandGains(gains)

    let frames = 4096
    let input = UnsafeMutablePointer<Float>.allocate(capacity: frames)
    let output = UnsafeMutablePointer<Float>.allocate(capacity: frames)
    defer {
      input.deallocate()
      output.deallocate()
    }
    for i in 0..<frames {
      input[i] = Float(sin(2 * .pi * 31.0 * Double(i) / 44_100.0))
    }
    proc.process(input: input, output: output, frames: frames, channel: 0)

    var inMax: Float = 0
    var outMax: Float = 0
    for i in (frames * 3 / 4)..<frames {
      inMax = max(inMax, abs(input[i]))
      outMax = max(outMax, abs(output[i]))
    }
    XCTAssertGreaterThan(outMax, inMax * 1.5)
  }

  func testBandNineBoostsHighFrequencyAtSteadyState() {
    // Boosting band 9 amplifies a 16 kHz sine. Asserts band index 9
    // maps to 16 kHz. 16 kHz at 44.1 kHz sample rate is just below
    // Nyquist; the cascade should still amplify.
    let proc = EQProcessor()
    proc.setSampleRate(44_100)
    proc.setChannelCount(1)
    var gains = [Double](repeating: 0, count: 10)
    gains[9] = 12  // boost 16 kHz band
    proc.setBandGains(gains)

    let frames = 4096
    let input = UnsafeMutablePointer<Float>.allocate(capacity: frames)
    let output = UnsafeMutablePointer<Float>.allocate(capacity: frames)
    defer {
      input.deallocate()
      output.deallocate()
    }
    for i in 0..<frames {
      input[i] = Float(sin(2 * .pi * 16_000.0 * Double(i) / 44_100.0))
    }
    proc.process(input: input, output: output, frames: frames, channel: 0)

    var inMax: Float = 0
    var outMax: Float = 0
    for i in (frames * 3 / 4)..<frames {
      inMax = max(inMax, abs(input[i]))
      outMax = max(outMax, abs(output[i]))
    }
    XCTAssertGreaterThan(outMax, inMax * 1.2)
  }

  func testProcessChannelOutOfRangeIsNoOp() {
    let proc = EQProcessor()
    proc.setSampleRate(44_100)
    proc.setChannelCount(2)
    proc.setBandGains(Array(repeating: 0, count: 10))

    let frames = 8
    let input = UnsafeMutablePointer<Float>.allocate(capacity: frames)
    let output = UnsafeMutablePointer<Float>.allocate(capacity: frames)
    defer {
      input.deallocate()
      output.deallocate()
    }
    for i in 0..<frames {
      input[i] = Float(i)
      output[i] = -1
    }
    proc.process(input: input, output: output, frames: frames, channel: 99)
    // Output unchanged because process bailed.
    for i in 0..<frames {
      XCTAssertEqual(Float(-1), output[i])
    }
  }

  func testProcessorPicksUpProviderGainsOnVersionAdvance() {
    // The render-thread tap callback compares the provider's
    // gains version to its own last-seen value and recomputes
    // coefficients in place when they differ. A fresh
    // [AudioTapProvider] starts at version 0; calling [setGains]
    // advances it, so the first closure invocation pulls the
    // boosted gains under the lock and the cascade amplifies the
    // input. A subsequent flat-gains call advances the version
    // again, and a second closure invocation should settle back
    // toward unity.
    let provider = AudioTapProvider()
    // EQ defaults to disabled at the provider level; consumers
    // (Equalizer Hybrid's setEnabled) flip this. The tap closure
    // reads the enabled flag from the same snapshot as the gains
    // and falls back to pass-through when disabled — turn it on
    // explicitly so the version-advance recompute is exercised.
    provider.setEnabled(true)
    var boosted = [Double](repeating: 0, count: 10)
    boosted[5] = 12
    provider.setGains(boosted)

    let proc = EQProcessor(provider: provider)
    proc.setSampleRate(44_100)
    proc.setChannelCount(1)
    let closure = proc.tapProcessClosure()

    let frames = 4096
    let input = UnsafeMutablePointer<Float>.allocate(capacity: frames)
    let output = UnsafeMutablePointer<Float>.allocate(capacity: frames)
    defer {
      input.deallocate()
      output.deallocate()
    }
    for i in 0..<frames {
      input[i] = Float(sin(2 * .pi * 1000.0 * Double(i) / 44_100.0))
      output[i] = -99
    }

    closure(input, output, frames, 1, 0, 1)

    var inMaxBoost: Float = 0
    var outMaxBoost: Float = 0
    for i in (frames * 3 / 4)..<frames {
      inMaxBoost = max(inMaxBoost, abs(input[i]))
      outMaxBoost = max(outMaxBoost, abs(output[i]))
    }
    XCTAssertGreaterThan(outMaxBoost, inMaxBoost * 1.5)

    // Flatten via the provider; the next closure invocation must
    // observe the version advance and recompute coefficients to
    // identity. Refill the input buffer so the comparison is
    // against fresh samples (the closure wrote in place above).
    provider.setGains(Array(repeating: 0, count: 10))
    for i in 0..<frames {
      input[i] = Float(sin(2 * .pi * 1000.0 * Double(i) / 44_100.0))
      output[i] = -99
    }
    closure(input, output, frames, 1, 0, 1)

    var inMaxFlat: Float = 0
    var outMaxFlat: Float = 0
    for i in (frames * 3 / 4)..<frames {
      inMaxFlat = max(inMaxFlat, abs(input[i]))
      outMaxFlat = max(outMaxFlat, abs(output[i]))
    }
    XCTAssertEqual(outMaxFlat, inMaxFlat, accuracy: 0.05)
  }

  func testTapProcessClosureKeepsProcessorAliveAfterCallerDropsReference() {
    // The tap closure is the sole strong owner of the EQProcessor:
    // the provider builds a processor inside `audioMix(for:)`,
    // hands the closure to the tap, and returns. The processor
    // must stay alive for as long as the tap holds the closure,
    // otherwise the cascade is silently inert and every buffer
    // routes through the pass-through fallback.
    let frames = 256
    let input = UnsafeMutablePointer<Float>.allocate(capacity: frames)
    let output = UnsafeMutablePointer<Float>.allocate(capacity: frames)
    defer {
      input.deallocate()
      output.deallocate()
    }
    for i in 0..<frames {
      input[i] = Float(sin(2 * .pi * 1000.0 * Double(i) / 44_100.0))
      output[i] = -99
    }

    let closure: EQTap.ProcessFn = {
      let proc = EQProcessor()
      proc.setSampleRate(44_100)
      proc.setChannelCount(1)
      var gains = [Double](repeating: 0, count: 10)
      gains[5] = 12  // hard boost on 1 kHz band
      proc.setBandGains(gains)
      return proc.tapProcessClosure()
    }()
    // The local `proc` from the inner scope has gone out of scope
    // here — only the closure can keep the processor alive.

    closure(input, output, frames, 1, 0, 1)

    // If the processor was dropped the closure falls through to
    // pass-through and output mirrors input. With a 12 dB boost
    // at the input frequency the cascade settles to a noticeably
    // larger amplitude.
    var inMax: Float = 0
    var outMax: Float = 0
    for i in (frames * 3 / 4)..<frames {
      inMax = max(inMax, abs(input[i]))
      outMax = max(outMax, abs(output[i]))
    }
    XCTAssertGreaterThan(outMax, inMax * 1.5)
  }
}
