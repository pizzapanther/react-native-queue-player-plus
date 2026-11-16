import Foundation
#if canImport(GoogleCast)
import GoogleCast

/// Live Chromecast session wrapping a `GCKCastSession` handed over by
/// the Cast SDK. Implements the full `CastSessionRemotePlayer`
/// contract:
///
/// - `load` translates each `CastMediaItem` into a `GCKMediaInformation`
///   via `MediaInfoBuilder` and calls `GCKRemoteMediaClient.loadMedia`
///   with a `GCKMediaLoadRequestData` populated from a
///   `GCKMediaQueueDataBuilder`. `file://` URLs are served via
///   `LocalMediaServer`; remote URLs pass through unchanged.
/// - `play` / `pause` / `stop` / transport mutations fire-and-forget
///   to `GCKRemoteMediaClient`; queue mutations await SDK request
///   completion via a `GCKRequestDelegate` bridge.
/// - State + position + queue updates flow back via
///   `RemoteMediaObserver`, which translates `GCKMediaStatus` deltas
///   into the typed callback surface this class fans out to the
///   add*Listener registries.
///
/// Threading: the Cast SDK's session manager and `GCKRemoteMediaClient`
/// are all main-thread-bound. Every transport call hops to main via
/// `runOnMain(_:)`. Listener registration and snapshot reads are
/// lock-guarded so concurrent JS-side subscribers don't race.
final class ChromecastSession: NSObject, CastSessionRemotePlayer {

  // MARK: - CastSession

  let isPushSink: Bool = false

  // MARK: - Snapshot state (single writer = main thread; readers any)

  private let stateLock = NSLock()
  private var _lastState: RemotePlaybackStateKind = .idle
  private var _lastPositionMs: Int64 = 0
  private var _lastDurationMs: Int64 = 0
  private var _lastBufferedMs: Int64 = 0
  private var _lastCurrentTrackIndex: Int = -1
  private var _lastVolume: Float = .nan
  private var _lastVolumeEmitted: Float = .nan

  private var _hasReportedState = false

  var lastState: RemotePlaybackStateKind {
    stateLock.lock(); defer { stateLock.unlock() }
    return _lastState
  }
  var hasReportedState: Bool {
    stateLock.lock(); defer { stateLock.unlock() }
    return _hasReportedState
  }
  var lastPositionMs: Int64 {
    stateLock.lock(); defer { stateLock.unlock() }
    return _lastPositionMs
  }
  var lastDurationMs: Int64 {
    stateLock.lock(); defer { stateLock.unlock() }
    return _lastDurationMs
  }
  var lastBufferedMs: Int64 {
    stateLock.lock(); defer { stateLock.unlock() }
    return _lastBufferedMs
  }
  var lastCurrentTrackIndex: Int {
    stateLock.lock(); defer { stateLock.unlock() }
    return _lastCurrentTrackIndex
  }
  var lastVolume: Float {
    stateLock.lock(); defer { stateLock.unlock() }
    return _lastVolume
  }

  // MARK: - Listener registries

  private let listenerLock = NSLock()
  private var nextListenerId: Int64 = 0
  private var stateListeners: [Int64: (RemotePlaybackStateKind) -> Void] = [:]
  private var positionListeners: [Int64: (Int64, Int64, Int64) -> Void] = [:]
  private var currentTrackIndexListeners: [Int64: (Int) -> Void] = [:]
  private var endedReasonListeners: [Int64: (RemoteEndReasonKind) -> Void] = [:]
  private var mediaErrorListeners: [Int64: (RemoteMediaErrorPayload) -> Void] = [:]
  private var seekCompletedListeners: [Int64: (Int64) -> Void] = [:]
  private var volumeListeners: [Int64: (Float) -> Void] = [:]

  // MARK: - Internal state

  private let sdkSession: GCKCastSession
  private let mediaObserver = RemoteMediaObserver()
  private var closed: Bool = false
  private var serverHandle: MediaServerHandle?
  private let sessionIdentifier: String
  private let pendingDelegatesLock = NSLock()
  /// Keyed retention for `GCKRequest.delegate` instances. `GCKRequest`
  /// holds its delegate weakly, so a continuation wrapper would
  /// deallocate before any completion callback fires unless something
  /// else retains it. The session-scoped registry keeps each delegate
  /// alive until its own terminal callback removes it.
  private var pendingRequestDelegates: [ObjectIdentifier: NSObject] = [:]

