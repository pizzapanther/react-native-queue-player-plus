import Foundation

/// Native cache for the most-recent [BrowseSnapshot] pushed by the
/// consumer via `setBrowseSnapshot`. iOS mirror of the Android
/// `BrowseDataProvider`. The CarPlay scene delegate's browse surface
/// reads from this cache so a controller-driven re-fetch does not
/// have to round-trip through JS.
///
/// Snapshot replacement is atomic: a single `NSLock` guards the
/// stored reference. Concurrent readers see either the prior
/// snapshot in full or the new one in full, never a torn mix.
///
/// Update notifications: a `Notification` named
/// [CarPlayBrowseDataProvider.didUpdateNotification] fires on the
/// main queue after every successful `setSnapshot`. The CarPlay
/// scene delegate observes the notification + rebuilds the active
/// template against the new snapshot.
final class CarPlayBrowseDataProvider {

  /// Notification posted on the main queue after `setSnapshot`. The
  /// `userInfo` dictionary carries one key — `"changedParents"` —
  /// mapping to a `Set<String>` of parent IDs whose children list
  /// changed (root + every section id from the prior + new
  /// snapshots). Observers loop the set + rebuild the affected
  /// `CPListTemplate` rows.
  static let didUpdateNotification = Notification.Name(
    "com.margelo.nitro.queueplayer.CarPlayBrowseDataProvider.didUpdate"
  )

  /// `userInfo` key under [didUpdateNotification] carrying the
  /// `Set<String>` of changed parent IDs.
  static let changedParentsKey = "changedParents"

  /// Replace the cached snapshot. Pass `nil` to clear. Returns the
  /// set of parentIds whose children list changed (root + every
  /// section id from the prior + new snapshots) so the caller can
  /// fan out per-parent UI invalidations alongside the catch-all
  /// notification.
  ///
  /// Returns an empty set when both the prior and the incoming
  /// snapshot are `nil` (e.g. clearing a cache that was never
  /// populated). The notification still fires with an empty
  /// changedParents userInfo so observers can drop any stale
  /// "loading…" placeholder.
  @discardableResult
  func setSnapshot(_ snapshot: BrowseSnapshot?) -> Set<String> {
    var changedParents: Set<String> = []
    lock.lock()
    let previous = self.snapshot
    self.snapshot = snapshot
    lock.unlock()

    if let p = previous {
      changedParents.insert(p.rootId)
      for section in p.sections {
        changedParents.insert(section.id)
      }
    }
    if let s = snapshot {
      changedParents.insert(s.rootId)
      for section in s.sections {
        changedParents.insert(section.id)
      }
    }

    let notify = {
      NotificationCenter.default.post(
        name: CarPlayBrowseDataProvider.didUpdateNotification,
        object: self,
        userInfo: [CarPlayBrowseDataProvider.changedParentsKey: changedParents]
      )
    }
    if Thread.isMainThread {
      notify()
    } else {
      DispatchQueue.main.async(execute: notify)
    }
    return changedParents
  }

  /// Read the current snapshot, or `nil` if none has been set.
  func getSnapshot() -> BrowseSnapshot? {
    lock.lock()
    defer { lock.unlock() }
    return snapshot
  }

  /// True when the parentId addresses the root of the cached
  /// snapshot. False when no snapshot is set.
  func isRoot(_ parentId: String) -> Bool {
    lock.lock()
    defer { lock.unlock() }
    return snapshot?.rootId == parentId
  }

  /// Find a section by id. Returns nil when no snapshot is set or
  /// no section matches.
  func getSection(_ sectionId: String) -> BrowseSection? {
    lock.lock()
    defer { lock.unlock() }
    return snapshot?.sections.first(where: { $0.id == sectionId })
  }

  /// Search the full snapshot for an item with the given id. Walks
  /// every section in declaration order. Returns nil when no
  /// snapshot is set or no item matches.
  func getItem(_ itemId: String) -> BrowseItem? {
    lock.lock()
    defer { lock.unlock() }
    guard let snapshot = snapshot else { return nil }
    for section in snapshot.sections {
      for item in section.items where item.id == itemId {
        return item
      }
    }
    return nil
  }

  /// Resolve a parentId to its children, paginated.
  ///  - `parentId == snapshot.rootId` returns one synthetic item per
  ///    section: `id = section.id, title = section.title,
  ///    playable = false, hasChildren = true`.
  ///  - `parentId == section.id` returns that section's items.
  ///  - Anything else returns an empty list.
  ///
  ///  Pagination: 0-based page index. `pageSize <= 0` is treated as
  ///  unbounded (returns the full list). Out-of-range pages return
  ///  empty.
  func getChildren(parentId: String, page: Int, pageSize: Int) -> [BrowseItem] {
    lock.lock()
    let local = snapshot
    lock.unlock()

    guard let snapshot = local else { return [] }
    let items: [BrowseItem]
    if parentId == snapshot.rootId {
      items = snapshot.sections.map { section in
        BrowseItem(
          id: section.id,
          title: section.title,
          subtitle: nil,
          artworkUrl: section.artworkUrl,
          playable: false,
          hasChildren: true
        )
      }
    } else if let section = snapshot.sections.first(where: { $0.id == parentId }) {
      items = Array(section.items)
    } else {
      return []
    }
    if pageSize <= 0 { return items }
    if page < 0 { return [] }
    let from = page * pageSize
    if from >= items.count { return [] }
    let to = min(from + pageSize, items.count)
    return Array(items[from..<to])
  }

  private var snapshot: BrowseSnapshot?
  private let lock = NSLock()
}
