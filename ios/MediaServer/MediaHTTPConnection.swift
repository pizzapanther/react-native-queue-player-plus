import Foundation
import Network

/// Resolution of an HTTP `Range` header against a known file size.
enum MediaByteRange: Equatable {
  /// No Range header, or one the server ignores (malformed / invalid
  /// bounds) — serve the whole file with `200`.
  case full
  /// A satisfiable single range with inclusive bounds — serve `206`.
  case partial(start: Int64, end: Int64)
  /// A syntactically-valid range that starts past EOF — serve `416`.
  case unsatisfiable
}

/// Parsed view of an incoming request: the method, the request target,
/// and the one header the file server acts on (`Range`). Built from the
/// raw header block (everything before the `\r\n\r\n` terminator).
struct MediaHTTPRequest: Equatable {
  let method: String
  let path: String
  let rangeHeader: String?

  /// The `<token>` from a `/media/<32-hex>` path, or `nil` when the path
  /// is anything else. The token is both the routing key (which file) and
  /// the access secret (high-entropy, unenumerable) for the LAN-reachable
  /// server.
  var mediaToken: String? {
    let prefix = "/media/"
    guard path.hasPrefix(prefix) else { return nil }
    let token = String(path.dropFirst(prefix.count))
    guard token.count == 32,
          token.allSatisfy({ ($0 >= "0" && $0 <= "9") || ($0 >= "a" && $0 <= "f") })
    else { return nil }
    return token
  }

  init?(headerData: Data) {
    guard let text = String(data: headerData, encoding: .utf8) else { return nil }
    var lines = text.components(separatedBy: "\r\n")
    guard !lines.isEmpty else { return nil }
    let requestLine = lines.removeFirst().split(separator: " ")
    guard requestLine.count >= 2 else { return nil }
    self.method = String(requestLine[0]).uppercased()
    // Strip any query string — the file path never carries one, and the
    // token match below expects the bare path.
    let target = String(requestLine[1])
    self.path = target.split(separator: "?", maxSplits: 1).first.map(String.init) ?? target

    var range: String?
    for line in lines where !line.isEmpty {
      guard let colon = line.firstIndex(of: ":") else { continue }
      let name = line[line.startIndex..<colon].trimmingCharacters(in: .whitespaces).lowercased()
      if name == "range" {
        range = String(line[line.index(after: colon)...]).trimmingCharacters(in: .whitespaces)
      }
    }
    self.rangeHeader = range
  }
}

/// What a `/media/<token>` request resolves to.
///
/// Two servers share this connection. The cast server registers local files it
/// was handed by the consumer; the playback cache registers remote origins and
/// lets the cache answer for them when it holds a complete copy. Keeping the
/// arbitration in the resolver is what keeps `LookaheadCache` out of this file.
enum MediaSource {
  case file(URL, contentType: String)
  case readThrough(ReadThroughSource)
}

/// An origin to read through, with the cache's complete copy when it has one.
struct ReadThroughSource {
  let origin: URL
  /// The consumer's configured headers, re-applied on the origin leg.
  let headers: [String: String]
  /// The cache's complete copy of `origin`, resolved at request time. Nil when
  /// the cache does not hold the whole file — there is no partial serving.
  let cachedFile: URL?
  let session: URLSession
}

/// Reconciles a data task's body with its response heads, so that a task the
/// system re-issued on resume delivers every byte once.
///
/// A resumed `URLSessionDataTask` is not guaranteed to continue rather than
/// re-issue the request. A second head that matches the first means the body
/// is starting over: the bytes the first transfer already delivered are
/// dropped from the restarted one. A second head that differs is a different
/// resource and cannot be stitched.
struct OriginRestartStitcher {
  enum HeadVerdict: Equatable {
    /// The first head, or a re-issued request answered the same way.
    case accept
    /// A re-issued request answered differently.
    case reject
  }

  private var first: HTTPURLResponse?
  private(set) var deliveredBytes = 0
  private var skip = 0

  mutating func head(_ response: HTTPURLResponse) -> HeadVerdict {
    guard let first else {
      self.first = response
      return .accept
    }
    guard Self.sameAnswer(first, response) else { return .reject }
    skip = deliveredBytes
    return .accept
  }

