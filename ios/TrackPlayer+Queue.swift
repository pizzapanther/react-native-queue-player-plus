import AVFoundation
import NitroModules

extension TrackPlayer {
  // MARK: - Queue mutation
  //
  // All mutations operate on two parallel structures:
  //   1. `self.tracks` — the authoritative ordered TrackItem list.
  //   2. The engine's installed run — the `QueueWindowArithmetic.slice`
  //      around `currentTrackIndex`, reconciled by `fullRebuildPlayerQueue`
  //      and the surgical insert/remove calls without tearing down the
  //      currently-playing item when possible.
  //
  // Thread safety: every mutation body is enqueued onto `playerQueue` by
  // `enqueueThenMirror`.
  // AVFoundation carries no main-thread requirement at the 16.0 floor, but it
  // is not thread-safe either, so one queue owns every player mutation. The
  // hop also serialises concurrent JS calls — two back-to-back `addToQueue` +
  // `removeFromQueue` invocations cannot interleave, because the queue is
  // serial.

  /// Belt-and-braces: verify the parallel-array invariant before
  /// any function that consumes both arrays. `QueueState` enforces
  /// this at every mutation site, but a direct read of either array
  /// outside `queueState`'s API can drift if a future contributor
  /// bypasses the type. Catches the desync earlier than the
  /// construction-time `precondition` in `AVQueueBuilder`.
  /// Debug-only — the precondition vanishes in Release builds where
  /// `AVQueueBuilder` already fails soft (returns `[]` + NSLog) on
  /// the same desync.
  private func assertQueueIdsInvariant(_ caller: StaticString) {
    #if DEBUG
    precondition(
      self.tracks.count == self.queueItemIds.count,
      "QueueState parallel-array invariant violated at \(caller): tracks=\(self.tracks.count), queueItemIds=\(self.queueItemIds.count)"
    )
    #endif
  }

  /// Raise `isMutatingPlayerQueue` for the duration of `body`,
  /// restoring the prior value on exit (so nested calls stay true
  /// throughout the outer mutation). Used to suppress spurious
  /// `onQueueEnd` / `onTrackChange` fires from the `\.currentItem`
  /// KVO during the `removeAllItems` → `insert` intermediate nil
  /// window. Must be called on `playerQueue`.
  internal func performingMutation(_ body: () -> Void) {
    let wasAlready = self.isMutatingPlayerQueue
    self.isMutatingPlayerQueue = true
    defer { self.isMutatingPlayerQueue = wasAlready }
    body()
  }

  func setQueue(tracks: [TrackItem], startAtIndex: Double?) throws -> Promise<Void> {
    // The local queue model is replaced **at call time**, before any cast
    // round-trip, so the mutation reaches the player in the order JS issued it
    // — a `skipToNext()` called straight after this one cannot overtake it.
    // The receiver is mirrored afterwards from the Task below.
    //
    // Local state is maintained whether or not the receiver takes the queue:
    // JS reads (`getQueue` / `getCurrentTrackIndex`) reflect the user-visible
    // queue while the receiver is authoritative for playback, and the local
    // engine is left holding the right items for when cast disconnects. The
    // engine is already paused during cast and the rebuild below keeps it
    // paused, so this is silent either way.
    return enqueueThenMirror({
      // The new queue is built below, on whatever listener is live.
      self.reviveReadThroughServerIfDead()
      self.performingMutation {
        // `self.engine` is the canonical "lib initialised" sentinel.
        // `self.player` is the gapless engine's AVQueuePlayer and is
        // nil under CrossfadeEngine — gating on it here would silently
        // no-op every queue mutation made while in crossfade mode.
        guard self.engine != nil else { return }
        let newIds = self.queueState.replaceAll(tracks)
        self.currentTrackIndex = Self.resolvedStartIndex(
          startAtIndex: startAtIndex, trackCount: tracks.count)
        self.pendingTrackChangeReason = .queueReplaced
        // A fresh queue is not over, whatever the one it replaces was.
        self.reachedQueueEnd = false
        // Fresh queue → fresh dedup state. Without this an error on
        // the prior queue's last item could suppress an identical
        // coded error on the new queue's first item.
        self.lastErrorQueueItemId = nil
        self.lastErrorCode = nil
        self.reportedFailureQueueItemIds.removeAll()
        // Reset auto-retry counters: new queueItemIds → new budget
        // entries. The retry budget is per-queue-position; old
        // entries from the prior queue are gone with the prior
        // queueItemIds, and a missing entry reads as zero retries.
        let attempts = self.effectiveAutoRetries()
        self.retryAttemptsRemaining = Dictionary(
          uniqueKeysWithValues: newIds.map { ($0, attempts) }
        )
        self.routedRecoveryAttemptedQueueItemIds.removeAll()
        // A fresh queue diverges at position 0, so the rebuild installs the
        // whole slice and the resolved start track gets initial-buffer
        // leniency from the rearm on that path.
        self.syncPlayerQueue(preserveCurrent: false)
      }
      self.rescheduleLookahead()
      self.recomputeCapabilities()
      self.emitQueueChange(
        .setQueue,
        inserted: self.tracks,
        insertedAt: self.tracks.isEmpty ? -1 : 0
      )
    }, mirror: { _, generation in
      // Push the queue to the receiver. Replacing the whole queue starts the
      // new queue there (distinct from `handoffCurrentPlaybackToCast`, which
      // preserves the in-flight play/pause state). A receiver-side failure
      // needs no fallback — the local path above already ran.
      let resolvedStart = Self.resolvedStartIndex(
        startAtIndex: startAtIndex, trackCount: tracks.count)
      let castItems = tracks.compactMap { CastMediaItem.from(track: $0) }
      // Skip the cast route if EVERY track had an unparseable url
      // (`from(track:)` returned nil). Empty queues mean nothing to play.
      guard !tracks.isEmpty, castItems.count == tracks.count else { return }
      // nil covers both "no receiver is active" and "the receiver-side load
      // failed"; neither needs anything beyond the local queue already set.
      let routed = (try? await CastTransportRouter.routeSetQueue(
        tracks: castItems,
        startIndex: max(0, resolvedStart),
        startPositionMs: 0,
        playWhenReady: true
      )) != nil
      guard routed else { return }
      // Seed the start track's lockscreen metadata immediately so the surface
      // doesn't wait for the first receiver status. The current track is
      // resolved from the receiver's absolute queue index, not the itemIds, so
      // no itemId map is needed here.
      //
      // The seed reads the live queue, so the check belongs inside this hop:
      // a `setQueue` that landed while the load was in flight has already
      // replaced `tracks`, and `resolvedStart` names a position in the queue
      // that lost. That newer mutation seeds its own start track.
      await self.onPlayerQueue {
        guard self.queueMutationGeneration == generation else { return }
        self.castEventBridge.seedStartMetadata(startIndex: resolvedStart)
      }
    })
  }

