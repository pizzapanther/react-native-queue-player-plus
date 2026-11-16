import Foundation

/// Pure-Swift index-shift arithmetic for queue mutations. Extracted
/// from `TrackPlayer.swift` so the shift semantics stand alone,
/// without an AVQueuePlayer. Mirrors
/// `android/.../QueueMutationArithmetic.kt` 1:1 — same function
/// shapes, same input/output semantics.
///
/// The helper only computes the new `currentTrackIndex` and (for
/// `removeFromQueue`) which original indices apply after dedup /
/// range-filter. Actual mutation of the track list + forwarding to
/// AVQueuePlayer happens in `TrackPlayer.swift`.
///
/// Gated behind `@_spi(QueuePlayerTests)` so the helpers stay off
/// the library's consumer-facing public surface — same SPI scope
/// used by `AVQueueBuilder.makeItemRaw`. The `@_spi` annotation
/// goes on each function (not the enum) to match the project-
/// established pattern + ensure cross-module visibility from the
/// test bundle.
public enum QueueMutationArithmetic {

  /// How many leading positions the engine already holds correctly.
  ///
  /// A rebuild that installs the whole slice throws away the item the engine
  /// has already buffered, which is an audible cut when it happens at a
  /// boundary. Comparing what the engine holds against what it should hold
  /// says how much of the front can stay, so only the divergent tail is
  /// replaced.
  ///
  /// Both sides are `queueItemId` — the library's own per-position identity.
  /// Consumer fields cannot be used here: two tracks may be the same URL, and
  /// `track.id` is optional consumer data, so neither distinguishes positions.
  /// A `nil` id belongs to an item the library did not stamp and never
  /// matches, so the prefix stops there.
  @_spi(QueuePlayerTests)
  public static func commonPrefixLength(
    current: [String?], desired: [String]
  ) -> Int {
    var length = 0
    while length < current.count, length < desired.count {
      guard let id = current[length], id == desired[length] else { break }
      length += 1
    }
    return length
  }

  /// Clamp an `insertBefore` argument to a valid insertion point in
  /// a track list of size `trackCount`. `nil` → append (return
  /// `trackCount`). Out-of-range values clamp into `0...trackCount`.
  @_spi(QueuePlayerTests)
  public static func clampInsertBefore(insertBefore: Int?, trackCount: Int) -> Int {
    guard let insertBefore else { return trackCount }
    if insertBefore < 0 { return 0 }
    if insertBefore > trackCount { return trackCount }
    return insertBefore
  }

  /// New `currentTrackIndex` after inserting `count` items at
  /// `insertAt`.
  ///
  /// Rules:
  /// - empty queue (`currentIndex < 0`) — stays at -1; inserting
  ///   into an empty queue does NOT auto-start playback (consumers
  ///   call `skipToIndex(0)` explicitly).
  /// - `insertAt <= currentIndex` — cur shifts forward by `count`
  ///   (items slid under it).
  /// - `insertAt > currentIndex` — cur unchanged.
  /// - `count <= 0` — no-op (should not be called with empty inputs
  ///   in practice; defensive).
  @_spi(QueuePlayerTests)
  public static func currentIndexAfterAdd(
    currentIndex: Int, insertAt: Int, count: Int
  ) -> Int {
    if count <= 0 { return currentIndex }
    if currentIndex < 0 { return currentIndex }
    return insertAt <= currentIndex ? currentIndex + count : currentIndex
  }

  /// Filter + dedup a removal-index input, dropping out-of-range
  /// and negative entries. Returned array is ASCENDING — callers
  /// iterate it descending when applying removals to the
  /// underlying list, but ascending shape is easier to reason
  /// about for the shift math downstream.
  ///
  /// `Set<Int>` has no defined iteration order in Swift, so the
  /// explicit `.sorted()` is load-bearing — do not omit it.
  @_spi(QueuePlayerTests)
  public static func sanitiseRemoveIndices(
    rawIndices: [Int], trackCount: Int
  ) -> [Int] {
    let inRange = rawIndices.filter { $0 >= 0 && $0 < trackCount }
    return Array(Set(inRange)).sorted()
  }

  /// New `currentTrackIndex` after removing the given (sanitised,
  /// ascending) indices from the track list.
  ///
  /// Rules (matches Kotlin sibling):
  /// - `currentIndex < 0` (empty / not-started) — stays -1.
  /// - Every `i < currentIndex` that was removed shifts cur down
  ///   by 1.
  /// - If cur itself was removed AND the list becomes empty,
  ///   cur → -1.
  /// - If cur itself was removed but other tracks remain, cur
  ///   stays at the SAME index value (now points at the track
  ///   previously at `cur+1` — auto-advance semantic). Clamp to
  ///   `newTrackCount - 1` if that lands past the new tail.
  @_spi(QueuePlayerTests)
  public static func currentIndexAfterRemove(
    currentIndex: Int, sanitisedIndices: [Int], trackCountBefore: Int
  ) -> Int {
    if currentIndex < 0 { return -1 }
    let indexSet = Set(sanitisedIndices)
    let removedBelowCur = sanitisedIndices.filter { $0 < currentIndex }.count
    let newTrackCount = trackCountBefore - sanitisedIndices.count
    if newTrackCount <= 0 { return -1 }

    var newCur = currentIndex - removedBelowCur
    if indexSet.contains(currentIndex) {
      // cur was removed; player auto-advances to what was at cur+1,
      // which now occupies the same index value after the earlier-
      // below shift has been applied. Clamp in case cur was at the
      // tail. Narrower-than-iOS-original guard for fidelity to the
      // Kotlin matrix (functionally equivalent — `removedBelow`
      // can never push `newCur` above the new tail).
      if newCur >= newTrackCount { newCur = newTrackCount - 1 }
    }
    return newCur
  }

  /// New `currentTrackIndex` after moving the track at `fromIndex`
  /// to `toIndex`. Inputs are assumed validated (in range,
  /// `from != to`).
  ///
  /// Rules (matches Kotlin sibling):
  /// - `currentIndex < 0` → -1.
  /// - `currentIndex == fromIndex` → `newCur = toIndex` (cur moved
  ///   with the track).
  /// - `from < cur && to >= cur` → `newCur -= 1` (track slid past
  ///   cur from below; cur is now one slot earlier).
  /// - `from > cur && to <= cur` → `newCur += 1` (track slid past
  ///   cur from above; cur is now one slot later).
  /// - otherwise cur unchanged.
  @_spi(QueuePlayerTests)
  public static func currentIndexAfterMove(
    currentIndex: Int, fromIndex: Int, toIndex: Int
  ) -> Int {
    if currentIndex < 0 { return currentIndex }
    if currentIndex == fromIndex { return toIndex }
    if fromIndex < currentIndex && toIndex >= currentIndex { return currentIndex - 1 }
    if fromIndex > currentIndex && toIndex <= currentIndex { return currentIndex + 1 }
    return currentIndex
  }
}
