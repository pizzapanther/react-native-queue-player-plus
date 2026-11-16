import AVFoundation
import Foundation

/// iOS-side mapping from raw `NSError` (AVFoundation / NSURLError /
/// CoreMedia / etc.) into the lib-standardized `PlaybackErrorCode`
/// vocabulary. Pure function; no `TrackPlayer` instance state.
///
/// Mirrors `PlaybackErrorMapping.kt` on the Android side. Both
/// implementations consult the standardized code (not the raw native
/// code) to drive the transient/fatal classifier so the
/// retry-eligibility contract is platform-agnostic.
///
/// **Two-tier classifier for AVFoundation `CANNOT_OPEN`:** the user-
/// reported "Cannot Open" string is fired both for genuine transient
/// failures (slow-to-respond server triggering the AVError fallback)
/// and for permanent corruption. We classify CANNOT_OPEN as transient
/// ONLY when the underlying error chain contains an `NSURLErrorDomain`
/// entry (i.e. wrapped network failure); bare AVError -11800 with no
/// network underlying is treated as fatal.
public enum PlaybackErrorMapping {

  /// Classify an `NSError` (or nil for the catch-all "no underlying")
  /// case into the lib-standardized code.
  public static func classify(_ underlying: NSError?) -> PlaybackErrorCode {
    guard let err = underlying else { return .cannotOpen }

    switch err.domain {
    case NSURLErrorDomain:
      return classifyNSURLError(err.code)
    case AVFoundationErrorDomain:
      return classifyAVFoundationError(err)
    default:
      // CoreMediaErrorDomain, kCFErrorDomain, etc. — no specific
      // mapping today; fall through to UNKNOWN so consumers see the
      // raw nativeCode + nativeDomain via the rich PlaybackError
      // shape rather than a misleading lib-standardized code.
      return .unknown
    }
  }

  /// Whether the lib should retry on this code automatically (subject
  /// to `PlayerConfig.autoRetries`). Codec / format / auth errors
  /// never recover; network errors typically do.
  public static func isTransient(_ code: PlaybackErrorCode) -> Bool {
    switch code {
    case .timeout, .connectionLost, .networkUnreachable,
         .sourceUnreachable, .cannotOpen, .unknown:
      return true
    case .httpError, .authError, .cleartextNotPermitted,
         .fileFormatNotRecognized, .decoderNotFound, .decodingFailed:
      return false
    }
  }

  /// Strip query strings from a native error message so logged
  /// `nativeMessage` doesn't carry auth tokens: stream URLs with
  /// `?token=…`-style query strings end up inside
  /// `localizedDescription` strings, and every `?…` run is removed.
  ///
  /// Walks the entire message, removing every `?…(whitespace|end)`
  /// run so a multi-URL error message ("redirect from
  /// `https://a.com/x?token=A` failed; trying
  /// `https://b.com/y?token=B` ...") has BOTH query strings stripped.
  @_spi(QueuePlayerTests)
  public static func stripQuery(_ message: String) -> String {
    var result = ""
    var idx = message.startIndex
    while idx < message.endIndex {
      if message[idx] == "?" {
        // Find next whitespace (or end) and skip the run.
        idx = message[idx...].firstIndex(where: { $0.isWhitespace }) ?? message.endIndex
      } else {
        result.append(message[idx])
        idx = message.index(after: idx)
      }
    }
    return result
  }

  // MARK: - String-based "Raw" surface
  //
  // NOTES.md section 6: Swift's `-cxx-interoperability-mode=default` hides
  // members of functions whose signatures touch Nitrogen-generated
  // C++-backed types when viewed from a different Swift module.
  // `classify` / `isTransient` reference `PlaybackErrorCode` — a
  // Nitrogen type — so a different module can't reach them directly.
  // This parallel surface takes/returns Strings (the enum's
  // stringValue). Production code uses the typed surface above.

  /// String-returning variant of `classify`.
  @_spi(QueuePlayerTests)
  public static func classifyRaw(_ underlying: NSError?) -> String {
    return classify(underlying).stringValue
  }

