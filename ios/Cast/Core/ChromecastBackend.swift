import Foundation
import UIKit
#if canImport(GoogleCast)
import GoogleCast
#endif

/// Owns the iOS Chromecast surface — discovery, session, transport,
/// receiver state observation. Single instance created by
/// `CastManager` and held for the lifetime of the HybridObject.
/// `CastManager` wires its listeners into the backend's typed callback
/// slots; nothing routes through NSNotificationCenter.
///
/// The backend is the SoT for: discovered receivers, the current
/// `GCKCastSession`, and the `RemoteMediaObserver` that translates
/// receiver state into callbacks. `CastManagerImpl` wires its
/// listeners into the backend's callback slots.
final class ChromecastBackend: NSObject {

  /// Called when the receiver list changes (discovery deltas).
  var onReceiversChanged: (([CastReceiver]) -> Void)?

  /// Called when the session lifecycle changes:
  ///  - `.connected(route)` — session attached, receiver ready
  ///  - `.disconnected(reason)` — session ended cleanly or
  ///    suspended; `reason` is a stable string
  ///  - `.error(reason)` — connect/resume failed
  var onSessionLifecycle: ((ChromecastSessionLifecycle) -> Void)?

  // MARK: - Internal state

  #if canImport(GoogleCast)
  private let discoveryAdapter = ChromecastDiscoveryAdapter()
  private let sessionAdapter = ChromecastSessionAdapter()
  private var discoveryAttached = false
  private var sessionAttached = false
  private var currentDevices: [GCKDevice] = []
  private var cachedReceivers: [CastReceiver] = []
  private var currentSession: GCKCastSession?
  /// Live `ChromecastSession` minted in `handleSessionConnected`,
  /// cleared in `handleSessionDisconnected`. The actual receiver-side
  /// `LocalMediaServer` handle lives on the session — the backend
  /// forwards GCK session callbacks to it.
  private var activeChromecastSession: ChromecastSession?
  /// Main only. Set while a foreground probe of the media server runs, so
  /// repeated foregrounds do not run overlapping probes that could each
  /// disconnect.
  private var foregroundProbeInFlight = false
  /// Main only. The session being ended because its media server stopped
  /// answering, so a second failed check does not end it again before GCK
  /// reports the end.
  private weak var sessionEndingForDeadServer: ChromecastSession?
  private var foregroundObserver: NSObjectProtocol?
  #endif

  override init() {
    super.init()
    #if canImport(GoogleCast)
    // Wire adapter callbacks to internal handlers.
    discoveryAdapter.onUpdate = { [weak self] devices in
      self?.handleDeviceListUpdate(devices)
    }
    sessionAdapter.onConnected = { [weak self] session in
      self?.handleSessionConnected(session)
    }
    sessionAdapter.onDisconnected = { [weak self] reason in
      self?.handleSessionDisconnected(reason: reason)
    }
    sessionAdapter.onError = { [weak self] reason in
      self?.handleSessionError(reason: reason)
    }
    // Receiver-side volume push from the session manager (fires
    // independently of MediaStatus, so pre-load volume changes still
    // surface). Forwarded into the active ChromecastSession via its
    // `acceptDeviceVolume` API; the session dedups internally.
    sessionAdapter.onVolumeChanged = { [weak self] volume, _ in
      self?.activeChromecastSession?.acceptDeviceVolume(volume)
    }
    foregroundObserver = NotificationCenter.default.addObserver(
      forName: UIApplication.willEnterForegroundNotification, object: nil, queue: .main
    ) { [weak self] _ in
      self?.checkMediaServerOnForeground()
    }
    #endif
  }

  deinit {
    #if canImport(GoogleCast)
    if let foregroundObserver {
      NotificationCenter.default.removeObserver(foregroundObserver)
    }
    #endif
  }

  // MARK: - Discovery

