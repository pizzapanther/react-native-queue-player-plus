import Foundation
import Network

/// Process-wide local HTTP server for URL-handoff cast backends
/// (Chromecast, future DLNA / Sonos). Serves consumer-supplied
/// `file://` URLs to receivers that can only pull `http://` — the
/// classic Chromecast offline-content path.
///
/// **Not related to the lookahead cache.** The lookahead cache is
/// fully native + streaming-focused. This server exposes only files
/// the consumer app has explicitly put in `setQueue` with a `file://`
/// URL (offline-downloaded content from the host app's own storage).
///
/// Binds a `Network.framework` `NWListener` to all interfaces on an
/// OS-assigned port and serves `GET` / `HEAD /media/{token}` via
/// `MediaHTTPConnection`, resolving tokens through `MediaTokenRegistry`.
/// Backends call `acquire(sessionId:)` for a `MediaServerHandle`; the
/// listener starts on first acquire and stops when the last handle
/// closes. Range / HEAD / Content-Length are handled by the responder.
/// Thread-safe singleton: all mutable state is guarded by `lock`, so it is
/// safe to capture in the listener's `@Sendable` dispatch closures.
final class LocalMediaServer: @unchecked Sendable {
  /// Process-wide singleton — only the Chromecast / DLNA / Sonos
  /// backends acquire handles; AirPlay-only apps never touch this and
  /// the listener never starts.
  static let shared = LocalMediaServer()

  private let lock = NSLock()
  private var listener: NWListener?
  /// OS-assigned port, read back once the listener reaches `.ready`.
  /// `0` while not bound.
  private var boundPort: UInt16 = 0
  private var refCount = 0
  private let registry = MediaTokenRegistry()
  /// Live accepted connections, tracked so the last release can cancel any
  /// in-flight transfer. Keyed by identity; each self-removes on finish.
  private var connections: [ObjectIdentifier: MediaHTTPConnection] = [:]
  private let serverQueue = DispatchQueue(label: "queueplayer.cast.mediaserver.listener")
  /// Never `serverQueue` or a caller's queue: `isAnswering` blocks its caller
  /// while the probe runs here.
  private let probeQueue = DispatchQueue(label: "queueplayer.cast.mediaserver.probe")
  /// Returned by `/health`, so `isAnswering` cannot mistake another process
  /// that took a reclaimed port for this listener. Not a secret: only unique.
  private let instanceId = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()

  // Continuations awaiting the listener's first bind, guarded by `lock`. Resumed
  // from the current listener's state handler (.ready / .failed / .cancelled) so the
  // acquiring async task suspends instead of busy-waiting a cooperative thread.
  // Keyed per wait, so a wait's timeout resumes only its own continuation and
  // never one a later acquire is still waiting on.
  private var readinessWaiters: [UUID: CheckedContinuation<Void, Never>] = [:]

  /// OS-assigned port. `nil` when the listener is not bound.
  var serverPortOrNil: Int? {
    lock.lock(); defer { lock.unlock() }
    return boundPort == 0 ? nil : Int(boundPort)
  }

  /// LAN IPv4 address for receivers to reach: the first IPv4 on an `en*`
  /// interface, looked up with `getifaddrs` on every read; `nil` when
  /// there is none.
  var currentLanAddress: String? {
    Self.firstWifiIPv4()
  }

