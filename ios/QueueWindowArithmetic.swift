import Foundation

/// Pure-Swift arithmetic for the bounded enqueue window.
///
/// Everything here is expressed in absolute queue *positions* — never engine
/// indices, which move as `AVQueuePlayer` consumes finished items.
///
/// `slice` describes the installed run — a trail behind the playhead plus the
/// forward window — and it **may wrap**: it can end at a lower absolute
/// position than it starts. A wrapped slice is a run in play order, not an
/// ordered set of integers, so it must not be compared with `<` / `>`, sorted,
/// or turned into a `Range<Int>`. The floor bounds its forward part.
///
/// `isAtLogicalTail` and `verdict` answer what a drained engine means, in
/// absolute positions and without wrapping — see `isAtLogicalTail` for why
/// that asymmetry is deliberate.
///
/// Helpers are gated behind `@_spi(QueuePlayerTests)` so they stay off the
/// library's consumer-facing surface, matching `QueueMutationArithmetic`.
public enum QueueWindowArithmetic {

  /// Items materialised at or after the current track, inclusive of it.
  ///
  /// Three is the minimum that satisfies "top up whenever at most one item
  /// remains after the current one, and never let that reach zero": a smaller
  /// floor lands an append in the slot `AVQueuePlayer` is about to consume.
  /// Ten sits well clear of that minimum. The cost windowing exists to avoid is
  /// per-item — item construction, a cache lookup, an LRU touch — and it is a
  /// queue-length problem, not a window-length one: ten items is a fixed,
  /// negligible cost where the unbounded build was thousands.
  @_spi(QueuePlayerTests)
  public static let floor: Int = 10

  /// What a drained engine means for the queue as a whole.
  @_spi(QueuePlayerTests)
  public enum EndVerdict: Equatable {
    /// The engine still has an active item; nothing ended.
    case notEnded
    /// Re-enqueue the current position. Under `.track` the queue can neither
    /// end nor advance, so a drain is always the same position again.
    case refillCurrent
    /// The engine ran out mid-queue. Re-seat the queue from the next position
    /// and resume — not a queue end.
    case windowDrained
    /// The logical queue ended under `.queue`; restart from position 0.
    case wrapToStart
    /// The logical queue ended. Fire `onQueueEnd` and settle to `.ended`.
    case queueEnd
  }

  /// Positions the engine should hold around `currentIndex`, in install order.
  ///
  /// `trail` positions behind the current one, the current one, and forward to
  /// the floor. The trail exists so a backward skip reuses an `AVURLAsset`
  /// already built; a run that only grows forward moves the memory peak later
  /// rather than lowering it, because nothing is ever removed.
  ///
  /// `wraps` is whether the queue returns to position 0 at its end, which is
  /// what makes a repeat-queue boundary a crossfade rather than a hard cut:
  /// the incoming item has to be materialised before the outgoing one ends.
  ///
  /// Returns at most `min(trail + floor, trackCount)` positions, always as one
  /// unbroken run in play order: each position is the previous one plus one,
  /// modulo `trackCount`. Where the run covers the whole queue the position
  /// after the last is the first; everywhere else it is not installed yet and
  /// belongs to top-up.
  ///
  /// A position appears at most once. Two materialisations of one position
  /// would give the engine two items carrying the same `queueItemId`, and
  /// every translation from position to engine index is a first-match — so the
  /// second copy would be unreachable and the trail could strand the item
  /// being heard. The forward run and the trail can only meet when the slice
  /// already covers every position, so nothing is lost either way; the forward
  /// run keeps the contested position because that is what leaves the result
  /// one unbroken run.
  ///
  /// A queue of one wraps onto itself, so it cannot both hold a fresh incoming
  /// item and hold each position once. It holds the one position, and a
  /// repeat-queue boundary there is a hard cut.
  @_spi(QueuePlayerTests)
  public static func slice(
    currentIndex: Int, trackCount: Int, trail: Int, wraps: Bool
  ) -> [Int] {
    guard trackCount > 0, currentIndex >= 0, currentIndex < trackCount else { return [] }
    let cap = min(max(0, trail) + floor, trackCount)

    // Uniqueness rests on the cap: a run of at most `trackCount` positions
    // stepping one at a time cannot revisit one. The `held` checks below are a
    // second mechanism, and are unreachable while the cap holds — they are
    // what keeps a future change to the cap from silently emitting duplicates.
    var positions: [Int] = []
    var held = Set<Int>()

    var forward = currentIndex
    while positions.count < min(floor, trackCount) {
      guard !held.contains(forward) else { break }
      positions.append(forward)
      held.insert(forward)
      let next = forward + 1
      if next < trackCount {
        forward = next
      } else if wraps {
        forward = 0
      } else {
        break
      }
    }

    var behind: [Int] = []
    var step = 1
    while behind.count < max(0, trail), positions.count + behind.count < cap {
      let raw = currentIndex - step
      let position = raw >= 0 ? raw : (wraps ? raw + trackCount : -1)
      guard position >= 0, !held.contains(position) else { break }
      behind.append(position)
      held.insert(position)
      step += 1
    }

    return behind.reversed() + positions
  }

  /// Whether the last enqueued position is also the last position in the
  /// queue. False on an empty queue, where `-1 == -1` would otherwise read
  /// as a tail.
  ///
  /// Deliberately not wrap-aware, even though `slice` wraps. `verdict` pairs
  /// this with `.windowDrained`, whose caller advances by a **non-modular**
  /// clamp; today that clamp is unreachable precisely because this answers
  /// true at the last position. An answer that went modular here would make it
  /// reachable, and the last track would re-seat onto itself instead of
  /// wrapping. A wrapping run's completeness is a question for the top-up
  /// path, not for the drain verdict.
  @_spi(QueuePlayerTests)
  public static func isAtLogicalTail(anchorPosition: Int, trackCount: Int) -> Bool {
    guard trackCount > 0 else { return false }
    return anchorPosition == trackCount - 1
  }

  /// Resolve what a drained engine means. `drained` is "the engine has no
  /// active item"; `atLogicalTail` is `isAtLogicalTail` against the anchor.
  /// Caller must hold a non-empty queue — an empty one has nothing to end.
  @_spi(QueuePlayerTests)
  public static func verdict(
    drained: Bool, atLogicalTail: Bool, repeatMode: QueueSkipRepeatMode
  ) -> EndVerdict {
    guard drained else { return .notEnded }
    if repeatMode == .track { return .refillCurrent }
    guard atLogicalTail else { return .windowDrained }
    return repeatMode == .queue ? .wrapToStart : .queueEnd
  }
}
