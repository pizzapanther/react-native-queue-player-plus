import AVFoundation

/// iOS TrackPlayer — Nitro HybridObject holding the AVQueuePlayer
/// instance + runtime config. This file declares the type and its
/// stored state; lifecycle (configure / destroy), transport, queue
/// mutation, skip, gapless, events, audio session, and lookahead-cache
/// methods live in the `TrackPlayer+*.swift` extensions.
class TrackPlayer: HybridTrackPlayerSpec {
  // MARK: - Stored state

  /// Periodic-time-observer firing rate for the progress event. Two
  /// ticks per second matches the JS-side onProgress contract and
  /// keeps backgrounded playback updates flowing without burning
  /// power on a faster cadence.
  internal static let progressIntervalSeconds: Double = 0.5

  /// Tolerance for "the loaded range has reached duration" — rounding can leave
  /// a sub-second gap when a track is fully downloaded.
  internal static let fullyBufferedEpsilonSeconds: Double = 1.0

  /// Sleep-timer tick cadence. 0.5s keeps the 10s linear fade smooth while
  /// staying cheap enough to run whether playback is playing or paused.
  internal static let sleepTimerTickInterval: TimeInterval = 0.5

  /// Serialises every access to player state: the AVQueuePlayer and the engine
  /// beneath it, the track list, the current index and everything derived from
  /// them. AVFoundation carries no main-thread requirement at the 16.0
  /// deployment floor — `AVPlayer`'s pre-iOS-16 notes are gone and the
  /// declarations are `NS_SWIFT_NONISOLATED` — so the player can live here and
  /// leave main free for UI, MediaPlayer, CarPlay and Cast.
  ///
  /// Main-only frameworks are still main-only: anything reaching
  /// `MPNowPlayingInfoCenter`, `MPRemoteCommandCenter`, CarPlay or the cast
  /// bridge hops to main with its payload already computed, never by calling
  /// back into player state from there.
  internal let playerQueue = DispatchQueue(
    label: "com.margelo.nitro.queueplayer.player")

  /// Identifies `playerQueue` from inside a block running on it.
  /// `DispatchQueue.sync` runs its body on the *calling* thread, so
  /// `Thread.isMainThread` answers a different question than "am I already
  /// serialised?" and is wrong in both directions here. The queue's own
  /// specific is the only test that survives a `sync` from main.
  internal let playerQueueKey = DispatchSpecificKey<ObjectIdentifier>()

  /// The underlying AVQueuePlayer. `nil` before configure(), torn down
  /// on destroy(). AVQueuePlayer specifically (not AVPlayer) because
  /// it owns queue management + gapless transitions — see
  /// NOTES.md section 5.
  internal var player: AVQueuePlayer?
  /// The active engine — `GaplessEngine` over `player`, or a
  /// `CrossfadeEngine` over its own two players (then `player` is nil).
  /// Transport, volume, speed, the installed run and seeks go through this
  /// surface; `player` is read directly only for the gapless-specific KVO
  /// (`installGaplessObservers`, `installEventObservers`) and for scalars the
  /// engine does not expose (`currentTime`, `timeControlStatus`).
  internal var engine: PlaybackEngine?

  /// Authoritative queue state — two parallel arrays (`tracks` +
  /// `queueItemIds`) owned by `QueueState` so the type system
  /// prevents either field from being mutated without the other.
  /// AVQueuePlayer consumes items forward-only; skip-backward
  /// rebuilds from `queueState.tracks`. `queueItemIds` is the
  /// lib-generated identity per position, stamped onto each
  /// AVPlayerItem so `matchTrackIndex` can resolve currentItem back
  /// to an index. Queue-position identity is never derived from
  /// `TrackItem` fields — only `track.url` is required + reliable
  /// on consumer-supplied data.
  internal var queueState = QueueState()

  /// Read-only view of the queue's TrackItem array. Mutations go
  /// through `queueState`'s API.
  internal var tracks: [TrackItem] { queueState.tracks }

  /// Read-only view of the parallel queue-item IDs. Mutations go
  /// through `queueState`'s API.
  internal var queueItemIds: [String] { queueState.queueItemIds }

  /// Index into `tracks` of the currently-playing item. -1 when empty
  /// or before the first play. Surfaced to JS via getCurrentTrackIndex.
  internal var currentTrackIndex: Int = -1

