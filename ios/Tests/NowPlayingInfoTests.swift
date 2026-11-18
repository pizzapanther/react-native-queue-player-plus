import MediaPlayer
import XCTest
@testable import QueuePlayer

/// `NowPlayingInfo` writes the `MPNowPlayingInfoCenter.default()`
/// dictionary that drives the lock-screen / control-center surface.
/// Tests construct the real `TrackItem` Nitro struct via the same
/// initializer call sites use, then drive the public refresh /
/// position / artwork / clear methods and assert dictionary contents.
///
/// `MPNowPlayingInfoCenter.default()` is process-global, so each test
/// nils the dictionary in setUp + tearDown.
final class NowPlayingInfoTests: XCTestCase {

  private var nowPlaying: NowPlayingInfo!

  override func setUp() {
    super.setUp()
    nowPlaying = NowPlayingInfo()
    MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
  }

  override func tearDown() {
    nowPlaying.clear()
    // Drain before dropping the subject: writes are enqueued, so one left in
    // flight would land during the next test and clobber its seeded state.
    _ = nowPlayingInfoAfterWritesLand()
    nowPlaying = nil
    MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
    super.tearDown()
  }

  // MARK: - refreshAll

  func testRefreshAllPublishesTheSnapshotSuppliedToEachCall() {
    let track = makeTrack(title: "T")
    nowPlaying.refreshAll(
      NowPlayingInfo.Snapshot(
        track: track, queueItemId: "q1", elapsedSeconds: 5, durationSeconds: 60, rate: 1), trigger: .trackChange)
    nowPlaying.refreshAll(
      NowPlayingInfo.Snapshot(
        track: track, queueItemId: "q1", elapsedSeconds: 40, durationSeconds: 90, rate: 0), trigger: .trackChange)
    let info = nowPlayingInfoAfterWritesLand()
    XCTAssertEqual(info?[MPNowPlayingInfoPropertyElapsedPlaybackTime] as? Double, 40)
    XCTAssertEqual(info?[MPNowPlayingInfoPropertyPlaybackRate] as? Double, 0)
    XCTAssertEqual(info?[MPMediaItemPropertyPlaybackDuration] as? Double, 90)
  }

  func testRefreshAllClearsDictWhenSnapshotHasNoTrack() {
    MPNowPlayingInfoCenter.default().nowPlayingInfo = ["pre": "existing"]
    nowPlaying.refreshAll(
      NowPlayingInfo.Snapshot(
        track: nil, queueItemId: nil, elapsedSeconds: 0, durationSeconds: 0, rate: 0), trigger: .trackChange)
    XCTAssertNil(nowPlayingInfoAfterWritesLand())
  }

  func testRefreshAllPopulatesTitleArtistAlbum() {
    let track = makeTrack(title: "Song", artist: "Artist", album: "Album")
    nowPlaying.refreshAll(
      NowPlayingInfo.Snapshot(
        track: track, queueItemId: "q1", elapsedSeconds: 12, durationSeconds: 180, rate: 1), trigger: .trackChange)
    let info = nowPlayingInfoAfterWritesLand()
    XCTAssertEqual(info?[MPMediaItemPropertyTitle] as? String, "Song")
    XCTAssertEqual(info?[MPMediaItemPropertyArtist] as? String, "Artist")
    XCTAssertEqual(info?[MPMediaItemPropertyAlbumTitle] as? String, "Album")
  }

  func testRefreshAllEmptyStringCoercesNilTitleArtistAlbum() {
    let track = makeTrack(title: nil, artist: nil, album: nil)
    nowPlaying.refreshAll(
      NowPlayingInfo.Snapshot(
        track: track, queueItemId: "q1", elapsedSeconds: 0, durationSeconds: 60, rate: 0), trigger: .trackChange)
    let info = nowPlayingInfoAfterWritesLand()
    XCTAssertEqual(info?[MPMediaItemPropertyTitle] as? String, "")
    XCTAssertEqual(info?[MPMediaItemPropertyArtist] as? String, "")
    XCTAssertEqual(info?[MPMediaItemPropertyAlbumTitle] as? String, "")
  }

