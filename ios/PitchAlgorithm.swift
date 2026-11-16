import AVFoundation

/// Maps the public `PitchCorrectionMode` to the native
/// `AVAudioTimePitchAlgorithm` stamped on each `AVPlayerItem`.
///
/// At unity speed there is nothing to stretch, so every mode resolves to
/// `.varispeed` — the zero-work passthrough — so no time-domain / spectral
/// processing graph is primed while playing at normal speed. Above/below
/// unity: `none` lets pitch follow rate (`.varispeed`), `voice` preserves
/// pitch with the speech-tuned algorithm (`.timeDomain`), `music` preserves
/// pitch with the transient-preserving algorithm (`.spectral`).
enum PitchAlgorithm {
  static func resolve(
    mode: PitchCorrectionMode,
    speed: Float
  ) -> AVAudioTimePitchAlgorithm {
    guard speed != 1.0 else { return .varispeed }
    switch mode {
    case .none: return .varispeed
    case .voice: return .timeDomain
    case .music: return .spectral
    }
  }
}