  /// Most recent PlayerConfig passed in via configure(). Defaults are
  /// empty — no custom headers, default UA. Used by AVQueueBuilder
  /// when creating new AVURLAssets.
  internal var config: PlayerConfig =
    PlayerConfig(
      httpHeaders: nil,
      userAgent: nil,
      placeholderArtworkUri: nil, autoRetries: nil,
      retryBackoffMs: nil,
      networkTimeoutMs: nil,
      audioContentType: nil,
      audioCategoryOptions: nil,
      voiceVocabularyDonationEnabled: nil,
      allowsExternalPlayback: nil,
      visualizationEnabled: nil,
      clampSeekToBuffered: nil,
      skipToPreviousBehavior: nil, lookaheadCacheMaxSizeMb: nil, lookaheadCacheEvictionPolicy: nil,
      progressUpdateIntervalMs: nil, backgroundProgressUpdateIntervalMs: nil, enableDiagnosticLogs: nil
    )

  /// Guards the mode scalars below and `voiceVocabularyDonationEnabled`.
  ///
  /// They are written on `playerQueue` like the rest of the player state, but
  /// unlike the rest they are also read from off it: the two Nitro getters
  /// answer on the JS thread, and the end-of-item notification captures the
  /// repeat mode on AVFoundation's thread before it hops. One small lock is
  /// what NOTES.md section 30 reserves for exactly this — a value that genuinely
  /// crosses queues and is too small to justify confinement — and it lets the
  /// getters answer without parking the JS thread behind whatever the queue is
  /// doing, up to a full rebuild.
  private let modeLock = NSLock()

  private var _repeatModeState: RepeatMode = .off
  internal var repeatModeState: RepeatMode {
    get { modeLock.lock(); defer { modeLock.unlock() }; return _repeatModeState }
    set { modeLock.lock(); defer { modeLock.unlock() }; _repeatModeState = newValue }
  }

  /// Canonical volume + playback speed, independent of the active
  /// engine. Setters update these and forward to the engine; getters
  /// read these (not `self.player`, which is nil under crossfade); the
  /// engine swap re-applies them so a gapless↔crossfade swap preserves
  /// the user's settings.
  internal var volumeState: Float = 1.0
  internal var playbackSpeedState: Float = 1.0

  /// Lock-screen / control-center remote-control config. Applied to
  /// `RemoteCommands` on set + re-applied on `configure`. Interval seek is
  /// performed natively from the `onCommand` handler.
  internal var remoteSkipMode: RemoteSkipMode = .track
  internal var remoteForwardInterval: Double = 15
  internal var remoteBackwardInterval: Double = 15

  /// Canonical pitch-correction mode, independent of the active engine.
  /// Re-applied to the engine at configure + engine swap so it survives
  /// a destroy→configure cycle and a gapless↔crossfade swap. Default
  /// `voice` (pitch preserved, cheapest correct option).
  private var _pitchCorrectionModeState: PitchCorrectionMode = .voice
  internal var pitchCorrectionModeState: PitchCorrectionMode {
    get { modeLock.lock(); defer { modeLock.unlock() }; return _pitchCorrectionModeState }
    set { modeLock.lock(); defer { modeLock.unlock() }; _pitchCorrectionModeState = newValue }
  }

  /// AVAudioSession wrapper — handles activate/deactivate,
  /// interruption (phone calls, Siri), and route change (headphones
  /// unplugged) notifications. Wired in configure, torn down on
  /// destroy. Interruption handlers pause + emit state change with
  /// reason `.interruption`; resume on `.endedShouldResume`.
  /// Route-change pauses on `.oldDeviceUnavailable` per Apple HIG.
  internal let audioSession = AudioSession()

  /// Owns the `MPNowPlayingInfoCenter` dictionary that drives the
  /// lock-screen / control-center / CarPlay surfaces. `configure` starts its
  /// 10 s periodic refresh; `destroy` clears it.
  internal let nowPlayingInfo = NowPlayingInfo()

  /// Owns the `MPRemoteCommandCenter` handler registrations that
  /// receive lock-screen / control-center / CarPlay / hardware
  /// transport events. Wired in `configure`, uninstalled in `destroy`.
  /// Each handler stamps `pendingStateChangeReason = .system` before
  /// routing into the public transport methods so the resulting state-
  /// change emit carries `.system`, distinguishing remote-command
  /// origin from in-app `.user` taps.
  internal let remoteCommands = RemoteCommands()

  /// Resolves `track.artworkUrl` to a UIImage for the lock-screen
  /// surface. The placeholder is the synchronous floor (already
  /// surfaced by `nowPlayingInfo.refreshAll`); `applyArtwork` swaps
  /// in the resolved bitmap when the network fetch lands. Single
  /// in-flight request — cancelled on track-change.
  internal let artworkResolver = ArtworkResolver()

  /// Lookahead cache + prefetcher. Built in `configure`, torn down in
  /// `destroy` (prefetcher first so its in-flight task can observe
  /// cancellation cleanly before the cache's URLSession is
  /// invalidated). Default 100 MB budget + 3-track lookahead window;
  /// `setLookaheadCache` lets consumers override at runtime.
  internal var lookaheadCache: LookaheadCache?
  internal var lookaheadCachePrefetcher: LookaheadCachePrefetcher?

