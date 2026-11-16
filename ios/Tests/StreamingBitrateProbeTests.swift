import XCTest
@testable import QueuePlayer

/// The probe derives a bitrate from a local file's first 256 KB and reads
/// nothing that is not a `file://` URL.
final class StreamingBitrateProbeTests: XCTestCase {

  func testALocalCbrMp3ReportsItsEncodedBitrate() async throws {
    let url = try PlayerFixtures.fixtureURL(name: "silence-cbr-128", ext: "mp3", for: self)
    let bitrate = await StreamingBitrateProbe.bitrate(forUrl: url)
    XCTAssertEqual(try XCTUnwrap(bitrate), 128_000, accuracy: 128_000 * 0.05)
  }

  func testARemoteUrlIsNotProbed() async throws {
    let url = try XCTUnwrap(URL(string: "http://127.0.0.1:1/\(UUID().uuidString).mp3"))
    let bitrate = await StreamingBitrateProbe.bitrate(forUrl: url)
    XCTAssertNil(bitrate)
  }

  func testAMissingFileReportsNoBitrate() async {
    let url = FileManager.default.temporaryDirectory
      .appendingPathComponent("absent-\(UUID().uuidString).mp3")
    let bitrate = await StreamingBitrateProbe.bitrate(forUrl: url)
    XCTAssertNil(bitrate)
  }
}
