import Foundation
import Network

/// Serves the player's remote tracks from the lookahead cache when it holds
/// them, and from the origin when it does not.
///
/// The player binds one stable `http://127.0.0.1:<port>/media/<token>` per
/// source URL and never re-points it. Whether a request is answered from disk
/// or from the network is decided per request, which is the only moment the
/// answer is actually knowable — the cache fills while the queue is already
/// built, so a URL chosen at construction is always chosen too early.
///
/// Loopback-bound: nothing outside the device needs to reach it. That is a
/// smaller default rather than a security boundary — another process on the
/// same device could reach it too, and the 128-bit token is what stands in its
/// way.
final class ReadThroughServer: @unchecked Sendable {

  /// Token → the origin it stands for. Written on the player's queue, read on
  /// connection queues.
  private let lock = NSLock()
  private var origins: [String: URL] = [:]
  private var tokensBySource: [String: String] = [:]
  /// The consumer's `track.url` string per token: the cache is keyed on it
  /// verbatim, and `URL.absoluteString` is a re-serialisation that can differ
  /// from it (percent-encoding on iOS 17+).
  private var sourceURLs: [String: String] = [:]
  private var touched: Set<String> = []
  private var headers: [String: String] = [:]

  /// Returned by this server's `/health`, so `isAnswering` cannot mistake
  /// another process that took a reclaimed port for this listener.
  private let instanceId = ReadThroughServer.newToken()

  private let listenerLock = NSLock()
  private var listener: NWListener?
  /// Signalled by `listener`'s `.cancelled`, so `restart()` can wait for it.
  private var listenerCancelled: DispatchSemaphore?
  private var boundPort: UInt16 = 0
  private var connections: [ObjectIdentifier: MediaHTTPConnection] = [:]
  private var bindWaiters: [DispatchSemaphore] = []

  private let serverQueue = DispatchQueue(label: "queueplayer.readthrough.listener")
  /// Never `serverQueue` or a caller's queue: `isAnswering` blocks its caller
  /// while the probe runs here.
  private let probeQueue = DispatchQueue(label: "queueplayer.readthrough.probe")

  /// One session for every origin leg, so connections are pooled across the
  /// per-request client connections AVFoundation opens.
  private let session: URLSession

  /// The default session: pooled like `URLSession.shared`, but with no
  /// `URLCache` — the lookahead cache already keeps the bytes on disk, and
  /// `URLCache.shared` would hold a second copy of every small range.
  private static func makeOriginSession() -> URLSession {
    let configuration = URLSessionConfiguration.default
    configuration.urlCache = nil
    configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
    return URLSession(configuration: configuration)
  }

  /// Consulted per request for the cache's complete copy of an origin. Injected
  /// rather than referenced directly so the server owns no cache lifetime.
  private let cachedFileForOrigin: (String) -> URL?

  /// Called once per token the first time it is served from disk, so the cache
  /// can bump LRU on a real read. Not per request: `touch` writes the index on
  /// the same serial queue the lookup takes, and AVFoundation issues several
  /// range requests per track.
  private let onFirstDiskHit: (String) -> Void

  init(
    session: URLSession = ReadThroughServer.makeOriginSession(),
    cachedFileForOrigin: @escaping (String) -> URL?,
    onFirstDiskHit: @escaping (String) -> Void
  ) {
    self.session = session
    self.cachedFileForOrigin = cachedFileForOrigin
    self.onFirstDiskHit = onFirstDiskHit
  }

  // MARK: - Routing