  private static let receiverQueueLimit = 100

  // MARK: - Init / lifecycle

  init(session: GCKCastSession) {
    self.sdkSession = session
    self.sessionIdentifier = session.sessionID ?? "chromecast-\(UUID().uuidString)"
    super.init()
    wireObserver()
    runOnMain { [weak self] in
      guard let self = self, let client = session.remoteMediaClient else { return }
      self.mediaObserver.attach(client)
    }
  }

  private func wireObserver() {
    mediaObserver.onStateChanged = { [weak self] s in self?.updateState(s) }
    mediaObserver.onPositionChanged = { [weak self] p, d, b in
      self?.updatePosition(positionMs: p, durationMs: d, bufferedMs: b)
    }
    mediaObserver.onCurrentTrackIndexChanged = { [weak self] index in
      self?.updateCurrentTrackIndex(index)
    }
    mediaObserver.onEndedReason = { [weak self] r in self?.fireEndedReason(r) }
    mediaObserver.onMediaError = { [weak self] e in self?.fireMediaError(e) }
    mediaObserver.onSeekCompleted = { [weak self] ms in self?.fireSeekCompleted(ms) }
  }

  /// Receiver itemIds for the items currently in the status window, in
  /// order, returned from `load` as the request's success signal. Empty
  /// when the receiver's `GCKMediaStatus` isn't available yet (first load
  /// hasn't landed, or the session dropped mid-read). Current-track
  /// resolution + queue mutations use `GCKMediaQueue` absolute indices,
  /// not these window-scoped ids.
  private func readQueueItemIds() -> [Int] {
    guard let client = sdkSession.remoteMediaClient,
          let status = client.mediaStatus
    else { return [] }
    var ids: [Int] = []
    for i in 0..<status.queueItemCount {
      if let item = status.queueItem(at: i) {
        ids.append(Int(item.itemID))
      }
    }
    return ids
  }

  // MARK: - State update sinks

  private func updateState(_ s: RemotePlaybackStateKind) {
    stateLock.lock()
    let previous = _lastState
    _lastState = s
    if s != .idle { _hasReportedState = true }
    stateLock.unlock()
    #if DEBUG
    if previous != s {
      NSLog("[ChromecastSession] receiver state \(previous) -> \(s)")
    }
    #endif
    fireOnEach(stateListeners) { $0(s) }
  }

  private func updatePosition(positionMs p: Int64, durationMs d: Int64, bufferedMs b: Int64) {
    stateLock.lock()
    _lastPositionMs = p
    _lastDurationMs = d
    _lastBufferedMs = b
    stateLock.unlock()
    fireOnEach(positionListeners) { $0(p, d, b) }
  }

  private func updateCurrentTrackIndex(_ index: Int) {
    stateLock.lock()
    _lastCurrentTrackIndex = index
    stateLock.unlock()
    fireOnEach(currentTrackIndexListeners) { $0(index) }
  }

  private func updateVolume(_ v: Float) {
    stateLock.lock()
    _lastVolume = v
    if _lastVolumeEmitted.bitPattern == v.bitPattern {
      stateLock.unlock()
      return
    }
    _lastVolumeEmitted = v
    stateLock.unlock()
    fireOnEach(volumeListeners) { $0(v) }
  }

  /// Inject a volume reading from an external source — the
  /// `GCKSessionManagerListener.didReceiveDeviceVolume` callback, fired
  /// even when no media has loaded yet. Caller is `CastManager`; same
  /// dedup as the `MediaStatus`-driven path.
  func acceptDeviceVolume(_ value: Float) {
    updateVolume(value)
  }

  private func fireEndedReason(_ r: RemoteEndReasonKind) {
    fireOnEach(endedReasonListeners) { $0(r) }
  }

  private func fireMediaError(_ e: RemoteMediaErrorPayload) {
    fireOnEach(mediaErrorListeners) { $0(e) }
  }

  fileprivate func fireSeekCompleted(_ positionMs: Int64) {
    fireOnEach(seekCompletedListeners) { $0(positionMs) }
  }

  private func fireOnEach<T>(_ registry: [Int64: T], invoke: (T) -> Void) {
    listenerLock.lock()
    let snapshot = Array(registry.values)
    listenerLock.unlock()
    for callback in snapshot { invoke(callback) }
  }

  // MARK: - Add-listener API

