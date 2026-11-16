/// Pure-Swift skip-index arithmetic. Extracted from `TrackPlayer.swift`
/// so the logic stays clear of the Swift ↔ C++ interop boundary —
/// Nitrogen-generated types (`TrackItem`, `RepeatMode`) don't export
/// their members to importing modules under Swift's current
/// `-cxx-interoperability-mode=default` settings. These helpers take
/// primitive arguments instead.
///
/// `TrackPlayer.computeNextIndex` / `.computePreviousIndex` instance
/// methods are thin adapters that map the Nitrogen `RepeatMode` enum
/// onto `QueueSkipRepeatMode` and call through here.

/// Repeat-mode enum expressed in plain Swift. Value order (0/1/2)
/// matches `RepeatModeKind` in the nitro.ts spec.
///
/// Gated behind `@_spi(QueuePlayerTests)` so the helper surface stays
/// off the library's consumer-facing API.
@_spi(QueuePlayerTests)
public enum QueueSkipRepeatMode: Int {
  case off = 0
  case track = 1
  case queue = 2
}

@_spi(QueuePlayerTests)
public enum QueueSkipArithmetic {

  /// Returns the next-track index, or nil when nothing should play
  /// next (empty queue, no next track under `.off`/`.track` at the
  /// end, or nothing currently playing). `.track` navigates
  /// identically to `.off`: advance with no wrap. Repeat-one persists
  /// so the newly-current track loops on natural end (handled in
  /// `TrackPlayer`, not here).
  public static func computeNext(
    trackCount: Int,
    currentIndex: Int,
    repeatMode: QueueSkipRepeatMode
  ) -> Int? {
    guard trackCount > 0, currentIndex >= 0 else { return nil }
    switch repeatMode {
    case .off, .track:
      let next = currentIndex + 1
      return next < trackCount ? next : nil
    case .queue:
      let next = currentIndex + 1
      return next < trackCount ? next : 0
    }
  }

  /// Previous-track index. Same early-bail rules as `computeNext`.
  ///
  /// `.off` and `.track` both return nil at index 0 (no previous track,
  /// no wrap). The "restart current when past the threshold" model is
  /// available via `PlayerConfig.skipToPreviousBehavior` (resolved by
  /// `resolveSkipToPrevious`).
  public static func computePrevious(
    trackCount: Int,
    currentIndex: Int,
    repeatMode: QueueSkipRepeatMode
  ) -> Int? {
    guard trackCount > 0, currentIndex >= 0 else { return nil }
    switch repeatMode {
    case .off, .track:
      let prev = currentIndex - 1
      return prev >= 0 ? prev : nil
    case .queue:
      return currentIndex == 0 ? trackCount - 1 : currentIndex - 1
    }
  }

  /// Whether the user-facing Next button should be enabled. Defined as
  /// `computeNext(...) != nil` so all three repeat modes are handled
  /// in one place: `.off` and `.track` are false at the last track
  /// (no wrap); `.queue` is always true with an active track (wraps).
  public static func canSkipNext(
    trackCount: Int,
    currentIndex: Int,
    repeatMode: QueueSkipRepeatMode
  ) -> Bool {
    return computeNext(
      trackCount: trackCount,
      currentIndex: currentIndex,
      repeatMode: repeatMode) != nil
  }

  /// Whether the user-facing Previous button should be enabled.
  /// Symmetric with `canSkipNext` — defined as `computePrevious(...)
  /// != nil`, so all three repeat modes are handled in one place:
  /// `.off` and `.track` are false at index 0 (no wrap); `.queue` is
  /// always true with an active track (wraps).
  ///
  /// When `restartEnabled` is true (`PlayerConfig.skipToPreviousBehavior
  /// == .restartOrPrevious`), Previous is enabled whenever a track is
  /// loaded — even at index 0, where past the threshold it restarts the
  /// current track — so the control never greys out while something is
  /// playing. With no loaded track (`currentIndex < 0`) there is nothing
  /// to restart, matching what resolveSkipToPrevious does for that state.
  public static func canSkipPrevious(
    trackCount: Int,
    currentIndex: Int,
    repeatMode: QueueSkipRepeatMode,
    restartEnabled: Bool = false
  ) -> Bool {
    if restartEnabled { return trackCount > 0 && currentIndex >= 0 }
    return computePrevious(
      trackCount: trackCount,
      currentIndex: currentIndex,
      repeatMode: repeatMode) != nil
  }

  /// Position (ms) at or below which a `.restartOrPrevious` skip-back goes
  /// to the previous track; past it, the current track restarts. Matches
  /// Media3's default `maxSeekToPreviousPosition` (the industry-standard 3 s).
  public static let restartThresholdMs: Int = 3000

  /// What a skip-to-previous should do, resolved from queue position + the
  /// configured behavior.
  public enum SkipToPreviousAction: Equatable {
    /// Navigate to this queue index (a real track change).
    case previous(Int)
    /// Restart the current track (seek to 0) — not a track change.
    case restartCurrent
    /// Nothing to do (empty queue, or no loaded track).
    case noOp
  }

  /// Resolves a skip-to-previous. When `restartEnabled` is false this is
  /// exactly `computePrevious` (previous track, or nothing). When true it
  /// applies the threshold model, matching Media3 `BasePlayer.seekToPrevious`:
  /// a previous track exists AND `positionMs <= thresholdMs` → previous;
  /// otherwise (past threshold, or first track with no previous) → restart
  /// the current track.
  public static func resolveSkipToPrevious(
    trackCount: Int,
    currentIndex: Int,
    repeatMode: QueueSkipRepeatMode,
    restartEnabled: Bool,
    positionMs: Int,
    thresholdMs: Int = restartThresholdMs
  ) -> SkipToPreviousAction {
    guard trackCount > 0, currentIndex >= 0 else { return .noOp }
    let prev = computePrevious(
      trackCount: trackCount,
      currentIndex: currentIndex,
      repeatMode: repeatMode)
    if !restartEnabled {
      return prev.map { .previous($0) } ?? .noOp
    }
    if let prev, positionMs <= thresholdMs {
      return .previous(prev)
    }
    return .restartCurrent
  }
}