  /// String-input variant of `isTransient`.
  @_spi(QueuePlayerTests)
  public static func isTransientRaw(_ codeString: String) -> Bool {
    guard let code = PlaybackErrorCode(fromString: codeString) else { return false }
    return isTransient(code)
  }

  /// Test-only string-input variant of `messageFor`.
  @_spi(QueuePlayerTests)
  public static func messageForRaw(_ codeString: String) -> String {
    guard let code = PlaybackErrorCode(fromString: codeString) else { return "" }
    return messageFor(code)
  }

  /// Lib-mapped human-readable message for a standardized code. Used
  /// when the consumer doesn't want to render the raw native message
  /// (which can be platform-jargon-heavy). Consistent across iOS +
  /// Android.
  public static func messageFor(_ code: PlaybackErrorCode) -> String {
    switch code {
    case .timeout: return "Network request timed out."
    case .connectionLost: return "Network connection lost."
    case .networkUnreachable: return "Network unreachable."
    case .httpError: return "Server returned an error response."
    case .authError: return "Authentication required or rejected."
    case .cleartextNotPermitted: return "Cleartext HTTP not permitted by app transport policy."
    case .fileFormatNotRecognized: return "Audio format not recognized."
    case .decoderNotFound: return "No decoder available for this audio format."
    case .decodingFailed: return "Audio decoder failure."
    case .sourceUnreachable: return "Audio source could not be reached."
    case .cannotOpen: return "Audio source could not be opened."
    case .unknown: return "Playback failed."
    }
  }

  // MARK: - Private mapping helpers

  private static func classifyNSURLError(_ code: Int) -> PlaybackErrorCode {
    switch code {
    case NSURLErrorTimedOut:
      return .timeout
    case NSURLErrorNotConnectedToInternet,
         NSURLErrorDNSLookupFailed,
         NSURLErrorCannotFindHost,
         NSURLErrorInternationalRoamingOff,
         NSURLErrorDataNotAllowed,
         NSURLErrorCallIsActive:
      return .networkUnreachable
    case NSURLErrorNetworkConnectionLost,
         NSURLErrorCannotConnectToHost,
         NSURLErrorRequestBodyStreamExhausted:
      return .connectionLost
    case NSURLErrorBadURL,
         NSURLErrorUnsupportedURL,
         NSURLErrorFileDoesNotExist,
         NSURLErrorFileIsDirectory:
      return .sourceUnreachable
    case NSURLErrorAppTransportSecurityRequiresSecureConnection:
      return .cleartextNotPermitted
    case NSURLErrorUserAuthenticationRequired,
         NSURLErrorClientCertificateRequired:
      return .authError
    default:
      return .unknown
    }
  }

  private static func classifyAVFoundationError(_ err: NSError) -> PlaybackErrorCode {
    // Raw AVError code constants from <AVFoundation/AVError.h>. Using
    // raw Ints (not AVError.Code rawValues) because Swift overlay
    // case-name coverage varies across SDK versions; these constants
    // are stable + documented.
    switch err.code {
    case -11828,  // AVErrorFileFormatNotRecognized
         -11829,  // AVErrorFailedToParse
         -11852:  // AVErrorContentIsNotAuthorized
      return .fileFormatNotRecognized
    case -11833,  // AVErrorDecoderNotFound
         -11839:  // AVErrorDecoderTemporarilyUnavailable
      return .decoderNotFound
    case -11800,  // AVErrorUnknown (generic decoding fall-through)
         -11849:  // AVErrorContentIsProtected
      return .decodingFailed
    case -11867,  // AVErrorNoLongerPlayable
         -11819:  // AVErrorMediaServicesWereReset
      return .cannotOpen
    default:
      // Two-tier classifier: bare AVError without a wrapped
      // NSURLErrorDomain underlying is fatal (e.g. real corruption).
      // AVError WITH a network underlying surfaces as transient
      // CANNOT_OPEN so auto-retry can handle slow-server cases.
      if let inner = err.userInfo[NSUnderlyingErrorKey] as? NSError,
         inner.domain == NSURLErrorDomain {
        return .cannotOpen  // transient classifier in `isTransient`
      }
      return .unknown
    }
  }
}
