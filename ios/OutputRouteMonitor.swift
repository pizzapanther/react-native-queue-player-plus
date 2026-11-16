import AVFoundation
import Foundation

/// Classifies the active `AVAudioSession` route and notifies its
/// delegate when the route flips from "mixable" to "non-mixable".
///
/// "Non-mixable" routes are the ones that cannot reliably mix two
/// simultaneously-playing `AVPlayer` instances (per Apple Developer
/// Forum 105877 + 653606): AirPlay 2 and CarPlay. Bluetooth A2DP,
/// AirPlay 1, headphones, built-in speaker, USB-DAC, HDMI all
/// mix locally before the system's single-stream encode and are
/// safe to drive with two players.
///
/// Used by `CrossfadeEngine` to cancel any in-flight equal-power
/// fade the moment the user routes to a non-mixable destination
/// (otherwise iOS arbitrarily silences one of the two players,
/// producing a perceptible mid-fade audio jump). Per-boundary
/// fade-vs-hard-cut decisions read [isCurrentRouteMixable]
/// directly at track-boundary fire so the engine can degrade
/// per-transition instead of swapping engines on every route
/// change.
///
/// Threading: subscribes to `AVAudioSession.routeChangeNotification`
/// on the main run loop. Re-evaluates one runloop tick after each
/// notification because `currentRoute.outputs` lags the
/// notification by one runloop turn on iOS.
final class OutputRouteMonitor {

  /// Route categories the monitor exposes to consumers.
  enum OutputClass {
    /// Local routes (built-in speaker / headphones / Bluetooth
    /// A2DP / HDMI / USB-DAC / etc.) and AirPlay 1. The OS
    /// mixes two simultaneously-playing players locally before
    /// encoding the single output stream — dual-AVPlayer
    /// crossfade is safe.
    case localMixable
    /// AirPlay 2 + CarPlay. iOS forces single-stream routing
    /// once these engage and arbitrarily silences one of two
    /// concurrently-playing players. Engines must degrade to
    /// single-player playback for the duration.
    case nonMixableRemote
  }

  /// Latest classification. Read at engine track-boundary fire
  /// so per-transition fade-vs-cut decisions reflect the current
  /// route without depending on a callback delivery deadline.
  ///
  /// Written here on main as route changes arrive, read from the queue the
  /// engine serialises on, so it carries its own lock.
  var currentClass: OutputClass {
    classLock.lock()
    defer { classLock.unlock() }
    return storedClass
  }

  private let classLock = NSLock()
  private var storedClass: OutputClass = .localMixable

  /// Convenience accessor — `true` outside AirPlay 2 / CarPlay.
  var isCurrentRouteMixable: Bool {
    currentClass == .localMixable
  }

  /// Fired the moment the route flips into [OutputClass.nonMixableRemote]
  /// while a fade may be in flight. Engine wires this to its
  /// `cancelFade()` path. NOT fired when the route flips back to
  /// mixable — the next track-boundary picks up the new state
  /// via `isCurrentRouteMixable`.
  var onRouteBecameNonMixable: (() -> Void)?

  private var observer: NSObjectProtocol?

  /// Begin observing route changes. Idempotent — repeat calls
  /// drop the prior observer first. Synthetically seeds
  /// [currentClass] one runloop turn after registration so
  /// `currentRoute.outputs` is fully populated when
  /// [evaluate] reads it.
  func start() {
    stop()
    let center = NotificationCenter.default
    observer = center.addObserver(
      forName: AVAudioSession.routeChangeNotification,
      object: AVAudioSession.sharedInstance(),
      queue: .main
    ) { [weak self] _ in
      // Defer one runloop turn — `AVAudioSession.currentRoute.outputs`
      // can lag the notification by one turn, especially when
      // AirPlay engagement crosses the system's policy boundary.
      DispatchQueue.main.async { [weak self] in
        self?.evaluate()
      }
    }
    DispatchQueue.main.async { [weak self] in
      self?.evaluate()
    }
  }

  /// Stop observing. Safe to call when not started.
  func stop() {
    if let observer {
      NotificationCenter.default.removeObserver(observer)
    }
    observer = nil
  }

  deinit {
    if let observer {
      NotificationCenter.default.removeObserver(observer)
    }
  }

  /// Re-classify [currentClass] from the live route and fire the
  /// non-mixable callback if the classification flipped to
  /// non-mixable. Public for testing — production callers go
  /// through [start].
  func evaluate() {
    let outputs = AVAudioSession.sharedInstance().currentRoute.outputs
    let isNonMixable = outputs.contains { Self.isNonMixableOutput($0) }
    let newClass: OutputClass = isNonMixable ? .nonMixableRemote : .localMixable
    // One critical section: the flip test reads the value this write replaces.
    classLock.lock()
    let flipped = newClass == .nonMixableRemote && storedClass == .localMixable
    storedClass = newClass
    classLock.unlock()
    if flipped {
      onRouteBecameNonMixable?()
    }
  }

  /// Pure classifier — exposed `static` so unit tests can drive
  /// it with synthetic `AVAudioSessionPortDescription` arrays
  /// without standing up a real audio session.
  static func isNonMixableOutput(_ output: AVAudioSessionPortDescription) -> Bool {
    let portType = output.portType
    return portType == .airPlay
      || portType == .carAudio
  }
}
