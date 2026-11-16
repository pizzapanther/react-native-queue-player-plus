package com.margelo.nitro.queueplayer.cast

import android.net.Uri
import com.margelo.nitro.queueplayer.TrackItem
import kotlinx.coroutines.runBlocking

/**
 * Transport-side shim — `TrackPlayer`'s per-call entry point for
 * "should this command route to the receiver, or hit the local
 * engine?". Each route helper returns `true` when the command was
 * accepted by a live remote session (caller skips local handling);
 * `false` when there's no active remote (caller proceeds locally).
 *
 * Reads the active remote through [PlaybackStateRouter.activeRemote];
 * snapshot semantics — by the time the local fallback runs, the
 * session may have ended, but the local path is the right thing for
 * the next call anyway.
 */
internal object CastTransportRouter {

  fun routePlay(): Boolean {
    val remote = PlaybackStateRouter.activeRemote() ?: return false
    remote.play()
    return true
  }

  fun routePause(): Boolean {
    val remote = PlaybackStateRouter.activeRemote() ?: return false
    remote.pause()
    return true
  }

  /**
   * Flip the receiver's play state. The receiver's own state picks the
   * direction — the local engine is parked and paused while casting, so its
   * `playWhenReady` would resume the receiver on every press. Anything that
   * represents "trying to play" toggles to pause, so a press during buffering
   * stops rather than restarts.
   */
  fun routeTogglePlayPause(): Boolean {
    val remote = PlaybackStateRouter.activeRemote() ?: return false
    val active = when (remote.state.value) {
      RemotePlaybackState.PLAYING,
      RemotePlaybackState.BUFFERING,
      RemotePlaybackState.LOADING -> true
      else -> false
    }
    if (active) remote.pause() else remote.play()
    return true
  }

  fun routeStop(): Boolean {
    val remote = PlaybackStateRouter.activeRemote() ?: return false
    remote.stop()
    return true
  }

  fun routeSeekTo(positionMs: Long): Boolean {
    val remote = PlaybackStateRouter.activeRemote() ?: return false
    remote.seekTo(positionMs)
    return true
  }

  fun routeSkipToNext(): Boolean {
    val remote = PlaybackStateRouter.activeRemote() ?: return false
    remote.queueNext()
    return true
  }

  fun routeSkipToPrevious(): Boolean {
    val remote = PlaybackStateRouter.activeRemote() ?: return false
    remote.queuePrev()
    return true
  }

  /**
   * Jump the receiver to the queue item at absolute lib-track [index].
   * Synchronous fire-and-forget (mirrors iOS `routeSkipToIndex`): the
   * caller intercepts-and-returns during cast, leaving the local engine
   * paused so its index write can't desync from the receiver echo.
   */
  fun routeSkipToIndex(index: Int): Boolean {
    val remote = PlaybackStateRouter.activeRemote() ?: return false
    remote.skipToQueueIndex(index)
    return true
  }

  fun routeSetVolume(value: Float): Boolean {
    val remote = PlaybackStateRouter.activeRemote() ?: return false
    remote.setVolume(value)
    return true
  }

  /**
   * `setQueue` is suspending on the remote side (the receiver
   * round-trip is awaited). Caller invokes this from a coroutine
   * scope or uses [routeSetQueueBlocking] from a thread that can
   * block. Returns `true` when the remote handled the load.
   */
  suspend fun routeSetQueue(
    tracks: List<TrackItem>,
    startIndex: Int,
    startPositionMs: Long,
    playWhenReady: Boolean,
  ): Boolean {
    val remote = PlaybackStateRouter.activeRemote() ?: return false
    val items = tracks.map(::toCastMediaItem)
    remote.load(items, startIndex, startPositionMs, playWhenReady)
    return true
  }

  fun routeSetQueueBlocking(
    tracks: List<TrackItem>,
    startIndex: Int,
    startPositionMs: Long,
    playWhenReady: Boolean,
  ): Boolean {
    val remote = PlaybackStateRouter.activeRemote() ?: return false
    val items = tracks.map(::toCastMediaItem)
    runBlocking { remote.load(items, startIndex, startPositionMs, playWhenReady) }
    return true
  }

  // Queue mutations mirror to the receiver while the local queue stays the
  // source of truth for the consumer's UI. `false` when no remote is active
  // (the caller's local mutation is the only effect). Receiver errors are
  // swallowed — a failed mirror must not fail the local mutation. Mirrors
  // iOS `CastTransportRouter` routeAddToQueue/Remove/Move/Clear.

  suspend fun routeAddToQueue(tracks: List<TrackItem>, beforeIndex: Int?): Boolean {
    val remote = PlaybackStateRouter.activeRemote() ?: return false
    runCatching { remote.queueInsert(tracks.map(::toCastMediaItem), beforeIndex) }
    return true
  }

  suspend fun routeRemoveFromQueue(indices: List<Int>): Boolean {
    val remote = PlaybackStateRouter.activeRemote() ?: return false
    runCatching { remote.queueRemove(indices) }
    return true
  }

  suspend fun routeMoveInQueue(fromIndex: Int, toIndex: Int): Boolean {
    val remote = PlaybackStateRouter.activeRemote() ?: return false
    runCatching { remote.queueMove(fromIndex, toIndex) }
    return true
  }

  suspend fun routeClearQueue(): Boolean {
    val remote = PlaybackStateRouter.activeRemote() ?: return false
    runCatching { remote.queueClear() }
    return true
  }

  /**
   * Convert a `TrackItem` into a `CastMediaItem`. Preserves the raw
   * URI (including `file://`); `MediaInfoBuilder` does the LAN-HTTP
   * substitution for receiver fetch on the load path.
   */
  private fun toCastMediaItem(track: TrackItem): CastMediaItem {
    return CastMediaItem(
      uri = Uri.parse(track.url),
      title = track.title?.takeIf { it.isNotEmpty() },
      artist = track.artist?.takeIf { it.isNotEmpty() },
      album = track.album?.takeIf { it.isNotEmpty() },
      artwork = track.artworkUrl?.takeIf { it.isNotEmpty() }?.let { Uri.parse(it) },
      durationMs = track.duration?.takeIf { it > 0 }?.let { (it * 1000).toLong() },
      contentType = null,
    )
  }
}
