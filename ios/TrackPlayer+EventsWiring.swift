import AVFoundation

extension TrackPlayer {
  // MARK: - Events — observer wiring

  /// Install the periodic progress observer, state-change KVO, and
  /// the NotificationCenter observers for error + end-of-item.
  /// Track-change is piggybacked on the existing `\.currentItem`
  /// KVO installed by `installGaplessObservers` — see
  /// `handleCurrentItemDidChange`.
  internal func installEventObservers(on player: AVQueuePlayer) {
    tearDownEventObservers()

    // 2 Hz progress tick — per NOTES.md section 5, native periodic timer
    // (not JS setInterval) is load-bearing for backgrounded playback.
    let interval = CMTime(
      seconds: Self.progressIntervalSeconds,
      preferredTimescale: CMTimeScale(NSEC_PER_SEC))
    let token = player.addPeriodicTimeObserver(
      forInterval: interval, queue: playerQueue
    ) { [weak self] _ in
      self?.emitProgressIfSubscribed()
      self?.processMilestones()
      self?.recomputeBufferState()
      self?.recomputeFullyBuffered()
    }
    progressTimeObserver = (player: player, token: token)

    // `.new`-only KVO + a `playerQueue.async` bootstrap. See
    // `installGaplessObservers` for the rationale on why `.initial`
    // is avoided lib-wide.
    timeControlStatusObserver = player.observe(
      \.timeControlStatus, options: [.new]
    ) { [weak self] player, _ in
      #if DEBUG
      let observedStatus = player.timeControlStatus
      #endif
      self?.playerQueue.async {
        guard let self else { return }
        #if DEBUG
        if observedStatus == .playing && self.gaplessLogLastChangedAtNs > 0 {
          let now = DispatchTime.now().uptimeNanoseconds
          let deltaMs = Double(now &- self.gaplessLogLastChangedAtNs) / 1_000_000.0
          NSLog("[RNQP-GAPLESS] tcs-playing trackIdx=%d ts_ns=%llu delta_since_changed_ms=%.3f",
                self.currentTrackIndex, now, deltaMs)
          // One-shot: clear the marker so a subsequent buffering/play
          // cycle within the same track doesn't re-log against the
          // stale baseline.
          self.gaplessLogLastChangedAtNs = 0
        }
        #endif
        self.emitStateChangeIfChanged()
      }
    }
    // Bootstrap to replicate what `.initial` would have delivered.
    // It consumes at most one staked `pendingStateChangeReason`:
    // `reasonForComputedState` claims the slot for every computed
    // state but `.buffering`, and does so whether or not `emitState`
    // clears its dedup guard — so a reason staked before the install
    // degrades the next emit to `.system`.
    //
    // Both callers tolerate that. `configure()` reaches here with an
    // empty slot: `internalDestroy` clears it on every re-configure,
    // and on a first-ever configure any staked reason predates the
    // engine's existence. `applyEngineSwapForMode` has no such
    // guarantee, but its worst case is the same `.system` degradation
    // `wireRemoteCommands` documents for its own stamp race.
    //
    // The swap path must not stake a reason to compensate: an engine
    // swap is a system transition, and the incoming engine's first
    // emit must not inherit a cause belonging to the outgoing one.
    playerQueue.async { [weak self] in
      self?.emitStateChangeIfChanged()
    }

    NotificationCenter.default.addObserver(
      self, selector: #selector(handlePlayerItemFailedToPlayToEndTime(_:)),
      name: .AVPlayerItemFailedToPlayToEndTime, object: nil)
    // Buffer underrun. With `automaticallyWaitsToMinimizeStalling` off nothing
    // restarts playback on its own, so the refill has to re-issue play.
    NotificationCenter.default.addObserver(
      self, selector: #selector(handlePlaybackStalled(_:)),
      name: AVPlayerItem.playbackStalledNotification, object: nil)
    // Explicit AVPlayerItemDidPlayToEndTime observer. The SOLE
    // authoritative signal for natural end-of-track,
    // distinguishing real auto-advance from chain-advance bounces
    // (AVQueuePlayer walking past unbuffered items during a rebuild
    // does not fire DidPlayToEndTime). The handler sets
    // `didPlayToEndPending` for `handleCurrentItemDidChange` to gate
    // the matchTrackIndex resync, AND handles the repeat-track mode
    // auto-rewind (seek to zero + replay). Item-status observation
    // (initial-buffer leniency + load-failure → onError) is
    // installed by `attachStatusObserverIfArmed`, which runs on
    // every currentItem swap from the gapless KVO.
    NotificationCenter.default.addObserver(
      self, selector: #selector(handlePlayerItemDidPlayToEndTime(_:)),
      name: .AVPlayerItemDidPlayToEndTime, object: nil)
    // Access-log entries arrive multiple times per second during
    // streaming (per network transfer chunk). Two consumers share the
    // notification: (1) the streaming bitrate fallback chain
    // `handleNewAccessLogEntry → reresolveNowPlayingFormatForBitrate →
    // NowPlayingFormatExtractor.extract → readStreamingBitrate`
    // re-resolves the now-playing format for items whose cached
    // bitrate is still null (CBR stream where `estimatedDataRate` came
    // back zero); (2) the DEBUG gapless logger records stalls /
    // observed bitrate / transfer duration against the transition
    // timeline. The error-log subscription is DEBUG-only — it has no
    // production consumer.
    NotificationCenter.default.addObserver(
      self, selector: #selector(handleNewAccessLogEntry(_:)),
      name: AVPlayerItem.newAccessLogEntryNotification, object: nil)
    #if DEBUG
    NotificationCenter.default.addObserver(
      self, selector: #selector(handleNewErrorLogEntry(_:)),
      name: AVPlayerItem.newErrorLogEntryNotification, object: nil)
    #endif
  }

