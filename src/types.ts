/**
 * Public value types for react-native-queue-player.
 *
 * All Nitro HybridObject interfaces live in their own `.nitro.ts` files
 * (TrackPlayer, Equalizer, Visualizer, CastManager). The **pure value
 * types** those objects exchange — every `interface` / `type` alias that
 * is *not* a HybridObject — live here, so there is one canonical home for
 * anything a consumer might want to `import` by name.
 *
 * Naming rules:
 *   - Interfaces are structural (`TrackItem`, `CastDevice`, …).
 *   - String-literal unions are top-level named aliases (`PlayerState`,
 *     `CastProtocol`, …), never inline in a field type — Nitrogen's C++
 *     codegen requires named enums.
 *   - Enum-like types with one real value include a reserved placeholder
 *     (e.g. `EvictionPolicy = 'lru' | 'fifo'`) to disambiguate from
 *     plain `string` during Nitrogen codegen. See NOTES.md §1.4.
 */

// ============================================================
// Player — shared playback value types
// ============================================================

/** A playback-timed metadata sample. Missing fields were not supplied by the stream. */
export interface TimedMetadata {
  title?: string;
  artist?: string;
  albumTitle?: string;
  /** An artwork URL, or a data URI when the stream embeds an image. */
  artworkUrl?: string;
}

export interface TrackItem {
  /**
   * Optional consumer-supplied identifier. Echoed back to the consumer
   * via remote-command callbacks + voice-query results so the consumer
   * can map back to their app's track model. NEVER used by this library
   * for any internal identity, lookup, dedup, or equality. Only
   * `track.url` is required + reliable on TrackItem; every other
   * consumer field may be empty / missing / duplicate / colliding.
   * Lib-internal identity is generated per-item at queue-mutation time
   * (e.g. iOS `AVPlayerItem.queueItemId`).
   */
  id?: string;
  /** Remote URL (http/https) or local `file://` URL. Local skips the lookahead cache. */
  url: string;
  title?: string;
  artist?: string;
  album?: string;
  /** Seconds. Optional — native engine derives from the stream when absent. */
  duration?: number;
  /** Cover art URL — `file://` for local, http(s) for remote (custom headers apply). */
  artworkUrl?: string;
  /** Opaque passthrough the consuming app can attach; the module does not read it. */
  extras?: Record<string, string>;
  /**
   * Consumer-supplied `REPLAYGAIN_TRACK_GAIN` in dB.
   *
   * The four ReplayGain fields are all-or-nothing per track: supplying
   * ANY of them makes this track's ReplayGain come solely from these
   * values, and whatever the file itself carries is discarded in full —
   * including for the mode nothing was supplied for. Supplying a value
   * is the statement of intent; a track that should use the file's own
   * tags carries none of these fields.
   *
   * Presence is what claims the track, not validity: a lone
   * out-of-range peak still discards the file's tags and leaves the
   * track playing at unity.
   */
  replayGainTrackGain?: number;
  /**
   * Consumer-supplied `REPLAYGAIN_TRACK_PEAK`, linear. Accepted in the
   * `0.001 ... 2.0` band — `1.0` is digital full scale and caps a boost
   * at unity. A gain with no usable peak has its boost clamped to unity
   * and reports a `null` peak.
   */
  replayGainTrackPeak?: number;
  /** Consumer-supplied `REPLAYGAIN_ALBUM_GAIN` in dB. */
  replayGainAlbumGain?: number;
  /** Consumer-supplied `REPLAYGAIN_ALBUM_PEAK`, linear. */
  replayGainAlbumPeak?: number;
}

/**
 * Result returned by `shuffleQueue()`. The consumer app uses this
 * snapshot to immediately reflect the new ordering in its UI.
 *
 * - `tracks`: the post-shuffle queue (Fisher-Yates permutation of the
 *   pre-shuffle queue). The same `TrackItem` references the consumer
 *   passed in, just reordered.
 * - `currentIndex`: position of the now-current track. Always `0` for
 *   non-empty queues; `-1` for an empty queue.
 * - `currentTrack`: convenience accessor for `tracks[currentIndex]`.
 *   `undefined` for empty queues.
 */
export interface ShuffleResult {
  tracks: TrackItem[];
  currentIndex: number;
  currentTrack?: TrackItem;
}