  /// Live lookahead cache config — initialised from defaults on
  /// first configure, mutated by `setLookaheadCache`.
  /// Persists across destroy → configure so a consumer who set
  /// custom values once doesn't lose them on a reconfigure cycle.
  internal var lookaheadConfig: LookaheadCacheConfig = LookaheadCacheConfig(
    enabled: true,
    lookaheadCount: Double(LookaheadCachePrefetcher.DEFAULT_LOOKAHEAD_COUNT),
    allowsCellularAccess: true
  )

  /// Cache-status callback registered via `onCacheStatusChange`.
  /// Single-slot — the JS consumer owns the subscription via the
  /// returned unsubscribe closure.
  internal let cacheStatusListeners = ListenerRegistry<(CacheStatus) -> Void>()
  internal let nowPlayingFormatListeners =
    ListenerRegistry<(Variant_NullType_NowPlayingFormat?) -> Void>()

  /// Cached snapshot of the active item's audio format. Returned
  /// synchronously by `getNowPlayingFormat()`. `nil` between queue-end
  /// (or pre-first-track) and the first successful resolve.
  internal var lastEmittedFormat: NowPlayingFormat?

  /// Identity key for `lastEmittedFormat`'s source AVPlayerItem. The
  /// async resolver discards results whose source item is no longer
  /// current and whose generation token has been superseded.
  internal var lastEmittedFormatItemId: ObjectIdentifier?

  /// Monotonic generation token bumped on every refresh request. Any
  /// in-flight extractor Task whose captured token mismatches the
  /// current value drops its result — guards against the cache-hit race
  /// where `\.status == .readyToPlay` fires synchronously from the
  /// status-observer bootstrap before `handleCurrentItemDidChange`
  /// runs, producing two parallel resolves for the same item.
  internal var nowPlayingFormatRequestGen: UInt64 = 0

  /// True while a `reresolveNowPlayingFormatForBitrate` Task is mid-flight.
  /// Access-log notifications fire multiple times per second during
  /// streaming; without this gate every fire that lands while an extract
  /// is still running would spawn another detached Task. Read and written on
  /// `playerQueue` only — set before the Task launches, cleared via `defer`
  /// inside the queue-confined completion (covers success, early-return, and
  /// any future error path uniformly).
  internal var bitrateReresolveInFlight: Bool = false
  /// Re-resolves are counted per item: a stream whose server never declares
  /// a bitrate would otherwise run the extractor on every access-log entry
  /// for the life of the track.
  internal var bitrateReresolveItemId: ObjectIdentifier?
  internal var bitrateReresolveAttempts = 0
  internal static let maxBitrateReresolvesPerItem = 3

  /// Mode-switch state machine — same matrix as the Android
  /// counterpart in `PlaybackModeStateMachine.kt`. Driven by
  /// `setPlaybackMode`; `getPlaybackMode` returns `state.active`.
  internal let playbackModeStateMachine = PlaybackModeStateMachine()

  private var _playbackModeState: PlaybackModeStateMachine.State =
    PlaybackModeStateMachine.initial
  internal var playbackModeState: PlaybackModeStateMachine.State {
    get { modeLock.lock(); defer { modeLock.unlock() }; return _playbackModeState }
    set { modeLock.lock(); defer { modeLock.unlock() }; _playbackModeState = newValue }
  }

