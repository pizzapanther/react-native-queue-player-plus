import AVFoundation
import NitroModules

extension TrackPlayer {
  // MARK: - Transport — pass-through to AVQueuePlayer.

  func play() throws -> Promise<Void> {
    return enqueue {
      if CastTransportRouter.routePlay() { return }
      // Before the stuck-recovery block, which rebuilds on whatever listener is
      // live; a play from the lock screen or CarPlay arrives without the app
      // coming to the foreground. Restarting does not touch the reported-failure
      // set the block reads.
      self.reviveReadThroughServerIfDead()
      // Asking for this track again re-arms its routed-failure recovery.
      if let qid = self.queueItemIds[safe: self.currentTrackIndex] {
        self.routedRecoveryAttemptedQueueItemIds.remove(qid)
      }
      // Stuck-recovery: the queue holds on a track whose failure was reported,
      // and pressing play is asking for that track again — so it gets the
      // allowance the consumer configured back and a rebuild. Not a hard-coded
      // 1: `autoRetries: 0` asked for none.
      if let qid = self.reportedFailureOnCurrentTrack {
        // Asking for this track to play is incompatible with the queue being
        // over, so `reachedQueueEnd` cannot survive it.
        self.reachedQueueEnd = false
        self.retryAttemptsRemaining[qid] = self.effectiveAutoRetries()
        // This track only. A standby leg's report is a different track's, and
        // discarding it here would hand the listener that failure a second
        // time when the engine seats it.
        self.reportedFailureQueueItemIds.remove(qid)
        // A scheduled attempt for this item is superseded by the rebuild below.
        // Reachable because the two are set by different errors on the same
        // item: a reported failure leaves the report standing, and a later
        // failure carrying a different code is transient enough to schedule an
        // attempt. Leaving it would fire a second rebuild behind this one.
        if self.pendingRetryQueueItemId == qid { self.pendingRetryQueueItemId = nil }
        self.lastErrorQueueItemId = nil
        self.lastErrorCode = nil
        // Rebuild whenever an engine is live — the rebuild is
        // engine-agnostic, and the failed item has to be
        // reconstructed on either engine for the retry to reach it.
        // Forced: the failed item keeps its `queueItemId`, so a rebuild
        // that compares identity would find nothing to do.
        if self.engine != nil {
          self.performingMutation {
            self.fullRebuildPlayerQueue(forcingReinstall: true)
          }
        }
      }
      // A no-op when the block above rebuilt, which installs a fresh run.
      self.reinstallAfterReadThroughRestart()
      // At the end of the queue there is nothing to play: the engine is
      // parked on the finished track (or drained), and writing a rate onto
      // it would report `.playing` for a moment or, on some OS versions,
      // re-post the item's end. A skip, a seek or a fresh queue leaves this
      // state; play() alone restarts nothing.
      if self.reachedQueueEnd, self.currentTrackIndex >= 0 { return }
      self.pendingStateChangeReason = self.pendingStateChangeReason ?? .user
      self.wantsToPlay = true
      // An explicit play ends an interruption from the user's side: the
      // system does not always deliver `.ended` (an app suspended mid-call
      // never sees it), and the flag would otherwise keep retries, window
      // refills and network recovery disabled for the rest of the session.
      self.isInterrupted = false
      self.beginPlayback()
      self.recomputeBufferState()
    }
  }

