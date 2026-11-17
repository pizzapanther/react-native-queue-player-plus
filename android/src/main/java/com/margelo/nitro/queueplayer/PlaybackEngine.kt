package com.margelo.nitro.queueplayer

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.TeeAudioProcessor

/**
 * Strategy abstraction over the audio output pipeline.
 *
 * Three concrete implementations:
 *  - [GaplessEngine]: single ExoPlayer, native gapless via item chaining.
 *  - [CrossfadeEngine]: dual ExoPlayer with equal-power gain ramping at
 *    track boundaries.
 *  - [AirPlayEngine]: single ExoPlayer bound to an AirPlay PCM sink;
 *    selected by `applyEngineSwapForMode` while an AirPlay session is
 *    active.
 *
 * The engine owns the player instance(s), audio-thread effects, and
 * track-boundary scheduling. The lib's [TrackPlayer] holds the canonical
 * queue model + listener registries + system-side surfaces (NowPlaying,
 * RemoteCommands, AudioSession) and routes transport / queue calls
 * through whichever engine is active. An engine swap (gapless ↔ crossfade)
 * is `TrackPlayer.applyEngineSwapForMode`: [PlaybackService.swapEngine]
 * installs the new engine and releases the old one, then `TrackPlayer`
 * seeds the new engine through [setMediaItems].
 *
 * Threading: all methods are called on the service main looper unless
 * noted; the impl is responsible for any internal off-loop work (e.g.
 * heavy lookahead-cache prefetch).
 */
interface PlaybackEngine {

  /**
   * Release the underlying player(s) and stop all background work.
   * After [release], no further methods are valid.
   */
  fun release()

  // Queue mutation -- engine layer; TrackPlayer mirrors these against
  // its canonical queue model. All of these run synchronously on the
  // service main looper.

  /**
   * Replace the current queue with [items]. Empty list is allowed.
   * Playback resumes at [startIndex] / [startPositionMs] when items
   * are non-empty.
   */
  fun setMediaItems(items: List<MediaItem>, startIndex: Int, startPositionMs: Long)

  /**
   * Insert [items] before the existing item at [insertBefore]. An
   * `insertBefore` equal to the current queue length appends.
   */
  fun addMediaItems(items: List<MediaItem>, insertBefore: Int)

  /**
   * Remove items at [indices]. Indices may be in any order; the impl
   * sorts and removes from highest to lowest so positions before each
   * removal stay stable.
   */
  fun removeMediaItems(indices: IntArray)

  fun moveMediaItem(fromIndex: Int, toIndex: Int)

  /** Update display fields without replacing the media source or resetting playback. */
  fun updateMetadata(index: Int, metadata: androidx.media3.common.MediaMetadata) {
    val player = mediaSessionPlayer
    if (index !in 0 until player.mediaItemCount) return
    player.replaceMediaItem(index, MediaItemBuilder.withDisplayMetadata(player.getMediaItemAt(index), metadata))
  }

  // Transport — synchronous on the service main looper.
  fun play()
  fun pause()
  fun stop()
  fun seekTo(index: Int, positionMs: Long)
  fun skipToNext()
  fun skipToPrevious()

  // Config
  fun setVolume(volume: Float)
  fun setPlaybackSpeed(speed: Float)
  fun setRepeatMode(mode: RepeatMode)
  // Sets how pitch behaves when speed != 1.0 (none/voice/music). Drives
  // the PlaybackParameters pitch field; non-local engines (AirPlay) no-op
  // because the receiver owns rendering.
  fun setPitchCorrectionMode(mode: PitchCorrectionMode)
  // @C.WakeMode — applied to EVERY underlying player the engine owns
  // (both crossfade legs), not just the audible one, so the standby /
  // post-swap leg doesn't leak a network wake lock on an all-local queue.
  fun setWakeMode(mode: Int)

  // State

  /**
   * Current item index within the engine's queue. Returns `-1` when
   * the queue is empty (matches the canonical sentinel in
   * [TrackPlayer]). When non-empty, mirrors `Player.currentMediaItemIndex`.
   */
  val currentMediaItemIndex: Int
  val currentPositionMs: Long
  val currentDurationMs: Long
  val bufferedPositionMs: Long
  val isPlaying: Boolean
  val currentMediaItem: MediaItem?

  /**
   * True while a crossfade volume ramp is in flight. Always `false` for
   * [GaplessEngine]; [CrossfadeEngine] reports its live fade job. Callers
   * that mutate the leading item (e.g. a metadata backfill) skip while this
   * is true so they don't touch the outgoing leg mid-fade.
   */
  val isCrossfadeActive: Boolean

  /**
   * Snapshot of every queued [MediaItem]. Called rarely (engine swap,
   * diagnostic dumps); impl may allocate. The canonical queue model
   * lives on [TrackPlayer]; engines do not duplicate it for hot-path
   * reads.
   */
  val allMediaItems: List<MediaItem>

