import NitroModules

extension TrackPlayer {
  // MARK: - Skip
  //
  // AVQueuePlayer is forward-only, so skipToPrevious and skipToIndex
  // (when target < current) both require rebuilding the player's
  // queue from the authoritative `self.tracks` list at the new
  // index. skipToNext is a surgical `player.advanceToNextItem()`
  // when the next track is already queued; wraps round (via full
  // rebuild) when `repeatModeState == .queue` and we're past the
  // end.
  //
  // Shuffle: destructive action `shuffleQueue()`. Mutates
  // `self.tracks` to a Fisher-Yates permutation, resets
  // `currentTrackIndex` to 0, resumes playback if was playing, and
  // returns the new ordering to the consumer. Skip arithmetic walks
  // linearly across whatever ordering the consumer last installed;
  // there is no shadow shuffle queue + no shuffle-aware skip
  // overloads.

  /// `skipToIndex` is an explicit consumer intent — it always honours
  /// the target index in every repeat mode, including `.queue` (it
  /// never wraps — the target is absolute). Out-of-range indices
  /// silently no-op.
  func skipToIndex(index: Double, silent: Bool? = nil) throws -> Promise<Void> {
    return enqueue {
      // Engine-aware: under CrossfadeEngine `self.player` is nil
      // by design. Gating the skip on the engine surface lets
      // mid-play engine swaps + crossfade-mode user skips work.
      guard self.engine != nil else { return }
      // See `InputGuards.validQueueIndex` for the rationale —
      // validates non-finite + out-of-range in Double space so
      // the subsequent Int conversion is bounded.
      guard let target = InputGuards.validQueueIndex(
        index, count: self.tracks.count
      ) else { return }
      // During cast, jump the receiver to the target index; its status
      // echo drives the metadata + active-track update through the
      // bridge (same as skipToNext/Previous). The local engine stays
      // paused, so a local index write would desync from the receiver.
      self.silentTrackChange = silent == true && target != self.currentTrackIndex
        ? (target, .autoAdvance) : nil
      if CastTransportRouter.routeSkipToIndex(index: target) { return }
      self.applyNewCurrentIndex(
        newIndex: target, reason: .userSkipToIndex, silent: silent == true)
    }
  }

  func skipToNext(silent: Bool? = nil) throws -> Promise<Void> {
    return enqueue {
      self.silentTrackChange = silent == true ? self.computeNextIndex().flatMap {
        $0 != self.currentTrackIndex ? ($0, TrackChangeReason.autoAdvance) : nil
      } : nil
      if CastTransportRouter.routeSkipToNext() { return }
      guard self.engine != nil else { return }
      guard let next = self.computeNextIndex() else { return }
      self.applyNewCurrentIndex(
        newIndex: next, reason: .userSkipNext, silent: silent == true)
    }
  }

  /// Skip-previous, resolved by `QueueSkipArithmetic.resolveSkipToPrevious`
  /// from `PlayerConfig.skipToPreviousBehavior`. With the default `.previous`
  /// this goes to the previous track if there is one (`.off`/`.track` don't
  /// wrap, `.queue` wraps; `.track` navigates like `.off` and repeat-one
  /// persists). With `.restartOrPrevious` it restarts the current track (seek
  /// to 0) when past the threshold, otherwise goes to the previous track.
  func skipToPrevious() throws -> Promise<Void> {
    return enqueue {
      if CastTransportRouter.routeSkipToPrevious() { return }
      guard let engine = self.engine else { return }
      let positionMs = Int(max(0, engine.currentPositionSeconds) * 1000)
      let action = QueueSkipArithmetic.resolveSkipToPrevious(
        trackCount: self.tracks.count,
        currentIndex: self.currentTrackIndex,
        repeatMode: self.repeatModeAsSkipMode(),
        restartEnabled: self.skipRestartEnabled(),
        positionMs: positionMs)
      switch action {
      case .previous(let idx):
        self.applyNewCurrentIndex(newIndex: idx, reason: .userSkipPrevious)
      case .restartCurrent:
        // Restart the current track from 0 — a seek, not a track change.
        // Same-index `applyNewCurrentIndex` takes its restart branch
        // (currentItem unchanged → no `onTrackChange`), engine-aware across
        // gapless (live / drained-rebuild) + crossfade.
        self.applyNewCurrentIndex(
          newIndex: self.currentTrackIndex, reason: .userSkipPrevious)
      case .noOp:
        return
      }
    }
  }

