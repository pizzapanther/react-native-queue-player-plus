import XCTest
@_spi(QueuePlayerTests) import QueuePlayer

/// XCTest's `fulfillment(of:timeout:)` requires a wall-clock cap;
/// the await returns the moment the expectation fulfills, so a
/// generous cap doesn't slow the success path. Sized for GitHub
/// Actions `macos-latest` shared-runner contention — the wait
/// covers ~6 dispatch hops across Swift concurrency + an
/// `AsyncBarrier` round-trip in the orchestrator. Local M-series
/// completes in milliseconds; the cap exists to catch genuine
/// hangs, not to guess at scheduling latency.
private let ASYNC_DISPATCH_TIMEOUT: TimeInterval = 30.0

/// `LookaheadCachePrefetcher` unit tests.
///
/// Two surfaces:
///
/// 1. **`computeTargets` (pure)** — windowing arithmetic over (tracks,
///    currentIndex, lookaheadCount), plus the file://-bypass filter.
///    Mirrors Android's `LookaheadCacheWriterTest.computeTargets`
///    matrix 1-for-1 to keep cross-platform parity verifiable.
///
/// 2. **Orchestration via fake `LookaheadCacheDownloader`** —
///    verifies the sliding-window enqueue + prune behaviour, the
///    look-back tail, the in-flight keep/cancel rule, and the
///    drain-on-tearDown contract.
///
/// `@MainActor` because `reschedule` / `cancel` / `tearDown` assert the queue
/// the prefetcher was confined to, and these tests confine it to main.
@MainActor
final class LookaheadCachePrefetcherTests: XCTestCase {

  // MARK: - computeTargets

  func testComputeTargetsReturnsEmptyForEmptyQueue() {
    let p = newPrefetcher()
    XCTAssertTrue(p.computeTargets(tracks: [], currentIndex: -1, lookaheadCount: 3).isEmpty)
    XCTAssertTrue(p.computeTargets(tracks: [], currentIndex: 0, lookaheadCount: 3).isEmpty)
  }

  func testComputeTargetsReturnsEmptyForNonPositiveLookaheadCount() {
    let p = newPrefetcher()
    let q = [httpTrack("a"), httpTrack("b")]
    XCTAssertTrue(p.computeTargets(tracks: q, currentIndex: 0, lookaheadCount: 0).isEmpty)
    XCTAssertTrue(p.computeTargets(tracks: q, currentIndex: 0, lookaheadCount: -1).isEmpty)
  }

  func testComputeTargetsReturnsNextNRemoteTracksPastCurrentIndex() {
    let p = newPrefetcher()
    let q = (0..<10).map { httpTrack("t\($0)") }
    let targets = p.computeTargets(tracks: q, currentIndex: 2, lookaheadCount: 3)
    XCTAssertEqual(targets.map { $0.id }, ["t3", "t4", "t5"])
  }

  func testComputeTargetsClampsWindowToEndOfQueue() {
    let p = newPrefetcher()
    let q = (0..<5).map { httpTrack("t\($0)") }
    let targets = p.computeTargets(tracks: q, currentIndex: 3, lookaheadCount: 3)
    XCTAssertEqual(targets.map { $0.id }, ["t4"])
  }

  func testComputeTargetsReturnsEmptyAtTail() {
    let p = newPrefetcher()
    let q = (0..<5).map { httpTrack("t\($0)") }
    let targets = p.computeTargets(tracks: q, currentIndex: 4, lookaheadCount: 3)
    XCTAssertTrue(targets.isEmpty)
  }

  func testComputeTargetsStartsAtZeroWhenCurrentIndexIsMinusOne() {
    let p = newPrefetcher()
    let q = (0..<5).map { httpTrack("t\($0)") }
    let targets = p.computeTargets(tracks: q, currentIndex: -1, lookaheadCount: 3)
    XCTAssertEqual(targets.map { $0.id }, ["t0", "t1", "t2"])
  }

