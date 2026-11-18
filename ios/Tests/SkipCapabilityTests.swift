import XCTest
@testable import QueuePlayer
@_spi(QueuePlayerTests) import QueuePlayer

/// Tests for the canSkip surface + `onSkipCapabilityChange`
/// listener emission.
///
/// Covers the cases where `configure(...)` has not yet
/// stood up an AVQueuePlayer — initial cached-getter state, listener
/// registration + auto-fire, dispose closure correctness.
final class SkipCapabilityTests: XCTestCase {

  func testGetCanSkipNextReturnsFalseInitially() throws {
    let player = TrackPlayer()
    XCTAssertFalse(try player.getCanSkipNext())
  }

  func testGetCanSkipPreviousReturnsFalseInitially() throws {
    let player = TrackPlayer()
    XCTAssertFalse(try player.getCanSkipPrevious())
  }

  func testOnSkipCapabilityChangeAutoFiresWithCachedValues() throws {
    let player = TrackPlayer()
    let exp = expectation(description: "auto-fire on subscribe")
    var received: SkipCapability?
    let dispose = try player.onSkipCapabilityChange { capability in
      received = capability
      exp.fulfill()
    }
    wait(for: [exp], timeout: 1.0)
    XCTAssertEqual(received?.canSkipPrevious, false)
    XCTAssertEqual(received?.canSkipNext, false)
    dispose()
  }

  /// A disposed subscription receives nothing once the auto-fire has settled.
  ///
  /// An immediate dispose racing the auto-fire is a race, not a contract: the
  /// fire is enqueued on the owner queue when `subscribe` returns, so the queue
  /// block usually waits while the caller's synchronous dispose lands first,
  /// and under load it does not. What the library guarantees is that the
  /// registry is consulted before the callback runs, so a settled dispose is
  /// final.
  func testDisposeStopsCallbacksOnceSettled() throws {
    let player = TrackPlayer()
    var fireCount = 0
    let dispose = try player.onSkipCapabilityChange { _ in
      fireCount += 1
    }
    dispose()
    drain(player)
    let afterDispose = fireCount

    // A queue with a track on either side, so the recompute below is a real
    // change from the empty-queue capability rather than a deduplicated repeat
    // — the disposed subscription has to be what keeps it from arriving.
    player.playerQueue.async {
      _ = player.queueState.replaceAll((0 ..< 3).map {
        PlayerFixtures.track(id: "t\($0)", url: "https://example.com/\($0).m4a")
      })
      player.currentTrackIndex = 1
      player.recomputeCapabilities()
    }
    drain(player)

    XCTAssertEqual(
      fireCount, afterDispose,
      "a disposed subscription receives no further capability changes")
  }

  /// Two hops: the auto-fire runs on the owner queue and consumers are called
  /// from there, so draining main alone can return before either has run.
  private func drain(_ player: TrackPlayer) {
    let queueDrained = expectation(description: "owner queue drained")
    player.playerQueue.async { queueDrained.fulfill() }
    wait(for: [queueDrained], timeout: 2.0)
    let mainDrained = expectation(description: "main drained")
    DispatchQueue.main.async { mainDrained.fulfill() }
    wait(for: [mainDrained], timeout: 2.0)
  }

  func testOnSkipCapabilityChangeMultiSubscriberEachGetsAutoFire() throws {
    let player = TrackPlayer()
    let expA = expectation(description: "A auto-fire")
    let expB = expectation(description: "B auto-fire")
    var aReceived: SkipCapability?
    var bReceived: SkipCapability?
    let disposeA = try player.onSkipCapabilityChange { capability in
      aReceived = capability; expA.fulfill()
    }
    let disposeB = try player.onSkipCapabilityChange { capability in
      bReceived = capability; expB.fulfill()
    }
    wait(for: [expA, expB], timeout: 1.0)
    XCTAssertEqual(aReceived?.canSkipPrevious, false)
    XCTAssertEqual(aReceived?.canSkipNext, false)
    XCTAssertEqual(bReceived?.canSkipPrevious, false)
    XCTAssertEqual(bReceived?.canSkipNext, false)
    disposeA()
    disposeB()
  }

  func testDisposeIsIdempotent() throws {
    let player = TrackPlayer()
    let dispose = try player.onSkipCapabilityChange { _ in }
    dispose()
    // Calling dispose twice must not crash. (Underlying
    // ListenerRegistry.remove(id:) returns false on missing id but
    // doesn't throw.)
    dispose()
  }

  // MARK: - computeCapabilities

  func testComputeCapabilitiesEnablesBothDirectionsMidQueue() {
    let player = TrackPlayer()
    seedQueue(player, count: 3, currentIndex: 1)
    let capability = player.computeCapabilities()
    XCTAssertTrue(capability.canSkipPrevious)
    XCTAssertTrue(capability.canSkipNext)
  }

