import Foundation

/// Built-in 10-band equalizer presets keyed by display name.
///
/// 10 ISO-standard centre frequencies (Hz) span the audible
/// spectrum from sub-bass (31) to high treble (16k). Each preset's
/// gains array is in dB, one entry per band, ordered low-to-high
/// to match `frequencies`.
///
/// Presets are stored as an ordered array of (name, gains) tuples so
/// `getNames()` returns the canonical chip-display order. The presets
/// are read-only constants — consumer-side custom presets live in
/// `CustomPresetStore`.
enum EqualizerPresets {

  static let frequencies: [Double] = [
    31, 63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000,
  ]

  private static let presets: [(name: String, gains: [Double])] = [
    ("Flat",           [ 0.0,  0.0,  0.0,  0.0,  0.0,  0.0,  0.0,  0.0,  0.0,  0.0]),
    ("Reference",      [ 4.0,  4.0,  2.0,  1.0,  0.0,  0.0,  2.0,  3.0,  1.0, -2.0]),
    ("Bass Boost",     [ 6.0,  5.0,  3.0,  1.0,  0.0,  0.0,  0.0,  0.0,  0.0,  0.0]),
    ("Bass Reducer",   [-6.0, -5.0, -3.0, -1.0,  0.0,  0.0,  0.0,  0.0,  0.0,  0.0]),
    ("Treble Boost",   [ 0.0,  0.0,  0.0,  0.0,  0.0,  0.0,  2.0,  4.0,  5.0,  6.0]),
    ("Treble Reducer", [ 0.0,  0.0,  0.0,  0.0,  0.0,  0.0, -2.0, -4.0, -5.0, -6.0]),
    ("V-Shaped",       [ 5.0,  4.0,  2.0,  0.0, -2.0, -3.0, -1.0,  2.0,  4.0,  5.0]),
    ("Vocal Boost",    [-3.0, -2.0,  0.0,  1.0,  2.0,  3.0,  4.0,  3.0,  1.0,  0.0]),
    ("Spoken Word",    [-6.0, -4.0, -2.0,  0.0,  2.0,  4.0,  4.0,  3.0, -1.0, -3.0]),
    ("Late Night",     [ 6.0,  5.0,  2.0,  1.0,  1.0,  2.0,  3.0,  2.0,  3.0,  5.0]),
    ("Acoustic",       [ 3.0,  2.0,  1.0,  0.0,  0.0,  1.0,  2.0,  3.0,  2.0,  1.0]),
    ("Rock",           [ 5.0,  3.0, -2.0, -3.0, -1.0,  2.0,  4.0,  5.0,  5.0,  5.0]),
    ("Hip-Hop",        [ 5.0,  5.0,  3.0,  1.0, -1.0,  0.0,  1.0,  3.0,  4.0,  5.0]),
    ("Small Speakers", [ 5.0,  6.0,  3.0,  0.0,  0.0,  1.0,  2.0,  4.0,  5.0,  6.0]),
    ("Jazz",           [ 3.0,  2.0,  0.0,  1.0,  2.0,  1.0,  0.0,  1.0,  2.0,  3.0]),
    ("Electronic",     [ 5.0,  4.0,  2.0,  0.0, -2.0,  1.0,  2.0,  3.0,  4.0,  6.0]),
  ]

  /// Returns the gain array (dB) for `name`, or `nil` if unknown.
  static func getByName(_ name: String) -> [Double]? {
    return presets.first(where: { $0.name == name })?.gains
  }

  /// Display names in canonical chip-display order.
  static func getNames() -> [String] {
    return presets.map { $0.name }
  }
}
