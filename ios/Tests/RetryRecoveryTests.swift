import AVFoundation
import XCTest
@testable import QueuePlayer

/// The automatic retry rebuilds the failed item and resumes where playback had
/// reached, on whichever engine is active.
final class RetryRecoveryTests: XCTestCase {

  func testVideoSelectionPausesAndStillAnnouncesTheTrack() throws {
    let player = TrackPlayer()
    let engine = RetryEngineStub()
    player.engine = engine
    _ = player.queueState.replaceAll([
      PlayerFixtures.track(id: "video", url: "https://example.com/video.mp4", isVideo: true)
    ])
    player.currentTrackIndex = 0
    player.wantsToPlay = true
    var calls = 0
    let unsubscribe = try player.onTrackChange { track,, _, _ index, _, _ in
      calls += 1
      XCTAssertEqual(track?.isVideo, true)
      XCTAssertEqual(index, 0)
      XCTAssertFalse(player.wantsToPlay)
      XCTAssertGreaterThan(engine.pauseCount, 0)
    }
    player.pendingTrackChangeReason = .userSkipToIndex
    player.performingMutation { player.fullRebuildPlayerQueue() }
    XCTAssertEqual(calls, 1)
    XCTAssertTrue(engine.allMediaItems.isEmpty)
    // An intentionally unloaded video is not an audio drain to skip past.
    player.applyEndVerdict()
    XCTAssertEqual(player.currentTrackIndex, 0)
    XCTAssertEqual(calls, 1)
    player.beginPlayback()
    XCTAssertEqual(engine.playCount, 0)
    unsubscribe()
  }

  /// One automatic attempt by default. A transient failure that survives a
  /// rebuild is rarely cured by a second, and further attempts only delay the
  /// error the consumer needs to act on; `retry()` is the escape hatch after it.
  func testTheAutomaticRetryBudgetIsOneAttemptByDefault() {
    let player = TrackPlayer()
    XCTAssertEqual(player.effectiveAutoRetries(), 1)
  }

  func testTheConsumerCanAskForNoneOrMore() {
    let player = TrackPlayer()
    player.config = PlayerFixtures.config(autoRetries: 0)
    XCTAssertEqual(player.effectiveAutoRetries(), 0, "zero means zero")
    player.config = PlayerFixtures.config(autoRetries: 4)
    XCTAssertEqual(player.effectiveAutoRetries(), 4)
    player.config = PlayerFixtures.config(autoRetries: 99)
    XCTAssertEqual(player.effectiveAutoRetries(), 5, "clamped to the documented ceiling")
    player.config = PlayerFixtures.config(autoRetries: .nan)
    XCTAssertEqual(player.effectiveAutoRetries(), 1, "a value that is not a number falls back")
  }

  /// A crossfade failure reaches the automatic retry.
  ///
  /// The retry is keyed by `queueItemId`, so an engine reporting a failure
  /// without the item leaves `dispatchErrorOrRetry` synthesising a key that
  /// cannot appear in the queue — the retry branch is skipped and a transient
  /// blip goes straight to the consumer instead of being recovered.
  func testAnEngineFailureCarryingItsItemIsRetried() {
    let player = TrackPlayer()
    let engine = RetryEngineStub()
    seed(player, engine: engine)
    let qid = player.queueItemIds[0]
    player.retryAttemptsRemaining[qid] = 1
    // The engine names the item that failed, carrying the queue identity the
    // retry is keyed by.
    let item = AVPlayerItem(url: URL(string: "https://example.com/0.m4a")!)
    item.queueItemId = qid

    player.engineDidFailWithError(
      engine, error: URLError(.timedOut), item: item)

    XCTAssertEqual(
      player.retryAttemptsRemaining[qid], 0,
      "a transient failure spends the track's automatic attempt")
  }

  /// The other half of the same contract: with no item there is nothing to key
  /// a retry against, so the failure is surfaced rather than silently retried.
  func testAFailureWithoutAnItemIsNotRetried() {
    let player = TrackPlayer()
    let engine = RetryEngineStub()
    seed(player, engine: engine)
    let qid = player.queueItemIds[0]
    player.retryAttemptsRemaining[qid] = 1

    player.engineDidFailWithError(
      engine, error: URLError(.timedOut), item: nil)

    XCTAssertEqual(
      player.retryAttemptsRemaining[qid], 1,
      "no item means no automatic attempt is spent")
  }

