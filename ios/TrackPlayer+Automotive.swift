import NitroModules

extension TrackPlayer {
  // MARK: - Automotive / voice handler registration

  /// Atomically replace the cached `BrowseSnapshot` on the
  /// process-wide [CarPlayCoordinator]. The data provider posts a
  /// `didUpdateNotification` on the main queue; an active CarPlay
  /// scene observes the notification + rebuilds the root template
  /// against the new snapshot. Push semantics — most-recent call
  /// wins. Safe to call before a CarPlay scene attaches: the
  /// snapshot is cached on the coordinator + the next scene reads
  /// it directly at didConnect.
  func setBrowseSnapshot(snapshot: BrowseSnapshot) throws {
    CarPlayCoordinator.shared.dataProvider.setSnapshot(snapshot)
  }

  func onBrowseRequest(
    callback: @escaping (_ parentId: String) -> Promise<Promise<[BrowseItem]>>
  ) throws -> () -> Void {
    return CarPlayCoordinator.shared.bridge.registerOnBrowseRequest(callback)
  }

  // `onSearchRequest` is Android-only — CarPlay audio-entitled apps
  // cannot legally present `CPSearchTemplate` (per the CarPlay App
  // Programming Guide template-by-category rules); pushing or
  // presenting it raises NSInvalidArgumentException at runtime. Apple
  // Music + Spotify both dropped CarPlay search in iOS 26; the
  // canonical CarPlay search affordance for audio apps is Siri via
  // SiriKit's `INPlayMediaIntent`, not an in-app search template.
  //
  // `onPlayFromSearchRequest` IS wired on iOS — it serves the
  // SiriKit `INPlayMediaIntent` path: Siri parses "Hey Siri play X
  // on MyApp" into a structured `INMediaSearch`, the lib's
  // `PlayMediaIntentHandler` maps it to `MediaSearchRequest`, and
  // dispatches into this callback via `CarPlayBridge`.
  //
  // For `onSearchRequest` we accept the consumer's registration on
  // iOS to keep the TS API shape cross-platform; the callback is
  // discarded (`_ = callback` consumes the value), and the returned
  // disposer is a no-op so the consumer's bookkeeping stays sane.
  func onSearchRequest(
    callback: @escaping (_ query: String) -> Promise<Promise<[BrowseItem]>>
  ) throws -> () -> Void {
    _ = callback
    return {}
  }

  func onPlayFromSearchRequest(
    callback: @escaping (_ request: MediaSearchRequest) -> Promise<Promise<[TrackItem]>>
  ) throws -> () -> Void {
    return CarPlayCoordinator.shared.bridge.registerOnPlayFromSearchRequest(callback)
  }

  func onPlayFromIdRequest(
    callback: @escaping (_ mediaId: String) -> Promise<Promise<[TrackItem]>>
  ) throws -> () -> Void {
    return CarPlayCoordinator.shared.bridge.registerOnPlayFromIdRequest(callback)
  }

  // Donation Hybrid method — fire-and-forget into Siri's vocabulary
  // donation pipeline. Real impl lives in `ios/Siri/VoiceDonation.swift`.
  // Uses `INVocabulary.setVocabularyStrings(_:of:)` to seed Siri's NLU
  // with the consumer's artist + playlist names.
  func donateVoiceVocabulary(vocabulary: VoiceVocabulary) throws {
    // Opt-in only. `INVocabulary` raises an uncatchable Objective-C exception
    // in a process without the `com.apple.developer.siri` entitlement, and
    // iOS gives a process no way to read its own entitlements — so the
    // consumer declaring the capability is the only signal the library can
    // have that this is safe to touch.
    guard self.voiceVocabularyDonationEnabled else { return }
    VoiceDonation.donateVoiceVocabulary(vocabulary)
  }

  func onCarConnect(callback: @escaping () -> Void) throws -> () -> Void {
    return CarPlayCoordinator.shared.bridge.registerOnCarConnect(callback)
  }

  // `onServiceReady` is an Android-only signal. iOS apps die fully
  // under memory pressure (no separate service lifecycle), so there's
  // no `service-reborn` scenario to surface here. The lib accepts the
  // registration to keep the cross-platform API shape consistent and
  // returns a no-op disposer; the callback is consumed (so it isn't
  // retained) and never invoked. Consumers writing for both platforms
  // register one handler; on iOS only `onCarConnect` carries the
  // equivalent "automotive surface available" signal.
  func onServiceReady(
    callback: @escaping (_ reason: ServiceReadyReason) -> Void
  ) throws -> () -> Void {
    _ = callback
    return {}
  }

  func onCarDisconnect(callback: @escaping () -> Void) throws -> () -> Void {
    return CarPlayCoordinator.shared.bridge.registerOnCarDisconnect(callback)
  }

  func isCarConnected() throws -> Bool {
    return CarPlayCoordinator.shared.isCarConnected
  }
}
