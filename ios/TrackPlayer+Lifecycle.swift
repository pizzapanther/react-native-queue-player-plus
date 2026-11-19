import AVFoundation
import NitroModules

extension TrackPlayer {
  // MARK: - Lifecycle

  func configure(config: PlayerConfig) throws -> Promise<Void> {
    // `[self]` explicit: this body holds `self` strongly for its run, and the
    // long-lived callbacks it installs below capture `[weak self]`; the
    // explicit outer capture is what tells the compiler the mix is intended.
    return enqueue { [self] in
      // Universal auto-destroy contract: every `configure()` call
      // tears down any prior engine, audio session, observers, and
      // pending pipeline state before reinitialising. On the first
      // configure of a session the gate inside `internalDestroy`
      // short-circuits when `self.engine == nil`, so only the timer +
      // sleep-timer teardown runs; on subsequent calls it is a real
      // teardown. Effect: configure is always a clean-slate operation;
      // consumers don't need to remember to call `destroy()` before
      // re-configuring.
      // Capture the prior eviction policy before overwriting config — a change
      // clears the rebuilt cache below so it starts fresh under the new policy
      // (infrequent; a cache's eviction ordering is construction-fixed).
      let previousEvictionPolicy = self.config.lookaheadCacheEvictionPolicy ?? .lru
      self.internalDestroy()
      self.config = config
      // First, so the rest of configure is logged when diagnostics are on.
      DiagnosticLog.isEnabled = config.enableDiagnosticLogs ?? false
      DiagnosticLog.player.log("configure")
      let evictionPolicyChanged =
        previousEvictionPolicy != (config.lookaheadCacheEvictionPolicy ?? .lru)
      // Seed the progress-emission throttle from config and start observing
      // app background/foreground. `internalDestroy()` above removed any
      // prior observers, so this re-registers cleanly on every configure.
      self.applyProgressEmissionIntervals()
      self.installAppLifecycleObservers()
      // Pin-at-configure visualization-disabled flag. Mirrored to
      // the AudioTapProvider singleton so `Visualizer.subscribe`
      // and the Visualizer Consumer's `wantsTap` predicate can
      // read it live. Default `true` preserves existing behaviour.
      AudioTapProvider.shared.setVisualizationEnabled(
        config.visualizationEnabled ?? true
      )
      // Taken from the first call and never re-read: the Siri entitlement this
      // stands for is fixed when the app is signed, so a later value could not
      // mean anything.
      self.pinVoiceVocabularyDonation(
        config.voiceVocabularyDonationEnabled ?? false
      )
      // The queue mirror is only maintained while a car is attached, so
      // rebuild it the moment one attaches.
      CarPlayCoordinator.shared.onCarAttached = { [weak self] in
        guard let self else { return }
        // The car scene attaches on main, and the mirror is built from the
        // track list — so it is built on the queue that owns it. Fires from
        // `setCarConnected(true)`, so the car is attached by construction.
        self.playerQueue.async {
          self.syncCarPlayQueueMirror(
            self.buildCarPlayQueueSnapshot(), carAttached: true)
        }
      }
      let engine = GaplessEngine(
        allowsExternalPlayback: config.allowsExternalPlayback ?? false)
      self.engine = engine
      // configure() always rebuilds the default GaplessEngine, so the mode
      // state must reset to match. Otherwise getPlaybackMode() keeps
      // reporting a stale crossfade selection after a clean-slate
      // reconfigure while the engine is actually gapless — and a subsequent
      // setPlaybackMode(.crossfade) would no-op against that stale state and
      // never rebuild the CrossfadeEngine.
      self.playbackModeState = PlaybackModeStateMachine.initial
      let player = engine.player
      self.player = player
      self.gaplessFlipArmed = true
      self.installGaplessObservers(on: player)
      self.installEventObservers(on: player)
      // Re-apply persisted config to the fresh engine. These survive a
      // destroy→configure cycle and may be set before the first
      // configure, so the first engine must pick them up — matching the
      // engine-swap path. Speed is seeded (not set) so it doesn't start
      // the paused player via AVPlayer.rate; play() applies it on the
      // first resume.
      engine.setRepeatMode(self.repeatModeState)
      engine.setVolume(self.volumeState)
      engine.seedPlaybackSpeed(self.playbackSpeedState)
      engine.setPitchCorrectionMode(self.pitchCorrectionModeState)
      self.wireAudioSession()
      // Start observing receiver-driven cast session state; idempotent
      // on re-configure (no-op if already started).
      self.castEventBridge.start()
      // Activate the lockscreen mirror for cast sessions. Subscribes
      // through `PlaybackStateRouter.shared` — fires on every
      // session-active transition.
      CastNowPlayingController.shared.start()
      // The provider assigns `AVPlayerItem.audioMix` and walks the player's
      // items to do it, so its engine-facing work belongs on the queue that
      // serialises player access. Set before wiring, so the wire itself
      // lands there. The provider is a process-wide singleton, hence the
      // weak capture — teardown puts the target back on main.
      AudioTapProvider.shared.setMixDispatchTarget(
        self.playerQueue,
        isCurrent: { [weak self] in self?.isOnPlayerQueue ?? false })
      // Wire the shared audio-tap provider onto the engine. The
      // provider attaches an MTAudioProcessingTap-backed
      // AVAudioMix to each queued AVPlayerItem when any
      // registered consumer (EQ, visualizer) wants the tap.
      AudioTapProvider.shared.wireToPlaybackEngine(engine)
      // Notify subscribers when a tap-side RG extract lands so any
      // `onNowPlayingFormatChange` listener picks up the new RG
      // values immediately. `getNowPlayingFormat()` reads RG live
      // from `AudioTapProvider`, so the synchronous read path
      // doesn't depend on this callback — it's purely for change-
      // notification subscribers.
      AudioTapProvider.shared.onItemReplayGainDataPopulated = { [weak self] item in
        guard let self else { return }
        self.emitCurrentNowPlayingFormat(for: item)
      }
      // Category only. Claiming the session is deferred to the first play, so
      // merely loading the module does not stop whatever else the user has
      // playing — a consumer app calls configure() at launch, long before
      // anyone presses play. The category still has to be set here: route
      // sharing and the interruption observers below depend on it.
      self.audioSession.configureCategory(
        mode: AudioCategoryMapping.mode(for: config.audioContentType),
        options: AudioCategoryMapping.options(
          for: config.audioCategoryOptions,
          contentType: config.audioContentType
        )
      )
      self.audioSession.installObservers()
      let networkMonitor = PlaybackNetworkMonitor(queue: self.playerQueue)
      networkMonitor.onNetworkRestored = { [weak self] in self?.handleNetworkRestored() }
      networkMonitor.start()
      self.networkMonitor = networkMonitor
      self.startNowPlayingPeriodicTimer()
      self.wireRemoteCommands()
      self.remoteCommands.install()
      self.remoteCommands.setSkipCapability(
        canSkipNext: self.cachedSkipCapability.canSkipNext,
        canSkipPrevious: self.cachedSkipCapability.canSkipPrevious
      )
      // Route CarPlay Up Next row taps through the same steps the
      // `skipToIndex` Hybrid runs — engine guard, index validation,
      // and cast routing (a tap during an active cast session jumps
      // the RECEIVER; the local engine stays paused, so a local index
      // write would desync from it) — then the local index write.
      // Invoked on main by the scene delegate's Up Next row handler; the
      // index validation and the write both touch player state, so the body
      // runs on the queue that owns it.
      CarPlayCoordinator.shared.skipToIndexHandler = { [weak self] index in
        guard let self = self else { return }
        self.playerQueue.async {
          guard self.engine != nil else { return }
          guard let target = InputGuards.validQueueIndex(
            Double(index), count: self.tracks.count
          ) else { return }
          if CastTransportRouter.routeSkipToIndex(index: target) { return }
          self.applyNewCurrentIndex(newIndex: target, reason: .userSkipToIndex)
        }
      }
      // Read by the CarPlay scene at connect, on main. `getState()` runs
      // inline on the player queue, and the reply hops back to main, so
      // neither side waits on the other.
      CarPlayCoordinator.shared.playerStateReader = { [weak self] reply in
        guard let self = self else {
          DispatchQueue.main.async { reply(.none) }
          return
        }
        self.playerQueue.async {
          let state = (try? self.getState()) ?? PlayerState.none
          DispatchQueue.main.async { reply(state) }
        }
      }
      // Refresh on every configure() so a header / UA update lands
      // on the next artwork resolve. Live config — read fresh on
      // each track-change resolve.
      self.artworkResolver.configHeaders = config.httpHeaders
      self.artworkResolver.configUserAgent = config.userAgent
      // Swap the cover-art placeholder to the consumer-supplied image
      // (or back to the built-in when unset). Every placeholder site
      // reads `PlaceholderArtwork.image`, so this is the only hook needed.
      PlaceholderArtwork.setCustom(uri: config.placeholderArtworkUri)
      // Same live config for the cast lockscreen's own artwork
      // resolver so authenticated covers resolve during cast.
      CastNowPlayingController.shared.configureArtwork(
        headers: config.httpHeaders, userAgent: config.userAgent
      )
      // Build the lookahead cache + prefetcher honouring the live
      // `lookaheadConfig`. When disabled, neither is built.
      // `internalDestroy` above already nilled out any prior cache
      // + prefetcher, so this is always a fresh construction.
      if self.lookaheadConfig.enabled {
        let cache = LookaheadCache(
          maxSizeBytes: LookaheadCache.maxBytes(
            forMb: self.configuredCacheMaxSizeMb()
          ),
          evictionPolicy: self.configuredEvictionPolicy(),
          allowsCellularAccess: self.lookaheadConfig.allowsCellularAccess ?? true
        )
        // A policy change wipes the retained files so the cache refills
        // under the new eviction order.
        if evictionPolicyChanged {
          cache.clear()
        }
        self.lookaheadCache = cache
        let prefetcher = LookaheadCachePrefetcher(
          cache: cache,
          confinedTo: self.playerQueue,
          configHeaders: config.httpHeaders,
          configUserAgent: config.userAgent
        )
        prefetcher.defaultLookaheadCount =
          InputGuards.validLookaheadCount(self.lookaheadConfig.lookaheadCount)
        prefetcher.onTrackProcessed = { [weak self] in self?.emitCacheStatus() }
        self.lookaheadCachePrefetcher = prefetcher
        self.startReadThroughServer(for: cache)
      } else if evictionPolicyChanged {
        // Cache disabled at configure — no instance to clear(); purge the
        // on-disk files directly so a policy change still starts fresh
        // (parity with Android's unconditional clear).
        LookaheadCache.purgeDefaultDirectory()
      }
    }
  }

