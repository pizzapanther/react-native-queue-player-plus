import MediaPlayer
import XCTest
@testable import QueuePlayer

/// `CastNowPlayingController` drives `MPNowPlayingInfoCenter` while a
/// Chromecast `CastSessionRemotePlayer` is active. Tests activate the
/// shared controller through the real `PlaybackStateRouter`, drive a
/// listener-firing fake session, and assert the lockscreen dictionary.
///
/// `MPNowPlayingInfoCenter.default()` + both singletons are process-
/// global, so each test resets the router, stops the controller, and
/// nils the dictionary in setUp + tearDown.
final class CastNowPlayingControllerTests: XCTestCase {

  private let router = PlaybackStateRouter.shared
  private let controller = CastNowPlayingController.shared

  override func setUp() {
    super.setUp()
    router._resetForTesting()
    MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
    controller.start()
  }

  override func tearDown() {
    controller.stop()
    router._resetForTesting()
    MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
    super.tearDown()
  }

  private var info: [String: Any]? {
    MPNowPlayingInfoCenter.default().nowPlayingInfo
  }

  func testActivationSeedsAudioTypeAndPlaceholderArtwork() throws {
    try router.setActive(ControllableRemoteSession())
    XCTAssertEqual(
      info?[MPNowPlayingInfoPropertyMediaType] as? UInt,
      MPNowPlayingInfoMediaType.audio.rawValue
    )
    XCTAssertNotNil(
      info?[MPMediaItemPropertyArtwork],
      "the lockscreen seeds a placeholder so it is never artwork-less"
    )
  }

  func testUpdateTrackMetadataWritesTitleArtistAlbum() throws {
    try router.setActive(ControllableRemoteSession())
    controller.updateTrackMetadata(
      title: "Song", artist: "Artist", album: "Album",
      durationSec: 180, artworkUrl: nil
    )
    XCTAssertEqual(info?[MPMediaItemPropertyTitle] as? String, "Song")
    XCTAssertEqual(info?[MPMediaItemPropertyArtist] as? String, "Artist")
    XCTAssertEqual(info?[MPMediaItemPropertyAlbumTitle] as? String, "Album")
  }

  func testATrackKeepsOneContentIdAcrossStateWritesAndTheNextTrackGetsANewOne() throws {
    let session = ControllableRemoteSession()
    try router.setActive(session)
    controller.updateTrackMetadata(
      title: "One", artist: nil, album: nil, durationSec: 180, artworkUrl: nil)
    let first = info?[MPNowPlayingInfoPropertyExternalContentIdentifier] as? String
    XCTAssertNotNil(first)
    session.fireState(.playing)
    XCTAssertEqual(info?[MPNowPlayingInfoPropertyExternalContentIdentifier] as? String, first)
    controller.updateTrackMetadata(
      title: "Two", artist: nil, album: nil, durationSec: 180, artworkUrl: nil)
    let second = info?[MPNowPlayingInfoPropertyExternalContentIdentifier] as? String
    XCTAssertNotNil(second)
    XCTAssertNotEqual(second, first)
  }

  func testDurationPrefersReceiverStreamDuration() throws {
    let session = ControllableRemoteSession()
    session.lastDurationMs = 200_000
    try router.setActive(session)
    // Consumer-supplied duration (5s) is untrusted; the receiver's live
    // 200s stream duration wins.
    controller.updateTrackMetadata(
      title: "S", artist: nil, album: nil,
      durationSec: 5, artworkUrl: nil
    )
    XCTAssertEqual(
      info?[MPMediaItemPropertyPlaybackDuration] as? Double, 200.0
    )
  }

  func testStateListenerDrivesPlaybackRate() throws {
    let session = ControllableRemoteSession()
    try router.setActive(session)
    session.fireState(.playing)
    XCTAssertEqual(info?[MPNowPlayingInfoPropertyPlaybackRate] as? Double, 1.0)
    session.fireState(.paused)
    XCTAssertEqual(info?[MPNowPlayingInfoPropertyPlaybackRate] as? Double, 0.0)
  }

  func testPositionListenerDrivesElapsed() throws {
    let session = ControllableRemoteSession()
    try router.setActive(session)
    session.firePosition(positionMs: 30_000)
    XCTAssertEqual(
      info?[MPNowPlayingInfoPropertyElapsedPlaybackTime] as? Double, 30.0
    )
  }

  func testPositionListenerRepublishesDuration() throws {
    let session = ControllableRemoteSession()
    try router.setActive(session)
    // Duration arrives on a later position tick, after the initial
    // refresh saw 0 — it must reach the scrubber.
    session.firePosition(positionMs: 1_000, durationMs: 240_000)
    XCTAssertEqual(
      info?[MPMediaItemPropertyPlaybackDuration] as? Double, 240.0
    )
  }

  func testDeactivateClearsNowPlayingInfo() throws {
    try router.setActive(ControllableRemoteSession())
    controller.updateTrackMetadata(
      title: "S", artist: nil, album: nil, durationSec: 0, artworkUrl: nil
    )
    XCTAssertNotNil(info)
    try router.setActive(nil)
    XCTAssertNil(info, "the lockscreen surface is released on disconnect")
  }

  func testPushSinkSessionDoesNotTakeLockscreen() throws {
    let airplay = ControllableRemoteSession()
    airplay.pushSink = true
    try router.setActive(airplay)
    XCTAssertNil(
      info,
      "AirPlay keeps the local AVAudioSession alive; the cast controller stays out"
    )
  }