  /// The local URL standing for `origin`, or nil when the listener is not up.
  ///
  /// Stable for the life of the session: one token per source string, so a
  /// window rebuild hands the player the URL it already has and AVFoundation
  /// keeps reusing its own connections. Keyed on the string rather than the
  /// parsed URL because two strings that parse alike are still two tracks with
  /// two cache entries.
  /// `sourceURL` is the `track.url` string the cache is keyed on; it defaults
  /// to the origin's own serialisation for callers that have no other.
  func localURL(for origin: URL, sourceURL: String? = nil) -> URL? {
    let port = boundPortValue()
    guard port != 0 else { return nil }
    let source = sourceURL ?? origin.absoluteString
    lock.lock()
    let token: String
    if let existing = tokensBySource[source] {
      token = existing
    } else {
      token = Self.newToken()
      tokensBySource[source] = token
      origins[token] = origin
      sourceURLs[token] = source
    }
    lock.unlock()
    #if DEBUG
      NSLog(
        "[RNQP-CACHE] route token=%@ -> %@",
        String(token.prefix(8)), PlaybackErrorMapping.stripQuery(origin.absoluteString))
    #endif
    return URL(string: "http://127.0.0.1:\(port)/media/\(token)")
  }

  /// The consumer's headers, sent on every origin request. Written by
  /// `startReadThroughServer` — `configure()` and `setLookaheadCache` — so a
  /// refreshed token reaches requests for items built before it changed.
  func setHeaders(_ headers: [String: String]) {
    lock.lock()
    self.headers = headers
    lock.unlock()
  }

  private func resolve(_ token: String) -> MediaSource? {
    lock.lock()
    guard let origin = origins[token] else {
      lock.unlock()
      return nil
    }
    let sourceURL = sourceURLs[token] ?? origin.absoluteString
    let headers = self.headers
    let alreadyTouched = touched.contains(token)
    lock.unlock()

    let cached = cachedFileForOrigin(sourceURL)
    #if DEBUG
      NSLog(
        "[RNQP-CACHE] resolve token=%@ cached=%@ origin=%@",
        String(token.prefix(8)), cached == nil ? "no" : "yes",
        PlaybackErrorMapping.stripQuery(origin.absoluteString))
    #endif
    if cached != nil && !alreadyTouched {
      lock.lock()
      touched.insert(token)
      lock.unlock()
      onFirstDiskHit(sourceURL)
    }
    return .readThrough(
      ReadThroughSource(origin: origin, headers: headers, cachedFile: cached, session: session)
    )
  }

