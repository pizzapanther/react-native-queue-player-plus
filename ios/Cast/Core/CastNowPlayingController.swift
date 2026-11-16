import AVFoundation
import Foundation
import MediaPlayer
import UIKit

/// Drives `MPNowPlayingInfoCenter` (lockscreen + Control Center)
/// metadata while a Chromecast session is active. Without this, iOS
/// reclaims the Now Playing slot as soon as the local `AVPlayer`
/// stops, leaving the lockscreen blank during cast.
///
/// A silent `AVPlayer` loop (`silence.m4a`) runs for the duration of
/// the cast session so `AVAudioSession` stays active and iOS keeps
/// treating this app as the Now Playing app — without audible output,
/// the system hands the lockscreen slot to whatever plays next.
///
/// Subscribes to `PlaybackStateRouter.shared` so it observes
/// `CastSessionRemotePlayer` activation flips without needing a
/// `CastManager` reference. `isPushSink == true` sessions (AirPlay)
/// are skipped — push-PCM keeps the local AVAudioSession active
/// already, so the lockscreen continues to mirror the local player.
final class CastNowPlayingController {

  static let shared = CastNowPlayingController()

  private var activeChangeDisposer: (() -> Void)?
  private var sessionListenerDisposers: [() -> Void] = []
  private var silentPlayer: AVQueuePlayer?
  private var silentLooper: Any?
  private weak var currentSession: CastSessionRemotePlayer?

  /// Resolves the receiver track's artwork URL to a `UIImage` for the
  /// lockscreen. A private instance (own sequence counter) so a
  /// cast↔local surface handoff can't cross-cancel the other surface's
  /// in-flight fetch. Auth config is forwarded from
  /// `TrackPlayer.configure` via `configureArtwork`.
  private let artworkResolver = ArtworkResolver()

  /// Placeholder shown until the real cover resolves, so the lockscreen
  /// never renders artwork-less during the network fetch. Read live —
  /// `PlaceholderArtwork.image` is the consumer-configured image when one
  /// is set (`PlayerConfig.placeholderArtworkUri`), else the bundled one —
  /// so a per-refresh write here never clobbers it back to the built-in.
  private static var placeholderArtwork: MPMediaItemArtwork {
    let image = PlaceholderArtwork.image
    return MPMediaItemArtwork(boundsSize: image.size) { _ in image }
  }

  /// Snapshot of the active receiver track for the lockscreen surface.
  /// Pushed by `TrackPlayer` when the receiver's current track (resolved
  /// by absolute queue index) changes. `nil` until the first push (or
  /// after deactivate).
  private struct TrackMetadata {
    /// Published as the external content identifier so the system keeps one
    /// item for this track across the rate and position writes. A new value
    /// per metadata update, never the consumer's track id.
    let contentId = UUID().uuidString
    let title: String?
    let artist: String?
    let album: String?
    let durationSec: Double
    let artworkUrl: String?
  }
  private var currentMetadata: TrackMetadata?

  private init() {}

  // MARK: - Lifecycle

  /// Begin observing the global router. Idempotent — repeat calls are
  /// no-ops. Called from `TrackPlayer.configure` after the engine is
  /// set up.
  func start() {
    onMain { [self] in
      if self.activeChangeDisposer != nil { return }
      self.activeChangeDisposer = PlaybackStateRouter.shared.addActiveChangeListener {
        [weak self] session in
        self?.handleActiveChange(to: session)
      }
    }
  }

  /// Every field here is main-thread state — the router and session
  /// callbacks arrive on main — so the owner's entry points, called on its
  /// player queue, hop rather than touch the fields where they are called.
  private func onMain(_ body: @escaping () -> Void) {
    if Thread.isMainThread {
      body()
    } else {
      DispatchQueue.main.async(execute: body)
    }
  }

  /// Stop observing + tear down any active mirror. Called from
  /// `TrackPlayer.internalDestroy`.
  func stop() {
    onMain {
      self.deactivate()
      self.activeChangeDisposer?()
      self.activeChangeDisposer = nil
    }
  }

