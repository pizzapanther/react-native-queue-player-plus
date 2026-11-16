import AVFoundation
import Foundation

/// Dual-`AVPlayer` engine implementing equal-power crossfade at
/// track boundaries.
///
/// Two `AVPlayer` instances rotate roles per fade: the leading
/// leg is currently audible; the standby leg prerolls the next
/// queue item ahead of the fade window and ramps up from silence
/// as the leading ramps down.
///
/// Architecture (per platform research):
/// - Single-item per `AVPlayer` (NOT `AVQueuePlayer`) so each
///   leg's `AVMutableAudioMixInputParameters.setVolumeRamp` is
///   independently scheduled. The lib-side queue model on
///   `TrackPlayer` is the canonical position; the engine
///   advances the standby leg's currentItem to the next queue
///   position ahead of fade-start.
/// - Equal-power curve approximated via N short `setVolumeRamp`
///   sub-ramps. AVFoundation does not document the ramp's curve, so
///   each sub-ramp is treated as linear; ~50 sub-ramps over a fade window
///   is auditorily indistinguishable from true `cos(t·π/2)` /
///   `sin(t·π/2)`.
/// - Standby preroll via `AVPlayer.preroll(atRate: 1.0)` ~500ms
///   before fade-start so the audio output graph is hot when
///   its volume ramp begins.
/// - Workaround: a backward seek through a scheduled `setVolumeRamp`
///   window leaves the mix in place and the volume wrong on the next
///   play-through; recovery is `audioMix = nil` + `volume = userVolume`.
///   TODO: monitor upstream for fixes —
///   https://wadetregaskis.com/fading-audio-with-avplayer/ documents the
///   symptom; there is no Apple Feedback number to track.
/// - Per-boundary route check via `OutputRouteMonitor`: if the
///   active route is `.airPlay` / `.carAudio`, the boundary
///   hard-cuts (no fade) for that transition. Mid-fade route
///   change cancels the fade synchronously.
///
/// Runtime fade execution is a multi-second `setVolumeRamp` schedule
/// plus standby promotion to leading at fade-end, built on
/// deterministic primitives (curve math, sub-ramp count, route
/// classification, boundary fade-vs-cut decision).
final class CrossfadeEngine: PlaybackEngine {

  // MARK: - Construction

  /// User-set fade duration captured at construction time. Both
  /// `AVAudioMix` ramp scheduling and the role-swap timer
  /// coordinate against this value; runtime duration changes go
  /// through `setPlaybackMode`, which constructs a new engine.
  private let crossfadeDurationSeconds: TimeInterval

  /// Pre-fade preroll lead so the standby leg's audio output
  /// graph is hot when its first ramp starts. 500ms matches the
  /// platform research recommendation.
  static let prerollLeadSeconds: TimeInterval = 0.5

  /// Backstop for the part of the arm chain the status wait does not cover:
  /// the incoming asset's `tracks` load and preroll, which begin once the
  /// standby leg reports ready and have no bound of their own.
  ///
  /// Measured from that flip rather than from the arm, because an arm fires at
  /// most `maxCrossfadeMs + prerollLeadSeconds` before the leading track ends —
  /// a deadline measured from the arm and longer than that could only ever
  /// expire after the boundary it exists to protect.
  static let armDeadlineSeconds: TimeInterval = 5.0

  /// Equal-power approximation step count. ~50 sub-ramps over a
  /// 6-second fade is below the audibility threshold for the
  /// difference between staircased and continuous cos/sin.
  static let subRampCount: Int = 50

  /// Volume multiplier applied to both legs during the fade.
  /// Tracked so manual `setVolume(v)` survives a fade cancel or the
  /// role swap at fade end.
  private var userVolume: Float = 1.0

  /// Tracked so the user's playback speed survives a crossfade
  /// transition: the standby leg prerolls/plays at the configured
  /// rate and is re-set to this when promoted to leading. Without it
  /// every fade resets the rate to 1.0.
  private var userPlaybackSpeed: Float = 1.0

  /// Persisted pitch-correction mode, stamped as `audioTimePitchAlgorithm`
  /// on both legs' items. The resolved algorithm depends on
  /// `userPlaybackSpeed` (the intended rate), not the live leg rate
  /// (0 while paused / on the silent standby).
  private var pitchCorrectionMode: PitchCorrectionMode = .voice

  weak var delegate: PlaybackEngineDelegate?

  /// Owns the route monitor instead of relying on a shared one
  /// — the engine starts/stops monitoring with its own
  /// lifecycle.
  private let routeMonitor: OutputRouteMonitor

  // MARK: - Players

  private let playerA: AVPlayer
  private let playerB: AVPlayer

  /// `true` when [leadingPlayer] is `playerA` (the engine's
  /// first-built player). Flips on every fade-complete role
  /// swap. Read by the test surface to verify role-swap
  /// arithmetic without driving a real fade.
  private(set) var currentRoleIsA: Bool = true

  /// Leading leg — currently audible.
  var leadingPlayer: AVPlayer { currentRoleIsA ? playerA : playerB }

  /// Standby leg — prerolls the next queue item ahead of fade-start.
  var standbyPlayer: AVPlayer { currentRoleIsA ? playerB : playerA }

  // MARK: - Queue model

  /// The queue the owner serialises player access on. Every callback this
  /// engine delivers, and every timer and observer it installs, lands here —
  /// the fade state below carries no lock, so it is only safe while one queue
  /// owns both it and the transport calls driving it.
  private let ownerQueue: DispatchQueue

  /// Run `body` on the owner's queue and await its result, for the points
  /// where an async fade step has to touch that state again.
  private func onOwnerQueue<T>(_ body: @escaping () -> T) async -> T {
    await withCheckedContinuation { (cont: CheckedContinuation<T, Never>) in
      self.ownerQueue.async { cont.resume(returning: body()) }
    }
  }

  /// Engine-internal queue snapshot — caller sets via
  /// `setItems`; engine advances `currentIndex` on user skip /
  /// auto-advance. Each `AVPlayer` instance holds at most one
  /// item from this list at a time.
  private var queue: [AVPlayerItem] = []
  private var currentIndex: Int = -1

  /// Active repeat mode. AVPlayer has no native repeat, so the engine
  /// implements repeat-one itself: it suppresses the look-ahead fade
  /// and loops the current item on natural end (see `maybeArmCrossfade`
  /// + `handleItemDidPlayToEnd`). `.queue` wrap is driven by TrackPlayer
  /// rebuilding the queue, same as the gapless engine.
  private var repeatMode: RepeatMode = .off

  /// `true` while a fade ramp is scheduled / in flight.
  private var fadeInFlight: Bool = false

  /// Periodic-time-observer + the AVPlayer it was registered
  /// against. `AVPlayer.removeTimeObserver` raises
  /// `NSInvalidArgumentException` when called on a different
  /// instance than the one that issued the token, so the player
  /// is captured alongside the token. Removed on pause / stop /
  /// release / removeAllItems, and by setItems / remove when they drain
  /// the engine; re-installed on play; stopped and restarted across
  /// `completeCrossfade`.
  private var armingObserver: (player: AVPlayer, token: Any)?

  /// `true` after [armCrossfade] has prerolled standby for the
  /// upcoming fade.
  private var standbyArmed: Bool = false

  /// Wall-clock task that fires `completeCrossfade` after the
  /// fade duration. Stored so `cancelFadeInternal` /
  /// `release` can cancel it before a stale completion runs
  /// against a fresh fade.
  private var fadeCompletionTask: Task<Void, Never>?

  /// Audio-mix provider set via `setAudioMixProvider` — the
  /// `AudioTapProvider` shared by `Equalizer` and `Visualizer`. Each
  /// AVPlayerItem's `AVAudioMix` comes from its `audioMix(for:)`.
  private var mixProvider: AudioMixProvider?


  /// Released-once flag so `release()` is idempotent.
  private var released: Bool = false

  /// One-shot KVO on the standby `AVPlayer.status`. Set during
  /// [armCrossfade] when `replaceCurrentItem` was just called and
  /// the asset hasn't reached `.readyToPlay` yet — `preroll(atRate:)`
  /// raises `NSInternalInconsistencyException` if invoked before
  /// the status flip per `AVPlayer.h`. Invalidated on
  /// [cancelFadeInternal] / [release] / when the readiness fires.
  private var standbyReadyObserver: NSKeyValueObservation?

  /// Wall-clock fallback used when standby never reaches
  /// `.readyToPlay` (asset load failure / network error). Lets the
  /// arm path bail cleanly instead of stranding `standbyArmed = true`
  /// against a broken standby. 5s covers local + warm cache; for
  /// streaming the consumer would have already failed by then via
  /// the leading-leg error path.
  private var standbyReadyTimeout: DispatchWorkItem?

  /// Deadline on the whole arm chain, not just the wait for the standby leg's
  /// status.
  ///
  /// `standbyReadyTimeout` is cancelled the moment the status flips, and the
  /// work after that — loading the incoming asset's `tracks` key — has no
  /// bound of its own. Without this deadline a remote asset that never
  /// resolves leaves `standbyArmed` set, and the engine work deferred on
  /// that flag never flushes.
  private var armDeadline: DispatchWorkItem?

  /// Whether volume ramps have actually been written onto either leg's mix
  /// parameters. Only a written ramp can leave the mix in the state
  /// `cancelFadeInternal`'s reset recovers from, and being armed is not the
  /// same thing: the chain can be armed and still waiting for the incoming
  /// asset, with nothing written yet.
  private var rampsScheduled = false

  /// Generation counter incremented at every [awaitStandbyReady]
  /// entry. Closures captured by the KVO observer + the timeout
  /// `DispatchWorkItem` carry their generation value — if a new
  /// arm cycle increments the counter while a stale `ownerQueue`
  /// hop is pending, the gate inside the closure short-circuits.
  /// Defends against a narrow race where the original arm's
  /// `.readyToPlay` callback fires AFTER `cancelFadeInternal` +
  /// a fresh `armCrossfade` re-flipped `standbyArmed` to true.
  private var standbyReadyGen: Int = 0