  // MARK: - Events — callback storage + observer state
  //
  // Callbacks live in `ListenerRegistry` instances, which serialise their own
  // add/remove/snapshot; they are invoked on `playerQueue`, where every
  // observer body hops before reading state. Nitro handles cross-thread
  // delivery into JS — our job is just to call them with the right values.
  //
  // State + track change flow out of KVO. Progress flows out of
  // `addPeriodicTimeObserver` at `progressIntervalSeconds`. Error flows out
  // of `AVPlayerItemFailedToPlayToEndTime`. Queue-end is decided by
  // `applyEndVerdict` when an engine reports that it drained.
  // Multi-listener registries — each `onXxx(callback)` registers an
  // entry, returns a dispose closure that removes by id. Lets
  // multiple consumers (Player UI, harness, test runner) subscribe
  // simultaneously without clobbering each other.
  internal let stateChangeListeners = ListenerRegistry<(PlayerState, StateChangeReason) -> Void>()
  internal let trackChangeListeners = ListenerRegistry<(TrackItem?, Double, TrackChangeReason, Double?) -> Void>()
  internal let queueChangeListeners = ListenerRegistry<(QueueChangeDelta, Double, QueueChangeReason) -> Void>()
  internal let progressListeners = ListenerRegistry<(PlayerProgress) -> Void>()
  internal let milestoneListeners = ListenerRegistry<(Double, Double) -> Void>()
  /// Per-playthrough 25/50/75/90% milestone state. Confined to the queue that
  /// serialises player state
  /// (mutated only from the progress tick, `seekTo`, and the track-change
  /// delegates — all on that queue), so no extra synchronisation is needed.
  internal var milestoneTracker = MilestoneTracker()
  /// Throttles `onProgress` JS emission to the configured foreground /
  /// background cadence. Confined to the queue that serialises player state
  /// (mutated only from the progress
  /// tick + cast push + the app-lifecycle notifications, all on that queue).
  internal var progressEmissionGate = ProgressEmissionGate()
  /// NotificationCenter tokens for the `didEnterBackground` /
  /// `willEnterForeground` observers that drive `progressEmissionGate`'s
  /// background state. Registered per instance in `configure()` and removed in
  /// `internalDestroy()` — NOT tied to the AVQueuePlayer event observers, whose
  /// install/teardown runs on every engine swap.
  internal var appLifecycleObservers: [NSObjectProtocol] = []
  internal let errorListeners = ListenerRegistry<(PlaybackError) -> Void>()
  internal let queueEndListeners = ListenerRegistry<() -> Void>()
  // SkipCapability struct — wraps (canSkipPrevious, canSkipNext).
  // Workaround: a struct rather than two boolean callback args, because
  // Nitrogen 0.35.4 miscompiled a `(bool, bool) -> void` callback.
  // `SkipCapability` is the public callback payload.
  // TODO: monitor upstream for fixes — no margelo/nitro issue found.
  internal let skipCapabilityListeners = ListenerRegistry<(SkipCapability) -> Void>()

  /// Last-emitted skip capability tuple. Native owns the truth; the
  /// JS layer is a passive observer. Recomputed via
  /// `recomputeCapabilities()` at every `tracks` / `currentTrackIndex`
  /// / `repeatModeState` write site, with dedup —
  /// the listener fires only when the tuple actually changes. Single
  /// reference write means JS callers reading both fields via
  /// `getSkipCapability()` see a consistent pair without a torn-read
  /// risk; the legacy `getCanSkipNext` / `getCanSkipPrevious` getters
  /// delegate to this same struct.
  internal var cachedSkipCapability: SkipCapability =
    SkipCapability(canSkipPrevious: false, canSkipNext: false)

  // Buffer state — discrete empty/buffering/stalled/full for the active track, derived
  // from the item's native playback-buffer flags. Recomputed on the gapless
  // item's buffer KVO (sub-tick latency) and on the progress tick (covers the
  // crossfade engine + safety net); emitted only on an actual change.
  internal let bufferStateListeners = ListenerRegistry<(BufferState) -> Void>()
  internal var currentBufferState: BufferState = .empty
  internal var lastEmittedBufferState: BufferState?
  // The lib-generated queueItemId of the item `lastEmittedBufferState` was
  // computed for. A newly seated item resets the emitted baseline so its buffer
  // state is announced fresh even when it settles to the same discrete state the
  // previous item ended on (e.g. a `.full` queue reloaded to another `.full`
  // local track, where no intermediate sample is caught). Keyed on the
  // queueItemId, not the AVPlayerItem's object identity, so a reused pointer
  // address can never alias two different items. Without this a reload emits
  // nothing to a subscriber.
  internal var lastBufferStateQueueItemId: String?
  internal var bufferEmptyObserver: NSKeyValueObservation?
  internal var bufferKeepUpObserver: NSKeyValueObservation?
  internal var bufferFullObserver: NSKeyValueObservation?
  /// Fully-buffered — the whole track has downloaded. Distinct from
  /// `BufferState.full` ("keeps up"). Recomputed on the buffer-full KVO + the
  /// progress tick + track change; emitted on change. Reset per track.
  internal let fullyBufferedListeners = ListenerRegistry<(Bool) -> Void>()
  internal var currentFullyBuffered = false
  internal var lastEmittedFullyBuffered: Bool?
  /// True once the current track has actually reached a playing state — the
  /// discriminator between an initial `buffering` load and a mid-playback
  /// `stalled` rebuffer. Reset on track change / stop / teardown.
  internal var hasStartedPlaying = false
  /// Lib-level playback intent (play() → true, pause()/stop() → false). A
  /// not-full buffer only reads as `stalled` while playback is both started and
  /// intended; a paused load reads as `buffering`.
  internal var wantsToPlay = false