export interface PlayerConfig {
  /** Applied on every remote URL fetch: asset load, lookahead download, artwork. */
  httpHeaders?: Record<string, string>;
  /** Overrides the default UA on network requests. */
  userAgent?: string;
  /**
   * Replacement for the built-in RNQP cover-art placeholder (the image
   * shown before real artwork loads and when a track has no artwork).
   * MUST be a **local `file://` URI** — the built-in placeholder is a
   * local file, so this must be too; remote URLs are not loaded (they
   * would break offline playback and change behaviour). When set, this
   * image is used in place of the built-in everywhere the built-in is
   * used; unset, non-`file://`, or unloadable falls back to the built-in.
   * Resolve a bundled asset to a `file://` path with expo-asset —
   * `const a = Asset.fromModule(require('./logo.png')); await
   * a.downloadAsync();` then pass `a.localUri` — which copies the packaged
   * image onto the filesystem across dev and release.
   * (`Image.resolveAssetSource(...).uri` is not suitable: it is an
   * `http://` Metro URL in dev and a drawable resource name on Android
   * release, neither of which is a `file://` path.)
   */
  placeholderArtworkUri?: string;
  /**
   * Auto-retry attempts on transient errors (TIMEOUT / CONNECTION_LOST /
   * NETWORK_UNREACHABLE / SOURCE_UNREACHABLE / CANNOT_OPEN). Clamped
   * to [0, 5]; default 1. Fatal errors (codec / format / auth) are
   * never retried regardless of this setting.
   */
  autoRetries?: number;
  /**
   * Linear backoff in ms between retry attempts. **Clamped to
   * [200, 5000]; default 500.** The 200 ms floor is unconditional
   * (anti-retry-storm; passing 0 silently clamps up to 200).
   */
  retryBackoffMs?: number;
  /**
   * **iOS: ignored (AVPlayer uses CFNetwork; no public timeout API;
   * system-default ~60s applies).**
   *
   * Android: HTTP read timeout in ms. Applied via
   * `DefaultHttpDataSource.Factory.setReadTimeoutMs`. Clamped to
   * [1000, 60000]; default 30000. The longer-than-Media3-default
   * tolerates slow-spin-up servers; iOS's resilience equivalent is
   * the `autoRetries` + stuck-recovery flow. See NOTES.md §17 for
   * the full asymmetry rationale.
   */
  networkTimeoutMs?: number;
  /**
   * Hints the OS at the playback content type. `'music'` (default)
   * keeps the iOS audio session in `.default` mode + Android
   * `AudioAttributes.CONTENT_TYPE_MUSIC`. `'speech'` switches iOS
   * to `.spokenAudio` mode (per Apple HIG: spoken-audio sessions
   * properly pause under nav-voice instead of ducking) and Android
   * to `CONTENT_TYPE_SPEECH`.
   *
   * **Pinned at the first `configure()` per session.** Changing
   * this on a re-`configure()` requires `destroy()` + new
   * `configure()` to take effect (iOS audio-session reactivation
   * mid-playback risks observable hiccups; the lib opts for
   * predictability over runtime flexibility here).
   */
  audioContentType?: AudioContentType;
  /**
   * Fine-grained AVAudioSession `CategoryOptions` mapping
   * (iOS-effective; Android handles ducking via auto-managed
   * audio focus and ignores these flags). Same pin-at-first-
   * configure rule as `audioContentType`.
   */
  audioCategoryOptions?: AudioCategoryOptions;
  /**
   * Opt in to donating the consumer's catalog to Siri's vocabulary, so voice
   * queries like "Play Pearl Jam" resolve against the app's own artist and
   * playlist names. Enables `donateVoiceVocabulary`, which is inert without
   * it. Default `false`.
   *
   * **iOS only.** Android ignores this field: Google Assistant has no in-app
   * vocabulary donation API, and `donateVoiceVocabulary` is already a no-op
   * there.
   *
   * **Enabling it requires the `com.apple.developer.siri` entitlement** —
   * Xcode's Siri capability — not just `NSSiriUsageDescription` in
   * Info.plist. `INVocabulary` checks for the entitlement at runtime and
   * raises an Objective-C exception without it, which cannot be caught from
   * Swift and terminates the app. iOS gives a process no way to read its own
   * entitlements, so the library cannot verify this on your behalf: leave the
   * flag `false` unless the Siri capability is on the target.
   *
   * **Pinned at the first `configure()` for the life of the process.** The
   * entitlement it depends on is fixed when the app is signed, so there is
   * nothing for a later change to mean; a re-`configure()` with a different
   * value is ignored rather than silently re-arming a crash path.
   */
  voiceVocabularyDonationEnabled?: boolean;
  /**
   * Whether AVPlayer may switch to external playback on an AirPlay receiver
   * that supports it, such as an Apple TV or a Mac. In external playback,
   * AVPlayer hands the track URL to the receiver, which fetches and plays it
   * itself. Default `false`.
   *
   * With `false`, the device's player renders the audio and AirPlay carries
   * it, so the lookahead cache, EQ, ReplayGain, crossfade and the visualizer
   * keep working over AirPlay.
   *
   * With `true`:
   * - **Disable the lookahead cache before queueing tracks**, with
   *   `setLookaheadCache({ enabled: false })`; it is on by default. A remote
   *   track queued while the cache is on plays through a server bound to the
   *   device's loopback address, which the receiver cannot reach, so it
   *   fails on that route. Tracks queued before the cache was disabled keep
   *   that address.
   * - The receiver fetches the URL itself, so it must be reachable from the
   *   receiver's network. Headers set through `httpHeaders` and `userAgent`
   *   are not guaranteed to reach it.
   * - While the receiver plays the URL, the device's player does not render
   *   the audio, so EQ, ReplayGain, crossfade, the visualizer and
   *   end-of-track events may not behave as they do on local output.
   *
   * **iOS only.** Android ignores this field. Pinned at `configure()`; a
   * re-`configure()` rebuilds the engine with the new value.
   */
  allowsExternalPlayback?: boolean;
  /**
   * Disable the visualization pipeline at engine construction. When
   * `false`, the lib skips the viz-side audio chain entirely:
   * - **iOS**: visualizer subscribers do not request a tap install. With
   *   EQ also off, the `MTAudioProcessingTap` is not constructed per
   *   `AVPlayerItem`. EQ keeps working independently — toggling
   *   `Equalizer.setEnabled(true)` re-installs the tap for EQ use only.
   * - **Android**: the renderers factory is a vanilla
   *   `DefaultRenderersFactory`, skipping the
   *   `MultiplexingAudioBufferSink` Tee in the AudioSink chain entirely.
   *   EQ runs against `audioSessionId` and is unaffected.
   *
   * Calls to `getVisualizer().subscribe(...)` against a player
   * configured with `visualizationEnabled: false` fire
   * `onError('visualization-disabled')` on the next main-thread tick
   * (matches the existing `'hls-source'` error pattern). The dispose
   * returned from `subscribe` is a no-op.
   *
   * Default: `true` (current behaviour preserved for existing
   * consumers).
   *
   * Effect is pin-at-configure on both platforms — per `configure()`'s
   * auto-destroy contract every call rebuilds the engine, so setting
   * this flag in a re-configure call cleanly takes effect.
   */
  visualizationEnabled?: boolean;
  /**
   * Opt-in: cap a `seekTo` (or lock-screen interval skip) target that is past
   * the current buffered position at that buffered edge, so a scrub doesn't
   * overshoot into a re-buffer. A local or fully-buffered track reports
   * `buffered >= target`, so nothing is capped and a seek reaches the end
   * freely; a partly-buffered stream caps at the downloaded edge. Gated only on
   * this flag and `target > bufferedPosition` — not on source scheme or
   * declared duration. Separate from the always-on seekability guard (which
   * no-ops seeks on genuinely unseekable tracks).
   *
   * Default: `false` (targets pass through unclamped).
   */
  clampSeekToBuffered?: boolean;
  /**
   * How skip-to-previous behaves. `'previous'` (default) always goes to the
   * previous track. `'restart-or-previous'` restarts the current track
   * (seeks to 0) when it is past ~3 s, otherwise goes to the previous track
   * — the standard music-player model. Applies
   * to local playback; during cast, Previous routes to the receiver. When
   * `'restart-or-previous'`, the Previous control is always enabled
   * (`canSkipPrevious` reports `true` whenever a track is loaded, so the
   * control never greys out — even on the first track, where past the
   * threshold it restarts).
   *
   * Pinned per `configure()` (a fixed behavior, not a live toggle);
   * changing it re-runs `configure()`. Default: `'previous'`.
   */
  skipToPreviousBehavior?: SkipToPreviousBehavior;
  /**
   * Disk budget for the lookahead cache, in megabytes. Fixed at `configure()`
   * time and cannot change at runtime — Android's Media3 `SimpleCache` fixes
   * its size at construction (one instance per directory), so both platforms
   * treat this as a configure-only ceiling for a consistent contract. Default
   * `100`. Control actual runtime usage via `setLookaheadCache`'s `enabled` +
   * `lookaheadCount`; read the active ceiling back from
   * `getLookaheadCacheStatus().maxSizeMb`.
   *
   * Where the playing track is protected from eviction, this is a budget rather
   * than a hard cap — that is both policies on iOS, and `'fifo'` on Android
   * (see `EvictionPolicy`). Eviction never takes the playing track or its
   * immediate neighbours, so when those alone exceed the budget nothing is
   * evictable and usage can exceed it —
   * `getLookaheadCacheStatus().currentSizeMb` may read above `maxSizeMb` until
   * the window moves. Reachable with lossless sources and a large
   * `lookaheadCount`; size the budget for `2 + lookaheadCount` tracks to avoid
   * it. The alternative would be evicting the track being played.
   */
  lookaheadCacheMaxSizeMb?: number;
  /**
   * Eviction policy for the lookahead cache. Fixed at `configure()` time like
   * `lookaheadCacheMaxSizeMb` — changing it clears the cache and rebuilds with
   * the new policy (an infrequent operation). Default `'lru'`. See
   * {@link EvictionPolicy}.
   */
  lookaheadCacheEvictionPolicy?: EvictionPolicy;
  /**
   * Interval, in milliseconds, between `onProgress` emissions while the app is
   * in the foreground. Throttles only the JS `onProgress` fan-out — the native
   * sampler keeps running at its 2 Hz tick, so `onPlaybackMilestone` and buffer
   * state stay full-rate and unaffected.
   *
   * `500` is the effective floor: the native tick is 500 ms, so values below it
   * clamp to 500 (there is no faster-than-2Hz foreground rate). Non-positive or
   * non-finite values fall back to the default. No upper ceiling.
   *
   * Default: `500`. Applied at `configure()`; a re-`configure()` updates the
   * live rate with no engine rebuild.
   */
  progressUpdateIntervalMs?: number;
  /**
   * Interval, in milliseconds, between `onProgress` emissions while the app is
   * backgrounded. Lets a consumer drop the progress firehose (and the off-screen
   * re-renders it drives) while the UI is not visible; the lock screen / now-
   * playing surface and `onPlaybackMilestone` are separate native paths and keep
   * updating normally. On return to the foreground the next tick emits
   * immediately so the UI resumes at the live position.
   *
   * Clamped to a `500` ms floor like `progressUpdateIntervalMs`; a large value
   * (e.g. `60000`) is a legitimate near-paused background rate.
   *
   * Default: the effective `progressUpdateIntervalMs` (omitting this ⇒ no
   * background reduction). Applied at `configure()`; a re-`configure()` updates
   * the live rate with no engine rebuild.
   */
  backgroundProgressUpdateIntervalMs?: number;
  /**
   * Writes the library's diagnostic log lines to the system log, where they
   * are kept on the device and can be exported with a sysdiagnose. iOS only
   * today: lines go to the unified log under subsystem
   * `com.margelo.nitro.queueplayer`, one category per area (`NowPlaying`,
   * `AudioSession`, `RemoteCommand`). They carry state, timings and reasons,
   * never track titles, artists or URLs. Android accepts the option and logs
   * nothing yet.
   *
   * Default: `false`. Applied at `configure()`.
   */
  enableDiagnosticLogs?: boolean;
}

/**
 * High-level content classification used by the lib to drive
 * platform audio-session shape (iOS mode + Android
 * `AudioAttributes.contentType`).
 */
export type AudioContentType = 'music' | 'speech';

/**
 * Optional AVAudioSession `CategoryOptions` flags. iOS-effective
 * (Android uses auto-ducking via audio focus and ignores these).
 */
