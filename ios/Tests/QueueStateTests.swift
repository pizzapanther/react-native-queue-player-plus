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

  private func track(_ url: String) -> TrackItem {
    TrackItem(
      id: nil, url: url, title: nil, artist: nil, album: nil,
      duration: nil, artworkUrl: nil, extras: nil,
      replayGainTrackGain: nil, replayGainTrackPeak: nil,
      replayGainAlbumGain: nil, replayGainAlbumPeak: nil
    )
  }
}
