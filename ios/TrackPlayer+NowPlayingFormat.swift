import AVFoundation

extension TrackPlayer {
  /// Returns the active item's runtime audio format, or the null variant
  /// when nothing is currently playable / the format hasn't loaded yet.
  /// `Variant_NullType_NowPlayingFormat` is Nitrogen's bridge for
  /// `NowPlayingFormat | null` (`.first(.null)` = absent, `.second(format)` =
  /// present). Cached by the async resolver; never blocks.
  ///
  /// An empty / out-of-range queue short-circuits to `.null` regardless
  /// of the cache — the canonical-state branch below.
  func getNowPlayingFormat() throws -> Variant_NullType_NowPlayingFormat {
    return onPlayerQueueSync {
      // Lib canonical state takes precedence over the cached format.
      // After `clearQueue` / queue-end the engine's `currentMediaItem`
      // can briefly retain the last item; without this guard the read
      // returns the prior track's stale codec / bitrate / duration
      // even though `currentTrackIndex == -1` and the JS-facing state
      // shows track == null.
      if self.tracks.isEmpty
        || self.currentTrackIndex < 0
        || self.currentTrackIndex >= self.tracks.count {
        return .first(.null)
      }
      // RG fields are read live from `AudioTapProvider`'s per-item
      // cache so they surface as soon as the item's own metadata read
      // completes — independently of the slow-path AVFoundation
      // format-description loads that populate `lastEmittedFormat`'s
      // codec/bitrate/duration fields.
      let activeItem = self.activeMediaItem
      let rgData = activeItem.flatMap { AudioTapProvider.shared.replayGainDataForItem($0) }
      let rgActive = activeItem.map { AudioTapProvider.shared.replayGainActiveForItem($0) } ?? false
      if let cached = self.lastEmittedFormat {
        return .second(mergeRG(cached, rgData: rgData, rgActive: rgActive))
      }
      // No slow-path snapshot yet. Surface RG fields alone so
      // consumers waiting on RG (e.g. visualisation, normalisation
      // UI) don't have to wait for the AVAsset chain to complete.
      // Non-RG fields fall through to their `.null` sentinels.
      if rgData != nil {
        return .second(NowPlayingFormat(
          codec: .unknown,
          container: .unknown,
          mimeType: .first(.null),
          sampleRate: .first(.null),
          channelCount: .first(.null),
          bitrate: .first(.null),
          durationSec: .first(.null),
          replayGainTrackDb: rgData?.trackGainDb.map { .second($0) } ?? .first(.null),
          replayGainTrackPeak: rgData?.trackPeak.map { .second($0) } ?? .first(.null),
          replayGainAlbumDb: rgData?.albumGainDb.map { .second($0) } ?? .first(.null),
          replayGainAlbumPeak: rgData?.albumPeak.map { .second($0) } ?? .first(.null),
          replayGainActive: rgActive,
          ios: NowPlayingFormatIosExtras(bitsPerChannel: .first(.null)),
          android: nil
        ))
      }
      return .first(.null)
    }
  }

  /// Replace the four RG fields of `cached` with live values from
  /// the AudioTap. Non-RG fields are preserved as-is.
  private func mergeRG(
    _ cached: NowPlayingFormat,
    rgData: ReplayGainData?,
    rgActive: Bool
  ) -> NowPlayingFormat {
    return NowPlayingFormat(
      codec: cached.codec,
      container: cached.container,
      mimeType: cached.mimeType,
      sampleRate: cached.sampleRate,
      channelCount: cached.channelCount,
      bitrate: cached.bitrate,
      durationSec: cached.durationSec,
      replayGainTrackDb: rgData?.trackGainDb.map { .second($0) } ?? .first(.null),
      replayGainTrackPeak: rgData?.trackPeak.map { .second($0) } ?? .first(.null),
      replayGainAlbumDb: rgData?.albumGainDb.map { .second($0) } ?? .first(.null),
      replayGainAlbumPeak: rgData?.albumPeak.map { .second($0) } ?? .first(.null),
      replayGainActive: rgActive,
      ios: cached.ios,
      android: cached.android
    )
  }

