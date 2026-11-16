import Foundation

/// RBJ "Audio EQ Cookbook" peaking-filter coefficient calculator.
///
/// A peaking biquad boosts or cuts a band centred on `centerFreq`
/// without affecting frequencies far from the centre. Three inputs
/// drive the response:
///   * `centerFreqHz` — band centre in Hz
///   * `q` — band sharpness (higher Q = narrower band)
///   * `gainDb` — peak (positive) or notch (negative) magnitude in dB
///
/// Output is the five coefficients `vDSP_biquad` consumes, in the
/// standard `[b0, b1, b2, a1, a2]` order with `a0` already
/// normalised to 1.0. A 10-band cascade caller writes one
/// coefficient block per band per channel.
///
/// Formulas follow Robert Bristow-Johnson's published cookbook
/// (the reference implementation across virtually every audio
/// library that ships a parametric EQ).
enum BiquadCoefficients {

  /// Five-coefficient block in `vDSP_biquad` order:
  /// `[b0, b1, b2, a1, a2]` with `a0` normalised to 1.0.
  struct Block: Equatable {
    let b0: Double
    let b1: Double
    let b2: Double
    let a1: Double
    let a2: Double

    /// Identity block — output equals input. A "Flat" preset emits
    /// this for every band so the cascade is a true bypass without
    /// the EQ stage having to special-case zero gain.
    static let identity = Block(b0: 1, b1: 0, b2: 0, a1: 0, a2: 0)
  }

  /// Build a peaking-EQ coefficient block.
  ///
  /// `sampleRateHz` is the audio session's playback rate (typically
  /// 44_100 or 48_000); the renderer-supplied processing format is
  /// the source of truth.
  static func peaking(
    centerFreqHz: Double,
    q: Double,
    gainDb: Double,
    sampleRateHz: Double
  ) -> Block {
    let epsilon: Double = 1e-6
    if Swift.abs(gainDb) < epsilon { return .identity }
    if q <= 0 { return .identity }
    if centerFreqHz <= 0 { return .identity }
    if sampleRateHz <= 0 { return .identity }
    let nyquist = sampleRateHz * 0.5
    if centerFreqHz >= nyquist {
      // The band's centre is at or past the Nyquist frequency — no
      // representable response. Identity prevents a NaN cascade.
      return .identity
    }

    let A: Double = pow(10.0, gainDb / 40.0)
    let omega: Double = 2.0 * Double.pi * centerFreqHz / sampleRateHz
    let cosOmega: Double = cos(omega)
    let alpha: Double = sin(omega) / (2.0 * q)

    let b0 = 1.0 + alpha * A
    let b1 = -2.0 * cosOmega
    let b2 = 1.0 - alpha * A
    let a0 = 1.0 + alpha / A
    let a1 = -2.0 * cosOmega
    let a2 = 1.0 - alpha / A

    return Block(
      b0: b0 / a0,
      b1: b1 / a0,
      b2: b2 / a0,
      a1: a1 / a0,
      a2: a2 / a0
    )
  }
}