  /// Clamp the consumer-supplied start index into the queue bounds. An empty
  /// queue ignores the parameter; nil, non-finite, negative and out-of-range
  /// all coerce to 0.
  ///
  /// The clamp happens in `Double` space because `Int(.nan)` and
  /// `Int(.infinity)` are undefined behaviour — see `InputGuards`.
  private static func resolvedStartIndex(
    startAtIndex: Double?, trackCount: Int
  ) -> Int {
    guard trackCount > 0 else { return -1 }
    guard let requested = startAtIndex, requested.isFinite else { return 0 }
    return Int(max(0, min(requested, Double(trackCount - 1))))
  }

  /// Adding to an empty queue (cur == -1) leaves cur at -1 and leaves
  /// the player empty — consumer must call `skipToIndex(0)` to start
  /// playback. This avoids implicit "start playing" surprises on
  /// `addToQueue`. Non-empty queue with
  /// insertion strictly after cur preserves the currently-playing
  /// item surgically. Insertion before/at cur shifts cur forward;
  /// since AVQueuePlayer holds only cur-onward items, the player
  /// queue is unchanged by before-cur insertions (no playback gap).
  func addToQueue(tracks newTracks: [TrackItem], insertBefore: Double?) throws -> Promise<Void> {
    // The local mutation is enqueued **at call time** so it reaches the player
    // in the order JS issued it, and the receiver is mirrored afterwards.
    //
    // An insert into the immediate-next slot of a playing queue is the one
    // `AVQueuePlayer` prerolls on the shared render pipeline mid-playback,
    // which briefly disturbs the current item. The insert is not held until
    // the asset has loaded: a hold lets a transport call issued straight
    // afterwards reach the player first and act on the pre-insert queue.
    // Ordering wins — and in the sequence where it matters, "play next" then
    // skip, the current item is being left anyway.
    return enqueueThenMirror({ () -> Int in
      let at = QueueMutationArithmetic.clampInsertBefore(
        insertBefore: InputGuards.insertPosition(insertBefore, count: self.tracks.count),
        trackCount: self.tracks.count
      )
      self.performingMutation {
        self.addToQueueBody(newTracks: newTracks, insertBefore: insertBefore)
      }
      self.rescheduleLookahead()
      self.recomputeCapabilities()
      return at
    }, mirror: { insertAt, _ in
      // Mirror only when every new track is castable. A dropped item would
      // shift the receiver queue out of alignment with the local index space
      // that later mutations (remove/move) resolve against, so a partial set
      // is not routed at all — the same all-or-nothing rule `setQueue` applies.
      let castItems = newTracks.compactMap { CastMediaItem.from(track: $0) }
      if !castItems.isEmpty, castItems.count == newTracks.count {
        _ = await CastTransportRouter.routeAddToQueue(
          items: castItems, beforeIndex: insertAt)
      }
    })
  }

