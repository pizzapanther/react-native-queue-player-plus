import XCTest
@testable import QueuePlayer
@_spi(QueuePlayerTests) import QueuePlayer

/// Pure-Swift tests for the queue-mutation index-shift arithmetic.
final class QueueMutationArithmeticTests: XCTestCase {

  // MARK: - clampInsertBefore

  func testClampInsertBeforeNilAppendsToEnd() {
    XCTAssertEqual(QueueMutationArithmetic.clampInsertBefore(insertBefore: nil, trackCount: 5), 5)
  }

  func testClampInsertBeforeNegativeClampsToZero() {
    XCTAssertEqual(QueueMutationArithmetic.clampInsertBefore(insertBefore: -1, trackCount: 5), 0)
    XCTAssertEqual(QueueMutationArithmetic.clampInsertBefore(insertBefore: -100, trackCount: 5), 0)
  }

  func testClampInsertBeforePastTrackCountClampsToTrackCount() {
    XCTAssertEqual(QueueMutationArithmetic.clampInsertBefore(insertBefore: 100, trackCount: 5), 5)
  }

  func testClampInsertBeforeInRangePassesThrough() {
    XCTAssertEqual(QueueMutationArithmetic.clampInsertBefore(insertBefore: 3, trackCount: 5), 3)
    XCTAssertEqual(QueueMutationArithmetic.clampInsertBefore(insertBefore: 0, trackCount: 5), 0)
    XCTAssertEqual(QueueMutationArithmetic.clampInsertBefore(insertBefore: 5, trackCount: 5), 5)
  }

  // MARK: - currentIndexAfterAdd

  func testAfterAddEmptyQueueCurStaysMinusOne() {
    XCTAssertEqual(
      QueueMutationArithmetic.currentIndexAfterAdd(currentIndex: -1, insertAt: 0, count: 3),
      -1
    )
  }

  func testAfterAddInsertCountZeroIsNoOp() {
    XCTAssertEqual(
      QueueMutationArithmetic.currentIndexAfterAdd(currentIndex: 2, insertAt: 0, count: 0),
      2
    )
  }

  func testAfterAddInsertAtExactlyCurShiftsCurForward() {
    // Insert of 3 items at position 2 when cur == 2 moves cur to 5.
    XCTAssertEqual(
      QueueMutationArithmetic.currentIndexAfterAdd(currentIndex: 2, insertAt: 2, count: 3),
      5
    )
  }

  func testAfterAddInsertBeforeCurShiftsCurForward() {
    XCTAssertEqual(
      QueueMutationArithmetic.currentIndexAfterAdd(currentIndex: 2, insertAt: 1, count: 2),
      4
    )
  }

  func testAfterAddInsertAfterCurLeavesCurUnchanged() {
    XCTAssertEqual(
      QueueMutationArithmetic.currentIndexAfterAdd(currentIndex: 2, insertAt: 4, count: 3),
      2
    )
  }

  func testAfterAddInsertAtQueueTailLeavesCurUnchanged() {
    XCTAssertEqual(
      QueueMutationArithmetic.currentIndexAfterAdd(currentIndex: 2, insertAt: 5, count: 3),
      2
    )
  }

  // MARK: - sanitiseRemoveIndices

  func testSanitiseDropsNegativeAndOutOfRange() {
    XCTAssertEqual(
      QueueMutationArithmetic.sanitiseRemoveIndices(
        rawIndices: [-1, 0, 2, 5, 100], trackCount: 5
      ),
      [0, 2]
    )
  }

  func testSanitiseDedupsAndSortsAscending() {
    XCTAssertEqual(
      QueueMutationArithmetic.sanitiseRemoveIndices(
        rawIndices: [3, 1, 3, 2, 1], trackCount: 5
      ),
      [1, 2, 3]
    )
  }

  func testSanitiseEmptyInputReturnsEmpty() {
    XCTAssertEqual(
      QueueMutationArithmetic.sanitiseRemoveIndices(rawIndices: [], trackCount: 5),
      []
    )
  }

  func testSanitiseTrackCountZeroReturnsEmpty() {
    XCTAssertEqual(
      QueueMutationArithmetic.sanitiseRemoveIndices(rawIndices: [0, 1], trackCount: 0),
      []
    )
  }

  // MARK: - currentIndexAfterRemove

  func testAfterRemoveCurNegativeStaysMinusOne() {
    XCTAssertEqual(
      QueueMutationArithmetic.currentIndexAfterRemove(
        currentIndex: -1, sanitisedIndices: [0, 1], trackCountBefore: 5
      ),
      -1
    )
  }

