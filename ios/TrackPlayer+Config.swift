import Foundation
import NitroModules

extension TrackPlayer {
  // MARK: - Playback configuration — some stubs, some lifecycle-adjacent

  func setPlaybackMode(mode: PlaybackMode) throws -> Promise<Void> {
    return enqueueThrowing {
      let (newState, effect) = try self.playbackModeStateMachine.setRequested(
        state: self.playbackModeState,
        requested: mode
      )
      self.playbackModeState = newState
      switch effect {
      case .noOp:
        break
      case .immediateSwap:
        self.applyEngineSwapForMode(newState.active)
      }
    }
  }

  func getPlaybackMode() throws -> PlaybackMode {
    // Lock-guarded, so this answers without hopping. A `playerQueue.sync` here
    // parks the JS thread behind whatever the queue is running, which can be a
    // full queue rebuild.
    return self.playbackModeState.active
  }

  /// Build a fresh engine of the requested kind and swap it in
  /// place of the active engine. The new engine starts empty; the
  /// canonical queue model is re-installed via the engine surface
  /// so playback continues from the current track / position.
  ///
  /// JS-facing event sources differ per engine. GaplessEngine ties
  /// `self.player` to the underlying `AVQueuePlayer` and the KVO
  /// chain installed by `installGaplessObservers` /
  /// `installEventObservers` carries onTrackChange / onStateChange
  /// / onError. CrossfadeEngine sets `self.player` to nil and
  /// fires `PlaybackEngineDelegate` callbacks that this class
  /// implements (extension at the end of the file); the delegate
  /// is wired below. Both engines surface progress through
  /// `emitProgressIfSubscribed`, polymorphic over self.player vs
  /// engine state.
  internal func applyEngineSwapForMode(_ active: PlaybackMode) {
    // Before anything is read from the old engine: the new run is built
    // against whatever listener is live once this returns.
    reviveReadThroughServerIfDead()
    let oldEngine = self.engine
    // Capture pre-swap state — the engine swap releases the old
    // engine; reading `newEngine.currentPositionSeconds` /
    // `isPlaying` after would always return 0 / false. The
    // wasPlaying capture is what carries playback continuity
    // across the immediate engine swap.
    let resumePosition = oldEngine?.currentPositionSeconds ?? 0
    let resumeIndex = self.currentTrackIndex
    // The transport's intent, not the old engine's audible state: a leg that
    // is buffering, or mid-crossfade with its outgoing item finished, reports
    // not-playing while the consumer is very much still playing. Swapping on
    // that reading silently pauses them.
    let wasPlaying = self.wantsToPlay
    // Anything held back from the old engine goes with it: the new one is
    // installed from the queue model, which already carries every mutation.
    self.engineReconcileDeferred = false

    let newEngine: PlaybackEngine
    switch active.kind {
    case .gapless:
      newEngine = GaplessEngine(
        allowsExternalPlayback: self.config.allowsExternalPlayback ?? false)
    case .crossfade:
      // PlaybackModeStateMachine.requireValid guarantees this is
      // non-nil + within [1000, 12000] when `kind == .crossfade`.
      // Fail loud rather than silently degrading to 0 (which would
      // route through CrossfadeEngine's hard-cut path + skip
      // engineCrossfadeDidBegin entirely).
      guard let durationMs = active.crossfadeDurationMs else {
        preconditionFailure("PlaybackMode(crossfade) missing crossfadeDurationMs at engine construction")
      }
      let durationSeconds = durationMs / 1000.0
      newEngine = CrossfadeEngine(
        crossfadeDurationSeconds: durationSeconds, confinedTo: self.playerQueue,
        allowsExternalPlayback: self.config.allowsExternalPlayback ?? false)
    }

    self.engine = newEngine
    // GaplessEngine path: keep the established self.player KVO
    // chain (currentItem / timeControlStatus / item.status) as
    // the canonical event source. CrossfadeEngine path:
    // self.player stays nil; the engine fires PlaybackEngineDelegate
    // callbacks for track-change / state-change / format-ready /
    // failed / play-to-end / queue-end and progress is sourced
    // from the engine via a timer-driven fallback.
    if let gapless = newEngine as? GaplessEngine {
      self.player = gapless.player
      self.gaplessFlipArmed = true
      self.installGaplessObservers(on: gapless.player)
      self.installEventObservers(on: gapless.player)
      self.stopProgressFallbackTimer()
      // Gapless does not need the engine delegate — the underlying
      // self.player KVO chain is the canonical event source.
      newEngine.delegate = nil
    } else {
      self.tearDownGaplessObservers()
      self.tearDownEventObservers()
      self.player = nil
      newEngine.delegate = self
      self.startProgressFallbackTimer()
    }

    // Re-wire the shared audio-tap provider to the new engine's
    // audio-mix provider slot.
    AudioTapProvider.shared.wireToPlaybackEngine(newEngine)

    // Re-apply canonical config to the freshly-built engine BEFORE the
    // queue install / play — it starts at engine defaults (repeat off,
    // volume 1, speed 1), so without this a gapless↔crossfade swap
    // silently drops the user's settings.
    newEngine.setRepeatMode(self.repeatModeState)
    newEngine.setVolume(self.volumeState)
    // Carry the user's speed into the new engine. When resuming playback,
    // apply it live via `setPlaybackSpeed` (sets `AVPlayer.rate`) BEFORE
    // `setItems` — GaplessEngine.setItems captures the player's rate to
    // restore it across the queue rebuild, so a speed applied after
    // setItems/play() would be clobbered by that async restore. When
    // paused, seed it only (no rate write, so the engine stays paused);
    // play() applies it on the next resume.
    if wasPlaying {
      newEngine.setPlaybackSpeed(self.playbackSpeedState)
    } else {
      newEngine.seedPlaybackSpeed(self.playbackSpeedState)
    }
    newEngine.setPitchCorrectionMode(self.pitchCorrectionModeState)

    // Re-install the queue on the new engine — the same window every other
    // install uses, not the whole queue: a crossfade engine holds every item
    // it is given, and a long queue would put every track's asset load
    // through the read-through server at once. `topUpWindow` extends it.
    if resumeIndex >= 0, resumeIndex < self.tracks.count {
      let positions = QueueWindowArithmetic.slice(
        currentIndex: resumeIndex,
        trackCount: self.tracks.count,
        trail: self.engineTrail,
        wraps: false
      )
      let sliceIds = positions.map { self.queueItemIds[$0] }
      let items = AVQueueBuilder.buildPlayerItems(
        tracks: positions.map { self.tracks[$0] },
        queueItemIds: sliceIds,
        config: self.config,
        server: self.routingServer
      )
      // Seated the way every other install is; with nothing playable at or
      // after the current position the new engine stays empty rather than
      // replaying the trail.
      let currentId = self.queueItemIds[resumeIndex]
      if let startIndex = Self.seatIndex(in: items, sliceIds: sliceIds, currentId: currentId) {
        // The playhead carries across only onto the track that was playing;
        // any other seat is a track change and starts at zero.
        let position = items[startIndex].queueItemId == currentId ? resumePosition : 0
        newEngine.setItems(
          items,
          startIndex: startIndex,
          startPositionSeconds: position
        )
        // Honour pre-swap playing state so a mid-play setPlaybackMode keeps
        // playing on the new engine — through `beginPlayback`, which every
        // path that starts playback goes through, and not while the session
        // is interrupted.
        if wasPlaying, !self.isInterrupted {
          self.beginPlayback()
        }
      }
    }

    // The new engine holds only items built above, or none.
    readThroughReinstallPending = false
    // Whatever was seated above, the engine being replaced goes: its items
    // stop loading and its observers come down.
    oldEngine?.release()
  }