  /// Next-track index. Delegates to `QueueSkipArithmetic.computeNext`.
  /// cur<0 sentinel: "nothing playing yet" → skipToNext is a no-op
  /// (consumer starts via `setQueue` + `play` or `skipToIndex(0)`).
  internal func computeNextIndex() -> Int? {
    return QueueSkipArithmetic.computeNext(
      trackCount: self.tracks.count,
      currentIndex: self.currentTrackIndex,
      repeatMode: repeatModeAsSkipMode())
  }

  /// Previous-track index. Delegates to `QueueSkipArithmetic.computePrevious`.
  /// Same cur<0 early-bail as `computeNextIndex`.
  internal func computePreviousIndex() -> Int? {
    return QueueSkipArithmetic.computePrevious(
      trackCount: self.tracks.count,
      currentIndex: self.currentTrackIndex,
      repeatMode: repeatModeAsSkipMode())
  }

  /// Whether skip-to-previous restarts the current track past the threshold
  /// (`PlayerConfig.skipToPreviousBehavior == .restartOrPrevious`). Read live
  /// from the current config at each skip + capability recompute.
  private func skipRestartEnabled() -> Bool {
    return self.config.skipToPreviousBehavior == .restartOrPrevious
  }

  /// Map the Nitrogen-generated `RepeatMode` enum onto the pure-Swift
  /// `QueueSkipRepeatMode` so `QueueSkipArithmetic` can stay off the
  /// Swift ↔ C++ interop path.
  internal func repeatModeAsSkipMode() -> QueueSkipRepeatMode {
    switch self.repeatModeState {
    case .off: return .off
    case .track: return .track
    case .queue: return .queue
    }
  }

  /// Recompute `cachedSkipCapability` from
  /// authoritative state (`tracks`, `currentTrackIndex`,
  /// `repeatModeState`) and fire `onSkipCapabilityChange` if either
  /// flipped.
  ///
  /// **Threading invariant:** must be called on `playerQueue`. Reads
  /// `tracks` / `currentTrackIndex` / `repeatModeState`, all of
  /// which mutate only on that queue, so the read is race-free; the
  /// listener fire iterates a snapshot under `ListenerRegistry`'s
  /// internal lock so subscriber add/remove during the fire round
  /// is safe.
  ///
  /// **Dedup:** the listener fires only when the (prev, next) tuple
  /// actually changes. Two back-to-back recompute calls that land on
  /// the same tuple emit once, not twice. This is what makes it safe
  /// to scatter `recomputeCapabilities()` calls across every
  /// mutation site without producing chatty events.
  ///
  /// **Callers** (every site that writes `tracks` /
  /// `currentTrackIndex` / `repeatModeState`):
  /// - `setQueue`, `addToQueueBody`, `removeFromQueueBody`,
  ///   `moveInQueueBody`, `clearQueue`
  /// - `applyNewCurrentIndex` (covers skipToNext / skipToPrevious /
  ///   skipToIndex)
  /// - `handleCurrentItemDidChange` (covers AVQueuePlayer auto-advance
  ///   that updates `currentTrackIndex` via `matchTrackIndex`)
  /// - `setRepeatMode`, `shuffleQueue`
  /// - `destroy` (resets to (false, false) so a final emit lets
  ///   subscribers know the player is gone)
  internal func recomputeCapabilities() {
    // Building the mirror maps the whole track list, so it is only built when
    // a car is attached to receive it. The flag is read once and travels with
    // the snapshot: a `nil` built because no car was attached must never be
    // mistaken downstream for a `nil` that means "clear the mirror".
    let carAttached = CarPlayCoordinator.shared.isCarConnected
    let queueSnapshot = carAttached ? buildCarPlayQueueSnapshot() : nil
    publishCapabilities(
      computeCapabilities(), queueSnapshot: queueSnapshot, carAttached: carAttached)
  }

