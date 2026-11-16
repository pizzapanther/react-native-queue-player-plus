package com.margelo.nitro.queueplayer

/**
 * Pure state machine for the sleep timer — no player, volume, or handler
 * dependencies, so it stands alone. The owning [TrackPlayer]
 * drives it from a repeating main-looper runnable and applies the returned
 * decision (volume fade, pause) to the engine.
 *
 * Wall-clock: the deadline is a fixed epoch, so it counts down whether playback
 * is playing or paused. Two modes — a fixed [Mode.DURATION] and [Mode.END_OF_TRACK]
 * (pause when the current track finishes). A 60-second "tail" rule keeps a track
 * from being cut off near its end: if the deadline lands with <= [tailGraceSeconds]
 * left on the current track, the pause is deferred to that track's natural end.
 * Volume fades linearly over the final [fadeSeconds]. Mirrors the iOS
 * `SleepTimerCore`.
 */
class SleepTimerCore(
  private val fadeSeconds: Double = 10.0,
  private val tailGraceSeconds: Double = 60.0,
) {
  enum class Mode { INACTIVE, DURATION, END_OF_TRACK }

  /**
   * The decision produced by each [tick]. [fadeFraction] is the multiplier the
   * caller applies to the pre-fade volume (`1.0` = full / restore). [pauseNow]
   * asks the caller to pause playback. [stateChanged] asks the caller to emit
   * `onSleepTimerChange` (fired on arm/clear/conversion/fire, NOT on every
   * fade/countdown tick).
   */
  data class Tick(val fadeFraction: Double, val pauseNow: Boolean, val stateChanged: Boolean) {
    companion object {
      val IDLE = Tick(1.0, pauseNow = false, stateChanged = false)
    }
  }

  var mode: Mode = Mode.INACTIVE
    private set
  var deadlineEpochMs: Long? = null
    private set

  // Guards the tail rule to a single track so a stalled/looping position can't
  // defer the pause forever.
  private var tailExtended = false

  // True while an end-of-track timer is pending, and survives the conversion to
  // a DURATION fade so the real track-end signal (fireAtTrackEnd) knows to fire
  // — the poll can't hit the boundary exactly and never fires at all when the
  // duration is unknown (live/streaming).
  private var originEndOfTrack = false

  val isActive: Boolean get() = mode != Mode.INACTIVE
  val isEndOfTrack: Boolean get() = mode == Mode.END_OF_TRACK

  fun armDuration(seconds: Double, nowMs: Long) {
    mode = Mode.DURATION
    deadlineEpochMs = nowMs + (seconds * 1000).toLong()
    tailExtended = false
    originEndOfTrack = false
  }

  fun armEndOfTrack() {
    mode = Mode.END_OF_TRACK
    deadlineEpochMs = null
    tailExtended = false
    originEndOfTrack = true
  }

  fun clear() {
    mode = Mode.INACTIVE
    deadlineEpochMs = null
    tailExtended = false
    originEndOfTrack = false
  }

  /**
   * Fire the pending end-of-track timer at the current track's real end. The
   * owning engine calls this from its Media3 auto-transition signal so the pause
   * lands exactly at the boundary (and fires even when the duration was never
   * known). No-op unless an end-of-track timer is pending.
   */
  fun fireAtTrackEnd(): Tick {
    if (!originEndOfTrack) return Tick.IDLE
    clear()
    return Tick(1.0, pauseNow = true, stateChanged = true)
  }

  /** Whole seconds until the fixed deadline, or `null` when inactive / awaiting track end. */
  fun remainingSeconds(nowMs: Long): Int? {
    val deadline = deadlineEpochMs
    if (mode != Mode.DURATION || deadline == null) return null
    return maxOf(0, Math.round((deadline - nowMs) / 1000.0).toInt())
  }

  fun tick(nowMs: Long, trackDurationSec: Double, trackPositionSec: Double): Tick {
    when (mode) {
      Mode.INACTIVE -> return Tick.IDLE

      Mode.END_OF_TRACK -> {
        val tail = trackRemaining(trackDurationSec, trackPositionSec)
        if (tail != null && tail <= tailGraceSeconds) {
          mode = Mode.DURATION
          // Deadline lands AT the track boundary (not 1s past it) so the fade
          // completes at the end; on the local engine the real track-end signal
          // (fireAtTrackEnd) owns the actual pause, and this deadline is the
          // fallback the cast path rides out on the receiver's clock.
          deadlineEpochMs = nowMs + (tail * 1000).toLong()
          tailExtended = true
          return Tick(fadeFraction(tail), pauseNow = false, stateChanged = true)
        }
        return Tick.IDLE
      }

      Mode.DURATION -> {
        val deadline = deadlineEpochMs
        if (deadline == null) {
          mode = Mode.INACTIVE
          return Tick.IDLE
        }
        val remaining = (deadline - nowMs) / 1000.0
        if (remaining > 0) {
          return Tick(fadeFraction(remaining), pauseNow = false, stateChanged = false)
        }
        // Expired. Ride out the current track once if it's almost done.
        val tail = trackRemaining(trackDurationSec, trackPositionSec)
        if (!tailExtended && tail != null && tail > 0 && tail <= tailGraceSeconds) {
          tailExtended = true
          deadlineEpochMs = nowMs + ((tail + 1) * 1000).toLong()
          return Tick(fadeFraction(tail + 1), pauseNow = false, stateChanged = true)
        }
        // Fire.
        clear()
        return Tick(1.0, pauseNow = true, stateChanged = true)
      }
    }
  }

  /** Remaining seconds on the current track, or `null` when unknown (live stream, not-yet-loaded). */
  private fun trackRemaining(durationSec: Double, positionSec: Double): Double? {
    if (durationSec <= 0 || positionSec < 0) return null
    return maxOf(0.0, durationSec - positionSec)
  }

  private fun fadeFraction(remainingSec: Double): Double {
    if (remainingSec >= fadeSeconds) return 1.0
    if (remainingSec <= 0) return 0.0
    return remainingSec / fadeSeconds
  }
}
