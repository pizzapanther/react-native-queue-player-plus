import Foundation
#if canImport(GoogleCast)
import GoogleCast

/// Bridges `GCKRemoteMediaClient` status updates onto callbacks +
/// polls `approximateStreamPosition` at 1 Hz while attached. Cast SDK
/// on iOS has no push-driven progress; `approximateStreamPosition`
/// is cheap (SDK interpolates from last known + elapsed time).
extension RemotePlaybackStateKind {
  static func from(mediaStatus: GCKMediaStatus) -> RemotePlaybackStateKind {
    switch mediaStatus.playerState {
    case .playing: return .playing
    case .paused: return .paused
    case .buffering: return .buffering
    case .loading: return .loading
    case .idle:
      switch mediaStatus.idleReason {
      case .finished: return .ended
      case .error: return .error
      default: return .idle
      }
    default: return .idle
    }
  }
}

extension RemoteEndReasonKind {
  static func from(idleReason: GCKMediaPlayerIdleReason) -> RemoteEndReasonKind {
    switch idleReason {
    case .finished: return .finished
    case .interrupted: return .interrupted
    case .cancelled: return .canceled
    case .error: return .error
    default: return .canceled
    }
  }
}

final class RemoteMediaObserver: NSObject, GCKRemoteMediaClientListener {

  var onStateChanged: ((RemotePlaybackStateKind) -> Void)?
  var onPositionChanged: ((_ positionMs: Int64, _ durationMs: Int64, _ bufferedMs: Int64) -> Void)?
  /// Absolute index of the current item in the receiver's full queue,
  /// resolved via `GCKMediaQueue` (which stays in sync with the whole
  /// queue, not the ~3-item status window). This is the lib-track index.
  var onCurrentTrackIndexChanged: ((Int) -> Void)?
  var onEndedReason: ((RemoteEndReasonKind) -> Void)?

  /// Receiver-side media errors. Not driven by `didUpdate mediaStatus`
  /// (which only reports state/idle-reason); the owning
  /// `ChromecastSession` plumbs request-failure outcomes into this
  /// slot so consumers have a single observation surface.
  var onMediaError: ((RemoteMediaErrorPayload) -> Void)?

  /// Fires once per completed seek with the resolved position (ms).
  /// Wired by the owning `ChromecastSession`'s `seek(with:)` request
  /// delegate; the observer is the convenient sink, not the source.
  var onSeekCompleted: ((Int64) -> Void)?

  private weak var currentClient: GCKRemoteMediaClient?
  private var progressTimer: Timer?

  private var lastDurationMs: Int64 = 0
  private var lastBufferedMs: Int64 = 0
  // Dedup for the absolute-index emit. Persists across a same-session
  // queue reload (`setQueue` during cast): if the new queue starts at the
  // same absolute index the prior one was on, the emit is suppressed —
  // benign because that `setQueue` drives its own current-track update;
  // the cast emit only carries receiver-driven advances.
  private var lastTrackIndex: Int = -1
  private var lastIdleReasonEmitted: GCKMediaPlayerIdleReason = .none

  func attach(_ client: GCKRemoteMediaClient) {
    if currentClient === client { return }
    detach()
    currentClient = client
    client.add(self)
    startProgressTimer()
  }

  func detach() {
    progressTimer?.invalidate()
    progressTimer = nil
    currentClient?.remove(self)
    currentClient = nil
  }

  private func startProgressTimer() {
    progressTimer?.invalidate()
    progressTimer = nil
    let timer = Timer(timeInterval: 1.0, repeats: true) { [weak self] _ in
      guard let self = self, let client = self.currentClient else { return }
      let ms = Int64(client.approximateStreamPosition() * 1000.0)
      if ms >= 0 {
        self.onPositionChanged?(ms, self.lastDurationMs, self.lastBufferedMs)
      }
    }
    RunLoop.main.add(timer, forMode: .common)
    progressTimer = timer
  }

  private func durationMs(from status: GCKMediaStatus) -> Int64 {
    let d = status.mediaInformation?.streamDuration ?? 0
    guard d.isFinite, d > 0 else { return 0 }
    return Int64(d * 1000.0)
  }

  private func bufferedMs(from status: GCKMediaStatus) -> Int64 {
    let pos = status.streamPosition
    guard pos.isFinite, pos >= 0 else { return 0 }
    return Int64(pos * 1000.0)
  }

  // MARK: - GCKRemoteMediaClientListener

  func remoteMediaClient(_ client: GCKRemoteMediaClient, didUpdate mediaStatus: GCKMediaStatus?) {
    guard let status = mediaStatus else {
      onStateChanged?(.idle)
      return
    }
    lastDurationMs = durationMs(from: status)
    lastBufferedMs = bufferedMs(from: status)
    onStateChanged?(RemotePlaybackStateKind.from(mediaStatus: status))

    // Resolve the current item's ABSOLUTE index in the full queue via
    // `GCKMediaQueue` (the SDK keeps the whole queue in sync; the status
    // only exposes a ~3-item window). `indexOfItem(withID:)` returns
    // `NSNotFound` until the queue model knows the id — keep the prior
    // index then. This is the lib-track index, equivalent to Android's
    // `Player.currentMediaItemIndex`.
    let qIndex = client.mediaQueue.indexOfItem(withID: status.currentItemID)
    if qIndex != NSNotFound, qIndex != lastTrackIndex {
      lastTrackIndex = qIndex
      onCurrentTrackIndexChanged?(qIndex)
    }

    if status.playerState == .idle, status.idleReason != .none {
      if status.idleReason != lastIdleReasonEmitted {
        lastIdleReasonEmitted = status.idleReason
        onEndedReason?(RemoteEndReasonKind.from(idleReason: status.idleReason))
      }
    } else if status.playerState != .idle {
      lastIdleReasonEmitted = .none
    }
  }
}
#endif
