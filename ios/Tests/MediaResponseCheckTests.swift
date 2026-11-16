import XCTest
@testable import QueuePlayer

/// What the cache will and will not believe is a track, judged from a
/// response's declared type and the first bytes of its body.
final class MediaResponseCheckTests: XCTestCase {

  private let xmlEnvelope = Data("""
    <api-response status="failed"><error code="70"/></api-response>
    """.utf8)
  private let mp3 = Data([0x49, 0x44, 0x33, 0x04, 0x00, 0x00, 0x00, 0x00])  // "ID3"

  private func rejection(
    _ contentType: String?, _ body: Data, whole: Bool = true
  ) -> MediaResponseCheck.Rejection? {
    MediaResponseCheck.rejection(
      contentType: contentType, head: body, headIsStartOfResource: whole)
  }

  // MARK: - A declared document is refused whatever its body

  func testMarkupAndJsonContentTypesAreRefused() {
    for type in [
      "application/xml", "text/xml", "text/html",
      "application/xhtml+xml", "application/json", "text/json",
    ] {
      XCTAssertEqual(
        rejection(type, xmlEnvelope), .markupContentType(type),
        "\(type) is a document type")
    }
  }

  func testTheDeclaredTypeIsReadWithoutItsParameters() {
    XCTAssertEqual(
      rejection("application/xml; charset=utf-8", xmlEnvelope),
      .markupContentType("application/xml"))
    XCTAssertEqual(rejection("APPLICATION/XML", xmlEnvelope), .markupContentType("application/xml"))
  }

  /// A ranged request answers from an arbitrary offset, so only the declared
  /// type can be judged — but that much still holds.
  func testADeclaredDocumentIsRefusedOnAPartialBodyToo() {
    XCTAssertEqual(
      rejection("application/xml", Data([0x3C, 0x00]), whole: false),
      .markupContentType("application/xml"))
  }

  func testAPartialBodyIsNotJudgedByItsLeadingBytes() {
    // The same markup, under a type that is not itself a document, so only the
    // leading-byte rule can decide: refused as a whole resource, accepted as a
    // range, which is what separates the two inputs.
    let markup = Data("<api-response/>".utf8)
    XCTAssertEqual(
      rejection("application/octet-stream", markup),
      .markupBody)
    XCTAssertNil(
      rejection("application/octet-stream", markup, whole: false),
      "a body that begins part way into a file says nothing about its first bytes")
  }

  // MARK: - Plain text has to prove itself

  func testTextWithoutAContainerSignatureIsRefused() {
    XCTAssertEqual(
      rejection("text/plain", Data("not found".utf8)),
      .textWithoutMediaSignature("text/plain"))
  }

  func testTextCarryingAContainerSignatureIsAccepted() {
    XCTAssertNil(
      rejection("text/plain", mp3),
      "a server mislabelling audio as text does not make it a document")
  }

  func testAPlaylistDeclaredAsTextIsAccepted() {
    XCTAssertNil(rejection("text/plain", Data("#EXTM3U\n#EXT-X-VERSION:3".utf8)))
  }

  // MARK: - An unusable declared type falls to the body

  func testAMarkupBodyIsRefusedWhenNothingUsableIsDeclared() {
    XCTAssertEqual(rejection(nil, xmlEnvelope), .markupBody)
    XCTAssertEqual(rejection("application/octet-stream", xmlEnvelope), .markupBody)
    XCTAssertEqual(rejection(nil, Data("<!DOCTYPE html><html>".utf8)), .markupBody)
    XCTAssertEqual(rejection(nil, Data("{\"error\":\"not found\"}".utf8)), .markupBody)
  }

  func testLeadingSpaceAndAByteOrderMarkDoNotHideAMarkupBody() {
    XCTAssertEqual(rejection(nil, Data("\n  <?xml version=\"1.0\"?>".utf8)), .markupBody)
    XCTAssertEqual(
      rejection(nil, Data([0xEF, 0xBB, 0xBF]) + Data("<error/>".utf8)), .markupBody)
    // `FF FE` also satisfies the MPEG frame sync, so a UTF-16 document would
    // read as audio if the mark were not dropped first.
    XCTAssertEqual(
      rejection(nil, Data([0xFF, 0xFE]) + Data("<e/>".utf8)), .markupBody)
  }

  // MARK: - Real media is accepted, however it is labelled

  func testRecognisedContainersAreAccepted() {
    let bodies: [String: [UInt8]] = [
      "ID3": [0x49, 0x44, 0x33, 0x04, 0, 0, 0, 0],
      "MPEG frame sync": [0xFF, 0xFB, 0x90, 0x00, 0, 0, 0, 0],
      "ADTS AAC": [0xFF, 0xF1, 0x50, 0x80, 0, 0, 0, 0],
      "FLAC": [0x66, 0x4C, 0x61, 0x43, 0, 0, 0, 0],
      "Ogg": [0x4F, 0x67, 0x67, 0x53, 0, 0, 0, 0],
      "WAV": [0x52, 0x49, 0x46, 0x46, 0, 0, 0, 0],
      "AIFF": [0x46, 0x4F, 0x52, 0x4D, 0, 0, 0, 0],
      "CAF": [0x63, 0x61, 0x66, 0x66, 0, 0, 0, 0],
      "MP4": [0, 0, 0, 0x20, 0x66, 0x74, 0x79, 0x70],
    ]
    for (name, bytes) in bodies {
      XCTAssertNil(rejection("text/plain", Data(bytes)), "\(name) is media")
      XCTAssertNil(rejection(nil, Data(bytes)), "\(name) is media")
    }
  }

  func testAnUndeclaredOrOpaqueBinaryBodyIsAccepted() {
    let binary = Data([0x00, 0x01, 0x02, 0x03, 0x04])
    XCTAssertNil(rejection(nil, binary), "an unrecognised container is not a document")
    XCTAssertNil(rejection("application/octet-stream", binary))
    XCTAssertNil(rejection("audio/mpeg", binary))
  }

  func testAnEmptyBodyIsNotJudged() {
    XCTAssertNil(rejection(nil, Data()))
  }
}
