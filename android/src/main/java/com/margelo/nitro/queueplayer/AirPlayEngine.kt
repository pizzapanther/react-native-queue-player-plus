package com.margelo.nitro.queueplayer

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.exoplayer.source.MediaSource
import com.margelo.nitro.queueplayer.cast.airplay.AirPlayRenderersFactory
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Dedicated single-ExoPlayer engine for AirPlay (1 + 2) push-PCM streaming.
 *
 * A standalone peer of [GaplessEngine] / [CrossfadeEngine] — intentionally NOT
 * built on top of them. Its audio renderer's [AudioSink] is the AirPlay
 * session's sink (passed in), wired through a bare [AirPlayRenderersFactory]:
 * no forced float output, no ReplayGain, no visualizer. The AirPlay sinks only
 * accept Int16 RAW PCM, and feeding them from the local engine's
 * float/processor pipeline corrupted the stream — so AirPlay gets its own clean
 * engine, built on connect and released on disconnect.
 *
 * One engine per session: the [airplaySink] is bound at ExoPlayer construction
 * and never swapped. [TrackPlayer] swaps this engine in when an AirPlay session
 * activates and swaps back to the user's gapless/crossfade engine on disconnect,
 * carrying queue + index + position + play state across both directions.
 *
 * Threading: constructed + accessed on the service main looper; ExoPlayer
 * dispatches callbacks on the same looper.
 */