  /// Whether the consumer opted into Siri vocabulary donation. Taken from the
  /// first `configure()` and fixed for the life of the process.
  ///
  /// The entitlement it stands for is decided when the app is signed, so a
  /// later change could not mean anything; ignoring every later call keeps a
  /// re-`configure()` from re-arming a path that terminates a host without the
  /// capability.
  ///
  /// Written on `playerQueue` and read on the JS thread, so it takes the same
  /// lock the mode scalars do.
  private var _voiceVocabularyDonationEnabled = false
  private var voiceVocabularyDonationPinned = false
  internal var voiceVocabularyDonationEnabled: Bool {
    modeLock.lock(); defer { modeLock.unlock() }
    return _voiceVocabularyDonationEnabled
  }

  /// Record the donation opt-in. Ignores every call after the first.
  internal func pinVoiceVocabularyDonation(_ enabled: Bool) {
    modeLock.lock(); defer { modeLock.unlock() }
    guard !voiceVocabularyDonationPinned else { return }
    voiceVocabularyDonationPinned = true
    _voiceVocabularyDonationEnabled = enabled
  }

  /// Sleep-timer state machine (wall-clock countdown, 10s fade, 60s tail rule).
  /// Player/timer-free — this class drives it from `sleepTimerTimer` and applies
  /// the returned fade/pause decision to the engine.
  internal var sleepTimerCore = SleepTimerCore()
  internal let sleepTimerListeners = ListenerRegistry<(SleepTimerState) -> Void>()
  /// Repeating 0.5s tick source driving `sleepTimerCore`, which holds the
  /// wall-clock deadline. Runs whether playback is playing or paused, so it
  /// can't piggyback on the progress observer.
  internal var sleepTimerTimer: DispatchSourceTimer?
  /// True while a fade is in progress — the engine volume is being driven below
  /// `volumeState`. Cleared when the fade restores full volume or the timer
  /// tears down, so `volumeState` (and `getVolume`) stay truthful.
  internal var sleepTimerFading = false

  /// Token from `AVPlayer.addPeriodicTimeObserver`, paired with the
  /// specific `AVQueuePlayer` instance that issued it. `removeTime
  /// Observer` has to be called on the issuing player — keeping the
  /// reference here guards against a tear-down call arriving after
  /// `self.player` has been replaced by a later `configure`.
  internal var progressTimeObserver: (player: AVQueuePlayer, token: Any)?

  /// Engine-agnostic progress fallback — used when `self.player`
  /// is nil (CrossfadeEngine active). Polls
  /// `engine.currentPositionSeconds` at the same 0.5s cadence the
  /// `AVPlayer.addPeriodicTimeObserver` would. Started on engine
  /// swap to a non-AVQueuePlayer backend; stopped on swap back to
  /// gapless or on destroy.
  internal var progressFallbackTimer: DispatchSourceTimer?
  /// Last position published by the CrossfadeEngine fallback-timer progress
  /// path. Lets a paused tick emit once when the playhead moves (seek / stop)
  /// without re-publishing identical tuples during a steady pause.
  internal var lastEmittedCrossfadePosition: TimeInterval = -1

  /// KVO on `player.timeControlStatus`. Fires whenever AVPlayer
  /// transitions between `.paused`, `.waitingToPlayAtSpecifiedRate`,
  /// and `.playing` — translated into our PlayerState enum.
  internal var timeControlStatusObserver: NSKeyValueObservation?

  /// Dedup key for `errorListeners` fires. Keyed on
  /// `(queueItemId, standardized code)` so AVPlayer-internal item
  /// rebuilds during retries do not bypass dedup. A DIFFERENT error
  /// on the same item still surfaces because the code is part of the
  /// key. Cleared on track change, setQueue, clearQueue, destroy,
  /// and on retry-initiation so the retry-then-fail can fire a
  /// fresh error.
  internal var lastErrorQueueItemId: String?
  internal var lastErrorCode: PlaybackErrorCode?

  /// `queueItemId` of every track whose playback error has been reported to
  /// the consumer and not yet recovered.
  ///
  /// A set, not one slot: several tracks of an installed run can be failing at
  /// once — a window of unplayable sources does exactly that — and a single
  /// slot cannot hold them. Each fire replaces the last, so a later fire for a
  /// track already reported passes the check and reports again — one broken
  /// queue then hands the listener an unbounded stream of `onError`.
  ///
  /// Written where `dispatchErrorOrRetry` hands an `onError` out and where
  /// `stopOnDroppedTrack` reports a track the player discarded, so it names a
  /// track the consumer has been told about and nothing has recovered. The
  /// queue neither advances past that track nor puts it back until the track's
  /// entry is cleared, which `play()`, `retry()`, `retryFailedItem`, a track
  /// change to a different track, a skip that seats the position, `setQueue`,
  /// `clearQueue`, `destroy` and a forced `fullRebuildPlayerQueue` all do.
  /// Deliberately NOT a state emit of `.playing`: a source the player keeps
  /// re-attempting flips to playing between attempts, so clearing there would
  /// reopen the report for a track already reported.
  ///
  /// Separate from `lastErrorQueueItemId` even though both name a failing
  /// track: that one exists to dedup repeat reports and is cleared whenever a
  /// repeat would be wanted again, which is not the same question as whether
  /// the queue is still stopped.
  internal var reportedFailureQueueItemIds: Set<String> = []

