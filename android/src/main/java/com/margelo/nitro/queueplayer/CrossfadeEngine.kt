package com.margelo.nitro.queueplayer

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.exoplayer.source.MediaSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Dual-`ExoPlayer` engine implementing equal-power crossfade at
 * track boundaries.
 *
 * Two ExoPlayer instances rotate roles: the **leading** player is
 * the one currently audible to the listener; the **standby**
 * player prerolls the next queue item ahead of the fade window
 * and ramps up from silence as the leading ramps down. At
 * fade-complete the roles swap.
 *
 * Build constraints (per platform research):
 * - Per-player [MediaSource.Factory]: sharing a factory across
 *   two `ExoPlayer` instances is documented as an anti-pattern
 *   (see `androidx.media` issue #9099 + ExoPlayer #3438) — DRM
 *   provider state and `MediaSource.Factory` configuration
 *   couple to a single playback Looper. Each player must own its
 *   own factory; the underlying `SimpleCache` is shared via the
 *   factory's `CacheDataSource.Factory` so cache reads benefit
 *   from prior downloads.
 * - Per-player [LoadControl]: standby uses a smaller buffer
 *   window so it doesn't compete with the active player for
 *   memory. Defaults align with the leading player.
 * - `handleAudioFocus = false` on BOTH legs: two simultaneously-
 *   active Media3 focus requests fight each other (each
 *   `AudioFocusManager$AudioFocusListener` is a distinct
 *   listener instance from AudioFlinger's perspective even
 *   within the same UID — a second request fires
 *   `AUDIOFOCUS_LOSS` to the first). The engine instead owns
 *   one [AudioManager.OnAudioFocusChangeListener] that
 *   coordinates pause/duck across both players.
 *
 * Fade timing:
 * - Position-poll on the leading player at 200ms tick fires
 *   when `currentPosition >= duration - crossfadeDurationMs - 100ms`.
 * - The execute coroutine runs on `Dispatchers.Main` at 20ms tick
 *   computing equal-power gains (`cos(t·π/2)` outgoing,
 *   `sin(t·π/2)` incoming) — `Choreographer.FrameCallback` is
 *   unsuitable for audio-only playback (VSYNC pauses when no
 *   Surface is rendering, which would freeze the ramp).
 *
 * Edge cases handled:
 * - Manual skip / pause / seek mid-fade: cancel the execute job,
 *   snap leading to volume 1.0, release standby's item.
 * - Active player ends before fade completes (duration metadata
 *   wrong / network truncation): `STATE_ENDED` listener cancels
 *   the poll, snaps standby to volume 1.0, swaps roles
 *   immediately so playback continues.
 * - Auto-clamp `crossfadeDurationMs` to `min(configured,
 *   leadingDuration/2, standbyDuration/2)` at fade-arm time so
 *   short tracks never start a fade that can't complete.
 */
@UnstableApi
@RequiresApi(Build.VERSION_CODES.O)  // AudioFocusRequest is API 26+
internal class CrossfadeEngine(
  private val context: Context,
  /**
   * Provider invoked once per leg at construction so each
   * `ExoPlayer` instance receives its own `MediaSource.Factory`
   * (sharing factories across players is a documented Media3
   * anti-pattern — see `androidx.media #9099`). The provider may
   * close over a shared `SimpleCache` so cache hits are reused
   * across legs, which is the safe + recommended composition.
   */
  private val mediaSourceFactoryProvider: (() -> MediaSource.Factory)? = null,
  private val audioContentType: AudioContentType? = null,
  private val crossfadeDurationMs: Long,
) : PlaybackEngine {

  init {
    require(crossfadeDurationMs >= 0L) { "crossfadeDurationMs must be non-negative" }
    // Defence-in-depth: a remote PcmStream sink (AirPlay 1/2) can only
    // accept input from ONE ExoPlayer at a time. CrossfadeEngine drives
    // two players, so AirPlay streams through the dedicated single-player
    // AirPlayEngine instead while a push-PCM route is active — the engine
    // selector never builds a CrossfadeEngine then. This `check` catches a
    // regression in that gating loud.
    check(!com.margelo.nitro.queueplayer.cast.CastSinkRouter.isRemoteActive()) {
      "CrossfadeEngine instantiated while a cast push-PCM session is active; " +
        "the dedicated AirPlayEngine handles push-PCM routes."
    }
  }

  override var delegate: PlaybackEngineDelegate? = null

  // --- Visualizer audio chain (declared before players so buildPlayer
  // can reference them; the engine owns the always-on Tee and exposes
  // attach via the bufferSinks accumulator) ---

  private val bufferSinks = CopyOnWriteArrayList<TeeAudioProcessor.AudioBufferSink>()

  /**
   * One [VisualizerTeeBuilder] per ExoPlayer leg — sharing a single
   * Tee across two AudioSinks would interleave its `flush` +
   * `handleBuffer` state. Both legs feed the same shared
   * [bufferSinks] accumulator so a visualizer subscriber sees PCM
   * from whichever leg is currently audible. Null when
   * `VisualizationGate.isEnabled()` is false at construction time.
   * See [GaplessEngine.visualizerTeeBuilder] for the orthogonality
   * rationale with EQ.
   */
  private val visualizerTeeBuilderA: VisualizerTeeBuilder? =
    if (VisualizationGate.isEnabled()) VisualizerTeeBuilder(bufferSinks) else null
  private val visualizerTeeBuilderB: VisualizerTeeBuilder? =
    if (VisualizationGate.isEnabled()) VisualizerTeeBuilder(bufferSinks) else null

  /**
   * One [ReplayGainAudioProcessor] per leg — each leg's
   * [Player.Listener.onMetadata] pushes ONLY to its own processor so
   * mid-fade tag arrival on the standby leg cannot stomp the leading
   * leg's gain. The crossfade volume ramp (player.volume) is a
   * separate layer; RG composes multiplicatively underneath.
   */
  private val replayGainProcessorA = ReplayGainAudioProcessor()
  private val replayGainProcessorB = ReplayGainAudioProcessor()

  /**
   * Per-leg cached [ReplayGainData]. Mirror the iOS per-item
   * `ItemRGState` cache: each leg owns its own data + processor +
   * extraction lifecycle.
   *
   * ReplayGain recompute paths (per-leg listener parse → cache +
   * push, JS-driven mode flip → both legs' cache snapshot + push)
   * all run on the application main thread (Media3 dispatches
   * `Player.Listener` callbacks on the application looper;
   * [TrackPlayer.setReplayGainMode] hops to main before invoking
   * [recomputeReplayGainFromConfig]). The @Volatile fields +
   * @Volatile gain on each [ReplayGainAudioProcessor] are sufficient
   * because there's only one writer per leg's cache + processor at
   * any time.
   */
  @Volatile
  private var cachedReplayGainDataA: ReplayGainData? = null
  @Volatile
  private var cachedReplayGainDataB: ReplayGainData? = null

  // One mode-gated pitch-correction chain PER LEG — each holds its own
  // (stateful) music time-stretcher, mirroring the per-leg RG + viz Tee rule.
  private val pitchChainA: PitchAwareAudioProcessorChain = run {
    // Order: RG runs FIRST (pre-viz) so the visualizer + downstream
    // pipeline see post-RG samples — matches "what the user hears".
    val procs = mutableListOf<AudioProcessor>(replayGainProcessorA)
    visualizerTeeBuilderA?.audioProcessor?.let { procs += it }
    PitchAwareAudioProcessorChain(procs.toTypedArray())
  }
  private val pitchChainB: PitchAwareAudioProcessorChain = run {
    val procs = mutableListOf<AudioProcessor>(replayGainProcessorB)
    visualizerTeeBuilderB?.audioProcessor?.let { procs += it }
    PitchAwareAudioProcessorChain(procs.toTypedArray())
  }

  private val renderersFactoryA: RenderersFactory =
    AudioPipelineRenderersFactory(context, pitchChainA)
  private val renderersFactoryB: RenderersFactory =
    AudioPipelineRenderersFactory(context, pitchChainB)

  // --- Players ---

  internal val playerA: ExoPlayer = buildPlayer(isStandby = false, renderersFactory = renderersFactoryA)
  internal val playerB: ExoPlayer = buildPlayer(isStandby = true, renderersFactory = renderersFactoryB)

  /** `true` when [playerA] is the leading (currently audible) leg. */
  @Volatile
  internal var currentRoleIsA: Boolean = true
    private set

  /** Leading leg — the player currently producing audible audio. */
  internal val leadingPlayer: ExoPlayer
    get() = if (currentRoleIsA) playerA else playerB

  /** Standby leg — prerolls the next queue item; volume 0 outside fade window. */
  internal val standbyPlayer: ExoPlayer
    get() = if (currentRoleIsA) playerB else playerA

  /**
   * Service-level wiring binds `MediaSession` + `audioFocusListener`
   * to the leading leg. Mid-fade role swap doesn't update this
   * field; `CrossfadeForwardingPlayer` is the layer that hides
   * the rotation from `MediaSession`.
   */
  override val mediaSessionPlayer: ExoPlayer get() = leadingPlayer

  // --- Listener registries ---

  private val sessionIdListeners = CopyOnWriteArrayList<(IntArray) -> Unit>()

  private var sessionIdA: Int = C.AUDIO_SESSION_ID_UNSET
  private var sessionIdB: Int = C.AUDIO_SESSION_ID_UNSET

  // --- Fade state ---

  private val engineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

  /** Active fade job; null outside fade window. */
  private var fadeJob: Job? = null

  /**
   * Position-poll Job — watches the leading leg's `currentPosition`
   * every 200ms while playback is active and arms the fade when the
   * window approaches. Cancelled on pause / stop / seek / skip /
   * release. Restarted when [play] resumes.
   */
  private var armingJob: Job? = null

  /** True after [armCrossfade] has prerolled standby for the upcoming fade. */
  private var standbyArmed: Boolean = false

  /**
   * User-set volume for the leading leg, preserved across
   * fade-cancel paths so manual `setVolume(v)` survives a
   * subsequent skip / pause / seek that resets the fade.
   * Standby leg is always at 0.0 outside the fade window.
   */
  private var userVolume: Float = 1f

  /** Pitch-correction mode, applied to both legs' PlaybackParameters. */
  private var pitchCorrectionMode: PitchCorrectionMode = PitchCorrectionMode.VOICE

  /** Lib-level audio focus owner (replaces per-player handleAudioFocus = true). */
  private val audioManager: AudioManager =
    context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

  /**
   * Tracks whether the engine was actively playing at the moment
   * of an `AUDIOFOCUS_LOSS_TRANSIENT` so the matching `_GAIN`
   * resumes only when the user hadn't already paused us.
   * `false` when the user paused before focus was lost — in that
   * case the gain is a no-op.
   */
  private var pausedByFocusLoss: Boolean = false

  /**
   * `true` while focus duck is active. The fade tick reads
   * [effectiveUserVolume] so an in-flight ramp respects the duck;
   * outside the fade window, the duck path sets player volumes
   * directly. Cleared on `AUDIOFOCUS_GAIN`.
   */
  private var duckActive: Boolean = false

  /** Pre-duck multiplier baked into the running fade, so the
   *  fade tick + the standalone-duck path agree on the target
   *  volume. */
  private fun effectiveUserVolume(): Float =
    if (duckActive) userVolume * DUCK_MULTIPLIER else userVolume

  /**
   * Set for the duration of the two `pause()` calls a focus loss makes.
   *
   * This engine sets `handleAudioFocus = false` and owns focus itself, so
   * Media3 never stamps a focus pause with its audio-focus reason: `pause()`
   * dispatches `onPlayWhenReadyChanged(false, USER_REQUEST)` inline, and the
   * delegate stamps whatever reason the last callback carried. While this is
   * set the leading listener reports `AUDIO_FOCUS_LOSS` instead — the reason
   * Media3 would have used, which the delegate maps to an interruption — so a
   * phone call does not arrive as "the user pressed pause". Reporting the
   * loss before the pauses does not work: the state is still PLAYING then, so
   * that report is deduplicated and the pause's own reason overwrites it.
   */
  private var pausingForFocusLoss = false

  private fun pauseForFocusLoss() {
    pausingForFocusLoss = true
    try {
      playerA.pause()
      playerB.pause()
    } finally {
      pausingForFocusLoss = false
    }
  }

  /**
   * Threading: AudioManager dispatches the focus callback on the Looper
   * attached to the main thread by default (no Handler passed to the
   * builder), so this lands on the same dispatcher the engine expects for
   * every other call.
   */
  private val focusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
    when (focusChange) {
      AudioManager.AUDIOFOCUS_LOSS -> {
        pausedByFocusLoss = false
        duckActive = false
        // Cancel before pausing. The fade ramp runs on wall-clock time and is
        // not gated on isPlaying, so pausing the legs alone leaves it running
        // through the interruption: it finishes during the call and swaps
        // roles, and playback resumes on the wrong leg with the session
        // pointing at the other one.
        cancelFade()
        pauseForFocusLoss()
      }
      AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
        pausedByFocusLoss = leadingPlayer.isPlaying
        duckActive = false
        cancelFade()
        pauseForFocusLoss()
      }
      AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
        // Notification ping over media etc. — Media3's
        // AudioFocusManager would lower the volume rather than
        // pause; mirror that since we own the policy now.
        // Toggling [duckActive] makes the fade tick honour the
        // duck via [effectiveUserVolume]; for the standalone case
        // (no fade in flight) the player volumes are set directly
        // so the duck takes effect immediately.
        duckActive = true
        if (fadeJob?.isActive != true) {
          leadingPlayer.volume = effectiveUserVolume()
          standbyPlayer.volume = 0f
        }
      }
      AudioManager.AUDIOFOCUS_GAIN -> {
        if (duckActive) {
          duckActive = false
          if (fadeJob?.isActive != true) {
            leadingPlayer.volume = effectiveUserVolume()
            standbyPlayer.volume = 0f
          }
        }
        if (pausedByFocusLoss) {
          pausedByFocusLoss = false
          // Resume via the engine's own [play] so the boundary-arming
          // poll restarts; calling `leadingPlayer.play()` directly
          // would resume audio but leave the next fade boundary
          // un-armed. Audio focus is already granted on this branch
          // so the inner requestAudioFocus is a no-op.
          play()
        }
      }
    }
  }
  private val focusRequest: AudioFocusRequest = AudioFocusRequest
    .Builder(AudioManager.AUDIOFOCUS_GAIN)
    .setAudioAttributes(buildPlatformAudioAttributes())
    .setOnAudioFocusChangeListener(focusListener)
    .setAcceptsDelayedFocusGain(false)
    .build()

  private var released: Boolean = false

  // --- Player listeners ---

  private val leadingPlayerListener = object : Player.Listener {
    override fun onPlaybackStateChanged(playbackState: Int) {
      if (released) return
      if (playbackState == Player.STATE_ENDED) {
        // Leading player ended naturally — if a fade was in flight
        // it didn't complete in time. Cancel the ramp, snap
        // standby to full volume, swap roles immediately so
        // playback continues without a silent gap.
        if (fadeJob?.isActive == true) {
          cancelFadeAndPromoteStandby()
        }
        // End signal before the generic state callback, as in GaplessEngine.
        delegate?.onPlaybackEnded(this@CrossfadeEngine)
      }
      delegate?.onStateMaybeChanged(this@CrossfadeEngine, playWhenReadyReason = null)
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
      if (released) return
      val effectiveReason =
        if (pausingForFocusLoss && !playWhenReady) {
          Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS
        } else {
          reason
        }
      delegate?.onStateMaybeChanged(this@CrossfadeEngine, playWhenReadyReason = effectiveReason)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
      if (released) return
      delegate?.onIsPlayingChanged(this@CrossfadeEngine, isPlaying)
      delegate?.onStateMaybeChanged(this@CrossfadeEngine, playWhenReadyReason = null)
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
      if (released) return
      // The leg moved on: a later visit to the index whose preroll failed is
      // a fresh visit (a repeat-all wrap, a skip back) and arms again.
      armFailedForIndex = -1
      delegate?.onTrackTransition(this@CrossfadeEngine, reason)
    }

    override fun onTracksChanged(tracks: Tracks) {
      if (released) return
      delegate?.onActiveItemFormatChanged(this@CrossfadeEngine)
    }

    override fun onPositionDiscontinuity(
      oldPosition: Player.PositionInfo,
      newPosition: Player.PositionInfo,
      reason: Int,
    ) {
      if (released) return
      if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
        delegate?.onAutoTransitionDiscontinuity(this@CrossfadeEngine)
      }
    }

    override fun onPlayerError(error: PlaybackException) {
      if (released) return
      // A failure mid-fade is the outgoing track failing on its way out, and
      // the incoming one is already prerolled and audible. Promote it, exactly
      // as a leading leg that *ends* mid-fade does above — there is nothing
      // worth retrying about a track that was seconds from being discarded,
      // and rebuilding it would restart it under a ramp that is still running,
      // leaving two legs audible.
      if (fadeJob?.isActive == true) {
        // Logged on the way out: a permanent failure here — a codec or a
        // malformed container — leaves no other trace, and "the audio glitched
        // at a track boundary" is undiagnosable without it.
        Log.w("RNQP.CrossfadeEngine", "leading leg failed mid-fade; promoting standby", error)
        cancelFadeAndPromoteStandby()
        return
      }
      delegate?.onError(
        this@CrossfadeEngine, error, failedIndex = leadingPlayer.currentMediaItemIndex)
    }
  }

  /**
   * Standby leg listener — only forwards format-change signals so
   * a cached prerolled item's audio session ID is observable
   * before fade-start. Track-transition / playback-state events
   * from the standby leg are filtered out: they aren't observable
   * to the listener until the standby promotes to leading.
   */
  private val standbyPlayerListener = object : Player.Listener {
    override fun onTracksChanged(tracks: Tracks) {
      // No delegate fire — the active item is the leading leg's,
      // not the standby's. Standby format becomes relevant only
      // post-promotion, at which point the leading-listener fires.
    }

    override fun onPlayerError(error: PlaybackException) {
      // Standby errors during preroll abort the upcoming fade.
      // Surface as a normal engine error so the consumer can
      // decide whether to skip / retry; the leading leg keeps
      // playing.
      if (released) return
      // Before the cancel: it clears the standby leg's items, and an empty
      // timeline reports the masking index 0 rather than the track that
      // failed — which would spend track 0's retry budget and name its url.
      val failedIndex = standbyPlayer.currentMediaItemIndex
      // The boundary keeps its own disarm marker. `cancelFade` clears
      // `standbyArmed`, so without this the next poll tick re-arms the same
      // failing source and keeps re-prerolling it for the rest of the track.
      armFailedForIndex = leadingPlayer.currentMediaItemIndex
      cancelFade()
      delegate?.onError(this@CrossfadeEngine, error, failedIndex = failedIndex)
    }
  }

  private val analyticsListenerA = makeSessionIdListener(isA = true)
  private val analyticsListenerB = makeSessionIdListener(isA = false)

  private fun makeSessionIdListener(isA: Boolean) = object : AnalyticsListener {
    override fun onAudioSessionIdChanged(
      eventTime: AnalyticsListener.EventTime,
      audioSessionId: Int,
    ) {
      if (isA) sessionIdA = audioSessionId else sessionIdB = audioSessionId
      val ids = currentAudioSessionIds()
      sessionIdListeners.forEach { it(ids) }
    }
  }

  /**
   * Per-leg ReplayGain listener bound to the leg identity (NOT the
   * role). [swapRoles] migrates [leadingPlayerListener] /
   * [standbyPlayerListener] across legs because they are role-shaped
   * (forward-to-delegate vs filter-everything-but-error); the RG
   * listener is leg-shaped and stays attached to its own leg through
   * every role swap. Each leg's `onMetadata` pushes ONLY to its own
   * processor / cache.
   */
  private val replayGainListenerA = makeReplayGainListener(isA = true)
  private val replayGainListenerB = makeReplayGainListener(isA = false)

  private fun makeReplayGainListener(isA: Boolean) = object : Player.Listener {
    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
      if (isA) {
        cachedReplayGainDataA = null
        replayGainProcessorA.setLinearGain(1.0f)
      } else {
        cachedReplayGainDataB = null
        replayGainProcessorB.setLinearGain(1.0f)
      }
    }

    override fun onTracksChanged(tracks: Tracks) {
      // Container-level metadata (`Format.metadata` populated by
      // Mp3Extractor / FlacExtractor / Mp4Extractor / OggExtractor)
      // surfaces here, not via `onMetadata` — the latter only fires
      // for in-stream metadata samples (HLS in-stream events, ICY
      // shoutcast) routed through `MetadataRenderer`. `onTracksChanged`
      // fires both at initial track-selection AND on auto-advance.
      val (format, mimeType) = pickSelectedAudioFormat(tracks) ?: return
      val parsed = ReplayGainExtractor.extract(format.metadata, mimeType)
      handleReplayGainParse(isA = isA, parsed = parsed)
      // Re-signal the format change AFTER the RG parse. The same leg's
      // leadingPlayerListener.onTracksChanged already fired
      // onActiveItemFormatChanged (it is registered first), but with an
      // empty RG cache — so the now-playing snapshot would carry null
      // replayGain*Db. Re-fire so it picks up the freshly-parsed tags.
      // Mirrors GaplessEngine.onTracksChanged, which parses THEN signals
      // the format change within a single listener.
      delegate?.onActiveItemFormatChanged(this@CrossfadeEngine)
    }

    override fun onMetadata(metadata: Metadata) {
      // Defensive path for in-stream metadata samples (HLS in-stream
      // RG tags, ICY shoutcast). The container-level path runs via
      // `onTracksChanged` above.
      val player = if (isA) playerA else playerB
      // The active player switches to the incoming leg at fade-start. Neither
      // standby preroll nor the outgoing leg may publish its timed metadata.
      if (player === activePlayer) {
        delegate?.onTimedMetadata(this@CrossfadeEngine, metadata)
      }
      val mimeType = currentSampleMimeType(player)
      val parsed = ReplayGainExtractor.extract(metadata, mimeType)
      handleReplayGainParse(isA = isA, parsed = parsed)
      delegate?.onActiveItemFormatChanged(this@CrossfadeEngine)
    }
  }

  init {
    playerA.addListener(leadingPlayerListener)
    playerA.addListener(replayGainListenerA)
    playerA.addAnalyticsListener(analyticsListenerA)
    playerB.addListener(standbyPlayerListener)
    playerB.addListener(replayGainListenerB)
    playerB.addAnalyticsListener(analyticsListenerB)
    // Standby starts at volume 0 — it should not contribute to
    // the audible mix until the fade arms.
    playerB.volume = 0f
  }

  // --- PlaybackEngine: lifecycle ---

  override fun release() {
    if (released) return
    released = true
    // Release runs as part of an engine swap or destroy; the new
    // engine has already taken over the canonical active-track
    // state, so a mid-fade revert emit here would surface a
    // spurious onTrackChange to the OUTGOING leg of a fade that
    // the consumer no longer cares about.
    cancelFade(emitRevert = false, rebindWrapper = false)
    stopArmingPoll()
    audioManager.abandonAudioFocusRequest(focusRequest)
    // [leadingPlayerListener] / [standbyPlayerListener] are
    // role-shaped: [swapRoles] migrates them across legs on every
    // fade. Read them off the current role at release-time so the
    // remove call lands on the leg that actually owns each listener
    // — `Player.removeListener` is a no-op against an unattached
    // listener so a stale call wouldn't leak, but the role-shaped
    // form is what the listeners actually wire to.
    leadingPlayer.removeListener(leadingPlayerListener)
    standbyPlayer.removeListener(standbyPlayerListener)
    // RG listeners + analytics listeners are leg-shaped — they stay
    // attached to their construction-time leg through every swap.
    playerA.removeListener(replayGainListenerA)
    playerA.removeAnalyticsListener(analyticsListenerA)
    playerB.removeListener(replayGainListenerB)
    playerB.removeAnalyticsListener(analyticsListenerB)
    sessionIdListeners.clear()
    bufferSinks.clear()
    delegate = null
    engineScope.cancel()
    playerA.release()
    playerB.release()
  }

  // --- PlaybackEngine: queue mutation ---
  //
  // Single-item-per-leg model. The engine's queue is the lib-side
  // canonical model owned by `TrackPlayer`; the engine receives
  // pre-built `MediaItem` snapshots and either installs them on
  // the leading leg directly (gapless-equivalent path while no
  // fade is in flight) or queues them for the standby preroll.

  override fun setMediaItems(
    items: List<MediaItem>,
    startIndex: Int,
    startPositionMs: Long,
  ) {
    // emitRevert=false: the caller (setQueue / engine swap) sets the
    // canonical active index itself, so a mid-fade revert here would
    // clobber it with the pre-mutation leading index + emit a spurious
    // crossfade-cancelled track-change.
    cancelFade(emitRevert = false)
    // Reset arming state — the previous queue's preroll-armed
    // standby is stale once the queue is replaced. Without this,
    // [standbyArmed] could remain true against an empty / unrelated
    // queue and [maybeArmCrossfade] would early-return forever.
    standbyArmed = false
    armFailedForIndex = -1
    leadingPlayer.setMediaItems(items, startIndex, startPositionMs)
    leadingPlayer.prepare()
    standbyPlayer.stop()
    standbyPlayer.clearMediaItems()
    standbyPlayer.volume = 0f
    // Empty queue — no boundaries to arm. Stop the poll until
    // play() restarts it, and park the leg: draining is not pausing, so
    // `playWhenReady` would otherwise carry into the next queue seated here.
    if (items.isEmpty()) {
      stopArmingPoll()
      leadingPlayer.pause()
    }
  }

  override fun settlePendingFade() {
    // `emitRevert = true`: the caller keeps playing what the leading leg holds
    // rather than seating a new active track, so without the revert the audio
    // returns to the outgoing track while the canonical index still names the
    // incoming one, and no track change follows to correct it.
    cancelFade(emitRevert = true)
  }

  override fun settlePendingFadeByPromoting() {
    if (fadeJob?.isActive == true) cancelFadeAndPromoteStandby()
  }

  override fun addMediaItems(items: List<MediaItem>, insertBefore: Int) {
    // Queue mutation invalidates the standby leg's pre-loaded
    // future. Cancel any in-flight fade before mutating the
    // leading queue so the standby's stale mediaItems don't race
    // a swapRoles against the post-mutation index space.
    // emitRevert=false: the TrackPlayer mutation body recomputes the
    // canonical index, so a mid-fade revert here would clobber it.
    cancelFade(emitRevert = false)
    // The failed-preroll marker is an index; after a mutation it may name a
    // different track.
    armFailedForIndex = -1
    leadingPlayer.addMediaItems(insertBefore, items)
  }

  override fun removeMediaItems(indices: IntArray) {
    cancelFade(emitRevert = false)
    armFailedForIndex = -1
    val sorted = indices.toMutableList().apply { sortDescending() }
    for (i in sorted) leadingPlayer.removeMediaItem(i)
  }

  override fun moveMediaItem(fromIndex: Int, toIndex: Int) {
    cancelFade(emitRevert = false)
    armFailedForIndex = -1
    leadingPlayer.moveMediaItem(fromIndex, toIndex)
  }

  // --- PlaybackEngine: transport ---

  override fun play() {
    // Request audio focus so the focusListener gets duck / loss
    // callbacks for as long as we're playing, but do NOT gate the
    // actual play on the grant result. AudioManager can briefly
    // return DENIED right after a sibling engine release abandons
    // focus (e.g. during a mid-play setPlaybackMode → swap →
    // play sequence) — gating would silently leave the user
    // paused. Media3's AudioFocusManager (used by GaplessEngine)
    // takes the same approach: focus is best-effort, playback
    // proceeds.
    audioManager.requestAudioFocus(focusRequest)
    leadingPlayer.play()
    startArmingPoll()
  }

  override fun pause() {
    cancelFade()
    stopArmingPoll()
    leadingPlayer.pause()
    standbyPlayer.pause()
  }

  override fun stop() {
    cancelFade()
    stopArmingPoll()
    leadingPlayer.stop()
    standbyPlayer.stop()
    audioManager.abandonAudioFocusRequest(focusRequest)
  }

  override fun seekTo(index: Int, positionMs: Long) {
    // emitRevert=false: every JS-facing seek + skip routes through here
    // with an explicit target index already written to currentTrackIndex
    // by the caller, so a mid-fade revert would clobber it back to the
    // outgoing leg + emit a spurious crossfade-cancelled track-change.
    cancelFade(emitRevert = false)
    leadingPlayer.seekTo(index, positionMs)
    standbyArmed = false
  }

  override fun skipToNext() {
    cancelFade()
    leadingPlayer.seekToNextMediaItem()
    standbyArmed = false
  }

  override fun skipToPrevious() {
    cancelFade()
    leadingPlayer.seekToPreviousMediaItem()
    standbyArmed = false
  }

  // --- PlaybackEngine: config ---

  override fun setVolume(volume: Float) {
    userVolume = volume
    leadingPlayer.volume = volume
  }

  override fun setPlaybackSpeed(speed: Float) {
    // Both legs carry the active pitch-correction mode's pitch; the
    // standby prerolls at the same params so the fade-in is already at
    // the right pitch.
    val params = PitchCorrection.playbackParameters(pitchCorrectionMode, speed)
    leadingPlayer.playbackParameters = params
    standbyPlayer.playbackParameters = params
  }

  override fun setRepeatMode(mode: RepeatMode) {
    val media3Mode = when (mode) {
      RepeatMode.OFF -> Player.REPEAT_MODE_OFF
      RepeatMode.TRACK -> Player.REPEAT_MODE_ONE
      RepeatMode.QUEUE -> Player.REPEAT_MODE_ALL
    }
    leadingPlayer.repeatMode = media3Mode
    standbyPlayer.repeatMode = media3Mode
  }

  override fun setPitchCorrectionMode(mode: PitchCorrectionMode) {
    pitchCorrectionMode = mode
    // Route both legs' chains to the matching stretcher BEFORE re-applying
    // params, so applyPlaybackParameters sends the speed to the active one.
    pitchChainA.setMode(mode)
    pitchChainB.setMode(mode)
    // Recompute against the current speed (both legs share it) so the
    // pitch field flips immediately.
    val speed = leadingPlayer.playbackParameters.speed
    val params = PitchCorrection.playbackParameters(mode, speed)
    leadingPlayer.playbackParameters = params
    standbyPlayer.playbackParameters = params
  }

  override fun setWakeMode(mode: Int) {
    // Both legs share the wake-mode policy: the standby leg also holds a
    // wake/network lock while prerolling + fading in, so it must match
    // the leading leg. Applying only to the leading leg would leave the
    // post-swap leg on a stale (network) wake lock for an all-local queue.
    leadingPlayer.setWakeMode(mode)
    standbyPlayer.setWakeMode(mode)
  }

  // --- PlaybackEngine: state ---

  /**
   * During a crossfade the conceptual "active" track flips to the
   * standby leg the moment the fade begins (matches the JS-facing
   * `onTrackChange` + lock-screen / Now Playing flip the delegate
   * performs in response to `onCrossfadeBegin`). The protocol
   * accessors below honour this: when [fadeJob] is active they
   * read from [standbyPlayer] (the incoming leg) so `useProgress`,
   * seek clamps, preserve-current, and index lookups all see the
   * incoming track. Engine-internal code reaches [leadingPlayer]
   * directly. [isPlaying] continues to read [leadingPlayer] —
   * during a fade the leading leg's `isPlaying` is the audible
   * truth (the standby is also playing but at a fading-in volume).
   */
  private val activePlayer: ExoPlayer
    get() = if (fadeJob?.isActive == true) standbyPlayer else leadingPlayer

  override val currentMediaItemIndex: Int
    get() {
      val count = activePlayer.mediaItemCount
      if (count == 0) return -1
      return activePlayer.currentMediaItemIndex
    }

  override val currentPositionMs: Long
    get() = activePlayer.currentPosition

  override val currentDurationMs: Long
    get() {
      val d = activePlayer.duration
      return if (d == C.TIME_UNSET) 0L else d
    }

  override val bufferedPositionMs: Long
    get() = activePlayer.bufferedPosition

  override val isPlaying: Boolean
    get() = leadingPlayer.isPlaying

  override val currentMediaItem: MediaItem?
    get() = activePlayer.currentMediaItem

  override val isCrossfadeActive: Boolean
    get() = fadeJob?.isActive == true

  override val allMediaItems: List<MediaItem>
    get() {
      val count = leadingPlayer.mediaItemCount
      if (count == 0) return emptyList()
      val out = ArrayList<MediaItem>(count)
      for (i in 0 until count) out.add(leadingPlayer.getMediaItemAt(i))
      return out
    }

  // --- PlaybackEngine: effect chain ---

  override fun currentAudioSessionIds(): IntArray {
    val fadeActive = fadeJob?.isActive == true
    val leading = if (currentRoleIsA) sessionIdA else sessionIdB
    val standby = if (currentRoleIsA) sessionIdB else sessionIdA
    return when {
      fadeActive && leading != C.AUDIO_SESSION_ID_UNSET && standby != C.AUDIO_SESSION_ID_UNSET ->
        intArrayOf(leading, standby)
      leading != C.AUDIO_SESSION_ID_UNSET -> intArrayOf(leading)
      else -> IntArray(0)
    }
  }

  override fun addAudioSessionIdListener(listener: (IntArray) -> Unit): () -> Unit {
    sessionIdListeners.add(listener)
    listener(currentAudioSessionIds())
    return { sessionIdListeners.remove(listener) }
  }

  override fun attachAudioBufferSink(sink: TeeAudioProcessor.AudioBufferSink) {
    // No Tee to fan out to when viz was disabled at engine
    // construction (PlayerConfig.visualizationEnabled = false).
    val teeA = visualizerTeeBuilderA
    val teeB = visualizerTeeBuilderB
    if (teeA == null && teeB == null) return
    // Mirrors `GaplessEngine`: accumulate the sink for the
    // visualizer to consume. Both legs share the same accumulator;
    // each leg's mux fans out independently when its AudioSink
    // produces a buffer.
    bufferSinks.addIfAbsent(sink)
    // Replay the most recent flush params from every produced mux
    // onto the new sink so it has its encoding context the moment
    // it attaches, regardless of which leg has already flushed.
    teeA?.primeNewSink(sink)
    teeB?.primeNewSink(sink)
  }

  override fun detachAudioBufferSink(sink: TeeAudioProcessor.AudioBufferSink) {
    bufferSinks.remove(sink)
  }

  // --- ReplayGain — engine-internal accessors ---

  /**
   * Recompute BOTH legs' processor gain from each leg's cached data
   * + the current [ReplayGainConfig.getMode]. Called from
   * [TrackPlayer.setReplayGainMode] when the user flips mode
   * mid-track. Per-leg recompute on listener fires uses
   * [recomputeLegFromConfig] internally.
   *
   * Main thread only — Media3 dispatches `Player.Listener`
   * callbacks on the application looper and
   * [TrackPlayer.setReplayGainMode] hops to main before calling.
   */
  @MainThread
  internal fun recomputeReplayGainFromConfig() {
    recomputeLegFromConfig(isA = true)
    recomputeLegFromConfig(isA = false)
  }

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
   * The ReplayGain a leg's current item actually plays with: the
   * consumer's claim where there is one, that leg's cached tags
   * otherwise. Every read of ReplayGain on this engine goes through
   * here, so the merge lands before the first value reaches the audio
   * or a listener.
   *
   * Per leg, because each leg holds its own item and its own processor.
   * The index is that leg's own `currentMediaItemIndex`, which is
   * meaningful for both legs: [armCrossfade] loads the FULL queue onto
   * standby rather than the tail, so both legs count in the same global
   * index space as the consumer-supplied queue.
   */
  private fun effectiveReplayGainData(isA: Boolean): ReplayGainData? {
    val player = if (isA) playerA else playerB
    val cached = if (isA) cachedReplayGainDataA else cachedReplayGainDataB
    // An empty timeline reports index 0, which would name the first
    // queue entry for a leg holding no items at all — the standby leg
    // before it is armed. Nothing is claimed until there is an item for
    // the index to mean something.
    val supplied = if (player.mediaItemCount == 0) null
                   else suppliedReplayGainResolver?.invoke(player.currentMediaItemIndex)
    return ReplayGainData.merge(
      suppliedClaimed = supplied != null,
      supplied = supplied?.data,
      detected = cached,
    )
  }

  @MainThread
  private fun recomputeLegFromConfig(isA: Boolean) {
    val mode = ReplayGainConfig.getMode()
    val gain = computeLinearGain(effectiveReplayGainData(isA), mode)
    if (isA) {
      replayGainProcessorA.setLinearGain(gain)
    } else {
      replayGainProcessorB.setLinearGain(gain)
    }
  }

  /**
   * Cache + recompute entry point shared by both per-leg listener
   * paths. Called with the `ReplayGainData` produced by
   * [ReplayGainExtractor] from either `Format.metadata`
   * (container-level via `onTracksChanged`) or in-stream `Metadata`
   * samples (via `onMetadata`). A `null` parse leaves the cache
   * untouched — `onTracksChanged` fires for unrelated track-set
   * changes too, and `onMetadata` fires for non-RG frames (TIT2
   * title events, ICY now-playing, etc.).
   *
   * Per-leg shape: the listener routes to ONLY its own leg's cache
   * + processor (never the other leg's), so a tag arrival on the
   * standby leg cannot stomp the leading leg's gain mid-fade.
   *
   * Main thread only — Media3 dispatches `Player.Listener`
   * callbacks on the application looper.
   */
  @MainThread
  internal fun handleReplayGainParse(isA: Boolean, parsed: ReplayGainData?) {
    if (parsed != null) {
      if (isA) cachedReplayGainDataA = parsed else cachedReplayGainDataB = parsed
    }
    recomputeLegFromConfig(isA)
  }

  /**
   * Snapshot the ReplayGain the LEADING leg's item plays with, for the
   * [NowPlayingFormatExtractor] surface. The active item from JS's
   * point of view is always the leading leg's current item except
   * during the fade window — at which point [activePlayer] flips to
   * standby and this read should follow. Returns `null` when nothing is
   * supplied for the active leg's track and no usable RG tags have been
   * parsed for it either.
   */
  override fun currentReplayGainData(): ReplayGainData? =
    effectiveReplayGainData(isA = activePlayer === playerA)

  /**
   * `true` when the active leg's ReplayGain carries a tag for the
   * current [ReplayGainConfig.getMode]. Used by the NowPlayingFormat
   * path to populate `replayGainActive`.
   */
  override fun currentReplayGainActive(): Boolean {
    val data = currentReplayGainData() ?: return false
    val (gainDb, _) = data.selectGain(ReplayGainConfig.getMode())
    return gainDb != null
  }

  /**
   * Pick the first selected audio [androidx.media3.common.Format]
   * out of [tracks]. Used by the per-leg
   * [Player.Listener.onTracksChanged] to route `Format.metadata` +
   * `Format.sampleMimeType` into [ReplayGainExtractor.extract]
   * directly from the listener's live payload. Falls back to the
   * first audio track in any group when nothing is currently
   * selected. Returns `null` when no audio track exists.
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
   * Best-effort sample-MIME-type read from [player]'s currently-
   * selected audio track. Used by the in-stream
   * [Player.Listener.onMetadata] path (HLS / ICY) to gate the
   * Opus-container guard in [ReplayGainExtractor.extract]. The
   * container-level [Player.Listener.onTracksChanged] path reads
   * the sample MIME directly from the listener's [Tracks] payload
   * via [pickSelectedAudioFormat].
   */
  private fun currentSampleMimeType(player: ExoPlayer): String? {
    for (group in player.currentTracks.groups) {
      if (group.type != C.TRACK_TYPE_AUDIO) continue
      val mediaGroup = group.mediaTrackGroup
      for (i in 0 until mediaGroup.length) {
        val format = group.getTrackFormat(i)
        format.sampleMimeType?.let { return it }
      }
    }
    return null
  }

  // --- Fade orchestration ---

  /**
   * Cancel any in-flight fade and snap the leading leg back to full volume +
   * drop the standby leg's item. Called from manual skip / pause / seek paths
   * and the route-degradation callback. [emitRevert] controls the JS-facing
   * revert only. Re-binding the MediaSession wrapper is not optional and is
   * governed by [rebindWrapper], which only [release] clears — see the note on
   * the revert block below.
   */
  internal fun cancelFade(emitRevert: Boolean = true, rebindWrapper: Boolean = true) {
    val job = fadeJob
    val wasMidFade = job?.isActive == true
    val leadingIdx = leadingPlayer.currentMediaItemIndex
    fadeJob = null
    job?.cancel()
    // Disarm as well as cancel. [standbyArmed] is what stops
    // [maybeArmCrossfade] arming twice for one track, so leaving it set
    // after a cancel means it stays set for the rest of the session: every later
    // arm attempt returns early and each boundary becomes a hard cut, with
    // nothing surfaced and no way back except a seek, skip or new queue.
    // Cancelling before the preroll settles leaves it set with no fade job
    // to clear it, which is the window a pause lands in.
    standbyArmed = false
    leadingPlayer.volume = userVolume
    standbyPlayer.volume = 0f
    standbyPlayer.stop()
    standbyPlayer.clearMediaItems()
    if (wasMidFade && rebindWrapper) {
      // Re-bind first and unconditionally. A mid-fade cancel stops and clears
      // the standby leg, but the fade's start already pointed the session at
      // it, so leaving the binding alone hands the lock screen, notification,
      // AVRCP and Android Auto a player with no items — a blank surface and a
      // dead scrubber while audio continues on the leading leg. This is
      // independent of whether JS wants a revert event.
      onCrossfadeCancel?.invoke()
    }
    if (wasMidFade && emitRevert) {
      // Mid-fade cancel: fade-start fired `onCrossfadeBegin` →
      // delegate moved JS-facing state to the incoming leg. Revert
      // to the leading leg now: re-bind the wrapper back to
      // leadingPlayer so MediaSession metadata reverts; fire the
      // delegate so JS gets a `crossfade-cancelled` track-change
      // for the leading track. `emitRevert: false` is for callers
      // that will immediately set a new active track themselves
      // (e.g. engine release during a setPlaybackMode swap — the
      // new engine has already taken over the canonical active-
      // track state, so an intermediate revert here would surface
      // a spurious onTrackChange to the OUTGOING leg of a fade
      // the consumer no longer cares about).
      delegate?.onCrossfadeCancel(this, leadingIdx)
    }
  }

  /**
   * Cancel the in-flight fade and immediately swap the standby
   * leg to leading at the user's chosen volume. Used when the
   * leading leg ends naturally before the fade completes
   * (truncated track, wrong duration metadata).
   */
  internal fun cancelFadeAndPromoteStandby() {
    val job = fadeJob
    fadeJob = null
    job?.cancel()
    leadingPlayer.volume = 0f
    standbyPlayer.volume = userVolume
    swapRoles()
  }

  /**
   * Compute the effective fade duration for a transition,
   * clamping to half of each leg's playable duration so the
   * ramp never overflows track boundaries.
   */
  internal fun effectiveCrossfadeMs(leadingDurationMs: Long, standbyDurationMs: Long): Long {
    val configured = crossfadeDurationMs
    val leadingHalf = if (leadingDurationMs > 0) leadingDurationMs / 2 else configured
    val standbyHalf = if (standbyDurationMs > 0) standbyDurationMs / 2 else configured
    return min(min(configured, leadingHalf), standbyHalf).coerceAtLeast(0L)
  }

  /**
   * Equal-power gain pair for the given fade progress
   * `t ∈ [0, 1]`. `(outgoing, incoming)` summed power stays at
   * 1.0 across the ramp, avoiding the perceptible -3dB midpoint
   * dip that linear ramps produce on uncorrelated content.
   */
  internal fun equalPowerGains(t: Float): Pair<Float, Float> {
    val clamped = t.coerceIn(0f, 1f)
    val angle = clamped * (PI / 2.0).toFloat()
    return cos(angle) to sin(angle)
  }

  /**
   * Callback fired on every role swap so [CrossfadeForwardingPlayer]
   * (when bound by [PlaybackService.swapEngine]) can invalidate
   * its cached state + re-target `MediaSession.setPlayer` to the
   * new leading leg. Set by `PlaybackService` post-construction;
   * `null` when no wrapper is bound (e.g. headless tests).
   */
  internal var onRoleSwap: (() -> Unit)? = null

  /**
   * Callback fired the instant a crossfade begins ramping. Lets
   * [CrossfadeForwardingPlayer] re-bind `MediaSession` to the
   * standby (incoming) leg so the lock-screen / Auto / BT
   * scrubber + metadata flip to the incoming track from 00:00 the
   * moment it begins audibly playing. The standby's `ExoPlayer`
   * instance becomes the new leading on [swapRoles] at fade-end,
   * so the [onRoleSwap] re-bind a moment later is a no-op (already
   * pointing at the same instance).
   */
  internal var onCrossfadeBegin: (() -> Unit)? = null

  /**
   * Callback fired when an in-flight crossfade is cancelled
   * before [swapRoles] runs. Lets [CrossfadeForwardingPlayer]
   * re-bind `MediaSession` back to the leading leg so the lock-
   * screen / Auto / BT scrubber + metadata revert from the
   * incoming-track view that fade-start had pinned. Pairs with
   * [onCrossfadeBegin]: only fires when a `Begin` was fired
   * earlier and the fade did not complete.
   */
  internal var onCrossfadeCancel: (() -> Unit)? = null

  private fun swapRoles() {
    // Migrate listeners to follow the role swap so the leading-leg
    // listener (which forwards onTrackTransition / onPlaybackStateChanged
    // / onIsPlayingChanged / onPlayerError to the engine delegate)
    // fires from the player that's now leading. Without this the
    // listeners stay attached to playerA / playerB and post-swap events
    // route to the wrong listener, swallowing track transitions on
    // the new leading leg.
    //
    // Threading: this method runs from the runFade coroutine on
    // Dispatchers.Main, which is also the application looper for
    // both ExoPlayer instances. addListener / removeListener +
    // CrossfadeForwardingPlayer.setPlayer (via onRoleSwap) all
    // require the application looper.
    val oldLeading = leadingPlayer
    val oldStandby = standbyPlayer
    oldLeading.removeListener(leadingPlayerListener)
    oldStandby.removeListener(standbyPlayerListener)
    currentRoleIsA = !currentRoleIsA
    leadingPlayer.addListener(leadingPlayerListener)
    standbyPlayer.addListener(standbyPlayerListener)
    val newStandby = standbyPlayer
    newStandby.stop()
    newStandby.clearMediaItems()
    newStandby.volume = 0f
    standbyArmed = false
    // Notify the MediaSession-binding wrapper so the lock-screen
    // scrubber retargets to the now-leading leg.
    onRoleSwap?.invoke()
  }

  /**
   * Boundary-arming position-poll. Runs every
   * [POSITION_POLL_INTERVAL_MS] while playback is active. When the
   * leading leg's `currentPosition` approaches
   * `duration - effectiveCrossfadeMs - PREROLL_LEAD_MS`, prerolls
   * standby with the next item from the leading leg's queue and
   * fires [runFade].
   *
   * Cancellation: `pause` / `stop` / `seekTo` / `skipToNext` /
   * `skipToPrevious` / `release` all stop the poll. `play` restarts
   * it. The poll itself cancels when the engine's coroutine scope
   * is cancelled (in `release`).
   */
  private fun startArmingPoll() {
    if (armingJob?.isActive == true) return
    armingJob = engineScope.launch {
      while (isActive) {
        delay(POSITION_POLL_INTERVAL_MS)
        try {
          maybeArmCrossfade()
        } catch (_: IllegalStateException) {
          // ExoPlayer state read off main looper or post-release;
          // skip this tick. The next tick will re-evaluate.
        }
      }
    }
  }

  private fun stopArmingPoll() {
    val job = armingJob
    job?.cancel()
    armingJob = null
  }

  /**
   * The leading index whose standby preroll failed. Arming is skipped for
   * that boundary so one failing source is not re-prerolled on every poll
   * tick; cleared when the leading leg transitions and on every queue
   * mutation, since the index is only meaningful for this visit to it.
   */
  private var armFailedForIndex: Int = -1

  /**
   * Decide whether to arm the standby preroll + run the fade based
   * on the leading leg's current position relative to its
   * track-end. Idempotent — repeat fires within the same boundary
   * window are no-ops because [standbyArmed] tracks per-track.
   */
  private fun maybeArmCrossfade() {
    if (standbyArmed) return
    if (leadingPlayer.currentMediaItemIndex == armFailedForIndex) return
    if (fadeJob?.isActive == true) return
    if (!leadingPlayer.isPlaying) return
    if (crossfadeDurationMs <= 0L) return
    // Repeat-one loops the current item natively (REPEAT_MODE_ONE on the
    // leading leg). Never arm a look-ahead fade, which would advance past
    // the track the user asked to repeat — matches the gapless engine,
    // where the same native repeat loops the item in place.
    if (leadingPlayer.repeatMode == Player.REPEAT_MODE_ONE) return
    val currentIdx = leadingPlayer.currentMediaItemIndex
    val itemCount = leadingPlayer.mediaItemCount
    if (currentIdx < 0 || currentIdx >= itemCount - 1) return  // last track or empty
    val duration = leadingPlayer.duration
    if (duration == C.TIME_UNSET || duration <= 0L) return
    val position = leadingPlayer.currentPosition
    // Best-effort lookahead: leading's queue has the next item but
    // its duration isn't known until prepared. Passing 0 falls
    // through to the configured value via [effectiveCrossfadeMs]
    // with the leading-half clamp only.
    val standbyDurationMs = 0L
    val effective = effectiveCrossfadeMs(duration, standbyDurationMs)
    val armThreshold = duration - effective - PREROLL_LEAD_MS
    if (position < armThreshold) return
    standbyArmed = true
    armCrossfade(effective)
  }

  /**
   * Preroll the standby leg with the leading leg's tail (next item
   * onward) so Media3 auto-advance can take over after the fade
   * completes + role swap, then start the fade ramp.
   */
  private fun armCrossfade(durationMs: Long) {
    val nextIdx = leadingPlayer.currentMediaItemIndex + 1
    val itemCount = leadingPlayer.mediaItemCount
    if (nextIdx >= itemCount) {
      standbyArmed = false
      return
    }
    // Load the FULL queue onto standby (not just the tail) so the
    // standby's `currentMediaItemIndex` is in the same global index
    // space as the consumer-supplied queue. After [swapRoles] flips
    // standby into leading, JS-facing reads of `currentMediaItemIndex`
    // remain meaningful and the lock-screen / Android Auto scrubber
    // sees the right "track N of M" — loading a tail with startIndex=0
    // collapses the index to 0 every swap.
    val full = ArrayList<MediaItem>(itemCount)
    for (i in 0 until itemCount) full.add(leadingPlayer.getMediaItemAt(i))
    standbyPlayer.setMediaItems(full, /* startIndex = */ nextIdx, /* startPositionMs = */ 0L)
    standbyPlayer.prepare()
    standbyPlayer.volume = 0f
    standbyPlayer.play()
    // Tiny settle so the standby's audio output graph is hot when
    // the volume ramp begins. The N-tick poll catches the case
    // where standby buffering is slow + falls back to STATE_ENDED
    // safety on the leading leg.
    engineScope.launch {
      delay(PREROLL_SETTLE_MS)
      runFade(durationMs)
    }
  }

  /**
   * Schedule the equal-power fade ramp between [leadingPlayer] and
   * [standbyPlayer] over [durationMs] wall-clock. Caller (the
   * boundary-arming poll on `TrackPlayer`) prerolls the standby
   * leg and starts its playback at volume 0 before invoking.
   * Coroutine on `Dispatchers.Main` at 20ms tick; pause naturally
   * halts via `playWhenReady=false` because the leading player's
   * `isPlaying` flips false.
   */
  private fun runFade(durationMs: Long) {
    // Cancel any prior in-flight ramp coroutine. Do NOT call
    // [cancelFade] here — it would also `clearMediaItems()` on the
    // standby leg, destroying the preroll [armCrossfade] just set up
    // and forcing an early-return below.
    fadeJob?.cancel()
    fadeJob = null
    // Standby must already hold the next item — caller prerolls
    // before invoking. Defensive guard: nothing prepared, nothing
    // to swap to. Reset `standbyArmed` so the next [maybeArmCrossfade]
    // tick can re-arm cleanly if a future caller invokes runFade
    // without an intervening [swapRoles].
    if (standbyPlayer.mediaItemCount == 0) {
      standbyArmed = false
      return
    }
    if (durationMs <= 0L) {
      // Hard-cut path — no perceptible fade requested. Snap
      // volumes + swap roles synchronously, preserving the
      // user's volume choice on the new leading leg.
      leadingPlayer.volume = 0f
      standbyPlayer.volume = effectiveUserVolume()
      swapRoles()
      return
    }
    val durationNs = durationMs * 1_000_000L
    val startNs = System.nanoTime()
    val incomingIdx = standbyPlayer.currentMediaItemIndex
    fadeJob = engineScope.launch {
      while (isActive) {
        val elapsedNs = System.nanoTime() - startNs
        val t = (elapsedNs.toFloat() / durationNs.toFloat()).coerceIn(0f, 1f)
        val (outVol, inVol) = equalPowerGains(t)
        // [effectiveUserVolume] honours an in-flight focus duck so
        // the fade ramp doesn't overwrite the duck on the next tick.
        val target = effectiveUserVolume()
        leadingPlayer.volume = outVol * target
        standbyPlayer.volume = inVol * target
        if (t >= 1f) break
        delay(20L)
      }
      // Fade complete — promote standby to leading at the
      // user's chosen (duck-aware) volume; the swap below resets
      // standby volume to 0.
      leadingPlayer.volume = 0f
      standbyPlayer.volume = effectiveUserVolume()
      swapRoles()
      fadeJob = null
    }
    // Active-track flip: standby leg has launched its fade-in (the
    // launch above schedules the coroutine onto Dispatchers.Main —
    // the assignment to `fadeJob` already published the in-flight
    // marker so the protocol accessors retarget to the standby leg
    // for any state read the delegate triggers here). Re-bind the
    // forwarding wrapper to the standby `ExoPlayer` so MediaSession's
    // auto-published metadata + scrubber flip to the incoming
    // track from 00:00 the moment it begins audibly playing. Then
    // notify the delegate to fire JS-facing `onTrackChange`, refresh the
    // now-playing format and recompute skip capability.
    onCrossfadeBegin?.invoke()
    delegate?.onCrossfadeBegin(this, incomingIdx)
  }

  // --- Builders ---

  private fun buildAudioAttributes(): AudioAttributes {
    val contentType = if (audioContentType == AudioContentType.SPEECH) {
      C.AUDIO_CONTENT_TYPE_SPEECH
    } else {
      C.AUDIO_CONTENT_TYPE_MUSIC
    }
    return AudioAttributes.Builder()
      .setUsage(C.USAGE_MEDIA)
      .setContentType(contentType)
      .build()
  }

  /**
   * Platform [android.media.AudioAttributes] that mirrors the
   * Media3 [AudioAttributes] above. The platform type is what
   * [AudioFocusRequest.Builder.setAudioAttributes] consumes;
   * we construct it directly rather than reaching into Media3's
   * deprecated `audioAttributesV21` accessor.
   */
  private fun buildPlatformAudioAttributes(): android.media.AudioAttributes {
    val contentType = if (audioContentType == AudioContentType.SPEECH) {
      android.media.AudioAttributes.CONTENT_TYPE_SPEECH
    } else {
      android.media.AudioAttributes.CONTENT_TYPE_MUSIC
    }
    return android.media.AudioAttributes.Builder()
      .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
      .setContentType(contentType)
      .build()
  }

  private fun buildPlayer(isStandby: Boolean, renderersFactory: RenderersFactory): ExoPlayer {
    val builder = ExoPlayer.Builder(context)
    builder.setRenderersFactory(renderersFactory)
    val factory = mediaSourceFactoryProvider?.invoke()
    if (factory != null) {
      builder.setMediaSourceFactory(factory)
    }
    val loadControl: LoadControl = DefaultLoadControl.Builder()
      .setBufferDurationsMs(
        if (isStandby) STANDBY_MIN_BUFFER_MS else DEFAULT_MIN_BUFFER_MS,
        if (isStandby) STANDBY_MAX_BUFFER_MS else DEFAULT_MAX_BUFFER_MS,
        if (isStandby) STANDBY_BUFFER_FOR_PLAYBACK_MS else DEFAULT_BUFFER_FOR_PLAYBACK_MS,
        if (isStandby) STANDBY_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
        else DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
      )
      .build()
    builder.setLoadControl(loadControl)
    val player = builder.build()
    player.pauseAtEndOfMediaItems = false
    player.setAudioAttributes(buildAudioAttributes(), /* handleAudioFocus = */ false)
    player.setHandleAudioBecomingNoisy(true)
    player.setWakeMode(C.WAKE_MODE_NETWORK)
    return player
  }

  internal companion object {
    // Defaults match Media3 DefaultLoadControl.
    internal const val DEFAULT_MIN_BUFFER_MS: Int = 50_000
    internal const val DEFAULT_MAX_BUFFER_MS: Int = 50_000
    internal const val DEFAULT_BUFFER_FOR_PLAYBACK_MS: Int = 1_000
    internal const val DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS: Int = 2_000

    // Standby leg: keep just enough for a few seconds of preroll
    // so it doesn't compete with the leading leg's lookahead
    // cache for memory headroom.
    internal const val STANDBY_MIN_BUFFER_MS: Int = 15_000
    internal const val STANDBY_MAX_BUFFER_MS: Int = 15_000
    internal const val STANDBY_BUFFER_FOR_PLAYBACK_MS: Int = 2_000
    internal const val STANDBY_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS: Int = 2_000

    // Volume multiplier applied during AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK.
    // Matches Media3's `AudioFocusManager` default duck behaviour.
    internal const val DUCK_MULTIPLIER: Float = 0.2f

    /** Boundary-arming poll cadence. 200ms is a good balance between
     *  responsiveness (we won't miss the arm window even on a 12s
     *  fade) and battery cost. */
    internal const val POSITION_POLL_INTERVAL_MS: Long = 200L

    /** Lead time before fade-start to begin standby preroll. The
     *  AudioSink needs ~50-100ms to warm up; 500ms gives comfortable
     *  margin without the standby being ready way too early. */
    internal const val PREROLL_LEAD_MS: Long = 500L

    /** Settle delay between standby.play() and fade-ramp start.
     *  Lets the AudioSink ready up so the first ramp tick lands on
     *  audible bytes. */
    internal const val PREROLL_SETTLE_MS: Long = 150L
  }
}