  // MARK: - Engine event observers

  /// `\.timeControlStatus` KVO on the leading leg + the player it
  /// was registered against. Re-installed on every role swap.
  /// Fires `engineStateMaybeChanged`.
  private var leadingTimeControlObserver: NSKeyValueObservation?

  /// `\.currentItem.tracks` KVO on the leading leg's currentItem.
  /// Fires `engineActiveItemFormatChanged` once tracks populate.
  /// Re-installed on role swap and on `setItems`.
  private var leadingItemTracksObserver: NSKeyValueObservation?

  /// `\.currentItem.status` KVO on the leading leg's currentItem.
  /// Routes `.failed` through `engineDidFailWithError`.
  private var leadingItemStatusObserver: NSKeyValueObservation?

  /// `\.isPlaybackBufferEmpty` / `\.isPlaybackLikelyToKeepUp` /
  /// `\.isPlaybackBufferFull` KVO on the leading leg's currentItem. Each fires
  /// `engineBufferStateMaybeChanged` so a fast local reload's empty→full edge
  /// reaches the discrete buffer-state surface — the gapless engine gets the
  /// same from its own item KVO. Re-installed on role swap and on `setItems`.
  private var leadingItemBufferEmptyObserver: NSKeyValueObservation?
  private var leadingItemBufferKeepUpObserver: NSKeyValueObservation?
  private var leadingItemBufferFullObserver: NSKeyValueObservation?

  /// Block-based `NotificationCenter` observer tokens for the
  /// AVPlayerItem lifecycle notifications. Filtered inside the
  /// block to leading-leg only. Removed in `release`.
  private var itemDidPlayToEndToken: NSObjectProtocol?
  private var itemFailedToPlayToEndToken: NSObjectProtocol?

  // MARK: - Init

  /// `allowsExternalPlayback` is `PlayerConfig.allowsExternalPlayback`, applied to both legs.
  init(
    crossfadeDurationSeconds: TimeInterval,
    confinedTo ownerQueue: DispatchQueue,
    allowsExternalPlayback: Bool
  ) {
    precondition(
      crossfadeDurationSeconds >= 0,
      "crossfadeDurationSeconds must be non-negative"
    )
    self.crossfadeDurationSeconds = crossfadeDurationSeconds
    self.ownerQueue = ownerQueue

    let p1 = AVPlayer()
    p1.automaticallyWaitsToMinimizeStalling = true
    p1.allowsExternalPlayback = allowsExternalPlayback
    p1.volume = userVolume
    let p2 = AVPlayer()
    p2.automaticallyWaitsToMinimizeStalling = true
    p2.allowsExternalPlayback = allowsExternalPlayback
    p2.volume = 0  // standby starts silent

    self.playerA = p1
    self.playerB = p2

    let monitor = OutputRouteMonitor()
    self.routeMonitor = monitor
    monitor.start()
    monitor.onRouteBecameNonMixable = { [weak self] in
      // Mid-fade route change: cancel the in-flight fade so iOS
      // doesn't arbitrarily silence one of the two players. The
      // outgoing leg snaps to userVolume; the standby drops its
      // item.
      guard let self else { return }
      self.ownerQueue.async { self.cancelFade() }
    }

    // Lib-wide notifications fire for any AVPlayerItem; the
    // closure filters by `object === leadingPlayer.currentItem`
    // so events on the standby leg's preroll item don't surface.
    itemDidPlayToEndToken = NotificationCenter.default.addObserver(
      forName: .AVPlayerItemDidPlayToEndTime,
      object: nil,
      // `nil` delivers on the posting thread — AVFoundation's, not ours — so
      // the hop onto the owner's queue is explicit below.
      queue: nil
    ) { [weak self] note in
      guard let self else { return }
      self.ownerQueue.async { self.handleItemDidPlayToEnd(note) }
    }
    itemFailedToPlayToEndToken = NotificationCenter.default.addObserver(
      forName: .AVPlayerItemFailedToPlayToEndTime,
      object: nil,
      // `nil` delivers on the posting thread — AVFoundation's, not ours — so
      // the hop onto the owner's queue is explicit below.
      queue: nil
    ) { [weak self] note in
      guard let self else { return }
      self.ownerQueue.async { self.handleItemFailedToPlayToEnd(note) }
    }
  }

  // MARK: - Lifecycle

  func release() {
    guard !released else { return }
    released = true
    // Release runs as part of an engine swap or destroy; the new
    // engine has already taken over the canonical active-track
    // state, so a mid-fade revert emit here would surface a
    // spurious onTrackChange to the OUTGOING leg of a fade that
    // the consumer no longer cares about.
    cancelFadeInternal(emitRevert: false)
    stopArmingObserver()
    teardownLeadingObservers()
    if let token = itemDidPlayToEndToken {
      NotificationCenter.default.removeObserver(token)
      itemDidPlayToEndToken = nil
    }
    if let token = itemFailedToPlayToEndToken {
      NotificationCenter.default.removeObserver(token)
      itemFailedToPlayToEndToken = nil
    }
    routeMonitor.stop()
    AVQueueBuilder.cancelLoading(dropping: queue)
    leadingPlayer.replaceCurrentItem(with: nil)
    standbyPlayer.replaceCurrentItem(with: nil)
    queue.removeAll()
    currentIndex = -1
    mixProvider = nil
    delegate = nil
  }

  // MARK: - Queue mutation

  func setItems(
    _ items: [AVPlayerItem],
    startIndex: Int,
    startPositionSeconds: TimeInterval
  ) {
    // `replaceCurrentItem` below drops the leading leg to rate 0, so a
    // rebuild issued mid-playback would silently pause. Capture the intent
    // and restore the rate after the new item is seated — the same contract
    // `GaplessEngine.setItems` upholds across its own rebuild. The run
    // resumes exactly when a `play()` is still standing over it.
    let shouldResume = wantsPlayback
    cancelFadeInternal()
    // The outgoing slice is discarded here. Anything the incoming set reuses
    // keeps its loads — a cancelled asset never becomes playable, and this
    // engine deliberately rebuilds items on reused assets at seek, remove
    // and fade-arm.
    AVQueueBuilder.cancelLoading(dropping: queue, keeping: items)
    queue = items
    teardownLeadingObservers()
    if items.isEmpty || startIndex < 0 || startIndex >= items.count {
      currentIndex = -1
      stopArmingObserver()
      leadingPlayer.pause()
      leadingPlayer.replaceCurrentItem(with: nil)
      standbyPlayer.replaceCurrentItem(with: nil)
      return
    }
    currentIndex = startIndex
    let activeItem = items[startIndex]
    applyMixIfNeeded(to: activeItem)
    applyPitchAlgorithm(to: activeItem)
    leadingPlayer.replaceCurrentItem(with: activeItem)
    standbyPlayer.replaceCurrentItem(with: nil)
    if startPositionSeconds > 0 {
      activeItem.seek(
        to: CMTime(seconds: startPositionSeconds, preferredTimescale: 600),
        completionHandler: nil
      )
    }
    if shouldResume {
      resumeLeadingRespectingTap()
    }
    installLeadingObservers()
    delegate?.engineDidTransitionTrack(self)
  }

  @discardableResult
  func insertItems(
    _ items: [AVPlayerItem],
    after: AVPlayerItem?
  ) -> Bool {
    if items.isEmpty { return true }
    // Queue mutation invalidates the standby leg's pre-loaded
    // future. Cancel any in-flight fade before mutating so the
    // standby's stale state doesn't race a swap against the
    // post-mutation index space.
    cancelFadeInternal()
    let insertOffset: Int
    if let after, let idx = queue.firstIndex(where: { $0 === after }) {
      insertOffset = idx + 1
    } else if after == nil {
      insertOffset = queue.count
    } else {
      // Reference item not in the engine's queue — fall back to
      // a full rebuild signal so the caller can call setItems.
      return false
    }
    queue.insert(contentsOf: items, at: insertOffset)
    return true
  }

  func remove(_ item: AVPlayerItem) {
    guard let idx = queue.firstIndex(where: { $0 === item }) else { return }
    cancelFadeInternal()
    queue.remove(at: idx)
    if idx == currentIndex {
      // Removing the leading leg's current item — auto-advance
      // to whatever follows or drain.
      if currentIndex >= queue.count {
        currentIndex = queue.count - 1
      }
      if currentIndex < 0 {
        stopArmingObserver()
        leadingPlayer.pause()
        leadingPlayer.replaceCurrentItem(with: nil)
      } else {
        let shouldResume = wantsPlayback
        teardownLeadingObservers()
        // Never the stored instance: the slot that shifts in here may be the
        // item the arm attached to the standby leg, and attaching an item
        // still associated with another player raises an exception Swift
        // cannot catch.
        let next = rebuiltItem(at: currentIndex)
        applyMixIfNeeded(to: next)
        applyPitchAlgorithm(to: next)
        leadingPlayer.replaceCurrentItem(with: next)
        if shouldResume {
          resumeLeadingRespectingTap()
        }
        installLeadingObservers()
      }
    } else if idx < currentIndex {
      currentIndex -= 1
    }
  }

  func removeAllItems() {
    // Nothing left to want: an intent left standing here resumes the next run
    // installed rather than waiting to be asked.
    stopArmingObserver()
    // Detaching the item does not stop the player — `replaceCurrentItem(with:
    // nil)` leaves the rate where it was, so the next item seated on this leg
    // would start on contact. Draining parks it.
    leadingPlayer.pause()
    cancelFadeInternal()
    AVQueueBuilder.cancelLoading(dropping: queue)
    queue.removeAll()
    currentIndex = -1
    leadingPlayer.replaceCurrentItem(with: nil)
    standbyPlayer.replaceCurrentItem(with: nil)
  }