  /// `queueItemId` of the item whose automatic attempt is scheduled but has
  /// not run yet.
  ///
  /// A failed item is dropped from the player queue, which drains the engine
  /// and re-seats the item, and that re-seat fails again within milliseconds.
  /// The consumer asked for one attempt and has not had it, so a failure
  /// naming this item is not reported while the flag names it.
  internal var pendingRetryQueueItemId: String?

  /// Serves remote tracks from the lookahead cache when it holds them and from
  /// the origin when it does not. Built with the cache and kept until
  /// `destroy`, across the cache being disabled and re-enabled, because
  /// seated items address it by port and are never re-pointed. nil means no
  /// cache was ever built in this session.
  internal var readThroughServer: ReadThroughServer?

  /// The server new items are routed through: the live one while the cache
  /// is enabled, nil while it is disabled so those items bind their origin.
  /// Items built earlier keep whatever they were built on; the server serves
  /// a disabled cache's requests from the origin (`LookaheadCache.cachedURL`
  /// answers nil while disabled).
  internal var routingServer: ReadThroughServer? {
    lookaheadConfig.enabled ? readThroughServer : nil
  }

  /// The cache instance `readThroughServer` was built to read through; a
  /// different instance at the next enable means a new server.
  internal weak var readThroughServerCache: LookaheadCache?

  /// Set when the read-through server restarts: installed routed items may
  /// still name the replaced listener, or hold asset loads that failed against
  /// it. Cleared when a whole run of fresh items is installed
  /// (`reinstallWholeSlice`, the engine swap).
  internal var readThroughReinstallPending = false

  /// Positions whose routed failure has already been retried on a live
  /// read-through listener (after a restart, or because the item named a
  /// replaced one), so a second failure is reported rather than retried again. Not cleared where `reportedFailureQueueItemIds` is: the
  /// retry's forced rebuild empties that set, which would recover forever.
  /// Cleared by `setQueue`, `play()`, `retry()`, a skip, and reaching `.playing`.
  internal var routedRecoveryAttemptedQueueItemIds: Set<String> = []

  /// Auto-retry counter per queue position, keyed on `queueItemId`
  /// (the lib-generated UUID, never a consumer field). Decremented on each
  /// transient-error fire; when 0, the error surfaces to JS.
  /// Re-populated on setQueue / addToQueueBody from
  /// `effectiveAutoRetries()` (clamped 0..5; default 1), and restored to the
  /// same configured allowance by `play()`'s stuck-recovery and by `retry()`.
  internal var retryAttemptsRemaining: [String: Int] = [:]

  /// Last PlayerState emitted — used to suppress duplicate emissions.
  /// KVO fires for any status transition, but our PlayerState enum
  /// collapses several AVPlayer states so re-emission is possible.
  internal var lastReportedState: PlayerState = .none

  /// True between an `AVPlayerItemPlaybackStalled` and the buffer refilling.
  ///
  /// Playback runs with `automaticallyWaitsToMinimizeStalling = false` once an
  /// item is ready, which is load-bearing for gapless transitions — but it also
  /// means AVPlayer will **not** resume by itself after a buffer underrun. It
  /// sits at rate 0 with `timeControlStatus == .paused`, indistinguishable from
  /// a user pause, until something re-issues play. This flag is what remembers
  /// to.
  internal var isRecoveringFromStall = false

  /// True while an audio-session interruption is in force.
  ///
  /// `wantsToPlay` records what the *user* asked for; this records whether the
  /// *system* currently permits playback. The recovery paths — a drained-window
  /// refill, a repeat-queue wrap, an auto-retry — resume on intent alone, and
  /// without this they would start audio during a phone call, or restart it
  /// after an interruption the user chose not to resume.
  internal var isInterrupted = false

  /// Watches for the network returning after an outage, so a track that
  /// exhausted its retries offline can recover without the user noticing and
  /// pressing play. Built at `configure`, torn down at `destroy`.
  internal var networkMonitor: PlaybackNetworkMonitor?

  /// End-of-queue state. iOS has no native "ended" player state, so this is set
  /// when the queue drains with repeat off (`detectQueueEnd` / `enginePlaybackEnded`)
  /// and drives the terminal `.ended` in `PlayerStateDerivation` for BOTH
  /// engines. Cleared when playback resumes (`emitState(.playing)`), a new track
  /// loads (`dispatchTrackChange`), on seek, and on destroy. Confined to the
  /// queue that serialises player state.
  internal var reachedQueueEnd = false

