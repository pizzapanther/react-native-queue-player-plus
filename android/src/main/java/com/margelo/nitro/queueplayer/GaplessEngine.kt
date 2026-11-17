package com.margelo.nitro.queueplayer

import android.content.Context
import androidx.annotation.MainThread
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.exoplayer.source.MediaSource
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Single-ExoPlayer engine — native gapless via Media3 item chaining.
 *
 * Owns the ExoPlayer, wires `Player.Listener` + `AnalyticsListener`
 * internally, translates Media3 callbacks into [PlaybackEngineDelegate]
 * fires.
 *
 * Threading: constructed + accessed on the service main looper. The
 * underlying ExoPlayer dispatches its callbacks on the same looper
 * (Media3 contract); delegate fires inherit that thread.
 */
@UnstableApi
internal class GaplessEngine(
  context: Context,
  mediaSourceFactory: MediaSource.Factory? = null,
  audioContentType: AudioContentType? = null,
) : PlaybackEngine {

  override var delegate: PlaybackEngineDelegate? = null

  // Persisted pitch-correction mode; drives the PlaybackParameters pitch
  // field on every speed change. Default voice (pitch preserved).
  private var pitchCorrectionMode: PitchCorrectionMode = PitchCorrectionMode.VOICE

  private val sessionIdListeners = CopyOnWriteArrayList<(IntArray) -> Unit>()
  private var lastKnownSessionId: Int = C.AUDIO_SESSION_ID_UNSET

  /**
   * AudioBufferSinks accumulated via [attachAudioBufferSink].
   * Wrapped by a [MultiplexingAudioBufferSink] which sits inside the
   * always-on [TeeAudioProcessor] installed by [visualizerTeeBuilder].
   * When the list is empty, the Tee fan-out is a no-op and audio
   * passes through with negligible overhead.
   */
  private val bufferSinks = CopyOnWriteArrayList<TeeAudioProcessor.AudioBufferSink>()

  /**
   * Visualizer Tee builder constructed only when
   * `VisualizationGate.isEnabled()` is true at engine construction. The
   * builder owns the per-leg [MultiplexingAudioBufferSink] +
   * [TeeAudioProcessor] pair and the late-attach prime registry. When
   * viz is disabled this stays `null` so no Tee, no mux, no
   * `RadixTwoFFT` allocation is taken on. EQ continues to work either
   * way (`audiofx.Equalizer` attaches against `audioSessionId`,
   * independent of this chain).
   */
  private val visualizerTeeBuilder: VisualizerTeeBuilder? =
    if (VisualizationGate.isEnabled()) VisualizerTeeBuilder(bufferSinks) else null

  /**
   * Per-engine ReplayGain audio processor. Always present in the
   * pipeline; the processor's own [ReplayGainAudioProcessor.isActive]
   * gates only on encoding, so a gain pushed mid-stream reaches the
   * audio without a sink reconfigure. The
   * [Player.Listener.onTracksChanged] listener reads container-level
   * tags out of the active audio track's
   * `Format.metadata` and pushes a fresh gain;
   * [Player.Listener.onMediaItemTransition] resets to unity until
   * the next track's tracks become available. The
   * [Player.Listener.onMetadata] path stays wired for in-stream
   * metadata samples (HLS in-stream events, ICY shoutcast) — those
   * fire through the `MetadataRenderer` rather than the static
   * `Format.metadata` channel.
   */
  private val replayGainProcessor = ReplayGainAudioProcessor()

  /**
   * Cached [ReplayGainData] for the active item. Populated by
   * [Player.Listener.onTracksChanged] (container metadata via
   * `Format.metadata`) or [Player.Listener.onMetadata] (in-stream
   * metadata samples); cleared by [Player.Listener.onMediaItemTransition].
   * Read by [recomputeReplayGainFromConfig] when the user flips mode
   * mid-track and by the NowPlayingFormat surfacing in [TrackPlayer].
   *
   * ReplayGain recompute paths (listener parse → cache + push,
   * JS-driven mode flip → cache snapshot + push) all run on the
   * application main thread (Media3 dispatches `Player.Listener`
   * callbacks on the application looper; [TrackPlayer.setReplayGainMode]
   * hops to main before invoking [recomputeReplayGainFromConfig]). The
   * @Volatile field + @Volatile gain on [ReplayGainAudioProcessor] are
   * sufficient because there's only one writer for this cache + that
   * processor at any time.
   */
  @Volatile
  private var cachedReplayGainData: ReplayGainData? = null

  /**
   * Mode-gated pitch-correction chain (RG + viz Tee + Sonic + Signalsmith).
   * Held so [setPitchCorrectionMode] can flip the active stretcher in place
   * without rebuilding the sink.
   */
  private val pitchChain: PitchAwareAudioProcessorChain = run {
    // Order: RG runs FIRST (pre-viz) so the visualizer + downstream
    // pipeline see post-RG samples — matches "what the user hears".
    val procs = mutableListOf<AudioProcessor>(replayGainProcessor)
    visualizerTeeBuilder?.audioProcessor?.let { procs += it }
    PitchAwareAudioProcessorChain(procs.toTypedArray())
  }

  private val renderersFactory: RenderersFactory =
    AudioPipelineRenderersFactory(context, pitchChain)

  /**
   * Underlying Media3 player. Module-internal escape hatch so
   * [PlaybackService] can do service-level wiring (audio-focus
   * listener attach, `MediaSession.setPlayer`) without going
   * through the abstract [PlaybackEngine] surface.
   */
  val exoPlayer: ExoPlayer = buildExoPlayer(
    context, mediaSourceFactory, audioContentType, renderersFactory,
  )

  override val mediaSessionPlayer: ExoPlayer get() = exoPlayer

  private var released: Boolean = false

  private val playerListener = object : Player.Listener {
    override fun onPlaybackStateChanged(playbackState: Int) {
      // The end signal goes first: the queue-end path emits ENDED with its
      // own reason, and a plain state emit before it would take that slot
      // with SYSTEM and leave the queue-end emit deduplicated.
      if (playbackState == Player.STATE_ENDED) {
        delegate?.onPlaybackEnded(this@GaplessEngine)
      }
      delegate?.onStateMaybeChanged(this@GaplessEngine, playWhenReadyReason = null)
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
      delegate?.onStateMaybeChanged(this@GaplessEngine, playWhenReadyReason = reason)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
      delegate?.onIsPlayingChanged(this@GaplessEngine, isPlaying)
      delegate?.onStateMaybeChanged(this@GaplessEngine, playWhenReadyReason = null)
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
      // Item changed — drop the previous track's cached RG tags and
      // snap the processor back to unity so the first buffer of the
      // new track plays at unity until the next track's
      // `onTracksChanged` (or, for in-stream metadata, `onMetadata`)
      // arrives.
      cachedReplayGainData = null
      replayGainProcessor.setLinearGain(1.0f)
      delegate?.onTrackTransition(this@GaplessEngine, reason)
    }

    override fun onTracksChanged(tracks: Tracks) {
      // Container-level metadata (`Format.metadata` populated by
      // Mp3Extractor / FlacExtractor / Mp4Extractor / OggExtractor)
      // surfaces here, not via `onMetadata` — the latter only fires
      // for in-stream metadata samples (HLS in-stream events, ICY
      // shoutcast) routed through `MetadataRenderer`. `onTracksChanged`
      // fires both at initial track-selection AND on auto-advance
      // when the next item's tracks become available.
      val (format, mimeType) = pickSelectedAudioFormat(tracks)
        ?: run {
          delegate?.onActiveItemFormatChanged(this@GaplessEngine)
          return
        }
      val parsed = ReplayGainExtractor.extract(format.metadata, mimeType)
      handleReplayGainParse(parsed)
      delegate?.onActiveItemFormatChanged(this@GaplessEngine)
    }

    override fun onMetadata(metadata: Metadata) {
      delegate?.onTimedMetadata(this@GaplessEngine, metadata)
      // Defensive path for in-stream metadata samples (rare for the
      // local-file case; primarily HLS in-stream RG tags). The
      // container-level path runs via `onTracksChanged` above.
      val mimeType = currentSampleMimeType()
      val parsed = ReplayGainExtractor.extract(metadata, mimeType)
      handleReplayGainParse(parsed)
    }

    override fun onPositionDiscontinuity(
      oldPosition: Player.PositionInfo,
      newPosition: Player.PositionInfo,
      reason: Int,
    ) {
      if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
        delegate?.onAutoTransitionDiscontinuity(this@GaplessEngine)
      }
    }

    override fun onPlayerError(error: PlaybackException) {
      delegate?.onError(this@GaplessEngine, error, failedIndex = exoPlayer.currentMediaItemIndex)
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
    // Hand the active player reference to the cast sink-router so
    // protocol-side metadata-aware sessions can hook ExoPlayer events
    // when they activate. No-op when no cast session is ever active.
    com.margelo.nitro.queueplayer.cast.CastSinkRouter.registerPlayer(exoPlayer)
  }

  // --- PlaybackEngine — lifecycle ---

  override fun release() {
    if (released) return
    released = true
    exoPlayer.removeListener(playerListener)
    exoPlayer.removeAnalyticsListener(analyticsListener)
    sessionIdListeners.clear()
    bufferSinks.clear()
    // Drop the delegate before releasing the player so any callback
    // already dispatched onto the looper (between removeListener
    // returning and exoPlayer.release running) cannot fan out to a
    // TrackPlayer that has just swapped to a fresh engine. Mirrors
    // the iOS GaplessEngine.release contract.
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
    // ExoPlayer is in STATE_IDLE after construction + after stop().
    // setMediaItems alone doesn't transition out of IDLE — prepare()
    // is required before any subsequent play() can take effect.
    // Without this, mid-play engine swaps (gapless ↔ crossfade)
    // silently leave the new GaplessEngine paused even after the
    // user / boundary-fire path explicitly calls play().
    exoPlayer.prepare()
  }

  override fun addMediaItems(items: List<MediaItem>, insertBefore: Int) {
    exoPlayer.addMediaItems(insertBefore, items)
  }

  override fun removeMediaItems(indices: IntArray) {
    // Sort descending so each removal leaves earlier positions
    // stable. Caller need not pre-sort.
    val sorted = indices.toMutableList().apply { sortDescending() }
    for (i in sorted) exoPlayer.removeMediaItem(i)
  }

  override fun moveMediaItem(fromIndex: Int, toIndex: Int) {
    exoPlayer.moveMediaItem(fromIndex, toIndex)
  }

  // --- PlaybackEngine — transport ---

  override fun play() {
    exoPlayer.play()
  }

  override fun pause() {
    exoPlayer.pause()
  }

  override fun stop() {
    exoPlayer.stop()
  }

  override fun seekTo(index: Int, positionMs: Long) {
    exoPlayer.seekTo(index, positionMs)
  }

  override fun skipToNext() {
    exoPlayer.seekToNextMediaItem()
  }

  override fun skipToPrevious() {
    exoPlayer.seekToPreviousMediaItem()
  }

  // --- PlaybackEngine — config ---

  override fun setVolume(volume: Float) {
    exoPlayer.volume = volume
  }

  override fun setPlaybackSpeed(speed: Float) {
    // Apply the active pitch-correction mode's pitch alongside the speed
    // (none → pitch follows rate; voice/music → pitch preserved).
    exoPlayer.playbackParameters = PitchCorrection.playbackParameters(pitchCorrectionMode, speed)
  }

  override fun setRepeatMode(mode: RepeatMode) {
    exoPlayer.repeatMode = when (mode) {
      RepeatMode.OFF -> Player.REPEAT_MODE_OFF
      RepeatMode.TRACK -> Player.REPEAT_MODE_ONE
      RepeatMode.QUEUE -> Player.REPEAT_MODE_ALL
    }
  }

  override fun setPitchCorrectionMode(mode: PitchCorrectionMode) {
    pitchCorrectionMode = mode
    // Route the chain to the matching stretcher BEFORE re-applying params, so
    // applyPlaybackParameters sends the speed to the active one (music →
    // Signalsmith, none/voice → Sonic).
    pitchChain.setMode(mode)
    // Recompute against the current speed so the pitch field flips
    // immediately without waiting for the next speed change.
    val speed = exoPlayer.playbackParameters.speed
    exoPlayer.playbackParameters = PitchCorrection.playbackParameters(mode, speed)
  }

  override fun setWakeMode(mode: Int) {
    exoPlayer.setWakeMode(mode)
  }

  // --- PlaybackEngine — state ---

  override val currentMediaItemIndex: Int
    get() {
      // ExoPlayer returns 0 on an empty timeline; lib canonical
      // sentinel is -1 (matches TrackPlayer.currentTrackIndex).
      val count = exoPlayer.mediaItemCount
      if (count == 0) return -1
      return exoPlayer.currentMediaItemIndex
    }

  override val currentPositionMs: Long
    get() = exoPlayer.currentPosition

  override val currentDurationMs: Long
    get() {
      val d = exoPlayer.duration
      return if (d == C.TIME_UNSET) 0L else d
    }

  override val bufferedPositionMs: Long
    get() = exoPlayer.bufferedPosition

  override val isPlaying: Boolean
    get() = exoPlayer.isPlaying

  override val currentMediaItem: MediaItem?
    get() = exoPlayer.currentMediaItem

  override val isCrossfadeActive: Boolean
    get() = false

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
    // Auto-fire current state so listener doesn't need to seed
    // separately. Matches the existing skip-capability listener
    // pattern in TrackPlayer.
    listener(currentAudioSessionIds())
    return { sessionIdListeners.remove(listener) }
  }

  override fun attachAudioBufferSink(sink: TeeAudioProcessor.AudioBufferSink) {
    // No Tee to fan out to when viz was disabled at engine
    // construction (PlayerConfig.visualizationEnabled = false).
    val tee = visualizerTeeBuilder ?: return
    bufferSinks.addIfAbsent(sink)
    // Media3 fires `MultiplexingAudioBufferSink.flush(rate, channels,
    // encoding)` exactly once per AudioSink lifetime — typically when
    // `DefaultAudioSink.configure` runs against the first decoded
    // buffer, BEFORE any consumer-side `subscribe` has populated
    // `bufferSinks`. The single-ExoPlayer gapless path has no
    // sink-reprepare event to retrigger that flush, so a sink that
    // attaches after the first flush would otherwise receive
    // `handleBuffer` chunks with no encoding context. Replay the
    // cached flush params via the builder so every produced mux
    // primes the new sink with its own most-recent flush.
    tee.primeNewSink(sink)
  }

  override fun detachAudioBufferSink(sink: TeeAudioProcessor.AudioBufferSink) {
    bufferSinks.remove(sink)
  }

  // --- ReplayGain — engine-internal accessors ---

  /**
   * Resolves the consumer's ReplayGain claim over the queue entry at a
   * given index. Installed by [TrackPlayer] at engine construction and
   * invoked on every read rather than cached: a `setQueue` carrying
   * corrected values then takes effect at the next recompute, with no
   * cache to invalidate at the wrong moment.
   *
   * A `null` return means the consumer supplied nothing for that entry
   * and the file's own tags stand. A non-null result with a `null`
   * [SuppliedReplayGain.data] means the consumer claimed the track and
   * gave nothing usable — a different state, and one the merge has to
   * be able to tell apart.
   */
  internal var suppliedReplayGainResolver: ((Int) -> SuppliedReplayGain?)? = null

  /**
   * The ReplayGain the active item actually plays with: the consumer's
   * claim where there is one, [cachedReplayGainData] otherwise. Every
   * read of ReplayGain on this engine goes through here, so the merge
   * lands before the first value reaches the audio or a listener.
   */
  private fun effectiveReplayGainData(): ReplayGainData? {
    // An empty timeline reports index 0, which would name the first
    // queue entry for a player holding no items at all — so nothing is
    // claimed until there is an item for the index to mean something.
    val supplied = if (exoPlayer.mediaItemCount == 0) null
                   else suppliedReplayGainResolver?.invoke(exoPlayer.currentMediaItemIndex)
    return ReplayGainData.merge(
      suppliedClaimed = supplied != null,
      supplied = supplied?.data,
      detected = cachedReplayGainData,
    )
  }

  /**
   * Recompute the processor's linear gain from [effectiveReplayGainData]
   * + the current [ReplayGainConfig.getMode]. Called from
   * [Player.Listener.onTracksChanged] / [Player.Listener.onMetadata]
   * via [handleReplayGainParse] when fresh tags arrive and from
   * [TrackPlayer.setReplayGainMode] when the user flips mode
   * mid-track. With nothing supplied and nothing cached the processor
   * stays at unity.
   *
   * Main thread only — the listener bodies dispatch on the
   * application looper and [TrackPlayer.setReplayGainMode] hops to
   * main before calling.
   */
  @MainThread
  internal fun recomputeReplayGainFromConfig() {
    val gain = computeLinearGain(effectiveReplayGainData(), ReplayGainConfig.getMode())
    replayGainProcessor.setLinearGain(gain)
  }

  /**
   * Cache + recompute entry point shared by both listener paths.
   * Called with the `ReplayGainData` produced by [ReplayGainExtractor]
   * from either `Format.metadata` (container-level via
   * `onTracksChanged`) or in-stream `Metadata` samples (via
   * `onMetadata`). A `null` parse leaves the cache untouched —
   * `onTracksChanged` fires for unrelated track-set changes too,
   * and `onMetadata` fires for non-RG frames (TIT2 title events,
   * ICY now-playing, etc.); wiping cached tags on a null parse
   * would dump valid track-load RG data.
   *
   * Main thread only — Media3 dispatches `Player.Listener`
   * callbacks on the application looper.
   */
  @MainThread
  internal fun handleReplayGainParse(parsed: ReplayGainData?) {
    if (parsed != null) {
      cachedReplayGainData = parsed
    }
    recomputeReplayGainFromConfig()
  }

  /**
   * Snapshot the ReplayGain the active item plays with, for the
   * [NowPlayingFormatExtractor] surface. Returns `null` when nothing is
   * supplied for the current track and no usable RG tags have been
   * parsed for it either.
   */
  override fun currentReplayGainData(): ReplayGainData? = effectiveReplayGainData()

  /**
   * `true` when the active item's ReplayGain carries a tag for the
   * active mode (i.e. the gain push lands at non-unity). Used by the
   * NowPlayingFormat path to populate `replayGainActive` without
   * recomputing the linear gain itself.
   */
  override fun currentReplayGainActive(): Boolean {
    val data = effectiveReplayGainData() ?: return false
    val (gainDb, _) = data.selectGain(ReplayGainConfig.getMode())
    return gainDb != null
  }

  /**
   * Pick the first selected audio [androidx.media3.common.Format]
   * out of [tracks]. Used by [Player.Listener.onTracksChanged] to
   * route `Format.metadata` + `Format.sampleMimeType` into
   * [ReplayGainExtractor.extract] directly from the listener's live
   * payload. Falls back to the first audio track in any group when
   * nothing is currently selected. Returns `null` when no audio
   * track exists.
   */
  private fun pickSelectedAudioFormat(
    tracks: Tracks
  ): Pair<androidx.media3.common.Format, String?>? {
    var firstAudioFormat: androidx.media3.common.Format? = null
    for (group in tracks.groups) {
      if (group.type != C.TRACK_TYPE_AUDIO) continue
      val mediaGroup = group.mediaTrackGroup
      for (i in 0 until mediaGroup.length) {
        val format = group.getTrackFormat(i)
        if (firstAudioFormat == null) firstAudioFormat = format
        if (group.isTrackSelected(i)) {
          return format to format.sampleMimeType
        }
      }
    }
    val fallback = firstAudioFormat ?: return null
    return fallback to fallback.sampleMimeType
  }

  /**
   * Best-effort sample-MIME-type read for the active item. Walks
   * `currentTracks` and returns the first audio track's
   * `Format.sampleMimeType`. Used by the in-stream
   * [Player.Listener.onMetadata] path (HLS / ICY) to gate the
   * Opus-container guard in [ReplayGainExtractor.extract]. The
   * container-level [Player.Listener.onTracksChanged] path reads
   * the sample MIME directly from the listener's [Tracks] payload
   * via [pickSelectedAudioFormat].
   */
  private fun currentSampleMimeType(): String? {
    for (group in exoPlayer.currentTracks.groups) {
      if (group.type != C.TRACK_TYPE_AUDIO) continue
      val mediaGroup = group.mediaTrackGroup
      for (i in 0 until mediaGroup.length) {
        val format = group.getTrackFormat(i)
        format.sampleMimeType?.let { return it }
      }
    }
    return null
  }
}

/**
 * Build the underlying ExoPlayer with the lib's standard
 * configuration. `mediaSourceFactory == null` produces a
 * default-factory player suitable as a placeholder until the
 * consumer's `PlayerConfig` is applied.
 *
 * `audioContentType == SPEECH` flips the AudioAttributes
 * `contentType` to `C.AUDIO_CONTENT_TYPE_SPEECH`, feeding into
 * Android's audio-routing decisions (notably ducking + nav-voice
 * interruption behaviour). All other values map to MUSIC.
 *
 * `handleAudioFocus = true` lets Media3 drive focus + duck
 * internally — appropriate for the single-player gapless case. A
 * dual-player crossfade engine flips this to `false` and runs a
 * lib-level focus owner instead, since two simultaneously-active
 * Media3 focus requests fight each other.
 */
@UnstableApi
private fun buildExoPlayer(
  context: Context,
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