  func testComputeTargetsClampsSubMinusOneCurrentIndexToZero() {
    let p = newPrefetcher()
    let q = (0..<3).map { httpTrack("t\($0)") }
    let targets = p.computeTargets(tracks: q, currentIndex: -42, lookaheadCount: 3)
    XCTAssertEqual(targets.map { $0.id }, ["t0", "t1", "t2"])
  }

  func testComputeTargetsFiltersOutFileURIs() {
    let p = newPrefetcher()
    let q: [TrackItem] = [
      httpTrack("a"),
      makeTrack(id: "local-1", url: "file:///tmp/song.m4a"),
      httpTrack("c"),
      makeTrack(id: "local-2", url: "file:///tmp/other.m4a"),
      httpTrack("e"),
    ]
    let targets = p.computeTargets(tracks: q, currentIndex: 0, lookaheadCount: 4)
    XCTAssertEqual(targets.map { $0.id }, ["c", "e"])
  }

  func testComputeTargetsFiltersOutAssetAndContentURIs() {
    let p = newPrefetcher()
    let q: [TrackItem] = [
      httpTrack("a"),
      makeTrack(id: "asset", url: "asset:///bundled.mp3"),
      makeTrack(id: "content", url: "content://media/external/audio/123"),
      httpTrack("d"),
    ]
    let targets = p.computeTargets(tracks: q, currentIndex: 0, lookaheadCount: 3)
    XCTAssertEqual(targets.map { $0.id }, ["d"])
  }

  func testComputeTargetsRejectsNonProgressiveStreamingSchemes() {
    // Only http(s) schemes are accepted; rtsp/rtmp/dash/ws/etc.
    // are filtered out because the cache assumes seekable
    // byte-range downloads.
    let p = newPrefetcher()
    let q: [TrackItem] = [
      makeTrack(id: "rtsp-track", url: "rtsp://example.com/stream"),
      makeTrack(id: "rtmp-track", url: "rtmp://example.com/live"),
      makeTrack(id: "dash-track", url: "dash://example.com/manifest.mpd"),
      makeTrack(id: "ws-track", url: "ws://example.com/socket"),
      httpTrack("legit"),
    ]
    let targets = p.computeTargets(tracks: q, currentIndex: -1, lookaheadCount: 5)
    XCTAssertEqual(targets.map { $0.id }, ["legit"])
  }

  func testComputeTargetsAcceptsBothHttpAndHttps() {
    let p = newPrefetcher()
    let q: [TrackItem] = [
      httpTrack("a"),
      makeTrack(id: "https-track", url: "https://example.com/2.m4a"),
      makeTrack(id: "http-track", url: "http://example.com/3.m4a"),
    ]
    let targets = p.computeTargets(tracks: q, currentIndex: 0, lookaheadCount: 3)
    XCTAssertEqual(targets.map { $0.id }, ["https-track", "http-track"])
  }

  // MARK: - Orchestration

  func testRescheduleRunsOnTheInjectedQueueRatherThanMain() async {
    let downloader = StubDownloader()
    let confinement = DispatchQueue(label: "test.prefetcher.confinement")
    let p = LookaheadCachePrefetcher(downloader: downloader, confinedTo: confinement)
    let tracks = (0 ..< 3).map { httpTrack("q\($0)") }

    // The precondition inside `reschedule` fails the run if the caller is not
    // on the confinement queue, so entering from it is the assertion.
    let scheduled = expectation(description: "reschedule accepted from the injected queue")
    // A `DispatchWorkItem` rather than a trailing closure: the prefetcher is
    // confined to `confinement`, not shared across threads, so it does not
    // belong in a `@Sendable` closure.
    confinement.async(execute: DispatchWorkItem {
      p.reschedule(tracks: tracks, currentIndex: 0, lookaheadCount: 2)
      scheduled.fulfill()
    })
    await fulfillment(of: [scheduled], timeout: 2.0)
    await p.awaitCurrent()

    XCTAssertEqual(
      downloader.invocations, ["q1", "q2"],
      "the lookahead covers the tracks ahead of the current one, in order")
  }

