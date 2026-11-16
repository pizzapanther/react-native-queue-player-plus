import Foundation
import NitroModules

/// Pure-data transition table for `PlaybackMode` switches.
///
/// Owns nothing — `TrackPlayer.setPlaybackMode` calls into the
/// machine to compute (next state, transition effect) given the
/// current `active` state. The caller applies the effect:
///
/// - `noOp`          → already in the requested mode.
/// - `immediateSwap` → swap engines synchronously, regardless of
///                     play state. A brief audible interruption
///                     while the new engine constructs is the
///                     expected feedback to the user.
///
/// Always-immediate is the right UX: the user sees + hears the
/// engine change as soon as they confirm it. The same pattern
/// extends naturally to cast-engine swaps (Chromecast / AirPlay)
/// where waiting for a track boundary would be wrong.
///
/// Mirrored on Android in `PlaybackModeStateMachine.kt` with the
/// same transition matrix. Cross-platform parity is enforced by
/// the per-platform unit suites against the same matrix.
final class PlaybackModeStateMachine {

  /// `Equatable` synthesis would require `PlaybackMode: Equatable`,
  /// but the Nitrogen-bridged `PlaybackMode` is a C++ struct typealias
  /// and Swift interop does NOT bridge C++ `operator==` into Swift's
  /// `Equatable` conformance. The hand-rolled `==` therefore delegates
  /// to [modesEqual], which applies the same custom equality
  /// `setRequested` uses (gapless ignores duration).
  struct State: Equatable {
    let active: PlaybackMode

    init(active: PlaybackMode) {
      self.active = active
    }

    static func == (lhs: State, rhs: State) -> Bool {
      return PlaybackModeStateMachine.modesEqual(lhs.active, rhs.active)
    }
  }

  enum TransitionEffect: Equatable {
    case noOp
    case immediateSwap
  }

  /// Default seed state for first-configure callers — gapless
  /// mode, no pending. Avoids hand-construction at every entry
  /// point.
  static let initial: State = State(
    active: PlaybackMode(kind: .gapless, crossfadeDurationMs: nil)
  )

  /// Compute the result of a `setPlaybackMode(requested)` call.
  ///
  /// Equality is structural — same kind + same `crossfadeDurationMs`
  /// (or both nil for gapless) is a no-op. Swapping
  /// `crossfade(2000) → crossfade(5000)` is NOT equal — the engine
  /// must rebuild for the new duration.
  ///
  /// Throws `InvalidPlaybackModeError` when [requested] fails the
  /// `requireValid` checks (out-of-range / non-quantised crossfade
  /// duration); the caller's `throws` chain propagates it through
  /// the Nitro Promise<Void> bridge as a JS-thrown error.
  ///
  /// The caller is responsible for managing the brief audio
  /// interruption — `applyEngineSwapForMode` carries the position
  /// and re-installs the queue.
  func setRequested(
    state: State,
    requested: PlaybackMode
  ) throws -> (State, TransitionEffect) {
    try Self.requireValid(requested)
    if Self.modesEqual(state.active, requested) {
      return (state, .noOp)
    }
    return (State(active: requested), .immediateSwap)
  }

  // The static helpers below are file-internal so the test suite
  // can drive them directly: `PlaybackMode` is a C++ struct
  // typealias on the Swift side and does not auto-conform to
  // `Equatable`, so callers can't use `XCTAssertEqual` on it.
  // Tests assert via `modesEqual` to mirror the production-code
  // equality semantics.

  /// Modes match when kind matches AND, for crossfade, when the
  /// `crossfadeDurationMs` matches. Gapless never carries a
  /// duration and is equal across construction.
  static func modesEqual(_ a: PlaybackMode, _ b: PlaybackMode) -> Bool {
    if a.kind != b.kind { return false }
    switch a.kind {
    case .gapless:
      return true
    case .crossfade:
      return a.crossfadeDurationMs == b.crossfadeDurationMs
    }
  }

  /// Reject malformed `PlaybackMode` at the state-machine entry
  /// so the downstream `CrossfadeEngine` constructor sees only
  /// valid inputs. Crossfade duration must be:
  ///   - non-nil
  ///   - within `[CrossfadeDurationBounds.minMs, CrossfadeDurationBounds.maxMs]`
  ///   - quantised to `CrossfadeDurationBounds.stepMs` increments
  ///
  /// Throws Nitro's `RuntimeError` rather than aborting the
  /// process — `precondition` / `preconditionFailure` would
  /// terminate the app via `__assert_rtn`, not surface as a
  /// catchable JS error. The Nitro bridge converts the throw to
  /// a rejected JS Promise; `RuntimeError` (vs a custom `Error`
  /// type) is the right choice because Nitro's
  /// `RuntimeError.toCpp()` uses `String(describing:)`, which
  /// `RuntimeError` exposes as the bare message string —
  /// matches Android's `IllegalArgumentException` shape on the JS
  /// side (consumer sees `e.message === "crossfadeDurationMs..."`,
  /// not a wrapped type description).
  ///
  /// The runtime safety clamp inside `CrossfadeEngine`
  /// (`effectiveCrossfadeSeconds`) reduces the configured value
  /// further when the leading or standby track is shorter than
  /// twice the configured duration — that clamp is silent + audio-
  /// only + applies on top of this surface validation.
  static func requireValid(_ mode: PlaybackMode) throws {
    guard mode.kind == .crossfade else { return }
    guard let ms = mode.crossfadeDurationMs else {
      throw RuntimeError("PlaybackMode(crossfade) requires crossfadeDurationMs; got nil")
    }
    guard ms.isFinite else {
      throw RuntimeError("crossfadeDurationMs must be finite; got \(ms)")
    }
    guard ms >= CrossfadeDurationBounds.minMs && ms <= CrossfadeDurationBounds.maxMs else {
      throw RuntimeError("crossfadeDurationMs must be in [\(CrossfadeDurationBounds.minMs), \(CrossfadeDurationBounds.maxMs)]; got \(ms)")
    }
    guard CrossfadeDurationBounds.isQuantised(ms) else {
      throw RuntimeError("crossfadeDurationMs must be quantised to \(CrossfadeDurationBounds.stepMs)ms steps; got \(ms)")
    }
  }
}

/// Surface-validation bounds for `crossfadeDurationMs`. Centralised
/// so both the state machine + any consumer wanting to render a
/// stepper UI can read the same numbers.
enum CrossfadeDurationBounds {
  static let minMs: Double = 1_000
  static let maxMs: Double = 12_000
  static let stepMs: Double = 500

  /// True when `ms` lands exactly on a `stepMs` boundary. Uses
  /// integer rounding so floating-point noise within ±0.5 of an
  /// integer ms doesn't reject otherwise-valid inputs.
  static func isQuantised(_ ms: Double) -> Bool {
    let rounded = ms.rounded()
    guard rounded == ms else { return false }
    return Int(rounded).isMultiple(of: Int(stepMs))
  }
}
