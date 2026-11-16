import AVFoundation
import Foundation

/// Single-`AVQueuePlayer` engine — native gapless via AVFoundation
/// item chaining + the audio session route.
///
/// Owns the underlying `AVQueuePlayer` and exposes it as a typed
/// property for service-level wiring (`AVAudioSession` activation,
/// `MPNowPlayingInfoCenter` integration, `MPRemoteCommandCenter`
/// installation) which `TrackPlayer` performs at configure time.
/// The engine itself is a thin wrapper that forwards every
/// `PlaybackEngine` method onto the underlying player and exposes
/// the player's state as protocol properties.
///
/// `TrackPlayer` installs its observer wiring on `engine.player`
/// directly — `installGaplessObservers` and `installEventObservers`
/// continue to attach KVO + NotificationCenter handlers to the
/// underlying player. The engine's `delegate` slot is reserved for
/// the routing surface that fans Media3-flavored `PlaybackEngineDelegate`
/// callbacks; on the iOS side the equivalent fan-out continues to
/// run through `TrackPlayer`'s direct observer code.
///
/// Threading: `TrackPlayer` calls every method on its `playerQueue`,
/// the one serial queue that owns the player. This engine never fires
/// its `delegate`; its events reach `TrackPlayer` through the KVO and
/// NotificationCenter observers `TrackPlayer` installs on `player`
/// (`installGaplessObservers` / `installEventObservers`), each of which
/// hops onto `playerQueue` before touching state. Main-only frameworks
/// (`MPNowPlayingInfoCenter`, CarPlay) are reached by `TrackPlayer`
/// hopping to main with the payload already computed.
final class GaplessEngine: PlaybackEngine {

  /// Underlying AVQueuePlayer. Internal to the lib so service-level
  /// wiring (audio session, NowPlaying, RemoteCommands, KVO) can
  /// continue to access the player directly during the multi-step
  /// extraction. Pure engine-aware consumers must use the abstract
  /// `PlaybackEngine` surface.
  let player: AVQueuePlayer

  weak var delegate: PlaybackEngineDelegate?

  private var mixProvider: AudioMixProvider?
  private var released = false
  /// Whether the transport wants playback, independent of what the player is
  /// doing. `AVQueuePlayer` does not drop its rate when it runs out of items,
  /// so a drained player is indistinguishable from a playing one by rate
  /// alone — and a rebuild that reads the rate would start the next queue
  /// installed on it. Set by `play()`, cleared by `pause()`, `stop()`,
  /// `release()` and every path that drains the queue.
  private var wantsPlayback = false

  /// `allowsExternalPlayback` is `PlayerConfig.allowsExternalPlayback`.
  init(allowsExternalPlayback: Bool) {
    let p = AVQueuePlayer()
    p.automaticallyWaitsToMinimizeStalling = true
    p.allowsExternalPlayback = allowsExternalPlayback
    p.actionAtItemEnd = .advance
    self.player = p
  }

  // MARK: - Lifecycle

  func release() {
    if released { return }
    released = true
    wantsPlayback = false
    // Releasing the queue empties the AVQueuePlayer's items; the
    // player itself is held by the strong `let player` until the
    // engine is deallocated, which TrackPlayer triggers when it
    // drops its engine reference.
    AVQueueBuilder.cancelLoading(dropping: player.items())
    player.removeAllItems()
    mixProvider = nil
    // Detach the delegate so any in-flight event still on the run
    // loop doesn't reach a TrackPlayer that has just released its
    // engine.
    delegate = nil
  }

  // MARK: - Queue mutation