  private func addToQueueBody(
    newTracks: [TrackItem],
    insertBefore: Double?
  ) {
    guard !newTracks.isEmpty, let engine = self.engine else { return }

    // Non-finite `insertBefore` is dropped (treated as append) so
    // `Int($0)` never traps on NaN / ±Infinity. Mirrors Android's
    // `takeIf { it.isFinite() }?.toInt()` sanitisation at the
    // mutation boundary.
    let at = QueueMutationArithmetic.clampInsertBefore(
      insertBefore: InputGuards.insertPosition(insertBefore, count: self.tracks.count),
      trackCount: self.tracks.count
    )

    // An insert lands in front of `at`, so that position and the one before it
    // are what the engine would have to be told about.
    let deferred = deferEngineWork(touching: [at - 1, at])
    let newIds = self.queueState.insert(newTracks, at: at)
    // Initialise retry-budget entries for the new items so they have
    // an auto-retry budget when (if) they hit a transient error.
    let attempts = self.effectiveAutoRetries()
    for id in newIds {
      self.retryAttemptsRemaining[id] = attempts
    }

    let oldCur = self.currentTrackIndex
    self.currentTrackIndex = QueueMutationArithmetic.currentIndexAfterAdd(
      currentIndex: oldCur, insertAt: at, count: newTracks.count
    )
    // Queue data model is final here (tracks + index committed); the
    // engine insert below only touches the audio pipeline. Emit before
    // the early returns so every non-empty add reports one snapshot.
    self.emitQueueChange(.add, inserted: newTracks, insertedAt: at)

    if oldCur < 0 {
      // Queue was empty — player stays empty until skipToIndex.
      return
    }

    if at <= oldCur {
      // Insertion at/before cur: player queue (cur-onward) is
      // unchanged; only cur shifted forward (already applied via
      // `currentIndexAfterAdd` above).
      return
    }

    // A queue that has ended stays ended until a skip or a seek: the engine
    // is left alone, whether it drained (gapless) or still holds the finished
    // item (crossfade), and the model carries the insert until then. Behind
    // a leg parked at its end, an inserted item would be what the next
    // `play()` fades into.
    if self.reachedQueueEnd { return }

    // Insertion strictly after cur: surgical insert preserves the
    // currently-playing item and any preloaded look-ahead state.
    // Anchor on the track immediately before the insertion point,
    // matched by lib-generated identity so it resolves in either
    // engine's live queue (cur-onward for GaplessEngine, installed slice
    // for CrossfadeEngine).
    let existing = engine.allMediaItems
    let anchorId: String? = (at - 1 >= 0 && at - 1 < self.queueItemIds.count)
      ? self.queueItemIds[at - 1] : nil
    let anchorItem = existing.first { $0.queueItemId != nil && $0.queueItemId == anchorId }

    guard let anchorItem else {
      // The track before the insertion point is not materialised, so there
      // is no position to insert after: falling back to the last enqueued
      // item would play the new tracks ahead of every position between it
      // and them, and an `insert(after: nil)` into a drained engine seats
      // playback on the new track while `currentTrackIndex` stays on the
      // one that finished. Leave the engine alone — `self.tracks` already
      // carries the insert, and the queue is rebuilt from it on the way
      // past.
      return
    }

    guard !deferred else { return }
    // Only what the window holds is built here. An add that runs past the
    // window — a whole album dropped in behind the playing track — goes
    // through the rebuild instead, which trims the installed tail and
    // appends the slice; `topUpWindow` materialises the rest as the playhead
    // advances. Inserting every added item surgically would build each one
    // now, and leave the engine holding an unbroken run only if all of them
    // were inserted.
    let window = QueueWindowArithmetic.slice(
      currentIndex: self.currentTrackIndex,
      trackCount: self.tracks.count,
      trail: self.engineTrail,
      wraps: false
    )
    guard window.contains(at + newTracks.count - 1) else {
      self.fullRebuildPlayerQueue()
      return
    }
    let newItems = AVQueueBuilder.buildPlayerItems(
      tracks: newTracks, queueItemIds: newIds,
      config: self.config, server: self.routingServer)
    let ok = engine.insertItems(newItems, after: anchorItem)
    if !ok {
      // Surgical insert failed — recover by rebuilding the tail
      // so `self.tracks` and the engine queue cannot diverge.
      self.syncPlayerQueue(preserveCurrent: true)
    }
  }

  func removeFromQueue(indices: [Double]) throws -> Promise<Void> {
    // Capture the sanitised indices against the pre-mutation queue and mutate
    // local **at call time**, so the mutation reaches the player in the order
    // JS issued it; then mirror the removal to the receiver (resolved to
    // stable itemIds inside the cast session before removing). Inside
    // `Promise.async` the mutation would be an unstructured Task's first
    // statement, which a later call's Task can beat to the queue.
    return enqueueThenMirror({
      // Exclude the current index — the currently-playing track is
      // pinned on the receiver too, matching the local body below.
      let cur = self.currentTrackIndex
      let sanitised = QueueMutationArithmetic.sanitiseRemoveIndices(
        rawIndices: indices.compactMap {
          InputGuards.validQueueIndex($0, count: self.tracks.count)
        },
        trackCount: self.tracks.count
      ).filter { $0 != cur }
      self.performingMutation {
        self.removeFromQueueBody(indices: indices)
      }
      self.rescheduleLookahead()
      self.recomputeCapabilities()
      return sanitised
    }, mirror: { castIndices, _ in
      if !castIndices.isEmpty {
        _ = await CastTransportRouter.routeRemoveFromQueue(indices: castIndices)
      }
    })
  }