  func addStateListener(_ cb: @escaping (RemotePlaybackStateKind) -> Void) -> () -> Void {
    let id = nextId()
    listenerLock.lock()
    stateListeners[id] = cb
    listenerLock.unlock()
    return { [weak self] in self?.removeListener(stateKey: id) }
  }

  func addPositionListener(_ cb: @escaping (Int64, Int64, Int64) -> Void) -> () -> Void {
    let id = nextId()
    listenerLock.lock()
    positionListeners[id] = cb
    listenerLock.unlock()
    return { [weak self] in self?.removeListener(positionKey: id) }
  }

  func addCurrentTrackIndexListener(_ cb: @escaping (Int) -> Void) -> () -> Void {
    let id = nextId()
    listenerLock.lock()
    currentTrackIndexListeners[id] = cb
    listenerLock.unlock()
    return { [weak self] in self?.removeListener(currentTrackIndexKey: id) }
  }

  func addEndedReasonListener(_ cb: @escaping (RemoteEndReasonKind) -> Void) -> () -> Void {
    let id = nextId()
    listenerLock.lock()
    endedReasonListeners[id] = cb
    listenerLock.unlock()
    return { [weak self] in self?.removeListener(endedReasonKey: id) }
  }

  func addMediaErrorListener(_ cb: @escaping (RemoteMediaErrorPayload) -> Void) -> () -> Void {
    let id = nextId()
    listenerLock.lock()
    mediaErrorListeners[id] = cb
    listenerLock.unlock()
    return { [weak self] in self?.removeListener(mediaErrorKey: id) }
  }

  func addSeekCompletedListener(_ cb: @escaping (Int64) -> Void) -> () -> Void {
    let id = nextId()
    listenerLock.lock()
    seekCompletedListeners[id] = cb
    listenerLock.unlock()
    return { [weak self] in self?.removeListener(seekCompletedKey: id) }
  }

  func addVolumeListener(_ cb: @escaping (Float) -> Void) -> () -> Void {
    let id = nextId()
    listenerLock.lock()
    volumeListeners[id] = cb
    listenerLock.unlock()
    return { [weak self] in self?.removeListener(volumeKey: id) }
  }

  private func nextId() -> Int64 {
    listenerLock.lock()
    nextListenerId += 1
    let id = nextListenerId
    listenerLock.unlock()
    return id
  }

  private func removeListener(stateKey id: Int64) {
    listenerLock.lock(); stateListeners.removeValue(forKey: id); listenerLock.unlock()
  }
  private func removeListener(positionKey id: Int64) {
    listenerLock.lock(); positionListeners.removeValue(forKey: id); listenerLock.unlock()
  }
  private func removeListener(currentTrackIndexKey id: Int64) {
    listenerLock.lock(); currentTrackIndexListeners.removeValue(forKey: id); listenerLock.unlock()
  }
  private func removeListener(endedReasonKey id: Int64) {
    listenerLock.lock(); endedReasonListeners.removeValue(forKey: id); listenerLock.unlock()
  }
  private func removeListener(mediaErrorKey id: Int64) {
    listenerLock.lock(); mediaErrorListeners.removeValue(forKey: id); listenerLock.unlock()
  }
  private func removeListener(seekCompletedKey id: Int64) {
    listenerLock.lock(); seekCompletedListeners.removeValue(forKey: id); listenerLock.unlock()
  }
  private func removeListener(volumeKey id: Int64) {
    listenerLock.lock(); volumeListeners.removeValue(forKey: id); listenerLock.unlock()
  }

  // MARK: - Transport (fire-and-forget)

  func setVolume(_ value: Float) {
    guard !closed else { return }
    let clamped = max(0.0, min(1.0, value))
    runOnMain { [weak self] in
      guard let self = self, !self.closed else { return }
      self.sdkSession.setDeviceVolume(clamped)
    }
  }

  /// Called on main when this session finds the local media server not
  /// answering; the backend ends the session, since the receiver's URLs name
  /// a dead port.
  var onMediaServerNotAnswering: (() -> Void)?

  /// Main only. Whether this session holds a media server handle. It stores one
  /// once `acquire` returns; the listener may still be unbound or not answering.
  var holdsMediaServer: Bool { serverHandle != nil }

  /// Main only. Incremented by each pause, stop or queue load as it is sent: the
  /// commands a play issued before them must not land after. A play waiting on
  /// the media server check drops itself when this has moved.
  private var transportGeneration = 0

