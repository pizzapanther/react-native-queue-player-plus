package com.margelo.nitro.queueplayer.cast.chromecast

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.cast.RemoteCastPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.Cast
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.api.PendingResult
import com.margelo.nitro.queueplayer.BuildConfig
import com.margelo.nitro.queueplayer.cast.CastBackends
import com.margelo.nitro.queueplayer.cast.CastMediaItem
import com.margelo.nitro.queueplayer.cast.CastSession
import com.margelo.nitro.queueplayer.cast.PlaybackStateRouter
import com.margelo.nitro.queueplayer.cast.RemoteEndReason
import com.margelo.nitro.queueplayer.cast.RemoteMediaError
import com.margelo.nitro.queueplayer.cast.RemotePlaybackState
import com.margelo.nitro.queueplayer.cast.RemoteRepeatMode
import com.margelo.nitro.queueplayer.cast.http.MediaServerHandle
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import com.google.android.gms.cast.framework.CastSession as GmsCastSession

/**
 * Live Chromecast session wrapping a [GmsCastSession] handed over by
 * the Cast SDK. Implements the full [CastSession.RemotePlayer]
 * contract:
 *
 * - [load] translates each [CastMediaItem] into a `MediaInfo` via
 *   [MediaInfoBuilder] and calls `RemoteMediaClient.queueLoad(...)`.
 *   `file://` URLs are served via [CastBackends.localMediaServer];
 *   remote URLs pass through unchanged.
 * - [play] / [pause] / [stop] / transport mutations fire-and-forget
 *   to `RemoteMediaClient`.
 * - State + position + queue updates flow back via
 *   [RemoteMediaCallbacks] which translates `MediaStatus` deltas into
 *   the StateFlow / SharedFlow surface the bridge consumes.
 *
 * `mediaSessionPlayer` returns a Media3 `CastPlayer` bound to the same
 * receiver. `PlaybackService` swaps it onto the `MediaSession` during
 * cast so the system notification + lockscreen follow the receiver's
 * metadata/transport. JS-issued transport + events stay on the
 * StateFlows + `RemoteMediaClient` here; both views observe the one
 * receiver, which is authoritative.
 *
 * Threading: the Cast SDK's session manager, `RemoteMediaClient`, and
 * the underlying `MediaRouter` are all main-thread-bound. Every call
 * hops to main via [runOnMain].
 */