  func testComputeCapabilitiesDisablesTheAbsentDirectionAtEachQueueEnd() {
    let player = TrackPlayer()
    seedQueue(player, count: 3, currentIndex: 0)
    let first = player.computeCapabilities()
    XCTAssertFalse(first.canSkipPrevious)
    XCTAssertTrue(first.canSkipNext)

    player.currentTrackIndex = 2
    let last = player.computeCapabilities()
    XCTAssertTrue(last.canSkipPrevious)
    XCTAssertFalse(last.canSkipNext)
  }

  func testComputeCapabilitiesEnablesBothDirectionsUnderRepeatQueue() {
    let player = TrackPlayer()
    seedQueue(player, count: 3, currentIndex: 0)
    player.repeatModeState = .queue
    let capability = player.computeCapabilities()
    XCTAssertTrue(capability.canSkipPrevious)
    XCTAssertTrue(capability.canSkipNext)
  }

  func testComputeCapabilitiesLeavesTheCachedCapabilityUntouched() {
    let player = TrackPlayer()
    seedQueue(player, count: 3, currentIndex: 1)
    player.cachedSkipCapability = SkipCapability(canSkipPrevious: true, canSkipNext: false)

    _ = player.computeCapabilities()

    XCTAssertTrue(player.cachedSkipCapability.canSkipPrevious)
    XCTAssertFalse(player.cachedSkipCapability.canSkipNext)
  }

  // MARK: - buildCarPlayQueueSnapshot

  func testBuildCarPlayQueueSnapshotMirrorsTheQueueAndCurrentIndex() {
    let player = TrackPlayer()
    seedQueue(player, count: 3, currentIndex: 2)
    let snapshot = player.buildCarPlayQueueSnapshot()
    XCTAssertEqual(snapshot?.entries.count, 3)
    XCTAssertEqual(snapshot?.currentIndex, 2)
    XCTAssertEqual(snapshot?.entries.map { $0.title }, ["Track 0", "Track 1", "Track 2"])
  }

  func testBuildCarPlayQueueSnapshotIsNilForAnEmptyQueue() {
    let player = TrackPlayer()
    XCTAssertNil(player.buildCarPlayQueueSnapshot())
  }

  func testBuildCarPlayQueueSnapshotFallsBackToAlbumWhenArtistIsAbsent() {
    let player = TrackPlayer()
    _ = player.queueState.replaceAll([
      makeTrack(index: 0, title: nil, artist: nil, album: "Album"),
      makeTrack(index: 1, title: "Titled", artist: "Artist", album: "Album"),
    ])
    player.currentTrackIndex = 0
    let entries = player.buildCarPlayQueueSnapshot()?.entries
    XCTAssertEqual(entries?[0].title, "", "an absent title renders as an empty string")
    XCTAssertEqual(entries?[0].subtitle, "Album")
    XCTAssertEqual(entries?[1].subtitle, "Artist")
  }

  // MARK: - publishCapabilities

  func testPublishCapabilitiesRefreshesTheCarPlayMirrorWhenTheCapabilityIsUnchanged() {
    let player = TrackPlayer()
    seedQueue(player, count: 3, currentIndex: 1)
    let capability = player.computeCapabilities()
    player.cachedSkipCapability = capability
    defer { CarPlayCoordinator.shared.queueProvider.setSnapshot(nil) }

    player.publishCapabilities(
      capability, queueSnapshot: player.buildCarPlayQueueSnapshot(), carAttached: true)

    XCTAssertEqual(
      CarPlayCoordinator.shared.queueProvider.getSnapshot()?.entries.count, 3,
      "a reorder or metadata change refreshes the mirror even though it leaves "
        + "skip capability untouched")
  }

  func testPublishCapabilitiesClearsTheCarPlayMirrorForAnEmptyQueue() {
    let player = TrackPlayer()
    defer { CarPlayCoordinator.shared.queueProvider.setSnapshot(nil) }
    CarPlayCoordinator.shared.queueProvider.setSnapshot(
      CarPlayQueueSnapshot(
        entries: [CarPlayQueueEntry(title: "stale", subtitle: nil, artworkUrl: nil)],
        currentIndex: 0))

    player.publishCapabilities(
      player.computeCapabilities(),
      queueSnapshot: player.buildCarPlayQueueSnapshot(), carAttached: true)

    XCTAssertNil(CarPlayCoordinator.shared.queueProvider.getSnapshot())
  }

  func testPublishCapabilitiesLeavesTheMirrorAloneWithNoCarAttached() {
    let player = TrackPlayer()
    seedQueue(player, count: 2, currentIndex: 0)
    defer { CarPlayCoordinator.shared.queueProvider.setSnapshot(nil) }
    CarPlayCoordinator.shared.queueProvider.setSnapshot(
      CarPlayQueueSnapshot(
        entries: [CarPlayQueueEntry(title: "held", subtitle: nil, artworkUrl: nil)],
        currentIndex: 0))

    player.publishCapabilities(
      player.computeCapabilities(), queueSnapshot: nil, carAttached: false)

    XCTAssertEqual(
      CarPlayCoordinator.shared.queueProvider.getSnapshot()?.entries.first?.title, "held",
      "a snapshot that was never built because no car is attached must not be "
        + "mistaken for a request to clear")
  }

