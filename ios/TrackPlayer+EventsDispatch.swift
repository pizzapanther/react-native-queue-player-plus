import AVFoundation

extension TrackPlayer {
  // MARK: - Events — dispatch

  /// Called from the `\.currentItem` KVO dispatch in
  /// `installGaplessObservers`. Fires `onTrackChange` whenever the
  /// player's current item changes, and — when the change is
  /// "currentItem went nil while the queue still had tracks" outside
  /// of an in-flight mutation — also fires `onQueueEnd` and emits an
  /// `.ended` state change.
  ///
  /// Mid-mutation nil fires are suppressed entirely. Every
  /// `removeAllItems` + `insert` pair produces a transient
  /// `currentItem == nil` observation before the real new item
  /// lands; without the `isMutatingPlayerQueue` guard we'd both
  /// consume `pendingTrackChangeReason` against the intermediate
  /// fire AND spuriously emit `onQueueEnd` on every backward skip,
  /// setQueue, full rebuild, etc.
  internal func handleCurrentItemDidChange() {
    if self.isMutatingPlayerQueue && self.player?.currentItem == nil {
      return
    }
    // Snapshot timestamp first thing so `transitionGapMs` measures the
    // iOS-side notification→KVO scheduling delta, not the synchronous
    // lib work that follows. Always overwritten — cheap UInt64 store.
    self.gaplessGapMeasuredAtNs = DispatchTime.now().uptimeNanoseconds
    let reason = consumePendingReasonAndResyncIndex()
    dispatchTrackChange(reason: reason)
    // Native auto-advance can change `currentTrackIndex` (via the
    // matchTrackIndex resync) without going through any explicit
    // mutation method. Recompute capabilities here so the boundary-
    // flip canSkipNext under `.off` (last-track auto-advance leaving
    // cur on the last track with no next) lands.
    self.recomputeCapabilities()
    detectQueueEnd()
    // The matchTrackIndex resync makes `currentTrackIndex` canonical
    // post-auto-advance, so rescheduling the prefetcher window from
    // here shifts it to cur+1..cur+lookaheadCount against the
    // freshly-synced index. The prefetcher dedupes by url, so
    // re-issuing the same window mid-prefetch is cheap.
    self.rescheduleLookahead()
    // Last: the append resolves against the freshly-pointed prefetcher, and
    // its synchronous item construction stays behind the user-visible
    // track change rather than in front of it.
    self.topUpWindow()
  }

  /// Consume `pendingTrackChangeReason` + `didPlayToEndPending` and,
  /// when the player has auto-advanced naturally, resync
  /// `currentTrackIndex` from the player's current AVPlayerItem via
  /// `matchTrackIndex`. Returns the consumed reason for the caller
  /// to thread into `dispatchTrackChange`. Defaults to `.autoAdvance`
  /// when no user mutation stashed a reason.
  ///
  /// Resync requires BOTH `didPlayToEndPending` set AND no pending
  /// user-mutation reason. User mutations pre-write
  /// `currentTrackIndex` before the rebuild — that intent is
  /// authoritative. The reason gate covers the FB9221518 case where
  /// a paused-state `removeAllItems` fires DidPlayToEndTime for a
  /// queue item that was never played; without it the post-rebuild
  /// `\.currentItem` KVO, once on `playerQueue`, would resync to
  /// AVQueuePlayer's stale `currentItem` value (the pre-rebuild item).
  private func consumePendingReasonAndResyncIndex() -> TrackChangeReason {
    // Resync gate: only when the player auto-advanced naturally
    // (no pending user-mutation reason AND DidPlayToEndTime fired).
    //
    // Two gates AND-ed together because each catches a distinct
    // wrong-resync vector:
    //
    // 1. `didPlayToEndPending`: chain-advance bounces during a
    //    `removeAllItems` + insert rebuild do NOT fire the notification,
    //    so this gate excludes those.
    // 2. `pendingUserMutation`: a paused-state queue mutation
    //    (skipToIndex, skipPrev, skipNext via fullRebuild) under FB9221518
    //    can fire `AVPlayerItemDidPlayToEndTime` for an item being
    //    pulled from the queue. Without this gate the fired
    //    notification lands at the post-mutation `\.currentItem` KVO
    //    hop to `playerQueue`, sees `didPlayToEndPending == true`, and resyncs
    //    `currentTrackIndex` to whatever AVQueuePlayer's
    //    not-yet-published `currentItem` resolves to — which can be
    //    the pre-mutation item's queueItemId. The user-mutation pre-
    //    write of `currentTrackIndex` is authoritative; the resync
    //    must defer to it.
    let pendingUserMutation = self.pendingTrackChangeReason != nil
    let reason = self.pendingTrackChangeReason ?? .autoAdvance
    self.pendingTrackChangeReason = nil
    let endFired = self.didPlayToEndPending
    self.didPlayToEndPending = false
    // A queue stopped on a reported failure does not re-derive its index from
    // the player. `removeAllItems` fires `AVPlayerItemDidPlayToEndTime` for an
    // item that never played (FB9221518), and the re-seat below issues one, so
    // without this a later pass would resync off the parked position onto the
    // track behind it — the move the stop exists to prevent. The report does
    // not exist yet on the pass that files it, so that pass still runs.
    if endFired && !pendingUserMutation && self.reportedFailureOnCurrentTrack == nil {
      if let derived = matchTrackIndex(forCurrentItem: self.player?.currentItem) {
        // One natural advance that lands more than one position on means the
        // player dropped what it skipped: `AVQueuePlayer` discards an item that
        // cannot be played, and a track that failed while the previous one was
        // still playing is never seated, so nothing ever observes its failure —
        // the current-item status observer is the only load-failure path and it
        // never sees that item. Carrying the queue past it would skip a track
        // the listener never heard and was never told about.
        if derived > self.currentTrackIndex + 1 {
          self.stopOnDroppedTrack(at: self.currentTrackIndex + 1)
        } else {
          self.currentTrackIndex = derived
        }
      }
    }
    return reason
  }