  /// Install AudioSession interruption + route-change callbacks on
  /// top of the transport. Centralised here so both callback types
  /// share the same emit-state-then-call-native-method pattern.
  private func wireAudioSession() {
    self.audioSession.onInterruption = { [weak self] kind in
      guard let self else { return }
      // Delivered on main; everything below reads or writes player state.
      self.playerQueue.async {
        switch kind {
        case .began:
          // System wants us paused (phone call, Siri, alarm). Pause
          // + stash `.interruption` for the resulting `timeControl
          // Status` KVO to pick up on the terminal `.paused` emit.
          //
          // Through the engine, mirroring `.endedShouldResume`: CrossfadeEngine
          // has no `self.player`, and its `pause()` also cancels an in-flight
          // fade and its arming observer. Pausing the raw player would leave
          // the fade's wall-clock completion task live to swap legs mid-call.
          self.pendingStateChangeReason = .interruption
          self.isInterrupted = true
          // The system holds the session now; the resume below reactivates
          // it only if this is cleared.
          self.audioSession.noteInterrupted()
          self.engine?.pause()
        case .endedShouldResume:
          // System says it's safe to resume. Resume through the engine so
          // the user's playback speed is restored (a raw AVPlayer.play()
          // resets rate to 1.0) and crossfade — whose legs the OS paused on
          // `.began` but which has no `self.player` to act on — resumes too.
          // play() transitions via `.buffering` → `.playing`; buffering
          // passes through as `.system` so the reason sticks until
          // `.playing` lands.
          self.pendingStateChangeReason = .interruption
          self.isInterrupted = false
          // Some interruptions deactivate our session — a call that took the
          // route, notably. Resuming into a session we never reactivated plays
          // nothing, so reactivate before the transport call.
          self.audioSession.activate(
            mode: AudioCategoryMapping.mode(for: self.config.audioContentType),
            options: AudioCategoryMapping.options(
              for: self.config.audioCategoryOptions,
              contentType: self.config.audioContentType
            ))
          // Not when the queue is stopped on a reported failure: the player is
          // parked on a track the consumer was told failed, and `beginPlayback`
          // writes a rate without consulting the transport intent, so resuming
          // here would start it — or the track behind it — unasked.
          if self.reportedFailureOnCurrentTrack == nil {
            // A long interruption can suspend the app, and the resume arrives
            // without it coming to the foreground.
            self.reviveReadThroughServerIfDead()
            self.reinstallAfterReadThroughRestart()
            self.beginPlayback()
          }
        case .endedShouldNotResume:
          // Interruption ended but user resolved it in a way that
          // shouldn't restart music. The player is already paused
          // (OS pre-paused on `.began`, we stayed paused), so no
          // transport call and no state transition to report.
          // Apple's docs confirm clients should not resume — no
          // client action needed; a dedicated `onInterruptionEnd`
          // event could land later if consumers need to clear UI
          // annotations.
          //
          // Intent is cleared too: the system has told us the user resolved
          // this in a way that should not restart music, so a later window
          // refill or retry must not treat the pre-interruption intent as
          // still standing.
          self.isInterrupted = false
          self.wantsToPlay = false
          break
        }

        // An interruption is a normal lifecycle signal (call / Siri / a route
        // handoff), NOT a playback error — surfacing it on the `onError` stream
        // makes consumers render an error banner for a routine pause. The pause
        // is already reported via the `.paused` state stamped with
        // `reason = .interruption`, which is the correct, non-error signal.
      }
    }

    self.audioSession.onRouteChange = { [weak self] kind in
      guard let self else { return }
      // Delivered on main; everything below reads or writes player state.
      self.playerQueue.async {
        switch kind {
        case .oldDeviceUnavailable:
          // Headphones unplugged, BT disconnected — never blast
          // music over the speaker per Apple HIG. Pause through the engine:
          // `self.player` is nil under CrossfadeEngine, so reading it here
          // would let the route change pass unhandled on that engine and do
          // the one thing this case exists to prevent.
          self.pendingStateChangeReason = .routeChange
          // Drop the transport intent too. This pause is final — the HIG says a
          // vanished route must not resume on its own — and the resume paths
          // (drained refill, retry) read the intent, so leaving it set would let
          // one of them start audio out of the speaker afterwards. An
          // interruption is deliberately different: it keeps the intent, because
          // `.endedShouldResume` is meant to resume.
          self.wantsToPlay = false
          self.engine?.pause()
        case .newDeviceAvailable:
          // New route arrived (BT connected, headphones plugged in).
          // No automatic transport action; state is unchanged, so
          // no emission either. Consumers wanting to observe new
          // devices can subscribe to AVAudioSession notifications
          // directly if needed.
          break
        }
      }
    }

    // Audio-session lifecycle failures (`setCategory` /
    // `setActive(true)` / `setActive(false)`) surface as non-fatal
    // PlaybackErrors with stable codes (see `AudioSession.onError`
    // doc). Deliberately do NOT call `emitState(.error, ...)` —
    // these are session-level failures, not player-level; the
    // player itself isn't in an error state and may still recover
    // (e.g. another app released the session).
    self.audioSession.onError = { [weak self] sessionError in
      guard let self else { return }
      // Audio-session errors don't have a meaningful AVPlayer-level
      // standardized code (they're session-scope, not playback-
      // scope). Surface as `.unknown` with the SCREAMING_SNAKE_CASE
      // session code in `nativeDomain` so consumers can branch on
      // `nativeDomain.startsWith("AUDIO_SESSION_")` if they need
      // session-specific UX.
      // Apply the same `stripQuery` PII safeguard as the rest of
      // the error fire sites — audio-session messages today don't
      // carry URLs, but defense in depth.
      let safeMessage = PlaybackErrorMapping.stripQuery(sessionError.message)
      // Audio-session errors are session-scope; no specific queue
      // item to correlate, so queueItemId + url are empty.
      let err = PlaybackError(
        code: .unknown,
        message: safeMessage,
        fatal: false,
        nativeCode: Double(sessionError.nativeCode),
        nativeDomain: sessionError.nativeDomain,
        nativeMessage: safeMessage,
        queueItemId: "",
        url: ""
      )
      self.errorListeners.forEach { $0(err) }
    }
  }

