package com.margelo.nitro.queueplayer

/**
 * Pure-data transition table for `PlaybackMode` switches.
 *
 * Owns nothing — `TrackPlayer.setPlaybackMode` calls into the
 * machine to compute (next state, transition effect) given the
 * current `active` state. The caller applies the effect:
 *
 * - [TransitionEffect.NoOp]          → already in the requested mode.
 * - [TransitionEffect.ImmediateSwap] → swap engines synchronously,
 *   regardless of play state. A brief audible interruption while
 *   the new engine constructs is the expected feedback to the user.
 *
 * Always-immediate is the right UX: the user sees + hears the
 * engine change as soon as they confirm it. The same pattern
 * extends naturally to cast-engine swaps (Chromecast / AirPlay)
 * where waiting for a track boundary would be wrong.
 *
 * Mirrored on iOS in `PlaybackModeStateMachine.swift` with the
 * same transition matrix. Cross-platform parity is enforced by
 * the per-platform unit suites against the same matrix.
 */
internal class PlaybackModeStateMachine {

  companion object {
    /**
     * Default seed state for first-configure callers — gapless
     * mode. Avoids hand-construction at every entry point.
     */
    val Initial: State = State(active = PlaybackMode(PlaybackModeKind.GAPLESS, null))
  }

  data class State(
    val active: PlaybackMode,
  )

  sealed interface TransitionEffect {
    object NoOp : TransitionEffect
    object ImmediateSwap : TransitionEffect
  }

  /**
   * Compute the result of a `setPlaybackMode(requested)` call.
   *
   * Equality is structural — same kind + same `crossfadeDurationMs`
   * (or both nil for gapless) is a no-op. Swapping `crossfade(2000)`
   * → `crossfade(5000)` is NOT equal — the engine must rebuild for
   * the new duration.
   *
   * The caller is responsible for managing the brief audio
   * interruption — `applyEngineSwapForMode` carries the position
   * and re-installs the queue.
   */
  fun setRequested(
    state: State,
    requested: PlaybackMode,
  ): Pair<State, TransitionEffect> {
    requireValid(requested)
    if (modesEqual(state.active, requested)) {
      return state to TransitionEffect.NoOp
    }
    return State(active = requested) to TransitionEffect.ImmediateSwap
  }

  /**
   * Modes match when kind matches AND, for crossfade, when the
   * `crossfadeDurationMs` matches. Gapless never carries a duration
   * and is equal across construction.
   */
  private fun modesEqual(a: PlaybackMode, b: PlaybackMode): Boolean {
    if (a.kind != b.kind) return false
    return when (a.kind) {
      PlaybackModeKind.GAPLESS -> true
      PlaybackModeKind.CROSSFADE -> a.crossfadeDurationMs == b.crossfadeDurationMs
    }
  }

  /**
   * Reject malformed `PlaybackMode` at the state-machine entry so
   * the downstream `CrossfadeEngine` constructor sees only valid
   * inputs. Crossfade duration must be:
   *   - non-null
   *   - within `[CrossfadeDurationBounds.MIN_MS, CrossfadeDurationBounds.MAX_MS]`
   *   - quantised to `CrossfadeDurationBounds.STEP_MS` increments
   *
   * The runtime safety clamp inside `CrossfadeEngine`
   * (`effectiveCrossfadeMs`) reduces the configured value further
   * when the leading or standby track is shorter than twice the
   * configured duration — that clamp is silent + audio-only +
   * applies on top of this surface validation.
   */
  private fun requireValid(mode: PlaybackMode) {
    if (mode.kind != PlaybackModeKind.CROSSFADE) return
    val ms = mode.crossfadeDurationMs
      ?: throw IllegalArgumentException("PlaybackMode(crossfade) requires crossfadeDurationMs; got null")
    require(ms.isFinite()) { "crossfadeDurationMs must be finite; got $ms" }
    require(ms in CrossfadeDurationBounds.MIN_MS..CrossfadeDurationBounds.MAX_MS) {
      "crossfadeDurationMs must be in [${CrossfadeDurationBounds.MIN_MS}, ${CrossfadeDurationBounds.MAX_MS}]; got $ms"
    }
    require(CrossfadeDurationBounds.isQuantised(ms)) {
      "crossfadeDurationMs must be quantised to ${CrossfadeDurationBounds.STEP_MS}ms steps; got $ms"
    }
  }
}

/**
 * Surface-validation bounds for `crossfadeDurationMs`. Centralised
 * so both the state machine + any consumer wanting to render a
 * stepper UI can read the same numbers.
 */
internal object CrossfadeDurationBounds {
  const val MIN_MS: Double = 1_000.0
  const val MAX_MS: Double = 12_000.0
  const val STEP_MS: Double = 500.0

  /**
   * True when [ms] lands exactly on a [STEP_MS] boundary. Uses
   * integer rounding so floating-point noise within ±0.5 of an
   * integer ms doesn't reject otherwise-valid inputs.
   */
  fun isQuantised(ms: Double): Boolean {
    val rounded = kotlin.math.round(ms)
    if (rounded != ms) return false
    return rounded.toLong() % STEP_MS.toLong() == 0L
  }
}