@UnstableApi
internal class AirPlayEngine(
  context: android.content.Context,
  airplaySink: AudioSink,
  mediaSourceFactory: MediaSource.Factory? = null,
  audioContentType: AudioContentType? = null,
) : PlaybackEngine {

  override var delegate: PlaybackEngineDelegate? = null

  private val sessionIdListeners = CopyOnWriteArrayList<(IntArray) -> Unit>()
  private var lastKnownSessionId: Int = C.AUDIO_SESSION_ID_UNSET

  private val renderersFactory: RenderersFactory =
    AirPlayRenderersFactory(context, airplaySink)

  /**
   * Underlying Media3 player. Module-internal escape hatch so
   * [PlaybackService] can do service-level wiring (audio-focus listener
   * attach, `MediaSession.setPlayer`) without going through the abstract
   * [PlaybackEngine] surface.
   */
  val exoPlayer: ExoPlayer = buildAirPlayExoPlayer(
    context, mediaSourceFactory, audioContentType, renderersFactory,
  )

  override val mediaSessionPlayer: ExoPlayer get() = exoPlayer

  private var released: Boolean = false

  private val playerListener = object : Player.Listener {
    override fun onPlaybackStateChanged(playbackState: Int) {
      // End signal before the generic state callback, as in GaplessEngine.
      if (playbackState == Player.STATE_ENDED) {
        delegate?.onPlaybackEnded(this@AirPlayEngine)
      }
      delegate?.onStateMaybeChanged(this@AirPlayEngine, playWhenReadyReason = null)
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
      delegate?.onStateMaybeChanged(this@AirPlayEngine, playWhenReadyReason = reason)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
      delegate?.onIsPlayingChanged(this@AirPlayEngine, isPlaying)
      delegate?.onStateMaybeChanged(this@AirPlayEngine, playWhenReadyReason = null)
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
      delegate?.onTrackTransition(this@AirPlayEngine, reason)
    }

    override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
      // Surface the active item's format change. No ReplayGain parse — RG is
      // intentionally absent on the AirPlay engine.
      delegate?.onActiveItemFormatChanged(this@AirPlayEngine)
    }

    override fun onPositionDiscontinuity(
      oldPosition: Player.PositionInfo,
      newPosition: Player.PositionInfo,
      reason: Int,
    ) {
      if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
        delegate?.onAutoTransitionDiscontinuity(this@AirPlayEngine)
      }
    }

    override fun onPlayerError(error: PlaybackException) {
      delegate?.onError(this@AirPlayEngine, error)
    }
  }

  private val analyticsListener = object : AnalyticsListener {
    override fun onAudioSessionIdChanged(
      eventTime: AnalyticsListener.EventTime,
      audioSessionId: Int,
    ) {
      lastKnownSessionId = audioSessionId
      val ids = currentAudioSessionIds()
      sessionIdListeners.forEach { it(ids) }
    }
  }

  init {
    exoPlayer.addListener(playerListener)
    exoPlayer.addAnalyticsListener(analyticsListener)
    // Hand the active player reference to the cast sink-router. TrackPlayer
    // reads it back when it swaps this engine in and starts the active session's
    // receiver-display metadata sync (MetadataAwareSession.onActivated) against
    // this engine's ExoPlayer.
    com.margelo.nitro.queueplayer.cast.CastSinkRouter.registerPlayer(exoPlayer)
  }

  // --- PlaybackEngine — lifecycle ---

  override fun release() {
    if (released) return
    released = true
    exoPlayer.removeListener(playerListener)
    exoPlayer.removeAnalyticsListener(analyticsListener)
    sessionIdListeners.clear()
    // Stop the receiver-display metadata sync bound to this engine's player
    // before the player is released. Releasing the AirPlay engine is the single
    // deactivation trigger that covers every teardown — swap back to local,
    // AirPlay->AirPlay rebuild, and service onDestroy — because each releases
    // the active AirPlay engine. On disconnect / receiver death the session is
    // already cleared (so this is a no-op) and its own close() has stopped the
    // sync; this catches the paths that don't route through a session close.
    (com.margelo.nitro.queueplayer.cast.CastSinkRouter.activeSession()
      as? com.margelo.nitro.queueplayer.cast.MetadataAwareSession)?.onDeactivated()
    // Drop the delegate before releasing the player so any callback already
    // dispatched onto the looper cannot fan out to a TrackPlayer that has just
    // swapped to a fresh engine. exoPlayer.release() releases the AirPlay sink
    // (idempotent — the session's close() may have released it already).
    delegate = null
    exoPlayer.release()
  }

  // --- PlaybackEngine — queue mutation ---

  override fun setMediaItems(
    items: List<MediaItem>,
    startIndex: Int,
    startPositionMs: Long,
  ) {
    if (items.isEmpty()) {
      // Draining is not pausing: `playWhenReady` survives an empty playlist,
      // so the next queue seated on this player would start on contact.
      exoPlayer.pause()
    }
    exoPlayer.setMediaItems(items, startIndex, startPositionMs)
    // ExoPlayer is IDLE after construction; prepare() is required before any
    // subsequent play() can take effect (mirrors GaplessEngine).
    exoPlayer.prepare()
  }

  override fun addMediaItems(items: List<MediaItem>, insertBefore: Int) {
    exoPlayer.addMediaItems(insertBefore, items)
  }

  override fun removeMediaItems(indices: IntArray) {
    val sorted = indices.toMutableList().apply { sortDescending() }
    for (i in sorted) exoPlayer.removeMediaItem(i)
  }

  override fun moveMediaItem(fromIndex: Int, toIndex: Int) {
    exoPlayer.moveMediaItem(fromIndex, toIndex)
  }

  // --- PlaybackEngine — transport ---

  override fun play() { exoPlayer.play() }
  override fun pause() { exoPlayer.pause() }
  override fun stop() { exoPlayer.stop() }
  override fun seekTo(index: Int, positionMs: Long) { exoPlayer.seekTo(index, positionMs) }
  override fun skipToNext() { exoPlayer.seekToNextMediaItem() }
  override fun skipToPrevious() { exoPlayer.seekToPreviousMediaItem() }

  // --- PlaybackEngine — config ---

  override fun setVolume(volume: Float) {
    // The AirPlay sink ignores ExoPlayer volume (receiver volume is controlled
    // session-side via RTSP). Forwarded for interface completeness.
    exoPlayer.volume = volume
  }

  override fun setPlaybackSpeed(speed: Float) { exoPlayer.setPlaybackSpeed(speed) }

  override fun setRepeatMode(mode: RepeatMode) {
    exoPlayer.repeatMode = when (mode) {
      RepeatMode.OFF -> Player.REPEAT_MODE_OFF
      RepeatMode.TRACK -> Player.REPEAT_MODE_ONE
      RepeatMode.QUEUE -> Player.REPEAT_MODE_ALL
    }
  }

  override fun setPitchCorrectionMode(mode: PitchCorrectionMode) {
    // No-op: AirPlay streams to a remote receiver that owns rendering.
    // Pitch-correction is a local-engine concern.
  }

  override fun setWakeMode(mode: Int) {
    exoPlayer.setWakeMode(mode)
  }

  // --- PlaybackEngine — state ---

  override val currentMediaItemIndex: Int
    get() {
      val count = exoPlayer.mediaItemCount
      if (count == 0) return -1
      return exoPlayer.currentMediaItemIndex
    }

  override val currentPositionMs: Long get() = exoPlayer.currentPosition

  override val currentDurationMs: Long
    get() {
      val d = exoPlayer.duration
      return if (d == C.TIME_UNSET) 0L else d
    }

  override val bufferedPositionMs: Long get() = exoPlayer.bufferedPosition
  override val isPlaying: Boolean get() = exoPlayer.isPlaying
  override val currentMediaItem: MediaItem? get() = exoPlayer.currentMediaItem
  override val isCrossfadeActive: Boolean get() = false

  override val allMediaItems: List<MediaItem>
    get() {
      val count = exoPlayer.mediaItemCount
      if (count == 0) return emptyList()
      val out = ArrayList<MediaItem>(count)
      for (i in 0 until count) out.add(exoPlayer.getMediaItemAt(i))
      return out
    }

  // --- PlaybackEngine — effect chain ---

  override fun currentAudioSessionIds(): IntArray =
    if (lastKnownSessionId == C.AUDIO_SESSION_ID_UNSET) IntArray(0)
    else intArrayOf(lastKnownSessionId)

  override fun addAudioSessionIdListener(listener: (IntArray) -> Unit): () -> Unit {
    sessionIdListeners.add(listener)
    listener(currentAudioSessionIds())
    return { sessionIdListeners.remove(listener) }
  }

  // No visualizer on the AirPlay engine — the receiver renders audio, not us.
  override fun attachAudioBufferSink(sink: TeeAudioProcessor.AudioBufferSink) { /* no-op */ }
  override fun detachAudioBufferSink(sink: TeeAudioProcessor.AudioBufferSink) { /* no-op */ }

  // ReplayGain absent on the AirPlay engine — interface defaults
  // (currentReplayGainData() = null, currentReplayGainActive() = false) apply.
}