  /// Map `MPRemoteCommandCenter` events to the public transport
  /// methods. The closure stamps `pendingStateChangeReason = .system`
  /// before invoking the transport call so the conditional
  /// `pendingStateChangeReason ?? .user` write inside each transport
  /// method short-circuits and the resulting state-change emit
  /// carries `.system`.
  ///
  /// Race window: the stamp and the transport call it belongs to are two
  /// separate blocks on `playerQueue`, so an unrelated block landing between
  /// them (e.g. an `.interruption` stamp from `wireAudioSession`) can
  /// overwrite the slot. The practical impact
  /// is bounded — the default reason in `emitStateChangeIfChanged` is
  /// `.system`, so a stolen `.system` re-falls-through to `.system` on
  /// the next consumer. An `.interruption` interleave tags the
  /// state-change as `.interruption` (semantically correct — the
  /// interruption IS why the state changed); the subsequent play()
  /// emit defaults to `.user`. Acceptable degradation.
  ///
  /// Togglers: `.togglePlayPause` flips off ANY actively-progressing
  /// state, not just `.playing` — `.buffering` (waiting-to-play)
  /// counts as "user is trying to play" and should pause on tap.
  ///
  /// `[weak self]` prevents the cycle TrackPlayer → remoteCommands →
  /// onCommand → TrackPlayer (TrackPlayer owns RemoteCommands as a
  /// `let` property).
  private func wireRemoteCommands() {
    self.remoteCommands.onCommand = { [weak self] command in
      guard let self = self else { return }
      // MPRemoteCommandCenter calls its handlers on main; everything below
      // reads or writes player state, starting with the staked reason.
      self.playerQueue.async {
        self.pendingStateChangeReason = .system
        switch command {
        case .play:
          _ = try? self.play()
        case .pause:
          _ = try? self.pause()
        case .togglePlayPause:
          let active =
            self.lastReportedState == .playing ||
            self.lastReportedState == .buffering
          if active {
            _ = try? self.pause()
          } else {
            _ = try? self.play()
          }
        case .stop:
          _ = try? self.stop()
        case .nextTrack:
          _ = try? self.skipToNext()
        case .previousTrack:
          _ = try? self.skipToPrevious()
        case .skipForward(let seconds):
          // Chromecast: relative-seek the receiver from ITS position. AirPlay /
          // local: `routeSeekBy` returns false (no GCK session), so seek the
          // local engine — whose position is accurate (AVPlayer plays to the
          // AirPlay route). `seekTo` clamps to [0, duration].
          if !CastTransportRouter.routeSeekBy(deltaMs: Int64(seconds * 1000)) {
            let base = self.engine?.currentPositionSeconds ?? 0
            _ = try? self.seekTo(position: (base.isFinite ? base : 0) + seconds)
          }
        case .skipBackward(let seconds):
          if !CastTransportRouter.routeSeekBy(deltaMs: -Int64(seconds * 1000)) {
            let base = self.engine?.currentPositionSeconds ?? 0
            _ = try? self.seekTo(position: (base.isFinite ? base : 0) - seconds)
          }
        case .changePlaybackPosition(let seconds):
          _ = try? self.seekTo(position: seconds)
        }
      }
    }
  }