  /// Stop on a track the player dropped mid-advance, report it, and park.
  ///
  /// Nothing has observed this track fail — `AVQueuePlayer` removed it rather
  /// than seating it — so the report is raised here, against the position.
  /// `fileFormatNotRecognized` is chosen for being fatal, which is what the
  /// stop needs: the same function drops the play intent, and doing that under
  /// a transient code would contradict `dispatchErrorOrRetry`, which refuses to.
  /// The library does not know *why* the player dropped it — a 404, a DNS
  /// failure and a non-media body all land here — so the code is a statement
  /// about recoverability, not about the body.
  private func stopOnDroppedTrack(at index: Int) {
    guard let qid = self.queueItemIds[safe: index] else { return }
    self.currentTrackIndex = index
    self.engine?.pause()
    self.wantsToPlay = false
    // The player discarded this position's item and is sitting on one further
    // on, so every read that answers from the seated item — duration,
    // seekability, the progress payload's position — would describe a track the
    // listener is not on. Re-seat the failed position so the index and the item
    // agree. Forced, because the item keeps its `queueItemId` and an identity
    // comparison would find the slice already correct.
    //
    // Read before the rebuild, which empties the set: a track the status
    // observer already reported is one the consumer has been told about, and
    // the drop is the same failure arriving by a second route.
    let alreadyReported = self.reportedFailureQueueItemIds.contains(qid)
    // The report is filed after: a forced rebuild clears the set, and the
    // re-seated item failing again is then suppressed by this entry, so one
    // dropped track still reports once.
    self.performingMutation { self.fullRebuildPlayerQueue(forcingReinstall: true) }
    self.reportedFailureQueueItemIds.insert(qid)
    // The queue still parks — index, pause, intent and the re-seat above all
    // stand — it just does not say so twice.
    if alreadyReported { return }
    let err = self.buildPlaybackError(
      nil,
      mapped: .fileFormatNotRecognized,
      queueItemId: qid,
      url: self.tracks[safe: index]?.url ?? "",
      nativeDomainOverride: "PLAYER_ITEM_DROPPED"
    )
    self.errorListeners.forEach { $0(err) }
    self.emitState(.error, reason: .error)
  }

  /// Debug-only log of the classifier's verdict for the current item next to
  /// the URL its asset is bound to and its `sourceURL`.
  ///
  /// The verdict is `classifyTrackSource`'s, computed by the caller from the
  /// track URL, whether the item is routed through the read-through server,
  /// and whether the cache holds the whole file. The asset URL decides
  /// nothing: for a routed item it is the server's `127.0.0.1` address
  /// whether the bytes come from disk or the origin. Queries are stripped: a
  /// source URL carries a token in its query for any server that
  /// authenticates that way.
  private func logPlaybackSource(_ classified: TrackSource?) {
    #if DEBUG
      guard let item = self.engine?.currentMediaItem else { return }
      let assetURL = (item.asset as? AVURLAsset)?.url.absoluteString ?? "<non-url asset>"
      let source = item.sourceURL ?? "<none>"
      let origin = classified.map { "\($0)" } ?? "unknown"
      NSLog(
        "[RNQP-DIAG] now-playing from=%@ asset=%@ source=%@",
        origin,
        PlaybackErrorMapping.stripQuery(assetURL),
        PlaybackErrorMapping.stripQuery(source)
      )
    #endif
  }

  /// Re-derive `currentTrackSource` for whatever `currentTrackIndex` now
  /// points at. Caller is on `playerQueue`.
  internal func reclassifyCurrentTrackSource() {
    let idx = self.currentTrackIndex
    let track: TrackItem? = (idx >= 0 && idx < self.tracks.count)
      ? self.tracks[idx] : nil
    let cache = self.lookaheadCache
    // Whether this position's item reads through the server is decided when
    // the item is built and stamped on it, so the installed item is asked
    // first — any item in the run, not only the seated one, so the answer
    // does not depend on the engine having advanced yet. A position with no
    // item installed is answered on the terms `makeItem` will use for it.
    // A disabled cache serves nothing, so nothing is `cached` while it is off.
    let stamped: Bool? = engineIndex(forQueuePosition: idx).flatMap { engineIdx in
      self.engine?.allMediaItems[safe: engineIdx]?.isRoutedThroughReadThroughServer
    }
    let isRouted = self.lookaheadConfig.enabled
      && (stamped ?? (self.routingServer != nil
            && (track.map { AVQueueBuilder.isRoutable($0.url) } ?? false)))
    self.currentTrackSource = TrackPlayer.classifyTrackSource(
      url: track?.url, isRouted: isRouted
    ) { url in
      cache?.isFullyCached(url: url) ?? false
    }
    logPlaybackSource(self.currentTrackSource)
  }