  /**
   * Underlying ExoPlayer for the audio-focus listener and the
   * `MediaSession` binding. `GaplessEngine` returns its single
   * player, which [PlaybackService] binds directly; `CrossfadeEngine`
   * returns the leading leg's player.
   *
   * A mid-fade role swap inside `CrossfadeEngine` flips which player this
   * getter returns, and nothing re-reads it: [PlaybackService] binds a
   * crossfade engine through a [CrossfadeForwardingPlayer] instead, which
   * re-targets via `setPlayer` on every role swap, fade start and fade
   * cancel, so the session and the focus listener follow the audible leg.
   */
  val mediaSessionPlayer: ExoPlayer

  /**
   * Active audio session IDs.
   *
   * Gapless: 1 entry. Crossfade during the fade window: 2 entries
   * (outgoing + incoming). Empty when the player is yet to allocate
   * its first AudioTrack (`C.AUDIO_SESSION_ID_UNSET`).
   */
  fun currentAudioSessionIds(): IntArray

  /**
   * Subscribe to changes in [currentAudioSessionIds]. Listener fires
   * on the service main looper. Returns a disposer.
   */
  fun addAudioSessionIdListener(listener: (IntArray) -> Unit): () -> Unit

  /**
   * Inject a [TeeAudioProcessor.AudioBufferSink] into the renderer
   * chain. Used by the visualizer pipeline to receive raw PCM via
   * `TeeAudioProcessor`.
   *
   * Multiple [attachAudioBufferSink] calls accumulate; each registered
   * sink receives every PCM buffer until detached. Implementations
   * fan out to ALL active players (1 for gapless, 2 during crossfade)
   * so effects observe both streams during transition.
   */
  @UnstableApi
  fun attachAudioBufferSink(sink: TeeAudioProcessor.AudioBufferSink)

  @UnstableApi
  fun detachAudioBufferSink(sink: TeeAudioProcessor.AudioBufferSink)

  /**
   * Settle any in-flight or armed crossfade so the queue can be mutated by
   * something other than this engine.
   *
   * An armed standby leg holds a snapshot of the queue taken when it was
   * armed. A caller that appends or replaces items through a path the engine
   * does not see — Media3 setting them on the session player after resolving
   * an assistant request — leaves that snapshot stale, and promoting it drops
   * whatever the caller added. Default is a no-op: an engine with one player
   * has nothing to settle.
   */
  fun settlePendingFade() {}

  /**
   * Settle an in-flight fade by promoting the incoming leg, so the leg a
   * caller reads is the track actually being heard.
   *
   * Mid-fade the leading leg holds the outgoing track while the canonical
   * index names the incoming one, so a caller that reads the player and the
   * index together sees two different tracks. Promoting resolves them to the
   * incoming track, which is the one the listener is arriving at. Default is a
   * no-op: an engine with one player is never in this state.
   */
  fun settlePendingFadeByPromoting() {}

  /**
   * Snapshot the active item's cached [ReplayGainData], or `null` when no
   * usable RG tags have been parsed yet. Surfaced by
   * [NowPlayingFormatExtractor] to populate the four `replayGain*Db` /
   * `replayGain*Peak` fields. The default is `null`, for an engine that
   * reads no tags.
   */
  fun currentReplayGainData(): ReplayGainData? = null

  /**
   * `true` when the active item's cached [ReplayGainData] carries
   * a tag for the current [ReplayGainConfig.getMode]. Surfaced by
   * [NowPlayingFormatExtractor] as the `replayGainActive` flag.
   * Default returns `false` so engines without RG wiring compile
   * without a no-op override.
   */
  fun currentReplayGainActive(): Boolean = false

  /**
   * Single delegate receiving state / track / progress / error /
   * queue-end events. TrackPlayer is the canonical delegate; engines
   * fire on the service main looper.
   *
   * Strong reference by design; the iOS counterpart is `weak` because
   * `TrackPlayer` owns its engine strongly and Swift's reference graph
   * would otherwise close (TrackPlayer → engine → delegate → TrackPlayer).
   * On Android the engine lives inside [PlaybackService], reached through
   * [PlaybackService.LocalBinder.engine] and owned by the service
   * lifecycle, not by TrackPlayer; the cycle is broken explicitly by
   * [release] dropping `delegate` and by `TrackPlayer.tearDownEventObservers`
   * clearing itself from the engine's `delegate`.
   *
   * Threading: setter MUST be invoked on the service main looper to
   * avoid a write-vs-fire race during an engine swap.
   */
  var delegate: PlaybackEngineDelegate?
}

/**
 * Engine → TrackPlayer event channel.
 *
 * Delivers raw Media3-flavored signals; the delegate (typically
 * [TrackPlayer]) consults the canonical queue model + lib-internal
 * pending-reason flags to compute the JS-facing event shape. The
 * iOS counterpart fires structurally similar callbacks with
 * AVFoundation-flavored parameters in `PlaybackEngine.swift`.
 */
interface PlaybackEngineDelegate {

