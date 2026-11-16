import Foundation
#if canImport(GoogleCast)
import GoogleCast

/// Adapter over `GCKSessionManagerListener` collapsing the SDK's nine
/// session-lifecycle callbacks into three outcomes:
///
/// - **connected** — `didStart` or `didResumeCastSession` fired.
/// - **disconnected** — `didEnd` or `didSuspend` fired.
/// - **error** — any `*FailTo*` variant.
///
/// `GCKErrorCodeCancelled` (SDK 4.8.4) is a non-retry contract: when
/// `didFailToStart` or `didFailToResume` fires with that code, the
/// backend MUST NOT re-call `startSession`/`resumeSession` on the same
/// device in response. Auto-retry on cancel is exactly what the SDK
/// is asking us not to do.
///
/// Cold-launch auto-resume is disabled: `willResumeCastSession` fires
/// both for (a) the SDK's saved-session restore attempt on cold launch
/// and (b) recovery from a mid-process suspend (network blip).
/// `hasEverConnected` discriminates the two — false means case (a),
/// so the abort runs; true means case (b), so the recovery is allowed.
/// Matches Android's `CastOptions.setResumeSavedSession(false)` which
/// only affects saved-session restore.
final class ChromecastSessionAdapter: NSObject, GCKSessionManagerListener {

  var onConnected: ((GCKCastSession) -> Void)?
  var onDisconnected: ((String) -> Void)?
  var onError: ((String) -> Void)?

  /// True after the first in-process didStart / didResume has been
  /// observed. Used to distinguish cold-launch saved-session restore
  /// (false → abort the resume) from mid-process suspend recovery
  /// (true → let the SDK resume).
  private var hasEverConnected = false

  /// Receiver-side device volume push from `GCKSessionManager` — fires
  /// independently of `GCKMediaStatus`, so volume changes that arrive
  /// before any media has loaded still surface. Carries `(volume,
  /// muted)`.
  var onVolumeChanged: ((Float, Bool) -> Void)?

  func sessionManager(_ sessionManager: GCKSessionManager, didStart session: GCKSession) {
    guard let cast = session as? GCKCastSession else { return }
    hasEverConnected = true
    onConnected?(cast)
  }

  func sessionManager(
    _ sessionManager: GCKSessionManager,
    session: GCKSession,
    didReceiveDeviceVolume volume: Float,
    muted: Bool
  ) {
    onVolumeChanged?(volume, muted)
  }

  func sessionManager(
    _ sessionManager: GCKSessionManager,
    didFailToStart session: GCKSession,
    withError error: Error
  ) {
    let ns = error as NSError
    onError?("start-failed:\(ns.code)")
  }

  func sessionManager(
    _ sessionManager: GCKSessionManager,
    didEnd session: GCKSession,
    withError error: Error?
  ) {
    if let ns = error as NSError? {
      onDisconnected?("ended:\(ns.code)")
    } else {
      onDisconnected?("ended")
    }
  }

  func sessionManager(
    _ sessionManager: GCKSessionManager,
    willResumeCastSession session: GCKCastSession
  ) {
    // First in-process willResume with no prior didStart is the SDK's
    // saved-session restore on cold launch — abort. Once an in-process
    // session has been observed (`hasEverConnected`), any subsequent
    // willResume is a mid-process suspend recovery and is allowed.
    if !hasEverConnected {
      sessionManager.endSessionAndStopCasting(false)
    }
  }

  func sessionManager(_ sessionManager: GCKSessionManager, didResumeCastSession session: GCKCastSession) {
    hasEverConnected = true
    onConnected?(session)
  }

  func sessionManager(
    _ sessionManager: GCKSessionManager,
    didFailToResumeSession session: GCKSession,
    withError error: Error
  ) {
    let ns = error as NSError
    onError?("resume-failed:\(ns.code)")
  }

  func sessionManager(
    _ sessionManager: GCKSessionManager,
    didSuspend session: GCKSession,
    with reason: GCKConnectionSuspendReason
  ) {
    onDisconnected?("suspended:\(reason.rawValue)")
  }
}
#endif
