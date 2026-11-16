import UIKit

extension TrackPlayer {
  // MARK: - Auto-retry + stuck-recovery

  /// Lib-clamped auto-retry budget. Reads from `config.autoRetries`,
  /// defaults to 1, clamps to [0, 5]. Called from `setQueue` /
  /// `addToQueueBody` to populate `retryAttemptsRemaining`.
  ///
  /// Non-finite (NaN / ±Infinity) inputs from JS would trap at
  /// `Int(Double.nan)` — Swift's Int initialiser preconditionFails
  /// rather than rounding to 0 like Kotlin. Mirror Android's
  /// `coerceIn` behaviour by gating on `isFinite` first.
  internal func effectiveAutoRetries() -> Int {
    guard let raw = self.config.autoRetries, raw.isFinite else { return 1 }
    // Clamp before converting: `Int(_:)` traps on a finite value too large for
    // `Int`, so the bound has to be applied in `Double` space.
    return Int(max(0, min(5, raw)))
  }

  /// Lib-clamped retry backoff in milliseconds. Reads from
  /// `config.retryBackoffMs`, defaults to 500, clamps to [200, 5000].
  /// Floor at 200ms prevents retry storms against misbehaving servers
  /// (e.g. 503 with `Retry-After: 0`). Same `isFinite` gate as
  /// `effectiveAutoRetries`.
  internal func effectiveRetryBackoffMs() -> Int {
    guard let raw = self.config.retryBackoffMs, raw.isFinite else { return 500 }
    return Int(max(200, min(5000, raw)))
  }

  // MARK: - Progress-emission throttle

  /// Monotonic clock in milliseconds for the progress-emission throttle.
  /// `systemUptime` is monotonic and needs no extra framework import.
  internal func monotonicNowMs() -> Double {
    ProcessInfo.processInfo.systemUptime * 1000
  }

  /// Foreground `onProgress` cadence in ms. Reads
  /// `config.progressUpdateIntervalMs`, defaults to 500, floors at the 500 ms
  /// native tick rate, and ignores non-finite / non-positive inputs.
  private func effectiveForegroundProgressIntervalMs() -> Double {
    guard let raw = self.config.progressUpdateIntervalMs, raw.isFinite, raw > 0 else { return 500 }
    return max(500, raw)
  }

  /// Background `onProgress` cadence in ms. Reads
  /// `config.backgroundProgressUpdateIntervalMs`, defaults to the effective
  /// foreground value (⇒ no background reduction), floors at 500, ignores
  /// non-finite / non-positive inputs.
  private func effectiveBackgroundProgressIntervalMs() -> Double {
    let foreground = effectiveForegroundProgressIntervalMs()
    guard let raw = self.config.backgroundProgressUpdateIntervalMs, raw.isFinite, raw > 0 else {
      return foreground
    }
    return max(500, raw)
  }

  /// Push the configured foreground + background cadences into the throttle.
  /// Called on every `configure()`; the gate preserves its runtime state.
  internal func applyProgressEmissionIntervals() {
    self.progressEmissionGate.setIntervals(
      foregroundMs: effectiveForegroundProgressIntervalMs(),
      backgroundMs: effectiveBackgroundProgressIntervalMs(),
      samplerPeriodMs: Self.progressIntervalSeconds * 1000)
  }

  /// Observe app background/foreground so the throttle can switch cadence.
  /// Idempotent — a no-op when already installed. Seeds the current state so a
  /// `configure()` issued while already backgrounded starts throttled.
  internal func installAppLifecycleObservers() {
    guard appLifecycleObservers.isEmpty else { return }
    // `applicationState` is a main-thread API and this runs on `playerQueue`:
    // read it on main and hand the value back. The seed lands a hop later,
    // so the first progress ticks of a configure issued in the background
    // may go out at the foreground cadence.
    DispatchQueue.main.async { [weak self] in
      let backgrounded = UIApplication.shared.applicationState == .background
      self?.playerQueue.async { self?.progressEmissionGate.setBackgrounded(backgrounded) }
    }
    let center = NotificationCenter.default
    appLifecycleObservers.append(
      center.addObserver(
        forName: UIApplication.didEnterBackgroundNotification,
        object: nil, queue: .main
      ) { [weak self] _ in
        DiagnosticLog.app.log("didEnterBackground")
        // The gate is player state; the notification arrives on main.
        self?.playerQueue.async { self?.progressEmissionGate.setBackgrounded(true) }
      })
    appLifecycleObservers.append(
      center.addObserver(
        forName: UIApplication.willEnterForegroundNotification,
        object: nil, queue: .main
      ) { [weak self] _ in
        DiagnosticLog.app.log("willEnterForeground")
        // The gate is player state; the notification arrives on main.
        self?.playerQueue.async {
          self?.progressEmissionGate.setBackgrounded(false)
          // A suspension may have cost the read-through listener its socket;
          // fix it before the user presses anything.
          self?.reviveReadThroughServerIfDead()
          self?.reinstallAfterReadThroughRestart()
        }
      })
  }

  /// Remove the app-lifecycle observers registered by
  /// `installAppLifecycleObservers`. Idempotent.
  internal func removeAppLifecycleObservers() {
    let center = NotificationCenter.default
    appLifecycleObservers.forEach { center.removeObserver($0) }
    appLifecycleObservers.removeAll()
  }