  private func removeFromQueueBody(indices: [Double]) {
    guard !indices.isEmpty, let engine = self.engine else { return }
    // `removeIds` below force-subscripts `queueItemIds`; this asserts the
    // parallel-array invariant the sanitiser's in-range output relies on.
    assertQueueIdsInvariant("removeFromQueueBody")

    // Bounds-check in `Double` space: `Int(_:)` from a floating-point source
    // traps on non-finite and out-of-Int-range values, and these arrive
    // straight from JS. See `InputGuards`.
    var sanitised = QueueMutationArithmetic.sanitiseRemoveIndices(
      rawIndices: indices.compactMap {
        InputGuards.validQueueIndex($0, count: self.tracks.count)
      },
      trackCount: self.tracks.count
    )
    // The currently-playing track is pinned: it cannot be removed via
    // removeFromQueue (a consumer that wants it gone skips off it first,
    // then removes it). Drop the current index from the set — a request
    // to remove only the current track is a no-op; a mixed request drops
    // the other tracks and keeps the current one.
    sanitised.removeAll { $0 == self.currentTrackIndex }
    guard !sanitised.isEmpty else { return }

    let trackCountBefore = self.tracks.count
    let oldCur = self.currentTrackIndex

    // Match the AVPlayerItems to drop by their lib-generated identity
    // rather than by index offset, so the removal is engine-agnostic:
    // GaplessEngine exposes only the cur-onward slice (consumed items
    // are gone), while CrossfadeEngine exposes its installed slice. Captured
    // before the `queueState.remove` loop below mutates the id array.
    // Decided here, while the positions still name the tracks being removed.
    let deferred = deferEngineWork(touching: sanitised)
    let removeIds = Set(sanitised.map { self.queueItemIds[$0] })
    let itemsToRemove = deferred ? [] : engine.allMediaItems.filter { item in
      guard let id = item.queueItemId else { return false }
      return removeIds.contains(id)
    }

    // Iterate in descending order so earlier indices stay valid as
    // each removal shifts the remainder. Drop the matching
    // retry-budget entry so the map doesn't grow stale.
    for i in sanitised.reversed() {
      let removedId = self.queueState.remove(at: i)
      self.retryAttemptsRemaining.removeValue(forKey: removedId)
    }

    // The current track is never in the removed set, so it keeps playing
    // and its identity is unchanged; only its index shifts down by the
    // number of removed earlier tracks.
    self.currentTrackIndex = QueueMutationArithmetic.currentIndexAfterRemove(
      currentIndex: oldCur,
      sanitisedIndices: sanitised,
      trackCountBefore: trackCountBefore
    )
    // Reached only past the `sanitised.isEmpty` guard, so ≥1 track was
    // removed. tracks + index are final; the engine.remove loop below
    // only detaches the AVPlayerItems.
    self.emitQueueChange(.remove, removed: sanitised)

    // The currentItem is untouched (never in `itemsToRemove`); removing
    // only later/earlier items leaves it playing in place.
    for item in itemsToRemove {
      engine.remove(item)
    }
    guard !deferred else { return }
    // Removing the queued-ahead tracks takes them straight out of the engine,
    // so the run can be left at the playing item with the queue far from over.
    self.topUpWindow()
  }

  func moveInQueue(fromIndex: Double, toIndex: Double) throws -> Promise<Void> {
    // Capture the validated from/to against the pre-mutation queue and mutate
    // local **at call time**, so the mutation reaches the player in JS call
    // order; then mirror the reorder to the receiver.
    return enqueueThenMirror({ () -> (from: Int, to: Int)? in
      let count = self.tracks.count
      // Mirrored to the receiver only when the move is one the local body
      // will also perform; the body applies the same rules itself.
      var resolved: (from: Int, to: Int)?
      if let from = InputGuards.validQueueIndex(fromIndex, count: count),
         let to = InputGuards.validQueueIndex(toIndex, count: count),
         from != to, from != self.currentTrackIndex {
        resolved = (from, to)
      }
      self.performingMutation {
        self.moveInQueueBody(fromIndex: fromIndex, toIndex: toIndex)
      }
      self.rescheduleLookahead()
      self.recomputeCapabilities()
      return resolved
    }, mirror: { move, _ in
      if let move = move {
        _ = await CastTransportRouter.routeMoveInQueue(fromIndex: move.from, toIndex: move.to)
      }
    })
  }

  private func moveInQueueBody(fromIndex: Double, toIndex: Double) {
    guard self.engine != nil else { return }
    // Bounds-check in `Double` space: `Int(_:)` from a floating-point source
    // traps on non-finite and out-of-Int-range values, and these arrive
    // straight from JS. See `InputGuards`.
    let count = self.tracks.count
    guard let from = InputGuards.validQueueIndex(fromIndex, count: count),
          let to = InputGuards.validQueueIndex(toIndex, count: count),
          // The currently-playing track cannot be reordered — only the tracks
          // around it move. Reordering the current item would force a full
          // rebuild that restarts the active track.
          from != to,
          from != self.currentTrackIndex else { return }

    // Both ends and the neighbours the item lands between, decided before the
    // model changes while those positions still name the same tracks. A move
    // is a remove then an insert, so a forward move lands between the
    // pre-mutation `to` and `to + 1` while a backward one lands between
    // `to - 1` and `to`; both neighbourhoods are covered rather than reasoned
    // about per direction.
    let deferred = deferEngineWork(touching: [from, to - 1, to, to + 1])
    let oldCur = self.currentTrackIndex
    self.queueState.move(from: from, to: to)

    self.currentTrackIndex = QueueMutationArithmetic.currentIndexAfterMove(
      currentIndex: oldCur, fromIndex: from, toIndex: to
    )
    let newCur = self.currentTrackIndex

    // Decide engine-queue adjustment:
    //   * cur unchanged (oldCur == newCur) AND cur track wasn't
    //     moved: engine queue is stale only in the tail; rebuild
    //     tail while preserving currentItem.
    //   * cur moved (from == oldCur) OR cur's physical position
    //     shifted: reconcile against the whole slice. The rebuild keeps the
    //     leading run it can, so the playing track survives a move that
    //     leaves it at the head.
    let preserveCurrent = (from != oldCur) && (newCur == oldCur)
    // Emitted before the early return, like add and remove: the queue model is
    // reordered whether or not the engine has been told yet, and a consumer
    // rendering from this event would otherwise show the old order.
    self.emitQueueChange(.move, movedFrom: from, movedTo: to)
    guard !deferred else { return }
    // A queue that has ended stays ended until a skip, a seek or a fresh
    // queue: the model carries the move and the rebuild those run picks it
    // up. Reconciling now would put a track behind the parked leg.
    if self.reachedQueueEnd { return }
    // performingMutation so the crossfade engine's setItems →
    // engineDidTransitionTrack doesn't clobber the move-computed
    // currentTrackIndex with an index resolved from the engine mid-rebuild (mirrors the
    // setQueue paths, which already mutate under this guard).
    self.performingMutation {
      self.syncPlayerQueue(preserveCurrent: preserveCurrent)
    }

  }

