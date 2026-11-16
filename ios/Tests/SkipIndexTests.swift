import XCTest
@_spi(QueuePlayerTests) import QueuePlayer

/// Skip-index arithmetic tested via the pure-Swift
/// `QueueSkipArithmetic` helpers. The `TrackPlayer.computeNextIndex` /
/// `.computePreviousIndex` instance methods are thin adapters that
/// delegate here, so covering the arithmetic directly is load-bearing
/// coverage for consumer-facing skip behaviour.
final class SkipIndexTests: XCTestCase {

  // MARK: - computeNext — empty + not-started guards

  func testComputeNextEmptyTracksReturnsNil() {
    XCTAssertNil(QueueSkipArithmetic.computeNext(
      trackCount: 0, currentIndex: -1, repeatMode: .off))
  }

  func testComputeNextCurrentMinusOneReturnsNil() {
    // `cur < 0` is the "nothing playing yet" sentinel — skip is a no-op.
    // Avoids accidentally synthesising a first play out of a Next button tap.
    XCTAssertNil(QueueSkipArithmetic.computeNext(
      trackCount: 3, currentIndex: -1, repeatMode: .off))
  }

  // MARK: - computeNext — repeat .off

  func testComputeNextRepeatOffMidQueueAdvances() {
    XCTAssertEqual(QueueSkipArithmetic.computeNext(
      trackCount: 3, currentIndex: 0, repeatMode: .off), 1)
  }

  func testComputeNextRepeatOffSecondLastAdvances() {
    XCTAssertEqual(QueueSkipArithmetic.computeNext(
      trackCount: 3, currentIndex: 1, repeatMode: .off), 2)
  }

  func testComputeNextRepeatOffAtEndReturnsNil() {
    XCTAssertNil(QueueSkipArithmetic.computeNext(
      trackCount: 3, currentIndex: 2, repeatMode: .off))
  }

  // MARK: - computeNext — repeat .queue (wrap)

  func testComputeNextRepeatQueueMidQueueAdvances() {
    XCTAssertEqual(QueueSkipArithmetic.computeNext(
      trackCount: 3, currentIndex: 0, repeatMode: .queue), 1)
  }

  func testComputeNextRepeatQueueAtEndWrapsToZero() {
    XCTAssertEqual(QueueSkipArithmetic.computeNext(
      trackCount: 3, currentIndex: 2, repeatMode: .queue), 0)
  }

  func testComputeNextRepeatQueueSingleTrackWrapsToSelf() {
    XCTAssertEqual(QueueSkipArithmetic.computeNext(
      trackCount: 1, currentIndex: 0, repeatMode: .queue), 0)
  }

  // MARK: - computeNext — repeat .track (navigates like .off, no wrap)

  func testComputeNextRepeatTrackAdvancesLikeOff() {
    // Under .track a user Next advances by one; repeat-one persists so
    // the new track loops on natural end (handled in TrackPlayer).
    XCTAssertEqual(QueueSkipArithmetic.computeNext(
      trackCount: 3, currentIndex: 1, repeatMode: .track), 2)
  }

  func testComputeNextRepeatTrackAtEndReturnsNil() {
    XCTAssertNil(QueueSkipArithmetic.computeNext(
      trackCount: 3, currentIndex: 2, repeatMode: .track))
  }

  // MARK: - computePrevious — empty + not-started guards

  func testComputePreviousEmptyTracksReturnsNil() {
    XCTAssertNil(QueueSkipArithmetic.computePrevious(
      trackCount: 0, currentIndex: -1, repeatMode: .off))
  }

  func testComputePreviousCurrentMinusOneReturnsNil() {
    XCTAssertNil(QueueSkipArithmetic.computePrevious(
      trackCount: 3, currentIndex: -1, repeatMode: .off))
  }

  // MARK: - computePrevious — repeat .off

  func testComputePreviousRepeatOffMidQueueDecrements() {
    XCTAssertEqual(QueueSkipArithmetic.computePrevious(
      trackCount: 3, currentIndex: 1, repeatMode: .off), 0)
  }

  func testComputePreviousRepeatOffAtStartReturnsNil() {
    // No rewind-on-boundary under .off. The consumer-facing "tap Previous
    // at index 0 restarts the track" behaviour lives in `skipToPrevious`,
    // not here.
    XCTAssertNil(QueueSkipArithmetic.computePrevious(
      trackCount: 3, currentIndex: 0, repeatMode: .off))
  }

  // MARK: - computePrevious — repeat .queue (wrap)