  /// Build the `NowPlayingInfo` snapshot every lock-screen refresh is pushed
  /// with: the current track, and the engine's position, duration and
  /// effective rate.
  ///
  /// Every player-side value comes from the engine, and under crossfade the
  /// engine's accessors follow the leg that is audible — so position, duration
  /// and rate describe one leg rather than a mix of two. A `nil` track + zero values when
  /// the player is gone is observed as "queue empty" by
  /// `NowPlayingInfo.refreshAll`, which then clears the dictionary.
  internal func nowPlayingSnapshot() -> NowPlayingInfo.Snapshot {
    let idx = self.currentTrackIndex
    let track: TrackItem? = (idx >= 0 && idx < self.tracks.count)
      ? self.tracks[idx] : nil
    // The engine owns these, not `self.player`: the gapless queue player does
    // not exist under crossfade.
    let elapsed = self.engine?.currentPositionSeconds ?? 0
    let duration = self.engine?.currentDurationSeconds ?? 0
    // `timeControlStatus`, not `isPlaying`: under crossfade the former follows
    // the audible leg exactly as position and duration do, while the latter
    // reads the leading leg alone. Taking `isPlaying` here would report rate 0
    // whenever the outgoing item has ended but the fade has not yet completed,
    // pairing a live position with a paused rate — and it is the same source
    // `PlayerStateDerivation` uses, so the pushed rate and the emitted state
    // cannot disagree.
    //
    // MediaPlayer wants the effective rate: the configured speed while
    // playing, zero otherwise. Publishing a requested-but-not-yet-audible rate
    // makes the system extrapolate the scrubber through a stall.
    let rate = (self.engine?.timeControlStatus == .playing) ? self.playbackSpeedState : 0
    return NowPlayingInfo.Snapshot(
      track: track,
      queueItemId: self.queueItemIds[safe: idx],
      elapsedSeconds: elapsed,
      durationSeconds: duration,
      rate: rate
    )
  }

  /// Drive the 10s lock-screen tick. The snapshot is built here, on the
  /// side that owns player state, and pushed into `NowPlayingInfo`.
  /// A paused player short-circuits — elapsed is unchanged and rate is
  /// already 0, so the write would be pure noise.
  private func startNowPlayingPeriodicTimer() {
    self.nowPlayingInfo.startPeriodicTimer { [weak self] in
      guard let self else { return }
      // The tick fires on main, where the Now Playing surface lives, but the
      // snapshot is player state — so it is built on the queue that owns that
      // state and handed back as a value.
      self.playerQueue.async {
        let snapshot = self.nowPlayingSnapshot()
        guard snapshot.rate != 0 else { return }
        self.nowPlayingInfo.refreshPositionAndRate(snapshot, trigger: .tick)
      }
    }
  }

  func destroy() throws -> Promise<Void> {
    return enqueue {
      DiagnosticLog.player.log("destroy")
      self.internalDestroy()
      // After the teardown, so its steps on this queue are logged. Work it
      // hands to main can run after this and is not logged.
      DiagnosticLog.isEnabled = false
    }
  }