  func setPlaybackSpeed(rate: Double) throws -> Promise<Void> {
    return enqueue {
      // Clamp to a 0.25 floor: rate=0 on AVPlayer is functionally equivalent
      // to pause(), which would surprise consumers calling
      // `setPlaybackSpeed(0)` for a slow-down effect. Cross-platform contract
      // documented in TrackPlayer.nitro.ts. A value that is not a number
      // leaves the current rate alone.
      guard !rate.isNaN else { return }
      let safeRate = Float(max(0.25, min(rate, 32)))
      self.playbackSpeedState = safeRate
      // Apply to the LIVE rate only when the user wants playback (already
      // playing, OR buffering toward play). On a paused/idle engine writing
      // `AVPlayer.rate` starts it (rate > 0 == "play"), so with no play intent
      // seed the speed instead (no rate write); `play()` applies it on the next
      // resume. This keeps a persisted speed applied at app start — before a
      // restored `setQueue` — from auto-starting a paused queue. `wantsToPlay`
      // (not just `isPlaying`) covers the buffering-toward-play window so a
      // speed change there still applies live.
      if self.wantsToPlay || self.engine?.isPlaying == true {
        self.engine?.setPlaybackSpeed(safeRate)
      } else {
        self.engine?.seedPlaybackSpeed(safeRate)
      }
    }
  }
  func setPitchCorrectionMode(mode: PitchCorrectionMode) throws -> Promise<Void> {
    return enqueue {
      self.pitchCorrectionModeState = mode
      self.engine?.setPitchCorrectionMode(mode)
    }
  }
  func getPitchCorrectionMode() throws -> PitchCorrectionMode {
    // Lock-guarded — see `getPlaybackMode`.
    return self.pitchCorrectionModeState
  }
  func setVolume(volume: Double) throws -> Promise<Void> {
    return enqueue {
      // Clamped to the documented range before either branch, so the cast and
      // local paths cannot diverge on the same input, and a value that is not
      // a number cannot poison the stored level that `getVolume` and the
      // sleep-timer fade read back.
      guard !volume.isNaN else { return }
      let level = Float(max(0, min(1, volume)))
      if CastTransportRouter.routeSetVolume(value: Double(level)) { return }
      self.volumeState = level
      self.engine?.setVolume(level)
    }
  }