  func testComputePreviousRepeatQueueMidQueueDecrements() {
    XCTAssertEqual(QueueSkipArithmetic.computePrevious(
      trackCount: 3, currentIndex: 2, repeatMode: .queue), 1)
  }

  func testComputePreviousRepeatQueueAtStartWrapsToEnd() {
    XCTAssertEqual(QueueSkipArithmetic.computePrevious(
      trackCount: 3, currentIndex: 0, repeatMode: .queue), 2)
  }

  func testComputePreviousRepeatQueueSingleTrackWrapsToSelf() {
    XCTAssertEqual(QueueSkipArithmetic.computePrevious(
      trackCount: 1, currentIndex: 0, repeatMode: .queue), 0)
  }

  // MARK: - computePrevious — repeat .track (navigates like .off, no wrap)

  func testComputePreviousRepeatTrackGoesBackLikeOff() {
    XCTAssertEqual(QueueSkipArithmetic.computePrevious(
      trackCount: 3, currentIndex: 1, repeatMode: .track), 0)
  }

  func testComputePreviousRepeatTrackAtStartReturnsNil() {
    XCTAssertNil(QueueSkipArithmetic.computePrevious(
      trackCount: 3, currentIndex: 0, repeatMode: .track))
  }

  // MARK: - QueueSkipRepeatMode raw-value contract

  func testRepeatModeRawValuesMatchCppEnumOrder() {
    // Value order (0/1/2) matches `RepeatModeKind` in the nitro.ts spec.
    // Accidental reorder in the Nitrogen spec would break the
    // repeatModeAsSkipMode() mapping in TrackPlayer.swift.
    XCTAssertEqual(QueueSkipRepeatMode.off.rawValue, 0)
    XCTAssertEqual(QueueSkipRepeatMode.track.rawValue, 1)
    XCTAssertEqual(QueueSkipRepeatMode.queue.rawValue, 2)
  }

  // MARK: - canSkipNext

  func testCanSkipNextEmptyQueueReturnsFalse() {
    XCTAssertFalse(QueueSkipArithmetic.canSkipNext(
      trackCount: 0, currentIndex: -1, repeatMode: .off))
  }

  func testCanSkipNextNothingPlayingReturnsFalse() {
    for mode in [QueueSkipRepeatMode.off, .queue, .track] {
      XCTAssertFalse(QueueSkipArithmetic.canSkipNext(
        trackCount: 5, currentIndex: -1, repeatMode: mode))
    }
  }

  func testCanSkipNextOffMidQueueIsTrue() {
    XCTAssertTrue(QueueSkipArithmetic.canSkipNext(
      trackCount: 5, currentIndex: 2, repeatMode: .off))
  }

  func testCanSkipNextOffAtLastIsFalse() {
    XCTAssertFalse(QueueSkipArithmetic.canSkipNext(
      trackCount: 5, currentIndex: 4, repeatMode: .off))
  }

  func testCanSkipNextQueueAtLastWrapsSoTrue() {
    XCTAssertTrue(QueueSkipArithmetic.canSkipNext(
      trackCount: 5, currentIndex: 4, repeatMode: .queue))
  }

  func testCanSkipNextQueueSingleTrackTrue() {
    XCTAssertTrue(QueueSkipArithmetic.canSkipNext(
      trackCount: 1, currentIndex: 0, repeatMode: .queue))
  }

  func testCanSkipNextTrackTrueExceptAtLastTrack() {
    for index in 0..<4 {
      XCTAssertTrue(QueueSkipArithmetic.canSkipNext(
        trackCount: 5, currentIndex: index, repeatMode: .track),
        "canSkipNext should be true at index \(index) under .track")
    }
    XCTAssertFalse(QueueSkipArithmetic.canSkipNext(
      trackCount: 5, currentIndex: 4, repeatMode: .track),
      "canSkipNext should be false at the last track under .track")
  }

  func testCanSkipNextOffSingleTrackIsFalse() {
    XCTAssertFalse(QueueSkipArithmetic.canSkipNext(
      trackCount: 1, currentIndex: 0, repeatMode: .off))
  }

  // MARK: - canSkipPrevious
  //
  // Symmetric with canSkipNext — defined as `computePrevious(...) !=
  // nil`. Apps that want "tap Previous restarts current at >3s"
  // implement that themselves in their button handler.

  func testCanSkipPreviousEmptyQueueReturnsFalse() {
    XCTAssertFalse(QueueSkipArithmetic.canSkipPrevious(
      trackCount: 0, currentIndex: -1, repeatMode: .off))
  }