  func clearQueue() throws -> Promise<Void> {
    // Enqueued at call time like every other queue mutation, so a `setQueue`
    // issued straight afterwards cannot be cleared by this one landing late.
    return enqueueThenMirror({
      let queueWasNonEmpty = !self.tracks.isEmpty
      self.performingMutation {
        self.engine?.removeAllItems()
        self.queueState.clear()
        self.currentTrackIndex = -1
        self.currentTrackSource = nil
        // No rearm here — the next `setQueue` goes through
        // `fullRebuildPlayerQueue` which rearms before inserts,
        // so the clean-slate initial-buffer phase is preserved.
      }
      // `performingMutation` suppresses the `\.currentItem` → nil
      // KVO fire; emit a synthetic trackChange here so JS hooks
      // (e.g. `useActiveTrack`) clear their (track, index) snapshot.
      // Mirrors Android `clearQueueInternal` which fires
      // `(null, -1, QUEUE_REPLACED)` via Media3's onMediaItemTransition
      // → `handleMediaItemTransition`. Reset `lastEmittedQueueItemId`
      // so a subsequent `setQueue` re-emits cleanly via the dedup
      // gate at `handleCurrentItemDidChange`.
      self.lastEmittedQueueItemId = nil
      self.lastErrorQueueItemId = nil
      self.lastErrorCode = nil
      self.reportedFailureQueueItemIds.removeAll()
      self.retryAttemptsRemaining.removeAll()
      // Fire queue-change BEFORE the synthetic track-change so a
      // `useQueue()` consumer sees the empty queue + index -1 together,
      // not a one-frame (old queue, index -1) transient. Only when the
      // queue was actually non-empty — clearing an empty queue is a no-op.
      if queueWasNonEmpty { self.emitQueueChange(.clear) }
      self.trackChangeListeners.forEach { $0(nil, Double(-1), .queueReplaced, nil) }
      self.nowPlayingInfo.refreshAll(self.nowPlayingSnapshot(), trigger: .queueCleared)
      self.artworkResolver.cancelInFlight()
      // Drop any cached now-playing format + emit null so JS-side
      // consumers of `onNowPlayingFormatChange` see the queue-cleared
      // state without waiting for the engine's `currentMediaItem` to
      // settle (it can briefly retain the last item post-removeAll).
      self.refreshNowPlayingFormatForActiveItem()
      self.rescheduleLookahead()
      self.recomputeCapabilities()
      // The queue is empty now → settle buffer state + fully-buffered to their
      // empty defaults and emit the transition. The `\.currentItem → nil` KVO
      // is suppressed under `performingMutation`, so recompute explicitly.
      self.hasStartedPlaying = false
      self.wantsToPlay = false
      self.recomputeBufferState()
      self.recomputeFullyBuffered()
    }, mirror: { _, _ in
      // Mirror the clear to the receiver (removes every receiver queue item).
      _ = await CastTransportRouter.routeClearQueue()
    })
  }

  func getQueue() throws -> Promise<[TrackItem]> {
    return enqueueValue { self.tracks }
  }

  /// Sync the engine's queue with `self.tracks[currentTrackIndex...]`.
  ///
  /// When `preserveCurrent` is true AND the engine has a currentItem,
  /// that item is kept in place and only the tail (items after
  /// current) is rebuilt — avoids a playback restart of the current
  /// track. Used for mutations that leave the currently-playing
  /// track untouched (e.g. remove-after-cur, move-entirely-after-cur).
  ///
  /// When false (or when there is no currentItem), reconciles against the
  /// whole slice. That only restarts the current track when the slice actually
  /// diverges at its head — a rebuild keeps whatever leading run the engine
  /// already holds.
  internal func syncPlayerQueue(preserveCurrent: Bool) {
    assertQueueIdsInvariant("syncPlayerQueue")
    guard let engine = self.engine else { return }
    guard self.currentTrackIndex >= 0,
          self.currentTrackIndex < self.tracks.count else {
      engine.removeAllItems()
      return
    }

    // The branch resolves before the tail build: `fullRebuildPlayerQueue`
    // constructs `tracks[cur...]` itself, and every constructed item starts
    // an `automaticallyLoadedAssetKeys` load plus, on a cache hit, a `touch`
    // that queues an index-plist write. Keep this guard above the build.
    // Anchor on the leg being heard, not on `currentMediaItem` — that switches
    // to the incoming leg the moment a fade starts, so the trim below would
    // remove the item actually playing. A fade in flight goes to the full
    // rebuild, which resolves the anchor by identity and guards the trim.
    guard preserveCurrent, !engine.isFadePendingOrActive,
          let currentItem = engine.leadingQueueItemId.flatMap({ id in
            engine.allMediaItems.first { $0.queueItemId == id }
          })
    else {
      return self.fullRebuildPlayerQueue()
    }

    // Build the tail post-currentItem. The origin comes from the engine's
    // own item: `currentTrackIndex` is resynced a main-queue hop later, so
    // reading it here can place the tail one position out and leave the
    // engine's run non-contiguous.
    let currentPosition = matchTrackIndex(forCurrentItem: currentItem) ?? self.currentTrackIndex
    let tailStart = currentPosition + 1
    // Bounded to the floor on both engines, so a move on a long queue builds
    // at most a window of items. The trail is not rebuilt through this path;
    // the next reconcile restores it.
    let tailEnd = min(currentPosition + QueueWindowArithmetic.floor, self.tracks.count)
    let tailTracks = Array(self.tracks[tailStart ..< tailEnd])
    let tailIds = Array(self.queueItemIds[tailStart ..< tailEnd])
    let tailItems = AVQueueBuilder.buildPlayerItems(
      tracks: tailTracks, queueItemIds: tailIds,
      config: self.config, server: self.routingServer)

    let dropped = engine.allMediaItems.filter { $0 !== currentItem }
    AVQueueBuilder.cancelLoading(dropping: dropped, keeping: [currentItem] + tailItems)
    for item in dropped {
      engine.remove(item)
    }
    if !engine.insertItems(tailItems, after: currentItem) {
      // Couldn't insert into the live queue — fall back to a full
      // rebuild so authoritative state + player converge.
      self.fullRebuildPlayerQueue()
    }
  }