  func testRefreshAllOmitsDurationWhenZeroOrNegativeOrNonFinite() {
    let track = makeTrack(title: "T")
    let omitted: [Double] = [0, -1, .nan, .infinity, -.infinity]
    for d in omitted {
      MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
      nowPlaying.refreshAll(
        NowPlayingInfo.Snapshot(
          track: track, queueItemId: "q1", elapsedSeconds: 0, durationSeconds: d, rate: 0), trigger: .trackChange)
      let info = nowPlayingInfoAfterWritesLand()
      XCTAssertNil(info?[MPMediaItemPropertyPlaybackDuration],
                   "duration must be omitted for value \(d)")
    }
  }

  func testRefreshAllSetsDurationWhenFinitePositive() {
    let track = makeTrack(title: "T")
    nowPlaying.refreshAll(
      NowPlayingInfo.Snapshot(
        track: track, queueItemId: "q1", elapsedSeconds: 0, durationSeconds: 240, rate: 1), trigger: .trackChange)
    let info = nowPlayingInfoAfterWritesLand()
    XCTAssertEqual(info?[MPMediaItemPropertyPlaybackDuration] as? Double, 240)
  }

  func testRefreshAllSetsAudioMediaType() {
    let track = makeTrack()
    nowPlaying.refreshAll(
      NowPlayingInfo.Snapshot(
        track: track, queueItemId: "q1", elapsedSeconds: 0, durationSeconds: 0, rate: 0), trigger: .trackChange)
    let info = nowPlayingInfoAfterWritesLand()
    XCTAssertEqual(
      info?[MPNowPlayingInfoPropertyMediaType] as? UInt,
      MPNowPlayingInfoMediaType.audio.rawValue
    )
  }

  func testRefreshAllPlacesArtworkPlaceholder() {
    let track = makeTrack(title: "T")
    nowPlaying.refreshAll(
      NowPlayingInfo.Snapshot(
        track: track, queueItemId: "q1", elapsedSeconds: 0, durationSeconds: 60, rate: 0), trigger: .trackChange)
    let info = nowPlayingInfoAfterWritesLand()
    XCTAssertNotNil(info?[MPMediaItemPropertyArtwork])
    XCTAssertTrue(info?[MPMediaItemPropertyArtwork] is MPMediaItemArtwork)
  }

  func testRefreshAllElapsedCoercesNonFiniteToZero() {
    let track = makeTrack(title: "T")
    let bad: [Double] = [.nan, .infinity, -.infinity, -5]
    for e in bad {
      nowPlaying.refreshAll(
        NowPlayingInfo.Snapshot(
          track: track, queueItemId: "q1", elapsedSeconds: e, durationSeconds: 60, rate: 1), trigger: .trackChange)
      let info = nowPlayingInfoAfterWritesLand()
      XCTAssertEqual(
        info?[MPNowPlayingInfoPropertyElapsedPlaybackTime] as? Double, 0,
        "elapsed must be 0 for value \(e)")
    }
  }

  func testRefreshAllRateCoercesNonFiniteToZero() {
    let track = makeTrack(title: "T")
    let bad: [Float] = [.nan, .infinity, -.infinity]
    for r in bad {
      nowPlaying.refreshAll(
        NowPlayingInfo.Snapshot(
          track: track, queueItemId: "q1", elapsedSeconds: 0, durationSeconds: 60, rate: r), trigger: .trackChange)
      let info = nowPlayingInfoAfterWritesLand()
      XCTAssertEqual(
        info?[MPNowPlayingInfoPropertyPlaybackRate] as? Double, 0,
        "rate must be 0 for value \(r)")
    }
  }

  // MARK: - refreshPositionAndRate

  func testRefreshPositionAndRateNoOpWhenSnapshotHasNoTrack() {
    MPNowPlayingInfoCenter.default().nowPlayingInfo = ["pre": "value"]
    nowPlaying.refreshPositionAndRate(
      NowPlayingInfo.Snapshot(
        track: nil, queueItemId: nil, elapsedSeconds: 5, durationSeconds: 60, rate: 1), trigger: .stateChange)
    let info = nowPlayingInfoAfterWritesLand()
    XCTAssertEqual(info?["pre"] as? String, "value")
    XCTAssertNil(info?[MPNowPlayingInfoPropertyElapsedPlaybackTime])
  }

  func testRefreshPositionAndRateNoOpWhenDictNeverPopulated() {
    let track = makeTrack(title: "T")
    MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
    nowPlaying.refreshPositionAndRate(
      NowPlayingInfo.Snapshot(
        track: track, queueItemId: "q1", elapsedSeconds: 5, durationSeconds: 60, rate: 1), trigger: .stateChange)
    XCTAssertNil(nowPlayingInfoAfterWritesLand())
  }