  func testAfterRemoveBelowCurShiftsCurDown() {
    // cur=3, remove [0,1] → cur shifts to 1.
    XCTAssertEqual(
      QueueMutationArithmetic.currentIndexAfterRemove(
        currentIndex: 3, sanitisedIndices: [0, 1], trackCountBefore: 5
      ),
      1
    )
  }

  func testAfterRemoveAboveCurLeavesCurUnchanged() {
    XCTAssertEqual(
      QueueMutationArithmetic.currentIndexAfterRemove(
        currentIndex: 1, sanitisedIndices: [3, 4], trackCountBefore: 5
      ),
      1
    )
  }

  func testAfterRemoveCurItselfKeepsCurAutoAdvanceSemantics() {
    // cur=2, remove [2] → newCur stays 2 (now points to what was at 3).
    XCTAssertEqual(
      QueueMutationArithmetic.currentIndexAfterRemove(
        currentIndex: 2, sanitisedIndices: [2], trackCountBefore: 5
      ),
      2
    )
  }

  func testAfterRemoveCurAtTailClampsToNewTail() {
    // cur=4 (last), remove [4] → newTrackCount=4, cur clamps to 3.
    XCTAssertEqual(
      QueueMutationArithmetic.currentIndexAfterRemove(
        currentIndex: 4, sanitisedIndices: [4], trackCountBefore: 5
      ),
      3
    )
  }

  func testAfterRemoveEverythingReturnsMinusOne() {
    XCTAssertEqual(
      QueueMutationArithmetic.currentIndexAfterRemove(
        currentIndex: 2, sanitisedIndices: [0, 1, 2, 3, 4], trackCountBefore: 5
      ),
      -1
    )
  }

  func testAfterRemoveMixedBelowAndCurCombinesShiftAndClamp() {
    // cur=3, remove [0, 3] → removedBelow=1, newCur=2; cur was
    // removed but 2 < newTrackCount(3) so no clamp.
    XCTAssertEqual(
      QueueMutationArithmetic.currentIndexAfterRemove(
        currentIndex: 3, sanitisedIndices: [0, 3], trackCountBefore: 5
      ),
      2
    )
  }

  // MARK: - currentIndexAfterMove

  func testAfterMoveCurNegativeStaysNegative() {
    XCTAssertEqual(
      QueueMutationArithmetic.currentIndexAfterMove(currentIndex: -1, fromIndex: 0, toIndex: 2),
      -1
    )
  }

  func testAfterMoveMovingCurTracksIt() {
    // cur=2, move [2 → 4]. newCur = 4.
    XCTAssertEqual(
      QueueMutationArithmetic.currentIndexAfterMove(currentIndex: 2, fromIndex: 2, toIndex: 4),
      4
    )
  }

  func testAfterMoveSlidePastCurFromBelowShiftsCurDown() {
    // cur=3, move [1 → 4]. newCur = 2.
    XCTAssertEqual(
      QueueMutationArithmetic.currentIndexAfterMove(currentIndex: 3, fromIndex: 1, toIndex: 4),
      2
    )
  }

  func testAfterMoveSlidePastCurFromAboveShiftsCurUp() {
    // cur=1, move [3 → 0]. newCur = 2.
    XCTAssertEqual(
      QueueMutationArithmetic.currentIndexAfterMove(currentIndex: 1, fromIndex: 3, toIndex: 0),
      2
    )
  }

  func testAfterMoveEntirelyBelowCurLeavesCurAlone() {
    // cur=4, move [0 → 2].
    XCTAssertEqual(
      QueueMutationArithmetic.currentIndexAfterMove(currentIndex: 4, fromIndex: 0, toIndex: 2),
      4
    )
  }

  func testAfterMoveEntirelyAboveCurLeavesCurAlone() {
    // cur=1, move [3 → 4].
    XCTAssertEqual(
      QueueMutationArithmetic.currentIndexAfterMove(currentIndex: 1, fromIndex: 3, toIndex: 4),
      1
    )
  }

  func testAfterMoveToExactlyCurFromBelowShiftsCurDownByOne() {
    // cur=2, from=0, to=2. newCur = 1.
    XCTAssertEqual(
      QueueMutationArithmetic.currentIndexAfterMove(currentIndex: 2, fromIndex: 0, toIndex: 2),
      1
    )
  }

  func testAfterMoveToExactlyCurFromAboveShiftsCurUpByOne() {
    // cur=2, from=4, to=2. newCur = 3.
    XCTAssertEqual(
      QueueMutationArithmetic.currentIndexAfterMove(currentIndex: 2, fromIndex: 4, toIndex: 2),
      3
    )
  }
}