  func testRescheduleWithEmptyTargetsLeavesOrchestratorIdle() async {
    let downloader = StubDownloader()
    let p = LookaheadCachePrefetcher(downloader: downloader, confinedTo: .main)
    p.reschedule(tracks: [], currentIndex: 0, lookaheadCount: 3)
    await p.awaitCurrent()
    XCTAssertFalse(p.hasInFlightTask)
    XCTAssertTrue(downloader.invocations.isEmpty)
  }

  func testRescheduleDispatchesDownloadsSequentiallyInQueueOrder() async {
    let downloader = StubDownloader()
    let p = LookaheadCachePrefetcher(downloader: downloader, confinedTo: .main)
    let q = (0..<5).map { httpTrack("t\($0)") }

    p.reschedule(tracks: q, currentIndex: 0, lookaheadCount: 3)
    await p.awaitCurrent()

    // currentIndex = 0 → look-back is suppressed (head IS current).
    XCTAssertEqual(downloader.invocations, ["t1", "t2", "t3"])
  }

  func testRescheduleSwallowsPerTrackErrorAndContinues() async {
    let downloader = StubDownloader(failingTrackIds: ["t2"])
    let p = LookaheadCachePrefetcher(downloader: downloader, confinedTo: .main)
    let q = (0..<5).map { httpTrack("t\($0)") }

    p.reschedule(tracks: q, currentIndex: 0, lookaheadCount: 3)
    await p.awaitCurrent()

    XCTAssertEqual(downloader.invocations, ["t1", "t2", "t3"],
      "t2 threw but t3 must still be downloaded")
  }

  func testPreviouslyErroredUrlReEnqueuesOnNextReschedule() async {
    // Network-flake recovery contract: a transient error swallows the
    // download attempt without marking the URL "done"; the next
    // reschedule that still wants that URL must retry it.
    let downloader = StubDownloader(failingTrackIds: ["t2"])
    let p = LookaheadCachePrefetcher(downloader: downloader, confinedTo: .main)
    let q = (0..<5).map { httpTrack("t\($0)") }

    p.reschedule(tracks: q, currentIndex: 0, lookaheadCount: 3)
    await p.awaitCurrent()
    XCTAssertEqual(downloader.invocations, ["t1", "t2", "t3"])

    // Connection restored — t2 no longer throws.
    downloader.setFailingTrackIds([])

    // Second reschedule with the same window. t1 + t3 are already
    // cached (downloaded successfully on attempt 1); only t2 needs
    // retry. Use isFullyCached behaviour: StubDownloader does NOT
    // track its own completed downloads, so this test relies on the
    // orchestrator's "always re-attempt missing URLs" contract.
    // After the second reschedule, t2 must appear in invocations
    // again.
    p.reschedule(tracks: q, currentIndex: 0, lookaheadCount: 3)
    await p.awaitCurrent()

    let t2Count = downloader.invocations.filter { $0 == "t2" }.count
    XCTAssertGreaterThanOrEqual(t2Count, 2,
      "t2 must re-attempt on next reschedule after a swallowed error")
  }

  func testNetworkOutageDrainsCleanlyAndRecoversOnNextReschedule() async {
    // Complete-outage recovery: every target in the window throws.
    // Orchestrator must drain pendingTargets cleanly (not hang),
    // leaving the prefetcher idle and ready for a fresh reschedule
    // when connectivity returns.
    let downloader = StubDownloader(failingTrackIds: ["t1", "t2", "t3"])
    let p = LookaheadCachePrefetcher(downloader: downloader, confinedTo: .main)
    let q = (0..<5).map { httpTrack("t\($0)") }

    p.reschedule(tracks: q, currentIndex: 0, lookaheadCount: 3)
    await p.awaitCurrent()

    XCTAssertEqual(downloader.invocations, ["t1", "t2", "t3"],
      "every target attempted exactly once, errors swallowed individually")
    XCTAssertNil(p.currentlyDownloadingUrl, "orchestrator must be idle after drain")

    // Connection restored.
    downloader.setFailingTrackIds([])

    p.reschedule(tracks: q, currentIndex: 0, lookaheadCount: 3)
    await p.awaitCurrent()

    XCTAssertEqual(downloader.invocations.count, 6,
      "second reschedule must retry all 3 missing URLs after the outage")
  }

