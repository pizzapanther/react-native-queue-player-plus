import AVFoundation
import XCTest
@testable import QueuePlayer

/// Unit tests for the ReplayGain mode + gain-math paths in
/// `AudioTapProvider`. These tests drive the pure
/// `computeLinearGain(data:mode:)` static helper plus the mode
/// getter/setter round-trip.
final class AudioTapProviderReplayGainTests: XCTestCase {

  // `getReplayGainMode`'s `ReplayGainMode` return type touches the
  // Swift↔C++ interop graph, so direct equality from `@testable`
  // XCTest can't observe it (NOTES.md section 6). The pure gain-math matrix
  // below is the unit surface.

  // MARK: - computeLinearGain — strict mode + selection

  func testComputeLinearGainOffAlwaysReturnsUnity() {
    let data = ReplayGainData(
      trackGainDb: -6.0, trackPeak: 0.95,
      albumGainDb: -10.0, albumPeak: 0.90
    )
    XCTAssertEqual(AudioTapProvider.computeLinearGain(data: data, mode: .off), 1.0)
  }

  func testComputeLinearGainTrackMode() {
    let data = ReplayGainData(
      trackGainDb: -6.0, trackPeak: nil,
      albumGainDb: -10.0, albumPeak: nil
    )
    let expected = Float(pow(10.0, -6.0 / 20.0))
    XCTAssertEqual(
      AudioTapProvider.computeLinearGain(data: data, mode: .track),
      expected, accuracy: 0.001
    )
  }

  func testComputeLinearGainAlbumMode() {
    let data = ReplayGainData(
      trackGainDb: -6.0, trackPeak: nil,
      albumGainDb: -10.0, albumPeak: nil
    )
    let expected = Float(pow(10.0, -10.0 / 20.0))
    XCTAssertEqual(
      AudioTapProvider.computeLinearGain(data: data, mode: .album),
      expected, accuracy: 0.001
    )
  }

  func testComputeLinearGainAlbumModeStrictReturnsUnityWhenAlbumTagMissing() {
    // Strict no-fallback semantics: album mode requires album tag.
    let data = ReplayGainData(
      trackGainDb: -6.0, trackPeak: nil,
      albumGainDb: nil, albumPeak: nil
    )
    XCTAssertEqual(
      AudioTapProvider.computeLinearGain(data: data, mode: .album), 1.0,
      "strict mode — album mode without album tag plays at unity"
    )
  }

  func testComputeLinearGainNilDataReturnsUnity() {
    for mode: ReplayGainSelectorMode in [.off, .track, .album] {
      XCTAssertEqual(
        AudioTapProvider.computeLinearGain(data: nil, mode: mode), 1.0,
        "mode \(mode) on nil data should return unity"
      )
    }
  }

  // MARK: - Peak clip protection

  func testComputeLinearGainPeakCapInactiveWhenRawBelowCap() {
    // -3 dB ≈ 0.7079 linear; cap = 1 / 0.7 ≈ 1.4286. Raw < cap.
    let data = ReplayGainData(
      trackGainDb: -3.0, trackPeak: 0.7,
      albumGainDb: nil, albumPeak: nil
    )
    let raw = Float(pow(10.0, -3.0 / 20.0))
    XCTAssertEqual(
      AudioTapProvider.computeLinearGain(data: data, mode: .track),
      raw, accuracy: 0.001
    )
  }

  func testComputeLinearGainPeakCapKicksInAboveCap() {
    // +6 dB ≈ 1.995 linear, peak 0.6 → cap = 1/0.6 ≈ 1.667. Cap wins.
    let data = ReplayGainData(
      trackGainDb: 6.0, trackPeak: 0.6,
      albumGainDb: nil, albumPeak: nil
    )
    let cap = Float(1.0 / 0.6)
    XCTAssertEqual(
      AudioTapProvider.computeLinearGain(data: data, mode: .track),
      cap, accuracy: 0.001
    )
  }

  func testComputeLinearGainFullScalePeakCapsBoostAtUnity() {
    // peak == 1.0 is digital full scale, so 1/peak == 1.0 and a +6 dB
    // boost is capped back to unity. +6 dB, not -6 dB: at -6 dB the cap
    // never binds and the row cannot discriminate. Mirrors Android's
    // `peak equal to 1 caps the boost at unity`.
    let data = ReplayGainData(
      trackGainDb: 6.0, trackPeak: 1.0,
      albumGainDb: nil, albumPeak: nil
    )
    XCTAssertEqual(
      AudioTapProvider.computeLinearGain(data: data, mode: .track),
      1.0, accuracy: 0.001
    )
  }

  func testComputeLinearGainUnusablePeakClampsBoostToUnity() {
    // peak < 0.001 is an encoder zero — no usable peak, so the boost is
    // clamped to unity rather than running uncapped.
    let data = ReplayGainData(
      trackGainDb: 6.0, trackPeak: 0.0005,
      albumGainDb: nil, albumPeak: nil
    )
    XCTAssertEqual(
      AudioTapProvider.computeLinearGain(data: data, mode: .track),
      1.0, accuracy: 0.001
    )
  }

