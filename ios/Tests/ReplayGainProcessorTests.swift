import XCTest
@testable import QueuePlayer

/// Unit tests for `ReplayGainProcessor` — the standalone Float32
/// gain stage. Covers identity-at-unity, scaling shapes, dB→linear
/// math, and contention on `setLinearGain` while `process` runs.
final class ReplayGainProcessorTests: XCTestCase {

  // MARK: - Gain shapes

  func testProcessIsIdentityAtUnityGain() {
    let processor = ReplayGainProcessor()
    var samples: [Float] = [-1.0, -0.5, 0.0, 0.25, 1.0]
    let original = samples
    samples.withUnsafeMutableBufferPointer { ptr in
      processor.process(buffer: ptr.baseAddress!, frames: ptr.count)
    }
    XCTAssertEqual(samples, original, "unity gain should not mutate the buffer")
  }

  func testProcessHalvesAtHalfGain() {
    let processor = ReplayGainProcessor()
    processor.setLinearGain(0.5)
    var samples: [Float] = [1.0, 0.5, -0.5, -1.0, 0.0]
    let expected: [Float] = [0.5, 0.25, -0.25, -0.5, 0.0]
    samples.withUnsafeMutableBufferPointer { ptr in
      processor.process(buffer: ptr.baseAddress!, frames: ptr.count)
    }
    for (s, e) in zip(samples, expected) {
      XCTAssertEqual(s, e, accuracy: 0.0001)
    }
  }

  func testProcessZeroesAtZeroGain() {
    let processor = ReplayGainProcessor()
    processor.setLinearGain(0.0)
    var samples: [Float] = [1.0, 0.5, -0.5, -1.0, 0.25]
    samples.withUnsafeMutableBufferPointer { ptr in
      processor.process(buffer: ptr.baseAddress!, frames: ptr.count)
    }
    XCTAssertEqual(samples, [0, 0, 0, 0, 0])
  }

  // MARK: - Gain attenuation matches dB→linear conversion

  func testProcessAttenuatesByDbValue() {
    // -6 dB ≈ 0.5012 linear. Confirms the math at the call-site
    // (`linearGain = pow(10, dB/20)`) lines up with the processor.
    let processor = ReplayGainProcessor()
    let linear = Float(pow(10.0, -6.0 / 20.0))
    processor.setLinearGain(linear)

    var samples = Array<Float>(repeating: 1.0, count: 64)
    samples.withUnsafeMutableBufferPointer { ptr in
      processor.process(buffer: ptr.baseAddress!, frames: ptr.count)
    }
    for s in samples {
      XCTAssertEqual(s, linear, accuracy: 0.0001)
    }
  }

  // MARK: - Threading

  func testSetLinearGainAndProcessRunConcurrentlyWithoutCrash() {
    let processor = ReplayGainProcessor()
    let writerExp = expectation(description: "writer finished")
    let readerExp = expectation(description: "reader finished")

    let writerQueue = DispatchQueue(label: "rg.writer")
    let readerQueue = DispatchQueue(label: "rg.reader")

    writerQueue.async {
      for i in 0..<2_000 {
        processor.setLinearGain(Float(i % 100) / 100.0)
      }
      writerExp.fulfill()
    }
    readerQueue.async {
      var samples = Array<Float>(repeating: 0.5, count: 256)
      for _ in 0..<2_000 {
        samples.withUnsafeMutableBufferPointer { ptr in
          processor.process(buffer: ptr.baseAddress!, frames: ptr.count)
        }
      }
      readerExp.fulfill()
    }

    wait(for: [writerExp, readerExp], timeout: 10)
  }
}