  func testRescheduleKeepsInFlightWhenItsUrlIsStillInTheNewWindow() async {
    // Sliding window: cur=0 advances to cur=1. The original
    // in-flight url old-1 (== tracks[1]) is still in the new
    // window as tracks[1+1-1] (= tracks[1] of the new layout
    // because the queue is unchanged), so the orchestrator must
    // KEEP the in-flight download rather than cancel it.
    let firstStarted = expectation(description: "first download started")
    let firstHold = AsyncHold()
    let downloader = GatedDownloader(
      gateTrackId: "old-1",
      onStart: { firstStarted.fulfill() },
      hold: firstHold
    )
    let p = LookaheadCachePrefetcher(downloader: downloader, confinedTo: .main)
    let queue = [
      httpTrack("cur"), httpTrack("old-1"), httpTrack("old-2"),
      httpTrack("old-3"), httpTrack("old-4"),
    ]

    p.reschedule(tracks: queue, currentIndex: 0, lookaheadCount: 1)
    await fulfillment(of: [firstStarted], timeout: ASYNC_DISPATCH_TIMEOUT)

    // Same queue, currentIndex unchanged but lookaheadCount widened
    // so old-1 stays in window. The in-flight URL must still be
    // old-1 — not cancelled, not replaced.
    p.reschedule(tracks: queue, currentIndex: 0, lookaheadCount: 3)
    XCTAssertEqual(p.currentlyDownloadingUrl, "https://example.com/old-1.m4a",
      "in-flight URL must be retained when still desired")

    firstHold.release()
    await p.awaitCurrent()

    XCTAssertEqual(downloader.invocations.filter { $0 == "old-1" }.count, 1,
      "old-1 must download exactly once")
  }

  func testRescheduleCancelsInFlightWhenItsUrlLeavesTheWindow() async {
    let firstStarted = expectation(description: "first download started")
    let firstHold = AsyncHold()
    let downloader = GatedDownloader(
      gateTrackId: "old-1",
      onStart: { firstStarted.fulfill() },
      hold: firstHold
    )
    let p = LookaheadCachePrefetcher(downloader: downloader, confinedTo: .main)

    p.reschedule(
      tracks: [httpTrack("cur"), httpTrack("old-1"), httpTrack("old-2")],
      currentIndex: 0,
      lookaheadCount: 3
    )
    await fulfillment(of: [firstStarted], timeout: ASYNC_DISPATCH_TIMEOUT)

    // Disjoint queue: old-1 leaves the window, in-flight must
    // cancel + new-1 must download.
    p.reschedule(
      tracks: [httpTrack("cur"), httpTrack("new-1")],
      currentIndex: 0,
      lookaheadCount: 3
    )

    firstHold.release()
    await p.awaitCurrent()

    XCTAssertTrue(downloader.invocations.contains("new-1"),
      "new-1 must have been downloaded after old-1 left the window")
    XCTAssertFalse(downloader.invocations.contains("old-2"),
      "old-2 must not have been downloaded — pruned out before iteration")
  }

  func testRescheduleAppendsLookbackAsLastTargetAfterForwardWindow() async {
    let downloader = StubDownloader()
    let p = LookaheadCachePrefetcher(downloader: downloader, confinedTo: .main)
    let q = (0..<10).map { httpTrack("t\($0)") }

    // currentIndex = 5 → forward window [6,7,8] + look-back t0.
    p.reschedule(tracks: q, currentIndex: 5, lookaheadCount: 3)
    await p.awaitCurrent()

    XCTAssertEqual(downloader.invocations, ["t6", "t7", "t8", "t0"],
      "look-back t0 must follow the forward window")
  }

