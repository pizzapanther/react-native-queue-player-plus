import XCTest
@testable import QueuePlayer

/// Unit tests for `ReplayGainExtractor`'s pure string parsers
/// (`parseGainDb`, `parsePeak`) and the `selectGain(mode:)` matrix
/// on `ReplayGainData`.
final class ReplayGainExtractorTests: XCTestCase {

  // Helper: `XCTAssertEqual` with the `accuracy:` overload requires
  // non-optional matching types. Lift the parser's `Double?` to
  // `Double` (failing the assertion early on `nil`) so test bodies
  // stay readable.
  private func assertGain(
    _ raw: String,
    expected: Double,
    accuracy: Double = 0.001,
    file: StaticString = #file, line: UInt = #line
  ) {
    guard let actual = ReplayGainExtractor.parseGainDb(raw) else {
      XCTFail("expected parseGainDb(\"\(raw)\") to succeed", file: file, line: line)
      return
    }
    XCTAssertEqual(actual, expected, accuracy: accuracy, file: file, line: line)
  }

  private func assertPeak(
    _ raw: String,
    expected: Double,
    accuracy: Double = 0.0001,
    file: StaticString = #file, line: UInt = #line
  ) {
    guard let actual = ReplayGainExtractor.parsePeak(raw) else {
      XCTFail("expected parsePeak(\"\(raw)\") to succeed", file: file, line: line)
      return
    }
    XCTAssertEqual(actual, expected, accuracy: accuracy, file: file, line: line)
  }

  private func assertAcceptedGain(
    _ value: Double,
    expected: Double,
    accuracy: Double = 0.001,
    file: StaticString = #file, line: UInt = #line
  ) {
    guard let actual = ReplayGainExtractor.acceptGainDb(value) else {
      XCTFail("expected acceptGainDb(\(value)) to succeed", file: file, line: line)
      return
    }
    XCTAssertEqual(actual, expected, accuracy: accuracy, file: file, line: line)
  }

  private func assertAcceptedPeak(
    _ value: Double,
    expected: Double,
    accuracy: Double = 0.0001,
    file: StaticString = #file, line: UInt = #line
  ) {
    guard let actual = ReplayGainExtractor.acceptPeak(value) else {
      XCTFail("expected acceptPeak(\(value)) to succeed", file: file, line: line)
      return
    }
    XCTAssertEqual(actual, expected, accuracy: accuracy, file: file, line: line)
  }

  // MARK: - parseGainDb tolerance

  func testParseGainDbStandardFormat() {
    assertGain("-6.54 dB", expected: -6.54)
    assertGain("+3.21 dB", expected: 3.21)
    assertGain("0.00 dB", expected: 0.0)
  }

  func testParseGainDbAcceptsBareNumberWithoutSuffix() {
    assertGain("-6.54", expected: -6.54)
    assertGain("+3.21", expected: 3.21)
  }

  func testParseGainDbAcceptsMissingSpaceBeforeDb() {
    assertGain("-6.54dB", expected: -6.54)
  }

  func testParseGainDbToleratesWhitespace() {
    assertGain("  -1.0 dB  ", expected: -1.0)
    assertGain("\t-6.54 dB\n", expected: -6.54)
  }

  func testParseGainDbToleratesInternalWhitespace() {
    // Per NOTES.md section 25 — extra-internal whitespace is tolerated.
    // Some taggers emit the sign separated from the digits.
    assertGain("- 6.54 dB", expected: -6.54)
    assertGain("+ 3.21 dB", expected: 3.21)
    assertGain("-6 .54 dB", expected: -6.54)
  }

  func testParseGainDbCaseInsensitiveSuffix() {
    assertGain("-6.54 DB", expected: -6.54)
    assertGain("-6.54 Db", expected: -6.54)
  }

  func testParseGainDbNormalisesUnicodeMinusSign() {
    // U+2212 MINUS SIGN — emitted by some mac-based taggers.
    assertGain("\u{2212}6.54 dB", expected: -6.54)
  }

  func testParseGainDbRejectsCommaDecimal() {
    // RG2 spec is period-decimal only.
    XCTAssertNil(ReplayGainExtractor.parseGainDb("-6,54 dB"))
  }

  func testParseGainDbRejectsNonNumeric() {
    XCTAssertNil(ReplayGainExtractor.parseGainDb("abc"))
    XCTAssertNil(ReplayGainExtractor.parseGainDb(""))
    XCTAssertNil(ReplayGainExtractor.parseGainDb("dB"))
    XCTAssertNil(ReplayGainExtractor.parseGainDb(" "))
  }