  func startDiscovery() {
    #if canImport(GoogleCast)
    guard Thread.isMainThread else {
      DispatchQueue.main.async { self.startDiscovery() }
      return
    }
    let manager = GCKCastContext.sharedInstance().discoveryManager
    if !discoveryAttached {
      manager.add(discoveryAdapter)
      discoveryAttached = true
      // Seed snapshot from any already-cached devices.
      discoveryAdapter.didUpdateDeviceList()
    }
    manager.startDiscovery()
    #endif
  }

  func stopDiscovery() {
    #if canImport(GoogleCast)
    guard Thread.isMainThread else {
      DispatchQueue.main.async { self.stopDiscovery() }
      return
    }
    let manager = GCKCastContext.sharedInstance().discoveryManager
    manager.stopDiscovery()
    if discoveryAttached {
      manager.remove(discoveryAdapter)
      discoveryAttached = false
    }
    #endif
  }

  func getReceivers() -> [CastReceiver] {
    #if canImport(GoogleCast)
    return cachedReceivers
    #else
    return []
    #endif
  }

  // MARK: - Session lifecycle

  /// Connect to a receiver by id (the namespaced form
  /// `chromecast:<unique-id>`). Resolves async — caller observes
  /// `onSessionLifecycle` for the outcome.
  func connect(receiverId: String) {
    #if canImport(GoogleCast)
    guard Thread.isMainThread else {
      DispatchQueue.main.async { self.connect(receiverId: receiverId) }
      return
    }
    // Strip "chromecast:" prefix to get the Cast SDK unique id.
    let prefix = "chromecast:"
    let uniqueId = receiverId.hasPrefix(prefix)
      ? String(receiverId.dropFirst(prefix.count))
      : receiverId

    let manager = GCKCastContext.sharedInstance().sessionManager
    if !sessionAttached {
      manager.add(sessionAdapter)
      sessionAttached = true
    }
    guard let device = currentDevices.first(where: { $0.uniqueID == uniqueId }) else {
      onSessionLifecycle?(.error(reason: "receiver-not-found"))
      return
    }
    let started = manager.startSession(with: device)
    if !started {
      onSessionLifecycle?(.error(reason: "start-session-rejected"))
    }
    #else
    onSessionLifecycle?(.error(reason: "google-cast-not-linked"))
    #endif
  }

  func disconnect() {
    #if canImport(GoogleCast)
    guard Thread.isMainThread else {
      DispatchQueue.main.async { self.disconnect() }
      return
    }
    let manager = GCKCastContext.sharedInstance().sessionManager
    manager.endSessionAndStopCasting(true)
    #endif
  }

  // MARK: - Transport

  func setVolume(_ value: Double) {
    #if canImport(GoogleCast)
    let clamped = max(0.0, min(1.0, value))
    currentSession?.setDeviceVolume(Float(clamped))
    #endif
  }

  func play() {
    #if canImport(GoogleCast)
    currentSession?.remoteMediaClient?.play()
    #endif
  }

  func pause() {
    #if canImport(GoogleCast)
    currentSession?.remoteMediaClient?.pause()
    #endif
  }

  func stop() {
    #if canImport(GoogleCast)
    currentSession?.remoteMediaClient?.stop()
    #endif
  }

  func seekTo(positionMs: Int64) {
    #if canImport(GoogleCast)
    let opts = GCKMediaSeekOptions()
    opts.interval = TimeInterval(positionMs) / 1000.0
    opts.resumeState = .unchanged
    currentSession?.remoteMediaClient?.seek(with: opts)
    #endif
  }

  // MARK: - Internal handlers

  #if canImport(GoogleCast)
  private func handleDeviceListUpdate(_ devices: [GCKDevice]) {
    currentDevices = devices
    cachedReceivers = devices.map { ChromecastReceiverMapper.mapToReceiver($0) }
    onReceiversChanged?(cachedReceivers)
  }