  /// Synchronous teardown. Idempotent — cancels the timer sources, then
  /// no-ops when nothing is configured. Called from public `destroy()`
  /// and from `configure()` (which auto-destroys before reinitialising;
  /// see `configure(_:)` for the universal contract).
  ///
  /// Persists `config` / `repeatModeState` / `lookaheadConfig` across
  /// the teardown so a consumer who called `setRepeatMode()` /
  /// `setLookaheadCache()` doesn't lose those settings on a
  /// reconfigure cycle. Matches Android's symmetric behaviour.
  private func internalDestroy() {
    // Ahead of the gate below: `setSleepTimer` / `setSleepTimerToTrackEnd`
    // arm without an engine, so a gated teardown would strand a live 2 Hz
    // tick source with nothing left to cancel it. All three are idempotent
    // and none read `engine`.
    self.stopProgressFallbackTimer()
    self.stopSleepTimerTick()
    self.sleepTimerCore.clear()
    // Idempotent gate: when nothing has been configured, every line
    // below would either no-op (nil-coalesced calls) or perform
    // unnecessary work against fresh state. `self.engine` is the
    // "is configured?" sentinel because it is non-nil under BOTH
    // engines. `self.player` is not — it holds the gapless
    // `AVQueuePlayer` and stays nil for as long as a CrossfadeEngine
    // is active, so gating on it would skip the whole teardown
    // mid-crossfade and leak every resource below.
    //
    // `configure(config:)` assigns `self.engine` before
    // `audioSession.configureCategory`, `audioSession.installObservers`,
    // `startNowPlayingPeriodicTimer` and `remoteCommands.install`, so the
    // gate admits cleanup of every resource those install. The tear-down
    // lines below are individually idempotent.
    // Above the gate: the tap provider is a process-wide singleton, so an
    // instance that configured and is then dropped without `destroy()` would
    // otherwise leave the singleton pointing at a dead player's queue, and
    // every later player's mix work would dispatch onto it.
    AudioTapProvider.shared.detachFromEngine()
    // The provider is process-wide, so a callback left installed on it outlives
    // this player and would run against the one being torn down.
    AudioTapProvider.shared.onItemReplayGainDataPopulated = nil
    AudioTapProvider.shared.resetMixDispatchTarget()

    guard self.engine != nil else { return }
    // Tear the prefetcher down BEFORE the cache. Cancelling the
    // prefetcher's Task chain arms cooperative cancellation so no
    // new download dispatches against the about-to-die URLSession;
    // the cache's `tearDown` then invalidates the session, which
    // is what actually kills any already-suspended
    // URLSessionDownloadTask mid-flight.
    self.lookaheadCachePrefetcher?.tearDown()
    self.lookaheadCachePrefetcher = nil
    // Before the cache: a connection mid-response holds an open handle on a
    // cache file, and the listener is what owns those connections.
    self.readThroughServer?.stop()
    self.readThroughServer = nil
    self.lookaheadCache?.tearDown()
    self.lookaheadCache = nil
    // Drop the mirror chain rather than cancelling it: a receiver call already
    // on the wire is left to finish, and the next session starts its own chain
    // instead of queueing behind a torn-down one.
    self.castMirrorChain = nil

    self.tearDownGaplessObservers()
    self.tearDownEventObservers()
    self.removeAppLifecycleObservers()
    self.networkMonitor?.stop()
    self.networkMonitor = nil
    self.audioSession.tearDownObservers()
    self.audioSession.onInterruption = nil
    self.audioSession.onRouteChange = nil
    self.audioSession.onError = nil
    self.nowPlayingInfo.clear()
    self.remoteCommands.uninstall()
    self.remoteCommands.onCommand = nil
    self.artworkResolver.reset()
    // The tap provider is a process-wide singleton: drop its pointer at the
    // engine and put its dispatch target back on main, so the next
    // `configure` starts from the clean slate its contract promises.
    // CarPlay coordinator state lives for process lifetime. The
    // bridge slots survive destroy — the subscription is owned by
    // the JS consumer (re-registering replaces the slot), matching
    // the documented Android behaviour. Browse data, in-flight
    // resolver promises, and the artwork cache reset with the
    // configure cycle.
    let coordinator = CarPlayCoordinator.shared
    coordinator.dataProvider.setSnapshot(nil)
    coordinator.queueProvider.setSnapshot(nil)
    coordinator.skipToIndexHandler = nil
    coordinator.playerStateReader = nil
    coordinator.onCarAttached = nil
    coordinator.pending.clear()
    coordinator.loader.clearCache()
    // engine.release() drains the underlying AVQueuePlayer's items +
    // nils its mix provider.
    self.engine?.release()
    self.engine = nil
    self.player = nil
    self.sleepTimerFading = false
    self.queueState.clear()
    // The clear emits nothing, so a mirror built from `onQueueChange` deltas
    // would splice the next delta onto the queue this just dropped. Moving
    // the revision past the next emit's value is what a subscriber reads as
    // a missed event, and re-reads.
    self.queueRevision += 1
    self.currentTrackIndex = -1
    self.currentTrackSource = nil
    self.currentBufferState = .empty
    self.lastEmittedBufferState = nil
    self.lastBufferStateQueueItemId = nil
    self.currentFullyBuffered = false
    self.lastEmittedFullyBuffered = nil
    self.hasStartedPlaying = false
    self.wantsToPlay = false
    self.isInterrupted = false
    self.isRecoveringFromStall = false
    self.engineReconcileDeferred = false
    self.lastEmittedQueueItemId = nil
    self.announcedTrack = nil
    self.announcedTrackId = nil
    self.announcedTrackPlaying = false
    self.didPlayToEndPending = false
    // `engine` + `player` are both nil now, so `activeMediaItem` is
    // nil: refresh emits null + cancels in-flight extractor Tasks via
    // the generation token.
    self.refreshNowPlayingFormatForActiveItem()
    // `lastErrorQueueItemId` / `lastErrorCode` reset alongside the
    // player so a subsequent error on a different item still fires
    // (the dedup is per-item).
    self.lastErrorQueueItemId = nil
    self.lastErrorCode = nil
    self.reportedFailureQueueItemIds.removeAll()
    self.pendingRetryQueueItemId = nil
    self.retryAttemptsRemaining.removeAll()
    self.lastReportedState = .none
    self.reachedQueueEnd = false
    self.pendingTrackChangeReason = nil
    self.pendingStateChangeReason = nil
    self.audioSession.deactivate()
    // Tear down the cast event bridge — disposers unsubscribe from
    // both the global router and any active session's state listener.
    self.castEventBridge.stop()
    // Tear down the cast lockscreen mirror.
    CastNowPlayingController.shared.stop()
    // Final (false, false) emit so any still-registered subscriber
    // sees the player is gone. JS hooks dispose their listener on
    // unmount; this covers stragglers + native-side subscribers.
    self.recomputeCapabilities()
  }