  func canInsert(_ item: AVPlayerItem, after: AVPlayerItem?) -> Bool {
    if let after {
      return queue.contains(where: { $0 === after })
    }
    return true  // append-at-end always permitted
  }

  // MARK: - Transport

  func play() {
    DiagnosticLog.player.log("engine play (crossfade)")
    // Start the arming observer first so `wantsPlayback` is true before the
    // leading `.readyToPlay` observer might resume a leg held for its tap.
    // `resumeLeadingRespectingTap` restores the user's speed (AVPlayer.play()
    // would reset rate to 1.0).
    startArmingObserver()
    resumeLeadingRespectingTap()
  }

  func pause() {
    DiagnosticLog.player.log("engine pause (crossfade)")
    cancelFadeInternal()
    stopArmingObserver()
    leadingPlayer.pause()
    standbyPlayer.pause()
  }

  func stop() {
    DiagnosticLog.player.log("engine stop (crossfade)")
    cancelFadeInternal()
    stopArmingObserver()
    leadingPlayer.pause()
    standbyPlayer.pause()
    // Match GaplessEngine.stop(): pause + seek the CURRENT (leading) item to 0
    // and KEEP it. Replacing it with nil leaves no currentItem — so position
    // never resets to observe, the periodic time observer never fires the
    // post-stop progress tick consumers wait for, and getState() drops to NONE
    // instead of PAUSED. Drain only the standby (prerolled next-track) leg;
    // it's stale once stopped and re-arms on the next play.
    leadingPlayer.currentItem?.seek(to: .zero, completionHandler: nil)
    standbyPlayer.replaceCurrentItem(with: nil)
  }

  func seek(toIndex index: Int, position: TimeInterval) {
    guard index >= 0, index < queue.count else { return }
    // Read playing intent before the fade-cancel: a cross-attach
    // (replaceCurrentItem) drops the leading player to rate 0, so the
    // new leg must be resumed at the user's speed below to match the
    // gapless skip (which restores rate across its queue rebuild).
    let shouldResume = wantsPlayback
    // Seek is itself a track-change: the engineDidTransitionTrack
    // fire at the end of this method is the authoritative active-
    // track flip for the new index. Suppress the cancel-revert
    // emit so a mid-fade seek doesn't dispatch an intermediate
    // `engineCrossfadeDidCancel` (a revert to the outgoing leg)
    // before the seek's own track-change lands.
    cancelFadeInternal(emitRevert: false)
    teardownLeadingObservers()
    currentIndex = index
    let existing = queue[index]
    // Same-instance no-op: target is already leadingPlayer's
    // currentItem (seek-within-current-track, or seekTo right
    // after setItems). Calling replaceCurrentItem with the same
    // instance triggers a redundant KVO cascade and delays
    // duration re-propagation by seconds; skip the re-attach.
    if leadingPlayer.currentItem === existing {
      if position > 0 {
        existing.seek(
          to: CMTime(seconds: position, preferredTimescale: 600),
          completionHandler: nil
        )
      } else {
        // Same-instance keeps any prior playhead; force-reset to
        // zero to honour the seek contract.
        existing.seek(to: .zero, completionHandler: nil)
      }
      // No re-attach here, but an item that reached its end has parked its leg
      // at rate 0, and seeking back into it must play from where it lands.
      if shouldResume {
        resumeLeadingRespectingTap()
      }
      standbyArmed = false
      installLeadingObservers()
      delegate?.engineDidTransitionTrack(self)
      return
    }
    // Cross-attach case: target is associated with another
    // AVPlayer (typically standbyPlayer post fade-arm). Rebuild
    // as a fresh AVPlayerItem on the existing asset.
    // AVPlayer.replaceCurrentItem(with:) raises NSException
    // (uncatchable in Swift → process abort) if the item is still
    // associated with another AVPlayer; AVPlayerItem.player
    // releases asynchronously after the previous player calls
    // replaceCurrentItem(with: nil) and there's no synchronous
    // way to wait for the release, no public API to inspect the
    // association. Mirror AVQueueBuilder.makeItem's construction
    // (`automaticallyLoadedAssetKeys` + buffer pin) and carry
    // queueItemId across so TrackPlayer.matchTrackIndex can still
    // resolve the new item back to its queue position.
    let target = AVPlayerItem(
      asset: existing.asset,
      automaticallyLoadedAssetKeys: ["playable", "duration"]
    )
    target.preferredForwardBufferDuration = 0.0
    target.queueItemId = existing.queueItemId
    target.sourceURL = existing.sourceURL
    target.suppliedReplayGain = existing.suppliedReplayGain
    // The routing stamp lives on the item instance (associated object), not the
    // reused asset, so carry it too — without it source classification reads the
    // default `false` and reports a cache-served track as `.streaming`.
    target.isRoutedThroughReadThroughServer = existing.isRoutedThroughReadThroughServer
    // Replace the queue slot with the rebuilt item — any cached
    // AVPlayerItem reference held by a future callsite (e.g. an
    // armCrossfade re-arming on this index) must read queue[index]
    // afresh; the prior `existing` instance is now orphaned and
    // its prior-player association will release asynchronously.
    queue[index] = target
    applyMixIfNeeded(to: target)
    applyPitchAlgorithm(to: target)
    leadingPlayer.replaceCurrentItem(with: target)
    if position > 0 {
      target.seek(
        to: CMTime(seconds: position, preferredTimescale: 600),
        completionHandler: nil
      )
    }
    if shouldResume {
      // replaceCurrentItem leaves the player at rate 0 — resume respecting a
      // wanted tap (mirrors play() / seek) so a skip while playing at a
      // non-default speed doesn't fall back to 1.0x and the tap survives.
      resumeLeadingRespectingTap()
    }
    standbyArmed = false
    installLeadingObservers()
    delegate?.engineDidTransitionTrack(self)
  }

  func skipToNext() {
    let next = currentIndex + 1
    if next < queue.count {
      seek(toIndex: next, position: 0)
    }
  }

  func skipToPrevious() {
    let prev = currentIndex - 1
    if prev >= 0 {
      seek(toIndex: prev, position: 0)
    } else {
      leadingPlayer.currentItem?.seek(to: .zero, completionHandler: nil)
    }
  }

  // MARK: - Config

  func setVolume(_ volume: Float) {
    userVolume = volume
    leadingPlayer.volume = volume
    // Standby stays silent outside fade window.
  }

  func setPlaybackSpeed(_ speed: Float) {
    userPlaybackSpeed = speed
    leadingPlayer.rate = speed
    // Crossing the 1.0 boundary changes the resolved algorithm; restamp
    // both legs (standby prerolls at this rate).
    reapplyPitchToCurrentLegs()
  }

  func seedPlaybackSpeed(_ speed: Float) {
    // Persist only; `play()` applies it. Does not touch the leg rate,
    // so it won't start a paused engine.
    userPlaybackSpeed = speed
    reapplyPitchToCurrentLegs()
  }

  func setRepeatMode(_ mode: RepeatMode) {
    // `.queue` wrap is still driven by TrackPlayer rebuilding the queue
    // (as on gapless). `.track` (repeat-one) is handled here because
    // AVPlayer has no native repeat: the engine suppresses the fade and
    // loops the current item on natural end.
    repeatMode = mode
  }

  // MARK: - State

  /// During a crossfade the conceptual "active" track flips to the
  /// standby leg the moment the fade begins (matches the JS-facing
  /// `onTrackChange` + lock-screen / Now Playing flip the delegate
  /// performs in response to `engineCrossfadeDidBegin`). All four
  /// accessors below honour this: when `fadeInFlight`, they read
  /// from `standbyPlayer` (the incoming leg) so `useProgress`,
  /// seek clamps, preserve-current, and index lookups all see the
  /// incoming track. Engine-internal code reaches `leadingPlayer`
  /// / `currentIndex` directly.
  var currentMediaItemIndex: Int {
    if queue.isEmpty { return -1 }
    return fadeInFlight ? currentIndex + 1 : currentIndex
  }

  var currentPositionSeconds: TimeInterval {
    let player = fadeInFlight ? standbyPlayer : leadingPlayer
    let t = player.currentTime()
    return t.isNumeric ? CMTimeGetSeconds(t) : 0
  }

  var currentDurationSeconds: TimeInterval {
    let player = fadeInFlight ? standbyPlayer : leadingPlayer
    guard let d = player.currentItem?.duration, d.isNumeric else { return 0 }
    return CMTimeGetSeconds(d)
  }

  var bufferedPositionSeconds: TimeInterval {
    let player = fadeInFlight ? standbyPlayer : leadingPlayer
    guard let ranges = player.currentItem?.loadedTimeRanges,
          let last = ranges.last?.timeRangeValue else { return 0 }
    return CMTimeGetSeconds(last.start + last.duration)
  }

  var isPlaying: Bool {
    // Whether the outgoing leg is audible right now — not whether the engine
    // was asked to play. It reads false mid-fade once the leading leg's item
    // has ended, and false while a leg is buffering, so a caller deciding
    // whether to restore a rate wants `wantsPlayback` instead. The
    // `timeControlStatus` accessor below reads the active leg (standby while
    // fading) to match `currentMediaItem` for state derivation.
    leadingPlayer.timeControlStatus == .playing
  }

  var timeControlStatus: AVPlayer.TimeControlStatus {
    // Mirror `currentMediaItem`'s leg selection: during a fade the standby
    // (incoming) leg is the conceptually-active track, so its status is the
    // one a consumer's spinner/state should reflect.
    let player = fadeInFlight ? standbyPlayer : leadingPlayer
    return player.timeControlStatus
  }

  var currentMediaItem: AVPlayerItem? {
    let player = fadeInFlight ? standbyPlayer : leadingPlayer
    return player.currentItem
  }