  func testCanSkipPreviousNothingPlayingReturnsFalse() {
    for mode in [QueueSkipRepeatMode.off, .queue, .track] {
      XCTAssertFalse(QueueSkipArithmetic.canSkipPrevious(
        trackCount: 5, currentIndex: -1, repeatMode: mode))
    }
  }

  func testCanSkipPreviousOffMidQueueIsTrue() {
    XCTAssertTrue(QueueSkipArithmetic.canSkipPrevious(
      trackCount: 5, currentIndex: 2, repeatMode: .off))
  }

  func testCanSkipPreviousOffAtIndexZeroIsFalse() {
    // No previous track in `.off` at index 0 — computePrevious
    // returns nil. App can still implement restart-on-tap-prev
    // by reading position itself; the lib reports the primitive.
    XCTAssertFalse(QueueSkipArithmetic.canSkipPrevious(
      trackCount: 5, currentIndex: 0, repeatMode: .off))
  }

  func testCanSkipPreviousQueueAtIndexZeroWrapsSoTrue() {
    XCTAssertTrue(QueueSkipArithmetic.canSkipPrevious(
      trackCount: 5, currentIndex: 0, repeatMode: .queue))
  }

  func testCanSkipPreviousQueueSingleTrackTrue() {
    XCTAssertTrue(QueueSkipArithmetic.canSkipPrevious(
      trackCount: 1, currentIndex: 0, repeatMode: .queue))
  }

  func testCanSkipPreviousTrackTrueExceptAtIndexZero() {
    XCTAssertFalse(QueueSkipArithmetic.canSkipPrevious(
      trackCount: 5, currentIndex: 0, repeatMode: .track),
      "canSkipPrevious should be false at index 0 under .track")
    for index in 1..<5 {
      XCTAssertTrue(QueueSkipArithmetic.canSkipPrevious(
        trackCount: 5, currentIndex: index, repeatMode: .track),
        "canSkipPrevious should be true at index \(index) under .track")
    }
  }

  func testCanSkipPreviousOffSingleTrackIsFalse() {
    XCTAssertFalse(QueueSkipArithmetic.canSkipPrevious(
      trackCount: 1, currentIndex: 0, repeatMode: .off))
  }

  // MARK: - single-track queue under .track (no wrap)

  func testTrackSingleTrackQueueHasNoNextNoPrevious() {
    XCTAssertNil(QueueSkipArithmetic.computeNext(
      trackCount: 1, currentIndex: 0, repeatMode: .track))
    XCTAssertNil(QueueSkipArithmetic.computePrevious(
      trackCount: 1, currentIndex: 0, repeatMode: .track))
    XCTAssertFalse(QueueSkipArithmetic.canSkipNext(
      trackCount: 1, currentIndex: 0, repeatMode: .track))
    XCTAssertFalse(QueueSkipArithmetic.canSkipPrevious(
      trackCount: 1, currentIndex: 0, repeatMode: .track))
  }

  // MARK: - resolveSkipToPrevious — mode OFF (position ignored, current behavior)

  private func resolve(
    count: Int = 3, index: Int, mode: QueueSkipRepeatMode,
    restart: Bool, positionMs: Int
  ) -> QueueSkipArithmetic.SkipToPreviousAction {
    QueueSkipArithmetic.resolveSkipToPrevious(
      trackCount: count, currentIndex: index, repeatMode: mode,
      restartEnabled: restart, positionMs: positionMs)
  }

  func testResolveModeOffIgnoresPositionAndGoesPrevious() {
    // Past-threshold position must NOT restart when the option is off.
    XCTAssertEqual(resolve(index: 2, mode: .off, restart: false, positionMs: 99_000),
                   .previous(1))
  }

  func testResolveModeOffFirstTrackIsNoOp() {
    XCTAssertEqual(resolve(index: 0, mode: .off, restart: false, positionMs: 99_000), .noOp)
  }

  func testResolveModeOffQueueWraps() {
    XCTAssertEqual(resolve(index: 0, mode: .queue, restart: false, positionMs: 0), .previous(2))
  }

  func testResolveModeOffEmptyQueueIsNoOp() {
    XCTAssertEqual(resolve(count: 0, index: -1, mode: .off, restart: false, positionMs: 0), .noOp)
  }

  // MARK: - resolveSkipToPrevious — mode ON (3s threshold model)

  func testResolvePastThresholdRestartsEvenWithPrevious() {
    for pos in [3001, 5000, 600_000] {
      XCTAssertEqual(resolve(index: 2, mode: .off, restart: true, positionMs: pos),
                     .restartCurrent, "position \(pos) > 3000 should restart")
    }
  }

