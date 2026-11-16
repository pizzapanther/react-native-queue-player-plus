import Foundation

/// Per-cast-session handle onto `LocalMediaServer`. Returned from
/// `LocalMediaServer.acquire`. The backend registers each consumer-
/// supplied `file://` URL it wants exposed to a receiver, gets back
/// a full `http://<lan>:<port>/media/<token>` URL, and on session end
/// calls `close()` to revoke every token registered through this
/// handle plus decrement the server's refcount.
final class MediaServerHandle {
  private weak var server: LocalMediaServer?
  private let sessionId: String
  private var closed = false
  private let lock = NSLock()

  init(server: LocalMediaServer, sessionId: String) {
    self.server = server
    self.sessionId = sessionId
  }

  /// Registers `fileURL` under a fresh token and returns the receiver-
  /// reachable `http://` URL. Optional `contentType` overrides MIME
  /// inferred from the file extension. Nil once the handle is closed: close
  /// revokes the session's tokens, and one registered afterwards never would
  /// be. Holds `lock` through the registration, so `close()` cannot revoke in
  /// between.
  func register(fileURL: URL, lanAddress: String, contentType: String? = nil) -> URL? {
    lock.lock()
    defer { lock.unlock() }
    guard !closed, let server = server, let port = server.serverPortOrNil else { return nil }
    let token = server.register(sessionId: sessionId, fileURL: fileURL, contentType: contentType)
    return URL(string: "http://\(lanAddress):\(port)/media/\(token)")
  }

  func close() {
    lock.lock()
    if closed {
      lock.unlock()
      return
    }
    closed = true
    lock.unlock()
    server?.release(sessionId: sessionId)
  }

  deinit {
    if !closed {
      close()
    }
  }
}
