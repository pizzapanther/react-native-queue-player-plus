import Foundation

/// Single-slot buffer for an intent that arrived before the JS-side
/// resolver had registered. Holds at most one request per instance;
/// a subsequent `push` replaces any prior entry (latest-wins).
///
/// Entries carry a `bufferedAt` timestamp and expire after `ttl`
/// seconds. Expired entries are dropped on the next `peek` / `drain`
/// without producing a result, so a stale cold-launch intent never
/// reaches a JS resolver that registers minutes later.
///
/// `CarPlayBridge` holds one instance for `INPlayMediaIntent`
/// cold-start dispatches. The primitive is generic over `Request` so
/// future SiriKit intent paths can reuse it without duplication.
/// `push` rather than `enqueue` because the buffer is single-slot —
/// the sibling `PendingBrowseRequests` uses set semantics, this one
/// does not.
///
/// Thread-safety: all mutating operations are guarded by an
/// `NSLock`, so the buffer is safe to share across any thread the
/// caller chooses.
///
/// Clock contract: callers must supply a monotonically non-decreasing
/// clock. Negative-skew elapsed values are clamped to zero so a
/// backwards-moving clock can't extend an entry's effective TTL.
public final class PendingIntentBuffer<Request: Sendable>: @unchecked Sendable {

  private struct Entry {
    let request: Request
    let bufferedAt: Date
  }

  private let lock = NSLock()
  private var entry: Entry?
  private let ttl: TimeInterval
  private let clock: @Sendable () -> Date

  public init(ttl: TimeInterval = 20, clock: @escaping @Sendable () -> Date = { Date() }) {
    self.ttl = ttl
    self.clock = clock
  }

  /// Store `request`, replacing any prior entry. The new entry's
  /// `bufferedAt` is read from the injected clock.
  public func push(_ request: Request) {
    lock.lock()
    defer { lock.unlock() }
    entry = Entry(request: request, bufferedAt: clock())
  }

  /// Remove and return the buffered request, or nil if empty or
  /// expired. Drains the slot in both cases — an expired entry is
  /// not retained for a later non-expired read.
  public func drain() -> Request? {
    lock.lock()
    defer { lock.unlock() }
    guard let current = entry else { return nil }
    entry = nil
    let elapsed = max(0, clock().timeIntervalSince(current.bufferedAt))
    if elapsed > ttl { return nil }
    return current.request
  }

  /// Inspect the buffered request without removing it. Auto-clears
  /// an expired entry as a side effect so a stale request can't be
  /// inspected indefinitely.
  public func peek() -> Request? {
    lock.lock()
    defer { lock.unlock() }
    guard let current = entry else { return nil }
    let elapsed = max(0, clock().timeIntervalSince(current.bufferedAt))
    if elapsed > ttl {
      entry = nil
      return nil
    }
    return current.request
  }

  /// Drop any buffered request without returning it. Used when a
  /// caller wants to clear the slot unconditionally (e.g. teardown).
  public func clear() {
    lock.lock()
    defer { lock.unlock() }
    entry = nil
  }

  /// True when the slot is empty (or holds only an expired entry).
  /// Side effect: clears an expired entry, matching `peek`.
  public var isEmpty: Bool {
    lock.lock()
    defer { lock.unlock() }
    guard let current = entry else { return true }
    let elapsed = max(0, clock().timeIntervalSince(current.bufferedAt))
    if elapsed > ttl {
      entry = nil
      return true
    }
    return false
  }
}
