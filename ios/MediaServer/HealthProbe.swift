import Foundation
import Network

/// Asks a local media server whether it is still serving: `GET /health` over
/// loopback, answered `200` with the server's own instance id.
///
/// A full request rather than a connect: a listener the OS reclaimed while the
/// app was suspended can still complete a handshake and then drop the
/// connection, and another process can take its port. A refused loopback
/// connect reports `.waiting` rather than failing, so `.waiting` is an answer
/// of no.
enum HealthProbe {

  /// Whether `127.0.0.1:port` answers within `timeoutMs`, and why not when it
  /// does not. Synchronous: blocks its caller while the probe runs on `queue`,
  /// so never call it on `queue`.
  static func check(
    port: UInt16, instance: String, queue: DispatchQueue, timeoutMs: Int
  ) -> (answered: Bool, reason: String) {
    guard let nwPort = NWEndpoint.Port(rawValue: port) else { return (false, "invalid port") }
    let outcome = ProbeOutcome()
    let conn = NWConnection(host: .ipv4(.loopback), port: nwPort, using: .tcp)
    conn.stateUpdateHandler = { state in
      switch state {
      case .ready:
        conn.send(
          content: healthRequest,
          completion: .contentProcessed { error in
            if let error { outcome.settle(false, "send \(error)") }
          })
        readHead(conn, into: Data(), expecting: instance, outcome: outcome)
      case .waiting(let error): outcome.settle(false, "waiting \(error)")
      case .failed(let error): outcome.settle(false, "failed \(error)")
      case .cancelled: outcome.settle(false, "cancelled")
      default: break
      }
    }
    conn.start(queue: queue)
    let answered = outcome.wait(ms: timeoutMs)
    // Read before the cancel, whose `.cancelled` would settle a timed-out
    // probe as cancelled and hide why it failed.
    let reason = outcome.reason
    conn.cancel()
    return (answered, reason)
  }

  private static let healthRequest = Data(
    "GET /health HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n".utf8)

  /// Read until the response head is complete, then settle on whether it is a
  /// `200` carrying `instance`.
  private static func readHead(
    _ conn: NWConnection, into buffer: Data, expecting instance: String, outcome: ProbeOutcome
  ) {
    conn.receive(minimumIncompleteLength: 1, maximumLength: 1024) { data, _, isComplete, error in
      var received = buffer
      if let data { received.append(data) }
      if let end = received.range(of: Data("\r\n\r\n".utf8)) {
        let lines = String(decoding: received[..<end.lowerBound], as: UTF8.self)
          .components(separatedBy: "\r\n")
        let status = lines.first ?? ""
        let ours = lines.contains("\(MediaHTTPConnection.instanceHeader): \(instance)")
        outcome.settle(
          status.hasPrefix("HTTP/1.1 200") && ours,
          ours ? "status \(status)" : "status \(status) from another server")
      } else if let error {
        outcome.settle(false, "receive \(error)")
      } else if isComplete || received.count >= 1024 {
        outcome.settle(false, "no response head")
      } else {
        readHead(conn, into: received, expecting: instance, outcome: outcome)
      }
    }
  }
}

/// The first answer a probe settles on, shared by its connection's callbacks
/// and the caller waiting on it. Later answers are ignored.
private final class ProbeOutcome: @unchecked Sendable {
  private let lock = NSLock()
  private let settled = DispatchSemaphore(value: 0)
  private var answer: Bool?
  private var why = "timeout"

  func settle(_ answered: Bool, _ reason: String) {
    lock.lock()
    guard answer == nil else { lock.unlock(); return }
    answer = answered
    why = reason
    lock.unlock()
    settled.signal()
  }

  /// The settled answer, or false when nothing settles within `ms`.
  func wait(ms: Int) -> Bool {
    _ = settled.wait(timeout: .now() + .milliseconds(ms))
    lock.lock(); defer { lock.unlock() }
    return answer ?? false
  }

  var reason: String {
    lock.lock(); defer { lock.unlock() }
    return why
  }
}
