import Foundation
import MediaPlayer
import UIKit

/// Owns the process-wide `MPNowPlayingInfoCenter.default().nowPlayingInfo`
/// dictionary that drives the iOS lock-screen, control-center, CarPlay,
/// AirPlay, and Apple Watch surfaces.
///
/// Emits on:
/// - track change (full refresh — title/artist/album/duration/artwork +
///   position + rate).
/// - state change (position + rate only).
/// - seek completion (position + rate only).
/// - 10s periodic tick (position + rate only — bounds extrapolation drift).
///
/// The system interpolates the scrubber between emits using
/// `MPNowPlayingInfoPropertyElapsedPlaybackTime` +
/// `MPNowPlayingInfoPropertyPlaybackRate`. The periodic tick keeps that
/// interpolation honest across long-running playback, rate changes, and
/// background-thread suspensions.
///
/// With diagnostic logging on, every write and skip is logged
/// (`DiagnosticLog.nowPlaying`) with what requested it and how long it
/// waited for main.
///
/// Threading: callable from any queue. Every public method hands its work
/// to main, which owns both the Now Playing surface and this object's state;
/// `TrackPlayer` calls in from `playerQueue`. Player state
/// is pushed in as a `Snapshot` argument — this writer never reads
/// player state itself, so it holds no synchronous read edge back into
/// whichever context owns that state.
final class NowPlayingInfo {

  /// What asked for a write. Logged with the write.
  enum Trigger: String {
    case trackChange, queueCleared, artworkResolved, stateChange, seek, tick,
      durationKnown, readyToPlay
  }

  /// Snapshot of player state at emit time. The caller is responsible
  /// for reading every field within the same tick — divergent values
  /// (elapsed taken at t0, rate at t0+Δ) show up on the lock-screen
  /// scrubber as jitter.
  struct Snapshot {
    let track: TrackItem?
    /// The lib-generated id of the queue entry `track` sits in, published as
    /// the external content identifier. Device logs show the system keys the
    /// item on it, so the item keeps one id across every write for the entry.
    let queueItemId: String?
    /// Current playback position in seconds. NaN / ±Inf coerce to 0.
    let elapsedSeconds: Double
    /// Total duration in seconds. NaN / ±Inf / ≤0 → omit (signals
    /// live-stream / unknown to the system).
    let durationSeconds: Double
    /// Effective playback rate: the configured speed while playing, 0
    /// otherwise. Not the player's requested rate, which is non-zero while
    /// waiting to play and would extrapolate the scrubber through a stall.
    let rate: Float
  }

  private var periodicTimer: DispatchSourceTimer?
  /// Subscription to the global cast active-session router. When a
  /// remote session is active, `CastNowPlayingController` owns the
  /// lockscreen surface and this writer becomes a no-op — every
  /// public method short-circuits without mutating
  /// `MPNowPlayingInfoCenter`. Re-engaged on session deactivate.
  private var castActive: Bool = false
  private var castRouterDisposer: (() -> Void)?

  init() {
    castRouterDisposer = PlaybackStateRouter.shared.addActiveChangeListener {
      [weak self] session in
      let active = (session != nil)
      NowPlayingInfo.onMain { self?.castActive = active }
    }
  }

  /// Hands `body` to main, where this object's state and the Now Playing
  /// surface both live. Always asynchronous, never inline-when-already-on-main:
  /// mixing the two lets a later write land before an earlier one, and the
  /// surface is last-write-wins.
  private static func onMain(_ body: @escaping @MainActor () -> Void) {
    DispatchQueue.main.async { MainActor.assumeIsolated(body) }
  }

  deinit {
    castRouterDisposer?()
    // libdispatch retains an armed source, so one that is never cancelled
    // keeps waking the main queue for the life of the process.
    periodicTimer?.cancel()
  }

  /// Full refresh — metadata + position + rate. Call on track-change.
  /// A snapshot with `track == nil` (queue empty / pre-first-track)
  /// clears the dictionary so the lock-screen / control-center surface
  /// goes away.
  func refreshAll(_ snapshot: Snapshot, trigger: Trigger) {
    let requested = ContinuousClock.now
    NowPlayingInfo.onMain { self.refreshAllOnMain(snapshot, trigger: trigger, requested: requested) }
  }

  @MainActor
  private func refreshAllOnMain(_ snapshot: Snapshot, trigger: Trigger, requested: ContinuousClock.Instant) {
    let waited = ContinuousClock.now - requested
    if castActive { return logSkip("refreshAll", trigger: trigger, reason: "castActive", waited: waited) }
    guard snapshot.track != nil else {
      MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
      return logWrite("clearForNoTrack", trigger: trigger, waited: waited, artwork: nil)
    }
    var info: [String: Any] = [:]
    applyMetadata(snapshot.track, into: &info)
    if snapshot.durationSeconds.isFinite && snapshot.durationSeconds > 0 {
      info[MPMediaItemPropertyPlaybackDuration] = snapshot.durationSeconds
    }
    info[MPNowPlayingInfoPropertyMediaType] =
      MPNowPlayingInfoMediaType.audio.rawValue
    info[MPNowPlayingInfoPropertyExternalContentIdentifier] = snapshot.queueItemId
    info[MPMediaItemPropertyArtwork] = placeholderArtwork()
    applyPositionAndRate(snapshot, into: &info)
    MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    logWrite("refreshAll", trigger: trigger, waited: waited, artwork: "placeholder")
  }

