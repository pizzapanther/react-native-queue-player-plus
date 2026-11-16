import XCTest
@testable import QueuePlayer

/// Covers the pure request-parse + `Range` resolution + response-head
/// builders behind the cast file server. The `NWListener` / connection
/// I/O is exercised by serving to a real receiver (integration); these
/// assert the byte-range math the receiver's seek + probe depend on.
final class MediaHTTPConnectionTests: XCTestCase {

  private let token = "0123456789abcdef0123456789abcdef"

  // MARK: - Range resolution

  func testNoRangeHeaderServesFull() {
    XCTAssertEqual(MediaHTTPConnection.resolveRange(nil, fileSize: 1000), .full)
    XCTAssertEqual(MediaHTTPConnection.resolveRange("", fileSize: 1000), .full)
  }

  func testClosedRange() {
    XCTAssertEqual(
      MediaHTTPConnection.resolveRange("bytes=0-99", fileSize: 1000),
      .partial(start: 0, end: 99)
    )
    XCTAssertEqual(
      MediaHTTPConnection.resolveRange("bytes=200-499", fileSize: 1000),
      .partial(start: 200, end: 499)
    )
  }

  func testOpenEndedRangeRunsToLastByte() {
    XCTAssertEqual(
      MediaHTTPConnection.resolveRange("bytes=0-", fileSize: 1000),
      .partial(start: 0, end: 999)
    )
    XCTAssertEqual(
      MediaHTTPConnection.resolveRange("bytes=500-", fileSize: 1000),
      .partial(start: 500, end: 999)
    )
  }

  func testSuffixRangeIsLastNBytes() {
    XCTAssertEqual(
      MediaHTTPConnection.resolveRange("bytes=-100", fileSize: 1000),
      .partial(start: 900, end: 999)
    )
  }

  func testSuffixLargerThanFileClampsToWholeFile() {
    XCTAssertEqual(
      MediaHTTPConnection.resolveRange("bytes=-5000", fileSize: 1000),
      .partial(start: 0, end: 999)
    )
  }

  func testEndPastEofClampsToLastByte() {
    XCTAssertEqual(
      MediaHTTPConnection.resolveRange("bytes=0-99999", fileSize: 1000),
      .partial(start: 0, end: 999)
    )
  }

  func testStartAtOrPastEofIsUnsatisfiable() {
    XCTAssertEqual(MediaHTTPConnection.resolveRange("bytes=1000-", fileSize: 1000), .unsatisfiable)
    XCTAssertEqual(MediaHTTPConnection.resolveRange("bytes=1500-2000", fileSize: 1000), .unsatisfiable)
  }

  func testZeroSuffixIsUnsatisfiable() {
    XCTAssertEqual(MediaHTTPConnection.resolveRange("bytes=-0", fileSize: 1000), .unsatisfiable)
  }

  func testInvertedOrMalformedRangeIsIgnored() {
    XCTAssertEqual(MediaHTTPConnection.resolveRange("bytes=500-100", fileSize: 1000), .full)
    XCTAssertEqual(MediaHTTPConnection.resolveRange("bytes=abc", fileSize: 1000), .full)
    XCTAssertEqual(MediaHTTPConnection.resolveRange("kbytes=0-1", fileSize: 1000), .full)
  }

  func testEmptyFileServesFull() {
    XCTAssertEqual(MediaHTTPConnection.resolveRange("bytes=0-10", fileSize: 0), .full)
  }

  func testMultiRangeResolvesToFirst() {
    XCTAssertEqual(
      MediaHTTPConnection.resolveRange("bytes=0-10,20-30", fileSize: 1000),
      .partial(start: 0, end: 10)
    )
  }

  func testRangeProbeFirstByte() {
    // The receiver's `bytes=0-1` reachability probe.
    XCTAssertEqual(
      MediaHTTPConnection.resolveRange("bytes=0-1", fileSize: 1000),
      .partial(start: 0, end: 1)
    )
  }

  // MARK: - Request parsing

  private func request(_ raw: String) -> MediaHTTPRequest? {
    MediaHTTPRequest(headerData: Data(raw.utf8))
  }

  func testParsesMethodPathAndRange() {
    let req = request("GET /media/\(token) HTTP/1.1\r\nHost: 10.0.0.2:8080\r\nRange: bytes=0-1\r\n")
    XCTAssertEqual(req?.method, "GET")
    XCTAssertEqual(req?.path, "/media/\(token)")
    XCTAssertEqual(req?.rangeHeader, "bytes=0-1")
    XCTAssertEqual(req?.mediaToken, token)
  }

  func testMethodIsUppercased() {
    XCTAssertEqual(request("head /media/\(token) HTTP/1.1\r\n")?.method, "HEAD")
  }

  func testQueryStringStrippedFromPath() {
    let req = request("GET /media/\(token)?x=1 HTTP/1.1\r\n")
    XCTAssertEqual(req?.path, "/media/\(token)")
    XCTAssertEqual(req?.mediaToken, token)
  }

  func testNonMediaPathHasNoToken() {
    XCTAssertNil(request("GET /something/else HTTP/1.1\r\n")?.mediaToken)
    XCTAssertNil(request("GET / HTTP/1.1\r\n")?.mediaToken)
  }

  func testWrongLengthTokenRejected() {
    XCTAssertNil(request("GET /media/deadbeef HTTP/1.1\r\n")?.mediaToken)
  }

  func testUppercaseHexTokenRejected() {
    XCTAssertNil(request("GET /media/0123456789ABCDEF0123456789ABCDEF HTTP/1.1\r\n")?.mediaToken)
  }

