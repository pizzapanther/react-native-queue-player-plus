import XCTest
@testable import QueuePlayer

/// `QueueState` holds `tracks` and `queueItemIds` as parallel arrays. The
/// id-supplying `insert` overload lets a caller reuse the lib-generated
/// ids it stamped on pre-built AVPlayerItems (the immediate-next preload
/// path) while preserving the 1:1 invariant.
final class QueueStateTests: XCTestCase {

  func testInsertWithSuppliedIdsPlacesThemParallelToTracks() {
    var state = QueueState()
    _ = state.replaceAll([track("a"), track("b"), track("c")])

    state.insert([track("x"), track("y")], at: 1, ids: ["id-x", "id-y"])

    XCTAssertEqual(state.tracks.map { $0.url }, ["a", "x", "y", "b", "c"])
    XCTAssertEqual(state.queueItemIds.count, state.tracks.count)
    XCTAssertEqual(state.queueItemIds[1], "id-x")
    XCTAssertEqual(state.queueItemIds[2], "id-y")
  }

  func testInsertWithSuppliedIdsAppendsAtEnd() {
    var state = QueueState()
    _ = state.replaceAll([track("a")])

    state.insert([track("z")], at: 1, ids: ["id-z"])

    XCTAssertEqual(state.tracks.map { $0.url }, ["a", "z"])
    XCTAssertEqual(state.queueItemIds.count, 2)
    XCTAssertEqual(state.queueItemIds[1], "id-z")
  }

  func testGeneratingInsertStillReturnsFreshParallelIds() {
    var state = QueueState()
    _ = state.replaceAll([track("a"), track("b")])

    let ids = state.insert([track("x")], at: 1)

    XCTAssertEqual(ids.count, 1)
    XCTAssertEqual(state.queueItemIds[1], ids[0])
    XCTAssertEqual(state.queueItemIds.count, state.tracks.count)
  }

  func testMetadataPatchPreservesIdentityAndPlaybackFields() throws {
    var state = QueueState()
    let original = TrackItem(
      id: "station", url: "https://example.com/live", title: "Old", artist: "Artist", album: "Album",
      duration: 42, artworkUrl: "https://example.com/art.jpg", extras: ["key": "value"],
      replayGainTrackGain: -3, replayGainTrackPeak: 0.9,
      replayGainAlbumGain: -2, replayGainAlbumPeak: 0.8, isVideo: nil)
    let ids = state.replaceAll([original, track("other")])
    let updated = try XCTUnwrap(state.updateMetadata(at: 0, metadata: TrackMetadataUpdate(
      title: "New", artist: nil, albumTitle: "New album", artworkUrl: nil)))

    XCTAssertEqual(updated.title, "New")
    XCTAssertEqual(updated.album, "New album")
    XCTAssertEqual(updated.artist, original.artist)
    XCTAssertEqual(updated.artworkUrl, original.artworkUrl)
    XCTAssertEqual(updated.id, original.id)
    XCTAssertEqual(updated.url, original.url)
    XCTAssertEqual(updated.duration, original.duration)
    XCTAssertEqual(updated.extras, original.extras)
    XCTAssertEqual(updated.replayGainTrackGain, original.replayGainTrackGain)
    XCTAssertEqual(updated.replayGainTrackPeak, original.replayGainTrackPeak)
    XCTAssertEqual(updated.replayGainAlbumGain, original.replayGainAlbumGain)
    XCTAssertEqual(updated.replayGainAlbumPeak, original.replayGainAlbumPeak)
    XCTAssertEqual(state.queueItemIds, ids)
    XCTAssertEqual(state.tracks[1].url, "other")
  }

  func testMetadataPatchIgnoresMissingFieldsAndAllowsClearing() {
    var state = QueueState()
    _ = state.replaceAll([track("a")])
    let empty = TrackMetadataUpdate(title: nil, artist: nil, albumTitle: nil, artworkUrl: nil)
    XCTAssertNil(state.updateMetadata(at: 0, metadata: empty))
    XCTAssertNil(state.updateMetadata(at: -1, metadata: empty))
    XCTAssertNil(state.updateMetadata(at: 1, metadata: empty))

    let clear = TrackMetadataUpdate(title: "", artist: "", albumTitle: "", artworkUrl: "")
    XCTAssertNotNil(state.updateMetadata(at: 0, metadata: clear))
    XCTAssertEqual(state.tracks[0].title, "")
    XCTAssertEqual(state.tracks[0].artist, "")
    XCTAssertEqual(state.tracks[0].album, "")
    XCTAssertEqual(state.tracks[0].artworkUrl, "")
    XCTAssertNil(state.updateMetadata(at: 0, metadata: clear))
  }

  private func track(_ url: String) -> TrackItem {
    TrackItem(
      id: nil, url: url, title: nil, artist: nil, album: nil,
      duration: nil, artworkUrl: nil, extras: nil,
      replayGainTrackGain: nil, replayGainTrackPeak: nil,
      replayGainAlbumGain: nil, replayGainAlbumPeak: nil, isVideo: nil
    )
  }
}