  /// Fire `trackChangeListeners` for the current `(track, idx,
  /// reason)`, deduped on `lastEmittedQueueItemId` (NOT idx — index
  /// alone would suppress setQueue replacements that keep cur=0 with
  /// a different first track). On a real fire, also resets the
  /// error-dedup state so a new item's identical-coded error fires.
  internal func dispatchTrackChange(reason: TrackChangeReason) {
    // Rebind even when a retry / engine swap preserves the queue entry's id.
    refreshTimedMetadataObservation()
    let idx = self.currentTrackIndex
    let track: TrackItem? = (idx >= 0 && idx < self.tracks.count)
      ? self.tracks[idx] : nil
    let currentItemId: String? = self.queueItemIds[safe: idx]
    self.reclassifyCurrentTrackSource()
    if self.lastEmittedQueueItemId != currentItemId {
      self.lastEmittedQueueItemId = currentItemId
      _ = self.pauseForVideoTrack()
      self.lastErrorQueueItemId = nil
      self.lastErrorCode = nil
      // A report against some OTHER track says nothing about the one now being
      // announced, so it goes. A report against the track being announced
      // stands: an item that is already failed when it is seated has its error
      // dispatched before this, the first announcement of its position, and
      // clearing here would leave the queue with nothing to hold on.
      self.reportedFailureQueueItemIds = self.reportedFailureQueueItemIds
        .intersection(currentItemId.map { [$0] } ?? [])
      // A real track (re)loaded => no longer at the end of the queue. At true
      // end-of-queue this block is skipped entirely — the last-track id still
      // matches `lastEmittedQueueItemId` — so `reachedQueueEnd` stays set; the
      // `idx >= 0` guard only keeps an explicit clear to index -1 from touching it.
      if idx >= 0 { self.reachedQueueEnd = false }
      // New active track => new milestone playthrough. This is the single
      // deduped track-change point (the crossfade fade-start echo collapses
      // here), so it resets exactly once per real track change.
      self.milestoneTracker.reset()
      // New track => its initial load counts as `buffering` (not `stalled`)
      // until it reaches a playing state, and it starts not-fully-buffered.
      self.hasStartedPlaying = false
      self.recomputeFullyBuffered()
      // Measure the gapless auto-advance silence gap (nil for every
      // other transition) before the emit so it rides on onTrackChange
      // as `transitionGapMs`. The timestamp was captured at the top of
      // handleCurrentItemDidChange, above this method's synchronous lib
      // work; falls back to now() if the snapshot wasn't taken.
      var transitionGapMs: Double? = nil
      if reason == .autoAdvance && self.gaplessGapPrevEndedAtNs > 0 {
        let now = self.gaplessGapMeasuredAtNs > 0
          ? self.gaplessGapMeasuredAtNs
          : DispatchTime.now().uptimeNanoseconds
        let gapNs = now &- self.gaplessGapPrevEndedAtNs
        transitionGapMs = Double(gapNs) / 1_000_000.0
        self.gaplessGapPrevEndedAtNs = 0
        self.gaplessGapMeasuredAtNs = 0
        #if DEBUG
        self.gaplessLogLastChangedAtNs = now
        NSLog("[RNQP-GAPLESS] item-changed trackIdx=%d ts_ns=%llu delta_since_ended_ms=%.3f",
              idx, now, transitionGapMs ?? 0)
        #endif
      }
      // Cross-platform listener-fire order: track-change first so the
      // consumer's track-keyed handlers run before the format-refresh
      // emits null. Matches Android's onMediaItemTransition →
      // refreshNowPlayingFormatForActiveItem ordering.
      trackChangeListeners.forEach { $0(track, Double(idx), reason, transitionGapMs) }
      self.refreshNowPlayingFormatForActiveItem()
      self.nowPlayingInfo.refreshAll(self.nowPlayingSnapshot(), trigger: .trackChange)
      // Kick the network resolve. The placeholder is already on the
      // lock-screen via refreshAll; the resolver swaps in the real
      // bitmap when the fetch lands. ArtworkResolver's sequence counter
      // drops a superseded fetch, and `applyArtwork` drops an image whose
      // entry is no longer the published one.
      DiagnosticLog.player.log("trackChange index=\(idx) reason=\(reason.stringValue) hasArtworkUrl=\(track?.artworkUrl != nil)")
      self.artworkResolver.resolve(url: track?.artworkUrl) { [weak self] image in
        self?.nowPlayingInfo.applyArtwork(image, for: currentItemId)
      }
    }
  }

  /// Classify the active-track playback source.
  ///
  /// `.cached` means the bytes being played are coming off local disk, which
  /// takes two things: the cache holds the track in full, **and** the item is
  /// bound to the read-through server, which is what reads from the cache. A
  /// track sitting complete on disk that the player is not routed through is
  /// still being streamed, and saying otherwise is how this reported `cached`
  /// while every byte came from the origin.
  ///
  /// `nil` when the URL is nil or its scheme is neither `file://` nor
  /// `http(s)://` — `data:`, `blob:` and custom schemes have no contract here.
  internal static func classifyTrackSource(
    url: String?, isRouted: Bool, isUrlCached: (String) -> Bool
  ) -> TrackSource? {
    guard let url = url else { return nil }
    if url.hasPrefix("file://") { return .local }
    let isHttp = url.hasPrefix("http://") || url.hasPrefix("https://")
    guard isHttp else { return nil }
    guard isRouted else { return .streaming }
    return isUrlCached(url) ? .cached : .streaming
  }

  /// Fires from the `addPeriodicTimeObserver` tick (gapless mode)
  /// or the engine-agnostic `progressFallbackTimer` (crossfade
  /// mode, where `self.player` is nil). Reports current position +
  /// duration + seconds-buffered-ahead-of-position.
  ///
  /// The two paths source position differently: gapless reads off
  /// `self.player.currentItem` directly; crossfade reads off
  /// `engine.currentPositionSeconds` / `currentDurationSeconds` /
  /// `bufferedPositionSeconds` (computed properties on the
  /// engine surface that delegate to whichever leg is leading).
  /// Shared by the snapshot getter and event emitter. Does not touch cadence,
  /// so reads cannot suppress a later event.
  internal func localProgressSnapshot() -> PlayerProgress {
    let position: TimeInterval
    let duration: TimeInterval
    let buffered: TimeInterval
    if let item = self.player?.currentItem {
      position = CMTimeGetSeconds(item.currentTime())
      let rawDuration = CMTimeGetSeconds(item.duration)
      duration = (rawDuration.isFinite && rawDuration > 0) ? rawDuration : 0
      buffered = loadedAheadSeconds(for: item, currentPosition: position)
    } else if let engine = self.engine {
      position = engine.currentPositionSeconds
      duration = engine.currentDurationSeconds
      buffered = max(0, engine.bufferedPositionSeconds - position)
    } else {
      return PlayerProgress(position: 0, duration: 0, buffered: 0)
    }
    return PlayerProgress(
      position: position.isFinite ? position : 0,
      duration: duration.isFinite ? duration : 0,
      buffered: buffered.isFinite ? buffered : 0)
  }

  internal func emitProgressIfSubscribed() {
    guard !progressListeners.isEmpty,
          PlaybackStateRouter.shared.activeRemote() == nil,
          !tracks.isEmpty, currentTrackIndex >= 0,
          engine?.isPlaying == true else { return }
    // Check playback before the gate so paused seeks cannot consume cadence.
    guard self.progressEmissionGate.shouldEmit(nowMs: self.monotonicNowMs()) else { return }
    let progress = localProgressSnapshot()
    progressListeners.forEach { $0(progress) }
  }

  /// Advance the milestone tracker off the same periodic tick and emit any
  /// 25/50/75/90% thresholds forward playback just crossed. Runs the
  /// tracker even with no subscribers (so a mid-stream subscribe doesn't
  /// retroactively fire already-passed thresholds); emission is gated on
  /// having listeners.
  internal func processMilestones() {
    // No active track => nothing to attribute a milestone to (mirrors the
    // Android guard; avoids emitting with trackIndex -1 on a stale read
    // during a clear / queue-end transition).
    guard !self.tracks.isEmpty, self.currentTrackIndex >= 0 else { return }
    let position: TimeInterval
    let playerDuration: TimeInterval
    if let item = self.player?.currentItem {
      position = CMTimeGetSeconds(item.currentTime())
      let raw = CMTimeGetSeconds(item.duration)
      playerDuration = (raw.isFinite && raw > 0) ? raw : 0
    } else if let engine = self.engine {
      position = engine.currentPositionSeconds
      playerDuration = engine.currentDurationSeconds
    } else {
      return
    }
    guard position.isFinite else { return }
    let duration = effectiveMilestoneDuration(playerDuration: playerDuration)
    let crossed = milestoneTracker.tick(durationSec: duration, positionSec: position)
    guard !crossed.isEmpty, !milestoneListeners.isEmpty else { return }
    let idx = Double(self.currentTrackIndex)
    for m in crossed {
      milestoneListeners.forEach { $0(Double(m), idx) }
    }
  }