  func testRefreshPositionAndRateNoOpAfterRefreshAllCleared() {
    let track = makeTrack(title: "T")
    nowPlaying.refreshAll(
      NowPlayingInfo.Snapshot(
        track: track, queueItemId: "q1", elapsedSeconds: 5, durationSeconds: 60, rate: 1), trigger: .trackChange)
    XCTAssertNotNil(nowPlayingInfoAfterWritesLand())

    nowPlaying.refreshAll(
      NowPlayingInfo.Snapshot(
        track: nil, queueItemId: nil, elapsedSeconds: 0, durationSeconds: 0, rate: 0), trigger: .trackChange)
    XCTAssertNil(nowPlayingInfoAfterWritesLand())

    nowPlaying.refreshPositionAndRate(
      NowPlayingInfo.Snapshot(
        track: track, queueItemId: "q1", elapsedSeconds: 30, durationSeconds: 60, rate: 1), trigger: .stateChange)
    XCTAssertNil(nowPlayingInfoAfterWritesLand())
  }

  func testRefreshPositionAndRateUpdatesElapsedAndRate() {
    let track = makeTrack(title: "T")
    nowPlaying.refreshAll(
      NowPlayingInfo.Snapshot(
        track: track, queueItemId: "q1", elapsedSeconds: 5, durationSeconds: 60, rate: 1), trigger: .trackChange)
    nowPlaying.refreshPositionAndRate(
      NowPlayingInfo.Snapshot(
        track: track, queueItemId: "q1", elapsedSeconds: 30, durationSeconds: 60, rate: 0), trigger: .stateChange)
    let info = nowPlayingInfoAfterWritesLand()
    XCTAssertEqual(info?[MPNowPlayingInfoPropertyElapsedPlaybackTime] as? Double, 30)
    XCTAssertEqual(info?[MPNowPlayingInfoPropertyPlaybackRate] as? Double, 0)
    XCTAssertEqual(info?[MPMediaItemPropertyTitle] as? String, "T")
  }

  // MARK: - applyArtwork

  func testApplyArtworkUpdatesDictWhenPopulated() {
    let track = makeTrack(title: "T")
    nowPlaying.refreshAll(
      NowPlayingInfo.Snapshot(
        track: track, queueItemId: "q1", elapsedSeconds: 0, durationSeconds: 60, rate: 0), trigger: .trackChange)
    let placeholder = nowPlayingInfoAfterWritesLand()?[MPMediaItemPropertyArtwork]
    let size = CGSize(width: 8, height: 8)
    let renderer = UIGraphicsImageRenderer(size: size)
    let custom = renderer.image { ctx in
      UIColor.red.setFill()
      ctx.fill(CGRect(x: 0, y: 0, width: 8, height: 8))
    }
    nowPlaying.applyArtwork(custom, for: "q1")
    let after = nowPlayingInfoAfterWritesLand()?[
      MPMediaItemPropertyArtwork] as? MPMediaItemArtwork
    XCTAssertNotNil(after)
    XCTAssertFalse((after as AnyObject) === (placeholder as AnyObject))
    let resolvedImage = after?.image(at: size)
    XCTAssertNotNil(resolvedImage)
    XCTAssertEqual(resolvedImage?.size, size,
                   "applyArtwork must surface the supplied UIImage at its natural size")
  }

  func testApplyArtworkNoOpWhenDictNil() {
    MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
    let renderer = UIGraphicsImageRenderer(size: CGSize(width: 8, height: 8))
    let custom = renderer.image { ctx in UIColor.red.setFill(); ctx.fill(CGRect(x: 0, y: 0, width: 8, height: 8)) }
    nowPlaying.applyArtwork(custom, for: "q1")
    XCTAssertNil(nowPlayingInfoAfterWritesLand())
  }

  // MARK: - queue entry id

  func testRefreshAllPublishesTheEntryIdAndTheNextEntryReplacesIt() {
    let track = makeTrack(title: "T")
    nowPlaying.refreshAll(
      NowPlayingInfo.Snapshot(
        track: track, queueItemId: "entry-a", elapsedSeconds: 0, durationSeconds: 60, rate: 1),
      trigger: .trackChange)
    XCTAssertEqual(
      nowPlayingInfoAfterWritesLand()?[MPNowPlayingInfoPropertyExternalContentIdentifier] as? String,
      "entry-a")
    nowPlaying.refreshAll(
      NowPlayingInfo.Snapshot(
        track: track, queueItemId: "entry-b", elapsedSeconds: 0, durationSeconds: 60, rate: 1),
      trigger: .trackChange)
    XCTAssertEqual(
      nowPlayingInfoAfterWritesLand()?[MPNowPlayingInfoPropertyExternalContentIdentifier] as? String,
      "entry-b")
  }