  /// Replace the artwork in the current dictionary with the resolver-
  /// supplied image. No-op when the dictionary is nil (track ended,
  /// queue cleared, destroyed) — refuses to resurrect the surface from
  /// an artwork-only payload.
  ///
  /// `boundsSize: image.size` and `requestHandler` returning the
  /// image unchanged is the shape `MPMediaItemArtwork` documents; the
  /// system handles size negotiation. A forced 1024² canvas with an
  /// aspect-fit render makes no difference to the CarPlay main-art
  /// region.
  ///
  /// `queueItemId` is the entry the image was resolved for. The image is
  /// dropped when the published dictionary belongs to another entry or
  /// carries no entry id.
  func applyArtwork(_ image: UIImage, for queueItemId: String?) {
    let requested = ContinuousClock.now
    NowPlayingInfo.onMain {
      self.applyArtworkOnMain(image, for: queueItemId, requested: requested)
    }
  }

  @MainActor
  private func applyArtworkOnMain(
    _ image: UIImage, for queueItemId: String?, requested: ContinuousClock.Instant
  ) {
    let waited = ContinuousClock.now - requested
    if castActive {
      return logSkip("applyArtwork", trigger: .artworkResolved, reason: "castActive", waited: waited)
    }
    guard var info = MPNowPlayingInfoCenter.default().nowPlayingInfo else {
      return logSkip("applyArtwork", trigger: .artworkResolved, reason: "noDictionary", waited: waited)
    }
    guard let queueItemId,
      info[MPNowPlayingInfoPropertyExternalContentIdentifier] as? String == queueItemId
    else {
      return logSkip("applyArtwork", trigger: .artworkResolved, reason: "otherItem", waited: waited)
    }
    info[MPMediaItemPropertyArtwork] = Self.artwork(for: image)
    MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    logWrite("applyArtwork", trigger: .artworkResolved, waited: waited, artwork: Self.describe(image))
  }

  /// Position + rate only. Leaves track metadata + artwork untouched.
  /// Call on state-change, seek-completion, periodic tick. No-op when
  /// there's no current track (queue empty) or when the dictionary
  /// hasn't been populated yet — refusing to resurrect the surface
  /// from a partial position-only payload.
  ///
  /// Also re-publishes [MPMediaItemPropertyPlaybackDuration] when the
  /// snapshot has a finite duration. The initial [refreshAll] on track
  /// change runs before the AVURLAsset has loaded its duration, so the
  /// progress bar / scrub UI on the lock screen + CarPlay Now Playing
  /// surface stays absent until this method's late re-publish lands
  /// (typically ~100-500ms after play starts, via the state-change
  /// dispatch in [TrackPlayer]).
  func refreshPositionAndRate(_ snapshot: Snapshot, trigger: Trigger) {
    let requested = ContinuousClock.now
    NowPlayingInfo.onMain {
      self.refreshPositionAndRateOnMain(snapshot, trigger: trigger, requested: requested)
    }
  }

  @MainActor
  private func refreshPositionAndRateOnMain(
    _ snapshot: Snapshot, trigger: Trigger, requested: ContinuousClock.Instant
  ) {
    let waited = ContinuousClock.now - requested
    if castActive { return logSkip("positionAndRate", trigger: trigger, reason: "castActive", waited: waited) }
    guard snapshot.track != nil else {
      return logSkip("positionAndRate", trigger: trigger, reason: "noTrack", waited: waited)
    }
    guard var info = MPNowPlayingInfoCenter.default().nowPlayingInfo else {
      return logSkip("positionAndRate", trigger: trigger, reason: "noDictionary", waited: waited)
    }
    applyPositionAndRate(snapshot, into: &info)
    if snapshot.durationSeconds.isFinite && snapshot.durationSeconds > 0 {
      info[MPMediaItemPropertyPlaybackDuration] = snapshot.durationSeconds
    }
    MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    logWrite("positionAndRate", trigger: trigger, waited: waited, artwork: nil)
  }

  @MainActor
  private func logWrite(_ kind: StaticString, trigger: Trigger, waited: Duration, artwork: String?) {
    DiagnosticLog.nowPlaying.log(Self.writeLine(kind, trigger: trigger, waited: waited, artwork: artwork))
  }

  @MainActor
  private static func writeLine(
    _ kind: StaticString, trigger: Trigger, waited: Duration, artwork: String?
  ) -> String {
    let info = MPNowPlayingInfoCenter.default().nowPlayingInfo
    let rate = info?[MPNowPlayingInfoPropertyPlaybackRate] as? Double ?? 0
    let elapsed = info?[MPNowPlayingInfoPropertyElapsedPlaybackTime] as? Double ?? 0
    let hasDuration = info?[MPMediaItemPropertyPlaybackDuration] != nil
    let item = (info?[MPNowPlayingInfoPropertyExternalContentIdentifier] as? String)
      .map { String($0.prefix(8)) } ?? "-"
    return "write \(kind) trigger=\(trigger.rawValue) waited=\(seconds(waited)) appState=\(appStateName()) item=\(item) rate=\(rate) elapsed=\(elapsed) duration=\(hasDuration) artwork=\(artwork ?? "unchanged")"
  }