  func testMetadataPushedBeforeActivationAppearsOnActivate() throws {
    // No active session yet — the push is cached, not written.
    controller.updateTrackMetadata(
      title: "Cached", artist: nil, album: nil, durationSec: 0, artworkUrl: nil
    )
    XCTAssertNil(info)
    try router.setActive(ControllableRemoteSession())
    XCTAssertEqual(
      info?[MPMediaItemPropertyTitle] as? String, "Cached",
      "activation renders the most recent cached metadata"
    )
  }

  func testSeededPlaceholderReflectsConfiguredCustomImage() throws {
    let tmp = FileManager.default.temporaryDirectory
      .appendingPathComponent("rnqp-cast-placeholder-\(UUID().uuidString).jpg")
    try makeTinyJpeg().write(to: tmp)
    defer {
      try? FileManager.default.removeItem(at: tmp)
      PlaceholderArtwork.setCustom(uri: nil)
    }
    let builtInSize = PlaceholderArtwork.image.size
    PlaceholderArtwork.setCustom(uri: tmp.absoluteString)
    let customSize = PlaceholderArtwork.image.size
    // Precondition: the fixture is a different size from the built-in,
    // else the size check below can't tell them apart.
    XCTAssertNotEqual(customSize, builtInSize)

    try router.setActive(ControllableRemoteSession())

    let artwork = info?[MPMediaItemPropertyArtwork] as? MPMediaItemArtwork
    XCTAssertEqual(
      artwork?.image(at: customSize)?.size, customSize,
      "the cast lockscreen seeds the live custom placeholder, not a frozen built-in"
    )
  }

  private func makeTinyJpeg() -> Data {
    let renderer = UIGraphicsImageRenderer(size: CGSize(width: 1, height: 1))
    let image = renderer.image { ctx in
      UIColor.green.setFill()
      ctx.fill(CGRect(x: 0, y: 0, width: 1, height: 1))
    }
    return image.jpegData(compressionQuality: 1.0) ?? Data()
  }
}

// MARK: - Listener-firing fake

/// `CastSessionRemotePlayer` whose state / position / currentItemId
/// listeners can be fired on demand, so controller behaviour can be
/// driven deterministically. Transport calls are no-ops.
final class ControllableRemoteSession: CastSessionRemotePlayer {
  var pushSink = false
  var isPushSink: Bool { pushSink }

  var lastState: RemotePlaybackStateKind = .idle
  var hasReportedState: Bool = false
  var lastPositionMs: Int64 = 0
  var lastDurationMs: Int64 = 0
  var lastBufferedMs: Int64 = 0
  var lastCurrentTrackIndex: Int = -1
  var lastVolume: Float = .nan

  private var stateCb: ((RemotePlaybackStateKind) -> Void)?
  private var positionCb: ((Int64, Int64, Int64) -> Void)?

  /// Drive the receiver state the way a live session does: a non-idle report
  /// sets `hasReportedState`. Set `lastState` directly to stage a state the
  /// receiver has not reported yet.
  func fireState(_ state: RemotePlaybackStateKind) {
    lastState = state
    if state != .idle { hasReportedState = true }
    stateCb?(state)
  }

  func firePosition(positionMs: Int64, durationMs: Int64 = 0, bufferedMs: Int64 = 0) {
    lastPositionMs = positionMs
    lastDurationMs = durationMs
    lastBufferedMs = bufferedMs
    positionCb?(positionMs, durationMs, bufferedMs)
  }

  func addStateListener(_ cb: @escaping (RemotePlaybackStateKind) -> Void) -> () -> Void {
    stateCb = cb
    return { [weak self] in self?.stateCb = nil }
  }

  func addPositionListener(_ cb: @escaping (Int64, Int64, Int64) -> Void) -> () -> Void {
    positionCb = cb
    return { [weak self] in self?.positionCb = nil }
  }

  func addCurrentTrackIndexListener(_ cb: @escaping (Int) -> Void) -> () -> Void { {} }
  func addEndedReasonListener(_ cb: @escaping (RemoteEndReasonKind) -> Void) -> () -> Void { {} }
  func addMediaErrorListener(_ cb: @escaping (RemoteMediaErrorPayload) -> Void) -> () -> Void { {} }
  func addSeekCompletedListener(_ cb: @escaping (Int64) -> Void) -> () -> Void { {} }
  func addVolumeListener(_ cb: @escaping (Float) -> Void) -> () -> Void { {} }

  func setVolume(_ value: Float) {}
  func close() {}
  func play() {}
  func pause() {}
  func stop() {}
  func seekTo(positionMs: Int64) {}
  func seekBy(deltaMs: Int64) {}
  func queueNext() {}
  func queuePrev() {}
  func skipToQueueIndex(index: Int) {}
  func setPlaybackRate(_ rate: Float) {}
  func setRepeatMode(_ mode: RemoteRepeatMode) {}

  func load(items: [CastMediaItem], startIndex: Int, startPositionMs: Int64, playWhenReady: Bool) async throws -> [Int] {
    (0..<items.count).map { $0 + 1 }
  }

  func queueInsert(items: [CastMediaItem], beforeIndex: Int?) async throws {}
  func queueRemove(indices: [Int]) async throws {}
  func queueMove(fromIndex: Int, toIndex: Int) async throws {}
  func queueClear() async throws {}
}
