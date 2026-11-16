import Foundation
import Network
import NitroModules

/// iOS Local Network permission preflight per Apple's TN3179
/// ("Understanding Local Network Privacy"). Pairs an `NWListener`
/// advertising a custom Bonjour service with an `NWBrowser` looking
/// for the same service. If the browser sees the listener's advert,
/// the system has granted Local Network permission; if the browser
/// fails with an LNA-specific error code, permission has been denied;
/// otherwise the state stays `.undetermined`.
///
/// First call to `requestPermission()` triggers the iOS system prompt
/// if the app hasn't been asked yet. Subsequent calls within the TTL
/// return the cached result.
///
/// Caller (`CastManager`) wires the probe's state-change callback to
/// the existing `onLocalNetworkPermissionChange` listener registry so
/// JS consumers see grant / deny transitions.
final class LocalNetworkPermissionProbe {

  /// Bonjour service type for the paired probe. Custom + app-scoped
  /// so it never collides with another app's discovery / responses.
  static let serviceType = "_rnqp-lnp._tcp"

  /// Cached permission state TTL. Permission is system-mutable (the
  /// user can revoke in Settings while the app runs), so the probe
  /// re-runs after this window if the state is read past the TTL.
  static let cacheTTLSeconds: TimeInterval = 60.0

  /// Default per-probe timeout — the system prompt is async on first
  /// run; this caps the wait for browser activity.
  static let probeTimeoutSeconds: TimeInterval = 8.0

  /// Multi-listener registry mirrored from `CastManager`'s typed
  /// callback pattern. `CastManager.onLocalNetworkPermissionChange`
  /// adds one listener; future native consumers can add more.
  private var listeners: [Int64: (CastLocalNetworkPermission) -> Void] = [:]
  private var nextListenerId: Int64 = 0
  private let listenerLock = NSLock()

  private let queue = DispatchQueue(label: "queueplayer.lna-probe")

  /// Lock-protected mutable state. The probe runs entirely on `queue`
  /// so all mutations happen serially; reads from other threads take
  /// the lock.
  private let stateLock = NSLock()
  private var _cachedState: CastLocalNetworkPermission = .undetermined
  private var _cachedAt: Date?
  private var _activeProbe: ProbeRun?

  init() {}

  /// The most recently resolved permission state, or `.undetermined` if no
  /// probe has resolved yet.
  ///
  /// Deliberately not TTL-gated. The TTL governs whether
  /// `requestPermission()` re-probes; expiring the *answer* as well would
  /// report `.undetermined` for a user who granted access a minute ago, and any
  /// consumer rule of the form "granted suppresses the prompt" would then
  /// invert — re-prompting exactly the people who already said yes.
  var cachedState: CastLocalNetworkPermission {
    stateLock.lock(); defer { stateLock.unlock() }
    return _cachedState
  }

  /// Trigger the probe. Returns immediately with a `Promise` that
  /// resolves to the final permission state. First call on a fresh
  /// app install raises the iOS system prompt for Local Network
  /// access; subsequent calls within the TTL skip the probe and
  /// resolve with the cached value.
  func requestPermission() -> Promise<CastLocalNetworkPermission> {
    let promise = Promise<CastLocalNetworkPermission>()

    stateLock.lock()
    if let at = _cachedAt, Date().timeIntervalSince(at) < Self.cacheTTLSeconds {
      let cached = _cachedState
      stateLock.unlock()
      promise.resolve(withResult: cached)
      return promise
    }
    if let active = _activeProbe {
      active.subscribe(promise)
      stateLock.unlock()
      return promise
    }
    let probe = ProbeRun(parent: self, queue: queue)
    _activeProbe = probe
    probe.subscribe(promise)
    stateLock.unlock()

    queue.async { probe.start() }
    return promise
  }

  // MARK: - Listener registry

  func addListener(
    _ callback: @escaping (CastLocalNetworkPermission) -> Void
  ) -> () -> Void {
    listenerLock.lock()
    nextListenerId += 1
    let id = nextListenerId
    listeners[id] = callback
    listenerLock.unlock()
    return { [weak self] in
      guard let self else { return }
      self.listenerLock.lock()
      self.listeners.removeValue(forKey: id)
      self.listenerLock.unlock()
    }
  }

  // MARK: - Probe completion (called by ProbeRun on `queue`)

  fileprivate func completeProbe(with state: CastLocalNetworkPermission) {
    var snapshotListeners: [(CastLocalNetworkPermission) -> Void] = []
    let previousState: CastLocalNetworkPermission
    stateLock.lock()
    previousState = _cachedState
    _cachedState = state
    _cachedAt = Date()
    _activeProbe = nil
    stateLock.unlock()
    if previousState != state {
      listenerLock.lock()
      snapshotListeners = Array(listeners.values)
      listenerLock.unlock()
      for l in snapshotListeners { l(state) }
    }
  }
}