  /// User-facing reason carrier for the `onTrackChange(_, _, reason)`
  /// emission. When a skip method (or `setQueue` /
  /// `applyNewCurrentIndex`) stashes a value here, the subsequent
  /// `\.currentItem` KVO fire reads it and emits with the right
  /// hint (`.userSkipNext`, `.userSkipPrevious`, `.userSkipToIndex`,
  /// `.queueReplaced`, etc). Consumed on a non-nil transition; the
  /// `isMutatingPlayerQueue + currentItem == nil` early-return in
  /// `handleCurrentItemDidChange` skips the intermediate `nil` fire
  /// inside a rebuild so the reason isn't stolen by the wrong fire.
  /// Defaults to `.autoAdvance` when nil at consumption time.
  ///
  /// Reason-carrier only — the `matchTrackIndex` resync gate lives
  /// on `didPlayToEndPending` so chain-advance bounces never re-run
  /// the resync.
  internal var pendingTrackChangeReason: TrackChangeReason?

  /// Counterpart to `pendingTrackChangeReason` for state transitions.
  /// `play` / `pause` / `stop` / `seekTo` stash `.user` before calling
  /// the native method; the `timeControlStatus` KVO picks it up to
  /// attribute the resulting state change. Default is `.system` when
  /// nothing has stashed a reason (covers buffering, auto-advance
  /// end-of-queue; AudioSession-driven interruptions and route
  /// changes carry their own reasons).
  internal var pendingStateChangeReason: StateChangeReason?

  /// Bridges receiver-driven cast session state into the lib's
  /// existing event surface. Lazily constructed and held for the
  /// `TrackPlayer`'s lifetime; `start()` on configure, `stop()` on
  /// destroy.
  internal lazy var castEventBridge = CastEventBridge(owner: self)

  /// Rises whenever a mutation is actively rearranging the player's
  /// internal queue (setQueue, addToQueue, removeFromQueue,
  /// moveInQueue, clearQueue, applyNewCurrentIndex,
  /// fullRebuildPlayerQueue). Every `removeAllItems` + `insert` pair
  /// produces an intermediate `currentItem == nil` KVO fire — the
  /// event dispatch path checks this flag and skips the intermediate
  /// fire so `onQueueEnd` isn't falsely emitted mid-rebuild and
  /// `pendingTrackChangeReason` isn't consumed by the wrong fire.
  /// Nested (mutations calling each other) safe because each nest
  /// uses `performingMutation` which restores the prior value on exit.
  internal var isMutatingPlayerQueue: Bool = false

  /// Bumped once per queue mutation, on `playerQueue`.
  ///
  /// A mutation's receiver round-trip runs after the mutation itself and can
  /// outlive it, so in a burst the earlier round-trip would otherwise push a
  /// queue — or an index resolved against a queue — that no longer exists.
  /// Work that outlives its mutation captures this value and re-checks it
  /// before acting on the live queue.
  ///
  /// Wraps via `&+=`; equality of a captured value against the live one is all
  /// that is ever asked, so wrapping is harmless.
  internal var queueMutationGeneration: UInt64 = 0

  /// Set when a queue mutation's engine work was held back because a
  /// crossfade was scheduled or running.
  ///
  /// The queue model is authoritative and is updated at once either way; this
  /// only records that the engine has not caught up. It carries no detail
  /// because none is needed — the reconcile re-derives what the engine should
  /// hold from the model, so one flag collapses any number of mutations made
  /// during one fade.
  ///
  /// Dropped on engine swap: the new engine is installed from the model.
  internal var engineReconcileDeferred: Bool = false

  /// The receiver work for the most recently enqueued mutation. Each mutation
  /// chains its own behind this one so the receiver sees mutations in the
  /// order they were made.
  ///
  /// Ordering is the whole contract: `addToQueue`, `removeFromQueue` and
  /// `moveInQueue` send index deltas resolved against the queue as it stood
  /// before their own mutation, so they are only correct against a receiver
  /// that has already applied everything before them. Two back-to-back
  /// unstructured Tasks have no ordering relationship, which is what this
  /// replaces. Written and read on `playerQueue`.
  internal var castMirrorChain: Task<Void, Never>?

  /// Increments once per queue-change emit; a gap tells a subscriber it missed one.
  internal var queueRevision: Int = 0

