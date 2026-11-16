import Foundation

/// Failure cases from AVAudioSession lifecycle calls. The
/// `nativeDomain` strings are stable SCREAMING_SNAKE_CASE codes
/// surfaced to JS via `PlaybackError.nativeDomain`; consumers may
/// branch on `nativeDomain.startsWith("AUDIO_SESSION_")` to detect
/// session-scope errors (vs player-scope errors carried via
/// `Media3:` / `AVFoundationErrorDomain`). The casing convention
/// matches Android's Media3 `errorCodeName` so JS consumers can
/// branch on `err.code` cross-platform.
internal enum AudioSessionError {
  case setCategoryFailed(message: String, nativeCode: Int)
  case setActiveFailed(message: String, nativeCode: Int)
  case deactivateFailed(message: String, nativeCode: Int)

  var nativeDomain: String {
    switch self {
    case .setCategoryFailed: return "AUDIO_SESSION_SET_CATEGORY_FAILED"
    case .setActiveFailed: return "AUDIO_SESSION_SET_ACTIVE_FAILED"
    case .deactivateFailed: return "AUDIO_SESSION_DEACTIVATE_FAILED"
    }
  }

  var message: String {
    switch self {
    case .setCategoryFailed(let m, _),
         .setActiveFailed(let m, _),
         .deactivateFailed(let m, _):
      return m
    }
  }

  /// AVAudioSession's underlying `NSError.code`. Mapped onto
  /// `PlaybackError.nativeCode` so JS consumers can distinguish e.g.
  /// `kAudioSessionNotInitialized` from a category-config rejection.
  var nativeCode: Int {
    switch self {
    case .setCategoryFailed(_, let c),
         .setActiveFailed(_, let c),
         .deactivateFailed(_, let c):
      return c
    }
  }
}
