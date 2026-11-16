package com.margelo.nitro.queueplayer.cast

import androidx.media3.common.Player
import androidx.media3.exoplayer.audio.AudioSink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A live cast session — what [CastBackend.connect] returns. Sealed
 * with two cases reflecting the two cast paradigms:
 *
 * - [PcmStream] — sender keeps decoding via ExoPlayer; session exposes
 *   an [AudioSink] that ExoPlayer pushes its decoded PCM into. AirPlay
 *   1 and AirPlay 2 both fit here.
 * - [RemotePlayer] — receiver decodes; ExoPlayer goes idle and the
 *   session forwards transport commands to the remote device.
 *   Chromecast + future DLNA/Sonos fit here.
 */
sealed class CastSession {

  /**
   * `true` when the backend expects the sender to keep decoding and
   * push PCM frames into an `AudioSink` (AirPlay); `false` when the
   * receiver pulls + decodes itself (Chromecast). Read by the
   * cast-transport router to decide whether to intercept
   * `TrackPlayer.*` calls or let the local player handle them.
   */
  abstract val isPushSink: Boolean

  /** Adjust receiver volume `0.0..1.0`. No-op when not supported. */
  abstract fun setVolume(value: Float)

  /** Tear down. Safe to call multiple times. */
  abstract fun close()

  /**
   * Invoked by the backend when the receiver side died unexpectedly
   * (WiFi loss, power-off, keep-alive timeout). Set by `CastManager`
   * after `connect()`; backend invokes on a detection thread.
   * Handlers must post cleanup to the main thread themselves.
   */
  @Volatile
  var deathListener: ((reason: String) -> Unit)? = null

  /** Paradigm A — backend is an `AudioSink`. */
  abstract class PcmStream : CastSession() {
    final override val isPushSink: Boolean = true

    /**
     * Return the `AudioSink` ExoPlayer should push decoded PCM into.
     * Called once per active session by `CastSinkRouter` when the
     * session becomes active.
     */
    abstract fun audioSink(): AudioSink
  }

  /** Paradigm B — backend is a remote player. */
  abstract class RemotePlayer : CastSession() {
    final override val isPushSink: Boolean = false

    /**
     * Optional `Player` implementation for `MediaSession`. When
     * non-null, `PlaybackService` retargets the session's forwarding
     * player to this on cast connect and back to the local engine on
     * disconnect. Chromecast returns a `RemoteCastPlayer` from
     * `media3-cast`. Default null.
     */
    open val mediaSessionPlayer: Player? = null

    /**
     * Push the full [items] queue to the receiver starting at
     * [startIndex] + [startPositionMs], beginning playback only when
     * [playWhenReady] is true (a handoff from a paused local session
     * starts the receiver paused at that position).
     */
    abstract suspend fun load(
      items: List<CastMediaItem>,
      startIndex: Int,
      startPositionMs: Long,
      playWhenReady: Boolean,
    )

    abstract fun play()
    abstract fun pause()
    abstract fun stop()
    abstract fun seekTo(positionMs: Long)
    abstract fun queueNext()
    abstract fun queuePrev()

    /**
     * Jump the receiver to the queue item at absolute lib-track [index].
     * The backend resolves [index] to the receiver itemId against the
     * full queue (the Cast SDK `MediaQueue`), so callers pass lib
     * indices, never opaque receiver itemIds. Out-of-range / unresolved
     * indices are a no-op.
     */
    abstract fun skipToQueueIndex(index: Int)

    /**
     * Insert [items] before the queue item at absolute lib-track
     * [beforeIndex] (resolved to a receiver itemId against the full
     * queue); `null` / out-of-range appends.
     */
    abstract suspend fun queueInsert(
      items: List<CastMediaItem>,
      beforeIndex: Int?,
    )

    /** Remove the queue items at the given absolute lib-track indices. */
    abstract suspend fun queueRemove(indices: List<Int>)

    /**
     * Move the queue item at [fromIndex] to [toIndex] (array-move
     * semantics).
     */
    abstract suspend fun queueMove(fromIndex: Int, toIndex: Int)

    /** Remove every item from the receiver queue. */
    abstract suspend fun queueClear()

    abstract fun setPlaybackRate(rate: Float)
    abstract fun setRepeatMode(mode: RemoteRepeatMode)

    /** Receiver-driven playback state for `PlaybackStateRouter`. */
    abstract val state: StateFlow<RemotePlaybackState>

    /**
     * `true` once the receiver has reported any state other than
     * [RemotePlaybackState.IDLE]. Set by the first such report and stays
     * set for the session's lifetime.
     *
     * State reads use this to decide whether the receiver can answer yet: a
     * session that has just activated still holds its initial IDLE, and the
     * local engine is the better answer until the receiver speaks. Once it
     * has, the receiver is authoritative — including a later IDLE, which
     * means it genuinely has nothing loaded.
     */
    abstract val hasReportedState: Boolean

    /** Receiver-driven position in ms. */
    abstract val positionMs: StateFlow<Long>

    /** Receiver-reported stream duration in ms. */
    abstract val durationMs: StateFlow<Long>

    /**
     * RNQP track index derived from media-session player's
     * `currentMediaItemIndex`. `-1` = unknown.
     */
    open val currentTrackIndex: StateFlow<Int>
      get() = MutableStateFlow(-1)

    /** Fires when receiver enters IDLE with non-NONE idleReason. */
    abstract val endedReason: SharedFlow<RemoteEndReason>

    /** Fires once per receiver media error. */
    abstract val mediaError: SharedFlow<RemoteMediaError>

    /** Fires once per completed seek with the resolved position (ms). */
    abstract val seekCompleted: SharedFlow<Long>
  }
}

/**
 * Reason a track ended on the receiver. Maps `MediaStatus.IDLE_REASON_*`
 * (Android) and `GCKMediaPlayerIdleReason*` (iOS) onto one enum.
 */
enum class RemoteEndReason {
  FINISHED,
  INTERRUPTED,
  CANCELED,
  ERROR,
}

/**
 * Error from the receiver during playback. [code] is platform-native;
 * [message] is platform-native description when available.
 */
data class RemoteMediaError(
  val code: Int,
  val message: String?,
)

/**
 * Receiver-queue repeat mode. Maps `MediaStatus.REPEAT_MODE_*` /
 * `GCKMediaRepeatMode*`. `ALL_AND_SHUFFLE` intentionally absent.
 */
enum class RemoteRepeatMode {
  OFF,
  SINGLE,
  ALL,
}

/**
 * Playback state from a [CastSession.RemotePlayer]. Mapping to lib's
 * local-side `State` enum lives in `PlaybackStateRouter`.
 */
enum class RemotePlaybackState {
  IDLE,
  LOADING,
  PLAYING,
  PAUSED,
  BUFFERING,
  ENDED,
  ERROR,
}