  /// Whether the engine's items can be left alone until the fade settles.
  ///
  /// Any change to the installed items cancels an in-flight fade, so a
  /// mutation that touches neither leg is held back rather than cutting a
  /// crossfade short for a part of the queue nobody is hearing. One that does
  /// touch a leg cannot wait: letting the arm chain ride on through would
  /// fade into a track the queue no longer has.
  internal func deferEngineWork(touching positions: [Int]) -> Bool {
    guard let engine = self.engine, engine.isFadePendingOrActive else { return false }
    let attached = Set(engine.attachedQueueItemIds)
    let touched = positions.compactMap { self.queueItemIds[safe: $0] }
    // No resolvable position means nothing is known about what this touches,
    // which is not the same as knowing it touches nothing.
    guard !touched.isEmpty else { return false }
    guard touched.allSatisfy({ !attached.contains($0) }) else { return false }
    self.engineReconcileDeferred = true
    return true
  }

  /// Apply a mutation the engine was not told about while a fade was running.
  ///
  /// Called when the fade settles, either way it settles. The reconcile
  /// re-derives everything from the model, so it does not matter how many
  /// mutations were held back or in what order they arrived.
  internal func flushDeferredEngineWork() {
    guard self.engineReconcileDeferred else { return }
    // A fade that is still pending has not settled; the work keeps waiting.
    guard self.engine?.isFadePendingOrActive != true else { return }
    // Every engine mutation cancels a fade, and a cancel settles one, so this
    // is reachable from inside a rebuild that is part-way through its own
    // trim-and-append. Rebuilding now would run against the outer pass's stale
    // view of the installed run and could append a second copy of an item it
    // still believes it holds. Keep the work owed and take it on the next hop,
    // when the outer mutation has finished.
    guard !self.isMutatingPlayerQueue else {
      self.playerQueue.async { [weak self] in self?.flushDeferredEngineWork() }
      return
    }
    self.engineReconcileDeferred = false
    self.performingMutation { self.fullRebuildPlayerQueue() }
    self.rescheduleLookahead()
    self.recomputeCapabilities()
  }

  /// How many played positions the engine keeps behind the playhead.
  ///
  /// The gapless `AVQueuePlayer` consumes finished items, so it can hold none
  /// and asking for a trail there would be asking for items it drops anyway.
  /// The crossfade engine retains its whole installed run, so without a trail
  /// the run only grows — which moves the memory peak later rather than
  /// lowering it. Two positions buys `AVURLAsset` reuse for an immediate
  /// backward skip and nothing beyond that, which is why it is not deeper.
  internal var engineTrail: Int {
    self.player != nil ? 0 : 2
  }

