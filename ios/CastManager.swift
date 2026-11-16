import Foundation
import NitroModules

/// `CastManager` HybridObject. Owns the Chromecast + AirPlay route
/// surfaces and bridges them to the Nitro spec.
class CastManager: HybridCastManagerSpec {

  private let chromecast = ChromecastBackend()
  private let airplayRoute = AirPlayRouteState()
  private var discoveryActive = false
  private var currentRoute: CastRoute = CastManager.localFallbackRoute()
  private var receiversSnapshot: [CastReceiver] = []
  /// Live remote-player session set by `handleSessionLifecycle` on
  /// `.connected` and cleared on `.disconnected` / `.error`. Same
  /// reference is registered with `PlaybackStateRouter.shared`, so
  /// `CastTransportRouter` reads through the router snapshot rather
  /// than this field.
  private var activeChromecastRemote: CastSessionRemotePlayer?

  // Listener registries — Nitro's event-listener API returns a
  // `() -> Void` disposer; we keep callbacks in dictionaries keyed by a
  // monotonic id so multiple subscribers can coexist.
  private var routeListeners: [Int64: (CastRoute) -> Void] = [:]
  private var receiversListeners: [Int64: ([CastReceiver]) -> Void] = [:]
  private var discoveryStateListeners: [Int64: (CastDiscoveryState) -> Void] = [:]
  private var sessionDiedListeners: [Int64: (CastSessionDiedEvent) -> Void] = [:]
  private var lnaListeners: [Int64: (CastLocalNetworkPermissionEvent) -> Void] = [:]
  private var audioRouteListeners: [Int64: (AudioRoute) -> Void] = [:]
  private var activeRemoteListeners: [Int64: (CastSessionRemotePlayer?) -> Void] = [:]
  private var nextListenerId: Int64 = 0
  private let listenerLock = NSLock()

  private let connectLock = NSLock()
  /// Pending JS `connect()` promise for an in-flight Chromecast attempt.
  /// Resolved from `handleSessionLifecycle(.connected)` once the route is
  /// live, rejected on `.error` — the same contract as the Android
  /// `connect()` so a consumer's `await connect()` settles only when the
  /// receiver is actually attached. Guarded by `connectLock`: installed
  /// on the JS thread in `connect`, cleared on the main thread from the
  /// session lifecycle handler.
  private var pendingChromecastConnect: Promise<Void>?

  override init() {
    super.init()
    chromecast.onReceiversChanged = { [weak self] receivers in
      self?.handleReceiversChanged(receivers)
    }
    chromecast.onSessionLifecycle = { [weak self] event in
      self?.handleSessionLifecycle(event)
    }
    airplayRoute.onRouteChanged = { [weak self] route in
      guard let self = self else { return }
      // AirPlay route changes only override the current route when we
      // aren't actively cast to a remote receiver (Chromecast). When
      // Chromecast is active, the OS's AirPlay route is irrelevant —
      // audio is on the receiver, not the AVAudioSession output.
      if self.currentRoute.castProtocol != .chromecast {
        self.currentRoute = route
        self.fireRouteChange()
      }
    }
    airplayRoute.onAudioRouteChanged = { [weak self] route in
      self?.fireAudioRouteChange(route)
    }
    airplayRoute.startObserving()
  }

  // MARK: - Capabilities

  func getSupportedProtocols() throws -> [CastProtocol] {
    var supported: [CastProtocol] = [.airplay]
    if isChromecastLinked() { supported.append(.chromecast) }
    return supported
  }

  private func isChromecastLinked() -> Bool {
    return NSClassFromString("GCKCastContext") != nil
  }

  // MARK: - Configuration

  func configure(config: CastConfigureOptions) throws -> Promise<Void> {
    // iOS AirPlay is system-routed via `AVRoutePickerView`: the device
    // name shown to receivers comes from `Settings → General → About`, and
    // the receiver list (including any Mac AirPlay targets) is owned by the
    // OS picker. So neither `deviceName` nor `hideAirPlayComputers` has a
    // plumb-through here — both are Android-only.
    return .resolved(withResult: ())
  }

  // MARK: - Discovery

  func startDiscovery() throws -> Promise<Void> {
    discoveryActive = true
    chromecast.startDiscovery()
    fireDiscoveryState()
    return .resolved(withResult: ())
  }

  func stopDiscovery() throws -> Promise<Void> {
    discoveryActive = false
    chromecast.stopDiscovery()
    fireDiscoveryState()
    return .resolved(withResult: ())
  }

  func isDiscovering() throws -> Bool { discoveryActive }