  /// Skip capability derived from `tracks` / `currentTrackIndex` /
  /// `repeatModeState`. Pure — reads state and returns a value, no
  /// listener fire and no remote-command or CarPlay write.
  internal func computeCapabilities() -> SkipCapability {
    let mode = repeatModeAsSkipMode()
    let count = tracks.count
    let cur = currentTrackIndex
    return SkipCapability(
      canSkipPrevious: QueueSkipArithmetic.canSkipPrevious(
        trackCount: count, currentIndex: cur, repeatMode: mode,
        restartEnabled: skipRestartEnabled()),
      canSkipNext: QueueSkipArithmetic.canSkipNext(
        trackCount: count, currentIndex: cur, repeatMode: mode))
  }

  /// Publish `capability` and `queueSnapshot`: refresh the CarPlay Up Next
  /// mirror, then cache the pair, fire `onSkipCapabilityChange` and update the
  /// remote-command centre when it actually changed. Runs on `playerQueue`;
  /// `RemoteCommands` hands its own work to main, which owns
  /// `MPRemoteCommandCenter`.
  internal func publishCapabilities(
    _ capability: SkipCapability, queueSnapshot: CarPlayQueueSnapshot?,
    carAttached: Bool
  ) {
    // Every queue / current-index / repeat-mode change funnels through here,
    // so it's the single point that keeps the CarPlay Up Next mirror current
    // — done before the skip-capability dedup guard below so reorder/metadata
    // changes (which don't flip skip capability) still refresh the mirror.
    syncCarPlayQueueMirror(queueSnapshot, carAttached: carAttached)
    let current = cachedSkipCapability
    guard capability.canSkipPrevious != current.canSkipPrevious
      || capability.canSkipNext != current.canSkipNext else {
      return
    }
    cachedSkipCapability = capability
    skipCapabilityListeners.forEach { $0(capability) }
    self.remoteCommands.setSkipCapability(
      canSkipNext: capability.canSkipNext,
      canSkipPrevious: capability.canSkipPrevious
    )
  }

  /// Mirror `snapshot` into the process-wide [CarPlayQueueProvider] so the
  /// CarPlay Up Next surface renders the current playback queue without
  /// round-tripping through JS; `nil` clears the mirror so the Up Next button
  /// hides. `carAttached` is the caller's single read of the connectivity
  /// flag, so the decision to build a snapshot and the decision to write one
  /// cannot disagree. Driven from `publishCapabilities`, which every queue /
  /// current-index / repeat-mode change funnels through, and from
  /// `CarPlayCoordinator.onCarAttached`.
  internal func syncCarPlayQueueMirror(
    _ snapshot: CarPlayQueueSnapshot?, carAttached: Bool
  ) {
    // With no car attached there is nothing to receive the update, and the
    // browse surface rebuilds from live state when one connects.
    guard carAttached else { return }
    // `CarPlayQueueProvider.setSnapshot` guards its store with a lock and
    // posts its update notification on the main queue itself.
    CarPlayCoordinator.shared.queueProvider.setSnapshot(snapshot)
  }

