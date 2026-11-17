import Foundation

/// Two parallel arrays bound 1:1 — `tracks[i]` is the consumer-supplied
/// `TrackItem` at queue position `i`, `queueItemIds[i]` is the
/// lib-generated UUID stamped onto the corresponding AVPlayerItem.
/// `matchTrackIndex` looks up `player.currentItem` by walking
/// `queueItemIds`; mismatched lengths break that lookup.
///
/// All mutations go through the methods below so the two fields
/// always change together. Fields are `private(set)` so external
/// code can read but never assign.
internal struct QueueState {
  private(set) var tracks: [TrackItem] = []
  private(set) var queueItemIds: [String] = []

  /// Returns the freshly-generated IDs.
  mutating func replaceAll(_ newTracks: [TrackItem]) -> [String] {
    tracks = newTracks
    queueItemIds = newTracks.map { _ in UUID().uuidString }
    return queueItemIds
  }

  /// Returns the freshly-generated IDs for the inserted slice.
  mutating func insert(_ newTracks: [TrackItem], at index: Int) -> [String] {
    let newIds = newTracks.map { _ in UUID().uuidString }
    insert(newTracks, at: index, ids: newIds)
    return newIds
  }

  /// Insert with caller-supplied IDs — used when the AVPlayerItems were
  /// built (and their assets preloaded) ahead of the state mutation, so
  /// the same lib-generated `queueItemId` that was stamped on each item
  /// stays 1:1 with `queueItemIds`. `ids` must parallel `newTracks`.
  mutating func insert(_ newTracks: [TrackItem], at index: Int, ids: [String]) {
    precondition(
      ids.count == newTracks.count,
      "QueueState.insert requires ids parallel to newTracks (1:1)"
    )
    tracks.insert(contentsOf: newTracks, at: index)
    queueItemIds.insert(contentsOf: ids, at: index)
  }

  /// Returns the removed `queueItemId`.
  mutating func remove(at index: Int) -> String {
    let removedId = queueItemIds.remove(at: index)
    tracks.remove(at: index)
    return removedId
  }

  mutating func move(from: Int, to: Int) {
    let movedTrack = tracks.remove(at: from)
    tracks.insert(movedTrack, at: to)
    let movedId = queueItemIds.remove(at: from)
    queueItemIds.insert(movedId, at: to)
  }

  mutating func clear() {
    tracks.removeAll()
    queueItemIds.removeAll()
  }

  /// Display-only patch. Keep the entry identity and every playback field.
  mutating func updateMetadata(at index: Int, metadata: TrackMetadataUpdate) -> TrackItem? {
    guard tracks.indices.contains(index) else { return nil }
    let old = tracks[index]
    let title = metadata.title ?? old.title
    let artist = metadata.artist ?? old.artist
    let album = metadata.albumTitle ?? old.album
    let artwork = metadata.artworkUrl ?? old.artworkUrl
    guard title != old.title || artist != old.artist || album != old.album
      || artwork != old.artworkUrl else { return nil }
    let updated = TrackItem(
      id: old.id, url: old.url, title: title, artist: artist, album: album,
      duration: old.duration, artworkUrl: artwork, extras: old.extras,
      replayGainTrackGain: old.replayGainTrackGain, replayGainTrackPeak: old.replayGainTrackPeak,
      replayGainAlbumGain: old.replayGainAlbumGain, replayGainAlbumPeak: old.replayGainAlbumPeak)
    tracks[index] = updated
    return updated
  }
}