export interface AudioCategoryOptions {
  /**
   * `.duckOthers` — when this app starts playback, other apps'
   * audio ducks (volume-reduced) instead of pausing.
   */
  duckOthers?: boolean;
  /**
   * `.mixWithOthers` — playback mixes with other apps' audio
   * instead of pausing them.
   */
  mixWithOthers?: boolean;
  /**
   * `.interruptSpokenAudioAndMixWithOthers` — interrupts other
   * apps' spoken-audio sessions but mixes with their music
   * sessions. **Default `true` when `audioContentType === 'speech'`,
   * `false` otherwise** (matches Apple's recommendation for
   * podcast / audiobook apps).
   */
  interruptSpokenAudio?: boolean;
}

export type PlayerState =
  | 'none'
  | 'loading'
  | 'buffering'
  | 'playing'
  | 'paused'
  | 'ended'
  | 'error';

/**
 * Discrete buffer state for the current track, derived from the platform's
 * native buffer signals (independent of the 2 Hz progress tick, so a spinner /
 * stall indicator reacts immediately):
 *
 * - `'full'` — enough is buffered to play smoothly from the current position
 *   (iOS `playbackLikelyToKeepUp` / `playbackBufferFull`; Android
 *   `STATE_READY`). Hide the loading spinner.
 * - `'buffering'` — loading, and playback has **not started yet** for this
 *   track (the initial buffer, even when play was requested), or loading while
 *   not actively trying to play. This is the expected, non-urgent loading case.
 * - `'stalled'` — playback **had started** and is now rebuffering mid-play
 *   (it wants to continue but ran out of data). This is the case where a
 *   spinner interrupts audio the user was already hearing.
 * - `'empty'` — nothing is loaded to play (iOS: no current item; Android
 *   `STATE_IDLE`).
 *
 * `'full'` describes playback health, not that the whole track is downloaded —
 * for buffered-ahead length in seconds use `PlayerProgress.buffered`.
 */
export type BufferState = 'empty' | 'buffering' | 'stalled' | 'full';

/**
 * Playback mode. §4.2 specifies `'gapless' | { crossfade: number }`; Nitrogen
 * can't represent a discriminated union with a string-literal tag (see
 * NOTES.md §1.2), so the API uses a single interface with a `kind` enum
 * field plus optional per-variant payload.
 */
export type PlaybackModeKind = 'gapless' | 'crossfade';

export interface PlaybackMode {
  kind: PlaybackModeKind;
  /**
   * Required + only read when `kind === 'crossfade'`. Ignored otherwise.
   *
   * Constraints (enforced at the native boundary — out-of-range or
   * non-quantised values throw); see `crossfadeDurationBounds` for
   * the canonical numbers a stepper UI can render against:
   *   - Range: `[1000, 12000]` ms.
   *   - Quantisation: must be a multiple of `500` ms (so 1000, 1500,
   *     2000, ..., 12000 — 23 valid values).
   *
   * The engine applies a separate runtime safety clamp to the
   * configured value: when the leading or standby track is shorter
   * than `2 × crossfadeDurationMs`, the engine silently clamps to
   * the longest fade that fits both legs. Consumers see no API
   * surface change; the audio just doesn't try to fade longer than
   * the track has runway for.
   */
  crossfadeDurationMs?: number;
}

/**
 * Canonical bounds for `PlaybackMode.crossfadeDurationMs`. Mirrors
 * the native validation constants — consumers building stepper /
 * slider UIs can read from here instead of hardcoding magic numbers.
 */
export const crossfadeDurationBounds = {
  /** Minimum accepted crossfade duration in ms. */
  minMs: 1_000,
  /** Maximum accepted crossfade duration in ms. */
  maxMs: 12_000,
  /** Quantisation step in ms — values must be multiples of this. */
  stepMs: 500,
} as const;

/**
 * Queue repeat behaviour:
 * - `'off'` — play through the queue once and stop at the end.
 * - `'queue'` — loop the whole queue; skip wraps at both ends.
 * - `'track'` — repeat-one: the current track replays on natural end.
 *   A user skip still navigates the queue with no wrap (exactly like
 *   `'off'`), and repeat-one persists so the newly-current track is the
 *   one that loops.
 */
export type RepeatMode = 'off' | 'queue' | 'track';

/**
 * How `skipToPrevious` (and the OS Previous control) behaves.
 * - `'previous'` — always go to the previous track (the default; current
 *   behavior).
 * - `'restart-or-previous'` — restart the current track (seek to 0) when it
 *   is past ~3 s, otherwise go to the previous track. The standard
 *   music-player model. See `PlayerConfig.skipToPreviousBehavior`.
 */
export type SkipToPreviousBehavior = 'previous' | 'restart-or-previous';

/**
 * How the lock-screen / notification skip controls behave: skip between queue
 * tracks (`'track'`) or jump forward/back within the current track by a fixed
 * interval (`'interval'`).
 */
export type RemoteSkipMode = 'track' | 'interval';

/**
 * Lock-screen / notification remote-control configuration. Wrapped in a struct
 * so fields can be added without a breaking change.
 */
export interface RemoteControlOptions {
  /** Skip controls jump between tracks (`'track'`) or by `±interval` seconds (`'interval'`). Default `'track'`. */
  skipMode: RemoteSkipMode;
  /** Seconds to jump ahead in `'interval'` mode. Default 15. */
  forwardJumpInterval: number;
  /** Seconds to jump back in `'interval'` mode. Default 15. */
  backwardJumpInterval: number;
}

/**
 * ReplayGain loudness-normalisation mode.
 *
 * - `'off'`: no gain applied; playback is sample-perfect relative
 *   to the source.
 * - `'track'`: read `REPLAYGAIN_TRACK_GAIN` per track. Best for
 *   shuffled / mixed playlists — every track plays at roughly the
 *   same perceived loudness.
 * - `'album'`: read `REPLAYGAIN_ALBUM_GAIN` per track (preserves
 *   the album's intentional cross-track dynamics — e.g. a quiet
 *   intro vs a loud climax). Strict — does NOT fall back to track
 *   gain when a track has no album tag (RG is simply inactive for
 *   that track; `replayGainActive` reports `false`).
 *
 * Auto-mode (lib heuristically picks track-vs-album from queue
 * context) is intentionally NOT supported — the consuming app
 * knows whether the user is playing an album front-to-back vs
 * shuffling a mixed playlist, and should call this setter
 * accordingly.
 */
export type ReplayGainMode = 'off' | 'track' | 'album';

/**
 * How pitch behaves when `playbackSpeed != 1.0`:
 * - `none` — no pitch correction; pitch follows rate (the
 *   "chipmunk" effect on speed-up, deepening on slow-down).
 * - `voice` — speech-tuned time-stretch; pitch is preserved.
 *   Cheapest correct option and the default.
 * - `music` — music-tuned time-stretch that preserves transients
 *   (percussion, attacks) on speed change.
 *
 * Auto-bypassed at `playbackSpeed == 1.0` (nothing to stretch).
 */
export type PitchCorrectionMode = 'none' | 'voice' | 'music';

export type StateChangeReason =
  | 'user'
  | 'system'
  | 'error'
  | 'interruption'
  | 'route-change'
  | 'queue-end'
  | 'sleep-timer';

/**
 * Progress snapshot emitted by `onProgress`. Positions in seconds,
 * driven by the native periodic time observer at ~2 Hz. Wrapped in a
 * struct (rather than three positional `number` callback args) so
 * future progress fields can be added without a breaking spec change.
 */
export interface PlayerProgress {
  /** Seconds from start of the current track. 0 when no track active. */
  position: number;
  /** Seconds. 0 when duration is unknown or no track active. */
  duration: number;
  /** Seconds buffered ahead of `position`. 0 when unknown. */
  buffered: number;
}

export type TrackChangeReason =
  | 'user-skip-next'
  | 'user-skip-previous'
  | 'user-skip-to-index'
  | 'auto-advance'
  | 'queue-replaced'
  | 'seek'
  /**
   * Crossfade was cancelled mid-fade (pause / stop / audio-focus
   * loss / queue mutation that invalidates the standby leg). Fires
   * with the outgoing leg's `(track, index)` so consumers can
   * revert any UI that flipped to the incoming on the prior
   * `auto-advance` fade-start fire. Crossfade-only.
   */
  | 'crossfade-cancelled';

/**
 * Why the queue contents or order changed, reported by `onQueueChange`.
 * Each value maps 1:1 to the public mutation that produced it.
 */
export type QueueChangeReason =
  | 'set-queue' // setQueue replaced the whole queue
  | 'add' // addToQueue inserted track(s)
  | 'remove' // removeFromQueue removed track(s)
  | 'move' // moveInQueue reordered a track
  | 'clear' // clearQueue emptied the queue
  | 'shuffle'; // shuffleQueue reordered the queue