  func getReceivers() throws -> [CastReceiver] {
    receiversSnapshot = chromecast.getReceivers()
    return receiversSnapshot
  }

  // MARK: - Route

  func getCurrentRoute() throws -> CastRoute { currentRoute }

  // MARK: - Session

  func connect(receiverId: String, options: CastConnectOptions) throws -> Promise<Void> {
    let promise = Promise<Void>()
    if receiverId.hasPrefix("chromecast:") {
      // Hold the promise until the receiver session is actually live so
      // `connect()` settles on the same contract as Android: by resolve
      // time the active route is set and route listeners have fired. The
      // outcome arrives via `handleSessionLifecycle` — `.connected`
      // resolves, `.error` rejects. Supersede any in-flight attempt.
      swapPendingChromecastConnect(promise)?.reject(
        withError: Self.connectFailedError("superseded by a newer Chromecast connect attempt")
      )
      chromecast.connect(receiverId: receiverId)
    } else {
      promise.reject(withError: Self.connectFailedError("unsupported receiverId prefix"))
    }
    return promise
  }

  /// Atomically install `new` as the pending connect promise, returning
  /// the prior one (for the caller to reject as superseded).
  private func swapPendingChromecastConnect(_ new: Promise<Void>?) -> Promise<Void>? {
    connectLock.lock(); defer { connectLock.unlock() }
    let prior = pendingChromecastConnect
    pendingChromecastConnect = new
    return prior
  }

  private static func connectFailedError(_ message: String) -> NSError {
    NSError(
      domain: "CastManager",
      code: 0,
      userInfo: [NSLocalizedDescriptionKey: message, "code": "cast_connect_failed"]
    )
  }

  func disconnect() throws -> Promise<Void> {
    chromecast.disconnect()
    return .resolved(withResult: ())
  }

  func showSystemPicker() throws -> Promise<Void> {
    airplayRoute.showSystemPicker()
    return .resolved(withResult: ())
  }

  // MARK: - Volume

  /// Most-recent receiver-reported device volume on Chromecast (read
  /// through the active `CastSessionRemotePlayer`). Returns `NaN` for
  /// AirPlay — iOS AirPlay volume is system-managed (hardware
  /// buttons), unobservable from the lib — and for local playback.
  func getRouteVolume() throws -> Double {
    if let active = activeChromecastRemote {
      let v = active.lastVolume
      return v.isNaN ? Double.nan : Double(v)
    }
    return Double.nan
  }

  func setRouteVolume(value: Double) throws -> Promise<Void> {
    guard currentRoute.capabilities.supportsRemoteVolume else {
      return .resolved(withResult: ())
    }
    if currentRoute.castProtocol == .chromecast {
      chromecast.setVolume(value)
    }
    return .resolved(withResult: ())
  }

  // MARK: - AirPlay pairing

  func submitPairingPin(pin: String) throws -> Promise<Void> {
    // iOS AirPlay pairing is fully OS-handled by AVRoutePickerView; the
    // lib has no PIN/password surface to plumb the credential into.
    return .resolved(withResult: ())
  }

  // MARK: - iOS Local Network permission

  private let lnaProbe = LocalNetworkPermissionProbe()
  private var lnaProbeListenerDisposer: (() -> Void)?

  /// Snapshot of the most-recently resolved Local Network permission
  /// state. Returns `.undetermined` if no probe has resolved yet, or
  /// the cached result has expired past its TTL.
  func getLocalNetworkPermissionState() throws -> CastLocalNetworkPermission {
    return lnaProbe.cachedState
  }

  /// Trigger a paired `NWBrowser` + `NWListener` probe per Apple's
  /// TN3179 ("Understanding Local Network Privacy"). First call on a
  /// fresh app install raises the iOS system prompt for Local Network
  /// access; subsequent calls within the TTL skip the probe and
  /// resolve with the cached value.
  func requestLocalNetworkPermission() throws -> Promise<CastLocalNetworkPermission> {
    return lnaProbe.requestPermission()
  }

  // MARK: - Audio route

  func getCurrentAudioRoute() throws -> AudioRoute {
    return airplayRoute.currentAudioRoute()
  }

  func onAudioRouteChange(callback: @escaping (_ route: AudioRoute) -> Void) throws -> () -> Void {
    let id = nextId()
    listenerLock.lock()
    audioRouteListeners[id] = callback
    listenerLock.unlock()
    callback(airplayRoute.currentAudioRoute())
    return { [weak self] in
      guard let self = self else { return }
      self.listenerLock.lock()
      self.audioRouteListeners.removeValue(forKey: id)
      self.listenerLock.unlock()
    }
  }