  func testRescheduleOmitsLookbackWhenCurrentTrackIsTrackZero() async {
    let downloader = StubDownloader()
    let p = LookaheadCachePrefetcher(downloader: downloader, confinedTo: .main)
    let q = (0..<5).map { httpTrack("t\($0)") }

    p.reschedule(tracks: q, currentIndex: 0, lookaheadCount: 3)
    await p.awaitCurrent()

    XCTAssertEqual(downloader.invocations, ["t1", "t2", "t3"],
      "look-back must not duplicate the current track")
  }

  func testRescheduleOmitsLookbackWhenTrackZeroIsNonRemote() async {
    let downloader = StubDownloader()
    let p = LookaheadCachePrefetcher(downloader: downloader, confinedTo: .main)
    let q: [TrackItem] = [
      makeTrack(id: "local-0", url: "file:///tmp/0.m4a"),
      httpTrack("t1"), httpTrack("t2"), httpTrack("t3"), httpTrack("t4"),
    ]

    // currentIndex = 2, lookahead = 1 → forward = [t3]; look-back
    // would be t0 but t0 is local → look-back suppressed.
    p.reschedule(tracks: q, currentIndex: 2, lookaheadCount: 1)
    await p.awaitCurrent()

    XCTAssertEqual(downloader.invocations, ["t3"],
      "non-remote head must not be appended as a look-back target")
  }

  func testRescheduleDoesNotReEnqueueAlreadyCachedUrl() async {
    let downloader = StubDownloader(cachedUrls: ["https://example.com/t2.m4a"])
    let p = LookaheadCachePrefetcher(downloader: downloader, confinedTo: .main)
    let q = (0..<5).map { httpTrack("t\($0)") }

    p.reschedule(tracks: q, currentIndex: 0, lookaheadCount: 3)
    await p.awaitCurrent()

    XCTAssertEqual(downloader.invocations, ["t1", "t3"],
      "t2 is already cached and must be skipped")
  }

  func testSlidingWindowKeepsCacheFilledAcrossSuccessiveAutoAdvances() async {
    // Simulate playback advancing one track at a time. Each
    // reschedule slides the window forward by one; the downloader
    // records what it's downloaded so the de-duplication path is
    // exercised on subsequent overlapping windows. Verify every
    // remote track within reach downloads exactly once.
    let downloader = TrackingDownloader()
    let p = LookaheadCachePrefetcher(downloader: downloader, confinedTo: .main)
    let q = (0..<8).map { httpTrack("t\($0)") }

    for cur in 0..<7 {
      p.reschedule(tracks: q, currentIndex: cur, lookaheadCount: 3)
      // Drain between advances so in-flight completion + pending
      // pop run cleanly between slides.
      await p.awaitCurrent()
    }

    let unique = Set(downloader.invocations)
    XCTAssertEqual(unique, Set(["t1", "t2", "t3", "t4", "t5", "t6", "t7", "t0"]),
      "every remote track in the window range must download at least once")
    for url in unique {
      XCTAssertEqual(downloader.invocations.filter { $0 == url }.count, 1,
        "track \(url) must download exactly once across successive advances")
    }
  }

