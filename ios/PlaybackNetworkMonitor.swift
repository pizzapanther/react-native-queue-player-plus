import Foundation
import Network

/// Reports the moment the network comes back after an outage.
///
/// A player that failed its retries while the device was offline has nothing
/// to react to when connectivity returns: the item stays failed and playback
/// stays stopped until the user notices and presses play. This is the signal
/// that lets recovery run at the point it can actually succeed.
///
/// Only the **down → up** edge is reported. A satisfied → satisfied update —
/// a Wi-Fi to cellular handoff, an interface change — is not an outage
/// recovery, and treating it as one would retry against a connection that
/// never dropped.
///
/// One-shot: `start()` arms the monitor once, and `stop()` after it is
/// terminal — a later `start()` does nothing. `NWPathMonitor` does not define
/// `start` after `cancel`, and the player never needs it: `configure()`
/// creates a fresh monitor and destroy stops it.
///
/// `NWPathMonitor` delivers the current path once on `start` and then on
/// every change, so a monitor started while online reports that first
/// delivery as a restore (the seed below is unsatisfied). Downstream that is
/// harmless — recovery only runs for an item that has already failed.
final class PlaybackNetworkMonitor {

  /// Fired on the down → up transition, on `queue`.
  var onNetworkRestored: (() -> Void)?

  private let monitor = NWPathMonitor()
  private let queue: DispatchQueue
  /// Seeded unsatisfied so a monitor that starts while offline reports the
  /// first success as a recovery rather than swallowing it as "no change".
  private var lastStatus: NWPath.Status = .unsatisfied
  private var started = false
  /// Set by `stop()` after a `start()`; nothing clears it.
  private var stopped = false

  /// - Parameter queue: the queue callbacks are delivered on. Callers pass the
  ///   queue that serialises player state, so the handler needs no hop.
  init(queue: DispatchQueue) {
    self.queue = queue
  }

  func start() {
    guard !started, !stopped else { return }
    started = true
    monitor.pathUpdateHandler = { [weak self] path in
      guard let self else { return }
      let previous = self.lastStatus
      self.lastStatus = path.status
      guard previous != .satisfied, path.status == .satisfied else { return }
      self.onNetworkRestored?()
    }
    monitor.start(queue: queue)
  }

  /// Terminal once the monitor has started; a `stop()` before any `start()`
  /// changes nothing.
  func stop() {
    guard started else { return }
    started = false
    stopped = true
    monitor.pathUpdateHandler = nil
    monitor.cancel()
  }

  /// A monitor dropped without `stop()` — `configure()` replaced it, or the
  /// player was released — must not keep its handler queue alive.
  deinit {
    monitor.cancel()
  }
}