  /// The outgoing leg's item for the whole fade — `currentMediaItem` switches
  /// to the incoming leg as soon as the fade starts, which is the wrong end to
  /// anchor an installed run on.
  var leadingQueueItemId: String? {
    leadingPlayer.currentItem?.queueItemId
  }

  var isFadePendingOrActive: Bool { standbyArmed || fadeInFlight }

  /// Both legs while a fade is scheduled or running: the standby leg carries a
  /// prerolled item that is about to be heard, and a mutation that removes it
  /// out from under the arm chain strands the fade on an item the queue no
  /// longer has.
  var attachedQueueItemIds: [String] {
    [leadingPlayer.currentItem?.queueItemId, standbyPlayer.currentItem?.queueItemId]
      .compactMap { $0 }
  }

  var allMediaItems: [AVPlayerItem] {
    queue
  }

  // MARK: - Effect chain

  func setAudioMixProvider(_ provider: AudioMixProvider?) {
    mixProvider = provider
    refreshAllItemMixes()
  }

  /// Resolve the item for [index] as a **fresh** `AVPlayerItem`.
  ///
  /// Never hands back the stored instance. Attaching an `AVPlayerItem` that is
  /// still associated with another `AVPlayer` raises an `NSException` that
  /// Swift cannot catch, and the association is released asynchronously with
  /// no API to observe it — so an item that was on a leg a moment ago is not
  /// safe to attach, and there is no way to ask whether it is. `seek(toIndex:)`
  /// rebuilds for the same reason.
  private func rebuiltItem(at index: Int) -> AVPlayerItem {
    let existing = queue[index]
    let rebuilt = AVPlayerItem(
      asset: existing.asset,
      automaticallyLoadedAssetKeys: ["playable", "duration"]
    )
    rebuilt.preferredForwardBufferDuration = 0.0
    rebuilt.queueItemId = existing.queueItemId
    rebuilt.sourceURL = existing.sourceURL
    rebuilt.suppliedReplayGain = existing.suppliedReplayGain
    // Carry the read-through routing stamp (an item-instance associated object)
    // so source classification keeps reporting `.cached` across the rebuild.
    rebuilt.isRoutedThroughReadThroughServer = existing.isRoutedThroughReadThroughServer
    queue[index] = rebuilt
    return rebuilt
  }

  func refreshAllItemMixes() {
    // Catch-up walk: only fill items that lack an `audioMix`. Items
    // that already carry a tap are left alone (re-assigning on an
    // already-rendering item allocates fresh `MTAudioProcessingTap`
    // + `EQProcessor` cascade resources that AVPlayer silently
    // drops, leaking them to ARC). See `GaplessEngine.refreshAllItemMixes`
    // for the full rationale.
    if let leading = leadingPlayer.currentItem, leading.audioMix == nil {
      leading.audioMix = mixProvider?.audioMix(for: leading)
    }
    if let standby = standbyPlayer.currentItem, standby.audioMix == nil {
      standby.audioMix = mixProvider?.audioMix(for: standby)
    }
    // `audioMix(for:)` reports the asset it built for, so walking the standby
    // leg last leaves the *upcoming* track standing as the active asset. Say
    // which one is actually audible.
    mixProvider?.noteActiveAsset(leadingPlayer.currentItem?.asset)
  }

  private func applyMixIfNeeded(to item: AVPlayerItem) {
    guard let provider = mixProvider,
          let mix = provider.audioMix(for: item) else { return }
    item.audioMix = mix
  }

  /// Resume the leading leg for a fresh seat / resume, holding it at rate 0
  /// when a consumer wants the tap and the item is not yet ready to render.
  ///
  /// AVPlayer honours `audioMix` only before an item renders; a mix set once
  /// the leg is playing is silently dropped and the equaliser / visualizer tap
  /// never prepares. When a tap is wanted we keep a not-yet-ready leg at rate 0
  /// and let the leading `.readyToPlay` observer load tracks, install the mix,
  /// then resume the held leg (see `installLeadingObservers` and
  /// `installLeadingTapMixThenResume`). When the item is already `.readyToPlay`
  /// the `.new`-only status observer will not fire, so drive that same path
  /// here. The common no-tap path — and any provider without a consumer
  /// model — resumes immediately, unchanged.
  ///
  /// Callers hold a live playback intent. Used for fresh seats and `play()`,
  /// and for the same-instance seek so it cannot start a leg a preceding seat
  /// is holding. NOT used by `completeCrossfade` (it promotes an
  /// already-rendering leg — holding would pause live audio) or the repeat-one
  /// loop (the same already-ready item).
  private func resumeLeadingRespectingTap() {
    let player = leadingPlayer
    guard let item = player.currentItem else {
      player.rate = userPlaybackSpeed
      return
    }
    guard mixProvider?.anyConsumerWantsTap() == true else {
      player.rate = userPlaybackSpeed
      return
    }
    // A deterministically-missing local file never reaches `.readyToPlay`, and
    // `installLeadingObservers` surfaces its failure without installing a
    // status observer — so nothing would resume a held leg. Resume directly:
    // it cannot render a tap anyway, and the failure path drives the retry.
    if let asset = item.asset as? AVURLAsset, asset.url.isFileURL,
       !FileManager.default.fileExists(atPath: asset.url.path) {
      player.rate = userPlaybackSpeed
      return
    }
    if item.status == .readyToPlay {
      // Already ready: the `.new`-only status observer will not fire, so drive
      // the tracks-load → mix-install → resume here. The leg is still held at
      // rate 0 (not rendering) until that completes.
      installLeadingTapMixThenResume(item: item)
      return
    }
    // Not yet ready: leave the leg at rate 0. `installLeadingObservers` arms
    // `autoWaits = true` for a not-yet-ready item and its `.readyToPlay`
    // observer installs the mix and then resumes this held leg.
  }

  /// Load the leading item's `tracks` key, then install its tap-mix and resume
  /// a leg held for the tap. `.readyToPlay` does not guarantee the asset's
  /// `tracks` key is loaded — that is a separate async load — and
  /// `audioMix(for:)` yields nil until it is (the reason the arm path loads
  /// tracks before attaching the standby's mix). Loading first guarantees the
  /// mix builds, so the tap is live before the first sample. The leg stays held
  /// (rate 0 under a live play-intent, `autoWaits` true) until the completion
  /// installs the mix, permits render, and resumes it.
  ///
  /// The load is bounded by `armDeadlineSeconds`, matching the standby arm
  /// deadline: a stall after `.readyToPlay` must not leave the held leg silent
  /// forever, so on timeout (or a load failure) the leg resumes untapped. The
  /// completion installs the mix only while the leg is still held (rate 0):
  /// once it has resumed, AVPlayer drops a mix set on a rendering item and
  /// leaks its tap cascade (see `refreshAllItemMixes`), so a consumer that
  /// subscribed after an untapped seat is left to the format-changed catch-up.
  /// Re-checks the engine is live and the item is still the captured leg's
  /// current one across the load.
  private func installLeadingTapMixThenResume(item: AVPlayerItem) {
    let player = leadingPlayer
    let asset = item.asset
    Task { [weak self] in
      await withTaskGroup(of: Void.self) { group in
        group.addTask { _ = try? await asset.load(.tracks) }
        group.addTask {
          try? await Task.sleep(
            nanoseconds: UInt64(Self.armDeadlineSeconds * 1_000_000_000))
        }
        _ = await group.next()
        group.cancelAll()
      }
      guard let self else { return }
      await self.onOwnerQueue {
        guard !self.released, player.currentItem === item else { return }
        if player.rate == 0 {
          self.refreshAllItemMixes()
        }
        player.automaticallyWaitsToMinimizeStalling = false
        if self.wantsPlayback, player.rate == 0 {
          player.rate = self.userPlaybackSpeed
        }
      }
    }
  }

  func setPitchCorrectionMode(_ mode: PitchCorrectionMode) {
    pitchCorrectionMode = mode
    reapplyPitchToCurrentLegs()
  }

  /// Stamp the resolved algorithm on `item`. Idempotent: a no-op when
  /// the item already carries it, so re-applying on a rendering item
  /// doesn't churn the audio graph.
  private func applyPitchAlgorithm(to item: AVPlayerItem) {
    let algo = PitchAlgorithm.resolve(
      mode: pitchCorrectionMode,
      speed: userPlaybackSpeed
    )
    if item.audioTimePitchAlgorithm != algo {
      item.audioTimePitchAlgorithm = algo
    }
  }

  private func reapplyPitchToCurrentLegs() {
    if let leading = leadingPlayer.currentItem { applyPitchAlgorithm(to: leading) }
    if let standby = standbyPlayer.currentItem { applyPitchAlgorithm(to: standby) }
  }

  // MARK: - Equal-power gain math

  /// Equal-power gain pair for fade progress `t ∈ [0, 1]`.
  /// `(outgoing, incoming)` — sum-of-squares stays at 1.0
  /// across the ramp so perceived loudness is constant.
  static func equalPowerGains(t: Float) -> (Float, Float) {
    let clamped = max(0, min(1, t))
    let angle = clamped * Float.pi / 2
    return (cos(angle), sin(angle))
  }

  /// Approximate the equal-power curve via [subRampCount]
  /// short linear segments. Returns the gain pair at each
  /// step's start. Caller schedules each segment via
  /// `setVolumeRamp(fromStartVolume:toEndVolume:timeRange:)`.
  ///
  /// `setVolumeRamp` is linear-only per the AVFoundation API;
  /// staircasing the cos/sin curve into N segments is the
  /// supported approximation.
  static func equalPowerSubRamps(
    durationSeconds: TimeInterval,
    steps: Int = subRampCount
  ) -> [(timeRange: CMTimeRange, outgoing: (start: Float, end: Float), incoming: (start: Float, end: Float))] {
    guard steps > 0, durationSeconds > 0 else { return [] }
    let stepSeconds = durationSeconds / Double(steps)
    var ramps: [(CMTimeRange, (Float, Float), (Float, Float))] = []
    for i in 0 ..< steps {
      let tStart = Float(i) / Float(steps)
      let tEnd = Float(i + 1) / Float(steps)
      let (outStart, inStart) = equalPowerGains(t: tStart)
      let (outEnd, inEnd) = equalPowerGains(t: tEnd)
      let cmStart = CMTime(seconds: Double(i) * stepSeconds, preferredTimescale: 600)
      let cmEnd = CMTime(seconds: Double(i + 1) * stepSeconds, preferredTimescale: 600)
      let range = CMTimeRange(start: cmStart, end: cmEnd)
      ramps.append((range, (outStart, outEnd), (inStart, inEnd)))
    }
    return ramps
  }

