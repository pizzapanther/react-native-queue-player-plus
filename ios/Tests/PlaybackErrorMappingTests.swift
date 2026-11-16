import XCTest
@testable import QueuePlayer
@_spi(QueuePlayerTests) import QueuePlayer
import AVFoundation

/// Unit tests for the iOS half of the cross-platform error-code
/// mapping in `PlaybackErrorMapping.swift`.
///
/// **Why string-keyed assertions?** NOTES.md section 6 — Swift's
/// `-cxx-interoperability-mode=default` hides members of functions
/// whose signatures touch Nitrogen-generated C++-backed types
/// (PlaybackErrorCode is one) when viewed from a separate test
/// module. The lib exposes a `*Raw` surface returning Strings as a
/// workaround; tests assert on the SCREAMING_SNAKE_CASE string
/// values from `PlaybackErrorCode.stringValue`.
final class PlaybackErrorMappingTests: XCTestCase {

  // MARK: - classifyRaw(NSError?)

  func testClassifyNilUnderlyingReturnsCannotOpen() {
    // When `item.error` is nil (e.g. AVPlayerItem reached `.failed`
    // without a downstream NSError), classify falls back to the
    // user-visible "Cannot Open" code. Treated as transient because
    // the overwhelmingly common cause in the wild is a slow-spin-up
    // server, not corruption.
    XCTAssertEqual(PlaybackErrorMapping.classifyRaw(nil), "CANNOT_OPEN")
  }

  func testClassifyNSURLErrorTimedOutMapsToTimeout() {
    let err = NSError(domain: NSURLErrorDomain, code: NSURLErrorTimedOut)
    XCTAssertEqual(PlaybackErrorMapping.classifyRaw(err), "TIMEOUT")
  }

  func testClassifyNSURLErrorNotConnectedMapsToNetworkUnreachable() {
    let err = NSError(domain: NSURLErrorDomain, code: NSURLErrorNotConnectedToInternet)
    XCTAssertEqual(PlaybackErrorMapping.classifyRaw(err), "NETWORK_UNREACHABLE")
  }

  func testClassifyNSURLErrorConnectionLostMapsToConnectionLost() {
    let err = NSError(domain: NSURLErrorDomain, code: NSURLErrorNetworkConnectionLost)
    XCTAssertEqual(PlaybackErrorMapping.classifyRaw(err), "CONNECTION_LOST")
  }

  func testClassifyNSURLErrorBadURLMapsToSourceUnreachable() {
    let err = NSError(domain: NSURLErrorDomain, code: NSURLErrorBadURL)
    XCTAssertEqual(PlaybackErrorMapping.classifyRaw(err), "SOURCE_UNREACHABLE")
  }

  func testClassifyNSURLErrorATSMapsToCleartextNotPermitted() {
    let err = NSError(
      domain: NSURLErrorDomain,
      code: NSURLErrorAppTransportSecurityRequiresSecureConnection
    )
    XCTAssertEqual(PlaybackErrorMapping.classifyRaw(err), "CLEARTEXT_NOT_PERMITTED")
  }

  func testClassifyNSURLErrorAuthMapsToAuthError() {
    let err = NSError(domain: NSURLErrorDomain, code: NSURLErrorUserAuthenticationRequired)
    XCTAssertEqual(PlaybackErrorMapping.classifyRaw(err), "AUTH_ERROR")
  }

  func testClassifyAVErrorFileFormatNotRecognized() {
    let err = NSError(domain: AVFoundationErrorDomain, code: -11828)
    XCTAssertEqual(PlaybackErrorMapping.classifyRaw(err), "FILE_FORMAT_NOT_RECOGNIZED")
  }

  func testClassifyAVErrorDecoderNotFound() {
    let err = NSError(domain: AVFoundationErrorDomain, code: -11833)
    XCTAssertEqual(PlaybackErrorMapping.classifyRaw(err), "DECODER_NOT_FOUND")
  }

  func testClassifyAVErrorMediaServicesWereResetMapsToCannotOpen() {
    let err = NSError(domain: AVFoundationErrorDomain, code: -11819)
    XCTAssertEqual(PlaybackErrorMapping.classifyRaw(err), "CANNOT_OPEN")
  }

  func testClassifyAVErrorWithNSURLUnderlyingMapsToCannotOpenTransient() {
    // Two-tier classifier: bare unknown AVError IS fatal (.unknown);
    // wrapped network underlying is transient (.cannotOpen).
    let inner = NSError(domain: NSURLErrorDomain, code: NSURLErrorTimedOut)
    let outer = NSError(
      domain: AVFoundationErrorDomain,
      code: -99999,  // unknown AVError code
      userInfo: [NSUnderlyingErrorKey: inner]
    )
    XCTAssertEqual(
      PlaybackErrorMapping.classifyRaw(outer), "CANNOT_OPEN",
      "AVError with NSURL underlying must map to transient CANNOT_OPEN"
    )
    XCTAssertTrue(
      PlaybackErrorMapping.isTransientRaw("CANNOT_OPEN"),
      "CANNOT_OPEN must be classified transient so auto-retry kicks in"
    )
  }