  func testCancelThenRescheduleDoesNotSpawnDuplicateOrchestrators() async {
    // Cancel-then-reschedule sequence must hand off cleanly: the
    // cancelled orchestrator's trailing main hop fires AFTER the new
    // reschedule has already launched a fresh orchestrator. Without
    // an identity check, the stale hook would clobber the new
    // orchestrator's tracking state and a subsequent reschedule would
    // launch a second concurrent loop.
    let firstStarted = expectation(description: "first download started")
    let firstHold = AsyncHold()
    let downloader = GatedDownloader(
      gateTrackId: "old-1",
      onStart: { firstStarted.fulfill() },
      hold: firstHold
    )
    let p = LookaheadCachePrefetcher(downloader: downloader, confinedTo: .main)

    p.reschedule(
      tracks: [httpTrack("cur"), httpTrack("old-1"), httpTrack("old-2")],
      currentIndex: 0,
      lookaheadCount: 3
    )
    await fulfillment(of: [firstStarted], timeout: ASYNC_DISPATCH_TIMEOUT)

    p.cancel()
    p.reschedule(
      tracks: [httpTrack("cur"), httpTrack("new-1"), httpTrack("new-2")],
      currentIndex: 0,
      lookaheadCount: 3
    )

    // Release the cancelled chain — its trailing hook must NOT
    // clobber the new orchestrator's state.
    firstHold.release()
    await p.awaitCurrent()

    XCTAssertEqual(downloader.invocations.filter { $0 == "new-1" }.count, 1,
      "new-1 must download exactly once — duplicate orchestrators would race")
    XCTAssertEqual(downloader.invocations.filter { $0 == "new-2" }.count, 1,
      "new-2 must download exactly once")

    // After drain, a fresh reschedule must successfully relaunch
    // (proves the identity check left the running flag in the
    // correct state).
    p.reschedule(
      tracks: [httpTrack("cur"), httpTrack("after-drain")],
      currentIndex: 0,
      lookaheadCount: 1
    )
    await p.awaitCurrent()
    XCTAssertTrue(downloader.invocations.contains("after-drain"),
      "post-drain reschedule must launch a fresh orchestrator")
  }

  func testTearDownCancelsInFlightTask() async {
    let started = expectation(description: "download started")
    let hold = AsyncHold()
    let downloader = GatedDownloader(
      gateTrackId: "gated",
      onStart: { started.fulfill() },
      hold: hold
    )
    let p = LookaheadCachePrefetcher(downloader: downloader, confinedTo: .main)
    p.reschedule(
      tracks: [httpTrack("cur"), httpTrack("gated"), httpTrack("after")],
      currentIndex: 0,
      lookaheadCount: 3
    )
    await fulfillment(of: [started], timeout: ASYNC_DISPATCH_TIMEOUT)

    p.tearDown()
    hold.release()
    await p.awaitCurrent()

    XCTAssertFalse(p.hasInFlightTask, "tearDown must clear orchestrator state")
    XCTAssertFalse(downloader.invocations.contains("after"),
      "tearDown must prevent further iterations")
  }

  // MARK: - Helpers

  private func newPrefetcher() -> LookaheadCachePrefetcher {
    return LookaheadCachePrefetcher(downloader: StubDownloader(), confinedTo: .main)
  }

  private func httpTrack(_ id: String) -> TrackItem {
    return makeTrack(id: id, url: "https://example.com/\(id).m4a")
  }

  private func makeTrack(id: String, url: String) -> TrackItem {
    return TrackItem(
      id: id, url: url, title: nil, artist: nil, album: nil,
      duration: nil, artworkUrl: nil, extras: nil,
      replayGainTrackGain: nil, replayGainTrackPeak: nil,
      replayGainAlbumGain: nil, replayGainAlbumPeak: nil
    )
  }
}

// MARK: - Test stubs

/// Records every download invocation; optionally throws for specific
/// trackIds so the swallow-and-continue path is exercisable. The
/// `cachedUrls` set lets a test seed the prefetcher's "is already
/// cached?" check so the skip-cached path is exercisable too.
final class StubDownloader: LookaheadCacheDownloader, @unchecked Sendable {
  private let lock = NSLock()
  private var _invocations: [String] = []
  private var _failingTrackIds: Set<String>
  private let cachedUrls: Set<String>

  init(failingTrackIds: Set<String> = [], cachedUrls: Set<String> = []) {
    self._failingTrackIds = failingTrackIds
    self.cachedUrls = cachedUrls
  }

  var invocations: [String] {
    lock.lock(); defer { lock.unlock() }
    return _invocations
  }