  /// The current position's `queueItemId` when its failure has been reported
  /// and not yet recovered; nil otherwise.
  ///
  /// The position matters because the queue only holds where it is for *this*
  /// track's failure. A report against a track the playhead has since left says
  /// nothing about whether the current one can play.
  ///
  /// Nil when the failing item carried no `queueItemId` — nothing can be
  /// correlated to a position then, so the queue behaves as it did before the
  /// failure. `AVQueueBuilder.makeItem` always sets one; raw items do not.
  internal var reportedFailureOnCurrentTrack: String? {
    guard let currentId = self.queueItemIds[safe: self.currentTrackIndex],
          self.reportedFailureQueueItemIds.contains(currentId) else { return nil }
    return currentId
  }

  /// Retry the current item when the network comes back after an outage.
  ///
  /// The common real-world case is a tunnel, a lift, or airplane mode: the
  /// retries burn while there is no connection, the item ends up failed, and
  /// nothing tries again — the user has to notice and press play. This runs
  /// recovery at the moment it can actually succeed.
  ///
  /// The guards are the whole design. Intent and interruption are checked as
  /// everywhere else, and the retry budget is refunded for **that one track
  /// only** rather than reset wholesale: a flapping connection would otherwise
  /// hand out unlimited retries and defeat the budget entirely.
  internal func handleNetworkRestored() {
    guard self.currentTrackIndex >= 0,
          self.currentTrackIndex < self.queueItemIds.count else { return }
    let qid = self.queueItemIds[self.currentTrackIndex]
    // The unrecovered-failure test is the error key naming *this* track, not
    // the last reported state: by the time a network comes back the announced
    // sequence has settled on `paused`, or on `ended` when the queue drained.
    // The key is cleared by every recovery path, so it is set only while this
    // track is still broken.
    guard NetworkRecoveryPolicy.shouldRetry(
      wantsToPlay: self.wantsToPlay,
      isInterrupted: self.isInterrupted,
      isInError: self.lastErrorQueueItemId == qid,
      isTransient: self.lastErrorCode.map(PlaybackErrorMapping.isTransient) ?? false
    ) else { return }
    if (self.retryAttemptsRemaining[qid] ?? 0) == 0 {
      self.retryAttemptsRemaining[qid] = 1
    }
    self.lastErrorQueueItemId = nil
    self.lastErrorCode = nil
    self.retryFailedItem(queueItemId: qid)
  }

  /// Try to recover from a transient failure by reinstalling the engine's
  /// items at the current position. Called from the transient auto-retry
  /// path in `dispatchErrorOrRetry` and from `handleNetworkRestored`.
  ///
  /// Implementation: `fullRebuildPlayerQueue(forcingReinstall: true)` with
  /// the current index unchanged. The force is load-bearing: a failed item
  /// keeps its `queueItemId`, so an identity comparison would find the
  /// slice already correct and reinstall nothing. Fresh AVPlayerItems are
  /// built through `AVQueueBuilder.buildPlayerItems` on the existing
  /// `queueItemIds` (so dedup keys remain stable), then playback seeks
  /// back to the captured position.
  ///
  /// Gated on the engine, not on `self.player`: the latter is the gapless
  /// queue player and is nil under crossfade.
  internal func retryFailedItem(queueItemId qid: String) {
    // The attempt has arrived, so whatever fails next is a new failure rather
    // than an echo of the one being retried.
    if self.pendingRetryQueueItemId == qid { self.pendingRetryQueueItemId = nil }
    self.reportedFailureQueueItemIds.remove(qid)
    guard let engine = self.engine else { return }
    // Verify the failed item is still the current one. A subsequent
    // user skip / queue mutation could have moved past it; in that
    // case the retry is moot.
    guard let idx = self.queueItemIds.firstIndex(of: qid),
          idx == self.currentTrackIndex else { return }
    // A retry is an explicit intent to play this track again, so it clears
    // `reachedQueueEnd` exactly as a seek or a skip does. Without this, a
    // queue whose only track failed to load has already drained, been called
    // ended and had its engine parked — so the rebuild below seats an item
    // into a queue that believes it is finished, and playback never resumes.
    self.reachedQueueEnd = false
    // Captured before the rebuild, which reloads the item from zero: a
    // transient blip well into a long track must not restart it.
    let resumePosition = engine.currentPositionSeconds
    // Forced: this exists to replace an item that failed, and a failed item
    // keeps its `queueItemId`, so an identity comparison would find the slice
    // already correct and rebuild nothing.
    self.performingMutation { self.fullRebuildPlayerQueue(forcingReinstall: true) }
    // No seek beats a seek to the wrong track: with a trail installed,
    // engine-index 0 is a track already played rather than the failed one.
    if resumePosition.isFinite, resumePosition > 0,
       let engineIdx = self.engineIndex(forQueuePosition: idx) {
      engine.seek(toIndex: engineIdx, position: resumePosition)
    }
    // Resume only what was actually running. A transient load failure can
    // arrive while the user is paused, and the rebuild leaves the item seated
    // at rate 0 either way, so the transport intent is what separates
    // "recover playback" from "start playing something nobody asked for".
    // Through the engine so the user's playback speed is restored (a raw
    // AVPlayer.play() resets rate to 1.0).
    // An auto-retry completing mid-interruption must not start audio.
    if self.wantsToPlay, !self.isInterrupted, engine.timeControlStatus != .playing {
      self.beginPlayback()
    }
  }
}