  /// Queue mirror for the CarPlay Up Next list, or `nil` for an empty queue
  /// (which clears the mirror and hides the button). Pure — reads `tracks`
  /// and `currentTrackIndex` and returns a value.
  internal func buildCarPlayQueueSnapshot() -> CarPlayQueueSnapshot? {
    guard !self.tracks.isEmpty else { return nil }
    let entries = self.tracks.map { track in
      CarPlayQueueEntry(
        title: track.title ?? "",
        subtitle: track.artist ?? track.album,
        artworkUrl: track.artworkUrl
      )
    }
    return CarPlayQueueSnapshot(
      entries: entries, currentIndex: self.currentTrackIndex)
  }

  /// Apply a new `currentTrackIndex` and sync the AVQueuePlayer.
  ///
  /// **Forward skip** (newIndex > oldIndex) — surgical trim of items
  /// between current and target + `player.advanceToNextItem()`. Safe
  /// because `preferredForwardBufferDuration = 0.0` closes the
  /// chain-advance-while-paused vector and the `didPlayToEndPending`
  /// resync gate prevents any chain-advance from corrupting
  /// `currentTrackIndex` even if it did fire.
  ///
  /// **Backward skip** (newIndex < oldIndex, including .queue-mode
  /// wraparound) — full rebuild via `fullRebuildPlayerQueue`.
  /// AVQueuePlayer is forward-only; backward navigation always
  /// requires removing all items + re-inserting from the new index.
  ///
  /// - `newIndex == oldIndex` → restarts the current track from position 0
  ///   with no track-change event — seeking in place, or reinstalling the
  ///   track when it is not materialised in the engine.
  /// - out-of-range `newIndex` → silent no-op.
  ///
  /// **Void return is intentional.** Silent-return cases here
  /// (out-of-range, identity skip) are legitimate; track-change /
  /// queue-end events flow through `handleCurrentItemDidChange`
  /// driven by the AVQueuePlayer `\.currentItem` KVO, which is the
  /// source of truth for whether a track actually became current.
  /// A `Bool` return would be a *prediction* of whether KVO will
  /// fire — predictions drift, KVO doesn't.
  internal func applyNewCurrentIndex(
    newIndex: Int, reason: TrackChangeReason, silent: Bool = false
  ) {
    guard newIndex >= 0, newIndex < self.tracks.count else { return }
    self.silentTrackChange = silent && newIndex != self.currentTrackIndex
      ? (newIndex, reason) : nil
    if self.tracks[newIndex].isVideo == true {
      self.currentTrackIndex = newIndex
      self.pendingTrackChangeReason = reason
      self.reachedQueueEnd = false
      self.performingMutation { self.fullRebuildPlayerQueue() }
      self.recomputeCapabilities()
      self.rescheduleLookahead()
      return
    }
    // Revived before the skip; any reinstall it leaves owed runs after, on
    // every exit, once the skip has moved the index and cleared
    // `reachedQueueEnd`. A skip that installed a fresh run has already settled it.
    self.reviveReadThroughServerIfDead()
    defer { self.reinstallAfterReadThroughRestart() }

    let oldIndex = self.currentTrackIndex
    // Seating a position is an attempt at it, so whatever was reported against
    // it no longer describes what is about to play. This is the clear point for
    // every skip, including the ones that seat without rebuilding — a crossfade
    // skip onto a position the engine still holds, and a forward skip that
    // trims the installed run — which would otherwise leave the report standing
    // and the track silently wedged: no report, no run extension, no end
    // verdict, until play().
    if let seated = self.queueItemIds[safe: newIndex] {
      self.reportedFailureQueueItemIds.remove(seated)
      self.routedRecoveryAttemptedQueueItemIds.remove(seated)
    }
    if newIndex == oldIndex {
      // A same-index skip is an explicit "restart this track" — no longer at
      // the end of the queue. Clear `reachedQueueEnd` here because this branch returns
      // before `dispatchTrackChange` (which is the other clear point), so a
      // crossfade in-place replay after end-of-queue would otherwise stay
      // stuck on `.ended`.
      self.reachedQueueEnd = false
      // Before the seek below rather than in the `defer`: the reinstall carries
      // the engine's playhead, which an asynchronous seek to zero may not have
      // moved yet.
      self.reinstallAfterReadThroughRestart()
      // Same-index user skip = "restart this track from 0". Don't
      // rebuild the queue or fire onTrackChange (the active track
      // hasn't changed), but do reset the engine's playhead so the
      // call-site contract is "track N from position 0" regardless
      // of where the engine currently is — including after a
      // setPlaybackMode swap that carried position across.
      //
      // Engine-aware: an engine indexes within the slice it was handed,
      // never in the lib's queue-position space, so a queue position has
      // to be translated by item identity before it can be seeked. For
      // gapless the reset goes through the AVQueuePlayer's currentItem
      // directly instead.
      //
      // A drained gapless AVQueuePlayer (`currentItem == nil`, so `items()` is
      // empty) can't be seeked — `actionAtItemEnd = .advance` consumes items as
      // they finish, so at end-of-queue there's nothing to seek. Rebuild at the
      // current index to reload the track so the restart intent isn't dropped.
      // Crossfade (`self.player == nil`) keeps its item on the leading leg, so
      // the engine seek resets it in place.
      if let player = self.player, player.currentItem != nil {
        player.currentItem?.seek(to: .zero, completionHandler: nil)
      } else if self.player != nil {
        self.performingMutation { self.fullRebuildPlayerQueue() }
      } else if let engineIdx = self.engineIndex(forQueuePosition: newIndex) {
        self.engine?.seek(toIndex: engineIdx, position: 0)
      } else {
        // Target isn't materialised in the engine — reinstall at it.
        self.performingMutation { self.fullRebuildPlayerQueue() }
      }
      return
    }

    // Stash reason only now that we know a real transition is
    // happening. Consumed by `handleCurrentItemDidChange` on the
    // next non-nil `\.currentItem` KVO fire. Mid-rebuild nil fires
    // are suppressed by `isMutatingPlayerQueue` so the reason
    // survives until the terminal currentItem.
    self.pendingTrackChangeReason = reason
    // Moving to another position is an intent to play it, so the queue is
    // not over — cleared here rather than only on the track change that
    // follows, so a target that cannot be built still reaches a verdict.
    self.reachedQueueEnd = false

    self.performingMutation {
      if oldIndex < 0 {
        // Queue was empty / uninitialised — populate player fresh.
        self.currentTrackIndex = newIndex
        fullRebuildPlayerQueue()
        return
      }

      self.currentTrackIndex = newIndex

      if self.player == nil {
        // CrossfadeEngine seeks by index within its own queue, so translate
        // the queue position by item identity first. Not found means the
        // target isn't materialised — reinstall the queue at it rather than
        // issuing a seek the engine would reject. The gapless forward-trim
        // (`forwardSkipBy`) is not usable here: it assumes `allMediaItems`
        // is the remaining forward-only slice, which is false for crossfade.
        if let engineIdx = self.engineIndex(forQueuePosition: newIndex) {
          self.engine?.seek(toIndex: engineIdx, position: 0)
          // The seek's own track-change fires while this mutation is in
          // flight, and the delegate skips the top-up for the duration; a
          // skip onto the last installed position would otherwise leave no
          // run ahead of it until the next transition, which a hard cut is.
          self.topUpWindow()
        } else {
          // The target is not in the engine's run — a track appended after the
          // queue ended, say. The rebuild installs it, but its surgical path
          // extends the run behind the current item without re-seating the
          // engine, so `currentTrackIndex` would advance while the engine kept
          // playing the old item and no `onTrackChange` fired. Seek to the
          // target once the run holds it so the skip actually moves the engine
          // and the transition is announced.
          fullRebuildPlayerQueue()
          if let engineIdx = self.engineIndex(forQueuePosition: newIndex) {
            self.engine?.seek(toIndex: engineIdx, position: 0)
            self.topUpWindow()
          }
        }
      } else if newIndex > oldIndex {
        forwardSkipBy(delta: newIndex - oldIndex)
      } else {
        // Backward skip / `.queue`-mode wraparound — full rebuild.
        // AVQueuePlayer is forward-only; backward navigation has no
        // surgical alternative.
        fullRebuildPlayerQueue()
      }
    }
    // Every skip shifts the lookahead window. Reschedule covers all
    // three skip methods (skipToIndex / Next / Previous all funnel
    // through here).
    self.rescheduleLookahead()
    self.recomputeCapabilities()
  }