  /// The part of `data` the client has not yet been sent; empty while a
  /// restarted transfer is still repeating what was delivered.
  mutating func body(_ data: Data) -> Data {
    var chunk = data
    if skip > 0 {
      let drop = min(skip, chunk.count)
      skip -= drop
      chunk = chunk.dropFirst(drop)
    }
    deliveredBytes += chunk.count
    return chunk
  }

  private static let identityHeaders = ["Content-Range", "Content-Length", "ETag", "Last-Modified"]

  private static func sameAnswer(_ a: HTTPURLResponse, _ b: HTTPURLResponse) -> Bool {
    a.statusCode == b.statusCode
      && identityHeaders.allSatisfy {
        a.value(forHTTPHeaderField: $0) == b.value(forHTTPHeaderField: $0)
      }
  }
}

/// One origin response delivered as the `Data` chunks URLSession produces.
///
/// The relay's own buffer is what is bounded: the task is suspended while more
/// than `highWater` bytes have been yielded but not yet sent to the client and
/// resumed under `lowWater`. Suspending a task stops its callbacks, not
/// necessarily its transfer — the system may keep receiving into its own
/// buffers — and a resumed data task may re-issue the request instead of
/// continuing; `OriginRestartStitcher` keeps the client's bytes exactly once
/// either way.
private final class OriginBody: NSObject, URLSessionDataDelegate {
  let chunks: AsyncThrowingStream<Data, Error>
  private var yield: AsyncThrowingStream<Data, Error>.Continuation?
  private let lock = NSLock()
  private var received: HTTPURLResponse?
  private var failure: Error?
  private var waiter: CheckedContinuation<URLResponse, Error>?
  private var pendingBytes = 0
  private var suspended = false
  private var stitcher = OriginRestartStitcher()
  private static let highWater = 2 * 1024 * 1024
  private static let lowWater = 512 * 1024

  /// The origin answered a re-issued request differently from the first one,
  /// so the bytes cannot be stitched onto what was already relayed.
  struct RestartedWithADifferentAnswer: Error {}

  override init() {
    var continuation: AsyncThrowingStream<Data, Error>.Continuation?
    chunks = AsyncThrowingStream { continuation = $0 }
    super.init()
    yield = continuation
  }

  /// The response head, once the origin has answered.
  func response() async throws -> URLResponse {
    try await withCheckedThrowingContinuation { cont in
      lock.lock()
      if let received {
        lock.unlock()
        cont.resume(returning: received)
      } else if let failure {
        lock.unlock()
        cont.resume(throwing: failure)
      } else {
        waiter = cont
        lock.unlock()
      }
    }
  }

  /// The relay has sent `count` bytes on; resume the origin when enough has
  /// drained. The task call stays under the lock so a suspend decided on the
  /// delegate queue and a resume decided here cannot land in the wrong order.
  func consumed(_ count: Int, task: URLSessionDataTask) {
    lock.lock()
    defer { lock.unlock() }
    pendingBytes -= count
    if suspended, pendingBytes < Self.lowWater {
      suspended = false
      task.resume()
    }
  }

  func urlSession(
    _ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void
  ) {
    lock.lock()
    guard let http = response as? HTTPURLResponse else {
      lock.unlock()
      completionHandler(.cancel)
      return
    }
    guard stitcher.head(http) == .accept else {
      lock.unlock()
      completionHandler(.cancel)
      yield?.finish(throwing: RestartedWithADifferentAnswer())
      return
    }
    let waiter: CheckedContinuation<URLResponse, Error>?
    if received == nil {
      received = http
      waiter = self.waiter
      self.waiter = nil
    } else {
      waiter = nil
    }
    lock.unlock()
    waiter?.resume(returning: http)
    completionHandler(.allow)
  }

  func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
    lock.lock()
    let chunk = stitcher.body(data)
    guard !chunk.isEmpty else {
      lock.unlock()
      return
    }
    pendingBytes += chunk.count
    if !suspended, pendingBytes > Self.highWater {
      suspended = true
      dataTask.suspend()
    }
    lock.unlock()
    yield?.yield(chunk)
  }

  func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
    lock.lock()
    let waiter = self.waiter
    self.waiter = nil
    if let error { failure = error }
    lock.unlock()
    if let error {
      waiter?.resume(throwing: error)
      yield?.finish(throwing: error)
    } else {
      yield?.finish()
    }
  }
}