  // MARK: - Clip safety — min(10^(gain/20), 1/peak), unity cap with no peak

  func testComputeLinearGainAttenuationIsUnaffectedByTheMissingPeakClamp() {
    // -20 dB, no peak. A negative gain can never clip, so the unity
    // clamp only ever binds on a boost.
    let data = ReplayGainData(
      trackGainDb: -20.0, trackPeak: nil,
      albumGainDb: nil, albumPeak: nil
    )
    XCTAssertEqual(
      AudioTapProvider.computeLinearGain(data: data, mode: .track),
      0.100, accuracy: 0.001
    )
  }

  func testComputeLinearGainWithNoPeakClampsBoostToUnity() {
    // +6 dB, no peak. A peak cannot be derived from a gain, so boost is
    // suppressed rather than run unprotected.
    let data = ReplayGainData(
      trackGainDb: 6.0, trackPeak: nil,
      albumGainDb: nil, albumPeak: nil
    )
    XCTAssertEqual(
      AudioTapProvider.computeLinearGain(data: data, mode: .track),
      1.000, accuracy: 0.001
    )
  }

  func testComputeLinearGainWithNoPeakLeavesZeroGainAtUnity() {
    let data = ReplayGainData(
      trackGainDb: 0.0, trackPeak: nil,
      albumGainDb: nil, albumPeak: nil
    )
    XCTAssertEqual(
      AudioTapProvider.computeLinearGain(data: data, mode: .track),
      1.000, accuracy: 0.001
    )
  }

  func testComputeLinearGainFullScalePeakDoesNotBindOnAttenuation() {
    // -20 dB with peak 1.0: the cap is present at 1.0 but 0.1 is lower.
    let data = ReplayGainData(
      trackGainDb: -20.0, trackPeak: 1.0,
      albumGainDb: nil, albumPeak: nil
    )
    XCTAssertEqual(
      AudioTapProvider.computeLinearGain(data: data, mode: .track),
      0.100, accuracy: 0.001
    )
  }

  func testComputeLinearGainBoostSurvivesWhenThePeakCapSitsAboveIt() {
    // +6 dB ~= 1.995, peak 0.5 -> cap 2.0. The cap does not bind.
    let data = ReplayGainData(
      trackGainDb: 6.0, trackPeak: 0.5,
      albumGainDb: nil, albumPeak: nil
    )
    XCTAssertEqual(
      AudioTapProvider.computeLinearGain(data: data, mode: .track),
      1.995, accuracy: 0.001
    )
  }

  func testComputeLinearGainBoostIsCappedByTheReciprocalOfThePeak() {
    // +12 dB ~= 3.981, peak 0.5 -> cap 2.0. The cap wins.
    let data = ReplayGainData(
      trackGainDb: 12.0, trackPeak: 0.5,
      albumGainDb: nil, albumPeak: nil
    )
    XCTAssertEqual(
      AudioTapProvider.computeLinearGain(data: data, mode: .track),
      2.000, accuracy: 0.001
    )
  }

  func testComputeLinearGainAPeakAboveUnityCapsBoostBelowUnity() {
    // +6 dB with a true peak of 1.16 -> cap 1/1.16 ~= 0.862, below unity.
    let data = ReplayGainData(
      trackGainDb: 6.0, trackPeak: 1.16,
      albumGainDb: nil, albumPeak: nil
    )
    XCTAssertEqual(
      AudioTapProvider.computeLinearGain(data: data, mode: .track),
      0.862, accuracy: 0.001
    )
  }

  func testComputeLinearGainClipSafetyIsMirroredInAlbumMode() {
    // The whole cap applies to whichever pair the mode selects — applying
    // it to the track pair only would pass every row above.
    let noPeak = ReplayGainData(
      trackGainDb: nil, trackPeak: nil,
      albumGainDb: 6.0, albumPeak: nil
    )
    XCTAssertEqual(
      AudioTapProvider.computeLinearGain(data: noPeak, mode: .album),
      1.000, accuracy: 0.001,
      "album mode with no album peak clamps boost to unity"
    )
    let fullScale = ReplayGainData(
      trackGainDb: nil, trackPeak: nil,
      albumGainDb: 6.0, albumPeak: 1.0
    )
    XCTAssertEqual(
      AudioTapProvider.computeLinearGain(data: fullScale, mode: .album),
      1.000, accuracy: 0.001,
      "album mode with a full-scale album peak caps boost at unity"
    )
  }

  // MARK: - replayGainActiveForItem

  func testReplayGainActiveForItemReturnsFalseForUnattachedItem() {
    let provider = AudioTapProvider()
    let item = AVPlayerItem(url: URL(string: "https://example.test/x.mp3")!)
    XCTAssertFalse(provider.replayGainActiveForItem(item))
  }
}
