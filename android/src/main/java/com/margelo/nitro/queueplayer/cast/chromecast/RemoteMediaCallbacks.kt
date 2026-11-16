package com.margelo.nitro.queueplayer.cast.chromecast

import com.google.android.gms.cast.MediaError
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.margelo.nitro.queueplayer.cast.RemoteEndReason
import com.margelo.nitro.queueplayer.cast.RemoteMediaError
import com.margelo.nitro.queueplayer.cast.RemotePlaybackState

/**
 * Bridges [RemoteMediaClient] status + progress updates onto the
 * `StateFlow`s exposed by [ChromecastSession]. Register in `init`,
 * unregister in `close`.
 *
 * Debounce: `onStatusUpdated` fires on every `MediaStatus` delta
 * including ones that don't change the fields we care about. The
 * callback dedups by tracking `lastIdleReasonEmitted` so we don't fan
 * out spurious "still idle for the same reason" notifications.
 */
internal class RemoteMediaCallbacks(
  private val onStateChanged: (RemotePlaybackState) -> Unit,
  private val onPositionChanged: (positionMs: Long) -> Unit,
  private val onDurationChanged: (durationMs: Long) -> Unit,
  private val onEndedReason: (RemoteEndReason) -> Unit,
  private val emitMediaError: (RemoteMediaError) -> Unit,
) {
  @Volatile
  private var lastIdleReasonEmitted: Int = MediaStatus.IDLE_REASON_NONE

  private val statusCallback = object : RemoteMediaClient.Callback() {
    override fun onStatusUpdated() {
      val client = currentClient ?: return
      val status = client.mediaStatus ?: run {
        onStateChanged(RemotePlaybackState.IDLE)
        return
      }
      val mapped = mapState(status)
      onStateChanged(mapped)

      val dur = status.mediaInfo?.streamDuration ?: -1L
      if (dur > 0) onDurationChanged(dur)

      if (status.playerState == MediaStatus.PLAYER_STATE_IDLE) {
        val reason = status.idleReason
        if (reason != MediaStatus.IDLE_REASON_NONE && reason != lastIdleReasonEmitted) {
          lastIdleReasonEmitted = reason
          onEndedReason(mapEndReason(reason))
        }
      } else {
        lastIdleReasonEmitted = MediaStatus.IDLE_REASON_NONE
      }
    }

    override fun onSendingRemoteMediaRequest() {
      onStateChanged(RemotePlaybackState.LOADING)
    }

    override fun onMediaError(mediaError: MediaError) {
      emitMediaError(
        RemoteMediaError(
          code = mediaError.detailedErrorCode ?: 0,
          message = mediaError.reason,
        )
      )
    }
  }

  private val progressListener = RemoteMediaClient.ProgressListener { progressMs, durationMs ->
    onPositionChanged(progressMs)
    if (durationMs > 0) onDurationChanged(durationMs)
  }

  @Volatile
  private var currentClient: RemoteMediaClient? = null

  fun attach(client: RemoteMediaClient) {
    currentClient = client
    client.registerCallback(statusCallback)
    client.addProgressListener(progressListener, PROGRESS_INTERVAL_MS)
  }

  fun detach() {
    val client = currentClient ?: return
    currentClient = null
    runCatching { client.unregisterCallback(statusCallback) }
    runCatching { client.removeProgressListener(progressListener) }
  }

  companion object {
    private const val PROGRESS_INTERVAL_MS = 1_000L

    fun mapState(status: MediaStatus): RemotePlaybackState = when (status.playerState) {
      MediaStatus.PLAYER_STATE_PLAYING -> RemotePlaybackState.PLAYING
      MediaStatus.PLAYER_STATE_PAUSED -> RemotePlaybackState.PAUSED
      MediaStatus.PLAYER_STATE_BUFFERING -> RemotePlaybackState.BUFFERING
      MediaStatus.PLAYER_STATE_LOADING -> RemotePlaybackState.LOADING
      MediaStatus.PLAYER_STATE_IDLE -> when (status.idleReason) {
        MediaStatus.IDLE_REASON_FINISHED -> RemotePlaybackState.ENDED
        MediaStatus.IDLE_REASON_ERROR -> RemotePlaybackState.ERROR
        else -> RemotePlaybackState.IDLE
      }
      else -> RemotePlaybackState.IDLE
    }

    fun mapEndReason(idleReason: Int): RemoteEndReason = when (idleReason) {
      MediaStatus.IDLE_REASON_FINISHED -> RemoteEndReason.FINISHED
      MediaStatus.IDLE_REASON_INTERRUPTED -> RemoteEndReason.INTERRUPTED
      MediaStatus.IDLE_REASON_CANCELED -> RemoteEndReason.CANCELED
      MediaStatus.IDLE_REASON_ERROR -> RemoteEndReason.ERROR
      else -> RemoteEndReason.CANCELED
    }
  }
}