  /// Drain the queue and re-install the slice of [items] from
  /// [startIndex] onward. Captures + restores the player rate
  /// around the rebuild so a `playing → setItems → still-playing`
  /// sequence survives `removeAllItems()` + the re-insert loop.
  /// Apple's docs do not formally guarantee rate preservation on
  /// `removeAllItems()`; capture + restore is defense in depth.
  ///
  /// Order: when [startPositionSeconds] > 0 the rate restore is
  /// queued inside the seek completion handler so the seek lands
  /// before playback resumes — otherwise a brief tick from
  /// position 0 leaks before the seek catches up.
  func setItems(
    _ items: [AVPlayerItem],
    startIndex: Int,
    startPositionSeconds: TimeInterval
  ) {
    let shouldResume = wantsPlayback
    // The outgoing items are discarded here; anything the incoming set reuses
    // keeps its loads, since a cancelled asset never becomes playable.
    AVQueueBuilder.cancelLoading(dropping: player.items(), keeping: items)
    player.removeAllItems()
    guard !items.isEmpty,
          startIndex >= 0,
          startIndex < items.count else {
      // Nothing seated, so nothing is being played. Removing the items does
      // not stop the player, and a leg left running would start whatever is
      // installed next on contact.
      wantsPlayback = false
      player.pause()
      // Empty queue, out-of-range startIndex, or startIndex past
      // end: leave drained. Matches `TrackPlayer.fullRebuildPlayerQueue`
      // where bad index also drains via the same removeAllItems-then-guard
      // shape.
      return
    }
    let slice = Array(items[startIndex ..< items.count])
    var cursor: AVPlayerItem? = nil
    for item in slice {
      if player.canInsert(item, after: cursor) {
        // Per-item audioMix install before enqueue. AVPlayerItem
        // honours `audioMix` only at item-enqueue time (Apple
        // contract: changes after the item starts rendering are
        // silently dropped — the property updates but the new
        // `MTAudioProcessingTap`'s init/prepare/process callbacks
        // never fire). Setting before `player.insert(...)` ensures
        // every full-queue rebuild (skipToPrevious / skipToIndex /
        // setQueue) lands a tap-bearing mix on the new currentItem.
        // For streaming items whose `tracks` key has not yet
        // loaded, the provider returns nil and the mix is applied
        // by `engineActiveItemFormatChanged → refreshActiveItemMixes`
        // once tracks publish.
        item.audioMix = mixProvider?.audioMix(for: item)
        applyPitchAlgorithm(to: item)
        player.insert(item, after: cursor)
        cursor = item
      }
    }
    let restoreRate: () -> Void = { [weak self] in
      guard let self, shouldResume, self.player.rate != self.userSpeed else { return }
      self.player.rate = self.userSpeed
    }
    if startPositionSeconds > 0, let head = player.currentItem {
      head.seek(
        to: CMTime(seconds: startPositionSeconds, preferredTimescale: 600),
        completionHandler: { _ in restoreRate() }
      )
    } else {
      restoreRate()
    }
  }

  /// Surgical insert into the live `AVQueuePlayer`. Iterates
  /// [items] calling `canInsert` + `insert(_:after:)`; returns
  /// `false` on the first `canInsert` rejection so the caller
  /// can fall back to a full rebuild via `setItems`.
  @discardableResult
  func insertItems(
    _ items: [AVPlayerItem],
    after: AVPlayerItem?
  ) -> Bool {
    var cursor: AVPlayerItem? = after
    for item in items {
      if player.canInsert(item, after: cursor) {
        // Per-item audioMix install before enqueue — see `setItems`
        // for the rationale.
        item.audioMix = mixProvider?.audioMix(for: item)
        applyPitchAlgorithm(to: item)
        player.insert(item, after: cursor)
        cursor = item
      } else {
        return false
      }
    }
    return true
  }

  /// Remove [item] from the live queue. Removing the current item
  /// causes `AVQueuePlayer` to auto-advance to the next queued
  /// item — caller is responsible for keeping its lib-side
  /// `currentTrackIndex` aligned.
  func remove(_ item: AVPlayerItem) {
    player.remove(item)
  }

  /// Drain the queue. After this call `currentMediaItem == nil`.
  func removeAllItems() {
    // Emptying the queue is not the same as stopping: `AVQueuePlayer` keeps
    // its rate across `removeAllItems`, so without this the next item seated
    // on the player would start on contact.
    wantsPlayback = false
    player.pause()
    AVQueueBuilder.cancelLoading(dropping: player.items())
    player.removeAllItems()
  }

  /// Mirror of `AVQueuePlayer.canInsert`. `nil` [after] checks
  /// whether the item can be inserted at the head.
  func canInsert(_ item: AVPlayerItem, after: AVPlayerItem?) -> Bool {
    player.canInsert(item, after: after)
  }

