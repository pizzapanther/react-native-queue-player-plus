import Foundation
import NitroModules

/// Single-slot registry for the JS-side automotive callbacks the
/// CarPlay scene delegate dispatches into. Each slot holds at most
/// one callback at a time — re-registering replaces the prior
/// callback, matching the JS-side `registerPlaybackService` factory
/// semantics where the latest call wins.
///
/// iOS mirror of the Android `BrowseCallbackRegistry`. Disposers
/// returned from `register*` clear the slot iff the registered
/// callback is still the active one (idempotent + safe against late
/// dispose-after-replace via reference identity).
///
/// Threading: every slot is guarded by the registry's `NSLock`.
/// CarPlay scene callbacks fire on the main queue; consumer JS-side
/// `register*` calls land on whatever Nitro thread the Hybrid
/// dispatch resolves on. Both are safe.
final class CarPlayBridge {

  // Browse-tree drilldown — invoked when CarPlayBrowseBuilder asks for
  // children of a parent that isn't in the cache. The Nitro Promise
  // is the outer Hybrid wrapper; the inner Promise is the JS-side
  // resolution. Both are awaited on the dispatch path.
  typealias OnBrowseRequest = (String) -> Promise<Promise<[BrowseItem]>>

  // "Play from media id" — invoked when CarPlay's CPListItem tap
  // resolves a browse-leaf to playback.
  typealias OnPlayFromIdRequest = (String) -> Promise<Promise<[TrackItem]>>

  // "Play from search" — invoked by the lib's SiriKit
  // `PlayMediaIntentHandler` when "Hey Siri play X on MyApp"
  // resolves into a structured `MediaSearchRequest`. Same Promise
  // shape as the browse + id paths.
  typealias OnPlayFromSearchRequest = (MediaSearchRequest) -> Promise<Promise<[TrackItem]>>

  // Connection lifecycle — fired on scene attach / detach.
  typealias CarConnectCallback = () -> Void
  typealias CarDisconnectCallback = () -> Void

  // MARK: - Registration

  func registerOnBrowseRequest(_ callback: @escaping OnBrowseRequest) -> () -> Void {
    register(\.onBrowseRequestSlot, callback: callback)
  }

  func registerOnPlayFromIdRequest(
    _ callback: @escaping OnPlayFromIdRequest
  ) -> () -> Void {
    register(\.onPlayFromIdRequestSlot, callback: callback)
  }

  func registerOnPlayFromSearchRequest(
    _ callback: @escaping OnPlayFromSearchRequest
  ) -> () -> Void {
    let disposer = register(\.onPlayFromSearchRequestSlot, callback: callback)
    // Drain any cold-start INPlayMediaIntent that arrived before the
    // JS bridge booted; the freshly-registered resolver picks it up
    // immediately. Single-slot buffer with the lib's standard 20 s
    // TTL — stale intents drop silently.
    if let buffered = pendingSearchBuffer.drain() {
      _ = callback(buffered)
    }
    return disposer
  }

  /// Push a cold-start `INPlayMediaIntent`-derived request into the
  /// pending buffer when no JS callback is registered yet. The next
  /// `registerOnPlayFromSearchRequest` call drains the buffer in its
  /// own body. Caller is the lib's `PlayMediaIntentHandler`.
  func bufferPlayFromSearch(_ request: MediaSearchRequest) {
    pendingSearchBuffer.push(request)
  }

  func registerOnCarConnect(_ callback: @escaping CarConnectCallback) -> () -> Void {
    register(\.onCarConnectSlot, callback: callback)
  }

  func registerOnCarDisconnect(
    _ callback: @escaping CarDisconnectCallback
  ) -> () -> Void {
    register(\.onCarDisconnectSlot, callback: callback)
  }

  // MARK: - Dispatch (slot reads)

  func browseRequestCallback() -> OnBrowseRequest? {
    lock.withLock { onBrowseRequestSlot.callback }
  }

  func playFromIdRequestCallback() -> OnPlayFromIdRequest? {
    lock.withLock { onPlayFromIdRequestSlot.callback }
  }

  func playFromSearchRequestCallback() -> OnPlayFromSearchRequest? {
    lock.withLock { onPlayFromSearchRequestSlot.callback }
  }

  func carConnectCallback() -> CarConnectCallback? {
    lock.withLock { onCarConnectSlot.callback }
  }

  func carDisconnectCallback() -> CarDisconnectCallback? {
    lock.withLock { onCarDisconnectSlot.callback }
  }

  /// Drop every registered callback. Called from
  /// `templateApplicationScene(_:didDisconnectInterfaceController:)` when
  /// the scene tears down.
  func clear() {
    lock.withLock {
      onBrowseRequestSlot = .empty
      onPlayFromIdRequestSlot = .empty
      onPlayFromSearchRequestSlot = .empty
      onCarConnectSlot = .empty
      onCarDisconnectSlot = .empty
    }
  }

  // MARK: - Storage

  /// Box that pairs a callback with a unique ObjectIdentifier-style
  /// token so the disposer can clear the slot iff its registration is
  /// still the active one.
  private struct Slot<T> {
    var token: Int64
    var callback: T?

    static var empty: Slot<T> { Slot(token: 0, callback: nil) }
  }

  private var onBrowseRequestSlot: Slot<OnBrowseRequest> = .empty
  private var onPlayFromIdRequestSlot: Slot<OnPlayFromIdRequest> = .empty
  private var onPlayFromSearchRequestSlot: Slot<OnPlayFromSearchRequest> = .empty
  private var onCarConnectSlot: Slot<CarConnectCallback> = .empty
  private var onCarDisconnectSlot: Slot<CarDisconnectCallback> = .empty

  /// Cold-start buffer for `INPlayMediaIntent` dispatches that arrive
  /// before the JS bridge has called `registerOnPlayFromSearchRequest`.
  /// Single-slot, latest-wins, 20 s TTL — the standard
  /// `PendingIntentBuffer` shape used across the lib.
  private let pendingSearchBuffer = PendingIntentBuffer<MediaSearchRequest>()
  private var nextToken: Int64 = 0
  private let lock = NSLock()

  private func register<T>(
    _ keyPath: ReferenceWritableKeyPath<CarPlayBridge, Slot<T>>,
    callback: T
  ) -> () -> Void {
    let token: Int64 = lock.withLock {
      nextToken &+= 1
      let t = nextToken
      self[keyPath: keyPath] = Slot(token: t, callback: callback)
      return t
    }
    return { [weak self] in
      guard let self = self else { return }
      self.lock.withLock {
        if self[keyPath: keyPath].token == token {
          self[keyPath: keyPath] = .empty
        }
      }
    }
  }
}