  /// Bring the engine's items in line with the positions it should be holding.
  ///
  /// The positions come from `QueueWindowArithmetic.slice`, which is the one
  /// answer to "what should be installed" — a trail behind the playhead and
  /// the floor ahead of it, stopping at the last position whatever the repeat
  /// mode; the wrap is decided on the drain path by `applyEndVerdict`.
  ///
  /// Reconciling rather than reinstalling: positions that fell off the back of
  /// the trail are removed, the run the engine already holds correctly is
  /// kept, and only the rest is built. Installing the whole slice would
  /// discard the item already buffered, which is audible as a cut when this
  /// lands at a boundary.
  ///
  /// `forcingReinstall` is for the callers that rebuild in order to replace a
  /// *specific* item rather than to follow a model change — the retry paths,
  /// which exist to construct a fresh item over one that has failed
  /// terminally. A failed item keeps its `queueItemId`, so identity cannot
  /// tell a dead item from a live one and the caller has to say.
  internal func fullRebuildPlayerQueue(forcingReinstall: Bool = false) {
    // A forced reinstall builds the current position from scratch, so whatever
    // was reported against it no longer describes what is installed. Its
    // callers — `play()`, `retry()` and `retryFailedItem` — drop the position
    // they attempt themselves too; this covers the case where the rebuild below
    // bails at its own guards, and a skip that seats without rebuilding clears
    // its target in `applyNewCurrentIndex`. `stopOnDroppedTrack` also forces a
    // reinstall and files its report after this line, so the clear does not
    // reach it.
    if forcingReinstall { self.reportedFailureQueueItemIds.removeAll() }
    assertQueueIdsInvariant("fullRebuildPlayerQueue")
    guard let engine = self.engine else { return }
    guard self.currentTrackIndex >= 0,
          self.currentTrackIndex < self.tracks.count else {
      engine.removeAllItems()
      return
    }
    let cur = self.currentTrackIndex
    // `wraps: false` deliberately. Carrying the head into the run at the tail
    // would let a repeat-queue boundary crossfade instead of cutting, but it
    // also moves the wrap off the drain path: the engine advances into the
    // wrap target itself instead of reporting that it ran out, and
    // `applyEndVerdict` never decides the wrap. The run stays forward-only;
    // `slice` carries the arithmetic for a wrapping run.
    let positions = QueueWindowArithmetic.slice(
      currentIndex: cur,
      trackCount: self.tracks.count,
      trail: self.engineTrail,
      wraps: false
    )
    guard !positions.isEmpty else {
      engine.removeAllItems()
      return
    }
    let slice = positions.map { self.tracks[$0] }
    let sliceIds = positions.map { self.queueItemIds[$0] }
    let currentId = self.queueItemIds[cur]

    let installed = engine.allMediaItems
    // Where the item being heard sits in the run. The trim must never reach
    // it: removing it replaces the leading leg's current item, which is a cut
    // in the middle of a track.
    let leading = engine.leadingQueueItemId.flatMap { id in
      installed.firstIndex { $0.queueItemId == id }
    }

    // Anchor on the first wanted position the engine actually holds, but never
    // past the current one. The trail is an optimisation — a missing trail
    // position is not worth reinstalling the run and restarting what is
    // playing — while a missing current position is exactly what a reinstall
    // is for.
    let curOffset = positions.firstIndex(of: cur) ?? 0
    var anchor = 0
    while anchor < curOffset,
          !installed.contains(where: { $0.queueItemId == sliceIds[anchor] }) {
      anchor += 1
    }
    let wantedIds = Array(sliceIds[anchor...])
    let wantedTracks = Array(slice[anchor...])

    // Positions that have fallen off the back of the run come off the front of
    // what is installed. A run that has rotated past the leading item cannot
    // be expressed as a trim plus an append, so it is reinstalled instead.
    let fellOut = installed.firstIndex { $0.queueItemId == wantedIds.first }
    let rotatedPastLeading = fellOut != nil && leading != nil && fellOut! > leading!
    let retained = (forcingReinstall || rotatedPastLeading)
      ? []
      : fellOut.map { Array(installed[$0...]) } ?? []
    let keep = QueueMutationArithmetic.commonPrefixLength(
      current: retained.map { $0.queueItemId }, desired: wantedIds)

    // Already holding exactly this run: touching it would only throw away the
    // buffer it has built.
    if keep == wantedIds.count, keep == retained.count, fellOut == 0, anchor == 0 {
      return
    }

    // Nothing worth keeping — install the run whole.
    guard keep > 0 else {
      reinstallWholeSlice(
        on: engine, slice: slice, sliceIds: sliceIds, startingAt: currentId)
      return
    }

    // The divergent tail must not contain the item being heard. Removing it
    // replaces the leading leg's current item, which stops playback and
    // restores no rate, so the engine sits paused with the transport still
    // wanting to play. Installing the run whole is the destructive option, but
    // it is the one that puts the rate back.
    //
    // `retained` is `installed[fellOut...]`, so the kept prefix ends at
    // `installed[fellOut + keep]` and the tail is everything from there on.
    let tailStart = (fellOut ?? 0) + keep
    guard leading.map({ $0 < tailStart }) ?? true else {
      reinstallWholeSlice(
        on: engine, slice: slice, sliceIds: sliceIds, startingAt: currentId)
      return
    }

    // Cancel before releasing: `AVURLAsset.dealloc` blocks the thread that
    // releases it while it cancels in-flight key loads, and that thread is the
    // one serialising player state. Every wholesale path already does this;
    // the surgical trim has to as well or a window slide that drops a
    // still-loading remote item stalls the queue behind it.
    let dropped = Array(installed[..<(fellOut ?? 0)]) + Array(retained[keep...])
    AVQueueBuilder.cancelLoading(dropping: dropped, keeping: Array(retained[..<keep]))
    if let fellOut {
      for item in installed[..<fellOut] {
        engine.remove(item)
      }
    }
    for item in retained[keep...] {
      engine.remove(item)
    }
    guard keep < wantedIds.count else { return }
    let tail = AVQueueBuilder.buildPlayerItems(
      tracks: Array(wantedTracks[keep...]), queueItemIds: Array(wantedIds[keep...]),
      config: self.config, server: self.routingServer)
    guard !tail.isEmpty else { return }
    if !engine.insertItems(tail, after: retained[keep - 1]) {
      // The engine refused the surgical insert, so install the whole run
      // instead. Rebuilding the tail's items a second time here is the cost of
      // a path that only runs when the engine has already said no.
      reinstallWholeSlice(
        on: engine, slice: slice, sliceIds: sliceIds, startingAt: currentId)
    }
  }

