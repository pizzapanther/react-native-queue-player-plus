import type { HybridObject } from 'react-native-nitro-modules';
import type {
  TrackItem,
  PlayerConfig,
  PlayerState,
  BufferState,
  PlaybackMode,
  RepeatMode,
  RemoteControlOptions,
  StateChangeReason,
  TrackChangeReason,
  QueueChangeReason,
  QueueChangeDelta,
  TrackSource,
  PlaybackError,
  PlayerProgress,
  SkipCapability,
  SleepTimerState,
  LookaheadCacheConfig,
  CacheStatus,
  MediaSearchRequest,
  BrowseItem,
  BrowseSnapshot,
  ShuffleResult,
  NowPlayingFormat,
  ReplayGainMode,
  PitchCorrectionMode,
  ServiceReadyReason,
  VoiceVocabulary,
} from './types';

// Value types live in src/types.ts. This file holds only the HybridObject
// interface — the Nitrogen-visible surface that generates C++ / Swift /
// Kotlin bindings.

export interface TrackPlayer
  extends HybridObject<{ ios: 'swift'; android: 'kotlin' }> {
  // Lifecycle
  /**
   * Configure the player. **Each call runs an internal `destroy()`
   * before reinitialising.** The lib tears down any existing playback
   * engine, audio session, observers, and pending pipeline state, then
   * constructs a fresh engine with the supplied config. On the FIRST
   * configure of a session the internal destroy is a no-op (nothing
   * exists yet); on subsequent calls it is a real teardown.
   *
   * Effect: configure is always a clean-slate operation. Consumers do
   * not need to call `destroy()` before re-configuring — the lib does
   * it for them.
   *
   * **Recommended usage**: call once at app initialisation in a
   * headless service. Re-configure only on app restart or explicit
   * state-reset flows. Each call clears the queue, drops the playhead
   * to zero, and surfaces a teardown signal to active subscribers; they
   * can resubscribe after the new engine wires up.
   *
   * `destroy()` remains exposed publicly for consumers who want to
   * explicitly tear down without immediately reconfiguring (e.g. on
   * app background → release audio resources).
   */
  configure(config: PlayerConfig): Promise<void>;
  /**
   * Tear down the playback engine, audio session, observers, and any
   * pending pipeline state. Active subscribers receive a teardown
   * signal. Idempotent — safe to call when nothing is currently
   * configured (no-op).
   *
   * Note: `configure()` calls this internally before each
   * reinitialise, so explicit `destroy()` is only needed for shutdown
   * / resource-release flows that do not immediately reconfigure
   * (e.g. app background).
   */
  destroy(): Promise<void>;

  // Queue (flat, app-driven — no internal persistence)
  /**
   * Replace the queue with `tracks`. Optional `startAtIndex` selects
   * which queue entry plays first when transport resumes (defaults to
   * 0). Out-of-range values clamp to `[0, tracks.length - 1]`; empty
   * queue ignores the parameter.
   *
   * `startAtIndex` matches Spotify / Apple Music "tap a song inside an
   * album" UX — push the whole album as the queue but start playback
   * at the tapped track. Skip-next walks through the remaining tracks
   * in their original order.
   */
  setQueue(tracks: TrackItem[], startAtIndex?: number): Promise<void>;
  /**
   * Append (or insert at `insertBefore`) the given tracks to the queue.
   *
   * Non-finite `insertBefore` (NaN / ±Infinity) is dropped on both
   * natives — the call falls through to append-at-end.
   *
   * Cross-platform note: when `setQueue([])` was called immediately
   * before `addToQueue([t])`, iOS leaves the AVQueuePlayer empty until
   * `skipToIndex(0)` is called explicitly; Android calls `prepare()`
   * after the add and starts playback ready immediately. Consumer apps
   * adding tracks to a previously-empty queue should call
   * `skipToIndex(0)` before `play()` for cross-platform consistency.
   */
  addToQueue(tracks: TrackItem[], insertBefore?: number): Promise<void>;
  removeFromQueue(indices: number[]): Promise<void>;
  /**
   * Move a track within the queue.
   *
   * Cross-platform note: moving the currently-playing track to a new
   * position causes an audible restart from 0 on iOS (full
   * AVQueuePlayer rebuild — AVQueuePlayer can't reorder around its
   * play head); Android `moveMediaItem` preserves position. If
   * cross-platform consistency matters more than the iOS rebuild's
   * cost, defer the move until playback ends or the user pauses.
   */
  moveInQueue(fromIndex: number, toIndex: number): Promise<void>;
  /**
   * Resolves with the current queue snapshot.
   *
   * Each platform reads the queue on the thread that owns it (the player's
   * serial queue on iOS, main on Android), so the array is internally
   * consistent. The pair `getQueue()` + `getCurrentTrackIndex()` is still
   * **not** atomic — awaiting the first leaves a gap in which a mutation can
   * land, so the index can name a position in a queue you no longer hold. For
   * UI that needs the pair consistent, subscribe: `onTrackChange` delivers
   * `(track, index)` together, and `onQueueChange` delivers the delta and the
   * post-mutation index together under the same mutation.
   */
  getQueue(): Promise<TrackItem[]>;
  clearQueue(): Promise<void>;

  // Transport
  play(): Promise<void>;
  pause(): Promise<void>;
  stop(): Promise<void>;
  /**
   * Re-attempt the current track, from any state. Rebuilds the item, resumes
   * from where it stopped, then plays, and restores the track's automatic
   * retry budget. Use for a "tap to retry" button: e.g. the user left WiFi
   * coverage so the auto-retries failed, then came back in range and taps
   * retry. While casting, forwards a play to the receiver instead. A no-op
   * only when there is no current track.
   */
  retry(): Promise<void>;
  /** position in seconds from the start of the current track */
  seekTo(position: number): Promise<void>;
  skipToIndex(index: number): Promise<void>;
  skipToNext(): Promise<void>;
  skipToPrevious(): Promise<void>;

  // Playback configuration
  setPlaybackMode(mode: PlaybackMode): Promise<void>;
  /**
   * Read the requested playback mode. Returns whatever was last
   * passed to `setPlaybackMode`, regardless of whether the engine
   * has actually swapped (mid-track switches are deferred to the
   * next track-boundary; the requested mode is the user's intent
   * and what consumer UI should reflect).
   */
  getPlaybackMode(): PlaybackMode;
  /**
   * Set playback speed. Lib clamps to a minimum of 0.25 — passing
   * 0 would pause on iOS (`AVPlayer.rate = 0` ≡ pause) and produce
   * undefined audio behaviour on Android (`PlaybackParameters(0f, …)`
   * is unspecified at the AudioSink). Values < 0.25 are silently
   * clamped to 0.25; consumers wanting to pause must call `pause()`.
   * No upper-bound clamp — native engines apply their own ceilings
   * (typically ~4.0).
   */
  setPlaybackSpeed(rate: number): Promise<void>;
  /**
   * Set how pitch behaves when `playbackSpeed != 1.0`: `none`
   * (pitch follows rate), `voice` (speech-tuned, pitch preserved),
   * or `music` (music-tuned, transient-preserving). Persists until
   * changed and is auto-bypassed at `playbackSpeed == 1.0`. Default
   * `voice`.
   *
   * iOS maps the three modes to native
   * `AVPlayerItem.audioTimePitchAlgorithm`
   * (`.varispeed` / `.timeDomain` / `.spectral`). On Android `none`
   * and `voice` use the built-in Media3 time-stretch; `music`
   * currently uses the same voice-grade algorithm as `voice` (a
   * dedicated music-grade engine is tracked separately).
   */
  setPitchCorrectionMode(mode: PitchCorrectionMode): Promise<void>;
  /** 0.0 .. 1.0 linear */
  setVolume(volume: number): Promise<void>;
  setRepeatMode(mode: RepeatMode): Promise<void>;
  /**
   * Choose how the lock-screen / notification skip controls behave — skip
   * between queue tracks (`'track'`) or jump forward/back within the current
   * track by a fixed interval (`'interval'`) — and set the jump intervals in
   * seconds. Applies immediately, so a consumer can let users pick their
   * preferred controls at runtime. In `'interval'` mode the jump seek is
   * performed natively; the default is `'track'` with 15-second intervals.
   */
  setRemoteControls(options: RemoteControlOptions): Promise<void>;
  /**
   * Arm a native sleep timer that pauses playback after `seconds` of
   * wall-clock time (it counts down whether playing or paused). Playback
   * fades out over the final 10 seconds, then pauses; `onStateChange` fires
   * `paused` with reason `sleep-timer`. Re-arming replaces any existing timer.
   *
   * To avoid cutting a track off near its end, if the deadline lands with 60
   * seconds or less left on the current track, the pause is deferred to the
   * track's natural end (and the fade is undone so the tail plays at full
   * volume).
   */
  setSleepTimer(seconds: number): Promise<void>;
  /**
   * Arm a native sleep timer that pauses playback when the CURRENT track
   * finishes, rather than after a fixed duration. Re-arming replaces any
   * existing timer. (Same 10-second fade + `sleep-timer` reason as
   * `setSleepTimer`, applied over the track's final seconds.)
   */
  setSleepTimerToTrackEnd(): Promise<void>;
  /** Cancel any armed sleep timer and restore the pre-fade volume. */
  clearSleepTimer(): Promise<void>;
  /**
   * Destructive shuffle action. Mutates the current queue: takes a
   * copy of `tracks`, applies a Fisher-Yates shuffle, sets the
   * shuffled order as the new queue, resets `currentTrackIndex` to
   * `0` (or `-1` for an empty queue), and — if state was `'playing'`
   * before the call — resumes playback on the new tracks[0].
   *
   * Returns the post-shuffle queue + new current so the consumer
   * app can immediately reflect the new ordering in its UI without
   * having to re-fetch via `getQueue()` + `getCurrentTrackIndex()`.
   *
   * **No "shuffle off" toggle.** The pre-shuffle ordering is not
   * preserved; once shuffled the queue stays in the new order until
   * the consumer calls `setQueue` to install a fresh ordering. This
   * matches Apple-Music-style "shuffle is an action, not a mode."
   */
  shuffleQueue(): Promise<ShuffleResult>;

  // State snapshots (sync reads, no round-trip cost via Nitro)
  getState(): PlayerState;
  /**
   * Index of the currently-playing track in the queue. Returns `-1`
   * sentinel when the queue is empty OR when no track has been
   * selected yet (post-`setQueue` before the first `skipToIndex` /
   * `play`). Consumers should treat any negative value as "no
   * current track" — the lib does not emit other negative
   * values today, but the `< 0` check is forward-compatible.
   */
  getCurrentTrackIndex(): number;
  /**
   * How the active track is being played: `'local'` (file://), `'cached'`
   * (http(s):// served from the lookahead cache), or `'streaming'`
   * (http(s):// served from the network). `undefined` when there is no
   * active track.
   *
   * Classified at currentItem-change time. A track whose lookahead
   * download completes mid-playback continues to report `'streaming'`
   * until the NEXT track-change re-classifies — the lib does not emit
   * a synthetic source-change event today. Subscribe to `onTrackChange`
   * and re-read this value whenever it fires.
   *
   * Native-owned + cached: this is a sync read of a value the native
   * side computes once per track-change.
   */
  getCurrentTrackSource(): TrackSource | undefined;
  getPlaybackSpeed(): number;
  /** Returns the current pitch-correction mode (default `'voice'`). */
  getPitchCorrectionMode(): PitchCorrectionMode;
  getVolume(): number;
  /** The current remote-control configuration (skip mode + jump intervals). */
  getRemoteControls(): RemoteControlOptions;
  /**
   * Current discrete buffer state for the active track (`'empty'` before any
   * media loads). See `onBufferStateChange` for the semantics and firing
   * behaviour.
   */
  getBufferState(): BufferState;
  /**
   * Whether the active track can be sought. `false` for a genuinely unseekable
   * stream (e.g. a live source, or a variable-bitrate MP3 with no seek index) —
   * a `seekTo` on such a track is a no-op rather than a reset to the start. A UI
   * can grey out its scrubber when this is `false`. `true` when there is no
   * active track (nothing to forbid). Constant-bitrate MP3 / ADTS / AMR are
   * seekable (the lib enables constant-bitrate seeking).
   */
  getIsSeekable(): boolean;
  /**
   * Whether the active track is fully buffered — the entire media has been
   * downloaded and there is nothing left to transfer. Distinct from
   * `BufferState` `'full'` (which is "enough buffered to keep up"): a track can
   * keep up while still downloading. Once fully buffered, a seek can go to the
   * end and the buffered bar reaches 100%. Always `false` for a live / unknown-
   * length stream that never completes. See `onFullyBufferedChange`.
   */
  isFullyBuffered(): boolean;

  /**
   * Whether the user-facing Next button should be enabled. True when
   * there's a next track to skip to under the current repeat mode:
   * - `'off'` and `'track'`: false at the last track (no wrap)
   * - `'queue'`: always true with an active track (wraps)
   *
   * `'track'` (repeat-one) navigates exactly like `'off'`: a user skip
   * advances through the queue and repeat-one persists so the newly
   * current track loops on natural end. Only natural auto-advance
   * replays the current track.
   *
   * Native-owned + cached: this is a sync read of a value the native
   * side recomputes whenever queue, current index, repeat mode, or
   * shuffle changes. Subscribe to `onSkipCapabilityChange` for live
   * updates; the `useCanSkip()` React hook does this for you.
   */
  getCanSkipNext(): boolean;
  /**
   * Whether the user-facing Previous button should be enabled.
   * Symmetric with `getCanSkipNext` — with the default
   * `skipToPreviousBehavior: 'previous'`, true when there's a previous
   * track to skip to under the current repeat mode:
   * - `'off'` and `'track'` at index 0: false (no previous, no wrap)
   * - `'queue'`: true with an active track (wraps)
   *
   * With `PlayerConfig.skipToPreviousBehavior: 'restart-or-previous'` the
   * lib applies the standard music-player "restart when past ~3 s, else
   * previous" model natively, and this reports `true` whenever a track is
   * loaded (even at index 0, where past the threshold Previous restarts the
   * current track) so the control never greys out.
   */
  getCanSkipPrevious(): boolean;
  /**
   * Atomic snapshot of the (canSkipNext, canSkipPrevious) pair.
   * Prefer this over calling `getCanSkipNext()` and
   * `getCanSkipPrevious()` separately when you need a consistent
   * pair: the two scalar getters read independent fields that can
   * be observed mid-update during a concurrent
   * `recomputeCapabilities()` write, surfacing a torn pair. The
   * struct getter reads a single atomically-published reference.
   */
  getSkipCapability(): SkipCapability;
  /** Current sleep-timer state (inactive when no timer is armed). */
  getSleepTimer(): SleepTimerState;

  // Events — each returns a disposer. Disposer invalidates the callback.
  onStateChange(
    callback: (state: PlayerState, reason: StateChangeReason) => void
  ): () => void;
  /**
   * Fires when the active track changes — user skip, auto-advance,
   * queue replace, mid-track seek that crosses a queue boundary.
   *
   * Crossfade contract: under crossfade mode the active track flips
   * to the INCOMING leg the instant the fade-in begins (not when
   * the outgoing leg finishes). The reported `(track, index)` pair
   * is the incoming track from fade-start; `useProgress()` retargets
   * to the incoming leg's clock at the same instant; iOS Now Playing
   * Info Center + Android MediaSession metadata flip in lockstep so
   * the lock-screen / CarPlay / Android Auto / BT scrubber shows
   * the incoming track from 0:00 the moment it begins audibly
   * playing. The outgoing leg continues fading out for the
   * configured `crossfadeDurationMs` after the event fires.
   *
   * `transitionGapMs` is the measured silence gap in milliseconds
   * between the previous track ending and this track's first audio,
   * timed natively (`mach_absolute_time()` on iOS, `System.nanoTime()`
   * on Android). It is populated only on a natural gapless auto-advance
   * (`reason === 'auto-advance'` under the gapless engine); it is
   * `undefined` for user skips, queue replacement, seeks, crossfade
   * transitions, and cast-receiver advances, where an inter-track gap is
   * not meaningful. Always null-check it — an `'auto-advance'` reason
   * alone does not guarantee a value (crossfade + cast advances also use
   * that reason). Empirically
   * `< 1ms` for native gapless auto-advance (AVQueuePlayer / Media3
   * ExoPlayer are gapless by design) — a larger value flags a
   * scheduling regression (a useful CI assertion seed).
   */
  onTrackChange(
    callback: (
      track: TrackItem | undefined,
      index: number,
      reason: TrackChangeReason,
      transitionGapMs: number | undefined
    ) => void
  ): () => void;
  /**
   * Fires when the queue **contents or order** change — `setQueue`,
   * `addToQueue`, `removeFromQueue`, `moveInQueue`, `clearQueue`,
   * `shuffleQueue`. Unlike `onTrackChange` (which fires only when the
   * active *track* changes), this fires on queue-preserving mutations —
   * e.g. inserting a "play next" item or removing an upcoming track — so
   * a reactive queue UI stays in sync.
   *
   * The `(delta, currentIndex)` pair is delivered **atomically**: both are
   * captured on the native side under the same mutation, so they never exhibit
   * the skew of awaiting `getQueue()` and then reading
   * `getCurrentTrackIndex()`. The delta describes the change rather than
   * carrying the whole queue, so a reorder costs two numbers instead of one
   * `TrackItem` per entry — see `QueueChangeDelta`. `currentIndex`
   * is `-1` when the queue is empty; note it can shift without the active
   * track changing (e.g. an insert before the current item), which is the
   * case `onTrackChange` does not cover.
   *
   * Fires only when the queue actually changes — a no-op mutation (empty
   * `addToQueue`, out-of-range `removeFromQueue`, `moveInQueue` with
   * `from === to`) does not fire, and neither do current-index-only
   * changes from auto-advance / skip (those are `onTrackChange`). Does not
   * auto-fire on subscribe; seed initial state from `getQueue()` once, then
   * apply each delta (the `useQueue()` hook does this). For a mutation that also changes the
   * active track (`set-queue` / `clear` / `shuffle`) this fires first,
   * then `onTrackChange` follows.
   */
  onQueueChange(
    callback: (
      delta: QueueChangeDelta,
      currentIndex: number,
      reason: QueueChangeReason
    ) => void
  ): () => void;
  /**
   * Subscribes to playback-progress ticks. Native side fires at ~2 Hz
   * via `addPeriodicTimeObserver` (iOS) / a coroutine-driven handler
   * (Android). Payload is a `PlayerProgress` struct so the callback
   * shape can grow new fields without a breaking change.
   */
  onProgress(callback: (progress: PlayerProgress) => void): () => void;
  /**
   * Fires when forward playback crosses a fixed progress threshold of
   * the current track: `milestone` is one of `25`, `50`, `75`, `90`
   * (percent). The 100% case is end-of-track — observe `onQueueEnd` /
   * `onTrackChange`. `trackIndex` is the queue index the milestone
   * belongs to. Intended for "mark as listened" / scrobbling without
   * the consumer running its own timer over `onProgress`.
   *
   * Semantics:
   * - **Forward playback only.** A milestone fires only when normal
   *   playback reaches the threshold. Seeking *past* a threshold
   *   consumes it silently — seek straight to 80% and only `90` fires
   *   (once playback reaches it); 25/50/75 do not fire that playthrough.
   * - **Once per threshold per playthrough.** A repeat-one loop is a new
   *   playthrough, so the milestones fire again on each loop.
   * - **Requires a computable duration.** If the player reports no/zero
   *   duration AND the track carries no `duration`, percentages can't be
   *   computed and nothing fires.
   *
   * Computed off the existing ~2Hz progress tick, so a milestone is
   * reported within ~one tick of the threshold being crossed.
   */
  onPlaybackMilestone(
    callback: (milestone: number, trackIndex: number) => void
  ): () => void;
  onError(callback: (error: PlaybackError) => void): () => void;
  onQueueEnd(callback: () => void): () => void;
  /**
   * Subscribes to changes in `canSkipNext` / `canSkipPrevious`. The
   * native side recomputes whenever queue, current index, repeat
   * mode, or shuffle changes, and only fires when the
   * `SkipCapability` tuple actually changes.
   *
   * **Auto-fire on subscribe:** the callback is invoked once
   * immediately on registration with the current cached values.
   * This mirrors Media3 `Player.Listener.onAvailableCommandsChanged`
   * and removes the seed-then-subscribe race (you don't need to
   * call `getCanSkipNext` / `getCanSkipPrevious` first).
   *
   * Wrapped in a `SkipCapability` struct (rather than two boolean
   * callback args) so further capability flags can be added without
   * a breaking API change.
   */
  onSkipCapabilityChange(
    callback: (capability: SkipCapability) => void
  ): () => void;
  /**
   * Fires whenever the sleep-timer state changes — armed, cleared, converted
   * from end-of-track to a concrete deadline, or fired. Fires once on subscribe
   * with the current state so a UI can render immediately. Payload is the
   * `SleepTimerState` snapshot (also available synchronously via
   * `getSleepTimer()`).
   */
  onSleepTimerChange(callback: (state: SleepTimerState) => void): () => void;
  /**
   * Fires whenever the current track's discrete `BufferState` changes
   * (`'empty' | 'buffering' | 'stalled' | 'full'`). Driven by the platform's native buffer
   * signals (iOS `AVPlayerItem` KVO on the playback-buffer properties; Android
   * Media3 `STATE_BUFFERING` / `STATE_READY` transitions) rather than the 2 Hz
   * progress tick, so a stall / loading indicator reacts immediately. Only
   * fires on an actual state change (deduplicated natively).
   *
   * Fires once on subscribe with the current state so a UI can render
   * immediately (also available synchronously via `getBufferState()`).
   */
  onBufferStateChange(callback: (state: BufferState) => void): () => void;
  /**
   * Fires when the active track's fully-buffered state changes — `true` once the
   * whole media has finished downloading, `false` on a track change back to a
   * still-loading item. Fires once on subscribe with the current value. See
   * `isFullyBuffered()`.
   */
  onFullyBufferedChange(callback: (fullyBuffered: boolean) => void): () => void;

  // Automotive / voice handler registration — the consuming app registers
  // callbacks that resolve browse / search / play requests coming in from
  // car UIs (CarPlay, Android Auto) and voice assistants (Google Assistant,
  // Siri).

  /**
   * Push the full pre-computed browse tree to the native cache. Consumers
   * call this on bootstrap and on data changes (new playlists, new
   * downloads, library refresh). Native serves CarPlay / Android Auto
   * browse callbacks from this snapshot for instant cold-start
   * presentation; deeper drilldown into items with `hasChildren: true`
   * whose children are NOT inlined in the snapshot falls through to
   * `onBrowseRequest`.
   *
   * Calls notifyChildrenChanged on connected MediaControllers + posts the
   * iOS scene-refresh notification so any active CarPlay scene rebuilds
   * its visible templates.
   */
  setBrowseSnapshot(snapshot: BrowseSnapshot): void;

  /**
   * Drilldown / lazy lookup. Native calls this when a controller asks for
   * children of a parentId NOT covered by the snapshot. Returns the items
   * to display; an empty array means "no children" (treated as a leaf).
   */
  onBrowseRequest(
    callback: (parentId: string) => Promise<BrowseItem[]>
  ): () => void;

  /**
   * Live search for CarPlay's `CPSearchTemplate` (search-as-you-type) and
   * Android Auto's `MediaLibrarySession.Callback.onSearch`. Returns items
   * to display in a list. Tapping an item fires `onPlayFromIdRequest`
   * with that item's `id`.
   */
  onSearchRequest(
    callback: (query: string) => Promise<BrowseItem[]>
  ): () => void;

  /**
   * Voice / structured search resolution. Fires when a voice assistant
   * delivers a structured request:
   * - Android: Google Assistant via
   *   `MediaSession.Callback.onPlayFromSearch`, parsed into
   *   `MediaSearchRequest`.
   * - iOS: SiriKit `INPlayMediaIntent` ("Hey Siri play X on MyApp"),
   *   parsed by Siri into an `INMediaSearch` and mapped by the lib's
   *   `PlayMediaIntentHandler` into `MediaSearchRequest`.
   *
   * Returns the tracks to play. The consumer's JS handler typically
   * calls `setQueue` + `play()` against the resolved list.
   */
  onPlayFromSearchRequest(
    callback: (request: MediaSearchRequest) => Promise<TrackItem[]>
  ): () => void;

  /**
   * Resolves a specific browse-tree item (or search result) to playable
   * tracks. `mediaId` matches the `BrowseItem.id` the consumer chose.
   */
  onPlayFromIdRequest(
    callback: (mediaId: string) => Promise<TrackItem[]>
  ): () => void;

  /**
   * **iOS-only.** Seed Siri's NLU with the consumer's catalog so voice
   * queries like "Play Pearl Jam" resolve to the right strings. Maps
   * to `INVocabulary.setVocabularyStrings(_:of:)` with
   * `.mediaMusicArtistName` for `artists` and `.mediaPlaylistTitle`
   * for `playlists`. Both lists are required (empty array means
   * "nothing to donate"). Apple recommends calling this once per
   * library refresh — not every launch — and the lib dispatches the
   * actual write to a background queue per Apple's guidance.
   *
   * **Inert unless `PlayerConfig.voiceVocabularyDonationEnabled` was `true` at
   * the first `configure()`,** and enabling that requires the
   * `com.apple.developer.siri` entitlement — Xcode's Siri capability — not
   * just `NSSiriUsageDescription` in Info.plist. `INVocabulary` checks for the
   * entitlement at runtime and raises an Objective-C exception without it,
   * which cannot be caught from Swift and terminates the app. iOS gives a
   * process no way to read its own entitlements, so the opt-in is the
   * library's only signal that this is safe to call: leave it `false` unless
   * the Siri capability is on the target.
   *
   * **Android**: no-op. Google Assistant has no in-app vocabulary
   * donation API.
   */
  donateVoiceVocabulary(vocabulary: VoiceVocabulary): void;

  /**
   * Fires when the FIRST automotive controller connects (CarPlay scene
   * attach, Android Auto MediaController bind, or any other connection
   * the lib classifies as automotive). Subsequent connections during the
   * same session do not re-fire. Use this to push a fresh
   * `setBrowseSnapshot` so the controller sees current data.
   */
  onCarConnect(callback: () => void): () => void;

  /**
   * Fires when the LAST automotive controller drops. Use to release any
   * automotive-specific subscriptions the consumer set up on connect.
   */
  onCarDisconnect(callback: () => void): () => void;

  /**
   * Whether an automotive controller is connected right now. The
   * pull-based counterpart to `onCarConnect` / `onCarDisconnect`: it holds
   * the same state those events transition between, so a consumer that
   * missed the connect event — e.g. it fired before the consumer registered
   * its handler in this app process — can still gate car-only work, like
   * pushing browse snapshots, on the current connection.
   *
   * `true` between the first automotive controller connecting and the last
   * one dropping. "Automotive controller" is the same classification the
   * connect/disconnect events use: the CarPlay scene on iOS; the headless
   * Android Auto / Wear / Assistant controllers on Android. Returns `false`
   * before the first connection and after a full disconnect. The state is
   * process-scoped: a fresh app process starts disconnected until a
   * controller connects within it.
   */
  isCarConnected(): boolean;

  /**
   * Fires when the platform's media service is freshly bound to the JS
   * side. `reason = 'first-bind'` for the initial bind during a JS-
   * process lifetime; `reason = 'service-reborn'` when the lib's
   * auto-rebind watchdog reconnects after an OS-driven service kill
   * (Android memory reclaim, idle-bucket reaper). Consumers typically
   * respond by re-pushing the browse snapshot — the new service
   * instance starts with a fresh `BrowseDataProvider`, so the cached
   * snapshot from the prior service instance is gone.
   *
   * **Android-only signal.** iOS apps die fully under memory pressure
   * (no separate service lifecycle to rebirth), so this callback is
   * never invoked on iOS — the registration is accepted for API
   * parity and the returned disposer is a no-op. Consumers writing
   * for both platforms can wire one handler and ignore the
   * platform-iOS branch.
   */
  onServiceReady(
    callback: (reason: ServiceReadyReason) => void
  ): () => void;

  // Lookahead cache — native-managed proactive disk cache.
  /**
   * Reconfigure the lookahead cache (enable / disable, change budget,
   * change directory, change eviction policy). Idempotent — calling
   * with the same config is a no-op.
   *
   * **Cross-platform note on size shrink**: dropping `maxSizeMb`
   * (e.g. 100 → 10) takes effect at different points on each platform.
   * iOS evicts immediately via `LookaheadCache.setMaxSizeBytes`; the
   * over-budget entries are deleted before the promise resolves.
   * Android **does not** resize the live cache — Media3's SimpleCache
   * holds a single instance per directory and cannot resize after
   * construction. The new budget is recorded but the running player
   * keeps honouring the previous one until the cache instance is
   * rebuilt, which only happens on `destroy()` + `configure()`. A
   * bare `configure({ ... })` against the same player+headers is a
   * no-op (the existing ExoPlayer is reused). Consumers who need
   * immediate Android eviction must `await player.destroy()` then
   * `await player.configure({ ... })` to force a rebuild.
   *
   * **Cross-platform note on `allowsCellularAccess`**: live on both,
   * but enforced at different points. iOS sets it on each prefetch
   * request and the OS refuses the request; a download already on
   * the wire finishes under the value it started with. Android has
   * no per-request equivalent, so prefetch stops being scheduled
   * while the default network is cellular, which also cancels the
   * download in flight — its partial data stays cached and resumes
   * later. Neither platform gates playback on it.
   *
   * **Cross-platform note on enable transition**: flipping `enabled`
   * from `false` → `true` follows the same Android limitation —
   * a fresh SimpleCache is only constructed during `configureInternal`,
   * so the cache only comes online on the next `destroy()` +
   * `configure()` cycle. iOS instantiates the cache lazily on the
   * first prefetch event, so the enable flip is effectively
   * immediate.
   */
  setLookaheadCache(config: LookaheadCacheConfig): Promise<void>;
  getLookaheadCacheStatus(): Promise<CacheStatus>;
  /**
   * Wipe every cached entry + cancel any in-flight prefetch. Does
   * NOT auto-restart the prefetcher — `clear` means "wipe and stop".
   * Trigger refill explicitly via `setLookaheadCache(...)`,
   * `setQueue(...)`, or `skipToIndex(...)` when you want a new
   * prefetch window.
   */
  clearLookaheadCache(): Promise<void>;
  /**
   * Fires once on subscribe with the current status, then after every
   * `setLookaheadCache()`, prefetch completion and `clearLookaheadCache()`.
   * An eviction shows in the next snapshot those produce.
   */
  onCacheStatusChange(callback: (status: CacheStatus) => void): () => void;

  /**
   * Set the ReplayGain loudness-normalisation mode.
   *
   * The lib reads each track's `REPLAYGAIN_*` tags at track-load
   * time and applies a per-track linear gain to the audio stream
   * BEFORE the EQ stage. The peak tag (if present + trustworthy)
   * caps the effective gain to prevent clipping.
   *
   * Tag-format coverage:
   * - **MP3** (ID3v2 TXXX), **AAC / ALAC / M4A** (iTunes-style
   *   atoms): iOS + Android.
   * - **FLAC**, **OGG-Vorbis** (Vorbis-comments): Android only;
   *   iOS Vorbis-comment surfacing varies and is best-effort.
   * - **Opus**: `OpusHead.output_gain` is auto-applied by the OS
   *   decoder per RFC 7845. Tag-based RG is intentionally ignored
   *   on Opus containers to avoid double-correction.
   * - **HLS / streaming-manifest sources**: not supported.
   *
   * Strict mode semantics: `'album'` mode requires the track to
   * carry `REPLAYGAIN_ALBUM_GAIN` to apply gain — there is NO
   * fallback to track-gain when the album tag is absent. The
   * `replayGainActive` field on `getNowPlayingFormat()` reports
   * the live state.
   *
   * Effect is per-track and live: changing the mode mid-track
   * takes effect within ~1 audio buffer. Crossfade applies the
   * per-track gain on each leg independently.
   *
   * Default: `'off'`.
   */
  setReplayGainMode(mode: ReplayGainMode): Promise<void>;
  /** Returns the current ReplayGain mode. */
  getReplayGainMode(): ReplayGainMode;

  /**
   * Snapshot of the currently-playing track's audio format.
   * Returns `null` until the format has been loaded for the active
   * track — iOS requires `await asset.load(.formatDescriptions,
   * .estimatedDataRate)` per item; Android lands the data via
   * `Player.Listener.onTracksChanged(Tracks)` before STATE_READY.
   * Re-fired on every `onTrackChange`. Use the `useNowPlayingFormat`
   * hook for the React-side ergonomics.
   */
  getNowPlayingFormat(): NowPlayingFormat | null;
  /**
   * Subscribe to format changes. Fires `null` during the brief
   * window between a track-change and the new format loading
   * (iOS) / `onTracksChanged` arriving (Android), then fires the
   * populated `NowPlayingFormat` once detection completes. Returns
   * a dispose function — match the existing on...Change pattern.
   */
  onNowPlayingFormatChange(
    callback: (format: NowPlayingFormat | null) => void
  ): () => void;
}