  @MainActor
  private func logSkip(_ kind: StaticString, trigger: Trigger, reason: StaticString, waited: Duration) {
    DiagnosticLog.nowPlaying.log(
      "skip \(kind) trigger=\(trigger.rawValue) reason=\(reason) waited=\(Self.seconds(waited)) appState=\(Self.appStateName())")
  }

  private static func describe(_ image: UIImage) -> String {
    image === PlaceholderArtwork.image
      ? "placeholder"
      : "resolved \(Int(image.size.width * image.scale))x\(Int(image.size.height * image.scale))"
  }

  private static func seconds(_ duration: Duration) -> String {
    let parts = duration.components
    return String(format: "%.3fs", Double(parts.seconds) + Double(parts.attoseconds) / 1e18)
  }

  @MainActor
  private static func appStateName() -> String {
    switch UIApplication.shared.applicationState {
    case .active: return "active"
    case .inactive: return "inactive"
    case .background: return "background"
    @unknown default: return "unknown"
    }
  }

  /// Stops the periodic timer and clears the dictionary. Call on
  /// `destroy()` before the audio session deactivates.
  func clear() {
    NowPlayingInfo.onMain {
      self.stopPeriodicTimerOnMain()
      MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
      DiagnosticLog.nowPlaying.log("write clear")
    }
  }

  /// Schedule the 10s periodic tick on the main queue. Restarts a running
  /// tick rather than stacking a second one. A 1s leeway lets the OS
  /// coalesce with other timers without affecting scrubber accuracy (the
  /// system extrapolates from elapsed + rate between emits). `onTick`
  /// builds the snapshot on the side that owns player state and pushes the
  /// refresh; it carries the paused short-circuit, since only that side can
  /// tell whether the write would be pure noise.
  func startPeriodicTimer(onTick: @escaping () -> Void) {
    NowPlayingInfo.onMain { self.startPeriodicTimerOnMain(onTick: onTick) }
  }

  @MainActor
  private func startPeriodicTimerOnMain(onTick: @escaping () -> Void) {
    stopPeriodicTimerOnMain()
    let timer = DispatchSource.makeTimerSource(queue: .main)
    timer.schedule(deadline: .now() + 10, repeating: 10, leeway: .seconds(1))
    timer.setEventHandler { onTick() }
    periodicTimer = timer
    // The source is resumed exactly once and never suspended — a suspended
    // source traps libdispatch on release.
    timer.resume()
  }

  /// Cancel the tick source. `cancel()` is safe from any thread; calling it
  /// on the source's own queue additionally guarantees no further tick runs
  /// once it returns.
  @MainActor
  private func stopPeriodicTimerOnMain() {
    periodicTimer?.cancel()
    periodicTimer = nil
  }

  /// Empty-string-coerce title / artist / album. CarPlay drops the
  /// rendered surface when an expected String property is missing —
  /// always set the keys, even when the consumer-supplied TrackItem
  /// has nothing populated.
  private func applyMetadata(_ track: TrackItem?, into info: inout [String: Any]) {
    info[MPMediaItemPropertyTitle] = track?.title ?? ""
    info[MPMediaItemPropertyArtist] = track?.artist ?? ""
    info[MPMediaItemPropertyAlbumTitle] = track?.album ?? ""
  }

  private func applyPositionAndRate(_ snapshot: Snapshot, into info: inout [String: Any]) {
    let elapsed = snapshot.elapsedSeconds.isFinite ? max(0, snapshot.elapsedSeconds) : 0
    let rate = snapshot.rate.isFinite ? Double(snapshot.rate) : 0
    info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = elapsed
    info[MPNowPlayingInfoPropertyPlaybackRate] = rate
  }

  /// MPMediaItemArtwork wrapper around the active placeholder image —
  /// `PlaceholderArtwork.image` is the consumer-configured image when one
  /// is set (`PlayerConfig.placeholderArtworkUri`), else the bundled one.
  /// Rebuilt per call (cheap; the system handles scaling) so a
  /// placeholder set at `configure` is reflected rather than serving a
  /// stale wrapper.
  private func placeholderArtwork() -> MPMediaItemArtwork {
    Self.artwork(for: PlaceholderArtwork.image)
  }

  /// Wraps `image` for the dictionary. The system calls the handler on any
  /// queue each time a surface asks for the image; each call is logged.
  private static func artwork(for image: UIImage) -> MPMediaItemArtwork {
    let kind = describe(image)
    return MPMediaItemArtwork(boundsSize: image.size) { size in
      DiagnosticLog.nowPlaying.log(
        "artwork requested \(kind) size=\(Int(size.width))x\(Int(size.height)) main=\(Thread.isMainThread)")
      return image
    }
  }
}
