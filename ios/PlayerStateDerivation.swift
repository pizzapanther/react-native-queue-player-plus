import AVFoundation

/// Pure derivation of `PlayerState` from the active player's time-control
/// status. Shared by BOTH engines (gapless + crossfade) via
/// `PlaybackEngine.timeControlStatus`, so the two report identical states for
/// identical situations — no per-engine special-casing for consumers. Mirrors
/// Android's `TransportStateTranslator`; pure + deterministic.
///
/// iOS has no native "ended" player state (unlike Android's `STATE_ENDED`), so
/// `TrackPlayer` sets `reachedQueueEnd` when the queue drains with repeat off
/// and clears it when playback resumes / a new track loads.
enum PlayerStateDerivation {
  /// - Parameters:
  ///   - status: the active player's `timeControlStatus`.
  ///   - currentIndex: the lib's canonical current track index (`-1` = empty).
  ///   - hasCurrentItem: whether the active player has a current item.
  ///   - reachedQueueEnd: whether the queue has drained with repeat off (see type doc).
  static func translate(
    status: AVPlayer.TimeControlStatus,
    currentIndex: Int,
    hasCurrentItem: Bool,
    reachedQueueEnd: Bool
  ) -> PlayerState {
    // A drained queue (repeat off) that isn't actively playing is terminal.
    // `.playing` takes precedence so a resume reads correctly even before
    // `reachedQueueEnd` is cleared.
    if reachedQueueEnd, currentIndex >= 0, status != .playing {
      return .ended
    }
    switch status {
    case .paused:
      return currentIndex < 0 ? .none : .paused
    case .waitingToPlayAtSpecifiedRate:
      // Waiting WITH a current item = initial load / rebuffer → `.buffering`.
      // Waiting with NO item = the AVQueuePlayer advanced past the last item
      // (end of queue) before `reachedQueueEnd` was set → terminal `.ended`.
      return hasCurrentItem ? .buffering : (currentIndex < 0 ? .none : .ended)
    case .playing:
      return .playing
    @unknown default:
      return .none
    }
  }
}