  /// Forward skip via surgical-trim + advanceToNextItem on the
  /// player's existing items array. Caller holds the surrounding
  /// `performingMutation` scope; this helper does NOT re-enter it.
  /// Falls back to `fullRebuildPlayerQueue` when the player snapshot
  /// is shorter than `delta` (defensive against partial chain-
  /// advance / error-recovery state).
  ///
  /// Workaround: `advanceToNextItem` while paused can chain-advance
  /// through unbuffered items because AVQueuePlayer interprets
  /// unresolved durations as "played to end". Mitigated by
  /// `preferredForwardBufferDuration = 0.0` at item construction +
  /// the `didPlayToEndPending` resync gate.
  /// TODO: monitor upstream for fixes — https://developer.apple.com/forums/thread/683744
  private func forwardSkipBy(delta: Int) {
    guard let engine = self.engine else { return }
    let snapshot = engine.allMediaItems
    guard snapshot.count > delta else {
      fullRebuildPlayerQueue()
      return
    }
    // Rearm the initial-buffer leniency window for the post-advance
    // currentItem. `fullRebuildPlayerQueue` does this before
    // inserting; surgical-trim doesn't touch items-array order, so
    // the call is explicit here.
    rearmGaplessFlip()
    let trimRange = 1 ..< delta
    let toRemove = Array(snapshot[trimRange])
    for item in toRemove {
      engine.remove(item)
    }
    engine.skipToNext()
    // The trim consumed the run ahead of the playhead. A skip while paused
    // never ticks the progress observer, so nothing else would refill it.
    self.topUpWindow()
  }

