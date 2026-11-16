import XCTest
@testable import QueuePlayer

/// The FLAC STREAMINFO parse, the only source of a local FLAC bitrate.
final class FLACStreamInfoTests: XCTestCase {


  func testFLACStreamInfoExtractsSampleRateAndTotalSamples() throws {
    // Build a STREAMINFO-only FLAC header.
    // sampleRate=44100, channels=2, bitsPerSample=16, totalSamples=1234567
    let info = makeFLACStreamInfo(
      sampleRate: 44100,
      channels: 2,
      bitsPerSample: 16,
      totalSamples: 1_234_567
    )
    let parsed = try XCTUnwrap(FLACStreamInfo.parse(info))
    XCTAssertEqual(parsed.sampleRate, 44100)
    XCTAssertEqual(parsed.channelCount, 2)
    XCTAssertEqual(parsed.bitsPerSample, 16)
    XCTAssertEqual(parsed.totalSamples, 1_234_567)
  }

  func testFLACStreamInfoHighSampleRate() throws {
    // foobar2000 + some lossless masters use 96 kHz / 24-bit. Verify
    // the 20-bit sampleRate field isn't truncated.
    let info = makeFLACStreamInfo(
      sampleRate: 96000,
      channels: 2,
      bitsPerSample: 24,
      totalSamples: 9_876_543
    )
    let parsed = try XCTUnwrap(FLACStreamInfo.parse(info))
    XCTAssertEqual(parsed.sampleRate, 96000)
    XCTAssertEqual(parsed.bitsPerSample, 24)
    XCTAssertEqual(parsed.totalSamples, 9_876_543)
  }

  func testFLACStreamInfoReturnsNilOnNonFLACBytes() {
    XCTAssertNil(FLACStreamInfo.parse(
      Data([0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00])
    ))
  }

  // MARK: - Test helpers — byte-level fixture builders

  private func makeFLACStreamInfo(
    sampleRate: UInt32,
    channels: Int,
    bitsPerSample: Int,
    totalSamples: Int64
  ) -> Data {
    var data = Data()
    data.append("fLaC".data(using: .ascii)!)
    // Block header: last-block flag (0x80) + type 0 (STREAMINFO) +
    // 24-bit size = 34 bytes.
    data.append(0x80)
    data.append(beUInt24(34))
    // Body. 34 bytes total.
    var body = Data(repeating: 0, count: 34)
    // minBlockSize, maxBlockSize, minFrameSize, maxFrameSize: bytes
    // 0..9, leave zero. Bytes 10..12 contain the 20-bit sampleRate
    // followed by 3-bit channels-1 and 5-bit bitsPerSample-1 split
    // across bytes 12..13. totalSamples is 36 bits at bytes 13..17.
    let sr = UInt32(sampleRate) & 0x000FFFFF
    let chMinusOne = UInt8((channels - 1) & 0x07)
    let bpsMinusOne = UInt8((bitsPerSample - 1) & 0x1F)
    body[10] = UInt8((sr >> 12) & 0xFF)
    body[11] = UInt8((sr >> 4) & 0xFF)
    body[12] = UInt8(((sr & 0x0F) << 4) | (UInt32(chMinusOne) << 1) | UInt32(bpsMinusOne >> 4))
    body[13] = UInt8(((bpsMinusOne & 0x0F) << 4) | UInt8((totalSamples >> 32) & 0x0F))
    body[14] = UInt8((totalSamples >> 24) & 0xFF)
    body[15] = UInt8((totalSamples >> 16) & 0xFF)
    body[16] = UInt8((totalSamples >> 8) & 0xFF)
    body[17] = UInt8(totalSamples & 0xFF)
    // Bytes 18..33: MD5, leave zero.
    data.append(body)
    return data
  }

  private func beUInt24(_ v: UInt32) -> Data {
    return Data([
      UInt8((v >> 16) & 0xff),
      UInt8((v >> 8) & 0xff),
      UInt8(v & 0xff),
    ])
  }
}
