import Foundation

/// Pure state machine for the sleep timer — no player, volume, or timer
/// dependencies, so it stands alone. The owning `TrackPlayer`
/// drives it from a repeating native timer and applies the returned decision
/// (volume fade, pause) to the engine.
///
/// Wall-clock: the deadline is a fixed epoch, so it counts down whether playback
/// is playing or paused. Two modes — a fixed `duration` and `endOfTrack` (pause
/// when the current track finishes). A 60-second "tail" rule keeps a track from
/// being cut off near its end: if the deadline lands with ≤ `tailGraceSeconds`
/// left on the current track, the pause is deferred to that track's natural end.
/// Volume fades linearly over the final `fadeSeconds`.
struct SleepTimerCore {
  enum Mode {
    case inactive
    case duration
    case endOfTrack
  }

  /// The decision produced by each `tick`. `fadeFraction` is the multiplier the
  /// caller applies to the pre-fade volume (`1.0` = full / restore). `pauseNow`
  /// asks the caller to pause playback. `stateChanged` asks the caller to emit
  /// `onSleepTimerChange` (fired on arm/clear/conversion/fire, NOT on every
  /// fade/countdown tick).
  struct Tick {
    let fadeFraction: Double
    let pauseNow: Bool
    let stateChanged: Bool

    static let idle = Tick(fadeFraction: 1.0, pauseNow: false, stateChanged: false)
  }

  private(set) var mode: Mode = .inactive
  private(set) var deadlineEpochMs: Int64?
  // Guards the tail rule to a single track so a stalled/looping position can't
  // defer the pause forever.
  private var tailExtended = false
  // True while an end-of-track timer is pending, and survives the conversion to
  // a `.duration` fade so the real track-end signal (`fireAtTrackEnd`) knows to
  // fire — the poll can't hit the boundary exactly and never fires at all when
  // the duration is unknown (live/streaming).
  private var originEndOfTrack = false

  let fadeSeconds: Double
  let tailGraceSeconds: Double

  init(fadeSeconds: Double = 10, tailGraceSeconds: Double = 60) {
    self.fadeSeconds = fadeSeconds
    self.tailGraceSeconds = tailGraceSeconds
  }

  var isActive: Bool { mode != .inactive }
  var isEndOfTrack: Bool { mode == .endOfTrack }

  mutating func armDuration(seconds: Double, nowMs: Int64) {
    mode = .duration
    deadlineEpochMs = nowMs + Int64(seconds * 1000)
    tailExtended = false
    originEndOfTrack = false
  }

  mutating func armEndOfTrack() {
    mode = .endOfTrack
    deadlineEpochMs = nil
    tailExtended = false
    originEndOfTrack = true
  }

  mutating func clear() {
    mode = .inactive
    deadlineEpochMs = nil
    tailExtended = false
    originEndOfTrack = false
  }

  /// Fire the pending end-of-track timer at the current track's real end. The
  /// owning player calls this from its track-end signal so the pause lands
  /// exactly at the boundary (and fires even when the track duration was never
  /// known). No-op unless an end-of-track timer is pending.
  mutating func fireAtTrackEnd() -> Tick {
    guard originEndOfTrack else { return .idle }
    clear()
    return Tick(fadeFraction: 1.0, pauseNow: true, stateChanged: true)
  }

  /// Whole seconds until the fixed deadline, or `nil` when inactive / awaiting
  /// track end. Used to build the `SleepTimerState` snapshot.
  func remainingSeconds(nowMs: Int64) -> Int? {
    guard mode == .duration, let deadline = deadlineEpochMs else { return nil }
    return max(0, Int((Double(deadline - nowMs) / 1000.0).rounded()))
  }

  mutating func tick(nowMs: Int64, trackDurationSec: Double, trackPositionSec: Double) -> Tick {
    switch mode {
    case .inactive:
      return .idle

    case .endOfTrack:
      // Convert to a concrete deadline once the current track is near its end,
      // then let the duration branch ride it out + fire.
      if let tail = trackRemaining(trackDurationSec, trackPositionSec), tail <= tailGraceSeconds {
        mode = .duration
        // Deadline lands AT the track boundary (not 1s past it) so the fade
        // completes at the end; on local playback the real track-end signal
        // (fireAtTrackEnd) owns the actual pause, and this deadline is the
        // fallback the cast path rides out on the receiver's clock.
        deadlineEpochMs = nowMs + Int64(tail * 1000)
        tailExtended = true
        return Tick(fadeFraction: fadeFraction(tail), pauseNow: false, stateChanged: true)
      }
      return .idle

    case .duration:
      guard let deadline = deadlineEpochMs else {
        mode = .inactive
        return .idle
      }
      let remaining = Double(deadline - nowMs) / 1000.0
      if remaining > 0 {
        return Tick(fadeFraction: fadeFraction(remaining), pauseNow: false, stateChanged: false)
      }
      // Expired. Ride out the current track once if it's almost done.
      if !tailExtended, let tail = trackRemaining(trackDurationSec, trackPositionSec),
         tail > 0, tail <= tailGraceSeconds {
        tailExtended = true
        deadlineEpochMs = nowMs + Int64((tail + 1) * 1000)
        return Tick(fadeFraction: fadeFraction(tail + 1), pauseNow: false, stateChanged: true)
      }
      // Fire.
      clear()
      return Tick(fadeFraction: 1.0, pauseNow: true, stateChanged: true)
    }
  }

  /// Remaining seconds on the current track, or `nil` when unknown (live stream,
  /// not-yet-loaded) — in which case the tail rule doesn't apply.
  private func trackRemaining(_ durationSec: Double, _ positionSec: Double) -> Double? {
    guard durationSec > 0, positionSec >= 0 else { return nil }
    return max(0, durationSec - positionSec)
  }

  private func fadeFraction(_ remainingSec: Double) -> Double {
    if remainingSec >= fadeSeconds { return 1.0 }
    if remainingSec <= 0 { return 0.0 }
    return remainingSec / fadeSeconds
  }
}