  func testMissingRangeHeaderIsNil() {
    XCTAssertNil(request("GET /media/\(token) HTTP/1.1\r\nHost: x\r\n")?.rangeHeader)
  }

  // MARK: - Response heads

  func testFullHeadAdvertisesLengthAndRanges() {
    let head = MediaHTTPConnection.headFull(contentType: "audio/mpeg", fileSize: 4096)
    XCTAssertTrue(head.hasPrefix("HTTP/1.1 200 OK\r\n"))
    XCTAssertTrue(head.contains("Content-Type: audio/mpeg\r\n"))
    XCTAssertTrue(head.contains("Content-Length: 4096\r\n"))
    XCTAssertTrue(head.contains("Accept-Ranges: bytes\r\n"))
    XCTAssertTrue(head.hasSuffix("Connection: close\r\n\r\n"))
  }

  func testPartialHeadCarriesContentRangeAndLength() {
    let head = MediaHTTPConnection.headPartial(
      contentType: "audio/mpeg", start: 100, end: 199, fileSize: 4096
    )
    XCTAssertTrue(head.hasPrefix("HTTP/1.1 206 Partial Content\r\n"))
    XCTAssertTrue(head.contains("Content-Range: bytes 100-199/4096\r\n"))
    XCTAssertTrue(head.contains("Content-Length: 100\r\n"))
  }

  func testUnsatisfiableHeadCarriesStarRange() {
    let head = MediaHTTPConnection.headUnsatisfiable(fileSize: 4096)
    XCTAssertTrue(head.hasPrefix("HTTP/1.1 416 Range Not Satisfiable\r\n"))
    XCTAssertTrue(head.contains("Content-Range: bytes */4096\r\n"))
    XCTAssertTrue(head.contains("Content-Length: 0\r\n"))
  }
}

/// The head the read-through leg relays from the origin to the player.
///
/// The player is AVFoundation reading media: it needs the status, the type, the
/// length and the range, and nothing else the origin chose to say.
final class MediaHTTPRelayHeadTests: XCTestCase {

  private func response(
    status: Int,
    headers: [String: String]
  ) -> HTTPURLResponse {
    HTTPURLResponse(
      url: URL(string: "https://example.com/track.mp3")!,
      statusCode: status,
      httpVersion: "HTTP/1.1",
      headerFields: headers
    )!
  }

  func testRelaysTheOriginStatusLine() {
    let head = MediaHTTPConnection.relayHead(response(status: 206, headers: [:]))
    XCTAssertTrue(head.hasPrefix("HTTP/1.1 206 Partial Content\r\n"), head)
  }

  /// The status line stays ASCII whatever the device locale; an unlisted
  /// status carries an empty reason phrase, which HTTP/1.1 permits.
  func testTheReasonPhraseIsFixedTextNotLocalisedText() {
    XCTAssertEqual(MediaHTTPConnection.reasonPhrase(for: 404), "Not Found")
    XCTAssertEqual(MediaHTTPConnection.reasonPhrase(for: 418), "")
    let head = MediaHTTPConnection.relayHead(response(status: 418, headers: [:]))
    XCTAssertTrue(head.hasPrefix("HTTP/1.1 418 \r\n"), head)
  }

  /// An HTTP error reaches the player as an HTTP error, so it classifies as the
  /// server's answer rather than as a transport failure.
  func testRelaysAnErrorStatusRatherThanMaskingIt() {
    let head = MediaHTTPConnection.relayHead(response(status: 404, headers: [:]))
    XCTAssertTrue(head.hasPrefix("HTTP/1.1 404 "), head)
  }

  func testCarriesTheHeadersThePlayerReadsMediaWith() {
    let head = MediaHTTPConnection.relayHead(response(status: 206, headers: [
      "Content-Type": "audio/mpeg",
      "Content-Length": "1024",
      "Content-Range": "bytes 0-1023/8192",
      "Accept-Ranges": "bytes",
    ]))
    XCTAssertTrue(head.contains("Content-Type: audio/mpeg"), head)
    XCTAssertTrue(head.contains("Content-Length: 1024"), head)
    XCTAssertTrue(head.contains("Content-Range: bytes 0-1023/8192"), head)
    XCTAssertTrue(head.contains("Accept-Ranges: bytes"), head)
  }

  func testDropsEverythingElseTheOriginSaid() {
    let head = MediaHTTPConnection.relayHead(response(status: 200, headers: [
      "Content-Type": "audio/mpeg",
      "Set-Cookie": "session=secret",
      "WWW-Authenticate": "Bearer realm=\"origin\"",
      "Location": "https://elsewhere.example.com/track.mp3",
      "Server": "origin/1.0",
    ]))
    XCTAssertFalse(head.lowercased().contains("set-cookie"), head)
    XCTAssertFalse(head.lowercased().contains("www-authenticate"), head)
    XCTAssertFalse(head.lowercased().contains("location"), head)
    XCTAssertFalse(head.lowercased().contains("server:"), head)
  }

  func testHeadIsTerminatedSoABodyCanFollow() {
    let head = MediaHTTPConnection.relayHead(response(status: 200, headers: ["Content-Length": "1"]))
    XCTAssertTrue(head.hasSuffix("\r\n\r\n"), head.debugDescription)
  }

  /// An absent header is omitted rather than sent empty — a `Content-Range: `
  /// with no value is a malformed response to a range request.
  func testOmitsHeadersTheOriginDidNotSend() {
    let head = MediaHTTPConnection.relayHead(response(status: 200, headers: ["Content-Type": "audio/mpeg"]))
    XCTAssertFalse(head.contains("Content-Range"), head)
  }
}
