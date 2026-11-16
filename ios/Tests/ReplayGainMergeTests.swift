import XCTest
@testable import QueuePlayer

/// Unit tests for `ReplayGainData.merge(suppliedClaimed:supplied:detected:)`
/// — the all-or-nothing, per-item choice between consumer-supplied
/// ReplayGain and values detected from the file.
///
/// The rule is one predicate on the item, not two pair decisions and not
/// four field decisions: if the consumer supplied *any* of the four
/// fields, that track's ReplayGain comes solely from the consumer and
/// detection is discarded in full — including for the mode the consumer
/// said nothing about. Supplying a value is itself the statement of
/// intent; a consumer who wanted the file's own tags supplies nothing.
///
/// `suppliedClaimed` is captured before validation, so
/// `suppliedClaimed == true` with `supplied == nil` is a real state
/// meaning "the consumer owns this track and gave nothing usable".
/// Ownership is triggered by field *presence*, not validity.
///
/// Supplied and detected fixtures deliberately carry different values in
/// every field, so a row that returned the wrong source cannot pass.
final class ReplayGainMergeTests: XCTestCase {

  // MARK: - Fixtures

  private let detectedAll = ReplayGainData(
    trackGainDb: -20.0, trackPeak: 0.5,
    albumGainDb: -6.0, albumPeak: 0.6
  )
  private let detectedTrackOnly = ReplayGainData(
    trackGainDb: -20.0, trackPeak: 0.5,
    albumGainDb: nil, albumPeak: nil
  )
  private let detectedAlbumOnly = ReplayGainData(
    trackGainDb: nil, trackPeak: nil,
    albumGainDb: -6.0, albumPeak: 0.6
  )

  private let suppliedAll = ReplayGainData(
    trackGainDb: -3.0, trackPeak: 0.25,
    albumGainDb: -9.0, albumPeak: 0.3
  )
  private let suppliedTrackGain = ReplayGainData(
    trackGainDb: -3.0, trackPeak: nil,
    albumGainDb: nil, albumPeak: nil
  )
  private let suppliedAlbumGain = ReplayGainData(
    trackGainDb: nil, trackPeak: nil,
    albumGainDb: -9.0, albumPeak: nil
  )
  private let suppliedTrackPeak = ReplayGainData(
    trackGainDb: nil, trackPeak: 0.25,
    albumGainDb: nil, albumPeak: nil
  )
  private let suppliedAlbumPeak = ReplayGainData(
    trackGainDb: nil, trackPeak: nil,
    albumGainDb: nil, albumPeak: 0.3
  )
  private let suppliedTrackPair = ReplayGainData(
    trackGainDb: -3.0, trackPeak: 0.25,
    albumGainDb: nil, albumPeak: nil
  )
  private let suppliedAlbumPair = ReplayGainData(
    trackGainDb: nil, trackPeak: nil,
    albumGainDb: -9.0, albumPeak: 0.3
  )

  // MARK: - Helper

  private func assertMerge(
    claimed: Bool,
    supplied: ReplayGainData?,
    detected: ReplayGainData?,
    equals expected: ReplayGainData?,
    _ message: String,
    file: StaticString = #file, line: UInt = #line
  ) {
    let actual = ReplayGainData.merge(
      suppliedClaimed: claimed, supplied: supplied, detected: detected
    )
    XCTAssertEqual(actual, expected, message, file: file, line: line)
  }

  // MARK: - No claim — detection is used verbatim

  func testAnUnclaimedTrackUsesEveryDetectedField() {
    assertMerge(
      claimed: false, supplied: nil, detected: detectedAll,
      equals: detectedAll,
      "no supplied field means detection is used, all four"
    )
  }

  func testAnUnclaimedTrackWithNoDetectionHasNoReplayGain() {
    assertMerge(
      claimed: false, supplied: nil, detected: nil,
      equals: nil,
      "nothing supplied and nothing detected is no ReplayGain"
    )
  }

  func testAnUnclaimedTrackKeepsATrackOnlyDetection() {
    assertMerge(
      claimed: false, supplied: nil, detected: detectedTrackOnly,
      equals: detectedTrackOnly,
      "a partial detection is used as-is, not padded"
    )
  }