  func testListenerRegistryContainsAfterAddAndAbsentAfterRemove() {
    // After `add()` returns an id, `contains(id:)` returns true.
    // After `remove(id:)`, `contains(id:)` returns false.
    // Idempotent removal returns false.
    let registry = ListenerRegistry<() -> Void>()
    let id = registry.add({})
    XCTAssertTrue(registry.contains(id: id))
    XCTAssertTrue(registry.remove(id: id))
    XCTAssertFalse(registry.contains(id: id))
    XCTAssertFalse(registry.remove(id: id))
  }
  private func seedQueue(_ player: TrackPlayer, count: Int, currentIndex: Int) {
    _ = player.queueState.replaceAll(
      (0 ..< count).map { makeTrack(index: $0, title: "Track \($0)") })
    player.currentTrackIndex = currentIndex
  }

  private func makeTrack(
    index: Int, title: String?, artist: String? = nil, album: String? = nil
  ) -> TrackItem {
    return TrackItem(
      id: "t\(index)", url: "https://example.com/\(index).m4a",
      title: title, artist: artist, album: album, duration: nil, artworkUrl: nil,
      extras: nil, replayGainTrackGain: nil, replayGainTrackPeak: nil,
      replayGainAlbumGain: nil, replayGainAlbumPeak: nil, isVideo: nil
    )
  }


  /// Restart-or-previous with no loaded track keeps Previous disabled.
  func testRestartModeWithNoLoadedTrackKeepsPreviousDisabled() {
    let player = TrackPlayer()
    player.config = PlayerConfig(httpHeaders: nil, userAgent: nil, placeholderArtworkUri: nil, autoRetries: nil, retryBackoffMs: nil, networkTimeoutMs: nil, audioContentType: nil, audioCategoryOptions: nil, voiceVocabularyDonationEnabled: nil, allowsExternalPlayback: nil,  visualizationEnabled: nil, clampSeekToBuffered: nil, skipToPreviousBehavior: .restartOrPrevious, lookaheadCacheMaxSizeMb: nil, lookaheadCacheEvictionPolicy: nil, progressUpdateIntervalMs: nil, backgroundProgressUpdateIntervalMs: nil, enableDiagnosticLogs: nil)
    seedQueue(player, count: 3, currentIndex: -1)
    XCTAssertFalse(player.computeCapabilities().canSkipPrevious)
  }

  /// Restart-or-previous with a loaded track at index 0 enables Previous.
  func testRestartModeAtIndexZeroEnablesPrevious() {
    let player = TrackPlayer()
    player.config = PlayerConfig(httpHeaders: nil, userAgent: nil, placeholderArtworkUri: nil, autoRetries: nil, retryBackoffMs: nil, networkTimeoutMs: nil, audioContentType: nil, audioCategoryOptions: nil, voiceVocabularyDonationEnabled: nil, allowsExternalPlayback: nil,  visualizationEnabled: nil, clampSeekToBuffered: nil, skipToPreviousBehavior: .restartOrPrevious, lookaheadCacheMaxSizeMb: nil, lookaheadCacheEvictionPolicy: nil, progressUpdateIntervalMs: nil, backgroundProgressUpdateIntervalMs: nil, enableDiagnosticLogs: nil)
    seedQueue(player, count: 3, currentIndex: 0)
    XCTAssertTrue(player.computeCapabilities().canSkipPrevious)
  }

  // MARK: - cast index writes keep capability and source current

  /// A receiver advance to the last track disables Next, as a local advance does.
  func testRemoteTrackChangeToLastIndexDisablesNext() {
    let player = TrackPlayer()
    seedQueue(player, count: 3, currentIndex: 0)
    player.playerQueue.async { player.recomputeCapabilities() }
    drain(player)
    XCTAssertTrue(player.cachedSkipCapability.canSkipNext)

    player.emitRemoteTrackChange(index: 2)
    drain(player)
    XCTAssertEqual(player.currentTrackIndex, 2)
    XCTAssertFalse(player.cachedSkipCapability.canSkipNext)
    XCTAssertTrue(player.cachedSkipCapability.canSkipPrevious)
  }

  /// A receiver advance classifies the source of the track it moved to.
  func testRemoteTrackChangeClassifiesTheNewTracksSource() {
    let player = TrackPlayer()
    _ = player.queueState.replaceAll([
      makeTrack(index: 0, title: "a"),
      makeTrack(index: 1, title: "b"),
      TrackItem(
        id: "local", url: "file:///tmp/local.m4a",
        title: nil, artist: nil, album: nil, duration: nil, artworkUrl: nil,
        extras: nil, replayGainTrackGain: nil, replayGainTrackPeak: nil,
        replayGainAlbumGain: nil, replayGainAlbumPeak: nil, isVideo: nil),
    ])
    player.currentTrackIndex = 0
    player.playerQueue.async { player.reclassifyCurrentTrackSource() }
    drain(player)
    XCTAssertEqual(player.currentTrackSource, .streaming)

    player.emitRemoteTrackChange(index: 2)
    drain(player)
    XCTAssertEqual(player.currentTrackSource, .local)
  }
}