  /// Checks the local media server first while this session holds it: a play
  /// after the app sat suspended may find its listener reclaimed. A session
  /// that loaded nothing holds no server, and plays straight away.
  func play() {
    runOnMain { [weak self] in self?.playAfterServerCheck() }
  }

  // No bitmask flag gates `play` — the mask models pause, not resume.
  private func playAfterServerCheck() {
    guard holdsMediaServer else {
      fireRemote("play") { $0.play() }
      return
    }
    let issuedAt = transportGeneration
    LocalMediaServer.shared.checkAnswering { [weak self] answering in
      guard let self, !self.closed else { return }
      NSLog("[ChromecastSession] play: media server answering=\(answering ? "yes" : "no")")
      guard answering else {
        self.onMediaServerNotAnswering?()
        return
      }
      // A pause, stop or load sent while the check ran was issued after this
      // play; sending the play now would reverse them.
      guard self.transportGeneration == issuedAt else {
        NSLog("[ChromecastSession] play: dropped, a later pause, stop or load was sent during the check")
        return
      }
      self.fireRemote("play") { $0.play() }
    }
  }
  func pause() {
    fireRemote("pause", requires: kGCKMediaCommandPause, supersedesPlay: true) { $0.pause() }
  }
  func stop() { fireRemote("stop", supersedesPlay: true) { $0.stop() } }
  func queueNext() { fireRemote("queueNext", requires: kGCKMediaCommandQueueNext) { $0.queueNextItem() } }
  func queuePrev() {
    fireRemote("queuePrev", requires: kGCKMediaCommandQueuePrevious) { $0.queuePreviousItem() }
  }
  func skipToQueueIndex(index: Int) {
    guard index >= 0 else { return }
    // Resolve the absolute lib index to the receiver's itemId via the full
    // queue (`GCKMediaQueue`) — the same source of truth used to read the
    // current index back. `kGCKMediaQueueInvalidItemID` means the queue
    // model hasn't synced that slot yet; skip rather than jump blind.
    fireRemote("skipToQueueIndex") { client in
      let itemId = client.mediaQueue.itemID(at: UInt(index))
      guard itemId != kGCKMediaQueueInvalidItemID else { return nil }
      return client.queueJumpToItem(withID: itemId)
    }
  }

  func setPlaybackRate(_ rate: Float) {
    fireRemote("setPlaybackRate", requires: kGCKMediaCommandSetPlaybackRate) {
      $0.setPlaybackRate(rate)
    }
  }

  func setRepeatMode(_ mode: RemoteRepeatMode) {
    let sdkMode: GCKMediaRepeatMode
    switch mode {
    case .off: sdkMode = .off
    case .single: sdkMode = .single
    case .all: sdkMode = .all
    }
    fireRemote("setRepeatMode") { $0.queueSetRepeatMode(sdkMode) }
  }

  func seekTo(positionMs: Int64) {
    guard !closed else { return }
    runOnMain { [weak self] in
      guard let self = self, !self.closed else { return }
      guard let client = self.sdkSession.remoteMediaClient else {
        NSLog("[ChromecastSession] seekTo: no GCKRemoteMediaClient — session not fully live?")
        return
      }
      let opts = GCKMediaSeekOptions()
      opts.interval = TimeInterval(positionMs) / 1000.0
      opts.resumeState = .unchanged
      let request = client.seek(with: opts)
      let delegate = SeekCompletionDelegate(positionMs: positionMs, owner: self)
      self.retain(delegate)
      request.delegate = delegate
    }
  }

  func seekBy(deltaMs: Int64) {
    guard !closed else { return }
    runOnMain { [weak self] in
      guard let self = self, !self.closed else { return }
      guard let client = self.sdkSession.remoteMediaClient else {
        NSLog("[ChromecastSession] seekBy: no GCKRemoteMediaClient — session not fully live?")
        return
      }
      // Native relative seek: the receiver applies `interval` seconds to its
      // own current position. `relative = true` is what distinguishes this
      // from the absolute `seekTo` above.
      let opts = GCKMediaSeekOptions()
      opts.interval = TimeInterval(deltaMs) / 1000.0
      opts.relative = true
      opts.resumeState = .unchanged
      let request = client.seek(with: opts)
      // Report the resolved target for the completion listener: current
      // receiver position + delta, clamped to [0, duration].
      var target = self.lastPositionMs + deltaMs
      if target < 0 { target = 0 }
      if self.lastDurationMs > 0 && target > self.lastDurationMs { target = self.lastDurationMs }
      let delegate = SeekCompletionDelegate(positionMs: target, owner: self)
      self.retain(delegate)
      request.delegate = delegate
    }
  }