/**
 * What a queue mutation did, delivered by `onQueueChange` alongside the reason.
 *
 * Describes the change rather than re-sending the queue, so a reorder costs two
 * numbers instead of one `TrackItem` per entry. Which fields carry meaning
 * depends on the reason; the rest are empty or `-1`.
 *
 * Apply it to your own copy of the queue, or call `getQueue()` if you would
 * rather re-read than track it. `useQueue()` does the former for you.
 *
 * **Not every emptying of the queue is reported.** `destroy()` and `configure()`
 * clear it without an event, and `configure()` runs that teardown on every call,
 * so a mirror held across one is stale with nothing to signal it.
 */
export interface QueueChangeDelta {
  /**
   * Increments once per emit. A jump of more than one means an event was missed
   * and the mirror should be rebuilt from `getQueue()`. Length alone cannot
   * detect that — `move`, `shuffle` and a same-size `set-queue` all preserve it.
   */
  revision: number;
  /** Queue length after the mutation. */
  length: number;
  /**
   * Tracks now occupying `[insertedAt, insertedAt + inserted.length)`.
   * Populated for `add` and `set-queue`; empty otherwise.
   *
   * `set-queue` carries the whole queue because the subscriber is not
   * necessarily whoever called `setQueue` — and on Android an assistant or
   * Android Auto request replaces the queue with tracks the app never passed in.
   */
  inserted: TrackItem[];
  /** Where `inserted` begins, or `-1` when nothing was inserted. */
  insertedAt: number;
  /** Positions removed, ascending, as they were before the removal. */
  removed: number[];
  /** Position a `move` came from, else `-1`. */
  movedFrom: number;
  /** Position a `move` went to, else `-1`. */
  movedTo: number;
  /**
   * For `shuffle`: `order[i]` is the position the track now at `i` held before
   * the shuffle. Empty for every other reason.
   */
  order: number[];
}

/**
 * How the active track is being played:
 * - `'local'` — `track.url` is a `file://` URL.
 * - `'cached'` — `track.url` is `http(s)://` and the lookahead cache holds
 *   a complete copy; native playback reads from the local cache file.
 * - `'streaming'` — `track.url` is `http(s)://` and the lookahead cache
 *   does NOT have a complete copy; native playback reads from the network
 *   (a partial cache may exist, but bytes are still arriving).
 *
 * Classified at currentItem-change time (the moment the new track becomes
 * current). A track that's mid-download surfaces as `'streaming'`; the next
 * track-change re-classifies once the cache has more.
 *
 * `undefined` when there is no active track (empty queue, post-clear, or
 * post-destroy) OR when `track.url` uses a scheme other than `file://` /
 * `http://` / `https://` (e.g. `data:`, `blob:`, custom schemes — the
 * classifier returns `undefined` rather than a misleading default).
 */
export type TrackSource = 'streaming' | 'local' | 'cached';

/**
 * Canonical audio codec identifier surfaced by `getNowPlayingFormat()`.
 * Both natives can detect the AAC family (LC / HE / HE-v2 / xHE) but
 * surfacing the profile requires extra parsing — v1 collapses to
 * `'aac'`. Consumers needing the AAC profile read it from the
 * platform-specific extras object's `codecs` field (RFC 6381 string
 * on Android) or off the iOS `formatDescriptions` directly.
 */
export type AudioCodec =
  | 'aac'
  | 'mp3'
  | 'flac'
  | 'alac'
  | 'opus'
  | 'pcm'
  | 'unknown';

/**
 * Canonical container identifier. `'mp4'` covers `.m4a`, `.m4b`, `.m4r`,
 * and audio-only `.mp4`. `'hls'` is the playlist (.m3u8) container,
 * not a file format.
 */
export type AudioContainer =
  | 'mp4'
  | 'mp3'
  | 'flac'
  | 'ogg'
  | 'wav'
  | 'aac-adts'
  | 'hls'
  | 'unknown';

/**
 * Snapshot of the currently-playing track's audio format. Surfaced
 * via `getNowPlayingFormat()` (sync getter — null until populated)
 * and `onNowPlayingFormatChange` (event-driven). Refresh on every
 * `onTrackChange` — the snapshot is keyed on whatever AVPlayerItem
 * (iOS) / MediaItem (Android) is currently active.
 *
 * Six fields are reliable on BOTH platforms for both `file://` and
 * `http(s)://` sources. Platform-specific extras live under the
 * optional `ios` / `android` objects so consumers can access richer
 * data when available without forcing cross-platform branching.
 */
export interface NowPlayingFormat {
  /** Canonical codec identifier. `'unknown'` when detection fails. */
  codec: AudioCodec;
  /**
   * Canonical container identifier. `'unknown'` when not detectable.
   * Container can be inferred independently of codec — e.g. AAC in
   * MP4 vs AAC in ADTS produce the same `codec: 'aac'` but distinct
   * `container` values.
   */
  container: AudioContainer;
  /**
   * Raw sample MIME type from the native layer (iOS-mapped FourCC
   * / Android `Format.sampleMimeType`). E.g. `'audio/mp4a-latm'`,
   * `'audio/mpeg'`, `'audio/flac'`. `null` when not detectable.
   */
  mimeType: string | null;
  /** Sample rate in Hz, or `null` when not detectable. */
  sampleRate: number | null;
  /** Channel count (1 = mono, 2 = stereo, 6 = 5.1, etc.), or `null`. */
  channelCount: number | null;
  /**
   * Average encoded bitrate in bits-per-second, or `null` when the source
   * does not declare one and it cannot be measured.
   *
   * Available for `file://` sources of any codec, and for progressive HTTP
   * and HLS sources whose stream or manifest declares a rate — which MP3,
   * M4A/AAC and HLS variants do.
   *
   * **Not available for remote FLAC.** The FLAC header carries no bitrate
   * — the format does not declare one — so it can only be derived from the
   * file's size over its duration. That works for a local file and needs a
   * request the library does not make for a remote one.
   *
   * A derived figure is the whole-file average, so it includes container
   * headers and embedded artwork: typically a few kbps above the audio-only
   * rate.
   */
  bitrate: number | null;
  /** Track duration in seconds, or `null` when not yet known. */
  durationSec: number | null;
  /**
   * The active track's `REPLAYGAIN_TRACK_GAIN` value in dB, or
   * `null` if the track has no track-gain tag (or the tag was
   * rejected by the parser, or the container is Opus where
   * `OpusHead.output_gain` is decoder-applied instead).
   *
   * Whether this gain is actually being applied depends on
   * `getReplayGainMode()` — see `replayGainActive`.
   */
  replayGainTrackDb: number | null;
  /**
   * The active track's `REPLAYGAIN_TRACK_PEAK` (linear, [0, ~1.5]),
   * or `null` if no trustworthy track-peak tag is present. When
   * non-null and the active mode applies track gain, the lib
   * auto-clamps the effective gain so `linearGain * peak ≤ 1.0`
   * to prevent clipping.
   */
  replayGainTrackPeak: number | null;
  /**
   * The active track's `REPLAYGAIN_ALBUM_GAIN` value in dB, or
   * `null` if the track has no album-gain tag.
   */
  replayGainAlbumDb: number | null;
  /**
   * The active track's `REPLAYGAIN_ALBUM_PEAK` (linear, [0, ~1.5]),
   * or `null` if no trustworthy album-peak tag is present. Same
   * clip-protection behaviour as `replayGainTrackPeak` when album
   * gain is active.
   */
  replayGainAlbumPeak: number | null;
  /**
   * `true` when ReplayGain is currently being applied to this
   * track: `getReplayGainMode() != 'off'` AND the tag for that
   * exact mode is present on the active track.
   *
   * Strict — `'album'` mode requires `replayGainAlbumDb !== null`;
   * `'track'` mode requires `replayGainTrackDb !== null`. There is
   * no fallback. Use this for a UI indicator (e.g., a "RG" badge
   * shown next to the codec/source labels).
   */
  replayGainActive: boolean;
  /** iOS-specific extras. Absent on Android. */
  ios?: NowPlayingFormatIosExtras;
  /** Android-specific extras. Absent on iOS. */
  android?: NowPlayingFormatAndroidExtras;
}

/** iOS-specific NowPlayingFormat fields. */
export interface NowPlayingFormatIosExtras {
  /** Bits per channel (PCM / lossless paths only; `null` for compressed). */
  bitsPerChannel: number | null;
}