  /// Attach the KVO chain that powers the stalling-flip gapless
  /// pattern: observe `player.currentItem` for item transitions,
  /// and for each current item observe `status` so we can flip
  /// `automaticallyWaitsToMinimizeStalling` to false the moment
  /// the current item reaches `.readyToPlay` while
  /// `gaplessFlipArmed == true`. Safe to call repeatedly — previous
  /// observers are invalidated first.
  ///
  /// `.new`-only KVO: `.initial` is deliberately NOT used. `.initial`
  /// fires synchronously during observer registration on whatever
  /// thread the install runs from, which can land mid-mutation and
  /// skip the lib's debounce step (`pendingStateChangeReason`
  /// consumption, `lastEmittedQueueItemId` dedup). The explicit
  /// `playerQueue.async` bootstrap below replicates `.initial` semantics
  /// by funnelling through the same handler the `.new` callback
  /// uses, but on a stable post-registration state. See NOTES.md section 18.
  internal func installGaplessObservers(on player: AVQueuePlayer) {
    tearDownGaplessObservers()

    currentItemObserver = player.observe(
      \.currentItem, options: [.new]
    ) { [weak self] _, _ in
      // KVO fires on AVFoundation's internal queue; funnel onto the queue
      // that owns player state, like every other player mutation.
      guard let self else { return }
      self.playerQueue.async {
        self.attachStatusObserverIfArmed()
        self.handleCurrentItemDidChange()
      }
    }
    // Bootstrap to replicate what `.initial` would have delivered. The async
    // hop matches the `.new` callback body's dispatch shape so a fresh-install
    // cur-item is observed identically to a subsequent transition.
    playerQueue.async { [weak self] in
      guard let self else { return }
      self.attachStatusObserverIfArmed()
      self.handleCurrentItemDidChange()
    }
  }

  private func attachStatusObserverIfArmed() {
    currentItemStatusObserver?.invalidate()
    currentItemStatusObserver = nil
    currentItemDurationObserver?.invalidate()
    currentItemDurationObserver = nil
    tearDownBufferObservers()
    guard let item = self.player?.currentItem else {
      // No current item (empty queue / torn down) — settle to empty.
      recomputeBufferState()
      return
    }

    // A local file that does not exist is deterministically unplayable, and its
    // `AVURLAsset` load can stall in `.unknown` indefinitely under host load —
    // the item never reaches `.failed`, so the `\.status` observer below would
    // never surface it and the queue sits buffering with no error. Fail it now
    // and install no observer for this dead item: a status observer that did
    // eventually reach `.failed` would re-report the same source under a
    // second, different error code. A retry re-seats
    // the item and re-runs this check, so a source that appears is picked up
    // then. Pausing first halts the AVQueuePlayer chain-advance the same way the
    // `.failed` branch does. Read the asset's own URL, the one the player loads.
    if let asset = item.asset as? AVURLAsset, asset.url.isFileURL,
       !FileManager.default.fileExists(atPath: asset.url.path) {
      self.player?.pause()
      let missing = NSError(
        domain: NSURLErrorDomain, code: NSURLErrorFileDoesNotExist,
        userInfo: [NSLocalizedDescriptionKey: "The requested file does not exist."])
      self.dispatchErrorOrRetry(
        item: item, underlying: missing, nativeDomainOverride: "PLAYER_ITEM_LOAD_FAILED")
      return
    }

    // Two distinct concerns share this KVO:
    //   1. `.readyToPlay` flips `automaticallyWaitsToMinimizeStalling`
    //      back to false (initial-buffer leniency one-shot).
    //   2. `.failed` surfaces an `onError` event — the only hook that
    //      catches initial-load failures (closed port, 4xx/5xx HTTP,
    //      malformed asset). Mid-playback failures arrive separately
    //      via `AVPlayerItemFailedToPlayToEndTime`.
    //
    // `.new`-only KVO + synchronous bootstrap. The bootstrap is sync
    // (not a `playerQueue.async` hop like the sibling `\.currentItem` /
    // `\.timeControlStatus` bootstraps) because this method is itself
    // called on `playerQueue` (from the `\.currentItem` KVO body's hop)
    // — running the bootstrap inline catches a cache-hit path
    // where the asset resolves to `.readyToPlay` before observer
    // install would otherwise leave `gaplessFlipArmed` set forever.
    //
    // Re-entrancy: a `.failed` immediate resolution dispatches through
    // `handleCurrentItemFailedToLoad` → `dispatchErrorOrRetry`, which
    // may schedule a retry that calls `fullRebuildPlayerQueue`. The
    // rebuild's `tearDownGaplessObservers` invalidates the just-
    // installed observer reference; the synchronous bootstrap's local
    // `let observer` reference still holds but its observation is
    // disconnected. Safe — the inflight bootstrap path completes,
    // returns to `attachStatusObserverIfArmed`, returns to the parent
    // KVO body, and the rebuild's freshly-installed observer takes
    // over.
    currentItemStatusObserver = item.observe(
      \.status, options: [.new]
    ) { [weak self] item, _ in
      guard let self else { return }
      self.playerQueue.async {
        self.dispatchItemStatus(item)
      }
    }
    self.dispatchItemStatus(item)
    currentItemDurationObserver = item.observe(
      \.duration, options: [.new]
    ) { [weak self] _, _ in
      guard let self else { return }
      self.playerQueue.async {
        self.nowPlayingInfo.refreshPositionAndRate(self.nowPlayingSnapshot(), trigger: .durationKnown)
      }
    }
    installBufferObservers(on: item)
  }

  /// KVO on the gapless current item's three native playback-buffer flags.
  /// Any change recomputes the `BufferState` and the fully-buffered flag on
  /// `playerQueue`, ahead of the next progress tick.
  private func installBufferObservers(on item: AVPlayerItem) {
    let onChange: (AVPlayerItem, Any) -> Void = { [weak self] _, _ in
      self?.playerQueue.async {
        self?.recomputeBufferState()
        self?.recomputeFullyBuffered()
      }
    }
    bufferEmptyObserver = item.observe(\.isPlaybackBufferEmpty, options: [.new], changeHandler: onChange)
    bufferKeepUpObserver = item.observe(\.isPlaybackLikelyToKeepUp, options: [.new], changeHandler: onChange)
    bufferFullObserver = item.observe(\.isPlaybackBufferFull, options: [.new], changeHandler: onChange)
    recomputeBufferState()
    recomputeFullyBuffered()
  }

  private func tearDownBufferObservers() {
    bufferEmptyObserver?.invalidate(); bufferEmptyObserver = nil
    bufferKeepUpObserver?.invalidate(); bufferKeepUpObserver = nil
    bufferFullObserver?.invalidate(); bufferFullObserver = nil
  }