  func testParseGainDbRejectsNaNAndInf() {
    XCTAssertNil(ReplayGainExtractor.parseGainDb("nan dB"))
    XCTAssertNil(ReplayGainExtractor.parseGainDb("inf dB"))
    XCTAssertNil(ReplayGainExtractor.parseGainDb("-inf"))
  }

  // MARK: - parsePeak tolerance + plausible-range guards

  func testParsePeakStandardLinear() {
    assertPeak("0.987654", expected: 0.987654)
    assertPeak("0.5", expected: 0.5)
    assertPeak("1.234567", expected: 1.234567)
  }

  func testPeakAtFullScaleIsAccepted() {
    // 1.000000 is the spec's encoding of digital full scale — a real
    // measurement for a modern master, and the one most in need of the
    // 1/peak clip cap.
    assertPeak("1.000000", expected: 1.0)
    assertPeak("1", expected: 1.0)
  }

  func testParsePeakRejectsEncoderZero() {
    XCTAssertNil(ReplayGainExtractor.parsePeak("0.0001"),
                 "peak < 0.001 means encoder zero / no analysis")
    XCTAssertNil(ReplayGainExtractor.parsePeak("0"),
                 "peak == 0 means encoder zero")
  }

  func testParsePeakAcceptsRealMeasurementsEitherSideOfUnity() {
    assertPeak("0.923366", expected: 0.923366)
    assertPeak("1.160082", expected: 1.160082)
  }

  func testParsePeakAcceptsTheLowerBoundary() {
    assertPeak("0.001", expected: 0.001, accuracy: 0.000001)
  }

  func testParsePeakRejectsJustBelowTheLowerBoundary() {
    XCTAssertNil(ReplayGainExtractor.parsePeak("0.000999"))
  }

  func testParsePeakAcceptsTheUpperBoundary() {
    assertPeak("2.0", expected: 2.0)
  }

  func testParsePeakRejectsJustAboveTheUpperBoundary() {
    XCTAssertNil(ReplayGainExtractor.parsePeak("2.000001"))
  }

  func testParsePeakRejectsCommaDecimal() {
    // RG2 is period-decimal, same as the gain path.
    XCTAssertNil(ReplayGainExtractor.parsePeak("0,5"))
  }

  func testParsePeakRejectsOutOfRange() {
    XCTAssertNil(ReplayGainExtractor.parsePeak("-0.5"))
    XCTAssertNil(ReplayGainExtractor.parsePeak("3.0"))
  }

  func testParsePeakRejectsUnparseable() {
    XCTAssertNil(ReplayGainExtractor.parsePeak("abc"))
    XCTAssertNil(ReplayGainExtractor.parsePeak(""))
    XCTAssertNil(ReplayGainExtractor.parsePeak("nan"))
    XCTAssertNil(ReplayGainExtractor.parsePeak("inf"))
  }

  // MARK: - selectGain matrix on ReplayGainData

  func testSelectGainOff() {
    let data = ReplayGainData(trackGainDb: -12, trackPeak: 0.95,
                              albumGainDb: -8, albumPeak: 0.90)
    let (g, p) = data.selectGain(mode: .off)
    XCTAssertNil(g)
    XCTAssertNil(p)
  }

  func testSelectGainTrack() throws {
    let data = ReplayGainData(trackGainDb: -12, trackPeak: 0.95,
                              albumGainDb: -8, albumPeak: 0.90)
    let (g, p) = data.selectGain(mode: .track)
    XCTAssertEqual(try XCTUnwrap(g), -12, accuracy: 0.001)
    XCTAssertEqual(try XCTUnwrap(p), 0.95, accuracy: 0.001)
  }

  func testSelectGainAlbum() throws {
    let data = ReplayGainData(trackGainDb: -12, trackPeak: 0.95,
                              albumGainDb: -8, albumPeak: 0.90)
    let (g, p) = data.selectGain(mode: .album)
    XCTAssertEqual(try XCTUnwrap(g), -8, accuracy: 0.001)
    XCTAssertEqual(try XCTUnwrap(p), 0.90, accuracy: 0.001)
  }