  /// Player-reported duration when known (> 0), else the consumer-supplied
  /// `TrackItem.duration` (seconds) for the active track, else 0 (no
  /// computable duration → no milestones).
  internal func effectiveMilestoneDuration(playerDuration: TimeInterval) -> TimeInterval {
    if playerDuration > 0 { return playerDuration }
    let idx = self.currentTrackIndex
    if idx >= 0, idx < self.tracks.count, let d = self.tracks[idx].duration, d > 0 {
      return d
    }
    return 0
  }

  /// Map an `AVPlayerItem` back to an index in `self.tracks`.
  ///
  /// Lookup is keyed on the lib-generated `queueItemId`
  /// associated-object set by `AVQueueBuilder.makeItem` at
  /// construction. `self.queueItemIds` is a parallel `[String]`
  /// array maintained 1:1 with `self.tracks` at every queue
  /// mutation site; `firstIndex(of:)` resolves the AVPlayerItem's
  /// `queueItemId` to its position in O(N) — N is queue size,
  /// typical < 100 tracks, no memoization warranted.
  ///
  /// queueItemId is unique per construction, so each AVPlayerItem
  /// maps to exactly one queue position even when the consumer
  /// supplies the same `track.url` at multiple positions.
  ///
  /// Returns nil when `item` is nil, when the item lacks a
  /// queueItemId (e.g. constructed outside `AVQueueBuilder` —
  /// shouldn't happen in production), or when the queueItemId
  /// isn't in `self.queueItemIds` (post-mutation desync — also
  /// shouldn't happen, but defensive). The caller leaves
  /// `currentTrackIndex` unchanged on a nil result.
  internal func matchTrackIndex(forCurrentItem item: AVPlayerItem?) -> Int? {
    guard let id = item?.queueItemId else { return nil }
    return self.queueItemIds.firstIndex(of: id)
  }

  /// Translate a queue position into the active engine's own index space by
  /// item identity. An engine indexes within the slice it was handed, so a
  /// queue position is only usable after this lookup.
  ///
  /// `nil` means the track is not materialised in the engine right now. That
  /// is a rebuild signal, not an error — the caller reinstalls the queue at
  /// the wanted position rather than issuing a seek the engine would reject.
  internal func engineIndex(forQueuePosition position: Int) -> Int? {
    guard let engine = self.engine,
          position >= 0, position < self.queueItemIds.count else { return nil }
    let id = self.queueItemIds[position]
    return engine.allMediaItems.firstIndex { $0.queueItemId == id }
  }

  /// Compute "seconds buffered ahead of position" — consumers use
  /// this for the "buffered track" overlay on a progress bar. Takes
  /// the end of the last loaded `CMTimeRange`, subtracts the current
  /// position, and clamps to zero. Returns 0 when no ranges are
  /// loaded or when the subtraction is non-finite.
  private func loadedAheadSeconds(
    for item: AVPlayerItem, currentPosition: Double
  ) -> Double {
    guard let last = item.loadedTimeRanges.last?.timeRangeValue else { return 0 }
    let end = CMTimeGetSeconds(CMTimeAdd(last.start, last.duration))
    guard end.isFinite, currentPosition.isFinite else { return 0 }
    return max(0, end - currentPosition)
  }

  /// Arm stall recovery. AVPlayer reports the underrun and then does nothing:
  /// with `automaticallyWaitsToMinimizeStalling` off it will not resume when
  /// the buffer refills, so `recomputeBufferState` re-issues play once it does.
  ///
  /// Gated on transport intent — a stall that arrives while the user is paused
  /// must not arm a resume that fires later and starts audio they did not ask
  /// for.
  @objc internal func handlePlaybackStalled(_ notification: Notification) {
    // The observer is registered for every item in the process; only the
    // item being heard can stall playback. A crossfade standby leg stalling
    // in preroll, or another player's item, must not arm a resume.
    let stalled = notification.object as? AVPlayerItem
    playerQueue.async {
      guard self.wantsToPlay, let stalled, stalled === self.activeMediaItem else { return }
      self.isRecoveringFromStall = true
      // The buffer can refill before this notification is delivered. The
      // keep-up flag only reports on change, so waiting for the next edge
      // would wait forever — recompute now and let it settle the resume.
      self.recomputeBufferState()
    }
  }

  /// Recompute the current buffer state from the active engine item, advance
  /// the per-track buffered high-water mark, and fire `onBufferStateChange`
  /// when the discrete state actually changes. Runs on the buffer KVO, each
  /// progress tick, the transport methods, and at track change.
  ///
  /// `.full` = keeps up (`playbackLikelyToKeepUp` / `playbackBufferFull`).
  /// A not-full loaded item is `.stalled` when playback had started and is
  /// intended (a mid-play rebuffer) or `.buffering` otherwise (initial load /
  /// loading while paused). `.empty` means nothing is loaded to play.
  internal func recomputeBufferState() {
    let item = self.engine?.currentMediaItem
    // A newly seated item announces its buffer state fresh. Drop the emitted
    // baseline when the active item's queueItemId changes so the first recompute
    // for the new item emits even if it lands on the same discrete state the
    // previous item ended on — a `.full` queue reloaded to another `.full`
    // local track otherwise catches no intermediate sample and emits nothing.
    let itemId = item?.queueItemId
    if itemId != self.lastBufferStateQueueItemId {
      self.lastBufferStateQueueItemId = itemId
      self.lastEmittedBufferState = nil
    }
    let computed: BufferState
    if let item {
      if item.isPlaybackLikelyToKeepUp || item.isPlaybackBufferFull {
        computed = .full
      } else {
        computed = (self.hasStartedPlaying && self.wantsToPlay) ? .stalled : .buffering
      }
    } else {
      computed = .empty
    }
    self.currentBufferState = computed
    // The buffer has recovered and the user still wants playback: nothing else
    // will restart it. Not while the session is interrupted — the refill can
    // land mid-call, and the interruption's `.ended` is what resumes then.
    // Not while the queue is stopped on a reported failure either: the player
    // has already moved onto the track behind it, and a refill is not the
    // listener asking for that track.
    if self.isRecoveringFromStall, computed == .full, self.wantsToPlay,
       !self.isInterrupted, self.reportedFailureOnCurrentTrack == nil {
      self.isRecoveringFromStall = false
      self.beginPlayback()
    }
    guard computed != self.lastEmittedBufferState else { return }
    self.lastEmittedBufferState = computed
    bufferStateListeners.forEach { $0(computed) }
  }