  func testPositionRateAndArtworkWritesKeepThePublishedEntryId() {
    let track = makeTrack(title: "T")
    nowPlaying.refreshAll(
      NowPlayingInfo.Snapshot(
        track: track, queueItemId: "entry-a", elapsedSeconds: 0, durationSeconds: 60, rate: 1),
      trigger: .trackChange)
    nowPlaying.refreshPositionAndRate(
      NowPlayingInfo.Snapshot(
        track: track, queueItemId: "entry-a", elapsedSeconds: 20, durationSeconds: 60, rate: 0),
      trigger: .stateChange)
    let placeholder = nowPlayingInfoAfterWritesLand()?[MPMediaItemPropertyArtwork]
    nowPlaying.applyArtwork(makeImage(), for: "entry-a")
    let info = nowPlayingInfoAfterWritesLand()
    XCTAssertEqual(info?[MPNowPlayingInfoPropertyExternalContentIdentifier] as? String, "entry-a")
    XCTAssertFalse((info?[MPMediaItemPropertyArtwork] as AnyObject) === (placeholder as AnyObject))
    XCTAssertEqual(info?[MPNowPlayingInfoPropertyElapsedPlaybackTime] as? Double, 20)
  }

  func testArtworkResolvedForAnotherEntryLeavesThePublishedArtworkInPlace() {
    let track = makeTrack(title: "T")
    nowPlaying.refreshAll(
      NowPlayingInfo.Snapshot(
        track: track, queueItemId: "entry-b", elapsedSeconds: 0, durationSeconds: 60, rate: 1),
      trigger: .trackChange)
    let placeholder = nowPlayingInfoAfterWritesLand()?[MPMediaItemPropertyArtwork]
    nowPlaying.applyArtwork(makeImage(), for: "entry-a")
    let after = nowPlayingInfoAfterWritesLand()?[MPMediaItemPropertyArtwork]
    XCTAssertTrue((after as AnyObject) === (placeholder as AnyObject))
  }

  // MARK: - clear

  func testClearNilsTheDict() {
    let track = makeTrack(title: "T")
    nowPlaying.refreshAll(
      NowPlayingInfo.Snapshot(
        track: track, queueItemId: "q1", elapsedSeconds: 0, durationSeconds: 60, rate: 0), trigger: .trackChange)
    XCTAssertNotNil(nowPlayingInfoAfterWritesLand())
    nowPlaying.clear()
    XCTAssertNil(nowPlayingInfoAfterWritesLand())
  }

  // MARK: - Helpers

  private func makeTrack(
    id: String = "t1",
    url: String = "https://example.com/track.mp3",
    title: String? = nil,
    artist: String? = nil,
    album: String? = nil,
    duration: Double? = nil,
    artworkUrl: String? = nil
  ) -> TrackItem {
    return TrackItem(
      id: id, url: url, title: title, artist: artist, album: album,
      duration: duration, artworkUrl: artworkUrl, extras: nil,
      replayGainTrackGain: nil, replayGainTrackPeak: nil,
      replayGainAlbumGain: nil, replayGainAlbumPeak: nil, isVideo: nil
    )
  }

  private func makeImage() -> UIImage {
    UIGraphicsImageRenderer(size: CGSize(width: 8, height: 8)).image { ctx in
      UIColor.red.setFill()
      ctx.fill(CGRect(x: 0, y: 0, width: 8, height: 8))
    }
  }

  /// The Now Playing dictionary once the writes handed to main have landed.
  /// `NowPlayingInfo` enqueues its writes rather than performing them inline,
  /// so a read taken in the same turn still sees the previous value.
  private func nowPlayingInfoAfterWritesLand() -> [String: Any]? {
    let drained = expectation(description: "main queue drained")
    DispatchQueue.main.async { drained.fulfill() }
    wait(for: [drained], timeout: 2.0)
    return MPNowPlayingInfoCenter.default().nowPlayingInfo
  }

}