  private func handleSessionConnected(_ session: GCKCastSession) {
    currentSession = session
    let castSession = ChromecastSession(session: session)
    activeChromecastSession = castSession
    castSession.onMediaServerNotAnswering = { [weak self, weak castSession] in
      guard let castSession else { return }
      self?.endSessionForDeadServer(castSession)
    }

    let route = CastRoute(
      castProtocol: .chromecast,
      receiverId: "chromecast:\(session.device.uniqueID)",
      name: session.device.friendlyName ?? session.device.deviceID,
      iconHint: .tv,
      capabilities: CastReceiverCapabilities(
        supportsRemoteVolume: true,
        streamingModel: .pullurl,
        requiresPairing: false
      ),
      protocolVersion: ""
    )
    onSessionLifecycle?(.connected(route: route, session: castSession))
  }

  /// A suspended app's listening socket can be reclaimed while the listener
  /// keeps reporting `.ready`, leaving the receiver's URLs on a dead port.
  /// While a session holds the media server, probe it on every return to the
  /// foreground, and end the session if it does not answer: reconnecting binds
  /// a fresh listener and re-sends the queue. A session that loaded nothing
  /// holds no server and has no URLs to lose.
  private func checkMediaServerOnForeground() {
    guard let session = activeChromecastSession, !foregroundProbeInFlight else { return }
    guard session.holdsMediaServer else {
      NSLog("[ChromecastBackend] foreground probe skipped: media server not held")
      return
    }
    foregroundProbeInFlight = true
    LocalMediaServer.shared.checkAnswering { [weak self] answering in
      guard let self else { return }
      self.foregroundProbeInFlight = false
      NSLog("[ChromecastBackend] foreground probe answering=\(answering ? "yes" : "no")")
      guard !answering else { return }
      self.endSessionForDeadServer(session)
    }
  }

  /// Ends `session` once, and only while it is still the live one: a session
  /// GCK replaced in the meantime has acquired, and so probed, the listener
  /// itself.
  private func endSessionForDeadServer(_ session: ChromecastSession) {
    guard activeChromecastSession === session, sessionEndingForDeadServer !== session else { return }
    sessionEndingForDeadServer = session
    NSLog("[ChromecastBackend] media server not answering; ending the cast session")
    disconnect()
  }

  private func handleSessionDisconnected(reason: String) {
    // With no live session there is nothing to report: its end was already
    // sent (`didSuspend`, or a start or resume error), or it never reached
    // `.connected` (an aborted saved-session restore).
    guard let session = activeChromecastSession else { return }
    activeChromecastSession = nil
    currentSession = nil
    // Notify consumers BEFORE closing the session so they get a chance
    // to read final `last*` snapshots / unregister from the router
    // while the session's listener slots are still wired.
    onSessionLifecycle?(.disconnected(reason: normalize(reason: reason)))
    session.close()
  }

  private func handleSessionError(reason: String) {
    // A resume failure with no live session follows a `didSuspend` whose
    // disconnect was already reported, or a cold-launch restore the app never
    // saw connect. A start failure is always forwarded: it rejects the pending
    // `connect()`.
    if activeChromecastSession == nil, reason.hasPrefix("resume-failed:") { return }
    let session = activeChromecastSession
    activeChromecastSession = nil
    currentSession = nil
    onSessionLifecycle?(.error(reason: reason))
    session?.close()
  }
  #endif

  private func normalize(reason: String) -> String {
    if reason.hasPrefix("suspended:") { return "networkLoss" }
    if reason.hasPrefix("ended") { return "ended" }
    return reason
  }
}

/// Outcomes of a session-lifecycle event. The `route` describes the
/// receiver for picker UI; the `session` (only on `.connected`) is the
/// live `CastSessionRemotePlayer` the rest of the lib drives transport
/// against.
enum ChromecastSessionLifecycle {
  case connected(route: CastRoute, session: CastSessionRemotePlayer)
  case disconnected(reason: String)
  case error(reason: String)
}
