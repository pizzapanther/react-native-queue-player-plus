import Foundation

/// Pure-Swift mirror of the Nitrogen-generated `ReplayGainMode`.
/// Keeps the selector + processor off the Swift↔C++ interop graph
/// so they compile as a clean Swift module. The Hybrid layer in
/// `TrackPlayer.swift` maps `ReplayGainMode` ↔ this enum at the
/// API boundary.
enum ReplayGainSelectorMode: Equatable {
  case off
  case track
  case album
}

/// Parsed ReplayGain tag values for a single track. All four fields
/// are independent — a track may carry only track tags, only album
/// tags, or both. The Opus container guard returns nil from
/// `AssetReplayGainReader.read(item:)` rather than producing a
/// ReplayGainData with one nil pair.
///
/// Strict mode (`.album` requires album tag, `.track` requires
/// track tag) means there is no fallback — see `selectGain(mode:)`.
struct ReplayGainData: Equatable {
  /// `REPLAYGAIN_TRACK_GAIN` parsed value in dB. `nil` when the tag
  /// was absent or rejected by the parser. `Double` to match the
  /// JS-surface `number | null` type; convert to `Float` at the
  /// audio-stage boundary (vDSP / DSP processors operate on Float).
  let trackGainDb: Double?
  /// `REPLAYGAIN_TRACK_PEAK` parsed linear value. `nil` when absent
  /// or rejected — `ReplayGainExtractor.acceptPeak` accepts the
  /// `0.001 ... 2.0` band, so a full-scale `1.0` is kept and an
  /// encoder zero (`< 0.001`) is treated as no-peak.
  let trackPeak: Double?
  /// `REPLAYGAIN_ALBUM_GAIN` parsed value in dB. `nil` when absent.
  let albumGainDb: Double?
  /// `REPLAYGAIN_ALBUM_PEAK` parsed linear value. `nil` when absent.
  let albumPeak: Double?

  /// All-or-nothing merge of consumer-supplied ReplayGain against
  /// values detected from the file, decided **per item** — never per
  /// mode and never per field.
  ///
  /// [suppliedClaimed] is `true` when any of the four fields was
  /// present on the consumer's `TrackItem`, captured before any value
  /// was validated. When it is `true` the track's ReplayGain comes
  /// solely from [supplied] and [detected] is discarded in full,
  /// including for the mode the consumer said nothing about.
  /// `suppliedClaimed == true` with `supplied == nil` is a real state
  /// — the consumer owns this track and gave nothing usable — so it
  /// yields no gain and still discards detection.
  static func merge(
    suppliedClaimed: Bool, supplied: ReplayGainData?, detected: ReplayGainData?
  ) -> ReplayGainData? {
    return suppliedClaimed ? supplied : detected
  }

  /// Strict mode-aware selector. See NOTES.md section 25.
  ///
  /// - `.off`: returns `(nil, nil)` (no-op).
  /// - `.track`: returns `(trackGainDb, trackPeak)` — both may be nil.
  /// - `.album`: returns `(albumGainDb, albumPeak)` — both may be nil.
  ///   No fallback to track gain when album is absent.
  ///
  /// Caller computes `linearGain = pow(10, gainDb! / 20)` and clip-
  /// caps via peak when both are non-nil. When `gainDb` is nil the
  /// caller treats RG as inactive for this track (linearGain = 1.0,
  /// `replayGainActive = false`).
  func selectGain(mode: ReplayGainSelectorMode) -> (gainDb: Double?, peak: Double?) {
    switch mode {
    case .off:
      return (nil, nil)
    case .track:
      return (trackGainDb, trackPeak)
    case .album:
      return (albumGainDb, albumPeak)
    }
  }
}