  func testSelectGainAlbumStrictNoFallback() throws {
    // album-only-missing scenario: track tag present, album not.
    // strict mode → album returns nil pair (no fallback to track).
    let data = ReplayGainData(trackGainDb: -6, trackPeak: 0.92,
                              albumGainDb: nil, albumPeak: nil)
    let (g, p) = data.selectGain(mode: .album)
    XCTAssertNil(g, "strict album mode does NOT fall back to track gain")
    XCTAssertNil(p)

    // ... but track mode on the same data returns the track values.
    let (gT, pT) = data.selectGain(mode: .track)
    XCTAssertEqual(try XCTUnwrap(gT), -6, accuracy: 0.001)
    XCTAssertEqual(try XCTUnwrap(pT), 0.92, accuracy: 0.001)
  }

  func testSelectGainTrackOnlyAlbumPresent() throws {
    // Inverse: only album tag present (rare but possible).
    // track mode returns nil pair; album mode returns the values.
    let data = ReplayGainData(trackGainDb: nil, trackPeak: nil,
                              albumGainDb: -9, albumPeak: 0.88)
    let (gT, pT) = data.selectGain(mode: .track)
    XCTAssertNil(gT)
    XCTAssertNil(pT)
    let (gA, pA) = data.selectGain(mode: .album)
    XCTAssertEqual(try XCTUnwrap(gA), -9, accuracy: 0.001)
    XCTAssertEqual(try XCTUnwrap(pA), 0.88, accuracy: 0.001)
  }

  func testSelectGainAllNil() {
    let data = ReplayGainData(trackGainDb: nil, trackPeak: nil,
                              albumGainDb: nil, albumPeak: nil)
    for mode in [ReplayGainSelectorMode.off, .track, .album] {
      let (g, p) = data.selectGain(mode: mode)
      XCTAssertNil(g, "mode \(mode) should return nil gain")
      XCTAssertNil(p, "mode \(mode) should return nil peak")
    }
  }

  // MARK: - acceptPeak — the numeric half of the same rule

  func testAcceptPeakAcceptsFullScale() {
    assertAcceptedPeak(1.0, expected: 1.0)
  }

  func testAcceptPeakAcceptsTheBoundaries() {
    assertAcceptedPeak(0.001, expected: 0.001, accuracy: 0.000001)
    assertAcceptedPeak(2.0, expected: 2.0)
  }

  func testAcceptPeakAcceptsRealMeasurementsEitherSideOfUnity() {
    assertAcceptedPeak(0.923366, expected: 0.923366)
    assertAcceptedPeak(1.160082, expected: 1.160082)
  }

  func testAcceptPeakRejectsValuesOutsideTheBoundaries() {
    // Paired with an accepted value so the test still fails against an
    // acceptor that rejects everything.
    assertAcceptedPeak(0.5, expected: 0.5)
    XCTAssertNil(ReplayGainExtractor.acceptPeak(0.000999))
    XCTAssertNil(ReplayGainExtractor.acceptPeak(2.000001))
    XCTAssertNil(ReplayGainExtractor.acceptPeak(0.0))
    XCTAssertNil(ReplayGainExtractor.acceptPeak(-0.5))
    XCTAssertNil(ReplayGainExtractor.acceptPeak(3.0))
  }

  func testAcceptPeakRejectsNonFinite() {
    assertAcceptedPeak(1.0, expected: 1.0)
    XCTAssertNil(ReplayGainExtractor.acceptPeak(.nan))
    XCTAssertNil(ReplayGainExtractor.acceptPeak(.infinity))
    XCTAssertNil(ReplayGainExtractor.acceptPeak(-.infinity))
  }

  // MARK: - acceptGainDb — the numeric half of the same rule

  func testAcceptGainDbAcceptsAnyFiniteValue() {
    assertAcceptedGain(-20.0, expected: -20.0)
    assertAcceptedGain(-6.54, expected: -6.54)
    assertAcceptedGain(0.0, expected: 0.0)
    assertAcceptedGain(3.21, expected: 3.21)
  }

  func testAcceptGainDbAppliesNoRangeGuard() {
    // Unlike a peak, a gain has no plausible-value window — the only
    // rejection is non-finite.
    assertAcceptedGain(100.0, expected: 100.0)
    assertAcceptedGain(-100.0, expected: -100.0)
  }

  func testAcceptGainDbRejectsNonFinite() {
    assertAcceptedGain(-6.0, expected: -6.0)
    XCTAssertNil(ReplayGainExtractor.acceptGainDb(.nan))
    XCTAssertNil(ReplayGainExtractor.acceptGainDb(.infinity))
    XCTAssertNil(ReplayGainExtractor.acceptGainDb(-.infinity))
  }