  /// Single source of truth for the per-item `\.status` KVO body.
  /// Called both from the observer callback and from the synchronous
  /// bootstrap in `attachStatusObserverIfArmed`.
  private func dispatchItemStatus(_ item: AVPlayerItem) {
    switch item.status {
    case .readyToPlay:
      if self.gaplessFlipArmed, let player = self.player {
        player.automaticallyWaitsToMinimizeStalling = false
        self.gaplessFlipArmed = false
      }
      // The asset's audio track + format descriptions are reliably
      // loadable by the time we hit `.readyToPlay`. The post-track-
      // change null-emit may race ahead of asset metadata load on
      // first hit, so we re-resolve here to land the real format.
      self.refreshNowPlayingFormatForActiveItem()
      // Streaming-item audioMix catch-up for the gapless engine.
      // engine.setItems / insertItems install the mix at insert
      // time only for items whose `tracks` key is already loaded;
      // streaming items insert with `audioMix == nil` and pick up
      // their mix here once `.readyToPlay` confirms tracks have
      // loaded. The crossfade engine has a parallel catch-up path
      // through its `engineActiveItemFormatChanged` delegate hook —
      // both call into `refreshActiveItemMixes`, which is
      // idempotent on items that already carry a tap, so a double-
      // fire is harmless.
      AudioTapProvider.shared.refreshActiveItemMixes()
      // Duration is only reliably readable once the item is `.readyToPlay`.
      // Re-publish now-playing so the lock-screen scrubber gets a duration on
      // first load + auto-advance (not just after a manual skip, which is the
      // only path that otherwise forces a state change that re-publishes it).
      self.nowPlayingInfo.refreshPositionAndRate(self.nowPlayingSnapshot(), trigger: .readyToPlay)
    case .failed:
      // Pause synchronously BEFORE surfacing the typed error. Without
      // this, AVQueuePlayer treats `.failed` as end-of-item and chain-
      // advances through subsequent items in the queue, walking
      // `currentTrackIndex` past the failure point while the JS error
      // event is still in flight. Pausing halts that walk so the
      // consumer sees the error against the failing track and can
      // decide whether to skip / retry / surface UI.
      self.player?.pause()
      self.handleCurrentItemFailedToLoad(item)
    default:
      break
    }
  }

  /// Surface a `.failed` AVPlayerItem as an `onError` + state ERROR.
  /// Idempotent per (queueItemId, standardized-code) so AVPlayer's
  /// internal item-rebuild during retries doesn't bypass dedup. A
  /// retry that flips the same item to a DIFFERENT error code still
  /// surfaces because the code is part of the dedup key.
  ///
  /// `nativeDomain` on the emitted error is stamped to
  /// `PLAYER_ITEM_LOAD_FAILED` so JS consumers can distinguish item-
  /// load failures (the raw asset never reached `.readyToPlay`) from
  /// mid-playback failures (which arrive via
  /// `AVPlayerItemFailedToPlayToEndTime` and keep the underlying
  /// `NSError.domain`). The underlying NSError code + message remain
  /// available via `nativeCode` + `nativeMessage`.
  private func handleCurrentItemFailedToLoad(_ item: AVPlayerItem) {
    guard item.status == .failed else { return }
    let underlying = item.error as NSError?
    dispatchErrorOrRetry(
      item: item,
      underlying: underlying,
      nativeDomainOverride: "PLAYER_ITEM_LOAD_FAILED"
    )
  }