  func retry() throws -> Promise<Void> {
    return enqueue {
      // During cast the receiver owns playback — forward a play so a failed
      // receiver item re-attempts.
      if CastTransportRouter.routePlay() { return }
      // A user asking for a retry has a reason, and the library is not the one
      // to judge it. This is the manual attempt once the automatic one is
      // spent, so gating it on the player's state would gate recovery on the
      // state of a player that has already failed to recover — and a wedged
      // decoder still reporting `playing` is exactly when someone reaches for
      // the button.
      //
      // Only the bounds remain: no current track is genuinely nothing to
      // retry, which is not second-guessing the request.
      guard self.currentTrackIndex >= 0,
            self.currentTrackIndex < self.queueItemIds.count else { return }
      let qid = self.queueItemIds[self.currentTrackIndex]
      // The forced rebuild below installs on whatever listener is live.
      self.reviveReadThroughServerIfDead()
      self.routedRecoveryAttemptedQueueItemIds.remove(qid)
      // Capture the pre-failure position so a mid-track failure resumes where
      // it stopped (the rebuild below reloads the item from 0). Matches
      // Android's retryFailedItem position-restore.
      let resumePos = self.engine?.currentPositionSeconds ?? 0
      // Asking for this track to play is incompatible with the queue being over,
      // and a queue whose only track failed to load has already drained: without
      // this the rebuild seats an item into a queue that believes it is finished.
      self.reachedQueueEnd = false
      // The configured budget, not a hard-coded 1: this restores the track's
      // automatic allowance for whatever fails *next*, and a consumer who set
      // `autoRetries: 0` asked for none.
      self.retryAttemptsRemaining[qid] = self.effectiveAutoRetries()
      // A scheduled attempt for this item is superseded by the rebuild below;
      // leaving it set would suppress the rebuilt item's own failure until the
      // earlier attempt fired.
      if self.pendingRetryQueueItemId == qid { self.pendingRetryQueueItemId = nil }
      self.lastErrorQueueItemId = nil
      self.lastErrorCode = nil
      // This track only, for the reason `play()` gives above.
      self.reportedFailureQueueItemIds.remove(qid)
      // Rebuild whenever an engine is live — the rebuild is
      // engine-agnostic, and the failed item has to be reconstructed
      // on either engine for the retry to reach it. Forced: the failed
      // item keeps its `queueItemId`, so a rebuild that compares
      // identity would find nothing to do.
      if self.engine != nil {
        self.performingMutation {
          self.fullRebuildPlayerQueue(forcingReinstall: true)
        }
      }
      self.pendingStateChangeReason = self.pendingStateChangeReason ?? .user
      self.wantsToPlay = true
      self.beginPlayback()
      if resumePos.isFinite, resumePos > 0 {
        _ = try? self.seekTo(position: resumePos)
      }
      self.recomputeBufferState()
    }
  }

  func pause() throws -> Promise<Void> {
    return enqueue {
      if CastTransportRouter.routePause() { return }
      self.pendingStateChangeReason = self.pendingStateChangeReason ?? .user
      self.wantsToPlay = false
      // An explicit pause outranks a pending stall recovery: without this the
      // buffer refilling afterwards would restart audio the user just stopped.
      self.isRecoveringFromStall = false
      self.engine?.pause()
      self.recomputeBufferState()
    }
  }

  /// "Stop" = pause + seek to start of current item. Does NOT clear
  /// the queue — consumers wanting a full reset call clearQueue().
  func stop() throws -> Promise<Void> {
    return enqueue {
      if CastTransportRouter.routeStop() { return }
      guard let engine = self.engine else { return }
      self.pendingStateChangeReason = self.pendingStateChangeReason ?? .user
      self.wantsToPlay = false
      self.hasStartedPlaying = false
      engine.stop()
      self.recomputeBufferState()
    }
  }

