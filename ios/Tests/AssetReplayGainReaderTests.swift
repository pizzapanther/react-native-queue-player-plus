import XCTest
@testable import QueuePlayer

/// Unit tests for `AssetReplayGainReader.tagName(info:key:)` — the pure
/// reduction of AVFoundation's three container-dependent metadata shapes
/// to one comparable ReplayGain tag name.
///
/// The shapes below are measured, not assumed:
/// - MP3 / ID3v2 `TXXX`: `info = REPLAYGAIN_TRACK_GAIN`, `key = TXXX`
/// - FLAC / Vorbis: `info = nil`, `key = REPLAYGAIN_TRACK_GAIN`
/// - M4A / iTunes freeform: `info = nil`,
///   `key = com.apple.iTunes.replaygain_track_gain`
///
/// The rule: `info` when non-blank, else `key`; then the segment after
/// the last `.`; then uppercased.
final class AssetReplayGainReaderTests: XCTestCase {

  private let replayGainNames = [
    ReplayGainExtractor.trackGainKey,
    ReplayGainExtractor.trackPeakKey,
    ReplayGainExtractor.albumGainKey,
    ReplayGainExtractor.albumPeakKey,
  ]

  private func assertNotAReplayGainName(
    _ actual: String?,
    _ message: String,
    file: StaticString = #file, line: UInt = #line
  ) {
    guard let actual = actual else { return }
    XCTAssertFalse(
      replayGainNames.contains(actual), message, file: file, line: line
    )
  }

  /// Every test below whose only other assertions are negative pairs
  /// them with this. `assertNotAReplayGainName` is satisfied by `nil`,
  /// so without a derivation that demonstrably works, "this key does
  /// not resolve to a ReplayGain name" is equally satisfied by a
  /// `tagName` that resolves nothing at all.
  private func assertNameDerivationIsLive(
    file: StaticString = #file, line: UInt = #line
  ) {
    XCTAssertEqual(
      AssetReplayGainReader.tagName(info: nil, key: "REPLAYGAIN_TRACK_GAIN"),
      ReplayGainExtractor.trackGainKey,
      "positive control — a known-good key must resolve to its name",
      file: file, line: line
    )
  }

  // MARK: - The three measured container shapes

  func testAnID3UserTextFrameTakesItsNameFromTheInfoField() {
    XCTAssertEqual(
      AssetReplayGainReader.tagName(
        info: "REPLAYGAIN_TRACK_GAIN", key: "TXXX"
      ),
      ReplayGainExtractor.trackGainKey,
      "MP3: the frame id is TXXX; the name is in the info field"
    )
  }

  func testAVorbisCommentTakesItsNameFromTheKey() {
    XCTAssertEqual(
      AssetReplayGainReader.tagName(
        info: nil, key: "REPLAYGAIN_TRACK_GAIN"
      ),
      ReplayGainExtractor.trackGainKey,
      "FLAC: the key is already the name"
    )
  }

  func testAnM4AFreeformKeyReducesToItsLastDotSegment() {
    XCTAssertEqual(
      AssetReplayGainReader.tagName(
        info: nil, key: "com.apple.iTunes.replaygain_track_gain"
      ),
      ReplayGainExtractor.trackGainKey,
      "M4A: reverse-DNS identifier, trimmed to its last segment and uppercased"
    )
  }

  func testAllFourM4AFreeformKeysReduceToTheirReplayGainNames() {
    let cases: [(String, String)] = [
      ("com.apple.iTunes.replaygain_track_gain", ReplayGainExtractor.trackGainKey),
      ("com.apple.iTunes.replaygain_track_peak", ReplayGainExtractor.trackPeakKey),
      ("com.apple.iTunes.replaygain_album_gain", ReplayGainExtractor.albumGainKey),
      ("com.apple.iTunes.replaygain_album_peak", ReplayGainExtractor.albumPeakKey),
    ]
    for (key, expected) in cases {
      XCTAssertEqual(
        AssetReplayGainReader.tagName(info: nil, key: key), expected,
        "\(key) must reduce to \(expected)"
      )
    }
  }

  // MARK: - Info / key precedence

