import Foundation

/// Process-wide singleton owning the CarPlay collaborators that
/// outlive any one scene + any one Hybrid configure cycle:
/// the [CarPlayBrowseDataProvider] cache, the [CarPlayBridge] JS
/// callback registry, the [PendingBrowseRequests] race-handler queue,
/// and the [ArtworkLoader] image fetcher.
///
/// The coordinator itself owns nothing user-visible. Both ends of
/// the integration reach in:
///  - [TrackPlayer.swift] writes through `dataProvider.setSnapshot`
///    when the consumer calls `setBrowseSnapshot`, and registers JS
///    callbacks on `bridge` when the consumer calls
///    `onBrowseRequest` / `onPlayFromSearchRequest` / etc.
///  - [CarPlaySceneDelegate.swift] reads from `dataProvider` when
///    the scene attaches, observes
///    `CarPlayBrowseDataProvider.didUpdateNotification` to rebuild on
///    snapshot push, drains `pending` after a snapshot lands, and
///    dispatches into `bridge` callbacks when the controller asks
///    for children / play.
///
/// The singleton pattern mirrors `Equalizer.current` / `Visualizer.current`
/// used elsewhere in the lib — the JS-side singleton constraint that
/// arises naturally from the Nitro Hybrid lifecycle. CarPlay's scene
/// delegate has no constructor injection point, so a singleton is
/// the only way to hand it the same instances the Hybrid binds.
final class CarPlayCoordinator {

  static let shared = CarPlayCoordinator()

  let dataProvider: CarPlayBrowseDataProvider
  let queueProvider: CarPlayQueueProvider
  let bridge: CarPlayBridge
  let pending: PendingBrowseRequests
  let loader: ArtworkLoader

  private init() {
    self.dataProvider = CarPlayBrowseDataProvider()
    self.queueProvider = CarPlayQueueProvider()
    self.bridge = CarPlayBridge()
    self.pending = PendingBrowseRequests()
    self.loader = ArtworkLoader()
  }

  // MARK: - Queue skip

  /// Skip playback to a queue index. `TrackPlayer` registers this at
  /// configure and clears it at destroy, on its player queue; the CarPlay
  /// scene delegate invokes it on main when the user taps a row in the Up Next
  /// list, so it is lock-guarded.
  var skipToIndexHandler: ((Int) -> Void)? {
    get { connectionLock.withLock { skipToIndexCallback } }
    set { connectionLock.withLock { skipToIndexCallback = newValue } }
  }
  private var skipToIndexCallback: ((Int) -> Void)?

  // MARK: - Connection state

  /// Guards `carConnectedFlag`, `carAttachedCallback`, `skipToIndexCallback`
  /// and `playerStateReaderCallback`.
  private let connectionLock = NSLock()
  /// Whether the CarPlay scene is currently connected. Written by
  /// [CarPlaySceneDelegate] at scene attach / detach — the same points that
  /// fire the `onCarConnect` / `onCarDisconnect` callbacks — and read by the
  /// `isCarConnected()` Hybrid getter. Scene callbacks land on the main
  /// queue; the getter can be read from any Nitro thread, so the flag is
  /// lock-guarded.
  ///
  /// A media app has a single CarPlay template scene, so this is a plain
  /// attached/detached flag rather than a connection count (the Android side
  /// counts because its headless controllers — Auto / Wear / Assistant — can
  /// overlap).
  private var carConnectedFlag = false

  var isCarConnected: Bool {
    connectionLock.lock()
    defer { connectionLock.unlock() }
    return carConnectedFlag
  }

  /// Invoked on the main queue when a scene attaches. `TrackPlayer` uses it
  /// to rebuild the queue mirror, which is only maintained while a car is
  /// attached — without this a car connecting mid-session would show whatever
  /// the mirror held when it was last connected.
  var onCarAttached: (() -> Void)? {
    get { connectionLock.withLock { carAttachedCallback } }
    set { connectionLock.withLock { carAttachedCallback = newValue } }
  }
  /// Written by `TrackPlayer` on its player queue, read here on main.
  private var carAttachedCallback: (() -> Void)?

  /// Hands the player's current state to its argument, on main. `TrackPlayer`
  /// registers it at configure and clears it at destroy; nil before the first
  /// configure. Asynchronous because the state lives on the player queue,
  /// which main must never wait on.
  var playerStateReader: ((@escaping (PlayerState) -> Void) -> Void)? {
    get { connectionLock.withLock { playerStateReaderCallback } }
    set { connectionLock.withLock { playerStateReaderCallback = newValue } }
  }
  private var playerStateReaderCallback: ((@escaping (PlayerState) -> Void) -> Void)?

  /// Whether CarPlay opens on Now Playing when it connects: only while audio
  /// is playing. A buffering, loading, paused or idle player opens on the
  /// browse lists.
  static func opensNowPlayingAtConnect(state: PlayerState) -> Bool {
    state == .playing
  }

  func setCarConnected(_ connected: Bool) {
    connectionLock.lock()
    let wasConnected = carConnectedFlag
    carConnectedFlag = connected
    let attached = carAttachedCallback
    connectionLock.unlock()
    if connected, !wasConnected {
      attached?()
    }
  }
}
