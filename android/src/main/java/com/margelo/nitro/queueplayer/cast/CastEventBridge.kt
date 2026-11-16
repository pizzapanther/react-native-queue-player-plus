package com.margelo.nitro.queueplayer.cast

import android.util.Log
import com.margelo.nitro.queueplayer.PlayerState
import com.margelo.nitro.queueplayer.StateChangeReason
import com.margelo.nitro.queueplayer.TrackPlayer
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Bridges cast `RemotePlayer` session state back into the lib's
 * existing event surface (`onStateChange` / etc.). Observes
 * [PlaybackStateRouter.activeFlow]: when a session becomes active,
 * subscribes to its StateFlows + fans receiver updates through
 * `TrackPlayer.emitRemoteState`. When the session ends, unsubscribes
 * cleanly.
 *
 * Single-instance — `TrackPlayer.init` constructs one bridge and
 * holds it for the process lifetime (HybridObject singleton). The
 * bridge's own scope is `SupervisorJob() + Dispatchers.Main` so a
 * collector failure on one session doesn't tear down the watcher
 * for the next.
 */
internal class CastEventBridge(private val owner: TrackPlayer) {

  // Last-resort guard: an unexpected throw in a per-session collector
  // (e.g. a receiver-load failure surfacing through the handoff) must log,
  // not crash the app's main thread.
  private val exceptionHandler = CoroutineExceptionHandler { _, t ->
    Log.w("CastEventBridge", "Cast bridge collector failed", t)
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + exceptionHandler)

  /** Active per-session collection job (cancelled on session change). */
  private var sessionJob: Job? = null

  // Continuous snapshot of the receiver's last-known position + track
  // index while casting, applied to the local engine on the cast->local
  // handback so it lands on the receiver's last spot (paused).
  private var lastPositionMs: Long = 0L
  private var lastTrackIndex: Int = -1

  fun start() {
    scope.launch {
      PlaybackStateRouter.activeFlow.collectLatest { session ->
        sessionJob?.cancel()
        sessionJob = null
        if (session == null) {
          // Returning to local — seek the local engine to where the
          // receiver left off and stay paused (the user resumes locally).
          owner.resumeLocalAfterCast(
            trackIndex = lastTrackIndex,
            positionMs = lastPositionMs,
          )
          return@collectLatest
        }
        // New session — reset the snapshot, then hand off current local
        // playback to the receiver so it continues from the same position
        // + play/pause.
        lastPositionMs = 0L
        lastTrackIndex = -1
        owner.handoffCurrentPlaybackToCast()
        sessionJob = scope.launch {
          launch {
            session.state.collectLatest { remoteState ->
              owner.emitRemoteState(mapRemotePlaybackState(remoteState), StateChangeReason.SYSTEM)
            }
          }
          launch {
            session.positionMs.collectLatest { pos -> lastPositionMs = pos }
          }
          launch {
            session.currentTrackIndex.collectLatest { idx ->
              if (idx >= 0) {
                lastTrackIndex = idx
                // Fan the receiver's queue position to the JS now-playing
                // surface (onTrackChange + getCurrentTrackIndex), not just
                // the resume snapshot.
                owner.emitRemoteTrackChange(idx)
              }
            }
          }
        }
      }
    }
  }

  fun stop() {
    sessionJob?.cancel()
    sessionJob = null
    scope.cancel()
  }

  /**
   * Seed the resume snapshot from the local pre-handoff position + index
   * so a disconnect before the receiver reports (rapid connect/disconnect,
   * before the load round-trip settles) still lands local on the right
   * spot. Receiver position/index updates collected during the session
   * take precedence as they arrive.
   */
  fun seedResumeSnapshot(trackIndex: Int, positionMs: Long) {
    lastTrackIndex = trackIndex
    lastPositionMs = positionMs
  }

}
