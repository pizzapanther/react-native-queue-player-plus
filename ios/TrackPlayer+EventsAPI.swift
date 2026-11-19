import Foundation

extension TrackPlayer {
  // MARK: - Events — public registration surface
  //
  // Each event has a multi-listener `ListenerRegistry`. Each `onXxx`
  // call appends a new entry; the returned closure removes that entry
  // by stable id. ListenerRegistry's internal queue.sync makes
  // add/remove safe against concurrent fire rounds — fire iterates a
  // snapshot taken under the lock.

  func onStateChange(
    callback: @escaping (_ state: PlayerState, _ reason: StateChangeReason) -> Void
  ) throws -> () -> Void {
    let id = stateChangeListeners.add(callback)
    return { [weak self] in self?.stateChangeListeners.remove(id: id) }
  }

  func onTrackChange(
    callback: @escaping (_ track: TrackItem?, _ index: Double, _ reason: TrackChangeReason, _ transitionGapMs: Double?, _ previousTrack: TrackItem?, _ wasPlaying: Bool) -> Void
  ) throws -> () -> Void {
    let id = trackChangeListeners.add(callback)
    return { [weak self] in self?.trackChangeListeners.remove(id: id) }
  }

  func onQueueChange(
    callback: @escaping (_ delta: QueueChangeDelta, _ currentIndex: Double, _ reason: QueueChangeReason) -> Void
  ) throws -> () -> Void {
    let id = queueChangeListeners.add(callback)
    return { [weak self] in self?.queueChangeListeners.remove(id: id) }
  }

  func onTimedMetadata(callback: @escaping (TimedMetadata) -> Void) throws -> () -> Void {
    let id = timedMetadataListeners.add(callback)
    return { [weak self] in self?.timedMetadataListeners.remove(id: id) }
  }

  /// Emit a queue change as a description of what happened rather than a copy
  /// of the queue. Callers pass only the fields their mutation defines; the
  /// rest stay empty, and `revision` lets a subscriber notice a dropped event.
  internal func emitQueueChange(
    _ reason: QueueChangeReason,
    inserted: [TrackItem] = [],
    insertedAt: Int = -1,
    removed: [Int] = [],
    movedFrom: Int = -1,
    movedTo: Int = -1,
    order: [Int] = []
  ) {
    self.queueRevision += 1
    let delta = QueueChangeDelta(
      revision: Double(self.queueRevision),
      length: Double(self.tracks.count),
      inserted: inserted,
      insertedAt: Double(insertedAt),
      removed: removed.map(Double.init),
      movedFrom: Double(movedFrom),
      movedTo: Double(movedTo),
      order: order.map(Double.init)
    )
    let index = Double(self.currentTrackIndex)
    queueChangeListeners.forEach { $0(delta, index, reason) }
  }

  func onProgress(
    callback: @escaping (_ progress: PlayerProgress) -> Void
  ) throws -> () -> Void {
    let id = progressListeners.add(callback)
    return { [weak self] in self?.progressListeners.remove(id: id) }
  }

  func onPlaybackMilestone(
    callback: @escaping (_ milestone: Double, _ trackIndex: Double) -> Void
  ) throws -> () -> Void {
    let id = milestoneListeners.add(callback)
    return { [weak self] in self?.milestoneListeners.remove(id: id) }
  }

  func onError(callback: @escaping (_ error: PlaybackError) -> Void) throws -> () -> Void {
    let id = errorListeners.add(callback)
    return { [weak self] in self?.errorListeners.remove(id: id) }
  }

  func onQueueEnd(callback: @escaping () -> Void) throws -> () -> Void {
    let id = queueEndListeners.add(callback)
    return { [weak self] in self?.queueEndListeners.remove(id: id) }
  }

  /// Subscribe to skip-capability changes. Auto-fires the callback
  /// once on registration with the current cached values (mirrors
  /// Media3 `Player.Listener.onAvailableCommandsChanged`) so new
  /// subscribers don't have to seed via the getters first. The
  /// auto-fire hops to `playerQueue` so the cache read is on the
  /// queue that writes it. Subsequent fires happen synchronously
  /// from `recomputeCapabilities()`, also on `playerQueue`.
  ///
  /// **Dispose race guard:** the deferred fire checks the
  /// subscriber is still registered (`contains(id:)`) before
  /// invoking the callback. Strict-mode subscribe → dispose →
  /// subscribe sequences fire the cleanup before the deferred fire
  /// runs; without the guard, the auto-fire would call into an
  /// already-unmounted React component's `setSnapshot`.
  func onSkipCapabilityChange(
    callback: @escaping (_ capability: SkipCapability) -> Void
  ) throws -> () -> Void {
    let id = skipCapabilityListeners.add(callback)
    playerQueue.async { [weak self] in
      guard let self else { return }
      guard self.skipCapabilityListeners.contains(id: id) else { return }
      callback(SkipCapability(
        canSkipPrevious: self.cachedSkipCapability.canSkipPrevious,
        canSkipNext: self.cachedSkipCapability.canSkipNext))
    }
    return { [weak self] in self?.skipCapabilityListeners.remove(id: id) }
  }
}