  /// The whole track has downloaded — the loaded range has reached the track's
  /// duration. When the player can't report a duration (e.g. a transcoded
  /// stream) it falls back to the consumer-supplied `track.duration`, the same
  /// fallback milestones use. A live / indefinite source never completes.
  /// (`isPlaybackBufferFull` is deliberately NOT used — it means the fixed
  /// buffer is full, not that the whole track has downloaded.)
  private func computeFullyBuffered() -> Bool {
    guard let item = self.engine?.currentMediaItem else { return false }
    if item.duration.isIndefinite { return false }
    // A local file or a fully-cached source is entirely on disk — fully
    // available regardless of the playback buffer.
    if self.currentTrackSource == .local || self.currentTrackSource == .cached { return true }
    var dur = CMTimeGetSeconds(item.duration)
    if !dur.isFinite || dur <= 0 {
      let idx = self.currentTrackIndex
      guard idx >= 0, idx < self.tracks.count, let d = self.tracks[idx].duration, d > 0 else {
        return false
      }
      dur = d
    }
    guard let last = item.loadedTimeRanges.last?.timeRangeValue else { return false }
    let end = CMTimeGetSeconds(CMTimeAdd(last.start, last.duration))
    return end.isFinite && end >= dur - Self.fullyBufferedEpsilonSeconds
  }

  /// Recompute fully-buffered and fire `onFullyBufferedChange` on a change.
  /// Runs on the buffer-full KVO, the progress tick (the loaded range grows),
  /// and at track change.
  internal func recomputeFullyBuffered() {
    let computed = computeFullyBuffered()
    self.currentFullyBuffered = computed
    guard computed != self.lastEmittedFullyBuffered else { return }
    self.lastEmittedFullyBuffered = computed
    fullyBufferedListeners.forEach { $0(computed) }
  }

  /// Cap `target` at the current buffered extent when `clampSeekToBuffered`
  /// is enabled and the target is past what has buffered. A local or fully-
  /// buffered track reports `buffered >= duration >= target`, so nothing is
  /// clamped and a seek reaches the end freely; a partly-buffered stream caps
  /// at the downloaded edge. Reads the live buffered position (not a tracked
  /// mark) so a seek-back that evicted the ahead-buffer clamps to what's there.
  internal func clampToBufferedIfEnabled(_ target: Double) -> Double {
    guard self.config.clampSeekToBuffered == true else { return target }
    guard let buffered = self.engine?.bufferedPositionSeconds, buffered.isFinite, buffered > 0 else {
      return target
    }
    return target > buffered ? buffered : target
  }

  /// Translate `AVPlayer.timeControlStatus` into our `PlayerState`
  /// enum and fire `onStateChange` when the value actually changed.
  /// Consumes `pendingStateChangeReason` (set by transport methods
  /// before they call `play`/`pause`/`stop`, or by the AudioSession
  /// handlers for interruption/route-change events) so user-
  /// initiated transitions are labelled correctly while purely
  /// system-internal ones default to `.system`.
  ///
  /// `.buffering` is a system-internal intermediate state — it
  /// shows up between `.paused` → `.playing` while AVPlayer fills
  /// its buffer. We deliberately do NOT consume the pending reason
  /// for `.buffering` so the reason survives to the terminal
  /// `.playing` emit. Otherwise a play() after interruption would
  /// label buffering `.interruption` and the actual `.playing` as
  /// `.system`, which consumers won't expect.
  internal func emitStateChangeIfChanged() {
    guard let engine = self.engine else { return }
    let computed = PlayerStateDerivation.translate(
      status: engine.timeControlStatus,
      currentIndex: self.currentTrackIndex,
      hasCurrentItem: engine.currentMediaItem != nil,
      reachedQueueEnd: self.reachedQueueEnd
    )
    emitState(computed, reason: reasonForComputedState(computed))
  }

  /// Reason for a derived `PlayerState`, shared by the gapless KVO
  /// (`emitStateChangeIfChanged`) and the crossfade delegate
  /// (`engineStateMaybeChanged`) so both label states identically.
  /// `.buffering` is intermediate — pass through as `.system` and leave the
  /// pending reason for the terminal emit. `.ended` is always end-of-queue —
  /// stamp `.queueEnd` to match `detectQueueEnd` / `enginePlaybackEnded`.
  internal func reasonForComputedState(_ computed: PlayerState) -> StateChangeReason {
    if computed == .buffering {
      return .system
    }
    if computed == .ended {
      self.pendingStateChangeReason = nil
      return .queueEnd
    }
    let reason = self.pendingStateChangeReason ?? .system
    self.pendingStateChangeReason = nil
    return reason
  }

  /// Bypass the timeControlStatus translation and emit a specific
  /// state directly (used for `.ended` / `.error` which AVPlayer
  /// doesn't model as timeControlStatus).
  internal func emitState(_ state: PlayerState, reason: StateChangeReason) {
    // Playback (re)starting clears `reachedQueueEnd` — do this BEFORE the
    // dedup guard so a redundant `.playing` re-emit still clears it.
    if state == .playing {
      self.reachedQueueEnd = false
      // Playing again means a later routed failure here is a new one.
      if let qid = self.queueItemIds[safe: self.currentTrackIndex] {
        self.routedRecoveryAttemptedQueueItemIds.remove(qid)
      }
    }
    guard state != self.lastReportedState else { return }
    DiagnosticLog.player.log(
      "state \(self.lastReportedState.stringValue) -> \(state.stringValue) reason=\(reason.stringValue)")
    self.lastReportedState = state
    // Once the track actually plays, a later not-full buffer is a mid-playback
    // `stalled`, not the initial `buffering` load.
    if state == .playing { self.hasStartedPlaying = true }
    self.stateChangeListeners.forEach { $0(state, reason) }
    self.nowPlayingInfo.refreshPositionAndRate(self.nowPlayingSnapshot(), trigger: .stateChange)
  }

