import Foundation
import NitroModules

extension TrackPlayer {
  func setSleepTimer(seconds: Double) throws -> Promise<Void> {
    return enqueue { self.setSleepTimerInternal(seconds: seconds) }
  }
  func setSleepTimerToTrackEnd() throws -> Promise<Void> {
    return enqueue { self.armSleepTimerToTrackEndInternal() }
  }
  func clearSleepTimer() throws -> Promise<Void> {
    return enqueue { self.clearSleepTimerInternal() }
  }
  func getSleepTimer() throws -> SleepTimerState {
    return onPlayerQueueSync { self.sleepTimerSnapshot() }
  }
  func onSleepTimerChange(
    callback: @escaping (_ state: SleepTimerState) -> Void
  ) throws -> () -> Void {
    let id = sleepTimerListeners.add(callback)
    playerQueue.async { [weak self] in
      guard let self else { return }
      guard self.sleepTimerListeners.contains(id: id) else { return }
      callback(self.sleepTimerSnapshot())
    }
    return { [weak self] in _ = self?.sleepTimerListeners.remove(id: id) }
  }

  // MARK: - Sleep timer — internal

  /// Arm a wall-clock duration timer; a non-positive value clears any timer.
  private func setSleepTimerInternal(seconds: Double) {
    guard seconds.isFinite, seconds > 0 else {
      clearSleepTimerInternal()
      return
    }
    restoreSleepTimerVolume()
    sleepTimerCore.armDuration(seconds: seconds, nowMs: Self.nowEpochMs())
    startSleepTimerTick()
    emitSleepTimerChanged()
  }

  private func armSleepTimerToTrackEndInternal() {
    restoreSleepTimerVolume()
    sleepTimerCore.armEndOfTrack()
    startSleepTimerTick()
    emitSleepTimerChanged()
  }

  private func clearSleepTimerInternal() {
    let wasActive = sleepTimerCore.isActive
    restoreSleepTimerVolume()
    sleepTimerCore.clear()
    stopSleepTimerTick()
    if wasActive { emitSleepTimerChanged() }
  }

  private func sleepTimerSnapshot() -> SleepTimerState {
    let now = Self.nowEpochMs()
    return SleepTimerState(
      active: sleepTimerCore.isActive,
      endsAtEpochMs: sleepTimerCore.deadlineEpochMs.map { Double($0) },
      remainingSeconds: sleepTimerCore.remainingSeconds(nowMs: now).map { Double($0) },
      endOfTrack: sleepTimerCore.isEndOfTrack)
  }

  private func emitSleepTimerChanged() {
    let snapshot = sleepTimerSnapshot()
    sleepTimerListeners.forEach { $0(snapshot) }
  }

  private func startSleepTimerTick() {
    guard sleepTimerTimer == nil else { return }
    let timer = DispatchSource.makeTimerSource(queue: playerQueue)
    timer.schedule(
      deadline: .now() + Self.sleepTimerTickInterval,
      repeating: Self.sleepTimerTickInterval)
    timer.setEventHandler { [weak self] in
      self?.onSleepTimerTick()
    }
    sleepTimerTimer = timer
    // The source is resumed exactly once and never suspended — a suspended
    // source traps libdispatch on release.
    timer.resume()
  }

  /// Caller must be on `playerQueue` — cancelling from the source's own
  /// queue is what guarantees no further tick.
  internal func stopSleepTimerTick() {
    sleepTimerTimer?.cancel()
    sleepTimerTimer = nil
  }

  private func onSleepTimerTick() {
    // While casting, the local engine is intentionally frozen (its position
    // never advances), so feed the state machine the receiver's position /
    // duration instead — otherwise the end-of-track tail rule never trips and
    // `.endOfTrack` stays idle forever.
    let durationSec: Double
    let positionSec: Double
    if let remote = PlaybackStateRouter.shared.activeRemote() {
      let d = Double(remote.lastDurationMs) / 1000.0
      let p = Double(remote.lastPositionMs) / 1000.0
      durationSec = (d.isFinite && d > 0) ? d : 0
      positionSec = (p.isFinite && p > 0) ? p : 0
    } else {
      let duration = self.engine?.currentDurationSeconds ?? 0
      let position = self.engine?.currentPositionSeconds ?? 0
      durationSec = (duration.isFinite && duration > 0) ? duration : 0
      positionSec = (position.isFinite && position > 0) ? position : 0
    }
    let result = sleepTimerCore.tick(
      nowMs: Self.nowEpochMs(),
      trackDurationSec: durationSec,
      trackPositionSec: positionSec)
    applySleepTimerResult(result)
  }

  /// Apply a `SleepTimerCore.Tick` decision to the engine — fade, state emit,
  /// and the boundary pause. Shared by the polling tick and the real track-end
  /// signal (`handlePlayerItemDidPlayToEndTime`).
  internal func applySleepTimerResult(_ result: SleepTimerCore.Tick) {
    applySleepTimerFade(result.fadeFraction)
    if result.stateChanged { emitSleepTimerChanged() }
    if result.pauseNow {
      // Route the boundary pause to the receiver when casting (the receiver
      // emits its own paused state); only the local-engine pause needs the
      // sleep-timer reason tag.
      if !CastTransportRouter.routePause() {
        self.pendingStateChangeReason = .sleepTimer
        // The timer's pause is a transport intent like the user's: without
        // clearing these, a drain that follows (the timer firing at a track
        // end on the last installed item) or a buffer refill would restart
        // playback the timer just stopped.
        self.wantsToPlay = false
        self.isRecoveringFromStall = false
        self.engine?.pause()
      }
    }
    if !sleepTimerCore.isActive { stopSleepTimerTick() }
  }

  /// Fade multiplies off `volumeState` without mutating it, so `getVolume`
  /// stays truthful and the pre-fade level is restored on clear/fire.
  private func applySleepTimerFade(_ fraction: Double) {
    // Fade is local-only: the receiver's volume is user/system-owned and
    // `volumeState` tracks the local level, so ramping it on the receiver would
    // clobber the cast volume. When casting the boundary pause still routes to
    // the receiver; the pre-pause fade is skipped. Undo any in-progress local
    // fade first so a cast session appearing mid-fade can't strand the local
    // engine at a ramped-down volume for a later cast→local return.
    if PlaybackStateRouter.shared.activeRemote() != nil {
      restoreSleepTimerVolume()
      return
    }
    if fraction < 1.0 {
      self.engine?.setVolume(Float(Double(self.volumeState) * fraction))
      sleepTimerFading = true
    } else if sleepTimerFading {
      self.engine?.setVolume(self.volumeState)
      sleepTimerFading = false
    }
  }

  private func restoreSleepTimerVolume() {
    if sleepTimerFading {
      self.engine?.setVolume(self.volumeState)
      sleepTimerFading = false
    }
  }

  private static func nowEpochMs() -> Int64 {
    Int64(Date().timeIntervalSince1970 * 1000)
  }
}
