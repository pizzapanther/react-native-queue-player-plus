import AVFoundation
import XCTest
@testable import QueuePlayer

/// Access-log entries arrive several times a second for a stream; the
/// bitrate re-resolve they trigger runs a bounded number of times per item,
/// since a server that never declares a bitrate would otherwise have the
/// extractor re-read the stream for the life of the track.
final class BitrateReresolveBudgetTests: XCTestCase {

  private var player: TrackPlayer!

  override func setUp() async throws {
    try await super.setUp()
    player = TrackPlayer()
  }

  override func tearDown() async throws {
    player = nil
    try await super.tearDown()
  }

  func testReresolvesStopAtTheBudgetForOneItem() async throws {
    let player = try XCTUnwrap(self.player)
    let engine = ActiveItemEngineStub()
    engine.item = AVPlayerItem(url: URL(fileURLWithPath: "/dev/null/a.mp3"))
    await player.onPlayerQueue { player.engine = engine }
    let before = await player.onPlayerQueueValue { player.nowPlayingFormatRequestGen }

    for _ in 0 ..< (TrackPlayer.maxBitrateReresolvesPerItem + 3) {
      await player.onPlayerQueue { player.reresolveNowPlayingFormatForBitrate() }
      try await waitUntilSettled(player)
    }

    let launched = await player.onPlayerQueueValue { player.nowPlayingFormatRequestGen }
    XCTAssertEqual(
      Int(launched - before), TrackPlayer.maxBitrateReresolvesPerItem,
      "every call past the budget is dropped")
  }

  /// The budget is per item: the next track starts with its own.
  func testANewActiveItemGetsAFreshBudget() async throws {
    let player = try XCTUnwrap(self.player)
    let engine = ActiveItemEngineStub()
    engine.item = AVPlayerItem(url: URL(fileURLWithPath: "/dev/null/a.mp3"))
    await player.onPlayerQueue { player.engine = engine }
    for _ in 0 ..< TrackPlayer.maxBitrateReresolvesPerItem {
      await player.onPlayerQueue { player.reresolveNowPlayingFormatForBitrate() }
      try await waitUntilSettled(player)
    }
    let spent = await player.onPlayerQueueValue { player.nowPlayingFormatRequestGen }

    engine.item = AVPlayerItem(url: URL(fileURLWithPath: "/dev/null/b.mp3"))
    await player.onPlayerQueue { player.reresolveNowPlayingFormatForBitrate() }
    try await waitUntilSettled(player)

    let afterSwitch = await player.onPlayerQueueValue { player.nowPlayingFormatRequestGen }
    XCTAssertEqual(afterSwitch, spent + 1, "the new item's first re-resolve runs")
  }

  /// A launched re-resolve finishes on the player queue; the next call is
  /// only meaningful once it has.
  private func waitUntilSettled(_ player: TrackPlayer) async throws {
    for _ in 0 ..< 100 {
      let inFlight = await player.onPlayerQueueValue { player.bitrateReresolveInFlight }
      if !inFlight { return }
      try await Task.sleep(nanoseconds: 20_000_000)
    }
    XCTFail("the re-resolve did not settle")
  }
}