  func testABlankInfoFieldFallsBackToTheKey() {
    // Empty and whitespace-only are both blank — a nil check alone lets
    // an empty info field shadow a usable key.
    XCTAssertEqual(
      AssetReplayGainReader.tagName(
        info: "", key: "REPLAYGAIN_TRACK_GAIN"
      ),
      ReplayGainExtractor.trackGainKey,
      "an empty info field falls back to the key"
    )
    XCTAssertEqual(
      AssetReplayGainReader.tagName(
        info: "   ", key: "REPLAYGAIN_TRACK_GAIN"
      ),
      ReplayGainExtractor.trackGainKey,
      "a whitespace-only info field falls back to the key"
    )
  }

  func testALowercaseInfoFieldIsUppercased() {
    XCTAssertEqual(
      AssetReplayGainReader.tagName(
        info: "replaygain_album_gain", key: "TXXX"
      ),
      ReplayGainExtractor.albumGainKey
    )
  }

  func testWithNeitherInfoNorKeyThereIsNoName() {
    assertNameDerivationIsLive()
    XCTAssertNil(AssetReplayGainReader.tagName(info: nil, key: nil))
  }

  // MARK: - Names that must not reach the ReplayGain lookup

  func testAnOrdinalKeyYieldsNoReplayGainName() {
    // ffmpeg writes `mdta` metadata under ordinal keys ("1", "2", ...);
    // the tag name is unrecoverable from them and none may match.
    assertNameDerivationIsLive()
    for key in ["1", "2", "10"] {
      assertNotAReplayGainName(
        AssetReplayGainReader.tagName(info: nil, key: key),
        "ordinal key \(key) must not resolve to a ReplayGain name"
      )
    }
  }

  func testAnUnrelatedTagNameYieldsNoReplayGainName() {
    assertNameDerivationIsLive()
    for key in ["TITLE", "ARTIST", "com.apple.iTunes.iTunNORM"] {
      assertNotAReplayGainName(
        AssetReplayGainReader.tagName(info: nil, key: key),
        "\(key) must not resolve to a ReplayGain name"
      )
    }
  }

  func testAKeyEndingInADotYieldsNoUsableName() {
    // The last-segment split has nothing after the separator. It must
    // return safely rather than trap on an out-of-range index, and must
    // never hand back an empty name.
    assertNameDerivationIsLive()
    for key in ["com.apple.iTunes.", ".", "..", "REPLAYGAIN_TRACK_GAIN."] {
      let name = AssetReplayGainReader.tagName(info: nil, key: key)
      XCTAssertNotEqual(name, "", "\(key) must not yield an empty name")
      assertNotAReplayGainName(
        name, "\(key) must not resolve to a ReplayGain name"
      )
    }
  }

  // MARK: - Opus double-correction guard


  func testIsOpusContainerRecognisesOpusExtension() {
    XCTAssertTrue(
      AssetReplayGainReader.isOpusContainer(
        url: URL(string: "https://example.test/track.opus")!
      )
    )
    XCTAssertTrue(
      AssetReplayGainReader.isOpusContainer(
        url: URL(string: "https://example.test/track.ogg")!
      )
    )
  }

  /// The guard reads the track's own URL, never the URL the asset is bound to.
  ///
  /// A routed track's asset URL is the read-through server's — a token with no
  /// extension — so a guard that asked the asset would fail open on every Opus
  /// track and apply a `REPLAYGAIN_*` tag on top of the decoder's own
  /// `OpusHead.output_gain`. That is audible, and no playback suite covers it.
  func testAServerTokenURLIsNotMistakenForANonOpusContainer() {
    XCTAssertFalse(
      AssetReplayGainReader.isOpusContainer(
        url: URL(string: "http://127.0.0.1:52341/media/0123456789abcdef0123456789abcdef")!
      ),
      "the transport URL carries no container — the guard must read sourceURL"
    )
    XCTAssertTrue(
      AssetReplayGainReader.isOpusContainer(
        url: URL(string: "https://example.test/track.opus")!
      ),
      "and the media URL still identifies Opus"
    )
  }

  func testIsOpusContainerReturnsFalseForOtherFormats() {
    XCTAssertFalse(
      AssetReplayGainReader.isOpusContainer(
        url: URL(string: "https://example.test/track.mp3")!
      )
    )
    XCTAssertFalse(
      AssetReplayGainReader.isOpusContainer(
        url: URL(string: "https://example.test/track.m4a")!
      )
    )
    XCTAssertFalse(
      AssetReplayGainReader.isOpusContainer(
        url: URL(string: "https://example.test/track.flac")!
      )
    )
  }

  // MARK: - FLAC STREAMINFO
}