/**
 * Build the AirPlay engine's ExoPlayer. Mirrors the gapless engine's standard
 * configuration (audio focus, becoming-noisy, network wake) but with the bare
 * [AirPlayRenderersFactory] supplied by the caller.
 */
@UnstableApi
private fun buildAirPlayExoPlayer(
  context: android.content.Context,
  mediaSourceFactory: MediaSource.Factory?,
  audioContentType: AudioContentType?,
  renderersFactory: RenderersFactory,
): ExoPlayer {
  val builder = ExoPlayer.Builder(context)
  builder.setRenderersFactory(renderersFactory)
  if (mediaSourceFactory != null) {
    builder.setMediaSourceFactory(mediaSourceFactory)
  }
  val player = builder.build()
  player.pauseAtEndOfMediaItems = false
  val contentType = if (audioContentType == AudioContentType.SPEECH) {
    C.AUDIO_CONTENT_TYPE_SPEECH
  } else {
    C.AUDIO_CONTENT_TYPE_MUSIC
  }
  val audioAttributes = AudioAttributes.Builder()
    .setUsage(C.USAGE_MEDIA)
    .setContentType(contentType)
    .build()
  player.setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
  player.setHandleAudioBecomingNoisy(true)
  player.setWakeMode(C.WAKE_MODE_NETWORK)
  return player
}
