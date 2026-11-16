import XCTest
@testable import QueuePlayer

/// A re-issued origin transfer repeats bytes the client already has; the
/// stitcher drops exactly those and refuses an answer that changed.
final class OriginRestartStitcherTests: XCTestCase {

  private func head(
    status: Int = 200, length: String? = "1500", etag: String? = nil
  ) -> HTTPURLResponse {
    var headers: [String: String] = [:]
    if let length { headers["Content-Length"] = length }
    if let etag { headers["ETag"] = etag }
    return HTTPURLResponse(
      url: URL(string: "http://127.0.0.1:1/a.mp3")!, statusCode: status,
      httpVersion: "HTTP/1.1", headerFields: headers)!
  }

  func testAFirstTransferPassesThrough() {
    var stitcher = OriginRestartStitcher()
    XCTAssertEqual(stitcher.head(head()), .accept)
    XCTAssertEqual(stitcher.body(Data(repeating: 1, count: 1000)).count, 1000)
    XCTAssertEqual(stitcher.deliveredBytes, 1000)
  }

  func testARestartRepeatsOnlyWhatWasNotDelivered() {
    var stitcher = OriginRestartStitcher()
    _ = stitcher.head(head())
    _ = stitcher.body(Data(repeating: 1, count: 1000))

    XCTAssertEqual(stitcher.head(head()), .accept, "the same answer is stitched")
    let restarted = Data((0 ..< 1500).map { UInt8($0 % 251) })
    let passed = stitcher.body(restarted)

    XCTAssertEqual(passed.count, 500)
    XCTAssertTrue(passed.elementsEqual(restarted[1000...]), "the tail past the delivered bytes")
    XCTAssertEqual(stitcher.deliveredBytes, 1500)
  }

  /// The repeated bytes can arrive across several chunks.
  func testTheSkipSpansChunks() {
    var stitcher = OriginRestartStitcher()
    _ = stitcher.head(head())
    _ = stitcher.body(Data(repeating: 1, count: 1000))
    _ = stitcher.head(head())

    XCTAssertTrue(stitcher.body(Data(repeating: 2, count: 400)).isEmpty)
    XCTAssertTrue(stitcher.body(Data(repeating: 2, count: 600)).isEmpty)
    XCTAssertEqual(stitcher.body(Data(repeating: 3, count: 100)).count, 100)
    XCTAssertEqual(stitcher.deliveredBytes, 1100)
  }

  func testADifferentAnswerIsRefused() {
    var stitcher = OriginRestartStitcher()
    _ = stitcher.head(head(etag: "\"v1\""))
    XCTAssertEqual(stitcher.head(head(etag: "\"v2\"")), .reject, "a changed ETag is a different resource")
    XCTAssertEqual(stitcher.head(head(length: "2000")), .reject, "a changed length too")
    XCTAssertEqual(stitcher.head(head(status: 206)), .reject, "and a changed status")
  }
}
