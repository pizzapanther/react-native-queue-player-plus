import AVFoundation
import CoreMedia
import XCTest
@testable import QueuePlayer

/// Pure-mapping coverage for `NowPlayingFormatExtractor`. These
/// tests pin the codec / container / MIME tables so a future
/// contributor can't drift the JS-visible enum without a test
/// failure.
final class NowPlayingFormatExtractorTests: XCTestCase {

  func testMapCodecCoversAACFamily() {
    XCTAssertEqual(NowPlayingFormatExtractor.mapCodec(formatID: kAudioFormatMPEG4AAC), .aac)
    XCTAssertEqual(NowPlayingFormatExtractor.mapCodec(formatID: kAudioFormatMPEG4AAC_HE), .aac)
    XCTAssertEqual(NowPlayingFormatExtractor.mapCodec(formatID: kAudioFormatMPEG4AAC_HE_V2), .aac)
    XCTAssertEqual(NowPlayingFormatExtractor.mapCodec(formatID: kAudioFormatMPEG4AAC_LD), .aac)
    XCTAssertEqual(NowPlayingFormatExtractor.mapCodec(formatID: kAudioFormatMPEG4AAC_ELD), .aac)
  }

  func testMapCodecCoversCommonFormats() {
    XCTAssertEqual(NowPlayingFormatExtractor.mapCodec(formatID: kAudioFormatMPEGLayer3), .mp3)
    XCTAssertEqual(NowPlayingFormatExtractor.mapCodec(formatID: kAudioFormatFLAC), .flac)
    XCTAssertEqual(NowPlayingFormatExtractor.mapCodec(formatID: kAudioFormatAppleLossless), .alac)
    XCTAssertEqual(NowPlayingFormatExtractor.mapCodec(formatID: kAudioFormatOpus), .opus)
    XCTAssertEqual(NowPlayingFormatExtractor.mapCodec(formatID: kAudioFormatLinearPCM), .pcm)
  }

  func testMapCodecUnknownForUnmappedFormat() {
    let madeUpFourCC: AudioFormatID = 0x66616B65 // 'fake'
    XCTAssertEqual(NowPlayingFormatExtractor.mapCodec(formatID: madeUpFourCC), .unknown)
  }

  func testMapContainerByExtension() {
    XCTAssertEqual(extractContainer("song.mp3"), .mp3)
    XCTAssertEqual(extractContainer("song.m4a"), .mp4)
    XCTAssertEqual(extractContainer("song.m4b"), .mp4)
    XCTAssertEqual(extractContainer("song.mp4"), .mp4)
    XCTAssertEqual(extractContainer("song.flac"), .flac)
    XCTAssertEqual(extractContainer("song.ogg"), .ogg)
    XCTAssertEqual(extractContainer("song.opus"), .ogg)
    XCTAssertEqual(extractContainer("song.wav"), .wav)
    XCTAssertEqual(extractContainer("song.aac"), .aacAdts)
    XCTAssertEqual(extractContainer("playlist.m3u8"), .hls)
  }

  func testMapContainerCaseInsensitive() {
    XCTAssertEqual(extractContainer("SONG.MP3"), .mp3)
    XCTAssertEqual(extractContainer("Song.M4A"), .mp4)
  }

  func testMapContainerUnknownForUnknownExtension() {
    XCTAssertEqual(extractContainer("song.xyz"), .unknown)
    XCTAssertEqual(extractContainer("song"), .unknown)
  }

  func testMapContainerUnknownForNilURL() {
    XCTAssertEqual(NowPlayingFormatExtractor.mapContainer(url: nil), .unknown)
  }

  func testMapMimeTypeMatchesCodec() {
    XCTAssertEqual(NowPlayingFormatExtractor.mapMimeType(codec: .aac), "audio/mp4a-latm")
    XCTAssertEqual(NowPlayingFormatExtractor.mapMimeType(codec: .mp3), "audio/mpeg")
    XCTAssertEqual(NowPlayingFormatExtractor.mapMimeType(codec: .flac), "audio/flac")
    XCTAssertEqual(NowPlayingFormatExtractor.mapMimeType(codec: .alac), "audio/alac")
    XCTAssertEqual(NowPlayingFormatExtractor.mapMimeType(codec: .opus), "audio/opus")
    XCTAssertEqual(NowPlayingFormatExtractor.mapMimeType(codec: .pcm), "audio/L16")
  }

  func testMapMimeTypeNilForUnknownCodec() {
    XCTAssertNil(NowPlayingFormatExtractor.mapMimeType(codec: .unknown))
  }

  // MARK: - readLocalMP3Bitrate