  /// Auto-clamp the configured fade duration so the ramp never
  /// overflows either leg's track length. Mirrors the Android
  /// `effectiveCrossfadeMs`.
  func effectiveCrossfadeSeconds(
    leadingDurationSeconds: TimeInterval,
    standbyDurationSeconds: TimeInterval
  ) -> TimeInterval {
    let configured = crossfadeDurationSeconds
    let leadingHalf = leadingDurationSeconds > 0
      ? leadingDurationSeconds / 2 : configured
    let standbyHalf = standbyDurationSeconds > 0
      ? standbyDurationSeconds / 2 : configured
    return max(0, min(min(configured, leadingHalf), standbyHalf))
  }

  // MARK: - Fade cancel

  /// Cancel any in-flight fade — strip the volume ramp from
  /// both legs' current `audioMix`, snap leading to userVolume,
  /// drop the standby's currentItem so its preroll bytes are
  /// released. Internal entry point used by `pause` / `seek` /
  /// `setItems` and by the route-monitor non-mixable callback.
  func cancelFade() {
    cancelFadeInternal()
  }

  // MARK: - Boundary arming

  /// Whether the transport wants playback, independent of which leg is
  /// producing sound.
  ///
  /// The arming observer is the intent: `play()` installs it; `pause()`,
  /// `stop()` and `release()` remove it; every path that drains the engine
  /// removes it, because a run with nothing in it is not a run being played;
  /// and `completeCrossfade` stops and restarts it across the role flip,
  /// synchronously, so no reader sees the gap.
  ///
  /// A leg's rate or status cannot answer this. Mid-fade the outgoing leading
  /// leg has already stopped while the incoming one plays, and an item that
  /// has reached its end leaves its leg at rate 0 with the transport still
  /// wanting to play. Restoring the rate from either of those leaves the
  /// engine paused against the consumer's intent.
  private var wantsPlayback: Bool { armingObserver != nil }

  /// Boundary-arming periodic time observer. Watches the leading
  /// leg's time at 200ms cadence; when within
  /// `effectiveCrossfadeSeconds + prerollLeadSeconds` of track end,
  /// prerolls standby + schedules the equal-power ramp on both
  /// legs' `AVMutableAudioMixInputParameters`.
  private func startArmingObserver() {
    if armingObserver != nil { return }
    let player = leadingPlayer
    let interval = CMTime(seconds: 0.2, preferredTimescale: CMTimeScale(NSEC_PER_SEC))
    let token = player.addPeriodicTimeObserver(
      forInterval: interval,
      queue: ownerQueue
    ) { [weak self] _ in
      self?.maybeArmCrossfade()
    }
    armingObserver = (player, token)
  }

  private func stopArmingObserver() {
    if let observer = armingObserver {
      observer.player.removeTimeObserver(observer.token)
    }
    armingObserver = nil
  }

  private func maybeArmCrossfade() {
    if standbyArmed { return }
    if fadeInFlight { return }
    if !routeMonitor.isCurrentRouteMixable { return }  // hard-cut on AirPlay/CarPlay
    if crossfadeDurationSeconds <= 0 { return }
    // Repeat-one loops the current item in place (see
    // `handleItemDidPlayToEnd`); never arm a look-ahead fade, which
    // would advance past the track the user asked to repeat.
    if repeatMode == .track { return }
    if currentIndex < 0 || currentIndex >= queue.count - 1 { return }  // last track or empty
    // Only a leg that is rendering arms. A rate written onto a leg sitting at
    // its end — a play() after the queue ended — reports the end position
    // without moving, and arming there would fade into whatever was queued
    // behind the finished track. A leg stalled inside the arm window waits
    // for the tick on which it resumes, so the standby prerolls from then.
    if leadingPlayer.timeControlStatus != .playing { return }
    guard let leading = leadingPlayer.currentItem else { return }
    let duration = leading.duration
    if !duration.isNumeric { return }
    let durationSeconds = CMTimeGetSeconds(duration)
    if durationSeconds <= 0 { return }
    let position = CMTimeGetSeconds(leading.currentTime())
    // Standby duration unknown until prepared — pass 0 so
    // effectiveCrossfadeSeconds falls through to the configured
    // value with leading-half clamp only.
    let effective = effectiveCrossfadeSeconds(
      leadingDurationSeconds: durationSeconds,
      standbyDurationSeconds: 0
    )
    let armThreshold = durationSeconds - effective - Self.prerollLeadSeconds
    if position < armThreshold { return }
    standbyArmed = true
    armCrossfade(durationSeconds: effective)
  }

  /// Preload the next queue item onto standby + schedule the
  /// equal-power N-sub-ramp `setVolumeRamp` schedule on both legs'
  /// `AVMutableAudioMixInputParameters`. Caller (the periodic
  /// observer) has already validated route + index + arming state.
  ///
  /// `AVPlayer.preroll(atRate:)` has TWO documented preconditions
  /// per Apple's `AVPlayer.h`:
  ///   1. `AVPlayer.status == .readyToPlay`
  ///   2. `automaticallyWaitsToMinimizeStalling == false`
  ///
  /// Violating either raises `NSInternalInconsistencyException`
  /// synchronously. After `replaceCurrentItem` the status flip is
  /// asynchronous — the asset has to load tracks/duration. KVO-
  /// wait until the status reaches `.readyToPlay` before invoking
  /// `preroll`; `runFade` chains through the readiness callback so
  /// the timing semantics (preroll → ramp schedule → wall-clock
  /// completion) are preserved. No seek before the attach:
  /// `replaceCurrentItem` resets the item's playhead.
  private func armCrossfade(durationSeconds: TimeInterval) {
    // Validate the index here rather than trusting the caller's earlier
    // check: `currentIndex + 1` is a trapping add, so a `currentIndex` of
    // `Int.max` crashes the process before any bounds check can run.
    // `NSNotFound` is `Int.max` on 64-bit, so a not-found sentinel that
    // reaches this field is fatal rather than merely wrong.
    let idx = currentIndex
    let count = queue.count
    guard idx >= 0, idx < count - 1 else {
      standbyArmed = false
      armDeadline?.cancel()
      armDeadline = nil
      return
    }
    let nextIdx = idx + 1
    let nextItem = rebuiltItem(at: nextIdx)
    applyPitchAlgorithm(to: nextItem)
    let standby = standbyPlayer
    standby.replaceCurrentItem(with: nextItem)
    let priorAutoStall = standby.automaticallyWaitsToMinimizeStalling
    awaitStandbyReady(standby) { [weak self] in
      guard let self else { return }
      if self.released || !self.standbyArmed { return }
      // The status wait's own timeout is cancelled by the flip that got us
      // here; everything below is unbounded without this.
      self.startArmDeadline()
      // Explicit `tracks` key load on the freshly-replaced standby
      // asset BEFORE preroll. `AVPlayerItem.status == .readyToPlay`
      // does NOT guarantee the asset's tracks key is loaded —
      // that's a separate async load. `runFade` requires the
      // tracks to be present so it can attach the volume-ramp
      // audioMix to the standby item.
      let nextAsset = nextItem.asset
      Task { [weak self] in
        do {
          _ = try await nextAsset.load(.tracks)
        } catch {
          if let self { await self.onOwnerQueue { self.cancelFadeInternal() } }
          return
        }
        guard let self else { return }
        // The preroll rate is read on the owner queue with the arm check,
        // so it is the speed set while the fade was being armed.
        let (armed, prerollRate) = await self.onOwnerQueue { () -> (Bool, Float) in
          if self.released || !self.standbyArmed { return (false, 1.0) }
        // Provider mix goes on here, between the tracks load and the
        // preroll: `audioMix(for:)` yields nil for an item whose tracks
        // key isn't loaded, and AVPlayer drops an `audioMix` set once the
        // item has begun rendering. That leaves this the only window in
        // which a remote incoming item can pick up the EQ / ReplayGain /
        // visualizer tap, and `runFade` schedules its ramp onto whatever
        // input parameters are attached by then. Read the item back off
        // the leg — the queue slot can be rebuilt across the await.
          if let attached = standby.currentItem {
            self.applyMixIfNeeded(to: attached)
          }
          standby.automaticallyWaitsToMinimizeStalling = false
          return (true, self.userPlaybackSpeed)
        }
        guard armed else { return }
        // Await preroll via a checked continuation rather than spawning a
        // fresh Task inside `preroll`'s @escaping completion, which would
        // warn about a @Sendable closure capturing non-Sendable self —
        // preroll's callback queue is unspecified.
        _ = await withCheckedContinuation { (cont: CheckedContinuation<Bool, Never>) in
          standby.preroll(atRate: prerollRate) { ready in
            cont.resume(returning: ready)
          }
        }
        await self.onOwnerQueue {
          if self.released || !self.standbyArmed { return }
          // Best-effort restore: cancel/release paths drop the
          // standby item entirely, so a missed restore here is
          // bounded by the next replaceCurrentItem.
          standby.automaticallyWaitsToMinimizeStalling = priorAutoStall
          self.runFade(durationSeconds: durationSeconds)
        }
      }
    }
  }