  /// Seek within the current track. Position in seconds; negative
  /// values clamp to 0, values past the duration clamp to duration.
  func seekTo(position: Double) throws -> Promise<Void> {
    // Enqueued at call time, like every other transport method, so a seek
    // reaches the player in the order JS issued it. The promise resolves once
    // the seek's own completion has landed, which is why this builds the
    // promise by hand rather than using `enqueue`.
    let promise = Promise<Void>()
    playerQueue.async {
      // Match Android (`TrackPlayer.kt` seekToInternal): non-finite
      // (NaN / ±Infinity) inputs are silent no-ops. See
      // `InputGuards.sanitisedSeekPosition` for the rationale.
      guard let position = InputGuards.sanitisedSeekPosition(position) else {
        promise.resolve()
        return
      }
      if CastTransportRouter.routeSeekTo(positionMs: Int64(position * 1000.0)) {
        promise.resolve()
        return
      }

      // Every player + queue read and the seek itself run on this one hop, so
      // the player never crosses a concurrency boundary. The return value
      // carries whether a seek was issued: the crossfade branch and the
      // drained-gapless branch complete in place and yield false.
      //
      // Named rather than inlined so the two early-return branches and the
      // seek branch share one exit.
      let issueSeek: () -> Bool = {
        // Engine-aware seek: under CrossfadeEngine `self.player` is
        // nil. Without this branch every seek call would silently
        // no-op once crossfade mode is active, breaking transport
        // controls + auto-advance test coverage.
        if self.player == nil {
          guard let engine = self.engine else { return false }
          // Seeking replays from a position — no longer at the end of the
          // queue. (Gapless no-ops at end-of-queue with no current item below.)
          self.reachedQueueEnd = false
          let duration = engine.currentDurationSeconds
          let clamped: Double
          if position < 0 {
            clamped = 0
          } else {
            clamped = (duration > 0 && position > duration) ? duration : position
          }
          let seekTarget = self.clampToBufferedIfEnabled(clamped)
          self.pendingStateChangeReason = self.pendingStateChangeReason ?? .user
          let engineIdx: Int
          if let installed = self.engineIndex(forQueuePosition: self.currentTrackIndex) {
            engineIdx = installed
          } else {
            // Active track absent from the engine — reinstall it, then seek
            // within the reinstalled run so the position is applied rather
            // than dropped with a resolved promise.
            self.performingMutation { self.fullRebuildPlayerQueue() }
            guard let reinstalled = self.engineIndex(forQueuePosition: self.currentTrackIndex)
            else { return false }
            engineIdx = reinstalled
          }
          engine.seek(toIndex: engineIdx, position: seekTarget)
          self.milestoneTracker.seek(
            durationSec: self.effectiveMilestoneDuration(playerDuration: duration),
            positionSec: seekTarget)
          self.nowPlayingInfo.refreshPositionAndRate(self.nowPlayingSnapshot(), trigger: .seek)
          return false
        }

        guard let player = self.player else { return false }
        // A gapless player drained at the end of the queue has no item to
        // seek in: reinstall the finished track first, as a same-index skip
        // does, so a seek after the end replays it on every engine. Seeking
        // is what leaves the ended state, so the flag clears here.
        if player.currentItem == nil, self.reachedQueueEnd,
           self.currentTrackIndex >= 0, self.currentTrackIndex < self.tracks.count {
          self.reachedQueueEnd = false
          self.performingMutation { self.fullRebuildPlayerQueue() }
        }
        guard let item = player.currentItem else { return false }
        let itemDuration = CMTimeGetSeconds(item.duration)
        let clamped: Double
        if position < 0 {
          clamped = 0
        } else {
          clamped = (itemDuration.isFinite && position > itemDuration) ? itemDuration : position
        }
        // Apply the opt-in buffered clamp on top of the 0/duration clamp — caps
        // the target at the buffered edge when the seek is past what's downloaded.
        let seekTarget = self.clampToBufferedIfEnabled(clamped)
        // Consume jumped-over milestones BEFORE issuing the (async) seek so a
        // time-jump progress tick that fires during the seek can't emit the
        // thresholds the seek skips over.
        self.milestoneTracker.seek(
          durationSec: self.effectiveMilestoneDuration(
            playerDuration: (itemDuration.isFinite && itemDuration > 0) ? itemDuration : 0),
          positionSec: seekTarget)
        // Stash `.user` reason BEFORE the seek so any state transition
        // the seek triggers (e.g. playable → buffering on a long jump)
        // gets the correct origin. Matches the play / pause / stop pattern.
        self.pendingStateChangeReason = self.pendingStateChangeReason ?? .user
        player.seek(
          to: CMTime(seconds: seekTarget, preferredTimescale: CMTimeScale(NSEC_PER_SEC)),
          toleranceBefore: .zero,
          toleranceAfter: .zero
        ) { _ in
          // This closure captures `player`, so a teardown that drops every
          // other reference cannot deallocate it with the seek still pending.
          // AVFoundation invokes the handler when the seek completes or is
          // interrupted, and the capture is released with it.
          _ = player
          self.playerQueue.async {
            // Surface the new scrubber position to the lock-screen /
            // control-center immediately. The KVO state-change emit covers
            // transitions (e.g. paused → buffering on a long jump) but a
            // same-state seek won't trigger an emit; without this the
            // lock-screen scrubber sits at the pre-seek position until the
            // next 10s periodic tick.
            self.nowPlayingInfo.refreshPositionAndRate(self.nowPlayingSnapshot(), trigger: .seek)
            promise.resolve()
          }
        }
        return true
      }
      // A branch that completed in place has nothing pending, so the promise
      // resolves here; the seek branch resolves from its completion instead.
      if !issueSeek() { promise.resolve() }
    }
    return promise
  }
}