  func testAnUnclaimedTrackKeepsAnAlbumOnlyDetection() {
    assertMerge(
      claimed: false, supplied: nil, detected: detectedAlbumOnly,
      equals: detectedAlbumOnly,
      "the mirrored partial-detection case"
    )
  }

  // MARK: - Claimed — one supplied field discards all detection

  func testASuppliedTrackGainDiscardsEveryDetectedField() {
    assertMerge(
      claimed: true, supplied: suppliedTrackGain, detected: detectedAll,
      equals: suppliedTrackGain,
      "supplied trackGain applies; detected peak and album pair are dropped"
    )
  }

  func testASuppliedAlbumGainDiscardsEveryDetectedField() {
    assertMerge(
      claimed: true, supplied: suppliedAlbumGain, detected: detectedAll,
      equals: suppliedAlbumGain,
      "supplied albumGain applies; detected track pair and album peak are dropped"
    )
  }

  func testASuppliedTrackPeakAloneLeavesNoGainAnywhere() {
    assertMerge(
      claimed: true, supplied: suppliedTrackPeak, detected: detectedAll,
      equals: suppliedTrackPeak,
      "a peak with no gain is ownership with nothing to apply"
    )
  }

  func testASuppliedAlbumPeakAloneLeavesNoGainAnywhere() {
    assertMerge(
      claimed: true, supplied: suppliedAlbumPeak, detected: detectedAll,
      equals: suppliedAlbumPeak,
      "the mirrored peak-only case"
    )
  }

  func testASuppliedTrackPairDiscardsTheDetectedAlbumPair() {
    assertMerge(
      claimed: true, supplied: suppliedTrackPair, detected: detectedAll,
      equals: suppliedTrackPair,
      "album mode plays at unity even though the file carries album tags"
    )
  }

  func testASuppliedAlbumPairDiscardsTheDetectedTrackPair() {
    assertMerge(
      claimed: true, supplied: suppliedAlbumPair, detected: detectedAll,
      equals: suppliedAlbumPair,
      "track mode plays at unity even though the file carries track tags"
    )
  }

  func testAFullySuppliedSetReplacesAFullyDetectedSet() {
    assertMerge(
      claimed: true, supplied: suppliedAll, detected: detectedAll,
      equals: suppliedAll,
      "all four supplied values win over all four detected values"
    )
  }

  func testASuppliedTrackGainDiscardsAnAlbumOnlyDetection() {
    assertMerge(
      claimed: true, supplied: suppliedTrackGain, detected: detectedAlbumOnly,
      equals: suppliedTrackGain,
      "detection is discarded even where supplied and detected do not overlap"
    )
  }

  // MARK: - Claimed with no detection to discard

  func testAFullySuppliedSetAppliesToAnUntaggedFile() {
    assertMerge(
      claimed: true, supplied: suppliedAll, detected: nil,
      equals: suppliedAll,
      "the motivating case — a server that stripped tags on transcode"
    )
  }

  func testASuppliedTrackGainAppliesToAnUntaggedFile() {
    assertMerge(
      claimed: true, supplied: suppliedTrackGain, detected: nil,
      equals: suppliedTrackGain,
      "a single supplied field on an untagged file applies alone"
    )
  }

  // MARK: - Claimed but nothing usable — ownership without a value

  func testAClaimWithNoUsableValueDiscardsAFullDetection() {
    // Every supplied value was rejected by validation, so `supplied` is
    // nil — but the claim was captured before validation, so the file's
    // tags do not come back.
    assertMerge(
      claimed: true, supplied: nil, detected: detectedAll,
      equals: nil,
      "presence, not validity, is what claims the track"
    )
  }

  func testAClaimWithNoUsableValueDiscardsAPartialDetection() {
    assertMerge(
      claimed: true, supplied: nil, detected: detectedTrackOnly,
      equals: nil,
      "a partial detection is discarded by a claim just as a full one is"
    )
  }

  func testAClaimWithNoUsableValueAndNoDetectionHasNoReplayGain() {
    assertMerge(
      claimed: true, supplied: nil, detected: nil,
      equals: nil,
      "ownership claimed, nothing supplied, nothing detected"
    )
  }
}