  /// Emit the current now-playing format to subscribers. Used by
  /// the AudioTap RG-data-populated callback to push the updated
  /// format without re-running `NowPlayingFormatExtractor`.
  internal func emitCurrentNowPlayingFormat(for item: AVPlayerItem) {
    guard let active = self.activeMediaItem,
          ObjectIdentifier(active) == ObjectIdentifier(item) else { return }
    let rgData = AudioTapProvider.shared.replayGainDataForItem(item)
    let rgActive = AudioTapProvider.shared.replayGainActiveForItem(item)
    if let cached = self.lastEmittedFormat {
      let merged = mergeRG(cached, rgData: rgData, rgActive: rgActive)
      self.lastEmittedFormat = merged
      self.nowPlayingFormatListeners.forEach { $0(.second(merged)) }
    }
  }

  func onNowPlayingFormatChange(
    callback: @escaping (_ format: Variant_NullType_NowPlayingFormat?) -> Void
  ) throws -> () -> Void {
    let id = nowPlayingFormatListeners.add(callback)
    return { [weak self] in self?.nowPlayingFormatListeners.remove(id: id) }
  }

  /// Engine-agnostic active-item accessor. Reads via the active
  /// engine's `currentMediaItem` getter (works for GaplessEngine
  /// AND CrossfadeEngine — `self.player` is nil during crossfade-
  /// mode playback because that engine routes through dual private
  /// AVPlayers). Falls back to `self.player?.currentItem` for paths
  /// that pre-date the engine surface.
  internal var activeMediaItem: AVPlayerItem? {
    return self.engine?.currentMediaItem ?? self.player?.currentItem
  }

  /// Bump the request generation, drop a stale cache (emitting null so
  /// the JS consumer sees a loading state), and kick off the async
  /// extractor for the current AVPlayerItem. When there is no current
  /// item, clears + emits null and returns without launching a Task.
  internal func refreshNowPlayingFormatForActiveItem() {
    self.nowPlayingFormatRequestGen &+= 1
    let gen = self.nowPlayingFormatRequestGen

    guard let item = self.activeMediaItem else {
      if self.lastEmittedFormat != nil || self.lastEmittedFormatItemId != nil {
        self.lastEmittedFormat = nil
        self.lastEmittedFormatItemId = nil
        nowPlayingFormatListeners.forEach { $0(.first(.null)) }
      }
      return
    }
    let itemId = ObjectIdentifier(item)

    // Same item already resolved — no work, no emit. The two same-item
    // re-emits do not pass through this check: `emitCurrentNowPlayingFormat(for:)`
    // merges fresh replay-gain fields into the cached snapshot without
    // running the extractor; `reresolveNowPlayingFormatForBitrate` re-runs
    // the extractor and emits only when the bitrate changed.
    if self.lastEmittedFormatItemId == itemId, self.lastEmittedFormat != nil {
      return
    }

    // New (or different) item — drop stale cache + emit null so the
    // JS consumer can show a loading state before the new format
    // lands. Its bitrate re-resolve budget starts fresh here as well as on
    // the identity check below, since an item allocated at a released
    // item's address would otherwise inherit a spent one.
    self.bitrateReresolveItemId = itemId
    self.bitrateReresolveAttempts = 0
    if self.lastEmittedFormat != nil {
      self.lastEmittedFormat = nil
      self.lastEmittedFormatItemId = nil
      nowPlayingFormatListeners.forEach { $0(.first(.null)) }
    }

    Task { [weak self] in
      let resolved = await NowPlayingFormatExtractor.extract(item: item)
      // The generation token, the active item and the last-emitted format are
      // all player state, so the completion settles on the queue that owns
      // them — otherwise the stale-resolve guard compares a value written
      // from another queue.
      await self?.onPlayerQueue { [weak self] in
        guard let self else { return }
        guard self.nowPlayingFormatRequestGen == gen else { return }
        // Drops fires from items the active engine has already
        // advanced past during the await.
        guard let current = self.activeMediaItem,
              ObjectIdentifier(current) == itemId else { return }
        guard let format = resolved else { return }
        self.lastEmittedFormat = format
        self.lastEmittedFormatItemId = itemId
        self.nowPlayingFormatListeners.forEach { $0(.second(format)) }
      }
    }
  }