  /// CBR MP3 frame parser returns approximately the encoder's nominal
  /// rate. ±5% tolerance covers Core Audio's averaging across frames
  /// (the bitrate field is computed from a running sum, not from the
  /// first-frame header).
  func testReadLocalMP3Bitrate_CBR_returnsApproximateNominal() async throws {
    let url = try requireFixture(name: "silence-cbr-128", ext: "mp3")
    let bitrate = await NowPlayingFormatExtractor.readLocalMP3Bitrate(url: url)
    let resolved = try XCTUnwrap(bitrate)
    let nominal: Double = 128_000.0
    let tolerance: Double = nominal * 0.05
    XCTAssertEqual(resolved, nominal, accuracy: tolerance,
                   "CBR 128 kbps fixture resolved to \(resolved) bps")
  }

  /// VBR MP3 with a XING/LAME header returns a non-nil bitrate inside
  /// a plausible range. Tolerance is wide because libmp3lame `-q:a 4`
  /// targets ~165 kbps but a one-second silence track sits well below
  /// that nominal — the test only asserts the value is sensible.
  func testReadLocalMP3Bitrate_VBR_returnsAverage() async throws {
    let url = try requireFixture(name: "silence-vbr", ext: "mp3")
    let bitrate = await NowPlayingFormatExtractor.readLocalMP3Bitrate(url: url)
    let resolved = try XCTUnwrap(bitrate)
    XCTAssertGreaterThan(resolved, 8_000.0,
                         "VBR fixture bitrate suspiciously low: \(resolved)")
    XCTAssertLessThan(resolved, 320_000.0,
                      "VBR fixture bitrate above MP3 max: \(resolved)")
  }

  func testReadLocalMP3Bitrate_NonExistentFileReturnsNil() async {
    let url = URL(fileURLWithPath: "/tmp/__rnqp-does-not-exist__.mp3")
    let bitrate = await NowPlayingFormatExtractor.readLocalMP3Bitrate(url: url)
    XCTAssertNil(bitrate)
  }

  func testReadLocalMP3Bitrate_MalformedMP3ReturnsNil() async throws {
    let tmp = FileManager.default.temporaryDirectory
      .appendingPathComponent("rnqp-malformed-\(UUID().uuidString).mp3")
    let junk = Data(repeating: 0x00, count: 1024)
    try junk.write(to: tmp)
    defer { try? FileManager.default.removeItem(at: tmp) }
    let bitrate = await NowPlayingFormatExtractor.readLocalMP3Bitrate(url: tmp)
    XCTAssertNil(bitrate, "all-zero buffer must not parse as MP3")
  }

  /// Non-MP3 audio passed to the local-MP3 helper returns nil. The
  /// `kAudioFileMP3Type` argument to `AudioFileOpenURL` is only a hint —
  /// Core Audio's content sniffing opens the AAC/.m4a fixture
  /// successfully. The helper's `mFormatID == kAudioFormatMPEGLayer3`
  /// ASBD check is what rejects the non-MP3 ASBD and returns nil.
  func testReadLocalMP3Bitrate_NotMP3FallthroughReturnsNil() async throws {
    let url = try requireFixture(name: "silence-aac-128", ext: "m4a")
    let bitrate = await NowPlayingFormatExtractor.readLocalMP3Bitrate(url: url)
    XCTAssertNil(bitrate)
  }

  // MARK: - helpers

  private func extractContainer(_ path: String) -> AudioContainer {
    NowPlayingFormatExtractor.mapContainer(url: URL(fileURLWithPath: "/tmp/\(path)"))
  }

  private func requireFixture(name: String, ext: String) throws -> URL {
    let bundle = Bundle(for: type(of: self))
    if let url = bundle.url(forResource: name, withExtension: ext) {
      return url
    }
    // CocoaPods test_spec resources land in a nested bundle named
    // `QueuePlayer-Unit-Tests.bundle` or under the test bundle's
    // `Resources` directory depending on platform — fall back to a
    // recursive search before giving up.
    let candidates = bundle.urls(forResourcesWithExtension: ext, subdirectory: nil) ?? []
    if let match = candidates.first(where: { $0.deletingPathExtension().lastPathComponent == name }) {
      return match
    }
    // Fixtures are bundled via `test_spec.resources` in QueuePlayer.podspec.
    // A miss means the test-harness pod install regressed — that's a real
    // infrastructure failure, not a conditionally-inapplicable test.
    return try XCTUnwrap(
      nil as URL?,
      "fixture \(name).\(ext) not bundled — pod install regression in tests-harness"
    )
  }
}