  // MARK: - One rule, two entry points

  func testPeakStringAndNumericPathsAgree() {
    // The string parser is the numeric acceptor plus text handling, so
    // every value that has both representations must land identically.
    let cases: [(String, Double)] = [
      ("1.000000", 1.0),
      ("1", 1.0),
      ("0.923366", 0.923366),
      ("1.160082", 1.160082),
      ("0.5", 0.5),
      ("2.0", 2.0),
      ("2.000001", 2.000001),
      ("0.001", 0.001),
      ("0.000999", 0.000999),
      ("0", 0.0),
      ("-0.5", -0.5),
      ("3.0", 3.0),
      ("nan", Double.nan),
      ("inf", Double.infinity),
      ("-inf", -Double.infinity),
    ]
    for (raw, value) in cases {
      XCTAssertEqual(
        ReplayGainExtractor.parsePeak(raw),
        ReplayGainExtractor.acceptPeak(value),
        "parsePeak(\"\(raw)\") and acceptPeak(\(value)) must agree"
      )
    }
  }

  func testGainDbStringAndNumericPathsAgree() {
    let cases: [(String, Double)] = [
      ("-20.00 dB", -20.0),
      ("-6.54", -6.54),
      ("+3.21 dB", 3.21),
      ("0.00 dB", 0.0),
      ("100.0", 100.0),
      ("-100.0", -100.0),
      ("nan dB", Double.nan),
      ("inf", Double.infinity),
      ("-inf", -Double.infinity),
    ]
    for (raw, value) in cases {
      XCTAssertEqual(
        ReplayGainExtractor.parseGainDb(raw),
        ReplayGainExtractor.acceptGainDb(value),
        "parseGainDb(\"\(raw)\") and acceptGainDb(\(value)) must agree"
      )
    }
  }

  // MARK: - Tag-set acceptance

  // MARK: - Tag-set acceptance

  func testRgDataFromTagsParsesAllFourFields() throws {
    let data = try XCTUnwrap(ReplayGainExtractor.rgDataFromTags([
      "REPLAYGAIN_TRACK_GAIN": "-12.0 dB",
      "REPLAYGAIN_TRACK_PEAK": "0.95",
      "REPLAYGAIN_ALBUM_GAIN": "-8.0 dB",
      "REPLAYGAIN_ALBUM_PEAK": "0.90",
    ]))
    XCTAssertEqual(try XCTUnwrap(data.trackGainDb), -12.0, accuracy: 0.001)
    XCTAssertEqual(try XCTUnwrap(data.trackPeak), 0.95, accuracy: 0.001)
    XCTAssertEqual(try XCTUnwrap(data.albumGainDb), -8.0, accuracy: 0.001)
    XCTAssertEqual(try XCTUnwrap(data.albumPeak), 0.90, accuracy: 0.001)
  }

  func testRgDataFromTagsAcceptsLowercaseKeys() throws {
    let data = try XCTUnwrap(ReplayGainExtractor.rgDataFromTags([
      "replaygain_track_gain": "-6.5 dB",
    ]))
    XCTAssertEqual(try XCTUnwrap(data.trackGainDb), -6.5, accuracy: 0.001)
  }

  func testRgDataFromTagsReturnsNilForEmptyDict() {
    XCTAssertNil(ReplayGainExtractor.rgDataFromTags([:]))
  }

  func testRgDataFromTagsReturnsNilWhenAllValuesUnparseable() {
    XCTAssertNil(ReplayGainExtractor.rgDataFromTags([
      "REPLAYGAIN_TRACK_GAIN": "garbage",
      "REPLAYGAIN_TRACK_PEAK": "3.0",  // above the plausible range → rejected
    ]))
  }

  func testRgDataFromTagsAcceptsAFullScalePeak() throws {
    // 1.000000 is digital full scale — a measurement, so the tag set is
    // usable even when it carries nothing else.
    let data = try XCTUnwrap(ReplayGainExtractor.rgDataFromTags([
      "REPLAYGAIN_TRACK_PEAK": "1.000000",
    ]))
    XCTAssertEqual(try XCTUnwrap(data.trackPeak), 1.0, accuracy: 0.0001)
  }

  // MARK: - Opus container guard
}
