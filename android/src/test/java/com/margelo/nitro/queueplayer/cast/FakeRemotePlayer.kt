package com.margelo.nitro.queueplayer.cast

import androidx.media3.common.Player
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Test double for [CastSession.RemotePlayer]. Transport calls record their
 * name in [transportCalls]; the StateFlows are driveable via the public
 * backing fields, or via [reportState] to mirror the real session's
 * [hasReportedState] flag; [mediaSessionPlayer] is injectable so bind tests
 * can assert the MediaSession swaps to it.
 */
internal class FakeRemotePlayer(
  private val player: Player? = null,
) : CastSession.RemotePlayer() {

  override val mediaSessionPlayer: Player? get() = player

  val backingState = MutableStateFlow(RemotePlaybackState.IDLE)
  val backingPositionMs = MutableStateFlow(0L)
  val backingDurationMs = MutableStateFlow(0L)
  val backingCurrentTrackIndex = MutableStateFlow(-1)
  private val backingEnded = MutableSharedFlow<RemoteEndReason>(extraBufferCapacity = 4)
  private val backingError = MutableSharedFlow<RemoteMediaError>(extraBufferCapacity = 4)
  private val backingSeek = MutableSharedFlow<Long>(extraBufferCapacity = 4)

  override val state: StateFlow<RemotePlaybackState> = backingState.asStateFlow()
  override val positionMs: StateFlow<Long> = backingPositionMs.asStateFlow()
  override val durationMs: StateFlow<Long> = backingDurationMs.asStateFlow()
  override val currentTrackIndex: StateFlow<Int> = backingCurrentTrackIndex.asStateFlow()
  override val endedReason: SharedFlow<RemoteEndReason> = backingEnded.asSharedFlow()
  override val mediaError: SharedFlow<RemoteMediaError> = backingError.asSharedFlow()
  override val seekCompleted: SharedFlow<Long> = backingSeek.asSharedFlow()

  /** Transport calls in the order they arrived, for routing assertions. */
  val transportCalls = mutableListOf<String>()

  override var hasReportedState: Boolean = false

  /**
   * Drive the receiver state the way a live session does: a non-idle report
   * sets [hasReportedState]. Set [backingState] directly to stage a state
   * the receiver has not reported yet.
   */
  fun reportState(state: RemotePlaybackState) {
    if (state != RemotePlaybackState.IDLE) hasReportedState = true
    backingState.value = state
  }

  override fun setVolume(value: Float) {}
  override fun close() {}
  override fun play() { transportCalls += "play" }
  override fun pause() { transportCalls += "pause" }
  override fun stop() { transportCalls += "stop" }
  override fun seekTo(positionMs: Long) { transportCalls += "seekTo:$positionMs" }
  override fun queueNext() { transportCalls += "queueNext" }
  override fun queuePrev() { transportCalls += "queuePrev" }
  override fun skipToQueueIndex(index: Int) { transportCalls += "skipToQueueIndex:$index" }
  override fun setPlaybackRate(rate: Float) {}
  override fun setRepeatMode(mode: RemoteRepeatMode) {}

  override suspend fun load(
    items: List<CastMediaItem>,
    startIndex: Int,
    startPositionMs: Long,
    playWhenReady: Boolean,
  ) {}

  override suspend fun queueInsert(items: List<CastMediaItem>, beforeIndex: Int?) {}
  override suspend fun queueRemove(indices: List<Int>) {}
  override suspend fun queueMove(fromIndex: Int, toIndex: Int) {}
  override suspend fun queueClear() {}
}
