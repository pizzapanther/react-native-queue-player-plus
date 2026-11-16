import Foundation

/// One queue entry mirrored for the CarPlay Up Next surface. A plain
/// value type decoupled from the Nitro `TrackItem` — the mirror holds
/// only what the list template renders, so the CarPlay scene reads
/// display data without reaching back into the Hybrid.
struct CarPlayQueueEntry {
  let title: String
  let subtitle: String?
  let artworkUrl: String?
}

/// Immutable mirror of the live play queue for the CarPlay Up Next
/// list. `currentIndex` is the index of the playing track, or -1 when
/// nothing is current. Snapshot replacement is atomic (see
/// [CarPlayQueueProvider]).
struct CarPlayQueueSnapshot {
  let entries: [CarPlayQueueEntry]
  let currentIndex: Int
}

/// Native cache for the most-recent [CarPlayQueueSnapshot] pushed by
/// `TrackPlayer` whenever the queue or current index changes. The
/// CarPlay scene delegate reads from this cache to build the Up Next
/// list so a queue-button tap does not have to round-trip through JS.
///
/// Snapshot replacement is atomic: a single `NSLock` guards the stored
/// reference. Concurrent readers see either the prior snapshot in full
/// or the new one in full, never a torn mix.
///
/// Update notifications: a `Notification` named
/// [CarPlayQueueProvider.didUpdateNotification] fires on the main queue
/// after every `setSnapshot`. The scene delegate observes it to
/// re-evaluate the Up Next button's enabled state as the queue changes.
final class CarPlayQueueProvider {

  /// Notification posted on the main queue after `setSnapshot`.
  static let didUpdateNotification = Notification.Name(
    "com.margelo.nitro.queueplayer.CarPlayQueueProvider.didUpdate"
  )

  /// Replace the cached snapshot. Pass `nil` to clear. Posts
  /// [didUpdateNotification] on the main queue (dispatched to main if
  /// the caller is off-main) so observers re-evaluate against the new
  /// snapshot on a stable thread.
  func setSnapshot(_ snapshot: CarPlayQueueSnapshot?) {
    lock.lock()
    self.snapshot = snapshot
    lock.unlock()

    let notify = {
      NotificationCenter.default.post(
        name: CarPlayQueueProvider.didUpdateNotification,
        object: self
      )
    }
    if Thread.isMainThread {
      notify()
    } else {
      DispatchQueue.main.async(execute: notify)
    }
  }

  /// Read the current snapshot, or `nil` if none has been set.
  func getSnapshot() -> CarPlayQueueSnapshot? {
    lock.lock()
    defer { lock.unlock() }
    return snapshot
  }

  private var snapshot: CarPlayQueueSnapshot?
  private let lock = NSLock()
}
