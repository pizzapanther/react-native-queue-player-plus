/// Throttles the JS `onProgress` fan-out to a configurable cadence, with an
/// optional reduced rate while the app is backgrounded. Pure + deterministic
/// (the caller supplies a monotonic timestamp).
///
/// This gates ONLY the JS emission — the native 2 Hz sampler keeps running, so
/// milestone detection and buffer-state recomputation stay full-rate. A tick
/// emits when at least `interval` has elapsed since the last emit, where
/// `interval` is the foreground or background cadence per the current app
/// state. `lastEmittedMs` starts at `-.infinity` so the first tick after a
/// subscribe always emits, and a background→foreground transition resets it so
/// the now-visible UI resumes at the live position on the next tick.
///
/// Main-thread-confined (the progress tick, the cast push, and the
/// background/foreground notifications all run on main), so no synchronisation.
struct ProgressEmissionGate {
  private var foregroundIntervalMs: Double = 500
  private var backgroundIntervalMs: Double = 500
  private var isBackgrounded = false
  private var lastEmittedMs: Double = -.infinity
  /// Half the native sampler period. The sampler fires at a fixed cadence, so a
  /// configured interval equal to that cadence would drop ~half the ticks to
  /// timing jitter — a tick landing a hair early reads as "too soon". Allowing
  /// emission up to half a sampler period early snaps each emit to the nearest
  /// tick: interval == sampler period emits every tick, 2× the period emits
  /// every other tick, and so on.
  private var toleranceMs: Double = 0

  /// Set the foreground + background cadences (already clamped by the caller)
  /// and the native sampler period that drives `shouldEmit`. Runtime state
  /// (`lastEmittedMs`, `isBackgrounded`) is preserved so a re-`configure()`
  /// updates the rate without dropping the throttle position.
  mutating func setIntervals(foregroundMs: Double, backgroundMs: Double, samplerPeriodMs: Double) {
    foregroundIntervalMs = foregroundMs
    backgroundIntervalMs = backgroundMs
    toleranceMs = samplerPeriodMs / 2
  }

  /// Whether a tick at `nowMs` may emit; records the emit time when it does.
  mutating func shouldEmit(nowMs: Double) -> Bool {
    let interval = isBackgrounded ? backgroundIntervalMs : foregroundIntervalMs
    guard nowMs - lastEmittedMs >= interval - toleranceMs else { return false }
    lastEmittedMs = nowMs
    return true
  }

  /// Update the foreground/background state. On a background→foreground
  /// transition, force the next tick to emit immediately (foreground-flush).
  mutating func setBackgrounded(_ backgrounded: Bool) {
    if isBackgrounded && !backgrounded {
      lastEmittedMs = -.infinity
    }
    isBackgrounded = backgrounded
  }
}