  // MARK: - Transport

  /// Persisted playback speed so `play()` resumes at the user's rate.
  /// `AVPlayer.play()` resets rate to 1.0 (defaultRate), so a
  /// set-speed → pause → resume would otherwise drop back to 1.0×.
  private var userSpeed: Float = 1.0

  /// Persisted pitch-correction mode. Stamped on each item as
  /// `audioTimePitchAlgorithm` (per-item; AVQueuePlayer has no
  /// player-level equivalent) at enqueue and re-applied on mode /
  /// speed change. The resolved algorithm depends on `userSpeed`
  /// (the intended rate), not `player.rate` (0 while paused).
  private var pitchCorrectionMode: PitchCorrectionMode = .voice

  func play() {
    DiagnosticLog.player.log("engine play (gapless)")
    wantsPlayback = true
    player.rate = userSpeed
  }

  func pause() {
    DiagnosticLog.player.log("engine pause (gapless)")
    wantsPlayback = false
    player.pause()
  }

  func stop() {
    DiagnosticLog.player.log("engine stop (gapless)")
    wantsPlayback = false
    player.pause()
    if let item = player.currentItem {
      item.seek(to: .zero, completionHandler: nil)
    }
  }

  /// Seek to [index] / [position]. AVQueuePlayer is forward-only —
  /// the engine walks the head forward by `advanceToNextItem` until
  /// the target is the current item. Backward seek (target index
  /// before `currentMediaItemIndex`) is the caller's
  /// responsibility: the canonical queue model lives on
  /// `TrackPlayer`, which rebuilds the AVQueuePlayer queue via
  /// `setItems` before invoking seek for backward navigation. The
  /// engine pauses around the multi-step walk to suppress the
  /// transient audio that each `advanceToNextItem` would otherwise
  /// produce on the new head.
  func seek(toIndex index: Int, position: TimeInterval) {
    let queueItems = player.items()
    guard index >= 0, index < queueItems.count else { return }
    let target = queueItems[index]
    if player.currentItem === target {
      target.seek(to: CMTime(seconds: position, preferredTimescale: 600), completionHandler: nil)
      return
    }
    // Multi-step forward walk: pause first to suppress the
    // intermediate audio, then advance, then restore the rate.
    let savedRate = player.rate
    if savedRate > 0 { player.pause() }
    var safety = queueItems.count
    while let head = player.currentItem, head !== target, safety > 0 {
      player.advanceToNextItem()
      safety -= 1
    }
    if let now = player.currentItem, now === target {
      now.seek(to: CMTime(seconds: position, preferredTimescale: 600), completionHandler: nil)
    }
    if savedRate > 0 { player.rate = savedRate }
  }

  func skipToNext() {
    player.advanceToNextItem()
  }

  func skipToPrevious() {
    // AVQueuePlayer is forward-only; seeking the current item to 0
    // is the closest approximation. TrackPlayer's queue model owns
    // the bidirectional skip semantics by rebuilding the queue
    // when needed.
    player.currentItem?.seek(to: .zero, completionHandler: nil)
  }

  // MARK: - Config

  func setVolume(_ volume: Float) {
    player.volume = volume
  }

  func setPlaybackSpeed(_ speed: Float) {
    userSpeed = speed
    player.rate = speed
    // The resolved algorithm depends on whether speed is unity, so a
    // change that crosses 1.0 re-stamps the queue (idempotent — only
    // the items whose effective algorithm changed are touched).
    reapplyPitchAlgorithm()
  }

  func seedPlaybackSpeed(_ speed: Float) {
    // Persist only; `play()` applies it. Does not touch `player.rate`,
    // so it won't start a paused engine.
    userSpeed = speed
    reapplyPitchAlgorithm()
  }

  func setRepeatMode(_ mode: RepeatMode) {
    // AVQueuePlayer has no native repeat mode. Under repeat-one, hold the
    // finished item at its end (`actionAtItemEnd = .none`) so the player
    // does not auto-advance — `TrackPlayer.handlePlayerItemDidPlayToEndTime`
    // then deterministically rewinds it to zero and replays it. `.off` /
    // `.queue` advance + wrap are driven by TrackPlayer's queue rebuild, so
    // they keep `.advance`. Switching mode only affects future end-of-item
    // behaviour; an item already parked at its end (repeat-one + the
    // end-of-track sleep-timer pause) is not retroactively advanced — a
    // resume or skip recovers it.
    player.actionAtItemEnd = (mode == .track) ? .none : .advance
  }