  /// A repeat of the same failure while the attempt is pending stays inside
  /// the library.
  func testAnEchoOfThePendingFailureIsNotReported() {
    let player = TrackPlayer()
    let engine = RetryEngineStub()
    seed(player, engine: engine)
    let qid = player.queueItemIds[0]
    player.retryAttemptsRemaining[qid] = 1
    let item = AVPlayerItem(url: URL(string: "https://example.com/0.m4a")!)
    item.queueItemId = qid
    var reported: [PlaybackError] = []
    _ = player.errorListeners.add { reported.append($0) }

    player.engineDidFailWithError(engine, error: URLError(.timedOut), item: item)
    player.engineDidFailWithError(engine, error: URLError(.timedOut), item: item)

    XCTAssertEqual(reported.count, 0, "the pending attempt owns both fires")
    XCTAssertEqual(
      player.retryAttemptsRemaining[qid], 0,
      "one failure spends one attempt, however many times it is reported")
  }

  /// A failure after the attempt has run is the consumer's to see.
  func testAFailureAfterTheAttemptIsReported() {
    let player = TrackPlayer()
    let engine = RetryEngineStub()
    seed(player, engine: engine)
    let qid = player.queueItemIds[0]
    player.retryAttemptsRemaining[qid] = 1
    let item = AVPlayerItem(url: URL(string: "https://example.com/0.m4a")!)
    item.queueItemId = qid
    var reported: [PlaybackError] = []
    _ = player.errorListeners.add { reported.append($0) }

    player.engineDidFailWithError(engine, error: URLError(.timedOut), item: item)
    player.retryFailedItem(queueItemId: qid)
    player.engineDidFailWithError(engine, error: URLError(.timedOut), item: item)

    XCTAssertEqual(reported.count, 1, "the attempt was spent and the track is still broken")
  }

  /// A user retry hands back the allowance the consumer configured, so one for
  /// a default player and none for a consumer who turned the automatic attempt
  /// off.
  func testAUserRetryRestoresTheConfiguredBudget() async throws {
    let player = TrackPlayer()
    let engine = RetryEngineStub()
    seed(player, engine: engine)
    player.config = PlayerFixtures.config(autoRetries: 0)
    let qid = player.queueItemIds[0]

    _ = try await player.retry().await()

    XCTAssertEqual(
      player.retryAttemptsRemaining[qid], 0,
      "a consumer who asked for no automatic attempt must not be given one")
  }

  /// The user-facing retry clears `reachedQueueEnd` too — a queue whose
  /// only track failed to load has already drained, and that is the case the
  /// button exists for.
  func testAUserRetryClearsTheEndOfQueueState() async throws {
    let player = TrackPlayer()
    let engine = RetryEngineStub()
    seed(player, engine: engine)
    player.reachedQueueEnd = true

    _ = try await player.retry().await()

    XCTAssertFalse(player.reachedQueueEnd)
  }

  /// The rebuild a user asked for replaces a scheduled one, so the rebuilt
  /// item's own failure is reported rather than taken for an echo.
  func testAUserRetrySupersedesAScheduledAttempt() async throws {
    let player = TrackPlayer()
    let engine = RetryEngineStub()
    seed(player, engine: engine)
    player.pendingRetryQueueItemId = player.queueItemIds[0]

    _ = try await player.retry().await()

    XCTAssertNil(player.pendingRetryQueueItemId)
  }

  /// A retry clears `reachedQueueEnd`.
  ///
  /// A queue whose only track fails to load drains the engine, so the queue-end
  /// verdict calls it finished and parks the player. Without clearing it
  /// the retry seats an item into a queue that believes it is over, and
  /// playback never resumes — the automatic attempt rebuilds and nothing plays.
  func testRetryingClearsTheEndOfQueueState() {
    let player = TrackPlayer()
    let engine = RetryEngineStub()
    seed(player, engine: engine)
    player.reachedQueueEnd = true

    player.retryFailedItem(queueItemId: player.queueItemIds[0])

    XCTAssertFalse(
      player.reachedQueueEnd,
      "a retry is an intent to play this track, so the queue is not over")
  }