  /// Internal entry point for `CastEventBridge` to fan receiver-driven
  /// state into the JS-facing event surface. Goes through the same
  /// `emitState` dedup chokepoint as local-engine transitions, so JS
  /// sees one event per real receiver transition regardless of source.
  func emitRemoteState(_ state: PlayerState, reason: StateChangeReason) {
    // Arrives on main from the Cast SDK's listeners; `emitState` writes the
    // reported-state fields that the local path owns.
    playerQueue.async { self.emitState(state, reason: reason) }
  }

  /// Fan a cast receiver's position update to JS `onProgress` so the
  /// consumer's player screen (progress bar / position / duration) tracks
  /// the receiver while a remote session is active. The receiver reports
  /// milliseconds; `PlayerProgress` is in seconds. Buffered arrives as an
  /// absolute receiver position, so it's reduced to "ahead of position"
  /// to match the local path's semantics. The local now-playing / format
  /// path is untouched — the local engine is silent during cast.
  func emitRemoteProgress(positionMs: Int64, durationMs: Int64, bufferedMs: Int64) {
    // Arrives on main from the Cast SDK; the throttle it shares with the local
    // tick is player state.
    playerQueue.async {
      self.emitRemoteProgressOnQueue(
        positionMs: positionMs, durationMs: durationMs, bufferedMs: bufferedMs)
    }
  }

  private func emitRemoteProgressOnQueue(
    positionMs: Int64, durationMs: Int64, bufferedMs: Int64
  ) {
    guard !progressListeners.isEmpty,
          PlaybackStateRouter.shared.activeRemote()?.lastState == .playing else { return }
    // Same throttle as the local tick, shared instance — a consumer's
    // configured cadence caps cast progress too (the receiver pushes at its own
    // rate, so the effective cadence is min(receiver rate, configured interval)).
    guard self.progressEmissionGate.shouldEmit(nowMs: self.monotonicNowMs()) else { return }
    let progress = remoteProgressSnapshot(
      positionMs: positionMs, durationMs: durationMs, bufferedMs: bufferedMs)
    progressListeners.forEach { $0(progress) }
  }

  internal func remoteProgressSnapshot(
    positionMs: Int64, durationMs: Int64, bufferedMs: Int64
  ) -> PlayerProgress {
    let position = TimeInterval(max(0, positionMs)) / 1000.0
    let duration = TimeInterval(max(0, durationMs)) / 1000.0
    let buffered = max(0, TimeInterval(bufferedMs) - TimeInterval(positionMs)) / 1000.0
    return PlayerProgress(position: position, duration: duration, buffered: buffered)
  }

  /// Fan a cast receiver's track advance to JS `onTrackChange` and update
  /// the authoritative `currentTrackIndex` so the consumer's player screen
  /// follows the receiver's current track. Mirrors the local dispatch's
  /// listener fire without the local-engine now-playing refresh (the cast
  /// lockscreen is driven separately via `CastNowPlayingController`).
  /// Called on the main thread from `CastEventBridge`, like `emitRemoteState`.
  func emitRemoteTrackChange(index: Int) {
    // Arrives on main from the Cast SDK; writes `currentTrackIndex` and reads
    // the track list.
    playerQueue.async { self.emitRemoteTrackChangeOnQueue(index: index) }
  }

  private func emitRemoteTrackChangeOnQueue(index: Int) {
    guard index >= 0, index < self.tracks.count else { return }
    self.currentTrackIndex = index
    let track = self.tracks[index]
    _ = self.pauseForVideoTrack()
    // No engine call happens on this path, so nothing downstream reaches
    // `dispatchTrackChange`; do what it does for the source here.
    self.reclassifyCurrentTrackSource()
    self.trackChangeListeners.forEach { $0(track, Double(index), .autoAdvance, nil) }
    // `currentTrackIndex` just moved — recompute skip capability so the
    // receiver's advance updates canSkipPrevious/Next (skip-back enables
    // once off the first track), same as every local index write.
    self.recomputeCapabilities()
  }

  /// Hand off current local playback to a newly-connected cast receiver:
  /// load the current queue at the current playhead with the current
  /// play/pause, so playback continues where it was. The local engine is
  /// silenced immediately (after the state read) so the device speaker
  /// never overlaps the receiver. Async — the receiver load is a
  /// round-trip; a failed load leaves local paused (never blasts audio).
  func handoffCurrentPlaybackToCast() {
    // Arrives on main from the Cast SDK. The capture and the local pause are
    // player state, so they run on the queue that owns it — asynchronously,
    // because a synchronous wait here parks main behind whatever the queue is
    // already doing, up to and including a full queue rebuild.
    playerQueue.async { [self] in
      let snapshot = self.buildHandoffSnapshot()
      guard let snapshot else { return }
      self.castEventBridge.seedDeactivationSnapshot(
        trackIndex: snapshot.startIndex,
        positionMs: snapshot.positionMs
      )
      // Chained like every other receiver mirror. This load is absolute, so a
      // delta mirror for a mutation issued just after activation must not
      // complete before it — the load would discard that mutation and leave the
      // receiver permanently diverged from the local queue.
      let previous = self.castMirrorChain
      self.castMirrorChain = Task { [weak self] in
        await previous?.value
        guard let self = self else { return }
        let ids = try? await CastTransportRouter.routeSetQueue(
          tracks: snapshot.items,
          startIndex: snapshot.startIndex,
          startPositionMs: snapshot.positionMs,
          playWhenReady: snapshot.playing
        )
        // A non-nil result means the receiver load succeeded; seed the start
        // track's lockscreen metadata. The current track is then tracked via
        // the receiver's absolute queue index, not these itemIds.
        if ids != nil {
          await self.onPlayerQueue {
            self.castEventBridge.seedStartMetadata(startIndex: snapshot.startIndex)
          }
        }
      }
    }
  }

  /// Capture the state a receiver handoff replays, and silence the local
  /// engine so the device speaker never overlaps the receiver. Caller must be
  /// on `playerQueue`.
  private func buildHandoffSnapshot() -> HandoffSnapshot? {
    let tracks = self.tracks
    let idx = self.currentTrackIndex
    let positionMs = Int64(max(0, (self.engine?.currentPositionSeconds ?? 0)) * 1000.0)
    // The transport's intent, not the leg's audible state: a leg that is
    // buffering, or mid-crossfade with its outgoing item finished, reads as
    // not-playing while the consumer is still playing — and the receiver would
    // start paused.
    let playing = self.wantsToPlay
    self.engine?.pause()
    guard idx >= 0, idx < tracks.count else { return nil }
    let items = tracks.compactMap { CastMediaItem.from(track: $0) }
    guard items.count == tracks.count else { return nil }
    return HandoffSnapshot(
      items: items, startIndex: idx, positionMs: positionMs, playing: playing
    )
  }