  /// One-shot KVO on `standby.status`. Fires [ready] once status
  /// reaches `.readyToPlay`; fires immediately on the calling
  /// runloop turn if already `.readyToPlay`. On `.failed` the
  /// callback fires too — caller must check `released` /
  /// `standbyArmed` before acting. Backed by a 5s wall-clock
  /// timeout that calls `cancelFadeInternal` if the status never
  /// flips (stuck-load defence).
  /// Bound the arm chain so a standby leg that never becomes playable cannot
  /// leave the engine armed indefinitely. Generous relative to the ready-wait:
  /// this is the backstop for everything after it, not a second attempt at the
  /// same deadline.
  private func startArmDeadline() {
    armDeadline?.cancel()
    let deadline = DispatchWorkItem { [weak self] in
      guard let self else { return }
      self.armDeadline = nil
      guard self.standbyArmed, !self.fadeInFlight else { return }
      self.cancelFadeInternal()
    }
    armDeadline = deadline
    ownerQueue.asyncAfter(deadline: .now() + Self.armDeadlineSeconds, execute: deadline)
  }

  private func awaitStandbyReady(_ player: AVPlayer, ready: @escaping () -> Void) {
    standbyReadyObserver?.invalidate()
    standbyReadyObserver = nil
    standbyReadyTimeout?.cancel()
    standbyReadyTimeout = nil
    standbyReadyGen &+= 1
    let myGen = standbyReadyGen

    if player.status == .readyToPlay {
      ready()
      return
    }

    standbyReadyObserver = player.observe(\.status, options: [.new]) { [weak self] obs, _ in
      let s = obs.status
      // `.unknown` is the start state — wait for the first real flip.
      guard s == .readyToPlay || s == .failed else { return }
      guard let self else { return }
      self.ownerQueue.async {
        // Cross-cycle race guard: if a new armCrossfade cycle
        // started after this observer queued its `ownerQueue` hop,
        // the generation counter has advanced — this fire belongs
        // to a stale standby and must not run `ready()` against
        // the new arm's state.
        if self.standbyReadyGen != myGen { return }
        self.standbyReadyObserver?.invalidate()
        self.standbyReadyObserver = nil
        self.standbyReadyTimeout?.cancel()
        self.standbyReadyTimeout = nil
        if s == .readyToPlay {
          ready()
        } else {
          // Standby load failed — drop the arm cleanly so the
          // leading leg keeps playing through to its natural end.
          self.cancelFadeInternal()
        }
      }
    }

    let timeout = DispatchWorkItem { [weak self] in
      guard let self else { return }
      // Same cross-cycle guard for the timeout path.
      if self.standbyReadyGen != myGen { return }
      self.standbyReadyObserver?.invalidate()
      self.standbyReadyObserver = nil
      self.standbyReadyTimeout = nil
      self.cancelFadeInternal()
    }
    standbyReadyTimeout = timeout
    self.ownerQueue.asyncAfter(deadline: .now() + 5.0, execute: timeout)
  }

  /// Input parameters to drive the fade ramp on [item]. Reuses the item's
  /// existing tap-bearing parameters when present so the EQ / visualizer /
  /// ReplayGain tap survives the fade; otherwise a bare ramp-only object
  /// keyed to [track]. Never re-runs the mix provider — that allocates a
  /// fresh MTAudioProcessingTap on an already-rendering item and leaks it
  /// (see refreshAllItemMixes).
  private func fadeParams(
    for item: AVPlayerItem,
    track: AVAssetTrack
  ) -> AVMutableAudioMixInputParameters {
    if let existing = item.audioMix?.inputParameters.first
      as? AVMutableAudioMixInputParameters {
      return existing
    }
    return AVMutableAudioMixInputParameters(track: track)
  }

  /// Schedule the equal-power N-sub-ramp `setVolumeRamp` schedule
  /// on both legs' currentItem `audioMix.inputParameters`. Caller
  /// has already prerolled standby. The fade runs against
  /// asset-time on each item; the post-fade swap is scheduled via
  /// a Task that sleeps for `durationSeconds` then promotes
  /// standby to leading.
  private func runFade(durationSeconds: TimeInterval) {
    if released { return }
    // One envelope per boundary. The arm chain that reaches here is
    // asynchronous — a standby-ready wait, then a `tracks` key load, then a hop
    // back to the owner queue — and every step of it checks `standbyArmed`
    // only, so a boundary whose chain is entered more than once arrives here
    // more than once. Each arrival would schedule another fifty sub-ramps onto
    // the same `AVMutableAudioMixInputParameters` (`fadeParams` reuses the
    // leg's existing one so its audio tap survives the fade), and those windows
    // overlap the ones already on it: `setVolumeRamp` answers an overlapping
    // window by raising, which is not catchable from Swift and takes the
    // process with it. The envelope is also simply wrong once it is scheduled
    // twice, crash or no crash.
    if fadeInFlight { return }
    guard let leading = leadingPlayer.currentItem,
          let standby = standbyPlayer.currentItem else { return }

    // BOTH sides must produce a valid AVAssetTrack; if either is
    // empty the fade can't ramp evenly and the user hears a hard
    // transition. armCrossfade has already awaited the standby
    // asset's tracks key load, so empty here is a real error —
    // fail loud, cancel cleanly, log via NSLog so the issue isn't
    // silent (the prior implementation's `if let` guard silently
    // skipped, which is exactly the failure mode this guard
    // exists to surface).
    // Read tracks via the AVPlayerItem.tracks chain — populated
    // at .readyToPlay and sync-accessible. Avoids the deprecated
    // sync `asset.tracks(withMediaType:)` (replaced by async
    // `loadTracks(withMediaType:)` in macOS 13 / iOS 16). The
    // standby asset's tracks key was already async-loaded in
    // armCrossfade so the chain is guaranteed populated by here.
    let leadingTrack = leading.tracks
      .first(where: { $0.assetTrack?.mediaType == .audio })?.assetTrack
    let standbyTrack = standby.tracks
      .first(where: { $0.assetTrack?.mediaType == .audio })?.assetTrack
    guard let leadingTrack, let standbyTrack else {
      NSLog("[RNQP-CROSSFADE] runFade aborting — missing audio track (leading=%@ standby=%@); cancelling fade",
            leadingTrack == nil ? "MISSING" : "ok",
            standbyTrack == nil ? "MISSING" : "ok")
      cancelFadeInternal()
      return
    }

    // The leading ramp anchors on the leg's current play position; a
    // non-numeric CMTime (an unresolved timebase — a leg that reached the fade
    // before its clock settled, which host load makes more likely) traps
    // AVFoundation's `setVolumeRamp` on the invalid time range. Abort the fade
    // cleanly instead. The standby ramp anchors at kCMTimeZero and is exempt.
    let leadingFadeStart = leading.currentTime()
    guard leadingFadeStart.isNumeric else {
      NSLog("[RNQP-CROSSFADE] runFade aborting — leading currentTime not numeric; cancelling fade")
      cancelFadeInternal()
      return
    }

    fadeInFlight = true
    // The chain reached the fade, so its backstop has done its job; the fade
    // has its own completion path.
    armDeadline?.cancel()
    armDeadline = nil

    let ramps = Self.equalPowerSubRamps(durationSeconds: durationSeconds)

    // Reuse each leg's EXISTING tap-bearing input parameters so the EQ /
    // visualizer / ReplayGain MTAudioProcessingTap keeps processing through
    // the fade window — the volume ramp and the tap coexist on one
    // AVMutableAudioMixInputParameters (the tap is pre-effects, so it sees
    // the track audio and the ramp scales the tapped output). A fresh tap is
    // never allocated here — re-running the provider on an already-rendering
    // item leaks tap resources (see refreshAllItemMixes). Falls back to a
    // bare ramp-only params object only when the leg carries no tap (no
    // EQ/viz/RG consumer, HLS, or tracks not yet loaded).
    let leadingParams = fadeParams(for: leading, track: leadingTrack)
    rampsScheduled = true
    for ramp in ramps {
      let absStart = leadingFadeStart + ramp.timeRange.start
      let timeRange = CMTimeRange(start: absStart, duration: ramp.timeRange.duration)
      leadingParams.setVolumeRamp(
        fromStartVolume: ramp.outgoing.start,
        toEndVolume: ramp.outgoing.end,
        timeRange: timeRange
      )
    }
    let leadingMix = AVMutableAudioMix()
    leadingMix.inputParameters = [leadingParams]
    leading.audioMix = leadingMix

    // Standby ramp anchored at asset-time zero, NOT
    // `standby.currentTime()`. After `replaceCurrentItem` +
    // `preroll(atRate:)` the standby's currentTime is unstable
    // across iOS versions until first decode pull lands; using
    // kCMTimeZero is the only reliable anchor for the head of a
    // freshly-attached AVPlayerItem.
    let standbyParams = fadeParams(for: standby, track: standbyTrack)
    for ramp in ramps {
      let timeRange = CMTimeRange(
        start: ramp.timeRange.start, duration: ramp.timeRange.duration
      )
      standbyParams.setVolumeRamp(
        fromStartVolume: ramp.incoming.start,
        toEndVolume: ramp.incoming.end,
        timeRange: timeRange
      )
    }
    let standbyMix = AVMutableAudioMix()
    standbyMix.inputParameters = [standbyParams]
    standby.audioMix = standbyMix

    standbyPlayer.volume = userVolume  // ramp inside the mix handles the actual envelope
    // Start at the user's speed rather than `play()`'s implicit 1.0, so the
    // incoming track is not audibly played at normal speed for the length of
    // the fade and then snapped.
    standbyPlayer.rate = userPlaybackSpeed

    // Active-track flip: the standby leg is now audibly playing
    // (volume 0 → ramping up). Notify the delegate so JS-facing
    // `onTrackChange` + `MPNowPlayingInfoCenter` retarget to the
    // incoming track immediately, not at fade-end. The accessor
    // group above (`currentMediaItem*`, `currentPositionSeconds`,
    // etc.) also flips at this point because `fadeInFlight = true`
    // is already set above; the delegate sees consistent state
    // across the callback.
    delegate?.engineCrossfadeDidBegin(self)

    // Post-fade swap scheduled in wall-clock — the asset-time-keyed
    // ramps complete in this many seconds regardless of pause
    // because pausing also halts the asset clock. The Task handle
    // is stored so a subsequent `cancelFadeInternal` /
    // `release` can cancel it; without cancellation a stale
    // completion can run against a fresh fade after the user
    // pauses+resumes within one boundary window.
    fadeCompletionTask?.cancel()
    let durationNs = UInt64(durationSeconds * 1_000_000_000)
    fadeCompletionTask = Task { [weak self] in
      do {
        try await Task.sleep(nanoseconds: durationNs)
      } catch {
        return  // cancelled
      }
      guard let self else { return }
      await self.onOwnerQueue { self.completeCrossfade() }
    }
  }

