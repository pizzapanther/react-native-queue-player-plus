import AVFoundation
import NitroModules

extension TrackPlayer {
  func updateMetadata(index: Double, metadata: TrackMetadataUpdate) throws -> Promise<Void> {
    enqueue { self.updateMetadataBody(index: index, metadata: metadata) }
  }

  internal func updateMetadataBody(index: Double, metadata: TrackMetadataUpdate) {
    guard index.rounded(.towardZero) == index,
          let index = InputGuards.validQueueIndex(index, count: tracks.count) else { return }
    let oldArtwork = tracks[index].artworkUrl
    guard let updated = queueState.updateMetadata(at: index, metadata: metadata) else { return }
    // AVPlayerItems carry media, not our display metadata. Keep those exact
    // items installed, including both legs of an in-flight crossfade.
    if index == currentTrackIndex {
      self.announcedTrack = updated
      let artworkChanged = oldArtwork != updated.artworkUrl
      nowPlayingInfo.refreshMetadata(nowPlayingSnapshot(), artworkChanged: artworkChanged)
      if artworkChanged {
        let itemId = queueItemIds[index]
        artworkResolver.resolve(url: updated.artworkUrl) { [weak self] image in
          self?.nowPlayingInfo.applyArtwork(image, for: itemId)
        }
      }
      if PlaybackStateRouter.shared.activeRemote() != nil {
        CastNowPlayingController.shared.updateTrackMetadata(
          title: updated.title, artist: updated.artist, album: updated.album,
          durationSec: updated.duration ?? 0, artworkUrl: updated.artworkUrl)
      }
    }
    emitQueueChange(.updateMetadata, inserted: [updated], insertedAt: index)
    recomputeCapabilities() // Refresh the CarPlay queue mirror too.
  }
}