  private struct HandoffSnapshot {
    let items: [CastMediaItem]
    let startIndex: Int
    let positionMs: Int64
    let playing: Bool
  }

  /// Return local playback to the receiver's last track + position after
  /// a cast session ends. The local engine kept the mirrored queue
  /// (paused) during cast, so this seeks it to the handback point and
  /// stays PAUSED — the user resumes locally. Never auto-blasts audio out
  /// of the phone on disconnect. A `-1` trackIndex (receiver index never
  /// resolved) is a no-op; local stays where it was.
  func resumeLocalAfterCast(trackIndex: Int, positionMs: Int64) {
    // Arrives on main from the Cast SDK; the rebuild below is player state and
    // must not be waited on from main.
    playerQueue.async {
      guard trackIndex >= 0, trackIndex < self.tracks.count else { return }
      let positionSec = max(0, Double(positionMs) / 1000.0)
      self.currentTrackIndex = trackIndex
      // Seated mid-track from the receiver's position: not over, whatever the
      // local queue had reached before the handoff.
      self.reachedQueueEnd = false
      // Rebuild the local engine from the resumed absolute index. The
      // engine was frozen at the cast-start index for the whole cast
      // session, so its queue no longer matches `tracks[trackIndex...]`;
      // a raw `engine.seek(toIndex: trackIndex)` would index the
      // GaplessEngine's remaining-queue space (not the lib's absolute
      // index) and seat the wrong item, leaving a later skipToIndex /
      // play desynced from `currentTrackIndex`. The rebuild re-seats the
      // current item so transport stays consistent.
      self.performingMutation { self.fullRebuildPlayerQueue() }
      // Seek within the freshly-seated current item to the receiver's last
      // position. Resolved by identity: the installed run reaches behind the
      // playhead on the crossfade engine, so engine-index 0 is a track already
      // played, not the current one. No seek at all beats a seek to the wrong
      // track, so an unresolvable position leaves it where the rebuild seated
      // it.
      if positionSec > 0,
         let engineIdx = self.engineIndex(forQueuePosition: trackIndex) {
        self.engine?.seek(toIndex: engineIdx, position: positionSec)
      }
      // Stay paused — the user resumes locally; never auto-blast the
      // phone speaker on revert.
      self.engine?.pause()
      self.pendingStateChangeReason = self.pendingStateChangeReason ?? .user
      self.rescheduleLookahead()
      self.recomputeCapabilities()
    }
  }

  /// Real end-of-track signal. AVPlayerItemDidPlayToEndTime fires
  /// when an item finishes playback naturally — NOT when
  /// AVQueuePlayer chain-advances through unbuffered items during a
  /// rebuild. The lib uses this distinction to:
  ///
  /// - Natural auto-advance: notification fires → set
  ///   `didPlayToEndPending` → `handleCurrentItemDidChange` runs
  ///   `matchTrackIndex` resync → `currentTrackIndex` updates to
  ///   the auto-advanced position.
  /// - Chain-advance bounce: notification does NOT fire (items
  ///   weren't actually playing). `\.currentItem` KVO still fires
  ///   from the rebuild's insert sequence, but
  ///   `handleCurrentItemDidChange`'s gate sees
  ///   `didPlayToEndPending == false` and skips the resync —
  ///   `currentTrackIndex` stays at whatever the user mutation
  ///   pre-wrote.
  ///
  /// Repeat-track handling: when `repeatModeState == .track`, seek
  /// the just-finished item back to zero + replay. The gapless engine
  /// holds the item at its end (`AVQueuePlayer.actionAtItemEnd = .none`
  /// under `.track`, set in `GaplessEngine.setRepeatMode`), so the
  /// player does not advance and `player.currentItem` is still the
  /// finished item when this async block runs — the rewind is
  /// deterministic, and the seek-to-zero produces no `\.currentItem`
  /// change to gate. `didPlayToEndPending` is armed in this branch only
  /// when the player has already advanced past the finished item.
  @objc internal func handlePlayerItemDidPlayToEndTime(_ notification: Notification) {
    // Capture the repeat mode as the item ends, before the hop to the owner
    // queue. `setRepeatMode` writes `actionAtItemEnd`, so a toggle landing
    // inside that hop would have the branch below decide against a mode that
    // was not in force when the player acted on the end of the track. This
    // runs on AVFoundation's thread; the read is lock-guarded for it.
    let repeatModeAtEnd = self.repeatModeState
    playerQueue.async { [weak self] in
      guard let self else { return }
      // End-of-track sleep timer: pause at the current track's real end —
      // before the repeat-track rewind or the auto-advance — so the pause
      // lands exactly at the boundary and fires even when the track duration
      // was never known. Under repeat-one this pauses at the first natural end.
      let sleepFired = self.sleepTimerCore.fireAtTrackEnd()
      if sleepFired.pauseNow { self.applySleepTimerResult(sleepFired) }
      // Branch on repeat-track FIRST, separately from the
      // currentItem === item check.
      if repeatModeAtEnd == .track {
        // Repeat-track: rewind the just-finished item + replay. The
        // gapless engine holds the item via `actionAtItemEnd = .none`
        // under `.track`, so the player does not advance and
        // `currentItem` is still the finished item here.
        if let player = self.player {
          // Repeat-one loop = a new milestone playthrough of the same track.
          // The gapless engine fires no track-change / delegate here, so
          // dispatchTrackChange's reset is skipped — reset directly, and
          // UNCONDITIONALLY for the gapless engine (self.player non-nil), so
          // milestones re-arm for the new playthrough. (CrossfadeEngine has
          // self.player == nil here and resets via engineDidPlayItemToEnd
          // instead — no double reset.)
          self.milestoneTracker.reset()
          if let item = notification.object as? AVPlayerItem,
             player.currentItem === item {
            item.seek(to: .zero, completionHandler: nil)
            // Replay through the engine so the user's playback speed is
            // restored (a raw AVPlayer.play() resets rate to 1.0) — unless the
            // end-of-track sleep timer just fired, in which case we pause at
            // this natural end instead of looping.
            if !sleepFired.pauseNow { self.engine?.play() }
          } else {
            // The finished item is no longer current, so the player advanced
            // before repeat-track took hold — the mode was toggled as the
            // track ended. There is a real move to reconcile, so arm the
            // resync the `\.currentItem` KVO consumes; without it the index
            // keeps naming the previous track, and `dispatchTrackChange`
            // dedups against the stale id so nothing is announced at all.
            self.didPlayToEndPending = true
          }
        }
        return
      }
      // Repeat-off / repeat-queue: arm the resync gate so
      // `handleCurrentItemDidChange` will run `matchTrackIndex`
      // when the `\.currentItem` KVO fires for the next item.
      self.didPlayToEndPending = true
      let now = DispatchTime.now().uptimeNanoseconds
      self.gaplessGapPrevEndedAtNs = now
      #if DEBUG
      self.gaplessLogLastEndedAtNs = now
      NSLog("[RNQP-GAPLESS] item-ended trackIdx=%d ts_ns=%llu",
            self.currentTrackIndex, now)
      #endif
    }
  }

