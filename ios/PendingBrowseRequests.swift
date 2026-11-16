import Foundation

/// Native-side queue of `parentId`s a controller asked the CarPlay
/// browse surface to enumerate before the consumer's JS bridge had
/// pushed a [BrowseSnapshot] into [CarPlayBrowseDataProvider]. The
/// empty `getChildren` response is the "still loading" UX hint to
/// the controller; once a snapshot lands, the scene delegate drains
/// the queue and rebuilds the affected `CPListTemplate` rows so the
/// cells render with real data.
///
/// iOS mirror of the Android `PendingBrowseRequests`. Same shape,
/// same semantics: idempotent enqueue, atomic drain, idempotent
/// clear.
///
/// Threading: every method is safe to call from any thread —
/// `NSLock` guards the underlying set.
final class PendingBrowseRequests {

  /// Record `parentId` as a pending request. Idempotent — duplicate
  /// enqueues collapse, so a single [drainAll] yields one rebuild
  /// per unique parent regardless of how many controllers asked for
  /// it before the snapshot landed.
  func enqueue(_ parentId: String) {
    lock.lock()
    defer { lock.unlock() }
    pending.insert(parentId)
  }

  /// Atomically take + clear the pending set. Subsequent calls return
  /// empty until the next [enqueue].
  @discardableResult
  func drainAll() -> Set<String> {
    lock.lock()
    defer { lock.unlock() }
    let drained = pending
    pending.removeAll()
    return drained
  }

  /// Discard every pending entry without triggering any rebuild.
  /// Invoked when the CarPlay scene tears down so a stale entry
  /// doesn't strand across the next scene attach.
  func clear() {
    lock.lock()
    defer { lock.unlock() }
    pending.removeAll()
  }

  /// Snapshot the pending parents without consuming. Production
  /// code drains via [drainAll]; this is for tests only. Stays
  /// `internal` (test target imports via `@testable import`) —
  /// mirrors the Kotlin `internal fun snapshotForTest` shape on
  /// the Android side.
  internal func snapshotForTest() -> Set<String> {
    lock.lock()
    defer { lock.unlock() }
    return pending
  }

  private var pending: Set<String> = []
  private let lock = NSLock()
}
