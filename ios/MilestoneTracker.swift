/// Tracks fixed playback-progress milestones (25/50/75/90%) for the
/// current track playthrough. Pure + deterministic (no AVFoundation).
///
/// A threshold emits only when forward playback crosses it: `seek` moves
/// the baseline WITHOUT emitting (so thresholds a seek jumps over are
/// suppressed), the first `tick` only establishes the baseline (so a
/// mid-track start doesn't back-fire), and each threshold emits at most
/// once per playthrough. `reset` begins a new playthrough (track change /
/// repeat-one loop). With no computable duration (<= 0) nothing fires.
struct MilestoneTracker {
  static let milestones: [Int] = [25, 50, 75, 90]

  private var fired: Set<Int> = []
  private var lastFraction: Double?

  /// Begin a fresh playthrough.
  mutating func reset() {
    fired.removeAll()
    lastFraction = nil
  }

  /// Move the baseline to a seek target and CONSUME (without emitting)
  /// every threshold at/below it — those were jumped, not played, so they
  /// must not fire this playthrough. Consuming into `fired` (rather than
  /// only moving the baseline) makes the suppress robust against a
  /// progress tick that observes the post-seek position around the same
  /// time as the seek: a consumed threshold can never fire regardless of
  /// tick/seek ordering.
  mutating func seek(durationSec: Double, positionSec: Double) {
    guard let frac = fraction(durationSec: durationSec, positionSec: positionSec) else {
      lastFraction = nil
      return
    }
    for m in MilestoneTracker.milestones where Double(m) / 100 <= frac {
      fired.insert(m)
    }
    lastFraction = frac
  }

  /// Advance from a progress tick; returns the milestones crossed by
  /// forward playback since the previous observation, ascending.
  mutating func tick(durationSec: Double, positionSec: Double) -> [Int] {
    guard let frac = fraction(durationSec: durationSec, positionSec: positionSec) else {
      return []
    }
    guard let prev = lastFraction else {
      lastFraction = frac
      return []
    }
    let crossed = MilestoneTracker.milestones.filter { m in
      !fired.contains(m) && prev < Double(m) / 100 && Double(m) / 100 <= frac
    }
    fired.formUnion(crossed)
    lastFraction = frac
    return crossed
  }

  private func fraction(durationSec: Double, positionSec: Double) -> Double? {
    guard durationSec > 0 else { return nil }
    return positionSec / durationSec
  }
}
