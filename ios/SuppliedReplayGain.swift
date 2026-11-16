import AVFoundation

/// The consumer's ReplayGain claim over one queue entry, resolved once
/// at queue-build time and carried on the `AVPlayerItem` it was built
/// for.
///
/// The claim and the values it survived with are two separate facts, and
/// keeping them separate is the whole point of this type. A `TrackItem`
/// that supplies only an out-of-band peak has claimed its track just as
/// firmly as one that supplies all four values, so collapsing the claim
/// into "did any value survive acceptance" would hand that track back to
/// the file's own tags. Presence claims the track; validity only decides
/// what there is to apply.
///
/// The instance therefore exists whenever any of the four fields was
/// present, and `data` is `nil` when none of those values survived
/// `ReplayGainExtractor`'s acceptance rules — a track that owns its
/// ReplayGain and plays at unity.
final class SuppliedReplayGain {
  /// The supplied values that passed acceptance. `nil` means the
  /// consumer claimed this track and gave nothing usable.
  let data: ReplayGainData?

  private init(data: ReplayGainData?) {
    self.data = data
  }

  /// Build the claim for [track], or `nil` when the consumer supplied
  /// none of the four ReplayGain fields and the track is left on
  /// whatever its file carries.
  ///
  /// Each present value goes through the same `acceptGainDb` /
  /// `acceptPeak` pair the tag strings land on, so a supplied number and
  /// a tagged number are held to one rule.
  static func from(track: TrackItem) -> SuppliedReplayGain? {
    guard track.replayGainTrackGain != nil
      || track.replayGainTrackPeak != nil
      || track.replayGainAlbumGain != nil
      || track.replayGainAlbumPeak != nil
    else {
      return nil
    }
    let trackGain = track.replayGainTrackGain.flatMap { ReplayGainExtractor.acceptGainDb($0) }
    let trackPeak = track.replayGainTrackPeak.flatMap { ReplayGainExtractor.acceptPeak($0) }
    let albumGain = track.replayGainAlbumGain.flatMap { ReplayGainExtractor.acceptGainDb($0) }
    let albumPeak = track.replayGainAlbumPeak.flatMap { ReplayGainExtractor.acceptPeak($0) }
    if trackGain == nil && trackPeak == nil
       && albumGain == nil && albumPeak == nil {
      return SuppliedReplayGain(data: nil)
    }
    return SuppliedReplayGain(
      data: ReplayGainData(
        trackGainDb: trackGain, trackPeak: trackPeak,
        albumGainDb: albumGain, albumPeak: albumPeak
      )
    )
  }
}

private var suppliedReplayGainKey: UInt8 = 0

extension AVPlayerItem {
  /// The consumer's ReplayGain claim over the `TrackItem` this item was
  /// built from. Stamped by `AVQueueBuilder.makeItem` at construction
  /// and carried across by the crossfade engine's item rebuilds, the
  /// same way `queueItemId` and `sourceURL` are.
  ///
  /// Stamped rather than looked up: the queue-mutating surface is
  /// `setQueue` / `addToQueue` / `removeFromQueue` / `moveInQueue` /
  /// `clearQueue`, and every one of them rebuilds or moves items, so
  /// there is no in-place metadata edit that could leave a stamped value
  /// describing a different track than the one playing.
  ///
  /// `nil` means the consumer supplied nothing and the item's ReplayGain
  /// comes from whatever `AssetReplayGainReader` finds in the file.
  var suppliedReplayGain: SuppliedReplayGain? {
    get { objc_getAssociatedObject(self, &suppliedReplayGainKey) as? SuppliedReplayGain }
    set {
      objc_setAssociatedObject(
        self, &suppliedReplayGainKey, newValue, .OBJC_ASSOCIATION_RETAIN_NONATOMIC)
    }
  }
}