  // MARK: - Queue mutations (suspending)

  func load(
    items: [CastMediaItem],
    startIndex: Int,
    startPositionMs: Int64,
    playWhenReady: Bool
  ) async throws -> [Int] {
    if closed { throw SessionError.closed }
    guard !items.isEmpty else { throw SessionError.emptyItems }

    let truncated: [CastMediaItem]
    if items.count > Self.receiverQueueLimit {
      NSLog(
        "[ChromecastSession] load: queue size \(items.count) exceeds receiver cap "
        + "\(Self.receiverQueueLimit) — truncating"
      )
      let err = RemoteMediaErrorPayload(
        code: -1,
        message: "Queue size \(items.count) exceeds Chromecast receiver cap of "
          + "\(Self.receiverQueueLimit) items. Truncated to first \(Self.receiverQueueLimit)."
      )
      fireMediaError(err)
      truncated = Array(items.prefix(Self.receiverQueueLimit))
    } else {
      truncated = items
    }

    let clampedStart = max(0, min(startIndex, truncated.count - 1))

    let server = LocalMediaServer.shared
    let handle = try await ensureServerHandle()
    let lanAddress = server.currentLanAddress

    let queueItems = try truncated.map { item -> GCKMediaQueueItem in
      let mediaInfo = try MediaInfoBuilder.build(
        item: item,
        handle: handle,
        lanAddress: lanAddress
      )
      let builder = GCKMediaQueueItemBuilder()
      builder.mediaInformation = mediaInfo
      builder.autoplay = true
      return builder.build()
    }

    updateState(.loading)

    let startTimeS = TimeInterval(startPositionMs) / 1000.0
    let queueData = GCKMediaQueueDataBuilder(queueType: .generic)
    queueData.items = queueItems
    queueData.startIndex = UInt(clampedStart)
    queueData.startTime = startTimeS

    let loadRequest = GCKMediaLoadRequestDataBuilder()
    loadRequest.mediaInformation = queueItems[clampedStart].mediaInformation
    loadRequest.queueData = queueData.build()
    // Per-item autoplay stays true (auto-advance once playing); the
    // initial request honours the caller's play/pause so a handoff from
    // a paused local session starts the receiver paused at startTime.
    loadRequest.autoplay = NSNumber(value: playWhenReady)
    loadRequest.startTime = startTimeS

    do {
      try await awaitRequest(label: "loadMedia", supersedesPlay: true) { [sdkSession] in
        sdkSession.remoteMediaClient?.loadMedia(with: loadRequest.build())
      }
    } catch {
      updateState(.error)
      throw error
    }
    return readQueueItemIds()
  }

  func queueInsert(items: [CastMediaItem], beforeIndex: Int?) async throws {
    if closed { throw SessionError.closed }
    if items.isEmpty { return }

    let server = LocalMediaServer.shared
    let handle = try await ensureServerHandle()
    let lanAddress = server.currentLanAddress

    let queueItems = try items.map { item -> GCKMediaQueueItem in
      let mediaInfo = try MediaInfoBuilder.build(
        item: item, handle: handle, lanAddress: lanAddress
      )
      let builder = GCKMediaQueueItemBuilder()
      builder.mediaInformation = mediaInfo
      builder.autoplay = true
      return builder.build()
    }
    // Resolve the anchor index to a receiver itemId against the full queue
    // (`GCKMediaQueue`). `nil` / out-of-range appends.
    try await awaitRequest(label: "queueInsert") { [sdkSession] in
      guard let client = sdkSession.remoteMediaClient else { return nil }
      let before = Self.receiverItemID(client, atIndex: beforeIndex)
      return client.queueInsert(queueItems, beforeItemWithID: before)
    }
  }