  /// A drain whose current item failed with an automatic attempt already
  /// scheduled is not the end of the queue. `AVQueuePlayer` consumes a failed
  /// item and drains to `currentItem == nil`, which reaches `applyEndVerdict`;
  /// deciding a queue-end there clears the play intent the scheduled retry
  /// reads, so the recovered track never resumes. The verdict holds the queue
  /// open while a retry is pending for the current track.
  func testADrainWithAPendingRetryDoesNotEndTheQueue() {
    let player = TrackPlayer()
    let engine = RetryEngineStub()
    seed(player, engine: engine)
    player.currentTrackIndex = 1
    player.wantsToPlay = true
    player.pendingRetryQueueItemId = player.queueItemIds[1]

    player.applyEndVerdict()

    XCTAssertTrue(
      player.wantsToPlay,
      "a pending retry keeps the play intent so the automatic attempt can resume")
    XCTAssertFalse(
      player.reachedQueueEnd,
      "a drain with a retry pending is a recovery in flight, not the end of the queue")
  }

  func testRetryResumesFromWherePlaybackHadReached() {
    let player = TrackPlayer()
    let engine = RetryEngineStub()
    engine.position = 42.5
    seed(player, engine: engine)
    // The crossfade shape: an engine is live, the gapless queue player is not.
    XCTAssertNil(player.player)

    player.retryFailedItem(queueItemId: player.queueItemIds[0])

    XCTAssertEqual(
      engine.seeks.first?.position, 42.5,
      "a blip well into a long track must not restart it from zero")
  }

  func testRetryDoesNotSeekWhenPlaybackHadNotStarted() {
    let player = TrackPlayer()
    let engine = RetryEngineStub()
    engine.position = 0
    seed(player, engine: engine)

    player.retryFailedItem(queueItemId: player.queueItemIds[0])

    XCTAssertTrue(engine.seeks.isEmpty, "position 0 is the rebuild's own starting point")
  }

  func testRetryIsMootOnceTheUserHasMovedOn() {
    let player = TrackPlayer()
    let engine = RetryEngineStub()
    engine.position = 30
    seed(player, engine: engine)
    let staleId = player.queueItemIds[1]
    // The failure belongs to a track the player is no longer on.
    player.retryFailedItem(queueItemId: staleId)

    XCTAssertTrue(engine.seeks.isEmpty)
  }

  // MARK: - The queue stops on a track whose failure was reported

  /// A failure dispatched as its item is seated survives the first
  /// announcement of that position, which happens in the same block.
  func testAFailureAtSeatTimeStillHoldsTheQueueAfterTheTrackIsAnnounced() {
    let player = TrackPlayer()
    let engine = RetryEngineStub()
    seed(player, engine: engine)
    let qid = player.queueItemIds[0]
    let item = AVPlayerItem(url: URL(string: "https://example.com/0.m4a")!)
    item.queueItemId = qid

    player.dispatchErrorOrRetry(
      item: item,
      underlying: NSError(domain: AVFoundationErrorDomain, code: -11828, userInfo: nil))
    player.handleCurrentItemDidChange()

    XCTAssertEqual(
      player.reportedFailureOnCurrentTrack, qid,
      "the track change announcing the failed track must not clear its report")
  }

  /// Announcing a different track clears a report the queue has moved past.
  func testAnnouncingADifferentTrackClearsTheReport() {
    let player = TrackPlayer()
    let engine = RetryEngineStub()
    seed(player, engine: engine)
    player.reportedFailureQueueItemIds = [player.queueItemIds[0]]
    player.currentTrackIndex = 1

    player.dispatchTrackChange(reason: .userSkipNext)

    XCTAssertTrue(player.reportedFailureQueueItemIds.isEmpty)
  }

  /// A forced reinstall builds the current position from scratch, so what was
  /// reported against it no longer describes what is installed.
  func testAForcedReinstallClearsTheReport() {
    let player = TrackPlayer()
    let engine = RetryEngineStub()
    seed(player, engine: engine)
    player.reportedFailureQueueItemIds = [player.queueItemIds[0]]

    player.performingMutation { player.fullRebuildPlayerQueue(forcingReinstall: true) }

    XCTAssertTrue(player.reportedFailureQueueItemIds.isEmpty)
  }

