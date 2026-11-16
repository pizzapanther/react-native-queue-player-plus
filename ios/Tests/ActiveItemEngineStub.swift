import AVFoundation
@testable import QueuePlayer

/// A `PlaybackEngine` that reports one active item and nothing else; for
/// tests whose subject reads `activeMediaItem` and needs a real
/// `AVPlayerItem` identity behind it.
final class ActiveItemEngineStub: PlaybackEngine {
  var item: AVPlayerItem?
  private(set) var released = false
  private(set) var playCalls = 0

  var currentMediaItem: AVPlayerItem? { item }
  var allMediaItems: [AVPlayerItem] { item.map { [$0] } ?? [] }
  var attachedQueueItemIds: [String] { [] }
  var isFadePendingOrActive: Bool { false }
  var currentMediaItemIndex: Int { item == nil ? -1 : 0 }
  var currentPositionSeconds: TimeInterval { 0 }
  var currentDurationSeconds: TimeInterval { 0 }
  var bufferedPositionSeconds: TimeInterval { 0 }
  var isPlaying: Bool { false }
  var timeControlStatus: AVPlayer.TimeControlStatus { .paused }
  var delegate: PlaybackEngineDelegate?

  func release() { released = true }
  func setItems(_ items: [AVPlayerItem], startIndex: Int, startPositionSeconds: TimeInterval) {}
  @discardableResult
  func insertItems(_ items: [AVPlayerItem], after: AVPlayerItem?) -> Bool { true }
  func remove(_ item: AVPlayerItem) {}
  func removeAllItems() {}
  func canInsert(_ item: AVPlayerItem, after: AVPlayerItem?) -> Bool { true }
  func play() { playCalls += 1 }
  func pause() {}
  func stop() {}
  func seek(toIndex index: Int, position: TimeInterval) {}
  func skipToNext() {}
  func skipToPrevious() {}
  func setVolume(_ volume: Float) {}
  func setPlaybackSpeed(_ speed: Float) {}
  func seedPlaybackSpeed(_ speed: Float) {}
  func setRepeatMode(_ mode: RepeatMode) {}
  func setPitchCorrectionMode(_ mode: PitchCorrectionMode) {}
  func setAudioMixProvider(_ provider: AudioMixProvider?) {}
  func refreshAllItemMixes() {}
}