  func setPitchCorrectionMode(_ mode: PitchCorrectionMode) {
    pitchCorrectionMode = mode
    reapplyPitchAlgorithm()
  }

  /// Stamp the resolved algorithm on `item`. Idempotent: a no-op when
  /// the item already carries it, so re-applying on a rendering item
  /// doesn't churn the audio graph.
  private func applyPitchAlgorithm(to item: AVPlayerItem) {
    let algo = PitchAlgorithm.resolve(mode: pitchCorrectionMode, speed: userSpeed)
    if item.audioTimePitchAlgorithm != algo {
      item.audioTimePitchAlgorithm = algo
    }
  }

  private func reapplyPitchAlgorithm() {
    for item in player.items() { applyPitchAlgorithm(to: item) }
  }

  // MARK: - State

  var currentMediaItemIndex: Int {
    let queueItems = player.items()
    if queueItems.isEmpty { return -1 }
    if let current = player.currentItem,
       let idx = queueItems.firstIndex(of: current) {
      return idx
    }
    // Queue is non-empty but currentItem is nil — paused at the
    // edge of the queue. Return the last queued index so callers
    // see "still on the last track" rather than the empty-queue
    // sentinel.
    return queueItems.count - 1
  }

  var currentPositionSeconds: TimeInterval {
    let t = player.currentTime()
    return t.isNumeric ? CMTimeGetSeconds(t) : 0
  }

  var currentDurationSeconds: TimeInterval {
    guard let d = player.currentItem?.duration, d.isNumeric else { return 0 }
    return CMTimeGetSeconds(d)
  }

  var bufferedPositionSeconds: TimeInterval {
    guard let ranges = player.currentItem?.loadedTimeRanges,
          let last = ranges.last?.timeRangeValue else { return 0 }
    return CMTimeGetSeconds(last.start + last.duration)
  }

  var isPlaying: Bool {
    player.timeControlStatus == .playing
  }

  var timeControlStatus: AVPlayer.TimeControlStatus {
    player.timeControlStatus
  }

  var currentMediaItem: AVPlayerItem? {
    player.currentItem
  }

  /// This engine has one leg and no fade to schedule.
  var isFadePendingOrActive: Bool { false }

  /// One leg, so whatever is current is the whole of what is attached.
  var attachedQueueItemIds: [String] {
    player.currentItem?.queueItemId.map { [$0] } ?? []
  }

  var allMediaItems: [AVPlayerItem] {
    player.items()
  }

  // MARK: - Effect chain

  func setAudioMixProvider(_ provider: AudioMixProvider?) {
    mixProvider = provider
    refreshAllItemMixes()
  }

  func refreshAllItemMixes() {
    // Catch-up walk: only fill items that were inserted with
    // `audioMix == nil` (typically streaming items whose `tracks`
    // key hadn't loaded at insert time, so `audioMix(for:)` returned
    // nil). Items that already carry a tap are left alone —
    // re-assigning would allocate a fresh `MTAudioProcessingTap` +
    // `EQProcessor` cascade that AVPlayer silently drops once the
    // item has begun rendering, leaking the prepared resources to
    // ARC until the property is reassigned again. The intended
    // mid-play tap handoff is "subscribers join the already-running
    // tap via `AudioTapProvider.deliverAudioFrame` fan-out", not "tear
    // down + reinstall the tap on every refresh".
    let provider = mixProvider
    for item in player.items() {
      if item.audioMix != nil { continue }
      item.audioMix = provider?.audioMix(for: item)
    }
    // `audioMix(for:)` announces each item it is given as the active asset,
    // so a walk leaves the last item refreshed standing as active. Re-assert
    // the one that is playing, or consumers read the wrong asset — the
    // visualizer's HLS check is answered from it.
    provider?.noteActiveAsset(player.currentItem?.asset)
  }
}
