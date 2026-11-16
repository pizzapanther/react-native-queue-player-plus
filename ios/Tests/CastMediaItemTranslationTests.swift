import AVFoundation
import MediaPlayer
import XCTest
@testable import QueuePlayer

/// A cast receiver is handed the consumer's own media URL, never the local
/// read-through server's.
///
/// Cast never touches an `AVPlayerItem`: it builds from `self.tracks`, and the
/// receiver fetches the origin itself over the LAN. Read-through routing lives
/// inside `AVQueueBuilder.makeItem` and never rewrites `self.tracks`.
final class CastMediaItemTranslationTests: XCTestCase {

  /// Configuring a real player writes the process-global
  /// `MPNowPlayingInfoCenter` on main. Drain those writes and clear the centre
  /// so they do not reach the next test class.
  override func tearDown() {
    let drained = expectation(description: "pending main-queue now-playing writes complete")
    DispatchQueue.main.async { drained.fulfill() }
    wait(for: [drained], timeout: 5.0)
    MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
    super.tearDown()
  }

  private func track(url: String) -> TrackItem {
    TrackItem(
      id: "cast-1", url: url, title: "T", artist: nil, album: nil, duration: nil,
      artworkUrl: nil, extras: nil,
      replayGainTrackGain: nil, replayGainTrackPeak: nil,
      replayGainAlbumGain: nil, replayGainAlbumPeak: nil)
  }

  func testAReceiverGetsTheConsumersRemoteURL() throws {
    let item = try XCTUnwrap(
      CastMediaItem.from(track: track(url: "https://example.com/song.mp3")))
    XCTAssertEqual(item.url.absoluteString, "https://example.com/song.mp3")
  }

  /// Local content still goes to the cast server, which is a different server
  /// on a different binding and is untouched by the read-through work.
  func testALocalFileStillReachesCastAsAFileURL() throws {
    let item = try XCTUnwrap(
      CastMediaItem.from(track: track(url: "file:///var/mobile/song.mp3")))
    XCTAssertTrue(item.url.isFileURL)
  }

  /// The routing decision on a configured player: the seated item addresses
  /// the loopback server while the track the cast side builds from keeps the
  /// consumer's URL.
  func testRoutingRewritesTheSeatedItemAndNotTheTrack() async throws {
    let player = TrackPlayer()
    _ = try await player.configure(config: PlayerFixtures.config()).await()
    let origin = "http://127.0.0.1:1/song.mp3"
    _ = try await player.setQueue(tracks: [track(url: origin)], startAtIndex: 0).await()

    let seatedItem = await player.onPlayerQueueValue { player.engine?.allMediaItems.first }
    // `AVPlayerItem.asset` is main-actor isolated (`AVAsset` is not Sendable).
    let seated = await MainActor.run { (seatedItem?.asset as? AVURLAsset)?.url }
    let modelURL = await player.onPlayerQueueValue { player.tracks.first?.url }

    XCTAssertEqual(seated?.host, "127.0.0.1", "the seated item reads through the loopback server")
    XCTAssertNotEqual(seated?.port, 1, "on the server's own port, not the origin's")
    XCTAssertEqual(modelURL, origin, "the track the cast side builds from is untouched")
    XCTAssertEqual(
      try XCTUnwrap(CastMediaItem.from(track: track(url: origin))).url.absoluteString, origin)

    // Await destroy so its player-queue → main now-playing clear is enqueued
    // before this test returns; `tearDown` then drains main and clears the
    // centre so no stray write reaches the next test class.
    _ = try await player.destroy().await()
  }
}
