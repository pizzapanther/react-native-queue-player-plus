package com.margelo.nitro.queueplayer

/**
 * Throttles the JS `onProgress` fan-out to a configurable cadence, with an
 * optional reduced rate while the app is backgrounded. Pure + deterministic
 * (the caller supplies a monotonic timestamp).
 *
 * This gates ONLY the JS emission — the native 2 Hz sampler keeps running, so
 * milestone detection and buffer-state recomputation stay full-rate. A tick
 * emits when at least `interval` has elapsed since the last emit, where
 * `interval` is the foreground or background cadence per the current app
 * state. [lastEmittedMs] starts at negative infinity so the first tick after a
 * subscribe always emits, and a background→foreground transition resets it so
 * the now-visible UI resumes at the live position on the next tick.
 *
 * Unlike iOS, the Android progress tick runs continuously (every 500ms) whether
 * playing or paused, so a throttled paused seek self-corrects on the next tick
 * within one interval — no play-state carve-out is needed, and throttling the
 * repeated paused position is the desired behaviour.
 *
 * Main-thread-confined (the progress tick and the ProcessLifecycleOwner
 * callbacks all run on main), so no synchronisation.
 */
internal class ProgressEmissionGate {
  private var foregroundIntervalMs: Double = 500.0
  private var backgroundIntervalMs: Double = 500.0
  private var isBackgrounded: Boolean = false
  private var lastEmittedMs: Double = Double.NEGATIVE_INFINITY

  /**
   * Half the native sampler period. The sampler fires at a fixed cadence, so a
   * configured interval equal to that cadence would drop ~half the ticks to
   * timing jitter — a tick landing a hair early reads as "too soon". Allowing
   * emission up to half a sampler period early snaps each emit to the nearest
   * tick: interval == sampler period emits every tick, 2× the period emits
   * every other tick, and so on.
   */
  private var toleranceMs: Double = 0.0

  /**
   * Set the foreground + background cadences (already clamped by the caller)
   * and the native sampler period that drives [shouldEmit]. Runtime state
   * ([lastEmittedMs], [isBackgrounded]) is preserved so a re-`configure()`
   * updates the rate without dropping the throttle position.
   */
  fun setIntervals(foregroundMs: Double, backgroundMs: Double, samplerPeriodMs: Double) {
    foregroundIntervalMs = foregroundMs
    backgroundIntervalMs = backgroundMs
    toleranceMs = samplerPeriodMs / 2
  }

  /** Whether a tick at [nowMs] may emit; records the emit time when it does. */
  fun shouldEmit(nowMs: Long): Boolean {
    val interval = if (isBackgrounded) backgroundIntervalMs else foregroundIntervalMs
    if (nowMs - lastEmittedMs < interval - toleranceMs) return false
    lastEmittedMs = nowMs.toDouble()
    return true
  }

  /**
   * Update the foreground/background state. On a background→foreground
   * transition, force the next tick to emit immediately (foreground-flush).
   */
  fun setBackgrounded(backgrounded: Boolean) {
    if (isBackgrounded && !backgrounded) {
      lastEmittedMs = Double.NEGATIVE_INFINITY
    }
    isBackgrounded = backgrounded
  }
}
