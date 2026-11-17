import AVFoundation

/// One output per active item. Invalidating the delegate also fences callbacks
/// already enqueued for an item that has been replaced or an engine torn down.
final class TimedMetadataObservation: NSObject, AVPlayerItemMetadataOutputPushDelegate {
  weak var item: AVPlayerItem?
  let output = AVPlayerItemMetadataOutput(identifiers: nil)
  private var receive: ((AVPlayerItem, TimedMetadata) -> Void)?

  init(item: AVPlayerItem, queue: DispatchQueue,
       receive: @escaping (AVPlayerItem, TimedMetadata) -> Void) {
    self.item = item
    self.receive = receive
    super.init()
    // Zero requests delivery at presentation time, never at download time.
    output.advanceIntervalForDelegateInvocation = 0
    output.setDelegate(self, queue: queue)
    item.add(output)
  }

  func invalidate() {
    receive = nil
    output.setDelegate(nil, queue: nil)
    item?.remove(output)
    item = nil
  }

  func metadataOutput(_ output: AVPlayerItemMetadataOutput,
                      didOutputTimedMetadataGroups groups: [AVTimedMetadataGroup],
                      from track: AVPlayerItemTrack?) {
    guard output === self.output, let item, let receive else { return }
    for group in groups {
      if let metadata = TimedMetadataParser.parse(group.items) {
        receive(item, metadata)
      }
    }
  }

  deinit { invalidate() }
}

extension TrackPlayer {
  /// Called on playerQueue on item changes, retries and engine replacements.
  internal func refreshTimedMetadataObservation() {
    let item = tracks.indices.contains(currentTrackIndex) ? activeMediaItem : nil
    if let observation = timedMetadataObservation, observation.item === item { return }
    timedMetadataObservation?.invalidate()
    timedMetadataObservation = nil
    guard let item else { return }
    timedMetadataObservation = TimedMetadataObservation(item: item, queue: playerQueue) {
      [weak self] source, metadata in
      guard let self,
            self.activeMediaItem === source,
            self.tracks.indices.contains(self.currentTrackIndex),
            PlaybackStateRouter.shared.activeRemote() == nil else { return }
      self.timedMetadataListeners.forEach { $0(metadata) }
    }
  }
}