  func testResolveThresholdBoundaryGoesPrevious() {
    // <= threshold → previous; one ms past → restart.
    XCTAssertEqual(resolve(index: 2, mode: .off, restart: true, positionMs: 3000), .previous(1))
    XCTAssertEqual(resolve(index: 2, mode: .off, restart: true, positionMs: 3001), .restartCurrent)
  }

  func testResolveUnderThresholdGoesPrevious() {
    for pos in [0, 1, 2999] {
      XCTAssertEqual(resolve(index: 2, mode: .off, restart: true, positionMs: pos),
                     .previous(1), "position \(pos) <= 3000 should go previous")
    }
  }

  func testResolveFirstTrackUnderThresholdRestartsWhenNoPrevious() {
    XCTAssertEqual(resolve(index: 0, mode: .off, restart: true, positionMs: 1000), .restartCurrent)
    XCTAssertEqual(resolve(index: 0, mode: .track, restart: true, positionMs: 1000), .restartCurrent)
  }

  func testResolveFirstTrackUnderThresholdQueueWraps() {
    // queue mode has a previous (wraps), so under threshold goes to the last track.
    XCTAssertEqual(resolve(index: 0, mode: .queue, restart: true, positionMs: 1000), .previous(2))
  }

  func testResolveFirstTrackPastThresholdRestarts() {
    for mode in [QueueSkipRepeatMode.off, .track, .queue] {
      XCTAssertEqual(resolve(index: 0, mode: mode, restart: true, positionMs: 9000),
                     .restartCurrent, "first track past threshold restarts under \(mode)")
    }
  }

  func testResolveModeOnEmptyQueueIsNoOp() {
    XCTAssertEqual(resolve(count: 0, index: -1, mode: .off, restart: true, positionMs: 9000), .noOp)
  }

  // MARK: - canSkipPrevious with restartEnabled (forced true)

  func testCanSkipPreviousForcedTrueAtEveryIndexAndMode() {
    for mode in [QueueSkipRepeatMode.off, .track, .queue] {
      for index in 0..<3 {
        XCTAssertTrue(QueueSkipArithmetic.canSkipPrevious(
          trackCount: 3, currentIndex: index, repeatMode: mode, restartEnabled: true),
          "restart mode forces canSkipPrevious true at index \(index) under \(mode)")
      }
    }
  }

  func testCanSkipPreviousRestartEnabledFalseForEmptyQueue() {
    XCTAssertFalse(QueueSkipArithmetic.canSkipPrevious(
      trackCount: 0, currentIndex: -1, repeatMode: .off, restartEnabled: true))
  }

  func testCanSkipPreviousRestartDisabledMatchesExisting() {
    // Default restartEnabled=false path is unchanged: false at index 0 under .off.
    XCTAssertFalse(QueueSkipArithmetic.canSkipPrevious(
      trackCount: 3, currentIndex: 0, repeatMode: .off, restartEnabled: false))
    XCTAssertTrue(QueueSkipArithmetic.canSkipPrevious(
      trackCount: 3, currentIndex: 1, repeatMode: .off, restartEnabled: false))
  }

  func testCanSkipPreviousRestartNothingPlayingReturnsFalseInAnyMode() {
    // Nothing is loaded, so there is no track to restart and no previous
    // track to reach: resolveSkipToPrevious returns no-op for this state.
    for mode in [QueueSkipRepeatMode.off, .queue, .track] {
      XCTAssertFalse(QueueSkipArithmetic.canSkipPrevious(
        trackCount: 5, currentIndex: -1, repeatMode: mode, restartEnabled: true))
    }
  }

  func testCanSkipPreviousAgreesWithResolveSkipToPrevious() {
    // canSkipPrevious is the enabled-state of the same decision
    // resolveSkipToPrevious makes; the two must never disagree.
    for count in [0, 1, 3] {
      for index in [-1, 0, 1, 2] {
        for mode in [QueueSkipRepeatMode.off, .queue, .track] {
          for restart in [false, true] {
            let can = QueueSkipArithmetic.canSkipPrevious(
              trackCount: count, currentIndex: index, repeatMode: mode,
              restartEnabled: restart)
            let resolved = QueueSkipArithmetic.resolveSkipToPrevious(
              trackCount: count, currentIndex: index, repeatMode: mode,
              restartEnabled: restart, positionMs: 0)
            XCTAssertEqual(can, resolved != .noOp,
              "count=\(count) index=\(index) mode=\(mode) restart=\(restart)")
          }
        }
      }
    }
  }
}