  /// The first IPv4 address on an `en*` interface (Wi-Fi), from a
  /// synchronous `getifaddrs` scan; `nil` when no such interface has one.
  /// Cellular addresses can't reach a LAN receiver, so other interfaces
  /// are ignored.
  private static func firstWifiIPv4() -> String? {
    var ifaddrPtr: UnsafeMutablePointer<ifaddrs>?
    guard getifaddrs(&ifaddrPtr) == 0, let first = ifaddrPtr else { return nil }
    defer { freeifaddrs(ifaddrPtr) }
    var ptr: UnsafeMutablePointer<ifaddrs>? = first
    while let current = ptr {
      defer { ptr = current.pointee.ifa_next }
      let name = String(cString: current.pointee.ifa_name)
      guard name.hasPrefix("en") else { continue }
      guard let sa = current.pointee.ifa_addr else { continue }
      guard sa.pointee.sa_family == UInt8(AF_INET) else { continue }
      var hostBuf = [CChar](repeating: 0, count: Int(NI_MAXHOST))
      let result = getnameinfo(
        sa, socklen_t(sa.pointee.sa_len),
        &hostBuf, socklen_t(hostBuf.count),
        nil, 0, NI_NUMERICHOST
      )
      if result == 0 {
        let host = String(cString: hostBuf)
        if !host.isEmpty && host != "0.0.0.0" { return host }
      }
    }
    return nil
  }

  /// Every caller waits for the bind, then the listener is probed: iOS can
  /// reclaim a suspended app's listening socket while the listener keeps
  /// reporting `.ready`. A bound listener that does not answer is replaced
  /// before this caller hands out any URL.
  func acquire(sessionId: String) async -> MediaServerHandle {
    incrementRefCount()
    await startAndWait()
    let probe = await probeOffCooperativePool()
    if let probed = probe.listener, !probe.answered {
      NSLog("[LocalMediaServer] acquire: listener not answering; rebinding")
      // Only the listener this caller probed: a concurrent acquire may already
      // have replaced it with one it handed out URLs for, possibly on the same
      // port.
      stop(ifCurrent: probed)
      await startAndWait()
      let answered = await probeOffCooperativePool().answered
      NSLog("[LocalMediaServer] rebind port=\(serverPortOrNil ?? 0) answering=\(answered ? "yes" : "no")")
    }
    // Tokens are revoked per handle, so closing one of two handles a session
    // acquired leaves the other's URLs registered.
    return MediaServerHandle(server: self, sessionId: "\(sessionId)/\(UUID().uuidString)")
  }

  /// Whether the listener answers `GET /health` with `200` and this server's
  /// instance id within `timeoutMs`; see `HealthProbe.check`. Longer than the
  /// read-through server's probe because a no here ends the user's cast
  /// session. Synchronous, and never call it on `probeQueue`.
  func isAnswering(timeoutMs: Int = 1000) -> Bool {
    probe(timeoutMs: timeoutMs).answered
  }

  /// The answer, and the listener it was asked of (nil when none was bound).
  /// The port and the listener are read together under `lock`.
  private func probe(timeoutMs: Int = 1000) -> (answered: Bool, listener: ObjectIdentifier?) {
    lock.lock()
    let port = boundPort
    let probed = port != 0 ? listener.map(ObjectIdentifier.init) : nil
    lock.unlock()
    guard let probed else {
      NSLog("[LocalMediaServer] probe: listener not bound")
      return (false, nil)
    }
    let result = HealthProbe.check(
      port: port, instance: instanceId, queue: probeQueue, timeoutMs: timeoutMs)
    if !result.answered {
      NSLog("[LocalMediaServer] probe port=\(port) failed: \(result.reason)")
    }
    return (result.answered, probed)
  }

  /// `isAnswering()` run on a global queue, with the answer delivered on main.
  func checkAnswering(completion: @escaping (Bool) -> Void) {
    DispatchQueue.global(qos: .userInitiated).async { [self] in
      let answered = isAnswering()
      DispatchQueue.main.async { completion(answered) }
    }
  }

  /// `probe()` without blocking a cooperative thread on its semaphore.
  private func probeOffCooperativePool() async -> (answered: Bool, listener: ObjectIdentifier?) {
    await withCheckedContinuation { cont in
      DispatchQueue.global(qos: .userInitiated).async { [self] in
        cont.resume(returning: probe())
      }
    }
  }

  /// Kept synchronous so the lock is never held across a suspension in the
  /// async `acquire`.
  private func incrementRefCount() {
    lock.lock()
    refCount += 1
    lock.unlock()
  }