/** Android-specific NowPlayingFormat fields. */
export interface NowPlayingFormatAndroidExtras {
  /** Manifest-declared peak bitrate (HLS / DASH), or `null`. */
  peakBitrate: number | null;
  /** RFC 6381 codecs string (e.g. `'mp4a.40.2'`), or `null`. */
  codecs: string | null;
  /**
   * `Format.pcmEncoding` value (PCM-only paths). `Format.NO_VALUE`
   * (-1) for compressed sources. See Media3's `C.PcmEncoding`
   * constants for the enum semantics.
   */
  pcmEncoding: number | null;
}

/**
 * Lib-standardized cross-platform error code vocabulary. Drives consumer
 * branching logic (`if (err.code === 'TIMEOUT') ...`) without per-platform
 * forking. Both iOS NSError + AVError codes and Android Media3
 * `PlaybackException.errorCode` values map onto this enum via the
 * `PlaybackErrorMapping` helper on each platform.
 *
 * **Transient (auto-retry eligible):** TIMEOUT, CONNECTION_LOST,
 * NETWORK_UNREACHABLE, SOURCE_UNREACHABLE, CANNOT_OPEN (when
 * iOS-NSURLError-wrapped — see iOS PlaybackErrorMapping for the
 * two-tier classifier), UNKNOWN.
 *
 * **Fatal (never retried):** HTTP_ERROR, AUTH_ERROR,
 * CLEARTEXT_NOT_PERMITTED, FILE_FORMAT_NOT_RECOGNIZED, DECODER_NOT_FOUND,
 * DECODING_FAILED.
 */
export type PlaybackErrorCode =
  | 'TIMEOUT'
  | 'CONNECTION_LOST'
  | 'NETWORK_UNREACHABLE'
  | 'HTTP_ERROR'
  | 'AUTH_ERROR'
  | 'CLEARTEXT_NOT_PERMITTED'
  | 'FILE_FORMAT_NOT_RECOGNIZED'
  | 'DECODER_NOT_FOUND'
  | 'DECODING_FAILED'
  | 'SOURCE_UNREACHABLE'
  | 'CANNOT_OPEN'
  | 'UNKNOWN';

export interface PlaybackError {
  /** Lib-standardized cross-platform code. Drives consumer branching. */
  code: PlaybackErrorCode;
  /** Lib-mapped human-readable summary. Consistent across platforms. */
  message: string;
  /** Non-recoverable errors stop playback; recoverable ones let the player retry. */
  fatal: boolean;
  /**
   * Raw native error code: `NSError.code` on iOS (Int), or
   * `PlaybackException.errorCode` on Android (Int — see Media3's
   * `ERROR_CODE_*` constants). For HTTP errors on Android the
   * underlying HTTP status is exposed here directly.
   */
  nativeCode: number;
  /**
   * Native error domain: `"NSURLErrorDomain"` / `"AVFoundationErrorDomain"`
   * / etc. on iOS; `"Media3"` plus the `errorCodeName` (e.g.
   * `"Media3:ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT"`) on Android.
   * Empty string when no domain available.
   */
  nativeDomain: string;
  /**
   * Raw native message — typically `localizedDescription` on iOS or
   * `PlaybackException.message` on Android. URL query strings are
   * stripped before emission to avoid leaking auth tokens carried in
   * query strings (e.g. `?token=…`).
   */
  nativeMessage: string;
  /**
   * Lib-generated identifier for the queue item that produced this
   * error. Identifies the specific AVPlayerItem (iOS) / MediaItem
   * (Android) instance — independent of any consumer-supplied
   * track.id. Empty string when the error fired against a
   * not-currently-active item or when the player is between items.
   */
  queueItemId: string;
  /**
   * Source URL of the queue item that produced this error. Lets a
   * consumer correlate an error to the specific track even when
   * AVQueuePlayer chain-advances past the failed item and the
   * active currentTrackIndex has already moved on. URL query
   * strings are NOT stripped — consumer's auth tokens are their
   * own data on the way back to them. Empty string when the error
   * fired against a not-currently-identified item.
   */
  url: string;
}

/**
 * Skip-capability snapshot. Emitted by `onSkipCapabilityChange` and
 * mirrored by the `useCanSkip()` hook. Reflects whether there's a
 * track to skip to under the current repeat mode. Under
 * `RepeatMode.track` (repeat-one) skip navigates with no wrap exactly
 * like `RepeatMode.off`, so a flag is `false` at the queue boundary
 * (no skip-next on the last track, no skip-previous on the first).
 *
 * Wrapped in a struct (rather than two separate boolean callback
 * args) so further capability flags can be added without a breaking
 * API change.
 */
export interface SkipCapability {
  /** Reflects queue length + current index + repeat mode (symmetric with `canSkipNext`). */
  canSkipPrevious: boolean;
  /** Reflects queue length + current index + repeat mode. */
  canSkipNext: boolean;
}

/**
 * Sleep-timer snapshot — the current state of the native sleep timer, as
 * returned by `getSleepTimer()` and delivered to `onSleepTimerChange`.
 *
 * The timer is wall-clock: `endsAtEpochMs` is a fixed deadline that counts down
 * whether playback is playing or paused. In end-of-track mode the timer instead
 * waits for the current track to finish, so `endsAtEpochMs` is `null` until it
 * converts to a concrete deadline near the track's end.
 *
 * Wrapped in a struct (rather than positional callback args) so fields can be
 * added without a breaking change.
 */
export interface SleepTimerState {
  /** `true` while a sleep timer is armed (duration or end-of-track). */
  active: boolean;
  /** Wall-clock epoch (ms) when playback will pause; absent when inactive or awaiting track end. */
  endsAtEpochMs?: number;
  /** Seconds until the timer fires; absent when inactive or awaiting track end. */
  remainingSeconds?: number;
  /**
   * `true` while waiting to pause at the end of the current track. Flips to
   * `false` once the timer converts to a concrete countdown near the track end
   * (at which point `endsAtEpochMs` / `remainingSeconds` become available).
   */
  endOfTrack: boolean;
}

// ============================================================
// Lookahead cache
// ============================================================

/**
 * Eviction policy for the lookahead cache, chosen at `configure()` via
 * {@link PlayerConfig.lookaheadCacheEvictionPolicy}.
 *
 * - `'lru'` (default): evict the least-recently-used track first — recency is
 *   bumped when a track is loaded for playback, so replayed tracks are kept.
 * - `'fifo'`: evict the oldest-inserted track first, regardless of replays
 *   ("keep the newest-fetched") — suits a forward-only prefetch queue.
 *
 * Construction-fixed like the cache size: changing it at `configure()` clears
 * the cache and rebuilds with the new policy.
 *
 * **Protecting the playing track differs by platform.** Eviction runs on every
 * download commit, so without protection the entry backing the track being
 * played can be deleted while it plays.
 *
 * - **iOS** protects under both policies.
 * - **Android** protects under `'fifo'` only. Media3's LRU evictor has no
 *   protected-key concept, and its recency bump is per byte-range span rather
 *   than per track, so a long track's already-played spans stay evictable while
 *   it is still playing. Choose `'fifo'` if that matters to you.
 */
export type EvictionPolicy = 'lru' | 'fifo';

export interface LookaheadCacheConfig {
  /**
   * Whether the cache is in use. Enabled, playback of a track the cache holds
   * in full is served from disk with no request, a track it does not hold is
   * fetched from the origin and written as it plays, and the tracks ahead are
   * prefetched. Disabled, every read goes to the origin and nothing is written,
   * whatever the disk already holds; the item already loading keeps the source
   * it opened. The on-disk entries survive a disable and serve again on enable.
   */
  enabled: boolean;
  /** Tracks ahead of the current index to proactively cache. 0 disables lookahead; eviction still runs. */
  lookaheadCount: number;
  /**
   * Whether prefetching may use cellular data. Defaults to `true`.
   *
   * The lookahead cache downloads whole upcoming tracks before playback
   * reaches them, so on a metered connection it can spend a lot of data on
   * tracks the listener skips past. Set `false` to back a "download over
   * Wi-Fi only" setting.
   *
   * Applies at runtime — no `configure()` cycle — and gates prefetch only.
   * Playback is never blocked by it, so the track being played carries on
   * either way.
   *
   * The enforcement point differs, because the platforms offer different
   * primitives:
   * - **iOS** sets it on each prefetch request, and the OS refuses to run that
   *   request over cellular. A download already on the wire keeps the value it
   *   started with and runs to completion.
   * - **Android** has no per-request equivalent, so prefetch stops being
   *   scheduled while the default network is cellular. That also cancels the
   *   download in flight, whose partial data stays in the cache and resumes
   *   when prefetch is permitted again.
   */
  allowsCellularAccess?: boolean;
}