  func queueRemove(indices: [Int]) async throws {
    if closed { throw SessionError.closed }
    if indices.isEmpty { return }
    // Resolve all indices to stable itemIds first, then remove by id — so
    // a multi-remove isn't affected by index shifting on the receiver.
    try await awaitRequest(label: "queueRemove") { [sdkSession] in
      guard let client = sdkSession.remoteMediaClient else { return nil }
      let ids = indices.compactMap { idx -> NSNumber? in
        let id = client.mediaQueue.itemID(at: UInt(max(0, idx)))
        return id == kGCKMediaQueueInvalidItemID ? nil : NSNumber(value: id)
      }
      guard !ids.isEmpty else { return nil }
      return client.queueRemoveItems(withIDs: ids)
    }
  }

  func queueMove(fromIndex: Int, toIndex: Int) async throws {
    if closed { throw SessionError.closed }
    guard fromIndex >= 0, toIndex >= 0, fromIndex != toIndex else { return }
    try await awaitRequest(label: "queueMove") { [sdkSession] in
      guard let client = sdkSession.remoteMediaClient else { return nil }
      let fromId = client.mediaQueue.itemID(at: UInt(fromIndex))
      guard fromId != kGCKMediaQueueInvalidItemID else { return nil }
      // Array-move semantics: to land the item at `toIndex`, insert it
      // before the item that currently sits just past the target slot —
      // one slot later when moving down, the target slot when moving up.
      let anchorIndex = toIndex > fromIndex ? toIndex + 1 : toIndex
      let before = Self.receiverItemID(client, atIndex: anchorIndex)
      return client.queueReorderItems(
        withIDs: [NSNumber(value: fromId)], insertBeforeItemWithID: before
      )
    }
  }

  func queueClear() async throws {
    if closed { throw SessionError.closed }
    try await awaitRequest(label: "queueClear") { [sdkSession] in
      guard let client = sdkSession.remoteMediaClient else { return nil }
      let count = client.mediaQueue.itemCount
      guard count > 0 else { return nil }
      var ids: [NSNumber] = []
      for i in 0..<count {
        let id = client.mediaQueue.itemID(at: i)
        if id != kGCKMediaQueueInvalidItemID { ids.append(NSNumber(value: id)) }
      }
      guard !ids.isEmpty else { return nil }
      return client.queueRemoveItems(withIDs: ids)
    }
  }

  /// Receiver itemId for the queue item at absolute `index`, resolved via
  /// the full `GCKMediaQueue`. Returns `kGCKMediaQueueInvalidItemID` (the
  /// "no anchor" / append sentinel) for `nil` or out-of-range indices.
  private static func receiverItemID(
    _ client: GCKRemoteMediaClient, atIndex index: Int?
  ) -> GCKMediaQueueItemID {
    guard let index = index, index >= 0, index < client.mediaQueue.itemCount else {
      return kGCKMediaQueueInvalidItemID
    }
    return client.mediaQueue.itemID(at: UInt(index))
  }

  // MARK: - Close

  func close() {
    if closed { return }
    closed = true
    // Resume any in-flight `awaitRequest` continuations so awaiting
    // Tasks unblock. Without this, a queue-mutation call that was
    // mid-flight when the SDK tore down the session would hang on
    // its continuation forever.
    pendingDelegatesLock.lock()
    let pending = pendingRequestDelegates
    pendingRequestDelegates.removeAll()
    pendingDelegatesLock.unlock()
    for case let cont as RequestContinuation in pending.values {
      cont.cont.resume(throwing: SessionError.closed)
    }
    runOnMain { [weak self] in
      guard let self = self else { return }
      self.mediaObserver.detach()
      self.serverHandle?.close()
      self.serverHandle = nil
    }
  }

  // MARK: - Helpers

  enum SessionError: Error {
    case closed
    /// The local media server stopped answering; the session is being ended.
    case mediaServerNotAnswering
    case emptyItems
    case requestStartFailed(String)
  }