  // MARK: - Gapless — observer state
  //
  // AVQueuePlayer does inter-track transitions for free when three
  // conditions hold: (1) `actionAtItemEnd == .advance` (AVQueuePlayer's
  // default; set explicitly in configure for resilience), (2) the
  // next AVPlayerItem is preloaded in player.items() before the
  // current ends (AVQueueBuilder + our surgical inserts keep that
  // invariant), and (3) `automaticallyWaitsToMinimizeStalling == false`
  // so the player doesn't pause at the boundary waiting for minimum
  // buffer on the new item.
  //
  // The flip-pattern: start with stalling=true so the *first* track
  // buffers properly
  // before playback begins; once the first item reaches readyToPlay,
  // flip stalling=false so every subsequent inter-track transition
  // is tight. We re-arm on setQueue/clear because a fresh queue's
  // first track needs the same initial-buffer leniency.
  internal var currentItemObserver: NSKeyValueObservation?
  internal var currentItemStatusObserver: NSKeyValueObservation?
  // Duration can resolve a beat AFTER `.status` reaches `.readyToPlay`; observe
  // it so the lock-screen duration lands even in that race.
  internal var currentItemDurationObserver: NSKeyValueObservation?
  internal var gaplessFlipArmed: Bool = true

  /// Set to true by the `AVPlayerItemDidPlayToEndTime` notification
  /// handler when an item finishes playback naturally. Consumed by
  /// `handleCurrentItemDidChange` as the SOLE signal for "this is a
  /// real auto-advance" — gates the `matchTrackIndex` resync so
  /// AVQueuePlayer chain-advance through unbuffered items (which
  /// does NOT produce a DidPlayToEndTime notification) cannot
  /// overwrite the user's authoritative `currentTrackIndex`.
  internal var didPlayToEndPending: Bool = false

  /// Last `queueItemId` for which `trackChangeListeners` were fired.
  /// Compared against the current item's `queueItemId` in
  /// `handleCurrentItemDidChange` to dedup redundant emissions
  /// across the chain-advance bounce + observer-reinstall classes.
  /// Reset to nil on `destroy` (the next configure starts fresh).
  ///
  /// Why queueItemId not currentTrackIndex: setQueue replaces the
  /// queue but may keep `currentTrackIndex == 0`; comparing index
  /// alone would suppress the legitimate trackChange for the new
  /// queue's first item. queueItemId differs across queue
  /// replacements (UUID-per-item-at-construction), so the gate
  /// fires emissions correctly for setQueue + skip + auto-advance
  /// while still suppressing redundant fires.
  internal var lastEmittedQueueItemId: String? = nil

  /// Cached classification of the active track's playback source.
  /// Recomputed in `dispatchTrackChange` whenever the active track
  /// changes (queue replace, skip, auto-advance, shuffle). Read by
  /// `getCurrentTrackSource()` for sync JS access.
  internal var currentTrackSource: TrackSource? = nil

  // Records mach-time at item-ended so the next auto-advance dispatch
  // can compute a silence-gap delta, surfaced as `onTrackChange`'s
  // `transitionGapMs`. Always-on (one UInt64 store on a low-frequency
  // event). Verbose `[RNQP-GAPLESS]` diagnostic NSLogs against these
  // timestamps are gated separately under `#if DEBUG`.
  internal var gaplessGapPrevEndedAtNs: UInt64 = 0

  // Captured at the top of `handleCurrentItemDidChange` so the
  // `transitionGapMs` measurement reflects pure iOS scheduling latency
  // between `AVPlayerItemDidPlayToEndTime` and the `\.currentItem` KVO fire,
  // not the synchronous lib-side work `dispatchTrackChange` does
  // before reaching the emit site (matchTrackIndex iteration,
  // trackChangeListeners fan-out, refreshNowPlayingFormatForActiveItem,
  // nowPlayingInfo.refreshAll, artworkResolver.resolve prelude). That
  // intra-method work is sometimes 10s of ms on a cold first
  // transition; measuring through it would conflate "scheduler
  // latency" with "lib processing time" — the test contract is the
  // former.
  internal var gaplessGapMeasuredAtNs: UInt64 = 0
  #if DEBUG
  // Debug-only timestamps backing the verbose `[RNQP-GAPLESS]` NSLog
  // emits. Compiled out entirely in Release builds.
  internal var gaplessLogLastEndedAtNs: UInt64 = 0
  internal var gaplessLogLastChangedAtNs: UInt64 = 0
  #endif

  /// Stamps `playerQueue` with its own identity so a block running on it can
  /// recognise the queue it is on. Instance-scoped: two TrackPlayers must not
  /// answer "already serialised" for each other's queue.
  override init() {
    super.init()
    playerQueue.setSpecific(key: playerQueueKey, value: ObjectIdentifier(self))
  }

  /// libdispatch retains an armed source, so one left running is never
  /// disposed — it keeps waking its queue for the life of the process.
  /// `cancel()` is safe from any thread, unlike `Timer.invalidate()`.
  deinit {
    progressFallbackTimer?.cancel()
    sleepTimerTimer?.cancel()
  }
}