  /// Promote standby to leading at fade-end. Drops the old
  /// leading's currentItem; advances `currentIndex`; resets
  /// arming markers so the new leading can re-arm for its own
  /// next-track boundary; fires `engineDidTransitionTrack` so
  /// `TrackPlayer` can refresh JS-facing state.
  ///
  /// Observer cleanup runs BEFORE the role flip so
  /// `removeTimeObserver` is called against the player that
  /// issued the token; reinstall runs AFTER the flip so the new
  /// observer attaches to the new leading. The fade ramp's
  /// audioMix wiped any provider-supplied EQ params on the new
  /// leading's currentItem — re-apply via `applyMixIfNeeded`
  /// after stripping the ramp mix.
  private func completeCrossfade() {
    if released { return }
    if !fadeInFlight { return }
    fadeInFlight = false
    fadeCompletionTask = nil
    stopArmingObserver()
    teardownLeadingObservers()
    let oldLeading = leadingPlayer
    oldLeading.pause()
    oldLeading.replaceCurrentItem(with: nil)
    oldLeading.volume = userVolume
    // The fade ramp's final tick already settled the standby (about
    // to be promoted to leading) at userVolume; no explicit write
    // needed across the toggle.
    currentRoleIsA.toggle()
    if let newLeading = leadingPlayer.currentItem {
      newLeading.audioMix = nil
      applyMixIfNeeded(to: newLeading)
    }
    // The promoted standby already runs at the user's speed; restamping it
    // here keeps the promotion total when the speed changed mid-fade.
    leadingPlayer.rate = userPlaybackSpeed
    standbyArmed = false
    rampsScheduled = false
    currentIndex += 1
    startArmingObserver()
    installLeadingObservers()
    // Settled before the transition: work held back for this fade belongs to
    // the run the transition is about to be told to extend.
    delegate?.engineFadeDidSettle(self)
    delegate?.engineDidTransitionTrack(self)
  }

  private func cancelFadeInternal(emitRevert: Bool = true) {
    fadeCompletionTask?.cancel()
    fadeCompletionTask = nil
    standbyReadyObserver?.invalidate()
    standbyReadyObserver = nil
    standbyReadyTimeout?.cancel()
    standbyReadyTimeout = nil
    armDeadline?.cancel()
    armDeadline = nil
    // Capture pre-state: if a fade was in flight we already fired
    // `engineCrossfadeDidBegin` for `currentIndex + 1` — the
    // delegate believes the incoming is active. We must fire
    // `engineCrossfadeDidCancel` so the delegate reverts
    // active-track state back to the leading
    // leg. `emitRevert: false` is for callers that will immediately
    // set a new active track themselves (e.g. seek to a different
    // index) — emitting the intermediate revert would surface a
    // spurious track-change to the outgoing leg before the caller's
    // own track-change.
    let wasMidFade = fadeInFlight
    let wasArmed = standbyArmed
    let hadScheduledRamp = rampsScheduled
    fadeInFlight = false
    standbyArmed = false
    rampsScheduled = false
    // Workaround: a backward seek through a scheduled ramp window
    // leaves the mix in place with the wrong volume — strip the mix
    // and re-apply the EQ-only mix to recover. See the type doc for
    // the source.
    //
    // Only when a ramp was scheduled: this runs on the item that is already
    // rendering, and re-applying builds a fresh processing tap that AVPlayer
    // silently drops on an item already in flight. Callers that cancel
    // routinely — every `remove` in a rebuild's removal loop — would otherwise
    // pay one dropped tap cascade per item removed.
    if hadScheduledRamp {
      if let leading = leadingPlayer.currentItem {
        leading.audioMix = nil
        applyMixIfNeeded(to: leading)
      }
      if let standby = standbyPlayer.currentItem {
        standby.audioMix = nil
      }
    }
    leadingPlayer.volume = userVolume
    standbyPlayer.volume = 0
    // `runFade` gave this leg a rate. Dropping its item does not stop it, so
    // without the pause the next arm attaches to a leg already advancing: its
    // mix is set on an item that has begun rendering and is silently dropped,
    // and the fade's ramp, anchored at asset-time zero, is already in the past.
    standbyPlayer.pause()
    // The arm turns this off as a documented precondition of `preroll` and
    // restores it once prerolled. A cancel in between skips that restore, and
    // the value survives the role flip that makes this leg the leading one —
    // where it means playback resumes into a stall instead of waiting.
    standbyPlayer.automaticallyWaitsToMinimizeStalling = true
    standbyPlayer.replaceCurrentItem(with: nil)
    if wasMidFade && emitRevert {
      delegate?.engineCrossfadeDidCancel(self)
    }
    // Every end of a fade, not just a reverted one. A cancel taken during the
    // arm window emits no revert — there is no track change to undo — but work
    // held back for the fade still has to be released, and pause, stop, the
    // arm deadline, a standby that fails to load and a route change all end
    // here without ever reaching `wasMidFade`.
    if wasMidFade || hadScheduledRamp || wasArmed {
      delegate?.engineFadeDidSettle(self)
    }
  }

  // MARK: - Engine event observer install/teardown