/// Serves one resolved `MediaSource` over a single `NWConnection` as HTTP/1.1:
/// `GET`/`HEAD` on `/media/<token>` with `Range` support (the Cast
/// receiver byte-range probes + seeks). One responder per accepted
/// connection; it owns the connection, runs all I/O on a dedicated serial
/// queue, streams the body in bounded chunks (no whole-file buffering),
/// and reports completion via `onFinished` so the owner can drop it.
final class MediaHTTPConnection {

  private let connection: NWConnection
  private let resolve: (String) -> MediaSource?
  private let onFinished: () -> Void
  private let queue: DispatchQueue
  /// In-flight body-streaming task, cancelled when the client hangs up so
  /// we stop reading a range the receiver has abandoned.
  private var streamTask: Task<Void, Never>?
  private let stateLock = NSLock()
  private var finished = false

  /// Bound on the accumulated request header so a stalled/malicious client
  /// can't grow the buffer without limit.
  private static let maxHeaderBytes = 64 * 1024
  /// Body streaming chunk — keeps memory flat for large offline files.
  private static let chunkBytes = 256 * 1024
  private static let headerTerminator = Data("\r\n\r\n".utf8)

  /// Sent back on `/health`, so a probe can tell its own server from another
  /// process that took the port. nil sends no instance header.
  private let serverInstance: String?

  init(
    connection: NWConnection,
    resolve: @escaping (String) -> MediaSource?,
    serverInstance: String? = nil,
    onFinished: @escaping () -> Void
  ) {
    self.connection = connection
    self.resolve = resolve
    self.serverInstance = serverInstance
    self.onFinished = onFinished
    self.queue = DispatchQueue(label: "queueplayer.cast.mediaserver.conn")
  }

  func start() {
    connection.stateUpdateHandler = { [weak self] state in
      guard let self = self else { return }
      switch state {
      case .ready:
        self.readRequest(buffer: Data())
      case .failed(let error):
        // A receiver cancelling a range connection it no longer needs
        // arrives here as a reset — expected churn, not an error.
        if !Self.isBenignDisconnect(error) {
          NSLog("[LocalMediaServer] connection failed: \(error)")
        }
        self.cancel()
      case .cancelled:
        self.finish()
      default:
        break
      }
    }
    connection.start(queue: queue)
  }

  /// Cancel the connection + any in-flight streaming. Idempotent.
  func cancel() {
    stateLock.lock()
    let task = streamTask
    streamTask = nil
    stateLock.unlock()
    task?.cancel()
    connection.cancel()
  }

  // MARK: - Request

  private func readRequest(buffer: Data) {
    connection.receive(minimumIncompleteLength: 1, maximumLength: 16 * 1024) {
      [weak self] data, _, isComplete, error in
      guard let self = self else { return }
      if error != nil {
        self.cancel()
        return
      }
      var buf = buffer
      if let data = data { buf.append(data) }
      // Bound the header accumulation right after the append so a client
      // that never sends `\r\n\r\n` can't grow the buffer past the cap.
      if buf.count > Self.maxHeaderBytes {
        self.respondError(status: 431, reason: "Request Header Fields Too Large")
        return
      }
      guard let terminator = buf.range(of: Self.headerTerminator) else {
        if isComplete {
          self.cancel()
        } else {
          self.readRequest(buffer: buf)
        }
        return
      }
      let headerData = buf.subdata(in: buf.startIndex..<terminator.lowerBound)
      guard let request = MediaHTTPRequest(headerData: headerData) else {
        self.respondError(status: 400, reason: "Bad Request")
        return
      }
      self.serve(request)
    }
  }