  /// Push current-track metadata. Called by `TrackPlayer` when the
  /// receiver's active track resolves (via `CastEventBridge`, which reads
  /// the absolute queue index from `GCKMediaQueue`). The controller
  /// refreshes the lockscreen if a cast session is active.
  func updateTrackMetadata(
    title: String?,
    artist: String?,
    album: String?,
    durationSec: Double,
    artworkUrl: String?
  ) {
    onMain {
      self.currentMetadata = TrackMetadata(
        title: title, artist: artist, album: album,
        durationSec: durationSec, artworkUrl: artworkUrl
      )
      if self.currentSession != nil {
        self.refreshNowPlayingInfo()
        self.resolveArtwork()
      }
    }
  }

  /// Forward the player's artwork HTTP config so authenticated artwork
  /// URLs resolve during cast. Called from `TrackPlayer.configure`.
  func configureArtwork(headers: [String: String]?, userAgent: String?) {
    onMain {
      self.artworkResolver.configHeaders = headers
      self.artworkResolver.configUserAgent = userAgent
    }
  }

  // MARK: - Active-flow observer

  private func handleActiveChange(to session: CastSessionRemotePlayer?) {
    // The router fires listeners on the caller's thread; every caller today
    // is on main, and the fields here are read and written on that basis.
    dispatchPrecondition(condition: .onQueue(.main))
    if let session, !session.isPushSink {
      activate(with: session)
    } else {
      deactivate()
    }
  }

  private func activate(with session: CastSessionRemotePlayer) {
    deactivate()
    DiagnosticLog.nowPlaying.log("cast session started: cast owns now playing")
    currentSession = session
    startSilentAudio()
    wireSessionListeners(session)
    refreshNowPlayingInfo()
    // Resolve artwork in case metadata was pushed before activation.
    resolveArtwork()
  }

  private func deactivate() {
    sessionListenerDisposers.forEach { $0() }
    sessionListenerDisposers.removeAll()
    stopSilentAudio()
    artworkResolver.cancelInFlight()
    if currentSession != nil {
      DiagnosticLog.nowPlaying.log("cast session ended: write clear")
    }
    currentSession = nil
    MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
  }

  // MARK: - Silent audio loop

  private func startSilentAudio() {
    guard
      let url = Bundle(for: CastNowPlayingController.self)
        .url(forResource: "QueuePlayer", withExtension: "bundle")
        .flatMap({ Bundle(url: $0) })?
        .url(forResource: "silence", withExtension: "m4a")
        ?? Bundle.main.url(forResource: "silence", withExtension: "m4a")
    else {
      NSLog(
        "[CastNowPlayingController] silence.m4a missing from bundle — lockscreen "
        + "metadata will not stay visible during cast. Verify the QueuePlayer pod "
        + "resource_bundles include ios/Resources/silence.m4a."
      )
      return
    }
    let loopPlayer = AVQueuePlayer()
    loopPlayer.isMuted = true
    let looper = AVPlayerLooper(
      player: loopPlayer,
      templateItem: AVPlayerItem(url: url)
    )
    loopPlayer.play()
    silentPlayer = loopPlayer
    silentLooper = looper
  }

  private func stopSilentAudio() {
    silentPlayer?.pause()
    silentPlayer?.replaceCurrentItem(with: nil)
    silentPlayer = nil
    silentLooper = nil
  }

  // MARK: - Session listeners

  private func wireSessionListeners(_ session: CastSessionRemotePlayer) {
    let stateDisposer = session.addStateListener { [weak self] state in
      self?.onState(state)
    }
    let positionDisposer = session.addPositionListener {
      [weak self] positionMs, durationMs, _ in
      self?.onPosition(positionMs: positionMs, durationMs: durationMs)
    }
    sessionListenerDisposers.append(stateDisposer)
    sessionListenerDisposers.append(positionDisposer)
  }

  // MARK: - Now Playing updates

  /// Private snapshot of the values we last pushed to
  /// `MPNowPlayingInfoCenter`. Lets `onPosition` / `onState` /
  /// `refreshNowPlayingInfo` skip the system write when nothing
  /// actually changed. iOS treats a high-rate `nowPlayingInfo` writer
  /// as an unhealthy player.
  private struct PushedSnapshot {
    var title: String?
    var artist: String?
    var album: String?
    var durationSec: Double?
    var rate: Double = 0.0
    var elapsedSec: Double = 0.0
  }
  private var pushed = PushedSnapshot()

  private func onState(_ state: RemotePlaybackStateKind) {
    let rate: Double = (state == .playing) ? 1.0 : 0.0
    if pushed.rate == rate { return }
    pushed.rate = rate
    var info = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
    info[MPNowPlayingInfoPropertyPlaybackRate] = rate
    info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = pushed.elapsedSec
    MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    DiagnosticLog.nowPlaying.log("write cast onState")
  }

