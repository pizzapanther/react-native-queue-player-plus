import XCTest
@testable import QueuePlayer

final class EqualizerPresetsTests: XCTestCase {

  private static let expectedOrderedNames: [String] = [
    "Flat",
    "Reference",
    "Bass Boost",
    "Bass Reducer",
    "Treble Boost",
    "Treble Reducer",
    "V-Shaped",
    "Vocal Boost",
    "Spoken Word",
    "Late Night",
    "Acoustic",
    "Rock",
    "Hip-Hop",
    "Small Speakers",
    "Jazz",
    "Electronic",
  ]

  func testFrequenciesSpanTenISOBands() {
    XCTAssertEqual(
      [31, 63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000],
      EqualizerPresets.frequencies
    )
  }

  func testGetNamesReturnsSixteenEntriesInCanonicalDisplayOrder() {
    let names = EqualizerPresets.getNames()
    XCTAssertEqual(16, names.count)
    XCTAssertEqual(Self.expectedOrderedNames, names)
  }

  func testEveryPresetHasTenBands() {
    for name in EqualizerPresets.getNames() {
      let gains = EqualizerPresets.getByName(name)
      XCTAssertEqual(10, gains?.count, "Preset '\(name)' must have 10 bands")
    }
  }

  func testFlatPresetIsAllZeroGains() {
    let flat = EqualizerPresets.getByName("Flat")
    XCTAssertEqual(Array(repeating: 0.0, count: 10), flat)
  }

  func testGetByNameReturnsNilForUnknownPreset() {
    XCTAssertNil(EqualizerPresets.getByName("Nonexistent"))
  }

  func testRockPresetGainsMatchTheRBJTable() {
    let rock = EqualizerPresets.getByName("Rock")!
    let expected: [Double] = [5.0, 3.0, -2.0, -3.0, -1.0, 2.0, 4.0, 5.0, 5.0, 5.0]
    for (i, e) in expected.enumerated() {
      XCTAssertEqual(e, rock[i], accuracy: 0.0001)
    }
  }

  func testTrebleBoostPresetGainsMatchTheRBJTable() {
    let tb = EqualizerPresets.getByName("Treble Boost")!
    let expected: [Double] = [0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 2.0, 4.0, 5.0, 6.0]
    for (i, e) in expected.enumerated() {
      XCTAssertEqual(e, tb[i], accuracy: 0.0001)
    }
  }
}
