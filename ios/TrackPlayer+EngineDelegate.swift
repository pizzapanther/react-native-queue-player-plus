import AVFoundation
import Foundation

// MARK: - PlaybackEngineDelegate

/// `CrossfadeEngine` fires these callbacks; `GaplessEngine`'s
/// equivalent surface continues to flow through `self.player`'s
/// KVO chain installed by `installGaplessObservers` /
/// `installEventObservers`. Each delegate method therefore runs
/// only when the active engine is `CrossfadeEngine` (per the
/// `delegate = self` wiring in `applyEngineSwapForMode`).
extension TrackPlayer: PlaybackEngineDelegate {
  func engineStateMaybeChanged(
    _ engine: PlaybackEngine, reason: StateChangeReason?
  ) {
    if let reason {
      // Engine-supplied classified reason takes precedence over
      // any prior staked reason — match the gapless KVO path
      // where pendingStateChangeReason is overwritten by the
      // newer staked reason.
      self.pendingStateChangeReason = reason
    }
    // The leading leg's `timeControlStatus` drives `.buffering` (load /
    // rebuffer) and `reachedQueueEnd` drives `.ended`.
    let computed = PlayerStateDerivation.translate(
      status: engine.timeControlStatus,
      currentIndex: self.currentTrackIndex,
      hasCurrentItem: engine.currentMediaItem != nil,
      reachedQueueEnd: self.reachedQueueEnd
    )
    emitState(computed, reason: reasonForComputedState(computed))
  }

  func engineDidTransitionTrack(_ engine: PlaybackEngine) {
    // Bring TrackPlayer's currentTrackIndex into sync with the
    // engine's index — CrossfadeEngine.completeCrossfade
    // increments engine.currentMediaItemIndex; on setItems / seek
    // the engine's index is already aligned with the caller's
    // resumeIndex.
    //
    // Crossfade post-swap echo: fade-start already fired
    // `engineCrossfadeDidBegin` which dispatched the JS-facing
    // track-change for the same incoming index. The
    // `lastEmittedQueueItemId` dedup inside `dispatchTrackChange`
    // turns the second call into a no-op. Capability + lookahead
    // refresh still run unconditionally — both are idempotent and
    // the second pass picks up any state that landed during the
    // fade window.
    // Resolve the engine's active item to a queue position by identity.
    // An engine's own index counts within whatever slice it was handed, so
    // it is not a queue position; `queueItemId` is, and it survives the
    // item-replacement paths (cross-attach, cached-URL upgrade).
    //
    // Still skipped during a queue mutation: the mutation already computed
    // the authoritative `currentTrackIndex` via QueueMutationArithmetic, and
    // the engine is mid-rebuild. (CrossfadeEngine.setItems fires this
    // delegate synchronously; GaplessEngine does not.)
    if !self.isMutatingPlayerQueue,
       let resolved = self.matchTrackIndex(forCurrentItem: engine.currentMediaItem) {
      self.currentTrackIndex = resolved
    }
    let reason = self.pendingTrackChangeReason ?? .autoAdvance
    self.pendingTrackChangeReason = nil
    dispatchTrackChange(reason: reason)
    self.recomputeCapabilities()
    self.rescheduleLookahead()
    // Extend the installed run as the playhead advances into it. Driven from
    // the transition rather than from arming, which would force the build into
    // the moment immediately before the ramp is scheduled.
    //
    // Not while a mutation is in flight: this delegate is fired synchronously
    // from `CrossfadeEngine.setItems`, so topping up there would re-enter
    // `setItems` from inside itself — and the mutation that raised the flag
    // installs the run it wants anyway.
    if !self.isMutatingPlayerQueue {
      self.topUpWindow()
    }
    // Refresh the now-playing format for the new active item. The crossfade
    // engine's `\.tracks` KVO is `.new`-only and a forward skip rebuilds the
    // incoming leg as a fresh AVPlayerItem whose tracks key isn't auto-loaded
    // (only playable/duration are), so engineActiveItemFormatChanged may never
    // fire for it — without this, getNowPlayingFormat() stays nil after a
    // skip to a cached track. The extractor loads the format asynchronously
    // and the lastEmittedFormatItemId dedup makes a redundant call a no-op.
    self.refreshNowPlayingFormatForActiveItem()
  }