  /// Reschedule the proactive prefetcher after a queue or skip
  /// mutation. Reads `tracks` + `currentTrackIndex` and dispatches
  /// the next N remote tracks to [LookaheadCachePrefetcher]. No-op
  /// when the prefetcher hasn't been built (configure not yet
  /// called).
  internal func rescheduleLookahead() {
    self.refreshCacheProtection()
    guard let prefetcher = self.lookaheadCachePrefetcher else { return }
    prefetcher.reschedule(
      tracks: self.tracks,
      currentIndex: self.currentTrackIndex
    )
  }

  /// Tell the cache which files eviction must not take: the playing track,
  /// the tracks being prefetched ahead of it, and one behind for an
  /// immediate skip-back.
  ///
  /// `commitDownloadedFile` evicts after every prefetch commit, so without
  /// this the entry backing the playing item can be deleted while it plays.
  /// Bounded deliberately — protecting the whole remaining queue would leave
  /// nothing evictable and turn each commit into a cache wipe.
  private func refreshCacheProtection() {
    guard let cache = self.lookaheadCache else { return }
    let cur = self.currentTrackIndex
    guard cur >= 0, cur < self.tracks.count else {
      cache.setProtectedUrls([])
      return
    }
    // The count crosses from JS as a Double, so Infinity and NaN are reachable
    // and `Int(_:)` traps on both. Clamp before converting.
    let ahead = min(
      InputGuards.validLookaheadCount(self.lookaheadConfig.lookaheadCount),
      self.tracks.count)
    let lower = max(0, cur - 1)
    let upper = min(self.tracks.count - 1, cur + ahead)
    cache.setProtectedUrls(Set(self.tracks[lower ... upper].map { $0.url }))
  }
}