  /// Drain the engine and install the whole run, seated on the current
  /// position.
  ///
  /// The only path that seats a fresh current item, which is why the
  /// gapless-flip rearm lives here: arming it on a rebuild that installs
  /// nothing would leave the one-shot armed with no `\.readyToPlay` coming to
  /// disarm it, and stalling would stay on for the rest of the track.
  private func reinstallWholeSlice(
    on engine: PlaybackEngine, slice: [TrackItem], sliceIds: [String],
    startingAt currentId: String
  ) {
    rearmGaplessFlip()
    // Every item installed below is built now, against the live listener, so
    // nothing left in the engine addresses one a restart replaced.
    readThroughReinstallPending = false
    let items = AVQueueBuilder.buildPlayerItems(
      tracks: slice, queueItemIds: sliceIds,
      config: self.config, server: self.routingServer)
    guard let startIndex = Self.seatIndex(in: items, sliceIds: sliceIds, currentId: currentId)
    else {
      // Nothing at or after the current position could be built. Seating on
      // the trail would replay a track already heard; installing nothing
      // leaves the engine drained, which is what the end verdict reads.
      engine.removeAllItems()
      return
    }
    // Reinstalling the run the engine is already rendering from would restart
    // the track being heard, so carry its playhead across. Only when the item
    // the run seats on is that same one: any other start item is a track
    // change, which begins at zero.
    let position =
      items[startIndex].queueItemId == engine.leadingQueueItemId
      ? engine.currentPositionSeconds : 0
    engine.setItems(
      items, startIndex: startIndex, startPositionSeconds: position)
  }

  /// Where in a built run the engine starts, or nil when nothing at or after
  /// the current position could be built.
  ///
  /// The run can start behind the playhead, so the engine is told where in it
  /// to begin. Resolved against the built items rather than the positions: an
  /// item whose URL cannot be built is dropped, which shifts everything after
  /// it. When the current position is one of the dropped ones, the next
  /// position in the run that did build is the seat — playing forwards from an
  /// unplayable track, rather than dropping back into the trail behind it.
  internal static func seatIndex(
    in items: [AVPlayerItem], sliceIds: [String], currentId: String
  ) -> Int? {
    if let exact = items.firstIndex(where: { $0.queueItemId == currentId }) {
      return exact
    }
    guard let curOffset = sliceIds.firstIndex(of: currentId) else { return nil }
    let ahead = Set(sliceIds[curOffset...])
    return items.firstIndex { item in item.queueItemId.map(ahead.contains) ?? false }
  }

  /// Destructive shuffle. Mutates the current queue in-place via
  /// Fisher-Yates, resets `currentTrackIndex` to 0, and resumes
  /// playback on the new tracks[0] if the player was playing
  /// pre-shuffle. Returns the post-shuffle snapshot so the consumer
  /// app can immediately reflect the new ordering in its UI.
  ///
  /// The pre-shuffle ordering is not preserved; once shuffled the
  /// queue stays in the new order until the consumer calls
  /// `setQueue` to install a fresh ordering.
  ///
  /// Empty queue → returns `{ tracks: [], currentIndex: -1, currentTrack: nil }`
  /// without mutating state.
  func shuffleQueue() throws -> Promise<ShuffleResult> {
    // Enqueued at call time like every other queue mutation, so a mutation
    // issued straight afterwards resolves against the shuffled order.
    return enqueueValue { () -> ShuffleResult in
      var result = ShuffleResult(
        tracks: [], currentIndex: -1, currentTrack: nil)
      // Capture playing state BEFORE the mutation so we know whether to
      // resume after the rebuild. The transport's intent rather than the
      // engine's audible state: a leg that is buffering, or mid-crossfade
      // with its outgoing item finished, reports not-playing while the
      // consumer is still playing.
      let wasPlaying = self.wantsToPlay

      if self.tracks.isEmpty {
        // Empty queue — leave state untouched, return the empty snapshot.
        return result
      }
      guard self.engine != nil else {
        // No engine attached (configure() not yet called). Bail with
        // empty result rather than silently mutating tracks but not
        // the player — the prior shape returned a "shuffled"
        // snapshot while leaving self.tracks unchanged, which lied
        // to the consumer. Gate on `self.engine` (canonical lib-
        // initialised sentinel); `self.player` is gapless-only and
        // nil under CrossfadeEngine.
        return result
      }

      // Shuffle positions rather than tracks: the permutation is what
      // onQueueChange reports, and it cannot be recovered from the reordered
      // list afterwards. Fisher-Yates via Swift stdlib (Apple-documented).
      var order = Array(self.tracks.indices)
      order.shuffle()
      let newTracks = order.map { self.tracks[$0] }

      self.performingMutation {
        // Mirrors setQueue — `replaceAll` regenerates queueItemIds
        // so each new AVPlayerItem gets a unique lib-internal
        // identity and the destructive shuffle re-keys every
        // position.
        let newIds = self.queueState.replaceAll(newTracks)
        self.currentTrackIndex = 0
        self.pendingTrackChangeReason = .queueReplaced
        // Re-keyed and re-seated at 0: not over, whatever it was before.
        self.reachedQueueEnd = false
        let attempts = self.effectiveAutoRetries()
        self.retryAttemptsRemaining = Dictionary(
          uniqueKeysWithValues: newIds.map { ($0, attempts) }
        )
        self.fullRebuildPlayerQueue()
      }
      self.rescheduleLookahead()
      self.recomputeCapabilities()
      // Skip a single-track shuffle — the order can't change.
      if self.tracks.count > 1 { self.emitQueueChange(.shuffle, order: order) }

      // Resume playback if the user was playing before the shuffle.
      // Stays paused otherwise — consumer can call play() if they
      // want to start from the new track 0.
      if wasPlaying, !self.isInterrupted {
        self.pendingStateChangeReason = .user
        self.beginPlayback()
      }

      result = ShuffleResult(
        tracks: self.tracks,
        currentIndex: 0,
        currentTrack: self.tracks[0])
      return result
    }
  }
}
