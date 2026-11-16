import Network
import XCTest
@testable import QueuePlayer

/// The `/health` probe both local media servers rely on, driven against a
/// loopback listener that answers a fixed response head.
final class HealthProbeTests: XCTestCase {

  private let instance = String(repeating: "a", count: 32)
  private let probeQueue = DispatchQueue(label: "health-probe-tests")

  func testAMatchingServerIsAnswering() throws {
    let server = try FixedHeadServer(
      head: "HTTP/1.1 200 OK\r\n\(MediaHTTPConnection.instanceHeader): \(instance)\r\nContent-Length: 0\r\n\r\n")
    defer { server.stop() }

    let result = HealthProbe.check(
      port: server.port, instance: instance, queue: probeQueue, timeoutMs: 2000)

    XCTAssertTrue(result.answered, result.reason)
  }

  /// Another server on the port, such as a process that took a reclaimed one,
  /// does not pass for ours.
  func testAnotherServersInstanceIsNotAnswering() throws {
    let other = String(repeating: "b", count: 32)
    let server = try FixedHeadServer(
      head: "HTTP/1.1 200 OK\r\n\(MediaHTTPConnection.instanceHeader): \(other)\r\nContent-Length: 0\r\n\r\n")
    defer { server.stop() }

    let result = HealthProbe.check(
      port: server.port, instance: instance, queue: probeQueue, timeoutMs: 2000)

    XCTAssertFalse(result.answered)
    XCTAssertTrue(result.reason.contains("another server"), result.reason)
  }

  func testAnErrorStatusWithOurInstanceIsNotAnswering() throws {
    let server = try FixedHeadServer(
      head: "HTTP/1.1 503 Service Unavailable\r\n\(MediaHTTPConnection.instanceHeader): \(instance)\r\nContent-Length: 0\r\n\r\n")
    defer { server.stop() }

    let result = HealthProbe.check(
      port: server.port, instance: instance, queue: probeQueue, timeoutMs: 2000)

    XCTAssertFalse(result.answered)
    XCTAssertTrue(result.reason.contains("status HTTP/1.1 503"), result.reason)
  }

  /// A refused loopback connect reports `.waiting` rather than failing; the
  /// probe treats that as an answer instead of waiting out its timeout.
  func testARefusedPortIsNotAnsweringBeforeTheTimeout() throws {
    let server = try FixedHeadServer(head: "")
    let port = server.port
    server.stop()

    let started = Date()
    let result = HealthProbe.check(port: port, instance: instance, queue: probeQueue, timeoutMs: 5000)

    XCTAssertFalse(result.answered)
    XCTAssertLessThan(Date().timeIntervalSince(started), 2, result.reason)
  }
}

/// A loopback listener that answers every connection with `head` and closes it.
private final class FixedHeadServer {
  let port: UInt16
  private let listener: NWListener
  private let queue = DispatchQueue(label: "fixed-head-server")
  private let cancelled = DispatchSemaphore(value: 0)

  init(head: String) throws {
    let params = NWParameters.tcp
    params.requiredLocalEndpoint = NWEndpoint.hostPort(host: "127.0.0.1", port: .any)
    listener = try NWListener(using: params)
    let ready = DispatchSemaphore(value: 0)
    let reply = Data(head.utf8)
    let queue = self.queue
    let cancelled = self.cancelled
    listener.stateUpdateHandler = { state in
      switch state {
      case .ready: ready.signal()
      case .cancelled: cancelled.signal()
      default: break
      }
    }
    listener.newConnectionHandler = { conn in
      conn.start(queue: queue)
      conn.receive(minimumIncompleteLength: 1, maximumLength: 1024) { _, _, _, _ in
        conn.send(content: reply, completion: .contentProcessed { _ in conn.cancel() })
      }
    }
    listener.start(queue: queue)
    guard ready.wait(timeout: .now() + 2) == .success, let bound = listener.port?.rawValue else {
      listener.cancel()
      throw NSError(domain: "FixedHeadServer", code: 1, userInfo: [NSLocalizedDescriptionKey: "did not bind"])
    }
    port = bound
  }

  /// Cancel the listener and wait for it to finish, so its port refuses connections.
  func stop() {
    listener.cancel()
    XCTAssertEqual(
      cancelled.wait(timeout: .now() + 2), .success, "the test listener did not finish cancelling")
  }
}