export interface CacheStatus {
  enabled: boolean;
  currentSizeMb: number;
  maxSizeMb: number;
  /**
   * Total number of tracks fully downloaded on disk in the cache — the whole
   * size-bounded cache, not scoped to the current queue or the lookahead
   * window. For a usage indicator prefer `currentSizeMb` / `maxSizeMb`.
   */
  tracksFullyCached: number;
  /** Track URLs currently downloading (subset of the lookahead window).
   *  Keyed on `track.url` because that's the cache key — `track.id` is
   *  consumer-supplied and not used internally.
   */
  currentlyCaching: string[];
}

// ============================================================
// Automotive / voice — handler request types
// ============================================================

export type MediaSearchType =
  | 'artist'
  | 'album'
  | 'song'
  | 'playlist'
  | 'genre';

/**
 * Which voice surface the request came from. Drives consumer's
 * resolver: iOS-only fields (`reference`, `playShuffled`,
 * `playbackRepeatMode`, `resumePlayback`, `playbackSpeed`,
 * `releaseDate`) are only populated when `origin === 'ios-siri'`.
 * The lib never invents an origin; if the request arrived from a
 * source the lib couldn't classify, `origin` is undefined.
 */
export type MediaSearchOrigin = 'ios-siri' | 'android-assistant';

/**
 * Apple's `INMediaReference` mapped to a JS enum. Apple's `.unknown`
 * case is mapped to `undefined` (the field is absent rather than
 * carrying a sentinel string).
 *
 * - `'currentlyPlaying'` — request refers to whatever is currently
 *   playing (e.g. resume / "more like this" patterns).
 * - `'my'` — request is scoped to the user's library / owned content
 *   (any utterance that signals "from my collection", not just the
 *   literal word "my").
 */
export type MediaSearchReference = 'currentlyPlaying' | 'my';

/**
 * Apple's `INPlaybackRepeatMode` mapped to a JS enum. Apple's
 * `.unknown` case is mapped to `undefined`.
 */
export type MediaSearchRepeatMode = 'none' | 'one' | 'all';

/**
 * Apple's `INPlaybackQueueLocation` mapped to a JS enum. Drives
 * "play X next" / "add X to the queue" voice utterances — Siri's
 * NLU parses the queue-position hint and surfaces it here so the
 * consumer's resolver can choose between replacing the queue
 * (`'now'`, the default), inserting after the current track
 * (`'next'`), or appending to the tail (`'later'`). Apple's
 * `.unknown` case is mapped to `undefined`.
 *
 * iOS only — Google Assistant's App Actions schema has no
 * structurally-equivalent slot, so the field is always undefined
 * for `origin === 'android-assistant'` requests.
 */
export type MediaSearchQueueLocation = 'now' | 'next' | 'later';

/**
 * Inclusive year range derived from `INMediaSearch.releaseDate`. Both
 * bounds are optional — Siri may surface only one end of the range
 * for queries like "play music from 1997". When both are populated
 * they describe a closed interval (e.g. `{ startYear: 1990, endYear:
 * 1999 }` for "play 90s music").
 */
export interface MediaSearchReleaseDateRange {
  startYear?: number;
  endYear?: number;
}

/**
 * iOS donation payload — the catalog items the consumer wants Siri's
 * NLU to recognise in voice queries. Maps to
 * `INVocabulary.setVocabularyStrings(_:of:)` with
 * `.mediaMusicArtistName` for `artists` and `.mediaPlaylistTitle`
 * for `playlists`. Apple's `INVocabularyStringType` has no case for
 * music album names or song titles; album matching happens at
 * intent-resolution time via the populated `MediaSearchRequest.album`
 * field, so a separate `albums` donation list has no destination in
 * Apple's API and is intentionally omitted.
 *
 * Both lists are required (empty array means "nothing to donate for
 * this taxonomy") — required `string[]` instead of `string[]?` keeps
 * the Nitrogen-generated C++ bridge cleanly typed; `std::optional<
 * std::vector<std::string>>` triggered a Swift C++ interop regression
 * on pre-existing files during a 5.1-time bisect.
 *
 * No-op on Android — Google Assistant has no in-app vocabulary
 * donation API; vocabulary discovery on Android is driven by App
 * Actions (`shortcuts.xml`) + the in-app browse tree. JSDoc in the
 * Hybrid spec documents the asymmetry to consumers.
 */
export interface VoiceVocabulary {
  artists: string[];
  playlists: string[];
}

/**
 * Voice / automotive search request. The wire shape is a single
 * interface with an enum-like `type` field plus per-type optional
 * refinements — Nitrogen disallows discriminated unions with
 * string-literal tags (NOTES.md §1.2), so the variants are
 * encoded by which optional field is populated rather than by
 * separate type members.
 *
 * **Field semantics by `type`** (consumer should populate only
 * the matching field; others should be `undefined`):
 *
 * - `'artist'` — `artist` carries the searched artist name; `query`
 *   is the original spoken/typed string.
 * - `'album'` — `album` and (optionally) `artist` qualify the
 *   album lookup.
 * - `'song'` — `song` and (optionally) `artist` / `album` qualify
 *   the song lookup.
 * - `'playlist'` — `playlist` carries the playlist name.
 * - `'genre'` — `genre` carries the genre name.
 * - `undefined` — generic search; consumer falls back to a
 *   best-effort lookup across all media kinds using `query`.
 *
 * Combinations not described above (e.g. `type: 'song'` with a
 * populated `playlist` field) are ambiguous; consumers should
 * ignore mismatched fields rather than reject the request.
 *
 * **Voice-only fields** (`origin`, `reference`, `playShuffled`,
 * `playbackRepeatMode`, `resumePlayback`, `playbackSpeed`,
 * `releaseDate`) are populated when the request arrived through a
 * voice assistant. `origin` is the discriminator. On Android
 * (`origin === 'android-assistant'`) only `genre` is structurally
 * populated by Google's NLU beyond the base fields; the iOS-only
 * fields are always undefined. Consumer's resolver should branch
 * on `origin` before reading them.
 */
export interface MediaSearchRequest {
  query: string;
  type?: MediaSearchType;
  artist?: string;
  album?: string;
  song?: string;
  playlist?: string;
  /**
   * First element of `INMediaSearch.genreNames` on iOS (lossy by
   * design — multi-genre queries collapse to one), or the parsed
   * genre extra from `MediaSession.Callback.onPlayFromSearch` on
   * Android. Either platform may leave it undefined.
   */
  genre?: string;
  /**
   * Voice-surface discriminator. Undefined when the request didn't
   * arrive through a recognised voice path.
   */
  origin?: MediaSearchOrigin;
  /**
   * Field shape inherited from the legacy `INMediaSearch.reference`
   * model. Not populated by iOS voice today (the App Intents path
   * delivers only `query` + occasional `artist`). Retained for shape
   * stability + future voice surfaces.
   */
  reference?: MediaSearchReference;
  /**
   * Field shape inherited from the legacy `INPlayMediaIntent` model.
   * Not populated by iOS voice today; see `reference` above.
   */
  playShuffled?: boolean;
  /**
   * Field shape inherited from the legacy `INPlayMediaIntent` model.
   * Not populated by iOS voice today; see `reference` above.
   */
  playbackRepeatMode?: MediaSearchRepeatMode;
  /**
   * Field shape inherited from the legacy `INPlayMediaIntent` model.
   * Not populated by iOS voice today; see `reference` above.
   */
  resumePlayback?: boolean;
  /**
   * Field shape inherited from the legacy `INPlayMediaIntent` model.
   * Not populated by iOS voice today; see `reference` above.
   */
  playbackSpeed?: number;
  /**
   * Field shape inherited from the legacy `INMediaSearch.releaseDate`
   * model. Not populated by iOS voice today (the App Intents path has
   * no decade-range slot); see `reference` above.
   */
  releaseDate?: MediaSearchReleaseDateRange;
  /**
   * Field shape inherited from the legacy `INPlayMediaIntent` model.
   * Not populated by iOS voice today; see `reference` above. When set
   * by other surfaces, the resolver branches: `'now'` (default)
   * replaces the queue and starts playback; `'next'` inserts the
   * resolved tracks after the current track without interrupting;
   * `'later'` appends to the tail.
   */
  playbackQueueLocation?: MediaSearchQueueLocation;
}