  /// A reconcile that is not rebuilding the position leaves the report alone —
  /// the track it names is still the one that failed.
  func testAPlainRebuildLeavesTheReportAlone() {
    let player = TrackPlayer()
    let engine = RetryEngineStub()
    seed(player, engine: engine)
    let qid = player.queueItemIds[0]
    player.reportedFailureQueueItemIds = [qid]

    player.performingMutation { player.fullRebuildPlayerQueue() }

    XCTAssertEqual(player.reportedFailureQueueItemIds, [qid])
  }

  /// One `play()`, one error. Several tracks of an installed run fail at once —
  /// a window of unplayable sources does exactly that — and only the track the
  /// listener is on is theirs to act on; it reports once however many times it
  /// or its neighbours fire. The record is a set so a repeat cannot be reopened
  /// by a different track replacing it in a one-slot key.
  func testOnlyTheCurrentTrackReportsAndOnlyOnce() {
    let player = TrackPlayer()
    let engine = RetryEngineStub()
    seed(player, engine: engine)
    var reported: [PlaybackError] = []
    _ = player.errorListeners.add { reported.append($0) }
    let fatal = NSError(domain: AVFoundationErrorDomain, code: -11828, userInfo: nil)
    func item(at index: Int) -> AVPlayerItem {
      let made = AVPlayerItem(url: URL(string: "https://example.com/\(index).m4a")!)
      made.queueItemId = player.queueItemIds[index]
      return made
    }

    // Two tracks failing, each reported twice, in the order that defeats a
    // one-slot key: every fire names a different track than the one before it.
    player.dispatchErrorOrRetry(item: item(at: 0), underlying: fatal)
    player.dispatchErrorOrRetry(item: item(at: 1), underlying: fatal)
    player.dispatchErrorOrRetry(item: item(at: 0), underlying: fatal)
    player.dispatchErrorOrRetry(item: item(at: 1), underlying: fatal)

    XCTAssertEqual(
      reported.count, 1,
      "one play(), one error: the track being played, once, whatever else fails")
    XCTAssertEqual(
      reported.first?.queueItemId, player.queueItemIds[0],
      "the error names the track the listener is on")
    XCTAssertEqual(
      player.reportedFailureQueueItemIds, Set([player.queueItemIds[0]]),
      "and only that track holds the queue")
  }

  /// Seating a position is an attempt at it, so a skip onto a track that
  /// reported earlier clears its report. Without this the track is wedged:
  /// it never reports again, its run is never extended, and it never reaches
  /// an end verdict — silently, until play().
  func testSkippingOntoAReportedTrackClearsItsReport() {
    let player = TrackPlayer()
    let engine = RetryEngineStub()
    seed(player, engine: engine)
    player.reportedFailureQueueItemIds = [player.queueItemIds[1]]

    player.applyNewCurrentIndex(newIndex: 1, reason: .userSkipToIndex)

    XCTAssertTrue(
      player.reportedFailureQueueItemIds.isEmpty,
      "the track being skipped onto is being tried again")
  }

  // MARK: - Routed failures and the read-through listener

  /// A current routed item built against a listener that has since been
  /// replaced is retried on the live one, with no report and no budget spent.
  func testACurrentRoutedItemOnAReplacedListenerIsRetriedSilently() throws {
    let (player, server) = try seedRouted()
    defer { server.stop() }
    let qid = player.queueItemIds[0]
    player.retryAttemptsRemaining[qid] = 1
    var reported: [PlaybackError] = []
    _ = player.errorListeners.add { reported.append($0) }

    player.playerQueue.sync {
      player.dispatchErrorOrRetry(item: routedItem(qid), underlying: connectionLost)
    }

    player.playerQueue.sync {
      XCTAssertTrue(reported.isEmpty)
      XCTAssertEqual(player.retryAttemptsRemaining[qid], 1, "the track's own budget is untouched")
      XCTAssertNil(player.pendingRetryQueueItemId, "the queued attempt has run")
    }
  }