  /// Post `block` to main and invoke it with the current
  /// `GCKRemoteMediaClient`. Silently skips when the session is closed
  /// or the client is unavailable (log only — transport calls are
  /// fire-and-forget).
  ///
  /// `block` returns the `GCKRequest` the SDK scheduled, or `nil` when it
  /// declined to issue one. A returned request gets a delegate so a
  /// receiver-side rejection is reported instead of vanishing — without it
  /// a refused command is indistinguishable from one the receiver honoured.
  ///
  /// `requires` names the `GCKMediaStatus` command bitmask flag the receiver
  /// must advertise for this command (`nil` for commands not gated by the
  /// mask). The receiver publishes support per media item, so an item can
  /// accept queue commands while refusing `pause` — worth a warning, since
  /// the command is otherwise dropped in silence.
  private func fireRemote(
    _ label: String,
    requires command: NSInteger? = nil,
    supersedesPlay: Bool = false,
    _ block: @escaping (GCKRemoteMediaClient) -> GCKRequest?
  ) {
    if closed { return }
    runOnMain { [weak self] in
      guard let self = self, !self.closed else { return }
      guard let client = self.sdkSession.remoteMediaClient else {
        NSLog("[ChromecastSession] \(label): no GCKRemoteMediaClient — session not fully live?")
        return
      }
      if let command = command,
         let status = client.mediaStatus,
         !status.isMediaCommandSupported(command) {
        NSLog(
          "[ChromecastSession] \(label): receiver does not advertise support for this command; sending anyway"
        )
      }
      #if DEBUG
      NSLog("[ChromecastSession] \(label): issuing")
      #endif
      // `nil` means this call site declined to issue anything — the only such
      // case is a queue index the receiver's model hasn't synced yet, which is
      // a normal transient, not a fault.
      guard let request = block(client) else {
        #if DEBUG
        NSLog("[ChromecastSession] \(label): no request issued")
        #endif
        return
      }
      if supersedesPlay { self.transportGeneration += 1 }
      let delegate = TransportRequestDelegate(label: label, owner: self)
      self.retain(delegate)
      request.delegate = delegate
    }
  }

  /// Issue `block` on main, await its returned `GCKRequest` via a
  /// dedicated `GCKRequestDelegate`. Closed-before-fire short-circuits
  /// with `SessionError.closed`; a nil `GCKRequest` (SDK refused to
  /// schedule) raises `requestStartFailed`.
  private func awaitRequest(
    label: String,
    supersedesPlay: Bool = false,
    block: @escaping () -> GCKRequest?
  ) async throws {
    if closed { throw SessionError.closed }
    try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
      runOnMain { [weak self] in
        guard let self = self else {
          cont.resume(throwing: SessionError.closed)
          return
        }
        if self.closed {
          cont.resume(throwing: SessionError.closed)
          return
        }
        guard let request = block() else {
          cont.resume(throwing: SessionError.requestStartFailed(label))
          return
        }
        if supersedesPlay { self.transportGeneration += 1 }
        let delegate = RequestContinuation(
          label: label,
          cont: cont,
          owner: self
        )
        self.retain(delegate)
        request.delegate = delegate
      }
    }
  }

  private func retain(_ delegate: NSObject) {
    pendingDelegatesLock.lock()
    pendingRequestDelegates[ObjectIdentifier(delegate)] = delegate
    pendingDelegatesLock.unlock()
  }

  /// Remove `delegate` from the pending set, returning whether it was still
  /// present. Removal is the single resume claim: whoever removes a
  /// `RequestContinuation` (a terminal callback here, or `close()`'s drain via
  /// `removeAll`) is the one that resumes it, so the continuation resumes
  /// exactly once even when teardown races a terminal callback.
  @discardableResult
  fileprivate func drop(_ delegate: NSObject) -> Bool {
    pendingDelegatesLock.lock()
    let claimed = pendingRequestDelegates.removeValue(forKey: ObjectIdentifier(delegate)) != nil
    pendingDelegatesLock.unlock()
    return claimed
  }

  /// The session's server handle, acquired on first use. The `closed` check
  /// and the store run on main, in the same turn as `close()`'s release of
  /// `serverHandle`, so `close()` always releases the handle: if it ran first,
  /// the new handle is closed here and the call throws; if it ran after, it
  /// closes the stored handle.
  private func ensureServerHandle() async throws -> MediaServerHandle {
    let (isClosed, existing) = await valueOnMain { (self.closed, self.serverHandle) }
    if isClosed { throw SessionError.closed }
    if let existing {
      // A load after the app sat suspended may find the listener reclaimed;
      // the receiver would fetch every new URL from a dead port.
      let answering = await withCheckedContinuation { cont in
        LocalMediaServer.shared.checkAnswering { cont.resume(returning: $0) }
      }
      guard answering else {
        NSLog("[ChromecastSession] load: media server not answering; ending the cast session")
        runOnMain { [weak self] in self?.onMediaServerNotAnswering?() }
        throw SessionError.mediaServerNotAnswering
      }
      if await valueOnMain({ self.closed }) { throw SessionError.closed }
      return existing
    }
    let acquired = await LocalMediaServer.shared.acquire(sessionId: sessionIdentifier)
    let kept: MediaServerHandle? = await valueOnMain {
      if self.closed { return nil }
      if let stored = self.serverHandle { return stored }
      self.serverHandle = acquired
      return acquired
    }
    guard let kept else {
      acquired.close()
      throw SessionError.closed
    }
    // Another call stored a handle while this one acquired; keep that one.
    if kept !== acquired { acquired.close() }
    return kept
  }

  /// `body`'s result, evaluated on main.
  private func valueOnMain<T>(_ body: @escaping () -> T) async -> T {
    await withCheckedContinuation { cont in
      runOnMain { cont.resume(returning: body()) }
    }
  }

  private func runOnMain(_ block: @escaping () -> Void) {
    if Thread.isMainThread {
      block()
    } else {
      DispatchQueue.main.async(execute: block)
    }
  }
}