  func setRepeatMode(mode: RepeatMode) throws -> Promise<Void> {
    return enqueue {
      self.repeatModeState = mode
      // Forward to the active engine. The CrossfadeEngine needs the
      // mode to suppress its look-ahead fade + loop the current item
      // under `.track` (AVPlayer has no native repeat); GaplessEngine
      // treats it as a no-op (repeat-track replay lives in
      // handlePlayerItemDidPlayToEndTime).
      self.engine?.setRepeatMode(mode)
      // Both canSkipNext and canSkipPrevious depend on repeat mode
      // (e.g. at idx 0 / last index, OFF↔QUEUE flips the boolean
      // because QUEUE wraps). Recompute is cheap; dedup keeps the
      // listener silent when the (prev, next) tuple is unchanged.
      // The installed run is forward-only, so a repeat-mode change does not
      // alter which positions it holds and there is nothing to reconcile. That
      // stops being true the moment the run carries the wrap target, and the
      // reconcile has to come back with it.
      self.recomputeCapabilities()
    }
  }

  func setRemoteControls(options: RemoteControlOptions) throws -> Promise<Void> {
    return enqueue {
      // Guard nonsensical negatives (a negative forward interval would seek
      // backward) and values too large to convert to milliseconds. Out-of-range
      // is clamped rather than rejected.
      let fwd = InputGuards.sanitisedSeekPosition(options.forwardJumpInterval)
        .map { max(0, $0) } ?? 0
      let back = InputGuards.sanitisedSeekPosition(options.backwardJumpInterval)
        .map { max(0, $0) } ?? 0
      self.remoteSkipMode = options.skipMode
      self.remoteForwardInterval = fwd
      self.remoteBackwardInterval = back
      self.remoteCommands.setRemoteControls(
        mode: options.skipMode,
        forwardInterval: fwd,
        backwardInterval: back)
      // Re-apply the cached grey-out: switching to track mode re-enables
      // next/prev, which must reflect the queue-boundary capability
      // (setSkipCapability is otherwise only called on queue/repeat change,
      // and no-ops in interval mode).
      self.remoteCommands.setSkipCapability(
        canSkipNext: self.cachedSkipCapability.canSkipNext,
        canSkipPrevious: self.cachedSkipCapability.canSkipPrevious)
    }
  }

  // ReplayGain mode — thin wrapper around `AudioTapProvider.shared`.
  // The provider owns the per-item state set + recompute walk; this
  // Hybrid surface is the JS-facing setter / getter only.
  func setReplayGainMode(mode: ReplayGainMode) throws -> Promise<Void> {
    return enqueue {
      AudioTapProvider.shared.setReplayGainMode(mode)
      // Re-emit the active item's NowPlayingFormat so
      // `onNowPlayingFormatChange` subscribers (e.g. the
      // `useNowPlayingFormat` hook) observe the new `replayGainActive`
      // flag without waiting for the next track change. Mirrors the
      // Android path's `refreshNowPlayingFormatForActiveItem` call from
      // its own `setReplayGainMode`.
      guard let item = self.activeMediaItem else { return }
      self.emitCurrentNowPlayingFormat(for: item)
    }
  }
  func getReplayGainMode() throws -> ReplayGainMode {
    return AudioTapProvider.shared.getReplayGainMode()
  }
}