  func engineCrossfadeDidBegin(_ engine: PlaybackEngine) {
    // Fade-start: the standby leg is now audibly playing (volume 0
    // ramping up). Flip JS-facing active track + lock-screen / Now
    // Playing surface to the incoming track immediately so the
    // user sees the new track's title/artwork the moment they
    // start hearing it. The leading leg continues fading out for
    // the configured `crossfadeDurationMs`; engine accessors
    // already prefer the standby leg while `fadeInFlight` is true.
    //
    // Reason is hardcoded `.autoAdvance` — fade-start is always an
    // auto-advance event; any user-driven transition (skipToNext /
    // skipToIndex / setMediaItems) would have called
    // `cancelFadeInternal` first, so a stashed
    // `pendingTrackChangeReason` here is stale and should not
    // re-attribute the fade.
    // A queue mutation already computed the authoritative index; the
    // rebuild's own fade-cancel must not walk it back to the outgoing leg.
    guard !self.isMutatingPlayerQueue,
          let incoming = self.matchTrackIndex(forCurrentItem: engine.currentMediaItem)
    else { return }
    self.currentTrackIndex = incoming
    self.pendingTrackChangeReason = nil
    dispatchTrackChange(reason: .autoAdvance)
    self.recomputeCapabilities()
    self.rescheduleLookahead()
  }

  func engineCrossfadeDidCancel(_ engine: PlaybackEngine) {
    // Fade-cancelled mid-flight (pause / stop / focus loss /
    // queue mutation). JS already received `onTrackChange(incoming,
    // reason: .autoAdvance)` from the prior fade-start fire; the
    // incoming track is no longer the active one. Revert
    // `currentTrackIndex` to the leading-leg index and emit
    // `onTrackChange(leading, reason: .crossfadeCancelled)` so
    // consumer UI can rewind to the leading track.
    // See `engineCrossfadeDidBegin` — a rebuild issued by a queue mutation
    // cancels the in-flight fade, and that cancel must not clobber the
    // index the mutation just computed.
    guard !self.isMutatingPlayerQueue,
          let reverted = self.matchTrackIndex(forCurrentItem: engine.currentMediaItem)
    else { return }
    self.currentTrackIndex = reverted
    self.pendingTrackChangeReason = nil
    dispatchTrackChange(reason: .crossfadeCancelled)
    self.recomputeCapabilities()
    self.rescheduleLookahead()
  }

  /// Apply whatever was held back while the fade ran.
  ///
  /// Fired for every end of a fade, which is the point: a cancel taken during
  /// the arm window reverts nothing and so reports nothing else, and work
  /// waiting on that fade would otherwise wait for one that is already over —
  /// with the engine still holding a run the queue has retired.
  func engineFadeDidSettle(_ engine: PlaybackEngine) {
    _ = engine
    self.flushDeferredEngineWork()
  }

  func engineBufferStateMaybeChanged(_ engine: PlaybackEngine) {
    _ = engine
    // Buffer surface only — no direct player-state emission. The crossfade
    // leading leg's buffer-flag KVO lands here; recompute the discrete
    // buffer-state and fully-buffered surfaces, exactly as the gapless item KVO
    // does through `installBufferObservers` (the stall-recovery restart inside
    // `recomputeBufferState` still applies here, as on gapless). Already on
    // `playerQueue` (the engine's ownerQueue), so recompute synchronously — a
    // second async hop would reorder against the recompute's change dedup.
    self.recomputeBufferState()
    self.recomputeFullyBuffered()
  }

  func engineActiveItemFormatChanged(_ engine: PlaybackEngine) {
    _ = engine
    self.refreshNowPlayingFormatForActiveItem()
    // Streaming-item audioMix catch-up for the crossfade engine
    // (which fires this delegate hook on its own KVO chain). The
    // gapless engine has a parallel catch-up path through
    // `dispatchItemStatus(.readyToPlay)`; both call into
    // `refreshActiveItemMixes`, which is idempotent on items that
    // already carry a tap, so a double-fire is harmless.
    AudioTapProvider.shared.refreshActiveItemMixes()
  }

  func engineDidFailWithError(
    _ engine: PlaybackEngine, error: Error, item: AVPlayerItem?
  ) {
    _ = engine
    let underlying = error as NSError
    // The item is forwarded, not dropped: without it `dispatchErrorOrRetry`
    // synthesises a key that cannot appear in `queueItemIds`, and the
    // automatic-retry branch requires a real `queueItemId`.
    dispatchErrorOrRetry(
      item: item,
      underlying: underlying,
      nativeDomainOverride: nil
    )
  }

  func engineDidPlayItemToEnd(_ engine: PlaybackEngine) {
    // CrossfadeEngine fires this on natural end-of-leading; the
    // boundary signal that drives JS-facing track-change is
    // engineDidTransitionTrack which fires immediately after.
    //
    // Under repeat-one the same track replays with no track-change (the
    // queueItemId is unchanged, so dispatchTrackChange's dedup skips its
    // reset). Each loop is a fresh playthrough, so re-arm the milestone
    // tracker here for that case only.
    if self.repeatModeState == .track {
      self.milestoneTracker.reset()
    }
    _ = engine
  }

  func enginePlaybackEnded(_ engine: PlaybackEngine) {
    _ = engine
    // The engine reports that it drained; what that means for the queue —
    // wrap, refill, or over — is decided above it, the same way for both
    // engines.
    applyEndVerdict()
  }
}