/// One in-flight probe. Owns the paired NWBrowser + NWListener and
/// fans the outcome into all subscribed promises.
fileprivate final class ProbeRun {
  private weak var parent: LocalNetworkPermissionProbe?
  private let queue: DispatchQueue
  private var listener: NWListener?
  private var browser: NWBrowser?
  private var timeoutItem: DispatchWorkItem?
  /// Guards `promises` + `resolved` so a late `subscribe` cannot land
  /// a promise into a probe that already resolved + cleared its
  /// promise list. Either the late promise resolves immediately with
  /// the terminal state, or it joins the still-pending list.
  private let lock = NSLock()
  private var promises: [Promise<CastLocalNetworkPermission>] = []
  private var resolved = false
  private var resolvedState: CastLocalNetworkPermission = .undetermined

  init(parent: LocalNetworkPermissionProbe, queue: DispatchQueue) {
    self.parent = parent
    self.queue = queue
  }

  func subscribe(_ promise: Promise<CastLocalNetworkPermission>) {
    lock.lock()
    if resolved {
      let state = resolvedState
      lock.unlock()
      promise.resolve(withResult: state)
      return
    }
    promises.append(promise)
    lock.unlock()
  }

  func start() {
    do {
      let listener = try NWListener(
        using: NWParameters.tcp,
        on: .any
      )
      listener.service = NWListener.Service(
        name: nil,
        type: LocalNetworkPermissionProbe.serviceType
      )
      // Listener is advertisement-only — `.granted` resolution comes
      // exclusively from `browseResultsChangedHandler` seeing the
      // advert. Accepting raw TCP traffic on the listener's port
      // would false-positive on a port-scanning LAN peer.
      listener.newConnectionHandler = { connection in connection.cancel() }
      listener.start(queue: queue)
      self.listener = listener

      let browser = NWBrowser(
        for: .bonjour(type: LocalNetworkPermissionProbe.serviceType, domain: nil),
        using: NWParameters.tcp
      )
      browser.stateUpdateHandler = { [weak self] state in
        self?.handleBrowserState(state)
      }
      browser.browseResultsChangedHandler = { [weak self] results, _ in
        if !results.isEmpty {
          self?.resolve(.granted)
        }
      }
      browser.start(queue: queue)
      self.browser = browser

      let timeout = DispatchWorkItem { [weak self] in
        // No browser activity within the cap — treat as undetermined.
        // Apple's prompt may still be visible; the next probe call
        // re-asks and may resolve.
        self?.resolve(.undetermined)
      }
      timeoutItem = timeout
      queue.asyncAfter(
        deadline: .now() + LocalNetworkPermissionProbe.probeTimeoutSeconds,
        execute: timeout
      )
    } catch {
      NSLog("[LocalNetworkPermissionProbe] NWListener failed to start: \(error)")
      resolve(.undetermined)
    }
  }

  private func handleBrowserState(_ state: NWBrowser.State) {
    switch state {
    case .waiting(let error):
      // Denial surfaces here, not in `.failed`: refusing local-network access
      // leaves the browser waiting with kDNSServiceErr_PolicyDenied (-65570).
      // `.waiting` is also the ordinary "no connectivity yet" state, so only
      // the error code decides — anything else stays unresolved and lets the
      // timeout or a later state settle it.
      if Self.isLocalNetworkDeniedError(error) {
        resolve(.denied)
      }
    case .failed(let error):
      if Self.isLocalNetworkDeniedError(error) {
        resolve(.denied)
      } else {
        resolve(.undetermined)
      }
    case .cancelled:
      // Browser cancelled before resolving — stay undetermined and
      // let the next requestPermission() call try again.
      resolve(.undetermined)
    default:
      break
    }
  }

  private func resolve(_ state: CastLocalNetworkPermission) {
    lock.lock()
    if resolved {
      lock.unlock()
      return
    }
    resolved = true
    resolvedState = state
    let resolvedPromises = promises
    promises.removeAll()
    lock.unlock()

    timeoutItem?.cancel()
    timeoutItem = nil
    browser?.cancel()
    listener?.cancel()
    browser = nil
    listener = nil
    parent?.completeProbe(with: state)
    for p in resolvedPromises { p.resolve(withResult: state) }
  }

  /// Detect the local-network-denied error.
  ///
  /// A denied browse reports `kDNSServiceErr_PolicyDenied` (-65570) as
  /// `NWError.dns`, which is the form that actually arrives on the `.waiting`
  /// path. `NWError.posix(.EACCES)` is kept for the listener side, which
  /// reports the same refusal as a POSIX error. Everything else (no Wi-Fi,
  /// system error) is not a denial and falls through.
  static func isLocalNetworkDeniedError(_ error: NWError) -> Bool {
    if case .posix(let code) = error, code == .EACCES {
      return true
    }
    if case .dns(let code) = error, code == Self.dnsPolicyDenied {
      return true
    }
    return false
  }

  /// `kDNSServiceErr_PolicyDenied`. Spelled out rather than imported so the
  /// probe does not pull in dnssd for one constant.
  private static let dnsPolicyDenied: DNSServiceErrorType = -65570
}