  /// 128 bits, so the token cannot be enumerated by something else on the
  /// device. Same shape `MediaHTTPRequest.mediaToken` already validates.
  private static func newToken() -> String {
    var bytes = [UInt8](repeating: 0, count: 16)
    if SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) != errSecSuccess {
      for i in bytes.indices { bytes[i] = UInt8.random(in: 0 ... 255) }
    }
    return bytes.map { String(format: "%02x", $0) }.joined()
  }

  // MARK: - Lifecycle

  /// Bring the listener up. Returns immediately; `waitUntilBound` is what waits.
  /// Call on the player's queue: two concurrent calls could each find the slot
  /// empty and bind two listeners.
  func start() {
    listenerLock.lock()
    let running = listener != nil
    listenerLock.unlock()
    guard !running else { return }
    bind(port: .any, reuse: false)
  }

  /// Create a listener on `port`, make it the current one and start it.
  ///
  /// Every state the handler acts on is checked against the current listener,
  /// so a listener that `restart()` has replaced or abandoned cannot clear the
  /// current one's state, write its port, or wake a waiter for the next bind.
  /// Returns nil, with any waiters woken, when the listener cannot be created.
  @discardableResult
  private func bind(port: NWEndpoint.Port, reuse: Bool) -> (NWListener, DispatchSemaphore)? {
    let params = NWParameters.tcp
    params.allowLocalEndpointReuse = reuse
    // Loopback only. The cast server binds every interface because a receiver
    // fetches over the LAN; nothing outside this device ever reads from here.
    params.requiredLocalEndpoint = NWEndpoint.hostPort(host: "127.0.0.1", port: port)
    guard let newListener = try? NWListener(using: params) else {
      NSLog("[QueuePlayer] read-through listener could not be created on port %d", port.rawValue)
      resumeReadinessWaiters()
      return nil
    }
    let cancelled = DispatchSemaphore(value: 0)
    listenerLock.lock()
    listener = newListener
    listenerCancelled = cancelled
    listenerLock.unlock()

    newListener.stateUpdateHandler = { [weak self] state in
      if case .cancelled = state { cancelled.signal() }
      guard let self = self else { return }
      switch state {
      case .ready:
        let bound = newListener.port?.rawValue ?? 0
        NSLog("[QueuePlayer] read-through listener ready port=%d", bound)
        self.listenerLock.lock()
        let current = self.listener === newListener
        if current { self.boundPort = bound }
        self.listenerLock.unlock()
        if current { self.resumeReadinessWaiters() }
      case .waiting(let error):
        // Logged only. Waking waiters here would make a bind that passes
        // through `.waiting` on its way to `.ready` report that it never bound.
        NSLog("[QueuePlayer] read-through listener waiting: %@", String(describing: error))
      case .failed, .cancelled:
        if case .failed(let error) = state {
          NSLog("[QueuePlayer] read-through listener failed: %@", String(describing: error))
        }
        // Drop the listener too, so a later `start()` can try again; a
        // failed listener left in the slot would make every restart a no-op.
        self.listenerLock.lock()
        let current = self.listener === newListener
        if current {
          self.listener = nil
          self.listenerCancelled = nil
          self.boundPort = 0
        }
        self.listenerLock.unlock()
        if current { self.resumeReadinessWaiters() }
      default:
        break
      }
    }
    newListener.newConnectionHandler = { [weak self] conn in
      self?.accept(conn)
    }
    newListener.start(queue: serverQueue)
    return (newListener, cancelled)
  }

  /// Replace the listener with a fresh one, keeping every token, the headers
  /// and the origin session, so items already built keep resolving. Returns
  /// the new port, or 0 when nothing could be bound.
  ///
  /// For a listener the OS reclaimed while the app was suspended, which keeps
  /// reporting `.ready` on a port that no longer accepts connections. Rebinds
  /// the same port first, so built items keep their URLs, and falls back to
  /// any port when that fails or does not answer. Cancels the open
  /// connections, so call it only while nothing is playing; an item that is
  /// buffering loses its connection and reconnects. Blocks its caller for the
  /// binds, up to about 5 s if Network.framework stalls on every step, and a
  /// server whose listener never binds is retried, at that cost, by every
  /// caller that finds it not answering. Never call it after `stop()`, which
  /// invalidates the session.
  @discardableResult
  func restart() -> UInt16 {
    listenerLock.lock()
    let old = listener
    let oldCancelled = listenerCancelled
    let oldPort = boundPort
    listener = nil
    listenerCancelled = nil
    boundPort = 0
    let open = connections
    connections.removeAll()
    listenerLock.unlock()

    open.values.forEach { $0.cancel() }
    var oldCancelledInTime = true
    if let old {
      old.cancel()
      oldCancelledInTime = oldCancelled.map { Self.wait($0, ms: 1000) } ?? true
    }

    if oldPort != 0, let port = NWEndpoint.Port(rawValue: oldPort),
       let attempt = bind(port: port, reuse: true) {
      if waitUntilBound(timeoutMs: 500), isAnswering() {
        NSLog(
          "[QueuePlayer] read-through restart old=%d new=%d samePort=yes oldCancelled=%@",
          oldPort, oldPort, oldCancelledInTime ? "yes" : "timeout")
        return oldPort
      }
      abandon(attempt)
    }

    if bind(port: .any, reuse: false) != nil { waitUntilBound() }
    let newPort = boundPortValue()
    NSLog(
      "[QueuePlayer] read-through restart old=%d new=%d samePort=%@ oldCancelled=%@",
      oldPort, newPort, oldPort == 0 ? "skipped" : "no", oldCancelledInTime ? "yes" : "timeout")
    return newPort
  }

  /// Retire a bind attempt that did not come up, clearing it from the slot
  /// first so its late `.ready` or `.cancelled` cannot touch the next listener.
  private func abandon(_ attempt: (NWListener, DispatchSemaphore)) {
    listenerLock.lock()
    if listener === attempt.0 {
      listener = nil
      listenerCancelled = nil
      boundPort = 0
    }
    listenerLock.unlock()
    attempt.0.cancel()
    if !Self.wait(attempt.1, ms: 1000) {
      NSLog("[QueuePlayer] read-through abandoned listener did not report cancelled in time")
    }
  }

  private static func wait(_ semaphore: DispatchSemaphore, ms: Int) -> Bool {
    semaphore.wait(timeout: .now() + .milliseconds(ms)) == .success
  }

  /// Whether this server's listener answers `GET /health` with `200` and its
  /// own instance id within `timeoutMs`; see `HealthProbe.check`. Synchronous,
  /// and never call it on `probeQueue`.
  func isAnswering(timeoutMs: Int = 300) -> Bool {
    let port = boundPortValue()
    guard port != 0 else { return false }
    let result = HealthProbe.check(
      port: port, instance: instanceId, queue: probeQueue, timeoutMs: timeoutMs)
    if !result.answered {
      NSLog("[QueuePlayer] read-through probe port=%d failed: %@", port, result.reason)
    }
    return result.answered
  }

  /// Block until the listener has bound, up to `timeoutMs`; `true` when it
  /// has. Called right after `start()` on the queue that builds items, so
  /// every item built afterwards sees a bound port: `localURL(for:)` is nil
  /// before the bind, and an item built then is bound to its origin for good.
  /// Binding loopback takes milliseconds; the timeout is for the listener
  /// that never reaches `.ready`, which `start()` also reports by resuming.
  @discardableResult
  func waitUntilBound(timeoutMs: Int = 2000) -> Bool {
    if boundPortValue() != 0 { return true }
    let bound = DispatchSemaphore(value: 0)
    listenerLock.lock()
    if boundPort != 0 {
      listenerLock.unlock()
      return true
    }
    bindWaiters.append(bound)
    listenerLock.unlock()
    _ = bound.wait(timeout: .now() + .milliseconds(timeoutMs))
    return boundPortValue() != 0
  }

  func boundPortValue() -> UInt16 {
    listenerLock.lock(); defer { listenerLock.unlock() }
    return boundPort
  }

  private func resumeReadinessWaiters() {
    listenerLock.lock()
    let waiters = bindWaiters
    bindWaiters.removeAll()
    listenerLock.unlock()
    waiters.forEach { $0.signal() }
  }

  private func accept(_ conn: NWConnection) {
    let responder = MediaHTTPConnection(
      connection: conn,
      resolve: { [weak self] token in self?.resolve(token) },
      serverInstance: instanceId
    ) { [weak self] in
      self?.untrack(conn)
    }
    listenerLock.lock()
    connections[ObjectIdentifier(conn)] = responder
    listenerLock.unlock()
    responder.start()
  }

  private func untrack(_ conn: NWConnection) {
    listenerLock.lock()
    connections.removeValue(forKey: ObjectIdentifier(conn))
    listenerLock.unlock()
  }

  /// Tear the listener down, invalidate the origin session and drop every
  /// token. Tokens do not outlive a session, and the map is bounded by the
  /// distinct origins seen in one — so nothing needs revoking as the queue
  /// changes. A stopped server is not restarted; `startReadThroughServer`
  /// builds a new one.
  func stop() {
    listenerLock.lock()
    let live = listener
    listener = nil
    listenerCancelled = nil
    boundPort = 0
    let open = connections
    connections.removeAll()
    listenerLock.unlock()
    open.values.forEach { $0.cancel() }
    live?.cancel()
    session.invalidateAndCancel()
    resumeReadinessWaiters()

    lock.lock()
    origins.removeAll()
    tokensBySource.removeAll()
    sourceURLs.removeAll()
    touched.removeAll()
    headers.removeAll()
    lock.unlock()
  }
}