export interface BrowseItem {
  /**
   * Identifier the consumer chooses for this item. Echoed back via
   * `onBrowseRequest(parentId)` for drilldown and `onPlayFromIdRequest(mediaId)`
   * when the user selects a playable item. Lib does not interpret it.
   */
  id: string;
  title: string;
  subtitle?: string;
  /** Local `file://` or remote `https://` URL. Native side loads via the platform image loader. */
  artworkUrl?: string;
  /**
   * True if selecting this item starts playback (calls
   * `onPlayFromIdRequest`). Mutually exclusive with `hasChildren` —
   * neither CarPlay's `CPListItem` nor Android Auto's `MediaItem`
   * cleanly renders a row that is both. Consumers that want a
   * tap-to-play album with an expandable song list should ship two
   * sibling items (the playable album + a browsable "songs" entry).
   */
  playable: boolean;
  /**
   * True if selecting this item drills into a nested list (calls
   * `onBrowseRequest`). Defaults to `false` when omitted. Mutually
   * exclusive with `playable` (see above).
   */
  hasChildren?: boolean;
}

/**
 * Reason code for `onServiceReady` — distinguishes the very first
 * service-bind in a JS-process lifetime (`first-bind`) from a later
 * rebind that the lib detected by comparing the platform service
 * instance's identity against the previously-seen one
 * (`service-reborn`).
 *
 * **Android**: the platform foreground service can be killed by the
 * OS (memory pressure, doze, idle-bucket reaper) and re-created on
 * the next bind. The lib's auto-rebind watchdog reconnects and fires
 * `service-reborn`. Consumers typically respond by re-pushing the
 * browse snapshot (the new service instance starts with a fresh
 * `BrowseDataProvider`, so the cached snapshot is gone).
 *
 * **iOS**: no equivalent service-rebirth scenario — apps die fully
 * under memory pressure. The lib accepts the `onServiceReady`
 * registration for API parity but never invokes the callback on
 * iOS; consumers rely on `onCarConnect` for the iOS-side attach
 * signal.
 */
export type ServiceReadyReason = 'first-bind' | 'service-reborn';

/**
 * Closed set of section-tab icons the lib knows how to render in a
 * platform-native way. Consumers pick from this enum rather than
 * passing platform-specific symbol names so the icon set stays
 * guard-railed and the lib can swap implementations (SF Symbol on
 * iOS today, Material icon on Android once Android Auto exposes a
 * tab-icon API — currently `androidx/media#646`).
 *
 * String values are stable so consumer apps can persist them.
 */
export enum SectionIcon {
  Home = 'home',
  Library = 'library',
  Playlists = 'playlists',
  Favourites = 'favourites',
  Albums = 'albums',
  Artists = 'artists',
  Songs = 'songs',
  Genres = 'genres',
  RecentlyPlayed = 'recently-played',
  Radio = 'radio',
  Search = 'search',
  Downloaded = 'downloaded',
  Podcasts = 'podcasts',
}

/**
 * One section in a browse snapshot — a labelled group of items the
 * consumer pre-builds for fast cold-start presentation in CarPlay /
 * Android Auto. Sections become root-level tabs at the top of the
 * automotive UI (Apple Music + Spotify shape — up to 4 tabs on both
 * platforms; over-cap sections are truncated with a one-time warning).
 */
export interface BrowseSection {
  /** Stable identifier within the snapshot. Used as parentId when the user drills into the section. */
  id: string;
  /** User-facing label (e.g. "Recently Played"). Renders as the tab title. */
  title: string;
  /**
   * Optional tab icon. CarPlay renders the matching SF Symbol on the
   * tab bar; Android Auto does not yet expose a tab-icon API and
   * silently drops the value with an INFO log on first use.
   */
  icon?: SectionIcon;
  /**
   * Optional artwork URL shown on the synthetic root-level row that
   * represents this section in pre-tab-bar CarPlay fallbacks and on
   * any future Android Auto surface that exposes section artwork.
   * Same URL semantics as `BrowseItem.artworkUrl` — `file://` and
   * `http(s)://` are both honoured.
   */
  artworkUrl?: string;
  items: BrowseItem[];
}

/**
 * Full pre-computed browse tree pushed by the consumer via
 * `setBrowseSnapshot`. The native automotive surface caches this so
 * cold-start car connections (CarPlay scene attach, Android Auto bind,
 * Google Assistant wake) can render content immediately without a JS
 * bridge round-trip. When the consumer's underlying data changes, push
 * a fresh snapshot; the native side replaces its cache atomically and
 * notifies connected controllers (push semantics — the most-recent
 * `setBrowseSnapshot` call wins).
 *
 * Consumer-shape-agnostic: the lib never reads the contents of a
 * snapshot beyond what's needed to render the platform UI.
 */
export interface BrowseSnapshot {
  /** Identifier the consumer chooses for the root parent (e.g. 'root'). */
  rootId: string;
  sections: BrowseSection[];
}

// ============================================================
// Equalizer
// ============================================================

export interface EqualizerBand {
  frequencyHz: number;
  gainDb: number;
}

export interface EqualizerPreset {
  name: string;
  gains: number[];
  /** True for the 16 built-ins, false for user-saved presets. */
  builtin: boolean;
}

// ============================================================
// Visualizer
// ============================================================

/**
 * Allowed FFT bin counts. AAC / AVAudioEngine + Android Visualizer both
 * accept any power of 2 from 256 to 2048 in this family; the lib narrows
 * to that set so consumers can't pass an arbitrary number that the
 * native side then has to round or reject. Android Path A may serve a
 * smaller bin count when the device caps below the request — check the
 * frame's actual fft.byteLength at runtime.
 */
export type VisualizerFftSize = 256 | 512 | 1024 | 2048;

export interface VisualizerConfig {
  /**
   * FFT bin count. Higher values give finer frequency resolution but
   * increase per-frame compute on both sides of the bridge. 1024 is a
   * sensible default for animated UIs at 60 FPS.
   */
  fftSize: VisualizerFftSize;
  /**
   * Emit interval in milliseconds. Default 16 (~60 FPS). The native
   * processor performs the windowing + FFT + magnitude scaling on the
   * audio render thread once per emit window; values below ~8 ms can
   * starve the audio render budget at fftSize=2048 and are not
   * recommended. The lib accepts any positive integer — bound your
   * own value to ≥8 ms in production code.
   */
  updateIntervalMs: number;
  /**
   * When true, emit raw time-domain Float32 PCM samples alongside the
   * frequency-domain magnitudes. Defaults to false. Opt-in adds one
   * extra ArrayBuffer + memcpy per frame on the native side; consumers
   * that don't need oscilloscope/Lissajous-style waveform rendering
   * should leave this off.
   */
  includeSamples?: boolean;
}

export interface VisualizerFrame {
  /**
   * Float32 frequency-domain magnitudes, length = `fftSize / 2`.
   * Lowest bin first; magnitudes are unscaled (consumer chooses dB,
   * normalisation, smoothing).
   */
  fft: ArrayBuffer;
  /**
   * Float32 raw pre-window time-domain PCM samples, length = `fftSize`.
   * Present iff the subscribe `config.includeSamples === true`. The
   * Hann window applied internally for the FFT does NOT touch this
   * payload — consumers receive the same mono-mixed samples that fed
   * the FFT, suitable for oscilloscope / Lissajous / waveform displays.
   *
   * Samples are post-EQ on iOS (the FFT tap sits inside the Equalizer
   * chain) and pre-EQ on Android (the Tee sits inside the AudioSink
   * processor chain, before the OS-level audiofx Equalizer applies).
   * Consumers comparing visualisations across platforms should account
   * for this asymmetry — the iOS visualiser shows the EQ-shaped signal
   * the user hears; the Android one shows the pre-EQ source PCM.
   */
  samples?: ArrayBuffer;
  /** Stream sample rate in Hz (44100, 48000, …). */
  sampleRate: number;
}

/**
 * Why a Visualizer subscription cannot deliver frames. The library
 * does not request `RECORD_AUDIO` on Android — the FFT reads the
 * app's own decoded PCM via Media3's TeeAudioProcessor, so denial of
 * any OS audio-capture permission is irrelevant.
 *
 * Currently emitted:
 * - `hls-source`: iOS only — `MTAudioProcessingTap` does not fire for
 *   `.m3u8` HLS sources, so the FFT tap can't see the audio.
 *
 * Reserved (declared but not currently emitted by either platform —
 * kept in the union so the codegen continues to emit a non-trivial
 * enum and consumer apps can prepare exhaustive switches that future-
 * proof against the implementation surfacing them):
 * - `no-active-track`: would fire on subscribe-before-setQueue.
 * - `engine-released`: would fire on engine teardown mid-subscription.
 */
