import Foundation

extension TrackPlayer {
  // MARK: - State snapshots
  //
  // Nitro calls these getters on a JS worker thread. Player state lives
  // on `playerQueue`, so every read hops there with `onPlayerQueueSync`,
  // which runs the body inline when the caller is already on that queue
  // (a `sync` from the queue onto itself would deadlock). The cached
  // `canSkip*` Bools are written on `playerQueue` by
  // `recomputeCapabilities()`, so the same hop reads them consistently.
  func getState() throws -> PlayerState {
    // The receiver owns playback during a cast session — the local engine is
    // parked and paused for its duration, so its derived state describes
    // nothing the user can hear. Until the receiver has reported (session
    // still activating, holding its initial `.idle`) the local read is the
    // better answer; after that the receiver is authoritative, including a
    // later `.idle`, which means it genuinely has nothing loaded.
    if let remote = PlaybackStateRouter.shared.activeRemote(), remote.hasReportedState {
      return remote.lastState.asPlayerState
    }
    return onPlayerQueueSync {
      // Both engines derive PlayerState from the same inputs via
      // `engine.timeControlStatus` (gapless returns its AVQueuePlayer's, which
      // is what `self.player` also points at; crossfade returns the active
      // leg's), so gapless + crossfade report identical states. `.none` when no
      // engine is configured.
      guard let engine = self.engine else { return .none }
      return PlayerStateDerivation.translate(
        status: engine.timeControlStatus,
        currentIndex: self.currentTrackIndex,
        hasCurrentItem: engine.currentMediaItem != nil,
        reachedQueueEnd: self.reachedQueueEnd
      )
    }
  }
  func getCurrentTrackIndex() throws -> Double {
    // Nitrogen spec types this return as Double; bridge the internal Int.
    return onPlayerQueueSync { Double(self.currentTrackIndex) }
  }
  func getCurrentTrackSource() throws -> TrackSource? {
    return onPlayerQueueSync { self.currentTrackSource }
  }
  func getCanSkipNext() throws -> Bool {
    return onPlayerQueueSync { self.cachedSkipCapability.canSkipNext }
  }
  func getSkipCapability() throws -> SkipCapability {
    return onPlayerQueueSync { self.cachedSkipCapability }
  }
  func getCanSkipPrevious() throws -> Bool {
    return onPlayerQueueSync { self.cachedSkipCapability.canSkipPrevious }
  }
  func getPlaybackSpeed() throws -> Double {
    return onPlayerQueueSync { Double(self.playbackSpeedState) }
  }
  func getVolume() throws -> Double {
    return onPlayerQueueSync { Double(self.volumeState) }
  }
  func getRemoteControls() throws -> RemoteControlOptions {
    return onPlayerQueueSync {
      let options: RemoteControlOptions = .init(
        skipMode: self.remoteSkipMode,
        forwardJumpInterval: self.remoteForwardInterval,
        backwardJumpInterval: self.remoteBackwardInterval)
      return options
    }
  }

  func getBufferState() throws -> BufferState {
    return onPlayerQueueSync { self.currentBufferState }
  }

  func getIsSeekable() throws -> Bool {
    return onPlayerQueueSync {
      guard let item = self.engine?.currentMediaItem else { return true }
      return !item.seekableTimeRanges.isEmpty
    }
  }

  func isFullyBuffered() throws -> Bool {
    return onPlayerQueueSync { self.currentFullyBuffered }
  }

  func onBufferStateChange(
    callback: @escaping (_ state: BufferState) -> Void
  ) throws -> () -> Void {
    let id = bufferStateListeners.add(callback)
    // Fire once on subscribe with the current state so a UI renders immediately.
    playerQueue.async { [weak self] in
      guard let self else { return }
      guard self.bufferStateListeners.contains(id: id) else { return }
      callback(self.currentBufferState)
    }
    return { [weak self] in self?.bufferStateListeners.remove(id: id) }
  }

  func onFullyBufferedChange(
    callback: @escaping (_ fullyBuffered: Bool) -> Void
  ) throws -> () -> Void {
    let id = fullyBufferedListeners.add(callback)
    playerQueue.async { [weak self] in
      guard let self else { return }
      guard self.fullyBufferedListeners.contains(id: id) else { return }
      callback(self.currentFullyBuffered)
    }
    return { [weak self] in self?.fullyBufferedListeners.remove(id: id) }
  }
}