  /// Shared dispatch path for `handleCurrentItemFailedToLoad` +
  /// `handlePlayerItemFailedToPlayToEndTime`. Encapsulates: (1)
  /// classifier, (2) qid-fallback to fresh UUID for raw items, (3)
  /// (queueItemId, code) dedup, (4) auto-retry path with dedup
  /// clear-on-retry, (5) terminal `errorListeners.forEach` +
  /// emitState(.error). Single chokepoint so the dedup + retry
  /// contracts can't drift between callers.
  ///
  /// `nativeDomainOverride` lets a caller stamp a stable lib-defined
  /// SCREAMING_SNAKE_CASE marker on the emitted `nativeDomain` (e.g.
  /// `PLAYER_ITEM_LOAD_FAILED` from the `.failed`-status path) so JS
  /// consumers can branch on the cause without parsing raw native
  /// domain strings. Nil preserves the underlying `NSError.domain`.
  internal func dispatchErrorOrRetry(
    item: AVPlayerItem?,
    underlying: NSError?,
    nativeDomainOverride: String? = nil
  ) {
    let mapped = PlaybackErrorMapping.classify(underlying)
    // Fresh UUID fallback per fire (NOT a static sentinel) when
    // queueItemId is missing — two unrelated items both lacking the
    // associated-object would otherwise dedup against each other.
    // Production AVQueueBuilder.makeItem always sets queueItemId; this
    // path covers raw `makeItemRaw` items (test stubs).
    let qid = item?.queueItemId ?? UUID().uuidString
    // An echo of a failure whose attempt has not run yet belongs to that
    // attempt, not to the consumer.
    if let qidReal = item?.queueItemId, self.pendingRetryQueueItemId == qidReal { return }
    // Already reported and not yet recovered. The `(item, code)` dedup below
    // cannot carry this on its own: a source the player keeps re-attempting —
    // a server answering a ranged request with a whole body makes AVFoundation
    // do exactly that — fails under more than one code, and codes that
    // alternate defeat a one-slot key. Every recovery path clears the field, so
    // the next thing the consumer asks for reports again.
    if let qidReal = item?.queueItemId, self.reportedFailureQueueItemIds.contains(qidReal) { return }
    // Once the queue has stopped on the current track, the tracks behind it go
    // quiet. The `AVPlayerItemFailedToPlayToEndTime` observer is registered
    // against every item, so a window of unplayable sources reports each of
    // them — turning one `play()` into one error per broken track, which is the
    // flicker the listener sees. They have already been told the track they are
    // on failed; the rest is noise they cannot act on.
    //
    // While playback is healthy this does not fire, so a standby leg failing to
    // preroll under crossfade is still surfaced — the leading leg is playing,
    // nothing has been reported against it, and that failure is news.
    //
    // Nothing is lost either way: a suppressed track reports when the player
    // seats it and it fails as the current one, or when the player drops it and
    // `stopOnDroppedTrack` reports it against the position it skipped.
    if let failedQid = item?.queueItemId,
       let currentQid = self.queueItemIds[safe: self.currentTrackIndex],
       failedQid != currentQid,
       self.reportedFailureQueueItemIds.contains(currentQid) {
      return
    }
    if self.lastErrorQueueItemId == qid && self.lastErrorCode == mapped { return }
    self.lastErrorQueueItemId = qid
    self.lastErrorCode = mapped
    // A routed item can fail because the read-through listener died, not the
    // source: iOS reclaims a suspended app's listening socket while the
    // listener keeps reporting ready. When that is what happened — the item
    // names a replaced listener, or the server does not answer and is
    // restarted — the current track is retried on the live listener without
    // spending its budget or telling the consumer. Once per position, so a
    // listener still dead after the restart is reported on the next failure.
    // Not for other tracks: `retryFailedItem` only acts on the current one, so
    // their items are left for the next reinstall and their error goes on.
    if let item, let qidReal = item.queueItemId, item.isRoutedThroughReadThroughServer {
      let isCurrent = self.queueItemIds[safe: self.currentTrackIndex] == qidReal
      let firstRecovery = !self.routedRecoveryAttemptedQueueItemIds.contains(qidReal)
      // Both are evaluated: the live listener is probed even when the item
      // names a replaced one, since it may have been reclaimed too.
      let replaced = self.addressesReplacedListener(item)
      let restarted = (!isCurrent || firstRecovery) && self.reviveReadThroughServerIfDead()
      if isCurrent, firstRecovery, replaced || restarted {
        self.routedRecoveryAttemptedQueueItemIds.insert(qidReal)
        self.pendingRetryQueueItemId = qidReal
        self.lastErrorQueueItemId = nil
        self.lastErrorCode = nil
        NSLog(
          "[QueuePlayer] read-through retrying the current track: %@",
          restarted ? "listener restarted" : "item named a replaced listener")
        // Asynchronous: this can run inside `attachStatusObserverIfArmed`,
        // which installs observers on the item after it returns.
        playerQueue.async { [weak self] in self?.retryFailedItem(queueItemId: qidReal) }
        return
      }
      if !isCurrent, replaced || restarted {
        self.readThroughReinstallPending = true
      }
    }
    // Auto-retry path: transient errors with retries left schedule
    // a delayed rebuild. Skip JS emit + state.error transition until
    // retries exhausted.
    if PlaybackErrorMapping.isTransient(mapped),
       let qidReal = item?.queueItemId,
       (self.retryAttemptsRemaining[qidReal] ?? 0) > 0 {
      self.retryAttemptsRemaining[qidReal] = self.retryAttemptsRemaining[qidReal]! - 1
      self.pendingRetryQueueItemId = qidReal
      // Clear dedup so the retry-then-fail (if any) fires fresh.
      self.lastErrorQueueItemId = nil
      self.lastErrorCode = nil
      let backoff = self.effectiveRetryBackoffMs()
      playerQueue.asyncAfter(deadline: .now() + .milliseconds(backoff)) {
        [weak self] in self?.retryFailedItem(queueItemId: qidReal)
      }
      return
    }
    // Surface the failing item's queueItemId + URL so consumers can
    // correlate the error to the specific track even when
    // AVQueuePlayer chain-advances past it before the JS event fires.
    // Reuse `qid` (which is the lib-generated UUID fallback for raw
    // items) so the JS-visible field matches the dedup key.
    let failedQid = item?.queueItemId ?? qid
    // The track's own URL. A routed item's asset URL names the read-through
    // server, which tells a consumer nothing about which track failed — and
    // correlating the error to a track is the whole purpose of this field.
    let failedUrl = item?.sourceURL ?? (item?.asset as? AVURLAsset)?.url.absoluteString ?? ""
    let err = self.buildPlaybackError(
      underlying,
      mapped: mapped,
      queueItemId: failedQid,
      url: failedUrl,
      nativeDomainOverride: nativeDomainOverride
    )
    if let failedQid = item?.queueItemId {
      // The consumer now knows this track failed, so the queue holds on it
      // until something asks for playback again.
      self.reportedFailureQueueItemIds.insert(failedQid)
      // `AVQueuePlayer` consumes a failed item, so by now its current item is
      // whatever was installed behind the failure. The gapless status path
      // pauses before it gets here; the crossfade path has no queue player to
      // pause, and neither covers a mid-playback failure.
      self.engine?.pause()
      // A failure that will not recover on its own ends the intent to play, the
      // way reaching the end of the queue does. Left set for a transient
      // failure whose attempts are merely spent, because that is what
      // `handleNetworkRestored` reads through `NetworkRecoveryPolicy` when the
      // network comes back. The resumes that do not consult this flag — a
      // stall recovery, an interruption ending — are held off by the entry
      // above instead, so neither case starts the track behind the failure.
      if !PlaybackErrorMapping.isTransient(mapped) { self.wantsToPlay = false }
    }
    self.errorListeners.forEach { $0(err) }
    self.emitState(.error, reason: .error)
  }

  internal func tearDownGaplessObservers() {
    currentItemObserver?.invalidate()
    currentItemObserver = nil
    currentItemStatusObserver?.invalidate()
    currentItemStatusObserver = nil
    currentItemDurationObserver?.invalidate()
    currentItemDurationObserver = nil
    tearDownBufferObservers()
  }

  /// Re-arm the initial-buffer leniency: the next `.readyToPlay`
  /// flips stalling back to false. Called from `fullRebuildPlayerQueue`
  /// (central choke point for "the currentItem is being replaced by
  /// a fresh AVPlayerItem") and from `removeFromQueue` when the
  /// currentItem is dropped and AVQueuePlayer auto-advances to an
  /// item that may not yet be buffered.
  ///
  /// The `\.currentItem` KVO chain already installed in `configure`
  /// handles attaching the status observer once the new currentItem
  /// is in place — we don't attach it synchronously here to avoid
  /// racing against the caller's in-flight `player.insert(...)`
  /// sequence.
  internal func rearmGaplessFlip() {
    guard let player = self.player else { return }
    self.gaplessFlipArmed = true
    player.automaticallyWaitsToMinimizeStalling = true
  }
}
