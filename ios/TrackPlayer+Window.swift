extension TrackPlayer {
  /// Bring the engine's installed run back in line as the playhead moves.
  ///
  /// Reconciling against `QueueWindowArithmetic.slice` rather than measuring
  /// from an anchor: a run that wraps ends at a lower absolute position than
  /// it starts, so any comparison of the two as integers reads it as
  /// backwards. The reconcile keeps what is still wanted, drops what has
  /// fallen off the trail, and builds the rest.
  internal func topUpWindow() {
    guard let engine = self.engine else { return }
    // Every branch of the reconcile changes the installed items, and each of
    // those cancels an in-flight fade. Extending the run can wait for the fade
    // to finish; cutting a crossfade short to do it cannot be undone.
    guard !engine.isFadePendingOrActive else { return }
    // This extends a run; it never creates one. A drained engine is either a
    // queue that has ended or one the caller is about to seat itself, and
    // installing into it here would put the finished track back and let the
    // rate the engine restores carry it on playing.
    guard !engine.allMediaItems.isEmpty, !self.reachedQueueEnd else { return }
    // A current position that has failed is not put back, whether its failure
    // has been reported or an attempt for it is still scheduled. `AVQueuePlayer`
    // consumes a failed item, and when the run holds something behind it the
    // player advances there instead of draining — so the end verdict never runs
    // and this reconcile is what rebuilds. It rebuilds from `currentTrackIndex`,
    // still the failed position because the resync needs a play-to-end and a
    // failure fires `AVPlayerItemFailedToPlayToEndTime` instead, and a reinstall
    // is exactly what a missing current position asks for. The rebuilt item
    // fails the same way and its error is swallowed — as a repeat, or as an echo
    // of the pending attempt — so the rebuild would repeat unannounced.
    guard self.reportedFailureOnCurrentTrack == nil else { return }
    if let currentId = self.queueItemIds[safe: self.currentTrackIndex],
       self.pendingRetryQueueItemId == currentId {
      return
    }
    self.performingMutation { self.fullRebuildPlayerQueue() }
  }

  /// Notices that the gapless player has auto-advanced past its last item,
  /// and hands the decision to `applyEndVerdict`.
  internal func detectQueueEnd() {
    // Engine-aware "is the active item nil" check. With
    // CrossfadeEngine `self.player` is nil by design — reading it
    // alone would mis-detect every mid-play engine swap as
    // queue-end and fire a spurious onQueueEnd + emitState(.ended).
    let activeItemNil: Bool
    if self.player != nil {
      activeItemNil = self.player?.currentItem == nil
    } else if let engine = self.engine {
      activeItemNil = engine.currentMediaItem == nil
    } else {
      activeItemNil = true
    }
    guard activeItemNil else { return }
    applyEndVerdict()
  }

  /// Play `currentTrackIndex` from its start and announce it.
  ///
  /// A rebuild replaces only the positions that differ, so it cannot be relied
  /// on for either half of this: when the position being re-seated is already
  /// the one installed, nothing is replaced, the seek is what restarts it, and
  /// no `currentItem` change fires to carry the track change out. Dispatching
  /// here is deduped against `lastEmittedQueueItemId`, so a rebuild that *did*
  /// replace the item still emits exactly once.
  private func restartCurrentPosition() {
    if let engine = self.engine,
       let engineIdx = engineIndex(forQueuePosition: self.currentTrackIndex) {
      engine.seek(toIndex: engineIdx, position: 0)
    }
    dispatchTrackChange(reason: .autoAdvance)
  }

  /// Decide and apply what a drained engine means for the queue.
  ///
  /// Both ends funnel here — the gapless player's `currentItem` going nil, and
  /// the crossfade engine reporting its last item played out — so the queue
  /// reaches the same verdict whichever engine is installed. The engines
  /// report that they drained; they do not decide whether the queue is over.
  ///
  /// A drained engine exposes no items, so the anchor cannot be derived from
  /// them. `matchTrackIndex(forCurrentItem: nil)` is nil at a drain, which
  /// leaves `currentTrackIndex` on the track that just finished — the furthest
  /// position the queue actually reached.
  internal func applyEndVerdict() {
    // `verdict` is specified against a queue that has something to end.
    guard !self.tracks.isEmpty else { return }
    // A queue that has ended stays ended: a further drain report — a rate
    // written onto a leg parked at its end re-posts the item's end — must
    // not re-decide against a queue that has since grown and seat the added
    // track. A skip, a seek or a fresh queue clears the flag first.
    guard !self.reachedQueueEnd else { return }
    // A drain whose current item failed to load with an auto-retry already
    // scheduled is not the end of the queue — the pending retry rebuilds and
    // resumes that item. `AVQueuePlayer` consumes a failed item (its
    // `actionAtItemEnd` is `.advance`) and drains to `currentItem == nil`,
    // which lands here; deciding a queue-end would clear the play intent the
    // retry reads, leaving the recovered track paused. The scheduled retry
    // clears `pendingRetryQueueItemId` and re-seats the item, so this only
    // holds for the brief window between the failure and the attempt.
    //
    // Relies on the item's `.failed` status KVO — which stakes
    // `pendingRetryQueueItemId` in `dispatchErrorOrRetry` — being delivered
    // before the `currentItem`→nil drain KVO that reaches here. The status
    // change is what causes the advance-to-nil, so it is observed first. The
    // `retry-recovery` demo suite is the integration coverage for that order.
    if let currentId = self.queueItemIds[safe: self.currentTrackIndex],
       self.pendingRetryQueueItemId == currentId {
      return
    }
    // A drain that follows the current item's reported failure is that failure,
    // not the end of the queue. The same `.advance` walks a failed item out of
    // the player, and with nothing installed behind it the queue drains to
    // `currentItem == nil` and lands here. Deciding a verdict would move past a
    // track the consumer has just been told failed — and when the tracks behind
    // it fail the same way, the advance, rebuild and resume repeat with nothing
    // counting them, wrapping forever under repeat-all. The queue stays where it
    // is, in `.error`, until `play()` or `retry()` asks for the track again.
    if self.reportedFailureOnCurrentTrack != nil {
      // Park the engine, as the queue-end branch below does and for the same
      // reason: neither engine drops its rate when it runs out of items, so a
      // leg left running would start whatever lands on it next. The gapless
      // failure path pauses the `AVQueuePlayer` itself, but the crossfade one
      // has no `AVQueuePlayer` to pause.
      self.engine?.pause()
      return
    }
    var verdict = QueueWindowArithmetic.verdict(
      drained: true,
      atLogicalTail: QueueWindowArithmetic.isAtLogicalTail(
        anchorPosition: self.currentTrackIndex, trackCount: self.tracks.count),
      repeatMode: repeatModeAsSkipMode())

    // Re-decided in place when the position a verdict seats cannot be built:
    // `.windowDrained` advances the anchor each time round, so it reaches the
    // tail; `.refillCurrent` cannot advance (repeat-track re-seats the same
    // position), so an unplayable current track ends the queue instead.
    while true {
      switch verdict {
      case .notEnded:
        return
      case .wrapToStart:
        self.currentTrackIndex = 0
        self.pendingTrackChangeReason = .autoAdvance
        // The queueItemId at index 0 is unchanged across a single-track wrap,
        // so without this clear the next dispatch would dedup-swallow the
        // re-emission and the consumer would see no track change.
        self.lastEmittedQueueItemId = nil
        self.performingMutation { self.fullRebuildPlayerQueue() }
        // Restart position 0 explicitly: on a single-track queue the wrap target
        // is the item already installed, so the rebuild replaces nothing.
        restartCurrentPosition()
        // Each engine restores its own rate across a rebuild only while its
        // play intent stands, and a reconcile that removes the item the
        // crossfade engine's leading leg held drops that intent with it — so
        // the resume is asked for here. Gated on the transport intent because
        // a rebuild cannot tell "was playing" from "was paused" by rate alone,
        // and on the interruption flag because the system may not permit
        // playback right now.
        if self.wantsToPlay, !self.isInterrupted { self.beginPlayback() }
        return
      case .windowDrained, .refillCurrent:
        // The engine ran out with queue left. `currentTrackIndex` still names
        // the track that just finished, so `.windowDrained` moves past it while
        // `.refillCurrent` re-seats it. Clearing `lastEmittedQueueItemId` keeps
        // the refilled track from being dedup-swallowed, and the drained player
        // has parked at rate 0, so it has to be told to resume.
        if verdict == .windowDrained {
          self.currentTrackIndex = min(self.currentTrackIndex + 1, self.tracks.count - 1)
        }
        self.pendingTrackChangeReason = .autoAdvance
        self.lastEmittedQueueItemId = nil
        self.performingMutation { self.fullRebuildPlayerQueue() }
        self.rescheduleLookahead()
        // A position whose item cannot be built is absent from the installed
        // run, and no engine signal follows to re-enter on. Asking whether the
        // run holds *this* position rather than whether the engine holds
        // anything: the run reaches behind the playhead, so an unplayable
        // current position still leaves the trail installed.
        //
        // Re-decide rather than parking mid-queue.
        if engineIndex(forQueuePosition: self.currentTrackIndex) == nil {
          if verdict == .refillCurrent {
            verdict = .queueEnd
          } else {
            verdict = QueueWindowArithmetic.verdict(
              drained: true,
              atLogicalTail: QueueWindowArithmetic.isAtLogicalTail(
                anchorPosition: self.currentTrackIndex, trackCount: self.tracks.count),
              repeatMode: repeatModeAsSkipMode())
          }
          continue
        }
        // Both verdicts need the explicit restart the wrap does — after the
        // guard above, so a position that could not be built is never announced
        // as a track change. An engine that retains its run rebuilds by
        // extending it, which never re-seats the leg: it would still hold the
        // item that just ended, and the resume below would write a rate onto a
        // track already at its end. On an engine that consumes its items the
        // rebuild has reinstalled from the anchor already, and this seeks to a
        // position it is on.
        restartCurrentPosition()
        // Playback is asked to resume for the same reason as the wrap above,
        // and only if it was running: neither engine drops its rate when it
        // runs out of items, so rate cannot tell "was playing" from "was
        // paused" and the transport intent is what does. Not while the system
        // has playback interrupted — the refill must not start audio during a
        // call, nor after one the user declined to resume.
        if self.wantsToPlay, !self.isInterrupted { self.beginPlayback() }
        return
      case .queueEnd:
        // Park the engine. Draining is not the same as being stopped — neither
        // engine drops its rate when it runs out of items, so a leg left running
        // would start the next queue installed on it the moment an item lands.
        // The other verdicts deliberately resume instead, just below.
        self.engine?.pause()
        // The intent to play ends with the queue: nothing that seats a track
        // from here — a skip, a seek, a fresh queue, an engine swap — starts
        // it until play() is called again.
        self.wantsToPlay = false
        self.reachedQueueEnd = true
        queueEndListeners.forEach { $0() }
        emitState(.ended, reason: .queueEnd)
        return
      }
    }
  }
}