  func testClassifyBareUnknownAVErrorIsUnknown() {
    let err = NSError(domain: AVFoundationErrorDomain, code: -99999)
    XCTAssertEqual(PlaybackErrorMapping.classifyRaw(err), "UNKNOWN")
  }

  func testClassifyOtherDomainReturnsUnknown() {
    let err = NSError(domain: "CoreMediaErrorDomain", code: 12345)
    XCTAssertEqual(PlaybackErrorMapping.classifyRaw(err), "UNKNOWN")
  }

  // MARK: - isTransientRaw — drives auto-retry eligibility

  func testIsTransientFlagsNetworkClass() {
    XCTAssertTrue(PlaybackErrorMapping.isTransientRaw("TIMEOUT"))
    XCTAssertTrue(PlaybackErrorMapping.isTransientRaw("CONNECTION_LOST"))
    XCTAssertTrue(PlaybackErrorMapping.isTransientRaw("NETWORK_UNREACHABLE"))
    XCTAssertTrue(PlaybackErrorMapping.isTransientRaw("SOURCE_UNREACHABLE"))
    XCTAssertTrue(PlaybackErrorMapping.isTransientRaw("CANNOT_OPEN"))
    XCTAssertTrue(PlaybackErrorMapping.isTransientRaw("UNKNOWN"))
  }

  func testIsTransientFlagsCodecClassFatal() {
    XCTAssertFalse(PlaybackErrorMapping.isTransientRaw("FILE_FORMAT_NOT_RECOGNIZED"))
    XCTAssertFalse(PlaybackErrorMapping.isTransientRaw("DECODER_NOT_FOUND"))
    XCTAssertFalse(PlaybackErrorMapping.isTransientRaw("DECODING_FAILED"))
  }

  func testIsTransientFlagsAuthAndCleartextFatal() {
    XCTAssertFalse(PlaybackErrorMapping.isTransientRaw("HTTP_ERROR"))
    XCTAssertFalse(PlaybackErrorMapping.isTransientRaw("AUTH_ERROR"))
    XCTAssertFalse(PlaybackErrorMapping.isTransientRaw("CLEARTEXT_NOT_PERMITTED"))
  }

  // MARK: - stripQuery — PII safe

  func testStripQueryRemovesQueryString() {
    let raw = "Source error at https://server.example/stream?u=user&t=token rejected"
    let stripped = PlaybackErrorMapping.stripQuery(raw)
    XCTAssertFalse(stripped.contains("u=user"))
    XCTAssertFalse(stripped.contains("t=token"))
    XCTAssertTrue(stripped.contains("https://server.example/stream"))
    XCTAssertTrue(stripped.contains("rejected"))
  }

  func testStripQueryNoOpWhenNoQueryString() {
    let raw = "decoder failed: codec MP3 not available"
    XCTAssertEqual(PlaybackErrorMapping.stripQuery(raw), raw)
  }

  func testStripQueryHandlesQueryAtEnd() {
    let raw = "https://example.com/x?a=1&b=2"
    let stripped = PlaybackErrorMapping.stripQuery(raw)
    XCTAssertEqual(stripped, "https://example.com/x")
  }

  // MARK: - messageForRaw — lib-mapped human-readable

  func testMessageForReturnsConsistentStrings() {
    // Spot-check that every code maps to a non-empty, sentence-case
    // message ending with a period. Cross-platform consumers compare
    // these against localized strings; empty / capitalisation drift
    // would surface as inconsistent UX.
    for codeString in [
      "TIMEOUT", "CONNECTION_LOST", "NETWORK_UNREACHABLE",
      "HTTP_ERROR", "AUTH_ERROR", "CLEARTEXT_NOT_PERMITTED",
      "FILE_FORMAT_NOT_RECOGNIZED", "DECODER_NOT_FOUND", "DECODING_FAILED",
      "SOURCE_UNREACHABLE", "CANNOT_OPEN", "UNKNOWN"
    ] {
      let message = PlaybackErrorMapping.messageForRaw(codeString)
      XCTAssertFalse(message.isEmpty, "messageForRaw(\(codeString)) must not be empty")
      XCTAssertTrue(
        message.first?.isUppercase ?? false,
        "messageForRaw(\(codeString)) should be sentence-case"
      )
      XCTAssertTrue(
        message.hasSuffix("."),
        "messageForRaw(\(codeString)) should end with a period"
      )
    }
  }
}