  /// New `AVPlayerItem` access-log entries arrive multiple times per
  /// second during streaming (per network transfer chunk). Two
  /// concerns share the handler: the bitrate fallback (re-resolves the
  /// now-playing format while the last emitted one has no bitrate) and
  /// DEBUG-only timeline logging.
  ///
  /// Once a bitrate lands in `lastEmittedFormat`, later fires for the same
  /// item do not re-resolve. The active-item gate (`item ===
  /// self.activeMediaItem`) drops fires from items the engine has already
  /// advanced past; `reresolveNowPlayingFormatForBitrate` bounds the
  /// attempts per item and checks its `nowPlayingFormatRequestGen` token
  /// so a stale resolve cannot overwrite a newer item's format.
  @objc internal func handleNewAccessLogEntry(_ notification: Notification) {
    playerQueue.async { [weak self] in
      guard let self,
            let item = notification.object as? AVPlayerItem else { return }
      // Not gated on codec. The access log reports whatever the player
      // negotiated, and a format that resolved without a bitrate is worth
      // re-reading whatever it is — MP3 is neither the only nor the usual
      // codec a consumer streams.
      if item === self.activeMediaItem,
         self.lastEmittedFormatItemId == ObjectIdentifier(item),
         let format = self.lastEmittedFormat {
        let hasBitrate: Bool = {
          guard let variant = format.bitrate,
                case .second = variant else { return false }
          return true
        }()
        if !hasBitrate {
          self.reresolveNowPlayingFormatForBitrate()
        }
      }
      #if DEBUG
      guard let event = item.accessLog()?.events.last else { return }
      let now = DispatchTime.now().uptimeNanoseconds
      let deltaSinceChangedMs = self.gaplessLogLastChangedAtNs > 0
        ? (now &- self.gaplessLogLastChangedAtNs) / 1_000_000 : 0
      NSLog("[RNQP-GAPLESS] access-log trackIdx=%d stalls=%ld observedBitrate=%lld transferDurationMs=%d numberOfMediaRequests=%ld delta_since_changed_ms=%llu",
            self.currentTrackIndex,
            event.numberOfStalls,
            Int64(event.observedBitrate),
            Int(event.transferDuration * 1000),
            event.numberOfMediaRequests,
            deltaSinceChangedMs)
      #endif
    }
  }

  #if DEBUG
  /// Error-log entries surface alongside the existing
  /// `AVPlayerItemFailedToPlayToEndTime` notification for a richer
  /// diagnostic record. Compiled out in Release builds.
  @objc internal func handleNewErrorLogEntry(_ notification: Notification) {
    playerQueue.async { [weak self] in
      guard let self,
            let item = notification.object as? AVPlayerItem,
            let log = item.errorLog(),
            let event = log.events.last else { return }
      NSLog("[RNQP-GAPLESS] error-log trackIdx=%d errorDomain=%@ errorCode=%ld errorComment=%@",
            self.currentTrackIndex,
            event.errorDomain,
            event.errorStatusCode,
            event.errorComment ?? "nil")
    }
  }
  #endif

  @objc internal func handlePlayerItemFailedToPlayToEndTime(_ notification: Notification) {
    playerQueue.async { [weak self] in
      guard let self else { return }
      let underlying = notification.userInfo?[
        AVPlayerItemFailedToPlayToEndTimeErrorKey] as? NSError
      // Mid-play failures share the same dedup + retry path as
      // load failures — both routed through `dispatchErrorOrRetry`.
      // Real-world repro showed 4× identical AVFoundation "Cannot
      // Open" fires from this notification firing repeatedly when
      // the player is in a wedged state; the dedup gate suppresses
      // them, the retry path attempts recovery before surfacing.
      let item = notification.object as? AVPlayerItem ?? self.player?.currentItem
      self.dispatchErrorOrRetry(item: item, underlying: underlying)
    }
  }

  /// Construct a richly-populated `PlaybackError` from an underlying
  /// `NSError` + the already-mapped standardized code. Centralised so
  /// every iOS error fire site produces the same shape (sanitised
  /// `nativeMessage`, consistent `fatal` classifier, etc.). Mirrors
  /// `PlaybackErrorMapping.kt#buildPlaybackError` on Android.
  ///
  /// `nativeDomainOverride` replaces the underlying `NSError.domain`
  /// on the emitted error — used by lib-defined error categories
  /// (`PLAYER_ITEM_LOAD_FAILED`, `AUDIO_SESSION_*`,
  /// `AUDIO_SESSION_INTERRUPTION_*`) that need a stable
  /// SCREAMING_SNAKE_CASE marker for JS branching. Nil keeps the raw
  /// underlying domain for callers that prefer the native string.
  internal func buildPlaybackError(
    _ underlying: NSError?,
    mapped: PlaybackErrorCode,
    queueItemId: String = "",
    url: String = "",
    nativeDomainOverride: String? = nil
  ) -> PlaybackError {
    let nativeCode = underlying?.code ?? 0
    let nativeDomain = nativeDomainOverride ?? (underlying?.domain ?? "")
    let nativeMessage = PlaybackErrorMapping.stripQuery(
      underlying?.localizedDescription ?? ""
    )
    return PlaybackError(
      code: mapped,
      message: PlaybackErrorMapping.messageFor(mapped),
      fatal: !PlaybackErrorMapping.isTransient(mapped),
      nativeCode: Double(nativeCode),
      nativeDomain: nativeDomain,
      nativeMessage: nativeMessage,
      queueItemId: queueItemId,
      url: url
    )
  }
}