  private func serve(_ request: MediaHTTPRequest) {
    guard request.method == "GET" || request.method == "HEAD" else {
      respondError(status: 405, reason: "Method Not Allowed")
      return
    }
    // Answered here, before any token lookup, so a `200` proves the listener
    // accepts and this code responds, and nothing more: no cache, origin or file.
    if request.path == "/health" {
      sendHeadOnly(Self.headHealth(instance: serverInstance))
      return
    }
    guard let token = request.mediaToken, let source = resolve(token) else {
      respondError(status: 404, reason: "Not Found")
      return
    }
    switch source {
    case .file(let url, let contentType):
      guard let handle = Self.openForReading(url) else {
        Self.logCache("404 no-file", request: request, detail: url.lastPathComponent)
        respondError(status: 404, reason: "Not Found")
        return
      }
      Self.logCache("DISK", request: request, detail: "\(handle.size)B \(url.lastPathComponent)")
      serveFile(handle, contentType: contentType, request: request)
    case .readThrough(let src):
      // The cache answers only when it holds the whole file AND the file opens
      // right now — anything else reads through. Opening before committing to a
      // response is what makes that fall-through real: once the head is sent
      // there is no going back to the origin.
      if let cached = src.cachedFile, let handle = Self.openForReading(cached) {
        Self.logCache(
          "HIT", request: request,
          detail: "\(handle.size)B from \(cached.lastPathComponent)")
        serveFile(handle, contentType: MimeTypes.fromFile(cached), request: request)
      } else {
        Self.logCache(
          "MISS", request: request,
          detail: src.cachedFile == nil ? "not cached" : "cache file unopenable")
        readThrough(src, request: request)
      }
    }
  }

  /// Open a regular file and measure it, or nil if it is missing, a directory,
  /// or unreadable. One syscall path for both the stat and the open, so the
  /// file cannot vanish between deciding to serve it and reading it.
  private static func openForReading(_ url: URL) -> (handle: FileHandle, size: Int64)? {
    var isDir: ObjCBool = false
    guard FileManager.default.fileExists(atPath: url.path, isDirectory: &isDir), !isDir.boolValue,
          let handle = try? FileHandle(forReadingFrom: url),
          let size = try? handle.seekToEnd()
    else { return nil }
    try? handle.seek(toOffset: 0)
    return (handle, Int64(size))
  }

  private func serveFile(
    _ opened: (handle: FileHandle, size: Int64),
    contentType: String,
    request: MediaHTTPRequest
  ) {
    let size = opened.size
    let isHead = request.method == "HEAD"
    switch Self.resolveRange(request.rangeHeader, fileSize: size) {
    case .full:
      let head = Self.headFull(contentType: contentType, fileSize: size)
      sendBodyAfterHead(head, handle: opened.handle, start: 0, end: max(0, size - 1), include: size > 0 && !isHead)
    case .partial(let start, let end):
      let head = Self.headPartial(contentType: contentType, start: start, end: end, fileSize: size)
      sendBodyAfterHead(head, handle: opened.handle, start: start, end: end, include: !isHead)
    case .unsatisfiable:
      try? opened.handle.close()
      sendHeadOnly(Self.headUnsatisfiable(fileSize: size))
    }
  }

  // MARK: - Response

  private func sendHeadOnly(_ head: String) {
    connection.send(content: Data(head.utf8), completion: .contentProcessed { [weak self] _ in
      self?.cancel()
    })
  }

  /// Send the header block, then (when `include`) stream the inclusive
  /// `[start, end]` byte range from `handle` in bounded chunks. The caller
  /// passes `include: false` for a `HEAD` or an empty file, which sends the
  /// head and closes.
  private func sendBodyAfterHead(_ head: String, handle: FileHandle, start: Int64, end: Int64, include: Bool) {
    let task = Task { [weak self] in
      guard let self = self else { try? handle.close(); return }
      defer { try? handle.close() }
      do {
        try await self.send(Data(head.utf8))
        if include {
          try await self.streamFile(handle, start: start, end: end)
        }
        self.cancel()
      } catch {
        // A client disconnect mid-stream surfaces as a send/read failure —
        // normal churn for cancelled range requests, just clean up. A file
        // unlinked mid-response keeps reading: the handle holds the inode.
        self.cancel()
      }
    }
    stateLock.lock()
    streamTask = task
    stateLock.unlock()
  }

  private func streamFile(_ handle: FileHandle, start: Int64, end: Int64) async throws {
    try handle.seek(toOffset: UInt64(start))
    var remaining = end - start + 1
    while remaining > 0 {
      if Task.isCancelled { return }
      let want = Int(min(Int64(Self.chunkBytes), remaining))
      guard let chunk = try handle.read(upToCount: want), !chunk.isEmpty else { return }
      try await send(chunk)
      remaining -= Int64(chunk.count)
    }
  }