  private func fireAudioRouteChange(_ route: AudioRoute) {
    listenerLock.lock()
    let listeners = Array(audioRouteListeners.values)
    listenerLock.unlock()
    for l in listeners { l(route) }
  }

  // MARK: - Event subscriptions

  func onRouteChange(callback: @escaping (_ route: CastRoute) -> Void) throws -> () -> Void {
    let id = nextId()
    listenerLock.lock()
    routeListeners[id] = callback
    listenerLock.unlock()
    callback(currentRoute)
    return { [weak self] in
      guard let self = self else { return }
      self.listenerLock.lock()
      self.routeListeners.removeValue(forKey: id)
      self.listenerLock.unlock()
    }
  }

  func onReceiversChange(callback: @escaping (_ receivers: [CastReceiver]) -> Void) throws -> () -> Void {
    let id = nextId()
    listenerLock.lock()
    receiversListeners[id] = callback
    listenerLock.unlock()
    callback(receiversSnapshot)
    return { [weak self] in
      guard let self = self else { return }
      self.listenerLock.lock()
      self.receiversListeners.removeValue(forKey: id)
      self.listenerLock.unlock()
    }
  }

  func onDiscoveryStateChange(
    callback: @escaping (_ state: CastDiscoveryState) -> Void
  ) throws -> () -> Void {
    let id = nextId()
    listenerLock.lock()
    discoveryStateListeners[id] = callback
    listenerLock.unlock()
    callback(CastDiscoveryState(isDiscovering: discoveryActive))
    return { [weak self] in
      guard let self = self else { return }
      self.listenerLock.lock()
      self.discoveryStateListeners.removeValue(forKey: id)
      self.listenerLock.unlock()
    }
  }

  func onSessionDied(
    callback: @escaping (_ event: CastSessionDiedEvent) -> Void
  ) throws -> () -> Void {
    let id = nextId()
    listenerLock.lock()
    sessionDiedListeners[id] = callback
    listenerLock.unlock()
    return { [weak self] in
      guard let self = self else { return }
      self.listenerLock.lock()
      self.sessionDiedListeners.removeValue(forKey: id)
      self.listenerLock.unlock()
    }
  }

  func onLocalNetworkPermissionChange(
    callback: @escaping (_ event: CastLocalNetworkPermissionEvent) -> Void
  ) throws -> () -> Void {
    let id = nextId()
    listenerLock.lock()
    lnaListeners[id] = callback
    let isFirstListener = (lnaListeners.count == 1)
    listenerLock.unlock()
    // Lazily wire the probe's grant/deny stream into the lib's
    // listener registry. Same probe instance serves every listener;
    // the disposer keeps the cross-wire alive for the manager's
    // lifetime (probe is per-CastManager, not a process singleton,
    // so the wire goes away when this manager is deallocated).
    if isFirstListener && lnaProbeListenerDisposer == nil {
      lnaProbeListenerDisposer = lnaProbe.addListener { [weak self] state in
        self?.fireLNAStateChange(state)
      }
    }
    callback(CastLocalNetworkPermissionEvent(state: lnaProbe.cachedState))
    return { [weak self] in
      guard let self = self else { return }
      self.listenerLock.lock()
      self.lnaListeners.removeValue(forKey: id)
      self.listenerLock.unlock()
    }
  }

  // MARK: - Backend handlers

  private func handleReceiversChanged(_ receivers: [CastReceiver]) {
    receiversSnapshot = receivers
    listenerLock.lock()
    let listeners = Array(receiversListeners.values)
    listenerLock.unlock()
    for l in listeners { l(receivers) }
  }