  /// Position updates fire at 1 Hz from the session. iOS extrapolates
  /// between updates from rate + elapsed, so we only push when the
  /// integer-second elapsed moves, or when the receiver first reports a
  /// stream duration that differs from what's on the lockscreen —
  /// bounded to ~1 write/sec even if the source pushes more often.
  private func onPosition(positionMs: Int64, durationMs: Int64) {
    let elapsed: Double = Double(positionMs) / 1000.0
    let elapsedMoved = (elapsed - pushed.elapsedSec).magnitude >= 0.5

    let duration: Double = durationMs > 0 ? Double(durationMs) / 1000.0 : 0
    let durationMoved =
      duration > 0 && (duration - (pushed.durationSec ?? 0)).magnitude >= 0.5

    guard elapsedMoved || durationMoved else { return }

    var info = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
    if elapsedMoved {
      pushed.elapsedSec = elapsed
      info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = elapsed
    }
    if durationMoved {
      pushed.durationSec = duration
      info[MPMediaItemPropertyPlaybackDuration] = duration
    }
    MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    DiagnosticLog.nowPlaying.log("write cast onPosition")
  }

  private func refreshNowPlayingInfo() {
    guard let session = currentSession else { return }
    let rate = (session.lastState == .playing) ? 1.0 : 0.0
    let elapsed = Double(session.lastPositionMs) / 1000.0
    let duration = resolvedDurationSec(session: session)

    var info: [String: Any] = [
      MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.audio.rawValue,
      MPNowPlayingInfoPropertyElapsedPlaybackTime: elapsed,
      MPNowPlayingInfoPropertyPlaybackRate: rate,
      MPMediaItemPropertyArtwork: Self.placeholderArtwork,
    ]
    if duration > 0 {
      info[MPMediaItemPropertyPlaybackDuration] = duration
    }

    if let metadata = currentMetadata {
      // Only write keys that have a real value — empty strings push
      // the system's previously-set value out of view (CarPlay
      // collapses the row), leaving the lockscreen blanker than
      // necessary while metadata is in flight.
      if let title = metadata.title { info[MPMediaItemPropertyTitle] = title }
      if let artist = metadata.artist { info[MPMediaItemPropertyArtist] = artist }
      if let album = metadata.album { info[MPMediaItemPropertyAlbumTitle] = album }
      info[MPNowPlayingInfoPropertyExternalContentIdentifier] = metadata.contentId
      pushed.title = metadata.title
      pushed.artist = metadata.artist
      pushed.album = metadata.album
    }

    pushed.durationSec = duration
    pushed.rate = rate
    pushed.elapsedSec = elapsed
    MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    DiagnosticLog.nowPlaying.log("write cast refreshNowPlayingInfo")
  }

  /// Duration for the lockscreen scrubber: the receiver's reported
  /// stream duration when known, else the consumer-supplied track
  /// duration (which is untrusted and frequently 0/unset).
  private func resolvedDurationSec(session: CastSessionRemotePlayer) -> Double {
    if session.lastDurationMs > 0 {
      return Double(session.lastDurationMs) / 1000.0
    }
    return currentMetadata?.durationSec ?? 0
  }

  // MARK: - Artwork

  /// Resolve the current track's artwork URL and swap it onto the
  /// lockscreen. The resolver's sequence counter drops a superseded
  /// fetch when the track advances mid-flight, so a stale cover never
  /// lands on the wrong track.
  private func resolveArtwork() {
    let url = currentMetadata?.artworkUrl
    artworkResolver.resolve(url: url) { [weak self] image in
      self?.applyArtwork(image)
    }
  }

  /// Swap the resolver-supplied image into the current now-playing
  /// dict. No-op when the cast surface was torn down (deactivate niled
  /// the dict) — never resurrect a cleared surface from artwork alone.
  private func applyArtwork(_ image: UIImage) {
    guard currentSession != nil,
          var info = MPNowPlayingInfoCenter.default().nowPlayingInfo
    else { return }
    info[MPMediaItemPropertyArtwork] =
      MPMediaItemArtwork(boundsSize: image.size) { _ in image }
    MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    DiagnosticLog.nowPlaying.log("write cast applyArtwork")
  }
}