  /// The last release takes the listener out of the slot under the same lock
  /// as the decrement, so an acquire arriving straight after binds a new
  /// listener rather than finding the one about to be cancelled.
  func release(sessionId: String) {
    registry.revokeAll(sessionId: sessionId)
    lock.lock()
    refCount -= 1
    let retired = refCount == 0 ? takeListenerLocked() : nil
    lock.unlock()
    retired.map(Self.cancel)
  }

  func register(sessionId: String, fileURL: URL, contentType: String?) -> String {
    registry.register(sessionId: sessionId, fileURL: fileURL, contentType: contentType)
  }

  // MARK: - Listener lifecycle

  /// Start the listener (if not already bound) and suspend, bounded, until it
  /// reports a bound port. `acquire` probes that port straight after, and
  /// `MediaServerHandle.register` builds URLs from it. Binding
  /// a TCP listener is a local operation that settles in milliseconds; the wait
  /// is a guard, not a normal cost. Resumes from the listener state handler
  /// (event-driven, so no cooperative thread is blocked) with a timeout backstop
  /// for the pathological never-binds case (the caller then surfaces a nil port).
  private func startAndWait(timeoutMs: Int = 2000) async {
    startListenerIfNeeded()
    if serverPortOrNil != nil { return }
    await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in
      lock.lock()
      if boundPort != 0 || listener == nil {
        lock.unlock()
        cont.resume()
        return
      }
      let id = UUID()
      readinessWaiters[id] = cont
      lock.unlock()
      serverQueue.asyncAfter(deadline: .now() + .milliseconds(timeoutMs)) { [weak self] in
        guard let self = self else { return }
        self.lock.lock()
        let expired = self.readinessWaiters.removeValue(forKey: id)
        self.lock.unlock()
        guard let expired else { return }
        NSLog("[LocalMediaServer] listener did not bind within \(timeoutMs)ms")
        expired.resume()
      }
    }
  }

  /// Resume + clear all readiness waiters. The list is the single resume claim:
  /// whichever trigger (bind, failure, or the timeout backstop) drains it first
  /// resumes each continuation, so each resumes exactly once.
  private func resumeReadinessWaiters() {
    lock.lock()
    let waiters = readinessWaiters.values
    readinessWaiters.removeAll()
    lock.unlock()
    waiters.forEach { $0.resume() }
  }

  /// Held across the check and the assignment, so two callers cannot each
  /// find the slot empty and bind two listeners. Any listener in the slot is
  /// kept, whatever its state: its `.failed`/`.cancelled` empties the slot.
  private func startListenerIfNeeded() {
    lock.lock()
    if listener != nil {
      lock.unlock()
      return
    }

    let params = NWParameters.tcp
    // Bind every interface (Wi-Fi LAN, etc.) on an OS-assigned port — the
    // Cast receiver fetches URLs over the LAN, so unlike a loopback proxy
    // this listener must accept en0 connections. URLs handed to receivers
    // go through `MediaServerHandle.register(fileURL:lanAddress:contentType:)`
    // with the host from `currentLanAddress` (the first IPv4 on an `en*` interface);
    // tokens are 32 hex chars (high entropy), so the LAN-reachable surface
    // can't be enumerated. `allowLocalEndpointReuse` avoids bind churn when
    // a prior listener's socket is still in TIME_WAIT.
    params.allowLocalEndpointReuse = true
    // Keep accepted connections alive through the receiver's buffer-ahead idle
    // gaps — a long track can sit idle on the wire while the buffered audio
    // plays, and without keepalive a router/connection-tracking idle timeout can
    // drop the transfer mid-track.
    if let tcp = params.defaultProtocolStack.transportProtocol as? NWProtocolTCP.Options {
      tcp.enableKeepalive = true
      tcp.keepaliveIdle = 30
      tcp.keepaliveInterval = 15
      tcp.keepaliveCount = 4
    }
    guard let newListener = try? NWListener(using: params) else {
      lock.unlock()
      // Init threw — unblock any acquire waiting on a bind that won't come.
      resumeReadinessWaiters()
      return
    }
    // Only the current listener updates the slot or the port, or wakes waiters,
    // so a stopped listener's late `.cancelled` cannot clear its replacement or
    // wake an acquire waiting for the replacement's bind.
    newListener.stateUpdateHandler = { [weak self] state in
      guard let self = self else { return }
      switch state {
      case .ready:
        let port = newListener.port?.rawValue ?? 0
        NSLog("[LocalMediaServer] listener ready port=\(port)")
        self.lock.lock()
        let current = self.listener === newListener
        if current { self.boundPort = port }
        self.lock.unlock()
        if current { self.resumeReadinessWaiters() }
      case .waiting(let error):
        // Logged only. Waking waiters here would make a bind that passes
        // through `.waiting` on its way to `.ready` return without a port.
        NSLog("[LocalMediaServer] listener waiting: \(error)")
      case .failed, .cancelled:
        self.lock.lock()
        let current = self.listener === newListener
        if current {
          self.listener = nil
          self.boundPort = 0
        }
        self.lock.unlock()
        if case .failed(let error) = state {
          NSLog("[LocalMediaServer] listener failed: \(error)")
          // A failed listener is cancelled to release its resources; its
          // `.cancelled` then finds it no longer current.
          newListener.cancel()
        }
        if current { self.resumeReadinessWaiters() }
      default:
        break
      }
    }
    newListener.newConnectionHandler = { [weak self, weak newListener] conn in
      guard let self, let newListener else {
        conn.cancel()
        return
      }
      self.accept(conn, from: newListener)
    }
    listener = newListener
    lock.unlock()
    newListener.start(queue: serverQueue)
  }

  /// Refuses a connection accepted by a listener that has been retired, so it
  /// cannot sit in `connections` until the last release.
  private func accept(_ conn: NWConnection, from acceptingListener: NWListener) {
    let responder = MediaHTTPConnection(
      connection: conn,
      resolve: { [registry] token in
        registry.resolve(token).map { .file($0.fileURL, contentType: $0.contentType) }
      },
      serverInstance: instanceId
    ) { [weak self] in
      self?.untrack(conn)
    }
    // Track BEFORE start(): the responder's finish callback (-> untrack)
    // can fire as soon as start() runs, so the insert must precede it or
    // untrack would no-op and the responder would leak until the last release.
    lock.lock()
    guard listener === acceptingListener else {
      lock.unlock()
      conn.cancel()
      return
    }
    connections[ObjectIdentifier(conn)] = responder
    lock.unlock()
    responder.start()
  }

  private func untrack(_ conn: NWConnection) {
    lock.lock()
    connections.removeValue(forKey: ObjectIdentifier(conn))
    lock.unlock()
  }

  /// Retire the listener and its connections while handles still hold the
  /// server; the next `startAndWait` binds a new one. Tokens and the refcount
  /// are kept.
  private func stop(ifCurrent probed: ObjectIdentifier) {
    lock.lock()
    let retired = listener.map(ObjectIdentifier.init) == probed ? takeListenerLocked() : nil
    lock.unlock()
    retired.map(Self.cancel)
  }

  /// Empty the slot and the connection map. Caller holds `lock`; cancel the
  /// result after releasing it.
  private func takeListenerLocked() -> (listener: NWListener?, connections: [MediaHTTPConnection]) {
    let retired = (listener: listener, connections: Array(connections.values))
    listener = nil
    boundPort = 0
    connections.removeAll()
    return retired
  }

  private static func cancel(_ retired: (listener: NWListener?, connections: [MediaHTTPConnection])) {
    retired.connections.forEach { $0.cancel() }
    retired.listener?.cancel()
  }
}
