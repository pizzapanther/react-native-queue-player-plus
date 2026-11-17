import AVFoundation
import XCTest
@testable import QueuePlayer

final class TimedMetadataTests: XCTestCase {
  private func text(_ key: String, _ value: String, info: String? = nil) -> AVMetadataItem {
    let item = AVMutableMetadataItem()
    item.keySpace = .id3
    item.key = key as NSString
    item.value = value as NSString
    if let info { item.extraAttributes = [.info: info] }
    return item
  }

  func testID3FieldsAndCustomArtworkURL() {
    let metadata = TimedMetadataParser.parse([
      text("TIT2", "Song"), text("TPE1", "Artist"), text("TALB", "Album"),
      text("TXXX", "https://example.com/art.jpg", info: "artworkUrl"),
    ])
    XCTAssertEqual(metadata?.title, "Song")
    XCTAssertEqual(metadata?.artist, "Artist")
    XCTAssertEqual(metadata?.albumTitle, "Album")
    XCTAssertEqual(metadata?.artworkUrl, "https://example.com/art.jpg")
  }

  func testPartialAndUnrelatedSamples() {
    let metadata = TimedMetadataParser.parse([text("TIT2", "")])
    XCTAssertEqual(metadata?.title, "")
    XCTAssertNil(metadata?.artist)
    XCTAssertNil(metadata?.albumTitle)
    XCTAssertNil(metadata?.artworkUrl)
    XCTAssertNil(TimedMetadataParser.parse([text("TXXX", "-3 dB", info: "REPLAYGAIN_TRACK_GAIN")]))
    XCTAssertNil(TimedMetadataParser.parse([]))
  }

  func testIcyTitleAndStationURL() {
    let metadata = TimedMetadataParser.parse([
      text("StreamTitle", "Artist - Song - Live"), text("StreamUrl", "https://station.com"),
    ])
    XCTAssertEqual(metadata?.title, "Song - Live")
    XCTAssertEqual(metadata?.artist, "Artist")
    XCTAssertNil(metadata?.artworkUrl)
    XCTAssertEqual(TimedMetadataParser.parse([text("StreamTitle", "Station ID")])?.title, "Station ID")
  }

  func testOutputUsesPresentationTimingAndStopsAfterInvalidation() {
    let item = AVPlayerItem(url: URL(fileURLWithPath: "/unused.mp3"))
    var received: [TimedMetadata] = []
    let observation = TimedMetadataObservation(item: item, queue: .main) { _, metadata in
      received.append(metadata)
    }
    XCTAssertEqual(observation.output.advanceIntervalForDelegateInvocation, 0)
    XCTAssertTrue(item.outputs.contains { $0 === observation.output })
    XCTAssertTrue(received.isEmpty)
    let group = AVTimedMetadataGroup(items: [text("TIT2", "Song")], timeRange:
      CMTimeRange(start: CMTime(seconds: 5, preferredTimescale: 600),
                  duration: CMTime(seconds: 1, preferredTimescale: 600)))
    observation.metadataOutput(observation.output, didOutputTimedMetadataGroups: [group], from: nil)
    XCTAssertEqual(received.count, 1)
    XCTAssertEqual(received.first?.title, "Song")
    observation.invalidate()
    observation.metadataOutput(observation.output, didOutputTimedMetadataGroups: [group], from: nil)
    XCTAssertEqual(received.count, 1)
    XCTAssertFalse(item.outputs.contains { $0 === observation.output })
  }
}
