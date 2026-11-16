import XCTest
@testable import QueuePlayer

final class BiquadCoefficientsTests: XCTestCase {

  func testZeroGainReturnsIdentityBlock() {
    let block = BiquadCoefficients.peaking(
      centerFreqHz: 1000,
      q: 1.0,
      gainDb: 0,
      sampleRateHz: 44_100
    )
    XCTAssertEqual(block, .identity)
  }

  func testCenterFreqAtNyquistReturnsIdentity() {
    let block = BiquadCoefficients.peaking(
      centerFreqHz: 22_050,
      q: 1.0,
      gainDb: 6,
      sampleRateHz: 44_100
    )
    XCTAssertEqual(block, .identity)
  }

  func testNonZeroGainProducesNonIdentityCoefficients() {
    let block = BiquadCoefficients.peaking(
      centerFreqHz: 1000,
      q: 1.0,
      gainDb: 6,
      sampleRateHz: 44_100
    )
    XCTAssertNotEqual(block, .identity)
    // Sanity bounds — RBJ peaking at +6 dB / Q=1 / 1kHz / 44.1k:
    // b0 should sit slightly above 1; a1 reflects -2*cos(omega).
    XCTAssertGreaterThan(block.b0, 1.0)
    XCTAssertLessThan(block.b0, 2.0)
  }

  func testB1EqualsA1WithinABlock() {
    // RBJ peaking unnormalized: b1 = a1 = -2*cos(omega). After
    // a0-normalization both become b1/a0 = a1/a0, so the ratio
    // collapses to equality regardless of gain or Q.
    for gain in [-12.0, -6.0, 6.0, 12.0] {
      let block = BiquadCoefficients.peaking(
        centerFreqHz: 1000, q: 1.0, gainDb: gain, sampleRateHz: 44_100
      )
      XCTAssertEqual(block.b1, block.a1, accuracy: 1e-12, "gain=\(gain)")
    }
  }

  func testInvalidInputsResolveToIdentity() {
    XCTAssertEqual(
      BiquadCoefficients.peaking(centerFreqHz: 0, q: 1, gainDb: 6, sampleRateHz: 44_100),
      .identity
    )
    XCTAssertEqual(
      BiquadCoefficients.peaking(centerFreqHz: 1000, q: 0, gainDb: 6, sampleRateHz: 44_100),
      .identity
    )
    XCTAssertEqual(
      BiquadCoefficients.peaking(centerFreqHz: 1000, q: 1, gainDb: 6, sampleRateHz: 0),
      .identity
    )
  }

  func testCoefficientsAreFiniteForAllPresetBands() {
    // Sweep the 10 ISO frequencies at extreme gains and verify
    // every block is finite. Coefficients stay finite across the
    // project's spec range (no NaN, no inf).
    for freq in EqualizerPresets.frequencies {
      for gain in [-12.0, -6.0, 0.0, 6.0, 12.0] {
        let block = BiquadCoefficients.peaking(
          centerFreqHz: freq, q: 1.0, gainDb: gain, sampleRateHz: 48_000
        )
        XCTAssertTrue(block.b0.isFinite)
        XCTAssertTrue(block.b1.isFinite)
        XCTAssertTrue(block.b2.isFinite)
        XCTAssertTrue(block.a1.isFinite)
        XCTAssertTrue(block.a2.isFinite)
      }
    }
  }
}