  internal func tearDownEventObservers() {
    timedMetadataObservation?.invalidate()
    timedMetadataObservation = nil
    if let pair = progressTimeObserver {
      pair.player.removeTimeObserver(pair.token)
      progressTimeObserver = nil
    }
    timeControlStatusObserver?.invalidate()
    timeControlStatusObserver = nil
    NotificationCenter.default.removeObserver(
      self, name: .AVPlayerItemFailedToPlayToEndTime, object: nil)
    NotificationCenter.default.removeObserver(
      self, name: AVPlayerItem.playbackStalledNotification, object: nil)
    NotificationCenter.default.removeObserver(
      self, name: .AVPlayerItemDidPlayToEndTime, object: nil)
    NotificationCenter.default.removeObserver(
      self, name: AVPlayerItem.newAccessLogEntryNotification, object: nil)
    #if DEBUG
    NotificationCenter.default.removeObserver(
      self, name: AVPlayerItem.newErrorLogEntryNotification, object: nil)
    #endif
  }

  /// Start polling the engine for progress at the same 0.5s
  /// cadence the AVPlayer periodic time observer would. Used when
  /// `self.player` is nil (CrossfadeEngine active). Idempotent —
  /// repeat calls drop the prior timer first.
  internal func startProgressFallbackTimer() {
    stopProgressFallbackTimer()
    let timer = DispatchSource.makeTimerSource(queue: playerQueue)
    timer.schedule(
      deadline: .now() + Self.progressIntervalSeconds,
      repeating: Self.progressIntervalSeconds)
    timer.setEventHandler { [weak self] in
      // These four calls must stay in step with the periodic-observer
      // block in `installEventObservers` — the two progress paths emit
      // the same events and only one of them is live at a time.
      self?.emitProgressIfSubscribed()
      self?.processMilestones()
      self?.recomputeBufferState()
      self?.recomputeFullyBuffered()
    }
    progressFallbackTimer = timer
    // The source is resumed exactly once and never suspended — a suspended
    // source traps libdispatch on release.
    timer.resume()
  }

  /// Caller must be on the queue that serialises player state — cancelling
  /// from the source's own queue is what
  /// guarantees no further tick.
  internal func stopProgressFallbackTimer() {
    progressFallbackTimer?.cancel()
    progressFallbackTimer = nil
  }
}