  /// Mutate the failing-id set mid-test so a recovery scenario can
  /// flip a track from "fails" to "succeeds" between two reschedules.
  func setFailingTrackIds(_ ids: Set<String>) {
    lock.lock(); defer { lock.unlock() }
    _failingTrackIds = ids
  }

  func download(track: TrackItem, headers: [String: String]?) async throws {
    // Test fixtures supply non-nil track.id values; the `?? ""`
    // satisfies the optional type while keeping the assertions
    // simple. The lib itself never assumes track.id is non-empty —
    // these tests verify orchestration order using fixture-supplied
    // identifiers.
    let shouldFail = lock.withLock {
      _invocations.append(track.id ?? "")
      return _failingTrackIds.contains(track.id ?? "")
    }
    if shouldFail {
      throw LookaheadCacheError.httpError(statusCode: 500)
    }
  }

  func isFullyCached(url: String) -> Bool {
    return cachedUrls.contains(url)
  }
}

/// Downloader that records every download it completes and reports
/// `isFullyCached` for any URL it has previously seen. Used by the
/// sliding-window integration test so the prefetcher's "skip
/// already-cached" path runs against realistic state.
final class TrackingDownloader: LookaheadCacheDownloader, @unchecked Sendable {
  private let lock = NSLock()
  private var _invocations: [String] = []
  private var _completed: Set<String> = []

  var invocations: [String] {
    lock.lock(); defer { lock.unlock() }
    return _invocations
  }

  func download(track: TrackItem, headers: [String: String]?) async throws {
    lock.withLock {
      _invocations.append(track.id ?? "")
      _completed.insert(track.url)
    }
  }

  func isFullyCached(url: String) -> Bool {
    lock.lock(); defer { lock.unlock() }
    return _completed.contains(url)
  }
}

/// Downloader that suspends on a specific trackId until released.
/// Used to verify the in-flight keep/cancel behaviour: while a
/// download is gated, a second reschedule either prunes the URL out
/// of the desired set (in-flight cancelled) or keeps it (in-flight
/// retained).
final class GatedDownloader: LookaheadCacheDownloader, @unchecked Sendable {
  private let lock = NSLock()
  private var _invocations: [String] = []
  private let gateTrackId: String
  private let onStart: () -> Void
  private let hold: AsyncHold

  init(gateTrackId: String, onStart: @escaping () -> Void, hold: AsyncHold) {
    self.gateTrackId = gateTrackId
    self.onStart = onStart
    self.hold = hold
  }

  var invocations: [String] {
    lock.lock(); defer { lock.unlock() }
    return _invocations
  }

  func download(track: TrackItem, headers: [String: String]?) async throws {
    // See `StubDownloader.download` — same `?? ""` rationale.
    lock.withLock { _invocations.append(track.id ?? "") }
    if track.id == gateTrackId {
      onStart()
      await hold.wait()
      try Task.checkCancellation()
    }
  }

  func isFullyCached(url: String) -> Bool { return false }
}

/// Continuation-based hold that can be released from a sync
/// context. Used by `GatedDownloader` to suspend a specific
/// download. Allocation-cheap; doesn't pin a thread.
///
/// Single-waiter contract: `wait()` is exclusive — calling it
/// twice without an intervening `release()` is a programmer error
/// (a CheckedContinuation can resume only once).
final class AsyncHold: @unchecked Sendable {
  private let lock = NSLock()
  private var continuation: CheckedContinuation<Void, Never>?
  private var resumed = false

  func wait() async {
    await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in
      lock.lock()
      if resumed {
        // release() was called before wait() — resume immediately.
        lock.unlock()
        cont.resume()
        return
      }
      precondition(
        continuation == nil,
        "AsyncHold.wait() is single-waiter; concurrent waits are unsupported"
      )
      continuation = cont
      lock.unlock()
    }
  }

  func release() {
    lock.lock()
    if let cont = continuation {
      continuation = nil
      lock.unlock()
      cont.resume()
    } else {
      // wait() hasn't been called yet — record the release so the
      // next wait() resumes immediately.
      resumed = true
      lock.unlock()
    }
  }
}