// MARK: - GCKRequest delegate wrappers

/// Bridge `GCKRequest`'s delegate methods into a Swift
/// `CheckedContinuation`. The owning `ChromecastSession` retains
/// instances until a terminal callback fires.
fileprivate final class RequestContinuation: NSObject, GCKRequestDelegate {
  let label: String
  let cont: CheckedContinuation<Void, Error>
  weak var owner: ChromecastSession?

  init(
    label: String,
    cont: CheckedContinuation<Void, Error>,
    owner: ChromecastSession
  ) {
    self.label = label
    self.cont = cont
    self.owner = owner
    super.init()
  }

  func requestDidComplete(_ request: GCKRequest) {
    guard owner?.drop(self) == true else { return }
    cont.resume()
  }

  func request(_ request: GCKRequest, didFailWithError error: GCKError) {
    guard owner?.drop(self) == true else { return }
    cont.resume(throwing: error)
  }

  func request(_ request: GCKRequest, didAbortWith abortReason: GCKRequestAbortReason) {
    guard owner?.drop(self) == true else { return }
    cont.resume(throwing: NSError(
      domain: "ChromecastSession",
      code: Int(abortReason.rawValue),
      userInfo: [NSLocalizedDescriptionKey: "\(label) aborted"]
    ))
  }
}

/// Reports the outcome of a fire-and-forget transport request. The caller
/// doesn't await these, so without a delegate a receiver that refuses a
/// command leaves no trace at all.
fileprivate final class TransportRequestDelegate: NSObject, GCKRequestDelegate {
  let label: String
  weak var owner: ChromecastSession?

  init(label: String, owner: ChromecastSession) {
    self.label = label
    self.owner = owner
    super.init()
  }

  func requestDidComplete(_ request: GCKRequest) {
    owner?.drop(self)
  }

  func request(_ request: GCKRequest, didFailWithError error: GCKError) {
    NSLog("[ChromecastSession] \(label) failed: \(error.localizedDescription)")
    owner?.drop(self)
  }

  func request(_ request: GCKRequest, didAbortWith abortReason: GCKRequestAbortReason) {
    NSLog("[ChromecastSession] \(label) aborted: \(abortReason.rawValue)")
    owner?.drop(self)
  }
}

/// Bridge `seek(with:)`'s `GCKRequest` into the session's
/// `onSeekCompleted` listener fan-out. Fire-and-forget — failures are
/// logged but do not surface as JS errors (the lib has no "seek-
/// failed" event surface).
fileprivate final class SeekCompletionDelegate: NSObject, GCKRequestDelegate {
  let positionMs: Int64
  weak var owner: ChromecastSession?

  init(positionMs: Int64, owner: ChromecastSession) {
    self.positionMs = positionMs
    self.owner = owner
    super.init()
  }

  func requestDidComplete(_ request: GCKRequest) {
    owner?.fireSeekCompleted(positionMs)
    owner?.drop(self)
  }

  func request(_ request: GCKRequest, didFailWithError error: GCKError) {
    NSLog("[ChromecastSession] seekTo(\(positionMs)) failed: \(error.localizedDescription)")
    owner?.drop(self)
  }

  func request(_ request: GCKRequest, didAbortWith abortReason: GCKRequestAbortReason) {
    NSLog("[ChromecastSession] seekTo(\(positionMs)) aborted: \(abortReason.rawValue)")
    owner?.drop(self)
  }
}

#endif