  /**
   * Fires when the player's effective state may have changed
   * (`playbackState` / `playWhenReady` / `isPlaying` transitioned).
   * The delegate consults [engine] state, the canonical queue model,
   * and lib-internal pending-reason flags to compute the emitted
   * `PlayerState`.
   *
   * [playWhenReadyReason] carries Media3's
   * `Player.PLAY_WHEN_READY_CHANGE_REASON_*` when the underlying
   * signal was `onPlayWhenReadyChanged`, so the delegate can stamp
   * a typed `StateChangeReason` (audio-focus loss, end-of-media,
   * etc.) before [PlayerState] dedup runs. `null` when the signal
   * came from `onPlaybackStateChanged` or `onIsPlayingChanged` —
   * those paths don't carry a reason and the delegate should
   * preserve any previously-stamped pending reason.
   */
  fun onStateMaybeChanged(engine: PlaybackEngine, playWhenReadyReason: Int? = null)

  /**
   * Fires on Media3 `Player.Listener.onIsPlayingChanged` with the
   * post-change `isPlaying` value. Drives the DEBUG gapless-gap
   * timing log on the playing-leg transition; production builds
   * may treat this as a no-op (the same state is already reflected
   * via [onStateMaybeChanged]).
   */
  fun onIsPlayingChanged(engine: PlaybackEngine, isPlaying: Boolean)

  /**
   * Fires on Media3 `Player.Listener.onMediaItemTransition`.
   * [platformReason] is one of `Player.MEDIA_ITEM_TRANSITION_REASON_*`.
   * The delegate translates against its own state machine
   * (e.g. consuming a pending user-skip reason).
   */
  fun onTrackTransition(engine: PlaybackEngine, platformReason: Int)

  /**
   * Fires when the active item's audio tracks become populated and
   * format details (codec / sample rate / channel count / bitrate)
   * become available. Drives [TrackPlayer.refreshNowPlayingFormatForActiveItem].
   * iOS counterpart fires on `AVPlayerItem.tracks` KVO.
   */
  fun onActiveItemFormatChanged(engine: PlaybackEngine)

  /** In-stream samples from the active player's playback-timed MetadataRenderer. */
  fun onTimedMetadata(engine: PlaybackEngine, metadata: androidx.media3.common.Metadata) {}

  /**
   * Fires on Media3 `Player.Listener.onPlayerError`. The delegate maps
   * [exception] via the lib's `PlaybackErrorMapping`. [failedIndex] is the
   * queue position of the item that failed, or -1 when the engine cannot
   * attribute it. A crossfade engine can fail on its standby leg while the
   * leading leg plays on, so the failure need not belong to the track the
   * delegate considers current.
   */
  fun onError(engine: PlaybackEngine, exception: PlaybackException, failedIndex: Int = -1)

  /**
   * Fires on Media3 `Player.STATE_ENDED`. The delegate consults the
   * canonical queue model + repeat mode to decide whether this is
   * queue-end (fires `queueEndListeners`) or a per-track end that
   * should be ignored.
   */
  fun onPlaybackEnded(engine: PlaybackEngine)

  /**
   * Fires on Media3 `Player.Listener.onPositionDiscontinuity` with
   * reason `DISCONTINUITY_REASON_AUTO_TRANSITION`. Lib-internal
   * diagnostics only — drives gapless-gap timing logs in DEBUG
   * builds.
   */
  fun onAutoTransitionDiscontinuity(engine: PlaybackEngine)

  /**
   * Fires when a crossfade begins ramping — i.e. the standby
   * [ExoPlayer] has started playback at volume 0 and the leading
   * leg's fade-out has begun. The delegate treats this as the
   * canonical "active track changed" trigger and emits
   * `onTrackChange` for the incoming track; the `MediaSession` re-bind
   * to the standby leg is [CrossfadeForwardingPlayer]'s, through the
   * engine's `onCrossfadeBegin` lambda. The subsequent `onTrackTransition`
   * for the same incoming index is a structural Media3 echo and must be
   * deduplicated.
   *
   * Crossfade-only — [GaplessEngine] never invokes this.
   * [incomingIndex] is the global queue index of the fading-in
   * track.
   */
  fun onCrossfadeBegin(engine: PlaybackEngine, incomingIndex: Int)

  /**
   * Fires when an in-flight crossfade is cancelled before
   * `swapRoles` runs (pause / stop / audio-focus loss / queue
   * mutation that invalidates the standby leg). The delegate
   * reverts JS-facing state to the leading leg — emit
   * `onTrackChange(reason: CROSSFADE_CANCELLED)` for the leading
   * track; the `MediaSession` re-bind back to the leading leg is
   * [CrossfadeForwardingPlayer]'s, through the engine's
   * `onCrossfadeCancel` lambda. Pairs with
   * [onCrossfadeBegin]: only fires when a `Begin` was fired
   * earlier and the fade did not complete.
   *
   * Crossfade-only. [revertedIndex] is the leading-leg index
   * that is now the active track.
   */
  fun onCrossfadeCancel(engine: PlaybackEngine, revertedIndex: Int)
}