  /// Relay the origin, applying the consumer's headers and forwarding the
  /// client's `Range` untouched.
  ///
  /// The origin's status line is relayed as-is, so an HTTP error stays an HTTP
  /// error and the player classifies it as one. An origin leg that fails before
  /// producing any response closes the connection without a reply, which
  /// AVFoundation reports as a transport failure rather than a server one —
  /// the distinction the retry classifier depends on.
  private func readThrough(_ src: ReadThroughSource, request: MediaHTTPRequest) {
    let task = Task { [weak self] in
      guard let self = self else { return }
      do {
        var upstream = URLRequest(url: src.origin)
        upstream.httpMethod = request.method
        for (name, value) in src.headers {
          upstream.setValue(value, forHTTPHeaderField: name)
        }
        if let range = request.rangeHeader {
          upstream.setValue(range, forHTTPHeaderField: "Range")
        }
        // Identity, always. `URLSession` decodes transparently, so a compressed
        // origin would leave the cache holding decoded bytes while a `Range`
        // addresses the compressed ones — two different byte spaces for one
        // resource, and a cache hit and a miss would disagree about every offset.
        upstream.setValue("identity", forHTTPHeaderField: "Accept-Encoding")

        // The body arrives as the `Data` chunks URLSession delivers, not one
        // byte per iteration: this is the playback path when the cache misses.
        let body = OriginBody()
        let dataTask = src.session.dataTask(with: upstream)
        dataTask.delegate = body
        dataTask.resume()
        defer { dataTask.cancel() }
        // The head wait is a plain continuation, so a client hanging up while
        // the origin is still answering has to reach the task through the
        // cancellation handler; the cancel completes the task with an error,
        // which resumes the waiter.
        let response = try await withTaskCancellationHandler {
          try await body.response()
        } onCancel: {
          dataTask.cancel()
        }
        guard let http = response as? HTTPURLResponse else {
          self.cancel()
          return
        }
        Self.logCache(
          "ORIGIN \(http.statusCode)", request: request,
          detail: "len=\(http.expectedContentLength) "
            + "range=\(http.value(forHTTPHeaderField: "Content-Range") ?? "-")")
        try await self.send(Data(Self.relayHead(http).utf8))
        if request.method != "HEAD" {
          for try await chunk in body.chunks {
            if Task.isCancelled { break }
            try await self.send(chunk)
            body.consumed(chunk.count, task: dataTask)
          }
        }
        self.cancel()
      } catch {
        Self.logCache("ORIGIN-FAILED", request: request, detail: "\(error)")
        self.cancel()
      }
    }
    stateLock.lock()
    streamTask = task
    stateLock.unlock()
  }

  /// Where every response came from, so "the cache is not being used" is a
  /// thing the log answers rather than a thing to infer from byte totals.
  static func logCache(_ verdict: String, request: MediaHTTPRequest, detail: String) {
    #if DEBUG
      NSLog(
        "[RNQP-CACHE] %@ %@ range=%@ token=%@ %@",
        verdict, request.method, request.rangeHeader ?? "-",
        String((request.mediaToken ?? "-").prefix(8)), detail)
    #endif
  }

  /// Headers relayed from the origin to the client. Everything else is dropped:
  /// the client is AVFoundation reading media, and nothing else the origin says
  /// is its business.
  static let relayedHeaders = ["Content-Type", "Content-Length", "Content-Range", "Accept-Ranges"]

  /// The reason phrases for the statuses an origin relays. Fixed ASCII rather
  /// than `HTTPURLResponse.localizedString(forStatusCode:)`, which is
  /// localised text; the phrase is optional on the wire, so an unlisted
  /// status is relayed with none.
  static func reasonPhrase(for status: Int) -> String {
    switch status {
    case 200: return "OK"
    case 206: return "Partial Content"
    case 304: return "Not Modified"
    case 400: return "Bad Request"
    case 401: return "Unauthorized"
    case 403: return "Forbidden"
    case 404: return "Not Found"
    case 416: return "Range Not Satisfiable"
    case 500: return "Internal Server Error"
    case 502: return "Bad Gateway"
    case 503: return "Service Unavailable"
    default: return ""
    }
  }

  static func relayHead(_ response: HTTPURLResponse) -> String {
    var head = "HTTP/1.1 \(response.statusCode) "
      + "\(reasonPhrase(for: response.statusCode))\r\n"
    for name in relayedHeaders {
      if let value = response.value(forHTTPHeaderField: name) {
        head += "\(name): \(value)\r\n"
      }
    }
    return head + "Connection: close\r\n\r\n"
  }

