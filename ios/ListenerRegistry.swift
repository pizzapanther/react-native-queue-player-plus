import Foundation

/// Thread-safe registry of event-listener callbacks, keyed by stable
/// numeric IDs so individual subscribers can dispose without
/// disturbing the others.
///
/// Each native event (state change, track change, progress, error,
/// queue end, cache status) gets its own registry. `onXxx(callback)`
/// registers, returns a closure that calls `remove(id)`.
final class ListenerRegistry<T> {
  private struct Entry {
    let id: Int64
    let callback: T
  }

  private let queue = DispatchQueue(label: "com.margelo.nitro.queueplayer.listenerRegistry")
  private var entries: [Entry] = []
  private var nextId: Int64 = 0

  /// Register a callback and return its stable ID for later removal.
  @discardableResult
  func add(_ callback: T) -> Int64 {
    var id: Int64 = 0
    queue.sync {
      nextId &+= 1
      id = nextId
      entries.append(Entry(id: id, callback: callback))
    }
    return id
  }

  /// Remove the callback with the given ID. Returns true if found.
  @discardableResult
  func remove(id: Int64) -> Bool {
    var found = false
    queue.sync {
      if let idx = entries.firstIndex(where: { $0.id == id }) {
        entries.remove(at: idx)
        found = true
      }
    }
    return found
  }

  /// Remove all registered callbacks.
  func clear() {
    queue.sync { entries.removeAll() }
  }

  /// Invoke `action` for every registered callback. Iteration is over
  /// a snapshot taken under the queue lock, so listeners adding/
  /// removing during a fire round don't corrupt the iteration.
  func forEach(_ action: (T) -> Void) {
    let snap = queue.sync { entries.map(\.callback) }
    snap.forEach(action)
  }

  /// True when no callbacks are registered. Handy for skipping
  /// per-tick work (e.g. progress observer body) when nothing's
  /// listening.
  var isEmpty: Bool {
    queue.sync { entries.isEmpty }
  }

  /// True when the given id is still registered. Used by deferred
  /// auto-fire paths (e.g. `onSkipCapabilityChange`) to skip the
  /// callback when the subscriber disposed between registration
  /// and the deferred fire — avoids calling into an unmounted React
  /// component's `setSnapshot`.
  func contains(id: Int64) -> Bool {
    queue.sync { entries.contains(where: { $0.id == id }) }
  }
}