export type VisualizerErrorReason =
  | 'hls-source'
  | 'no-active-track'
  | 'engine-released'
  | 'visualization-disabled';


// ============================================================
// Cast
// ============================================================

/**
 * Cast protocol family. `local` is the on-device output route (speaker,
 * headphones, BT — the OS handles routing within this bucket). The
 * remote protocols (`airplay`, `chromecast`, `dlna`, `sonos`) carry the
 * lib's cast surface. `dlna` + `sonos` are reserved for future backends;
 * native implementations advertise actual support via
 * `CastManager.getSupportedProtocols()`.
 */
export type CastProtocol = 'local' | 'airplay' | 'chromecast' | 'dlna' | 'sonos';

/**
 * How the sender delivers audio to the receiver.
 *
 * - `pushPcm`: sender encodes + transmits PCM frames (AirPlay 1/2). Local
 *   playback engine continues to schedule and decode; only the audio sink
 *   swaps. Crossfade engine MUST degrade to gapless under push-PCM (the
 *   wire protocol has no sender-side cross-stream mixing).
 * - `pullUrl`: receiver fetches a URL the sender supplies (Chromecast,
 *   DLNA, Sonos). Local engine pauses; remote engine takes over scheduling
 *   + transport.
 */
export type CastStreamingModel = 'pushPcm' | 'pullUrl';

/** Hint to the picker UI for which icon to show. Strings are stable. */
export type CastDeviceKind =
  | 'phone'
  | 'speaker'
  | 'tv'
  | 'display'
  | 'speakerGroup'
  | 'unknown';

/**
 * Reason an active cast session ended without an explicit `disconnect()`.
 * Advisory — the app resumes local playback regardless.
 */
export type CastSessionEndReason =
  | 'userDisconnect'
  | 'stolen'
  | 'networkLoss'
  | 'suspended'
  | 'ended'
  | 'feedbackTimeout'
  | 'authFailed'
  | 'unknown';

/**
 * iOS Local Network permission state. Android always returns `granted`.
 */
export type CastLocalNetworkPermission = 'undetermined' | 'granted' | 'denied';

/**
 * Receiver capability bag. Drives picker UI decisions without protocol
 * branching — e.g. show a volume slider iff `supportsRemoteVolume`.
 */
export interface CastReceiverCapabilities {
  supportsRemoteVolume: boolean;
  streamingModel: CastStreamingModel;
  /**
   * True for AirPlay 2 (PIN/password pair-setup on first connect; persisted
   * auth key on subsequent connects). False for AirPlay 1 (legacy RSA),
   * Chromecast, DLNA, Sonos.
   */
  requiresPairing: boolean;
}

/**
 * A discovered receiver in the local-network device list. Stable id
 * namespaces by protocol (`"airplay:<mac>"`, `"chromecast:<deviceId>"`)
 * so the JS layer can de-dupe across discovery refreshes.
 *
 * `castProtocol` is named so (not `protocol`) because `protocol` is a
 * Swift-reserved keyword and Nitrogen's codegen doesn't escape it.
 * See NOTES.md §1.8.
 */
export interface CastReceiver {
  id: string;
  castProtocol: CastProtocol;
  name: string;
  iconHint: CastDeviceKind;
  capabilities: CastReceiverCapabilities;
  /** `"1"` / `"2"` for AirPlay; empty string when not applicable. */
  protocolVersion: string;
  /** Vendor model hint (e.g. `"HomePod"`, `"AppleTV5,3"`); empty if unknown. */
  modelName: string;
}

/**
 * The currently active audio route. `castProtocol === 'local'` means the
 * OS is routing to the on-device output stack (speaker, headphones, BT,
 * or system-managed AirPlay on iOS); `receiverId` is empty in that case.
 */
export interface CastRoute {
  castProtocol: CastProtocol;
  /** Empty string when `castProtocol === 'local'`. */
  receiverId: string;
  name: string;
  iconHint: CastDeviceKind;
  capabilities: CastReceiverCapabilities;
  /** `"1"` / `"2"` for AirPlay; empty string when not applicable. */
  protocolVersion: string;
}

export interface CastDiscoveryState {
  isDiscovering: boolean;
}

export interface CastSessionDiedEvent {
  /** Empty string if the session never reached connected state. */
  receiverId: string;
  reason: CastSessionEndReason;
}

export interface CastLocalNetworkPermissionEvent {
  state: CastLocalNetworkPermission;
}

/**
 * Options passed to `CastManager.connect`. AirPlay 1/2 pairing supplies
 * `password` on the second-phase connect (after the initial `authFailed`
 * rejection). Chromecast ignores the field.
 */
export interface CastConnectOptions {
  password: string;
}

/**
 * Configuration for {@link CastManager.configure}.
 *
 * @property deviceName Name advertised to receivers in the AirPlay RTSP
 *   handshake (`X-Apple-Client-Name`) and shown on approval dialogs /
 *   now-playing. Falls back to the OS device name when empty. No effect on
 *   Chromecast.
 * @property hideAirPlayComputers Hide AirPlay receivers running on Mac
 *   computers (model `Mac*` / `iMac*`) from discovery. macOS AirPlay
 *   Receiver only accepts Apple senders on the same Apple ID, so it is
 *   un-connectable from this library — surfacing it is a dead picker entry.
 *   Defaults to `true`. Android-only: iOS AirPlay routing is OS-managed, so
 *   the flag is ignored there.
 */
export interface CastConfigureOptions {
  deviceName: string;
  hideAirPlayComputers?: boolean;
}

/**
 * Active audio output classification. Cross-platform; covers on-device
 * outputs (phone speaker, BT, wired, USB, HDMI) AND cast targets. Picker
 * UIs read this to show "currently playing on AirPods" regardless of
 * whether cast discovery is running.
 *
 * - `speaker`: phone built-in loudspeaker.
 * - `receiver`: phone earpiece (voice-call speaker; rare for music).
 * - `wired`: 3.5mm / lightning / USB-C analog headphones, dock line-out.
 * - `usb`: USB DAC / USB-C digital headphones.
 * - `hdmi`: HDMI / HDMI-ARC / HDMI-eARC.
 * - `bluetoothA2DP`: classic Bluetooth audio profile.
 * - `bluetoothLE`: LE Audio / hearing aids / BLE speaker/broadcast.
 * - `bluetoothHFP`: Bluetooth hands-free profile (SCO).
 * - `carPlay`: iOS-only; phone is projecting audio via CarPlay.
 * - `androidAuto`: Android-only; audio routed to an automotive bus
 *   (AOSP `TYPE_BUS`). Phone-projected Android Auto over BT is
 *   indistinguishable from regular A2DP at the OS layer + reports as
 *   `bluetoothA2DP`.
 * - `airplay`: active AirPlay route (iOS OS-managed; Android lib-managed).
 * - `chromecast`: active Chromecast session.
 * - `unknown`: unrecognised output type.
 */
export type AudioRouteKind =
  | 'speaker'
  | 'receiver'
  | 'wired'
  | 'usb'
  | 'hdmi'
  | 'bluetoothA2DP'
  | 'bluetoothLE'
  | 'bluetoothHFP'
  | 'carPlay'
  | 'androidAuto'
  | 'airplay'
  | 'chromecast'
  | 'unknown';

/**
 * Snapshot of the currently active audio output. Read via
 * `CastManager.getCurrentAudioRoute()`; observed via
 * `CastManager.onAudioRouteChange()` or the `useAudioRoute()` hook.
 *
 * The route is independent of cast discovery state — knowing what BT
 * headphones the user has connected does not require a cast scan.
 */
export interface AudioRoute {
  kind: AudioRouteKind;
  /** User-facing name (e.g. `"AirPods Pro"`, `"Living Room"`, `"Phone speaker"`). */
  name: string;
  /**
   * Stable id when one exists (BT MAC address, cast `receiverId`); empty
   * string otherwise. Not guaranteed unique across reboots; consumers use
   * it for picker-row deduplication during a single discovery cycle.
   *
   * On Android 12+, BT MAC addresses require `BLUETOOTH_CONNECT` runtime
   * permission; without it `identifier` is empty for BT devices. Same
   * permission also gates the user-facing `name`.
   */
  identifier: string;
  /** `true` when audio is going somewhere other than the phone's own speaker. */
  isExternal: boolean;
  /**
   * `true` when the OS handles the route swap (BT, wired, USB, HDMI,
   * iOS AirPlay). `false` for lib-managed routes (Android AirPlay,
   * Chromecast on both platforms) — the lib drives the audio sink swap
   * or remote engine for those.
   */
  isSystemManaged: boolean;
}