  /// The recovery runs once per position: a second failure after the attempt is
  /// the consumer's to see, so a listener still dead after a restart is reported.
  func testASecondRoutedFailureOfTheSameTrackIsReported() throws {
    let (player, server) = try seedRouted()
    defer { server.stop() }
    let qid = player.queueItemIds[0]
    var reported: [PlaybackError] = []
    _ = player.errorListeners.add { reported.append($0) }

    player.playerQueue.sync {
      player.dispatchErrorOrRetry(item: routedItem(qid), underlying: connectionLost)
    }
    player.playerQueue.sync {
      player.dispatchErrorOrRetry(item: routedItem(qid), underlying: connectionLost)
    }

    player.playerQueue.sync { XCTAssertEqual(reported.count, 1) }
  }

  /// Playing again and skipping onto the track each re-arm its recovery.
  func testPlayingOrSkippingOntoATrackRearmsItsRoutedRecovery() throws {
    let (player, server) = try seedRouted()
    defer { server.stop() }
    let qid = player.queueItemIds[0]

    player.playerQueue.sync {
      player.routedRecoveryAttemptedQueueItemIds.insert(qid)
      player.emitState(.playing, reason: .user)
      XCTAssertFalse(player.routedRecoveryAttemptedQueueItemIds.contains(qid))

      player.routedRecoveryAttemptedQueueItemIds.insert(qid)
      player.applyNewCurrentIndex(newIndex: 0, reason: .userSkipToIndex)
      XCTAssertFalse(player.routedRecoveryAttemptedQueueItemIds.contains(qid))
    }
  }

  /// A new queue re-arms every position's recovery.
  func testANewQueueRearmsRoutedRecovery() async throws {
    let (player, server) = try seedRouted()
    defer { server.stop() }
    _ = player.playerQueue.sync { player.routedRecoveryAttemptedQueueItemIds.insert(player.queueItemIds[0]) }

    _ = try await player.setQueue(tracks: Array(player.tracks), startAtIndex: 0).await()

    player.playerQueue.sync { XCTAssertTrue(player.routedRecoveryAttemptedQueueItemIds.isEmpty) }
  }

  /// Only the current track is retried. Another track's routed failure is
  /// reported as before, and its item is left for the next reinstall.
  func testANonCurrentRoutedItemOnAReplacedListenerIsReportedAndOwesAReinstall() throws {
    let (player, server) = try seedRouted()
    defer { server.stop() }
    let other = player.queueItemIds[1]
    var reported: [PlaybackError] = []
    _ = player.errorListeners.add { reported.append($0) }

    player.playerQueue.sync {
      player.dispatchErrorOrRetry(item: routedItem(other), underlying: connectionLost)
    }

    player.playerQueue.sync {
      XCTAssertEqual(reported.count, 1)
      XCTAssertTrue(player.readThroughReinstallPending)
    }
  }

  /// Control: the same failure on an item that does not go through the server
  /// is the source's, and is reported.
  func testAnUnroutedFailureIsReported() throws {
    let (player, server) = try seedRouted()
    defer { server.stop() }
    let qid = player.queueItemIds[0]
    var reported: [PlaybackError] = []
    _ = player.errorListeners.add { reported.append($0) }
    let item = AVPlayerItem(url: URL(string: "https://example.com/0.m4a")!)
    item.queueItemId = qid

    player.playerQueue.sync {
      player.dispatchErrorOrRetry(item: item, underlying: connectionLost)
    }

    player.playerQueue.sync { XCTAssertEqual(reported.count, 1) }
  }

  /// Control: a routed item on a listener that answers failed at its origin,
  /// and is reported.
  func testARoutedFailureOnALiveListenerIsReported() throws {
    let (player, server) = try seedRouted()
    defer { server.stop() }
    let qid = player.queueItemIds[0]
    var reported: [PlaybackError] = []
    _ = player.errorListeners.add { reported.append($0) }

    player.playerQueue.sync {
      player.dispatchErrorOrRetry(
        item: routedItem(qid, port: server.boundPortValue()), underlying: connectionLost)
    }

    player.playerQueue.sync { XCTAssertEqual(reported.count, 1) }
  }

  private let connectionLost = NSError(
    domain: NSURLErrorDomain, code: NSURLErrorNetworkConnectionLost, userInfo: nil)