  /// Internal so unit tests can drive the same lifecycle handler with
  /// a `FakeRemoteSession` and verify the router/listener wiring
  /// without a live `GCKCastSession`.
  internal func handleSessionLifecycle(_ event: ChromecastSessionLifecycle) {
    switch event {
    case .connected(let route, let session):
      currentRoute = route
      // Register with the global state router so `CastTransportRouter`
      // routes JS-side transport through this session. First-come-wins;
      // if a session is already registered the call throws — log and
      // continue with the route change so the UI stays consistent.
      do {
        try PlaybackStateRouter.shared.setActive(session)
        activeChromecastRemote = session
      } catch {
        NSLog("[CastManager] PlaybackStateRouter rejected new session: \(error)")
      }
      fireRouteChange()
      fireActiveRemoteChange(session)
      // Settle the in-flight `connect()` now that the route is live and
      // listeners have fired, so the consumer's `await connect()` returns
      // with the active route already observable.
      swapPendingChromecastConnect(nil)?.resolve(withResult: ())
    case .disconnected(let reason):
      let endReason = sessionEndReason(from: reason)
      let previousActive = activeChromecastRemote
      activeChromecastRemote = nil
      if let session = previousActive {
        // Cast->local always lands paused-at-position; the end reason only
        // feeds the JS session-died event below.
        PlaybackStateRouter.shared.clearActive(expected: session)
      }
      currentRoute = CastManager.localFallbackRoute()
      fireRouteChange()
      fireActiveRemoteChange(nil)
      let event = CastSessionDiedEvent(receiverId: "", reason: endReason)
      listenerLock.lock()
      let listeners = Array(sessionDiedListeners.values)
      listenerLock.unlock()
      for l in listeners { l(event) }
    case .error(let reason):
      let previousActive = activeChromecastRemote
      activeChromecastRemote = nil
      if let session = previousActive {
        // Connect/resume failure — tear down + surface the error to JS.
        PlaybackStateRouter.shared.clearActive(expected: session)
      }
      currentRoute = CastManager.localFallbackRoute()
      fireRouteChange()
      fireActiveRemoteChange(nil)
      let event = CastSessionDiedEvent(receiverId: "", reason: sessionEndReason(from: reason))
      listenerLock.lock()
      let listeners = Array(sessionDiedListeners.values)
      listenerLock.unlock()
      for l in listeners { l(event) }
      // A connect-time failure (receiver-not-found, start rejected, GCK
      // didFailToStartSession) rejects the in-flight `connect()`. A
      // mid-session error after a successful connect finds no pending
      // promise (already resolved on `.connected`) — the swap returns nil.
      swapPendingChromecastConnect(nil)?.reject(
        withError: Self.connectFailedError("Chromecast connect failed: \(reason)")
      )
    }
  }

  /// Register a listener for active-remote transitions. Fires on
  /// every `nil → session` / `session → nil` flip. The current value
  /// is NOT replayed on subscribe — call `activeChromecastRemote`
  /// (test-internal) or query `PlaybackStateRouter.shared.activeRemote()`
  /// for the snapshot.
  func onActiveRemoteChange(
    _ callback: @escaping (CastSessionRemotePlayer?) -> Void
  ) -> () -> Void {
    let id = nextId()
    listenerLock.lock()
    activeRemoteListeners[id] = callback
    listenerLock.unlock()
    return { [weak self] in
      guard let self else { return }
      self.listenerLock.lock()
      self.activeRemoteListeners.removeValue(forKey: id)
      self.listenerLock.unlock()
    }
  }

  private func fireActiveRemoteChange(_ session: CastSessionRemotePlayer?) {
    listenerLock.lock()
    let listeners = Array(activeRemoteListeners.values)
    listenerLock.unlock()
    for l in listeners { l(session) }
  }

  private func sessionEndReason(from raw: String) -> CastSessionEndReason {
    switch raw {
    case "networkLoss": return .networkloss
    case "ended": return .ended
    case "stolen": return .stolen
    case "feedbackTimeout": return .feedbacktimeout
    default: return .unknown
    }
  }

  private func fireRouteChange() {
    listenerLock.lock()
    let listeners = Array(routeListeners.values)
    listenerLock.unlock()
    let route = currentRoute
    for l in listeners { l(route) }
  }

  private func fireLNAStateChange(_ state: CastLocalNetworkPermission) {
    listenerLock.lock()
    let listeners = Array(lnaListeners.values)
    listenerLock.unlock()
    let event = CastLocalNetworkPermissionEvent(state: state)
    for l in listeners { l(event) }
  }

  private func fireDiscoveryState() {
    listenerLock.lock()
    let listeners = Array(discoveryStateListeners.values)
    let state = CastDiscoveryState(isDiscovering: discoveryActive)
    listenerLock.unlock()
    for l in listeners { l(state) }
  }

  // MARK: - Listener bookkeeping

  private func nextId() -> Int64 {
    listenerLock.lock()
    nextListenerId += 1
    let id = nextListenerId
    listenerLock.unlock()
    return id
  }

  private static func localFallbackRoute() -> CastRoute {
    CastRoute(
      castProtocol: .local,
      receiverId: "",
      name: "Phone speaker",
      iconHint: .phone,
      capabilities: CastReceiverCapabilities(
        supportsRemoteVolume: false,
        streamingModel: .pushpcm,
        requiresPairing: false
      ),
      protocolVersion: ""
    )
  }
}
