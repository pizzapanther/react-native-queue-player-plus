import Foundation

/// The ReplayGain value layer: the four tag names, the numeric
/// acceptance rules, the string parsers that feed them, and the
/// assembly of a `ReplayGainData` from a name/value dictionary.
///
/// Deliberately source-agnostic. Names arrive from
/// `AssetReplayGainReader`, which derives them from whatever shape
/// the container gave AVFoundation (ID3 `TXXX` info fields, Vorbis
/// comment keys, M4A freeform reverse-DNS keys); values arrive as the
/// raw strings those tags carry. Every entry point lands on the same
/// `acceptGainDb` / `acceptPeak` pair so one rule governs all of them.
///
/// Parser tolerance follows NOTES.md section 25 — `" dB"` suffix optional,
/// `+/-` prefix optional, U+2212 minus sign normalised, period
/// decimal, whitespace tolerant, NaN/Inf rejected. Name matching is
/// case-insensitive.
enum ReplayGainExtractor {

  // Tag descriptions matched (case-insensitive).
  static let trackGainKey = "REPLAYGAIN_TRACK_GAIN"
  static let trackPeakKey = "REPLAYGAIN_TRACK_PEAK"
  static let albumGainKey = "REPLAYGAIN_ALBUM_GAIN"
  static let albumPeakKey = "REPLAYGAIN_ALBUM_PEAK"

  // MARK: - Tag assembly

  /// Build a `ReplayGainData` from a `[name: rawValue]` dictionary.
  /// Names are matched case-insensitively against the four constants
  /// above; each value goes through the parser for its kind, so an
  /// unparseable or out-of-band value lands as `nil` rather than
  /// rejecting its siblings.
  ///
  /// Returns `nil` when none of the four names produced a usable
  /// value — "this source carries no ReplayGain".
  static func rgDataFromTags(_ tags: [String: String]) -> ReplayGainData? {
    let upper = tags.reduce(into: [String: String]()) { d, kv in
      d[kv.key.uppercased()] = kv.value
    }
    let trackGain = upper[trackGainKey].flatMap { parseGainDb($0) }
    let trackPeak = upper[trackPeakKey].flatMap { parsePeak($0) }
    let albumGain = upper[albumGainKey].flatMap { parseGainDb($0) }
    let albumPeak = upper[albumPeakKey].flatMap { parsePeak($0) }
    if trackGain == nil && trackPeak == nil
       && albumGain == nil && albumPeak == nil {
      return nil
    }
    return ReplayGainData(
      trackGainDb: trackGain, trackPeak: trackPeak,
      albumGainDb: albumGain, albumPeak: albumPeak
    )
  }

  // MARK: - Numeric acceptance

  /// Shared numeric acceptance for a gain in dB, whatever entry point
  /// it arrived through — a tag string via `parseGainDb` or a number
  /// straight from the consumer. Rejects only non-finite values: a
  /// gain carries no plausible range guard, unlike a peak, and clip
  /// safety is the gain clamp's job rather than the acceptor's.
  static func acceptGainDb(_ value: Double) -> Double? {
    guard value.isFinite else { return nil }
    return value
  }

  /// Shared numeric acceptance for a linear peak, whatever entry point
  /// it arrived through — a tag string via `parsePeak` or a number
  /// straight from the consumer. Accepts `0.001 ... 2.0` inclusive:
  /// `1.0` is the spec's encoding of digital full scale and is a real
  /// measurement, and values above it are ordinary true peaks. Rejects
  /// non-finite values, anything below the encoder-zero floor and
  /// anything above an implausible true peak.
  static func acceptPeak(_ value: Double) -> Double? {
    guard value.isFinite else { return nil }
    guard value >= 0.001, value <= 2.0 else { return nil }
    return value
  }

  // MARK: - Gain parsing

  /// Parse a `REPLAYGAIN_*_GAIN` string (e.g. `"-6.54 dB"`,
  /// `"+3.21"`, `"−1.0 dB"` with U+2212 minus) into a Double dB.
  /// Returns `nil` for unparseable input; the parsed number is then
  /// put through `acceptGainDb`, so text handling is all this adds
  /// over the numeric entry point.
  static func parseGainDb(_ rawInput: String) -> Double? {
    var trimmed = rawInput.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !trimmed.isEmpty else { return nil }

    // Normalise U+2212 MINUS SIGN (\u{2212}) → ASCII '-'.
    trimmed = trimmed.replacingOccurrences(of: "\u{2212}", with: "-")

    // Strip case-insensitive trailing "dB" + any whitespace separator.
    let lower = trimmed.lowercased()
    if lower.hasSuffix("db") {
      let endIdx = trimmed.index(trimmed.endIndex, offsetBy: -2)
      trimmed = String(trimmed[..<endIdx]).trimmingCharacters(in: .whitespacesAndNewlines)
    }
    guard !trimmed.isEmpty else { return nil }

    // Collapse extra-internal whitespace per NOTES.md section 25 — some
    // taggers emit `"- 6.54"` (sign-digit) or `"-6 .54"` (digit-
    // decimal). `Double` rejects both; collapsing makes them parse.
    trimmed = trimmed.unicodeScalars
      .filter { !CharacterSet.whitespaces.contains($0) }
      .map { String($0) }
      .joined()

    // Reject comma decimals (RG2 spec — period only).
    if trimmed.contains(",") { return nil }

    guard let value = Double(trimmed) else { return nil }
    return acceptGainDb(value)
  }

  /// Parse a `REPLAYGAIN_*_PEAK` string (linear, period decimal).
  /// Returns `nil` for unparseable input; the parsed number is then
  /// put through `acceptPeak`, which holds the `0.001 ... 2.0`
  /// acceptance band — a full-scale `1.0` included.
  static func parsePeak(_ rawInput: String) -> Double? {
    let trimmed = rawInput.trimmingCharacters(in: .whitespacesAndNewlines)
    guard let value = Double(trimmed) else { return nil }
    return acceptPeak(value)
  }
}