  /// A player with a live read-through server, two tracks, no automatic
  /// retries, and the first track current.
  private func seedRouted() throws -> (TrackPlayer, ReadThroughServer) {
    let player = TrackPlayer()
    seed(player, engine: RetryEngineStub())
    for qid in player.queueItemIds { player.retryAttemptsRemaining[qid] = 0 }
    let server = ReadThroughServer(cachedFileForOrigin: { _ in nil }, onFirstDiskHit: { _ in })
    server.start()
    XCTAssertTrue(server.waitUntilBound())
    player.readThroughServer = server
    return (player, server)
  }

  /// A routed item addressing `port`; port 1 stands for a listener that has
  /// been replaced, since nothing listens there.
  private func routedItem(_ qid: String, port: UInt16 = 1) -> AVPlayerItem {
    let token = String(repeating: "a", count: 32)
    let item = AVPlayerItem(url: URL(string: "http://127.0.0.1:\(port)/media/\(token)")!)
    item.queueItemId = qid
    item.isRoutedThroughReadThroughServer = true
    return item
  }

  private func seed(_ player: TrackPlayer, engine: RetryEngineStub) {
    _ = player.queueState.replaceAll((0 ..< 2).map {
      TrackItem(
        id: "t\($0)", url: "https://example.com/\($0).m4a", title: nil, artist: nil,
        album: nil, duration: nil, artworkUrl: nil, extras: nil,
        replayGainTrackGain: nil, replayGainTrackPeak: nil,
        replayGainAlbumGain: nil, replayGainAlbumPeak: nil, isVideo: nil)
    })
    player.currentTrackIndex = 0
    player.engine = engine
  }
}

/// Records the seeks the retry issues; everything else is inert.
private final class RetryEngineStub: PlaybackEngine {
  var pauseCount = 0
  var playCount = 0
  var position: TimeInterval = 0
  private(set) var seeks: [(index: Int, position: TimeInterval)] = []

  var currentPositionSeconds: TimeInterval { position }
  func seek(toIndex index: Int, position: TimeInterval) {
    seeks.append((index, position))
  }

  func setAudioMixProvider(_ provider: AudioMixProvider?) {}
  func refreshAllItemMixes() {}
  func release() {}
  /// Holds what it is given, so a caller resolving a queue position to an
  /// engine index by `queueItemId` gets the same answer a real engine would.
  func setItems(_ items: [AVPlayerItem], startIndex: Int, startPositionSeconds: TimeInterval) {
    installed = items
  }
  @discardableResult
  func insertItems(_ items: [AVPlayerItem], after: AVPlayerItem?) -> Bool {
    installed.append(contentsOf: items)
    return true
  }
  func remove(_ item: AVPlayerItem) {
    installed.removeAll { $0 === item }
  }
  func removeAllItems() {}
  func canInsert(_ item: AVPlayerItem, after: AVPlayerItem?) -> Bool { true }
  func play() { playCount += 1 }
  func pause() { pauseCount += 1 }
  func stop() {}
  func skipToNext() {}
  func skipToPrevious() {}
  func setVolume(_ volume: Float) {}
  func setPlaybackSpeed(_ speed: Float) {}
  func seedPlaybackSpeed(_ speed: Float) {}
  func setRepeatMode(_ mode: RepeatMode) {}
  func setPitchCorrectionMode(_ mode: PitchCorrectionMode) {}

  var currentDurationSeconds: TimeInterval { 0 }
  var isPlaying: Bool { false }
  var currentMediaItemIndex: Int { -1 }
  var bufferedPositionSeconds: TimeInterval { 0 }
  var timeControlStatus: AVPlayer.TimeControlStatus { .paused }
  var currentMediaItem: AVPlayerItem? { nil }
  private var installed: [AVPlayerItem] = []
  var allMediaItems: [AVPlayerItem] { installed }
  /// No fade to schedule — this stub exercises the rest of the surface.
  var isFadePendingOrActive: Bool { false }
  /// Nothing attached — this stub exercises the rest of the surface.
  var attachedQueueItemIds: [String] { [] }

  var delegate: PlaybackEngineDelegate?
}