  /// Compare two `Variant_NullType_Double?` values for "is the bitrate
  /// effectively the same". Treats `nil` and `.first(.null)` as
  /// equivalent (both mean "no bitrate"); `.second(Double)` matches
  /// only on IEEE-754 equality (Swift's `Double ==`). Value equality is
  /// intentional — the extractor produces values from a small set of
  /// integer-source feeds (Core Audio bitrate is `UInt32`, access-log
  /// bitrate is a server-declared kbps figure) so equality holds across
  /// repeated reads of the same underlying signal.
  private func sameBitrateVariant(
    _ a: Variant_NullType_Double?, _ b: Variant_NullType_Double?
  ) -> Bool {
    let aValue: Double? = {
      guard let a = a, case .second(let v) = a else { return nil }
      return v
    }()
    let bValue: Double? = {
      guard let b = b, case .second(let v) = b else { return nil }
      return v
    }()
    return aValue == bValue
  }

  /// Re-resolve the active item's now-playing format to fill a missing
  /// bitrate. The base `refreshNowPlayingFormatForActiveItem` returns early
  /// for an item it has already emitted a format for; this entry point runs
  /// the extractor again and emits only when the resolved bitrate differs
  /// from the emitted one. Called from the access-log notification handler
  /// while the emitted format has no bitrate, whatever the codec.
  ///
  /// `bitrateReresolveInFlight` early-returns when a previous call is
  /// still resolving — access-log notifications fire many times per
  /// second so without this gate every fire while the first extract is
  /// running would spawn another detached Task — and each item gets at most
  /// `maxBitrateReresolvesPerItem` runs, since a stream whose server never
  /// declares a bitrate would otherwise be re-read for the life of the
  /// track. The active-item gate + generation token + `lastEmittedFormatItemId`
  /// check still apply on the completion side.
  internal func reresolveNowPlayingFormatForBitrate() {
    if self.bitrateReresolveInFlight { return }
    guard let item = self.activeMediaItem else { return }
    let itemId = ObjectIdentifier(item)
    if self.bitrateReresolveItemId != itemId {
      self.bitrateReresolveItemId = itemId
      self.bitrateReresolveAttempts = 0
    }
    guard self.bitrateReresolveAttempts < Self.maxBitrateReresolvesPerItem else { return }
    self.bitrateReresolveAttempts += 1
    self.nowPlayingFormatRequestGen &+= 1
    let gen = self.nowPlayingFormatRequestGen
    self.bitrateReresolveInFlight = true

    Task { [weak self] in
      let resolved = await NowPlayingFormatExtractor.extract(item: item)
      // The generation token, the active item and the last-emitted format are
      // all player state, so the completion settles on the queue that owns
      // them — otherwise the stale-resolve guard compares a value written
      // from another queue.
      await self?.onPlayerQueue { [weak self] in
        guard let self else { return }
        defer { self.bitrateReresolveInFlight = false }
        guard self.nowPlayingFormatRequestGen == gen else { return }
        guard let current = self.activeMediaItem,
              ObjectIdentifier(current) == itemId else { return }
        guard self.lastEmittedFormatItemId == itemId else { return }
        guard let format = resolved else { return }
        // Emit only on a meaningful change. Bitrate is the field this
        // path exists to fill; comparing the variant tag handles both
        // null→value transitions and value→value updates without
        // pulling the wrapped Double into Equatable territory.
        let prev = self.lastEmittedFormat
        let bitrateChanged = !sameBitrateVariant(prev?.bitrate, format.bitrate)
        if !bitrateChanged { return }
        self.lastEmittedFormat = format
        self.lastEmittedFormatItemId = itemId
        self.nowPlayingFormatListeners.forEach { $0(.second(format)) }
      }
    }
  }
}