  /// Install KVO on the leading leg's `timeControlStatus` (drives
  /// `engineStateMaybeChanged`) and on the leading leg's currentItem
  /// `tracks` / `status` (drives format-ready + failed-to-load
  /// delegate fires). Called whenever
  /// the leading leg or its currentItem changes — i.e. from
  /// [setItems], [seek], and [completeCrossfade] after the role
  /// flip. Idempotent — teardown runs first inside.
  private func installLeadingObservers() {
    teardownLeadingObservers()
    let player = leadingPlayer
    leadingTimeControlObserver = player.observe(
      \.timeControlStatus, options: [.new]
    ) { [weak self] _, _ in
      guard let self else { return }
      self.ownerQueue.async {
        self.delegate?.engineStateMaybeChanged(self, reason: nil)
      }
    }
    if let item = player.currentItem {
      // A local file that does not exist is deterministically unplayable, and
      // its `AVURLAsset` load can stall in `.unknown` indefinitely on `AVPlayer`
      // under host load — the item never reaches `.failed`, so a status observer
      // would never surface it and the leg sits buffering with no error. Fail it
      // now, and install no status observer for this item: a load that does
      // eventually reach `.failed` would otherwise re-report the same dead
      // source through the observer below, under a second error code that is
      // fatal where the missing-file code is transient — the retry path. A retry
      // re-seats the item and re-runs this check, so a source that appears is
      // picked up then. Read the asset's own URL, the one the player will load.
      if let asset = item.asset as? AVURLAsset, asset.url.isFileURL,
         !FileManager.default.fileExists(atPath: asset.url.path) {
        let missing = NSError(
          domain: NSURLErrorDomain, code: NSURLErrorFileDoesNotExist,
          userInfo: [NSLocalizedDescriptionKey: "The requested file does not exist."])
        self.ownerQueue.async { [weak self] in
          guard let self else { return }
          self.delegate?.engineDidFailWithError(self, error: missing, item: item)
        }
        return
      }
      leadingItemTracksObserver = item.observe(
        \.tracks, options: [.new]
      ) { [weak self] obs, _ in
        // tracks fires on every mutation including transient empty
        // states during seek; only fire format-changed when the
        // tracks array is populated so the gapless KVO path's
        // dedup contract is matched.
        guard !obs.tracks.isEmpty else { return }
        guard let self else { return }
        self.ownerQueue.async {
          self.delegate?.engineActiveItemFormatChanged(self)
        }
      }
      // Bootstrap: `.new`-only KVO won't fire when the item's tracks are
      // ALREADY populated at install time — a cached / preloaded asset, e.g. a
      // skip to a lookahead-cached track. Without this the format-change never
      // propagates and the now-playing format stays nil. Mirrors what
      // `.initial` would have delivered.
      if !item.tracks.isEmpty {
        self.ownerQueue.async { [weak self] in
          guard let self else { return }
          self.delegate?.engineActiveItemFormatChanged(self)
        }
      }
      // Leading-leg initial-buffer leniency, mirroring the gapless engine's
      // `gaplessFlipArmed`. A freshly seated item waits to minimize stalling
      // until it is `.readyToPlay`, then plays without further waiting. Without
      // the flip the leading leg keeps `automaticallyWaitsToMinimizeStalling`
      // set after a resume / engine swap and can take longer than a caller's
      // settle window to reach `.playing`. Re-arm to `true` for a not-yet-ready
      // item so a play-before-buffered waits rather than stalling to paused;
      // flip to `false` once the item is ready so a resume starts promptly.
      // `player` is captured so the leg that owns this item is the one flipped
      // even if the leading role has since swapped.
      player.automaticallyWaitsToMinimizeStalling = item.status != .readyToPlay
      leadingItemStatusObserver = item.observe(
        \.status, options: [.new]
      ) { [weak self] obs, _ in
        if obs.status == .readyToPlay {
          // Flip on `ownerQueue` like every other mutation here (and the gapless
          // flip in `dispatchItemStatus`), re-checking the item is still the
          // leg's current one. KVO does not abort an in-flight callback on
          // `invalidate()`, so a stale `.readyToPlay` for a since-replaced item
          // must not strand `autoWaits = false` on a fresh not-yet-ready item —
          // the very stall the re-arm above prevents.
          self?.ownerQueue.async {
            guard let self, player.currentItem === obs else { return }
            if self.mixProvider?.anyConsumerWantsTap() == true {
              // A tap is wanted. Install this item's audio tap BEFORE
              // permitting prompt render: AVPlayer honours `audioMix` only
              // before the item starts rendering (Apple contract, the same one
              // `GaplessEngine.setItems` installs before enqueue for) — a mix
              // set once the leg is already playing is silently dropped, its
              // `MTAudioProcessingTap` never prepares, and the equaliser and
              // visualizer tap lose their audio. `.readyToPlay` does not
              // guarantee the asset's `tracks` key is loaded and
              // `audioMix(for:)` yields nil until it is, so load tracks first
              // (as the arm path does for the standby), then install the mix
              // and resume any leg held at rate 0 for the tap. The leg stays
              // held (`autoWaits` true) until that completes; a consumer that
              // subscribes mid-playback then joins the already-running tap
              // instead of needing an unreliable mid-render re-attach.
              self.installLeadingTapMixThenResume(item: obs)
            } else {
              // No tap wanted: nothing to install; permit prompt render, and
              // resume a leg that was held for a tap since dropped (a consumer
              // unsubscribed before this fired) — leaving it at rate 0 would
              // strand it silent. A genuine pause has cleared `wantsPlayback`
              // (via `stopArmingObserver`), so this does not override it.
              player.automaticallyWaitsToMinimizeStalling = false
              if self.wantsPlayback, player.rate == 0 {
                player.rate = self.userPlaybackSpeed
              }
            }
          }
          return
        }
        guard obs.status == .failed else { return }
        let underlying = obs.error ?? NSError(
          domain: "CrossfadeEngine", code: -1, userInfo: nil
        )
        guard let self else { return }
        // `obs` is the leading leg's current item — the one that failed.
        self.ownerQueue.async {
          self.delegate?.engineDidFailWithError(self, error: underlying, item: obs)
        }
      }
      // Bootstrap: `.new`-only KVO won't fire when the item is ALREADY `.failed`
      // at install time — a source that reached `.failed` between `setItems` and
      // the reinstall a `seek` runs. The reinstalled observer never sees the
      // transition, so surface it here, as `.initial` and the tracks observer
      // above both do.
      if item.status == .failed {
        let underlying = item.error ?? NSError(
          domain: "CrossfadeEngine", code: -1, userInfo: nil
        )
        self.ownerQueue.async { [weak self] in
          guard let self else { return }
          self.delegate?.engineDidFailWithError(self, error: underlying, item: item)
        }
      }
      // Buffer-flag KVO so a discrete buffer-state change reaches
      // `onBufferStateChange` on the crossfade engine, the same way the gapless
      // `AVQueuePlayer`'s item KVO delivers it. Without it the buffer surface
      // recomputes only on transport calls and the 500ms progress tick, so a
      // fast local reload's empty→full edge lands between samples and no change
      // is emitted. Routed to a delegate that recomputes ONLY the buffer surface
      // — no direct player-state emission; folding buffer edges into the state
      // path would churn `PlayerState` on every load edge.
      let onBuffer: (AVPlayerItem, Any) -> Void = { [weak self] _, _ in
        guard let self else { return }
        self.ownerQueue.async { [weak self] in
          guard let self else { return }
          self.delegate?.engineBufferStateMaybeChanged(self)
        }
      }
      leadingItemBufferEmptyObserver = item.observe(
        \.isPlaybackBufferEmpty, options: [.new], changeHandler: onBuffer)
      leadingItemBufferKeepUpObserver = item.observe(
        \.isPlaybackLikelyToKeepUp, options: [.new], changeHandler: onBuffer)
      leadingItemBufferFullObserver = item.observe(
        \.isPlaybackBufferFull, options: [.new], changeHandler: onBuffer)
      // Bootstrap: `.new`-only KVO won't fire for an item ALREADY buffer-ready
      // at install time — a cached / preloaded skip. Deliver the current reading
      // once, as the tracks / status bootstraps above do. Guarded on the ready
      // flags for the same reason those are guarded on `!tracks.isEmpty` /
      // `.failed`: bootstrap only when there is a settled state to deliver. An
      // unconditional fire queues a recompute on the owner queue on EVERY
      // seat / seek / advance — most of which seat a not-yet-buffered item that
      // the KVO will report when it settles — and that per-install churn shifts
      // the crossfade arm / preroll interleave on the serial queue under load.
      if item.isPlaybackLikelyToKeepUp || item.isPlaybackBufferFull {
        self.ownerQueue.async { [weak self] in
          guard let self else { return }
          self.delegate?.engineBufferStateMaybeChanged(self)
        }
      }
    }
  }

  private func teardownLeadingObservers() {
    leadingTimeControlObserver?.invalidate()
    leadingTimeControlObserver = nil
    leadingItemTracksObserver?.invalidate()
    leadingItemTracksObserver = nil
    leadingItemStatusObserver?.invalidate()
    leadingItemStatusObserver = nil
    leadingItemBufferEmptyObserver?.invalidate()
    leadingItemBufferEmptyObserver = nil
    leadingItemBufferKeepUpObserver?.invalidate()
    leadingItemBufferKeepUpObserver = nil
    leadingItemBufferFullObserver?.invalidate()
    leadingItemBufferFullObserver = nil
  }

  private func handleItemDidPlayToEnd(_ note: Notification) {
    guard let item = note.object as? AVPlayerItem,
          item === leadingPlayer.currentItem else { return }
    delegate?.engineDidPlayItemToEnd(self)
    // Repeat-one: loop the current item in place instead of advancing
    // or ending the queue. The fade is suppressed under `.track`, so
    // the item reaches its true end here; seek to zero + play replays
    // it, matching the gapless engine's repeat-track replay. Applies on
    // every track (incl. the last), so it must precede the queue-end check.
    if repeatMode == .track, !fadeInFlight, currentIndex >= 0 {
      leadingPlayer.seek(to: .zero)
      // `play()` resets the rate to 1.0, dropping the user's speed on every
      // loop; and the notification hops onto the owner queue, so a pause taken
      // in between must not be undone here.
      if wantsPlayback {
        leadingPlayer.rate = userPlaybackSpeed
      }
      return
    }
    // Queue-end detection: leading played its last item to end
    // AND no fade is in flight (mid-fade end is normal — the
    // standby is about to be promoted, not the queue ending).
    // [currentIndex] is still pre-swap during a fade; gating on
    // [fadeInFlight] avoids firing enginePlaybackEnded one item
    // too early when the penultimate track ends naturally during
    // its outgoing fade.
    if !fadeInFlight,
       currentIndex >= 0,
       currentIndex >= queue.count - 1 {
      delegate?.enginePlaybackEnded(self)
      return
    }
    // The item ended mid-run with no fade to carry the boundary. Advancing
    // is this engine's job either way: a fade is how it usually crosses, not
    // the only way it can.
    //
    // Reachable whenever the arm did not happen or did not survive — the
    // standby failed to load, the arm chain timed out, a route change or a
    // queue mutation cancelled it, or the item was the last one installed and
    // there was nothing to preroll. Without this the queue simply stops on a
    // track that has finished, and nothing arrives to restart it.
    if !fadeInFlight, currentIndex >= 0, currentIndex < queue.count - 1 {
      advanceWithoutFade()
    }
  }

  /// Attach the next queue item to the leading leg and advance the index, for
  /// an item that reached its end with no fade to carry the boundary.
  ///
  /// Follows `seek(toIndex:)`: capture playback intent, tear the fade state
  /// down through the one path that owns it, rebuild the incoming item, and
  /// resume only if the engine was playing.
  private func advanceWithoutFade() {
    if released { return }
    // The end-of-item notification hops onto the owner queue, so a pause taken
    // in between must not be undone by the rate write below.
    let shouldResume = wantsPlayback
    // Never by hand: the arm leaves a KVO observation, two timers and a task
    // behind, and the standby leg holding a buffering item. Suppress the
    // revert emit — the transition fired at the end of this method is the
    // authoritative track change.
    cancelFadeInternal(emitRevert: false)
    // `engineFadeDidSettle` above releases work deferred for the fade, which
    // can reconcile the run. Re-read the bounds it may have moved.
    guard currentIndex >= 0, currentIndex + 1 < queue.count else { return }
    teardownLeadingObservers()
    let nextIndex = currentIndex + 1
    // Fresh instance, never the stored one: the queue slot may still hold the
    // item the arm attached to the standby leg, and attaching an item that is
    // still associated with another player raises an uncatchable exception.
    let next = rebuiltItem(at: nextIndex)
    // Both before the attach: AVPlayer drops an `audioMix` set on an item that
    // has begun rendering, leaking the processing tap it carries.
    applyMixIfNeeded(to: next)
    applyPitchAlgorithm(to: next)
    leadingPlayer.replaceCurrentItem(with: next)
    currentIndex = nextIndex
    if shouldResume {
      // `replaceCurrentItem` leaves the player at rate 0 — resume respecting a
      // wanted tap, as `seek(toIndex:)` does.
      resumeLeadingRespectingTap()
    }
    installLeadingObservers()
    delegate?.engineDidTransitionTrack(self)
  }

  private func handleItemFailedToPlayToEnd(_ note: Notification) {
    guard let item = note.object as? AVPlayerItem,
          item === leadingPlayer.currentItem else { return }
    let underlying = (note.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? Error)
      ?? item.error
      ?? NSError(domain: "CrossfadeEngine", code: -2, userInfo: nil)
    // Guarded above as the leading leg's current item.
    delegate?.engineDidFailWithError(self, error: underlying, item: item)
  }
}