  private func send(_ data: Data) async throws {
    try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
      connection.send(content: data, completion: .contentProcessed { error in
        if let error = error { cont.resume(throwing: error) } else { cont.resume(returning: ()) }
      })
    }
  }

  private func respondError(status: Int, reason: String) {
    sendHeadOnly(Self.headError(status: status, reason: reason))
  }

  private func finish() {
    stateLock.lock()
    if finished { stateLock.unlock(); return }
    finished = true
    let task = streamTask
    streamTask = nil
    stateLock.unlock()
    task?.cancel()
    onFinished()
  }

  // MARK: - Range resolution (pure)

  /// Resolve an HTTP `Range` header against `fileSize`. Single-range only
  /// (the Cast receiver never sends multi-range); a multi-range header
  /// resolves to its first range. Per RFC 7233 a malformed / inverted
  /// range is ignored (`.full`, i.e. `200`); a syntactically-valid range
  /// starting at or past EOF is `.unsatisfiable` (`416`).
  static func resolveRange(_ header: String?, fileSize: Int64) -> MediaByteRange {
    guard fileSize > 0 else { return .full }
    guard let raw = header?.lowercased(), raw.hasPrefix("bytes=") else { return .full }
    let spec = raw.dropFirst("bytes=".count)
    let firstSpec = spec.split(separator: ",").first.map(String.init) ?? ""
    guard let dash = firstSpec.firstIndex(of: "-") else { return .full }
    let startStr = firstSpec[firstSpec.startIndex..<dash].trimmingCharacters(in: .whitespaces)
    let endStr = firstSpec[firstSpec.index(after: dash)...].trimmingCharacters(in: .whitespaces)

    if startStr.isEmpty {
      // Suffix form `-N`: the last N bytes.
      guard let suffix = Int64(endStr), suffix > 0 else { return .unsatisfiable }
      let start = max(0, fileSize - suffix)
      return .partial(start: start, end: fileSize - 1)
    }
    guard let start = Int64(startStr), start >= 0 else { return .full }
    if start >= fileSize { return .unsatisfiable }
    if endStr.isEmpty {
      return .partial(start: start, end: fileSize - 1)
    }
    guard let end = Int64(endStr) else { return .full }
    if end < start { return .full }
    return .partial(start: start, end: min(end, fileSize - 1))
  }

  // MARK: - Header builders (pure)

  static func headFull(contentType: String, fileSize: Int64) -> String {
    "HTTP/1.1 200 OK\r\n"
      + "Content-Type: \(contentType)\r\n"
      + "Content-Length: \(fileSize)\r\n"
      + "Accept-Ranges: bytes\r\n"
      + "Connection: close\r\n\r\n"
  }

  static func headPartial(contentType: String, start: Int64, end: Int64, fileSize: Int64) -> String {
    "HTTP/1.1 206 Partial Content\r\n"
      + "Content-Type: \(contentType)\r\n"
      + "Content-Range: bytes \(start)-\(end)/\(fileSize)\r\n"
      + "Content-Length: \(end - start + 1)\r\n"
      + "Accept-Ranges: bytes\r\n"
      + "Connection: close\r\n\r\n"
  }

  static func headUnsatisfiable(fileSize: Int64) -> String {
    "HTTP/1.1 416 Range Not Satisfiable\r\n"
      + "Content-Range: bytes */\(fileSize)\r\n"
      + "Content-Length: 0\r\n"
      + "Connection: close\r\n\r\n"
  }

  static let instanceHeader = "X-Queue-Player-Instance"

  static func headHealth(instance: String?) -> String {
    "HTTP/1.1 200 OK\r\n"
      + (instance.map { "\(instanceHeader): \($0)\r\n" } ?? "")
      + "Content-Length: 0\r\n"
      + "Connection: close\r\n\r\n"
  }

  static func headError(status: Int, reason: String) -> String {
    "HTTP/1.1 \(status) \(reason)\r\n"
      + "Content-Length: 0\r\n"
      + "Connection: close\r\n\r\n"
  }

  // MARK: - Helpers

  private static func isBenignDisconnect(_ error: NWError) -> Bool {
    if case .posix(let code) = error {
      return code == .ECONNRESET || code == .ENOTCONN || code == .EPIPE || code == .ECANCELED
    }
    return false
  }
}