internal class ChromecastSession(
  private val context: Context,
  private val sdkSession: GmsCastSession,
) : CastSession.RemotePlayer() {

  private val mainHandler = Handler(Looper.getMainLooper())

  @Volatile
  private var closed: Boolean = false

  /// In-flight queue-mutation continuations, main-thread confined (registered,
  /// resumed, and drained only inside [runOnMain] / SDK main-thread callbacks).
  /// [close] resumes any still-active entry so a request whose receiver reply
  /// never arrives (session torn down after dispatch) unblocks instead of
  /// hanging the awaiting coroutine.
  private val pendingRequests = mutableSetOf<CancellableContinuation<Unit>>()

  private val _state = MutableStateFlow(RemotePlaybackState.IDLE)
  private val _positionMs = MutableStateFlow(0L)
  private val _durationMs = MutableStateFlow(0L)
  private val _currentTrackIndex = MutableStateFlow(-1)
  private val _volume = MutableStateFlow(Float.NaN)
  private val _endedReason = MutableSharedFlow<RemoteEndReason>(extraBufferCapacity = 4)
  private val _mediaError = MutableSharedFlow<RemoteMediaError>(extraBufferCapacity = 4)
  private val _seekCompleted = MutableSharedFlow<Long>(extraBufferCapacity = 4)

  override val state: StateFlow<RemotePlaybackState> = _state.asStateFlow()

  @Volatile
  override var hasReportedState: Boolean = false
    private set
  override val positionMs: StateFlow<Long> = _positionMs.asStateFlow()
  override val durationMs: StateFlow<Long> = _durationMs.asStateFlow()
  override val currentTrackIndex: StateFlow<Int> = _currentTrackIndex.asStateFlow()
  override val endedReason: SharedFlow<RemoteEndReason> = _endedReason.asSharedFlow()
  override val mediaError: SharedFlow<RemoteMediaError> = _mediaError.asSharedFlow()
  override val seekCompleted: SharedFlow<Long> = _seekCompleted.asSharedFlow()

  /**
   * Most-recent receiver-reported device volume (`0.0..1.0`), or
   * `NaN` while no `MediaStatus` has been seen yet. Mirrored to JS
   * via `CastManager.getRouteVolume()`.
   */
  val volume: StateFlow<Float> = _volume.asStateFlow()

  /**
   * Media-server handle, acquired lazily on first [load] call that
   * needs it (local file). Closed in [close].
   */
  @Volatile
  private var serverHandle: MediaServerHandle? = null

  private val remoteMediaCallbacks = RemoteMediaCallbacks(
    onStateChanged = { next ->
      if (BuildConfig.DEBUG && _state.value != next) {
        Log.d(TAG, "receiver state ${_state.value} -> $next")
      }
      if (next != RemotePlaybackState.IDLE) hasReportedState = true
      _state.value = next
    },
    onPositionChanged = { _positionMs.value = it },
    onDurationChanged = { _durationMs.value = it },
    onEndedReason = { _endedReason.tryEmit(it) },
    emitMediaError = { _mediaError.tryEmit(it) },
  )

  /**
   * Receiver device-volume listener. The hardware volume rocker + the receiver's
   * own controls move the DEVICE volume (`CastSession.volume`), which
   * `MediaStatus.streamVolume` does not carry — so device volume is observed here
   * rather than in [RemoteMediaCallbacks]. Feeds [_volume], the `0.0..1.0`
   * device-volume plane that `getRouteVolume` and [CastVolumeForwardingPlayer]
   * read (so GET and SET share one plane).
   */
  private val castVolumeListener = object : Cast.Listener() {
    override fun onVolumeChanged() {
      _volume.value = runCatching { sdkSession.volume.toFloat() }.getOrDefault(Float.NaN)
    }
  }

  init {
    runOnMain {
      sdkSession.remoteMediaClient?.let { remoteMediaCallbacks.attach(it) }
      runCatching { sdkSession.addCastListener(castVolumeListener) }
      _volume.value = runCatching { sdkSession.volume.toFloat() }.getOrDefault(Float.NaN)
    }
  }

  /**
   * Media3 `Player` view over this receiver, built lazily on first
   * access. `PlaybackService` performs that access on the main thread —
   * the receiver-only `RemoteCastPlayer` binds to its calling thread —
   * and binds the result onto the `MediaSession` for the cast session's
   * duration. Stored as the stable `Player` type so teardown touches no
   * `@UnstableApi` surface. This is a control/metadata view only: the
   * receiver decodes the URLs pushed via [load]; the device never enters
   * the audio path (so there is no local engine machinery here — EQ,
   * visualizer, lookahead, audio focus all belong to the local engines).
   */
  private var sessionPlayer: Player? = null

  override val mediaSessionPlayer: Player?
    get() = ensureCastPlayer()

  @UnstableApi
  private fun ensureCastPlayer(): Player {
    sessionPlayer?.let { return it }
    val player = RemoteCastPlayer.Builder(context)
      .setMediaItemConverter(CastMediaItemConverter())
      .build()
    // Track the receiver's current queue position for the cast->local
    // resume. Suppress the spurious index-0 PLAYLIST_CHANGED during
    // queueLoad; capture the settled index from the timeline and real
    // item transitions.
    player.addListener(object : Player.Listener {
      override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return
        player.currentMediaItemIndex.takeIf { it >= 0 }?.let { _currentTrackIndex.value = it }
      }

      override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        if (timeline.isEmpty) return
        player.currentMediaItemIndex.takeIf { it >= 0 }?.let { _currentTrackIndex.value = it }
      }
    })
    sessionPlayer = CastVolumeForwardingPlayer(
      player = player,
      routingControllerId = runCatching { sdkSession.castDevice?.deviceId }.getOrNull(),
      setReceiverVolume = { setVolume(it) },
      receiverVolume = { _volume.value },
    )
    return sessionPlayer!!
  }

  override fun setVolume(value: Float) {
    if (closed) return
    val v = value.coerceIn(0f, 1f).toDouble()
    runOnMain {
      if (closed) return@runOnMain
      runCatching { sdkSession.volume = v }
        .onFailure { Log.w(TAG, "setVolume($v) failed", it) }
    }
  }

  override suspend fun load(
    items: List<CastMediaItem>,
    startIndex: Int,
    startPositionMs: Long,
    playWhenReady: Boolean,
  ) {
    Log.i(TAG, "load: ${items.size} items, startIndex=$startIndex, posMs=$startPositionMs, play=$playWhenReady, closed=$closed")
    if (closed) throw IllegalStateException("Session is closed")
    if (items.isEmpty()) throw IllegalArgumentException("load: items must not be empty")

    // Guard against the stock Chromecast receiver's ~100-item queue cap.
    // Truncate + emit a structured error so JS sees the degradation
    // instead of silent mirror corruption (sender thinks queue has N
    // items, receiver actually has 100).
    val effectiveItems = if (items.size > RECEIVER_QUEUE_LIMIT) {
      Log.w(TAG, "load: queue size ${items.size} exceeds receiver cap $RECEIVER_QUEUE_LIMIT — truncating")
      _mediaError.tryEmit(
        RemoteMediaError(
          code = -1,
          message = "Queue size ${items.size} exceeds Chromecast receiver cap of $RECEIVER_QUEUE_LIMIT items. Truncated to first $RECEIVER_QUEUE_LIMIT.",
        )
      )
      items.take(RECEIVER_QUEUE_LIMIT)
    } else {
      items
    }

    val clampedStart = startIndex.coerceIn(0, effectiveItems.size - 1)

    val server = CastBackends.localMediaServer
    val handle = serverHandle ?: server.acquire(sessionId(), context).also { serverHandle = it }
    val lanAddress = server.currentLanAddress

    val queueItems = effectiveItems.mapIndexed { index, item ->
      val mediaInfo = MediaInfoBuilder.build(
        item = item,
        handle = handle,
        lanAddress = lanAddress,
      )
      // Only the start item honours playWhenReady; later items keep
      // autoplay so the receiver auto-advances once playing. A handoff
      // from a paused local session loads the receiver paused.
      MediaQueueItem.Builder(mediaInfo)
        .setAutoplay(if (index == clampedStart) playWhenReady else true)
        .build()
    }.toTypedArray()

    _state.value = RemotePlaybackState.LOADING
    try {
      awaitRemoteMediaRequest("queueLoad") { client ->
        client.queueLoad(
          queueItems,
          clampedStart,
          MediaStatus.REPEAT_MODE_REPEAT_OFF,
          startPositionMs,
          null,
        )
      }
    } catch (t: Throwable) {
      _state.value = RemotePlaybackState.ERROR
      throw t
    }
    // Seed the resume index from the load's start so the cast->local
    // handback has a valid index even before the CastPlayer timeline
    // listener observes the receiver's first settled item. Receiver-driven
    // transitions (auto-advance, on-receiver skip) update it afterwards.
    _currentTrackIndex.value = clampedStart
  }

  // No bitmask flag gates `play` — the mask models pause, not resume.
  override fun play() {
    fireRemote("play") { it.play() }
  }

  override fun pause() {
    fireRemote("pause", requires = MediaStatus.COMMAND_PAUSE) { it.pause() }
  }

  override fun stop() {
    fireRemote("stop") { it.stop() }
  }

  override fun queueNext() {
    fireRemote("queueNext", requires = MediaStatus.COMMAND_QUEUE_NEXT) { it.queueNext(null) }
  }

  override fun queuePrev() {
    fireRemote("queuePrev", requires = MediaStatus.COMMAND_QUEUE_PREVIOUS) { it.queuePrev(null) }
  }

  override fun skipToQueueIndex(index: Int) {
    if (index < 0) return
    // Resolve the absolute lib index to the receiver's itemId via the full
    // queue (`MediaQueue`) — the same source of truth used to read the
    // current index back. `INVALID_ITEM_ID` means the queue model hasn't
    // synced that slot yet; skip rather than jump blind.
    fireRemote("skipToQueueIndex") { client ->
      val itemId = receiverItemIdAtIndex(index)
      if (itemId == MediaQueueItem.INVALID_ITEM_ID) null
      else client.queueJumpToItem(itemId, null)
    }
  }

  override suspend fun queueInsert(
    items: List<CastMediaItem>,
    beforeIndex: Int?,
  ) {
    if (closed) throw IllegalStateException("Session is closed")
    if (items.isEmpty()) return

    val server = CastBackends.localMediaServer
    val handle = serverHandle ?: server.acquire(sessionId(), context).also { serverHandle = it }
    val lanAddress = server.currentLanAddress

    val queueItems = items.map { item ->
      MediaQueueItem.Builder(
        MediaInfoBuilder.build(item = item, handle = handle, lanAddress = lanAddress)
      ).setAutoplay(true).build()
    }.toTypedArray()

    // Resolve the anchor index to a receiver itemId against the full queue
    // (`MediaQueue`). `null` / out-of-range appends (INVALID_ITEM_ID).
    awaitRemoteMediaRequest("queueInsert") { client ->
      val anchor =
        if (beforeIndex != null) receiverItemIdAtIndex(beforeIndex)
        else MediaQueueItem.INVALID_ITEM_ID
      client.queueInsertItems(queueItems, anchor, null)
    }
  }

  override suspend fun queueRemove(indices: List<Int>) {
    if (closed) throw IllegalStateException("Session is closed")
    if (indices.isEmpty()) return
    // Resolve all indices to stable itemIds first, then remove by id — so
    // a multi-remove isn't affected by index shifting on the receiver.
    awaitRemoteMediaRequest("queueRemove") { client ->
      val ids = indices
        .map { receiverItemIdAtIndex(it) }
        .filter { it != MediaQueueItem.INVALID_ITEM_ID }
      // Nothing resolved (queue not synced yet) — clean no-op.
      if (ids.isEmpty()) null
      else client.queueRemoveItems(ids.toIntArray(), null)
    }
  }

  override suspend fun queueMove(fromIndex: Int, toIndex: Int) {
    if (closed) throw IllegalStateException("Session is closed")
    if (fromIndex < 0 || toIndex < 0 || fromIndex == toIndex) return
    awaitRemoteMediaRequest("queueMove") { client ->
      val fromId = receiverItemIdAtIndex(fromIndex)
      // Unresolvable source index (queue not synced yet) — clean no-op.
      // Android's single-move primitive places the item at the absolute
      // target index directly (Google-recommended) — no anchor-item
      // resolution needed, unlike iOS's reorder-before-anchor path.
      if (fromId == MediaQueueItem.INVALID_ITEM_ID) null
      else client.queueMoveItemToNewIndex(fromId, toIndex, null)
    }
  }

  override suspend fun queueClear() {
    if (closed) throw IllegalStateException("Session is closed")
    // Collect every itemId from the full queue and remove them — this
    // terminates the receiver media session, matching iOS clear.
    awaitRemoteMediaRequest("queueClear") { client ->
      val ids = client.mediaQueue?.itemIds
      // Clearing an already-empty receiver queue is a clean no-op, not an
      // error (mirrors iOS, which early-returns when the queue is empty).
      if (ids == null || ids.isEmpty()) null
      else client.queueRemoveItems(ids, null)
    }
  }

  override fun setPlaybackRate(rate: Float) {
    fireRemote("setPlaybackRate", requires = MediaStatus.COMMAND_PLAYBACK_RATE) {
      it.setPlaybackRate(rate.toDouble())
    }
  }

  override fun setRepeatMode(mode: RemoteRepeatMode) {
    val sdkMode = when (mode) {
      RemoteRepeatMode.OFF -> MediaStatus.REPEAT_MODE_REPEAT_OFF
      RemoteRepeatMode.SINGLE -> MediaStatus.REPEAT_MODE_REPEAT_SINGLE
      RemoteRepeatMode.ALL -> MediaStatus.REPEAT_MODE_REPEAT_ALL
    }
    fireRemote("setRepeatMode") { it.queueSetRepeatMode(sdkMode, null) }
  }

  override fun seekTo(positionMs: Long) {
    if (closed) return
    runOnMain {
      if (closed) return@runOnMain
      val client = sdkSession.remoteMediaClient ?: run {
        Log.w(TAG, "seekTo: no RemoteMediaClient — session not fully live?")
        return@runOnMain
      }
      runCatching {
        client.seek(
          MediaSeekOptions.Builder()
            .setPosition(positionMs)
            .setResumeState(MediaSeekOptions.RESUME_STATE_UNCHANGED)
            .build()
        ).setResultCallback { result ->
          if (closed) return@setResultCallback
          if (result.status.isSuccess) {
            _seekCompleted.tryEmit(positionMs)
          } else {
            Log.w(
              TAG,
              "seekTo($positionMs) failed: ${result.status.statusCode} " +
                "${result.status.statusMessage}"
            )
          }
        }
      }.onFailure { Log.w(TAG, "seekTo($positionMs) threw", it) }
    }
  }

  override fun close() {
    if (closed) return
    closed = true
    runOnMain {
      // Unblock any in-flight queue-mutation request whose receiver reply will
      // never arrive now the session is torn down.
      val inflight = pendingRequests.toList()
      pendingRequests.clear()
      inflight.forEach { c ->
        if (c.isActive) c.resumeWithException(IllegalStateException("Session closed"))
      }
      // clearActive flips PlaybackStateRouter.activeFlow to null, which
      // drives PlaybackService to restore the local player and release
      // the CastPlayer (it owns the binding lifetime). This session does
      // not release sessionPlayer itself — doing so here would race the
      // session-player restore and could leave the MediaSession pointing
      // at a released player.
      runCatching { PlaybackStateRouter.clearActive(this) }
      runCatching { sdkSession.removeCastListener(castVolumeListener) }
      remoteMediaCallbacks.detach()
      runCatching { serverHandle?.close() }
      serverHandle = null
    }
  }

  /**
   * Post [block] to main and invoke it with the current [RemoteMediaClient].
   * Silently skips when the session is closed or the client is unavailable
   * (log only — transport calls are fire-and-forget).
   *
   * [block] returns the [PendingResult] the SDK scheduled, or `null` when it
   * declined to issue one. A returned result gets a callback so a
   * receiver-side rejection is reported instead of vanishing — without it a
   * refused command is indistinguishable from one the receiver honoured.
   *
   * [requires] names the [MediaStatus] command bitmask flag the receiver must
   * advertise for this command (`null` for commands not gated by the mask).
   * The receiver publishes support per media item, so an item can accept queue
   * commands while refusing `pause` — worth a warning, since the command is
   * otherwise dropped in silence.
   */
  private fun fireRemote(
    label: String,
    requires: Long? = null,
    block: (RemoteMediaClient) -> PendingResult<RemoteMediaClient.MediaChannelResult>?,
  ) {
    if (closed) return
    runOnMain {
      if (closed) return@runOnMain
      val client = sdkSession.remoteMediaClient
      if (client == null) {
        Log.w(TAG, "$label: no RemoteMediaClient — session not fully live?")
        return@runOnMain
      }
      val status = client.mediaStatus
      if (requires != null && status != null && !status.isMediaCommandSupported(requires)) {
        Log.w(
          TAG,
          "$label: receiver does not advertise support for this command " +
            "(supportedMediaCommands=${status.supportedMediaCommands}); sending anyway",
        )
      }
      if (BuildConfig.DEBUG) Log.d(TAG, "$label: issuing")
      val outcome = runCatching { block(client) }
      val thrown = outcome.exceptionOrNull()
      if (thrown != null) {
        Log.w(TAG, "$label failed", thrown)
        return@runOnMain
      }
      // A null result means this call site declined to issue anything — the
      // only such case is a queue index the receiver's model hasn't synced
      // yet, which is a normal transient, not a fault.
      val pending = outcome.getOrNull()
      if (pending == null) {
        if (BuildConfig.DEBUG) Log.d(TAG, "$label: no request issued")
        return@runOnMain
      }
      pending.setResultCallback { result ->
        if (closed) return@setResultCallback
        if (!result.status.isSuccess) {
          Log.w(
            TAG,
            "$label failed: ${result.status.statusCode} ${result.status.statusMessage}",
          )
        }
      }
    }
  }

  /**
   * Run [block] on the main thread against the current
   * [RemoteMediaClient] and await its returned `PendingResult`. Used
   * by [load] + other queue-mutation calls where callers need the
   * request to have landed (or failed) before returning.
   */
  private suspend fun awaitRemoteMediaRequest(
    label: String,
    block: (RemoteMediaClient) -> com.google.android.gms.common.api.PendingResult<RemoteMediaClient.MediaChannelResult>?,
  ) = suspendCancellableCoroutine<Unit> { cont ->
    runOnMain {
      if (closed) {
        cont.resumeWithException(IllegalStateException("Session closed before $label"))
        return@runOnMain
      }
      val client = sdkSession.remoteMediaClient
      if (client == null) {
        cont.resumeWithException(
          IllegalStateException("$label: RemoteMediaClient unavailable")
        )
        return@runOnMain
      }
      runCatching { block(client) }
        .onFailure { cont.resumeWithException(it) }
        .onSuccess { pending ->
          // A `null` request is a deliberate no-op (nothing to mirror —
          // e.g. clearing an already-empty queue): resume cleanly rather
          // than treat it as an error.
          if (pending == null) {
            resumeOnce(cont) { it.resume(Unit) }
            return@onSuccess
          }
          pendingRequests.add(cont)
          pending.setResultCallback { result ->
            val status = result.status
            if (status.isSuccess) {
              resumeOnce(cont) { it.resume(Unit) }
            } else {
              resumeOnce(cont) {
                it.resumeWithException(
                  RuntimeException(
                    "$label failed: ${status.statusCode} ${status.statusMessage}"
                  )
                )
              }
            }
          }
        }
    }
  }

  private inline fun resumeOnce(
    cont: CancellableContinuation<Unit>,
    action: (CancellableContinuation<Unit>) -> Unit,
  ) {
    pendingRequests.remove(cont)
    if (cont.isActive) action(cont)
  }

  /**
   * Receiver itemId for the queue item at absolute lib-track [index],
   * resolved via the full `MediaQueue` (the Android analogue of iOS
   * `GCKMediaQueue` — it tracks the whole queue's itemIds, not the
   * receiver's status window). Returns [MediaQueueItem.INVALID_ITEM_ID]
   * (the "no anchor" / append sentinel, value `0`) for negative or
   * out-of-range / not-yet-synced indices. Main-thread only — callers
   * run inside [fireRemote] / [awaitRemoteMediaRequest].
   */
  private fun receiverItemIdAtIndex(index: Int): Int =
    if (index < 0) MediaQueueItem.INVALID_ITEM_ID
    else sdkSession.remoteMediaClient?.mediaQueue?.itemIdAtIndex(index)
      ?: MediaQueueItem.INVALID_ITEM_ID

  private fun sessionId(): String =
    runCatching { sdkSession.sessionId ?: "chromecast-unknown" }
      .getOrDefault("chromecast-unknown")

  private fun runOnMain(block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) block()
    else mainHandler.post(block)
  }

  private companion object {
    const val TAG = "ChromecastSession"

    /** Stock Chromecast receiver soft cap on queue size. */
    const val RECEIVER_QUEUE_LIMIT = 100
  }
}
