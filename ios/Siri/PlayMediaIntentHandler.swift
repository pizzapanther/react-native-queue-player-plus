import Foundation
import Intents
import OSLog

/// SiriKit `INPlayMediaIntent` handler. Drives the "Hey Siri play
/// Pearl Jam on MyApp" voice surface.
///
/// Implements the full `INPlayMediaIntentHandling` protocol surface
/// (resolve* / confirm / handle). iOS uses the presence of these
/// methods to classify the bundle as a music app eligible for the
/// Remote Control Hub flow — the Loading overlay and Now-Playing
/// transition during voice dispatch. Implementing `handle` alone is
/// not sufficient; iOS skips the music-app classification and the
/// overlay never appears.
///
/// ## Resolve phase
///
/// `resolveMediaItems` returns a single synthetic placeholder reflecting
/// the spoken query. The honest implementation would run the
/// consumer's catalog search here, but that requires a JS-bridge
/// round-trip which is unsafe during the launch sequence (cold start
/// fires before the JS bundle finishes loading; warm start's resolve
/// timeout is tighter than a realistic catalog HTTP call). The
/// placeholder flips the music-app classification and the real
/// catalog search runs in `handle`, covered by the Loading overlay.
///
/// ## Confirm phase
///
/// `confirm` returns `.ready` so iOS shows the Loading overlay.
///
/// ## Handle phase
///
/// Dispatches a `MediaSearchRequest` to the JS-side resolver via
/// `CarPlayBridge.playFromSearchRequestCallback`. The consumer's JS
/// `onPlayFromSearchRequest` returns the resolved `[TrackItem]` and
/// kicks off `setQueue + play` fire-and-forget. We await the
/// resolver's returned track list (the inner Nitro Promise unwraps
/// to JS's return value) so the response code reflects the search
/// outcome:
///
/// - non-empty tracks → `.success`. Siri transitions to its Now-
///   Playing chrome; audio continues setting up on the JS side.
/// - empty tracks → `.failure`. Siri renders its localised
///   "Sorry, I couldn't…" message. `INPlayMediaIntentResponseCode`
///   has no "not found" code; `.failure` is the convention.
/// - JS resolver throws or runs past the 8s budget → `.failure`.
///
/// Awaiting does not slow audio start: the JS resolver returns its
/// track list as soon as the catalog hit resolves; `setQueue + play`
/// runs after the return, so the await window is bounded by the
/// catalog lookup, not by audio pipeline warm-up. The 8s ceiling
/// stays comfortably inside Apple's ~10s `handle` budget.
///
/// ## Cold-start race
///
/// When the JS bridge hasn't registered yet (Siri dispatched the
/// intent before `registerPlaybackService` ran), the request lands
/// on the lib's `PendingIntentBuffer` and drains automatically when
/// `CarPlayBridge.registerOnPlayFromSearchRequest` is invoked. The
/// Loading overlay covers this window. The buffered drain is async
/// and runs after `handle` returns, so the cold-start response is
/// always `.success` — there is no late channel to Siri for the
/// cold-start no-match case.
///
/// ## ObjC visibility
///
/// `internal final class` so the Swift module header
/// `<Module>-Swift.h` doesn't reference `INPlayMediaIntentHandling`
/// (an Intents framework symbol the QueuePlayer pod's Obj-C bridging
/// header doesn't import). Consumers reach the handler only through
/// `QueuePlayerMediaIntents.handler(forIntent:)` which returns it as
/// `Any?`.
@available(iOS 13.0, *)
internal final class PlayMediaIntentHandler: NSObject, INPlayMediaIntentHandling {

  private static let logger = Logger(
    subsystem: "com.queueplayer.lib",
    category: "PlayMediaIntent"
  )

  // MARK: - Resolve phase

  func resolveMediaItems(
    for intent: INPlayMediaIntent
  ) async -> [INPlayMediaMediaItemResolutionResult] {
    let query = intent.mediaSearch?.mediaName
      ?? intent.mediaSearch?.artistName
      ?? intent.mediaSearch?.albumName
      ?? "Music"
    let placeholder = INMediaItem(
      identifier: "rnqp-pending-search-\(query.hashValue)",
      title: query,
      type: intent.mediaSearch?.mediaType ?? .song,
      artwork: nil,
      artist: intent.mediaSearch?.artistName
    )
    return INPlayMediaMediaItemResolutionResult.successes(with: [placeholder])
  }

  func resolvePlayShuffled(
    for intent: INPlayMediaIntent
  ) async -> INBooleanResolutionResult {
    INBooleanResolutionResult.success(with: intent.playShuffled ?? false)
  }

  func resolvePlaybackRepeatMode(
    for intent: INPlayMediaIntent
  ) async -> INPlaybackRepeatModeResolutionResult {
    INPlaybackRepeatModeResolutionResult.success(with: intent.playbackRepeatMode)
  }

  func resolveResumePlayback(
    for intent: INPlayMediaIntent
  ) async -> INBooleanResolutionResult {
    INBooleanResolutionResult.success(with: intent.resumePlayback ?? true)
  }

  func resolvePlaybackQueueLocation(
    for intent: INPlayMediaIntent
  ) async -> INPlaybackQueueLocationResolutionResult {
    INPlaybackQueueLocationResolutionResult.success(with: intent.playbackQueueLocation)
  }

  func resolvePlaybackSpeed(
    for intent: INPlayMediaIntent
  ) async -> INPlayMediaPlaybackSpeedResolutionResult {
    INPlayMediaPlaybackSpeedResolutionResult.success(with: intent.playbackSpeed ?? 1.0)
  }

  // MARK: - Confirm phase

  func confirm(intent: INPlayMediaIntent) async -> INPlayMediaIntentResponse {
    INPlayMediaIntentResponse(code: .ready, userActivity: nil)
  }

  // MARK: - Handle phase

  func handle(intent: INPlayMediaIntent) async -> INPlayMediaIntentResponse {
    let request = Self.buildRequest(from: intent)
    Self.logger.info(
      "handle: query=\(request.query, privacy: .public) artist=\(request.artist ?? "nil", privacy: .public) album=\(request.album ?? "nil", privacy: .public)"
    )

    let bridge = CarPlayCoordinator.shared.bridge
    guard let cb = bridge.playFromSearchRequestCallback() else {
      bridge.bufferPlayFromSearch(request)
      return INPlayMediaIntentResponse(code: .success, userActivity: nil)
    }

    do {
      let tracks = try await Self.awaitWithTimeout(seconds: 8) {
        try await cb(request).await().await()
      }
      return INPlayMediaIntentResponse(
        code: tracks.isEmpty ? .failure : .success,
        userActivity: nil
      )
    } catch {
      Self.logger.error("handle: JS resolver failed — \(String(describing: error), privacy: .public)")
      return INPlayMediaIntentResponse(code: .failure, userActivity: nil)
    }
  }

  /// Race a `work` block against a sleep-based deadline. The first to
  /// finish wins; the other is cancelled. The throwing variant lets us
  /// surface either the work's error or a `TimeoutError` to the caller.
  private static func awaitWithTimeout<T: Sendable>(
    seconds: Double,
    _ work: @escaping @Sendable () async throws -> T
  ) async throws -> T {
    try await withThrowingTaskGroup(of: T.self) { group in
      group.addTask { try await work() }
      group.addTask {
        try await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
        throw TimeoutError()
      }
      defer { group.cancelAll() }
      guard let result = try await group.next() else {
        throw TimeoutError()
      }
      return result
    }
  }

  private struct TimeoutError: Error {}

  // MARK: - Intent → MediaSearchRequest mapping

  /// Map `INPlayMediaIntent.mediaSearch` into the lib's
  /// `MediaSearchRequest` shape so the consumer's JS resolver sees
  /// the same wire format regardless of voice surface (SiriKit on
  /// iOS, Google Assistant on Android).
  static func buildRequest(from intent: INPlayMediaIntent) -> MediaSearchRequest {
    let mediaSearch = intent.mediaSearch
    let mediaName = mediaSearch?.mediaName ?? ""
    let firstArtist = mediaSearch?.artistName
    return MediaSearchRequest(
      query: mediaName.isEmpty ? (firstArtist ?? "") : mediaName,
      type: mapType(mediaSearch?.mediaType),
      artist: firstArtist,
      album: mediaSearch?.albumName,
      song: mediaSearch?.mediaName,
      playlist: nil,
      genre: mediaSearch?.genreNames?.first,
      origin: MediaSearchOrigin.iosSiri,
      reference: mediaSearch.flatMap { mapReference($0.reference) },
      playShuffled: bridgeNumber(intent.playShuffled),
      playbackRepeatMode: mapRepeatMode(intent.playbackRepeatMode),
      resumePlayback: bridgeNumber(intent.resumePlayback),
      playbackSpeed: bridgeDouble(intent.playbackSpeed),
      releaseDate: mapReleaseDate(mediaSearch?.releaseDate),
      playbackQueueLocation: mapQueueLocation(intent.playbackQueueLocation)
    )
  }

  private static func mapType(_ type: INMediaItemType?) -> MediaSearchType? {
    switch type {
    case .song: return .song
    case .album: return .album
    case .artist: return .artist
    case .playlist: return .playlist
    case .genre: return .genre
    default: return nil
    }
  }

  private static func mapReference(_ reference: INMediaReference) -> MediaSearchReference? {
    switch reference {
    case .currentlyPlaying: return MediaSearchReference.currentlyplaying
    case .my: return MediaSearchReference.my
    default: return nil
    }
  }

  /// Apple's `INPlayMediaIntent.playShuffled` / `.resumePlayback` are
  /// declared as `Bool?` in newer SDKs and `NSNumber?` in older ones.
  /// This bridging helper accepts whatever the SDK ships and yields
  /// the lib's `Bool?` shape.
  private static func bridgeNumber(_ value: Any?) -> Bool? {
    if let v = value as? Bool { return v }
    if let v = value as? NSNumber { return v.boolValue }
    return nil
  }

  private static func bridgeDouble(_ value: Any?) -> Double? {
    if let v = value as? Double { return v }
    if let v = value as? NSNumber { return v.doubleValue }
    return nil
  }

  private static func mapRepeatMode(_ mode: INPlaybackRepeatMode) -> MediaSearchRepeatMode? {
    switch mode {
    case .none: return MediaSearchRepeatMode.none
    case .one: return MediaSearchRepeatMode.one
    case .all: return MediaSearchRepeatMode.all
    default: return nil
    }
  }

  private static func mapQueueLocation(_ location: INPlaybackQueueLocation) -> MediaSearchQueueLocation? {
    switch location {
    case .now: return MediaSearchQueueLocation.now
    case .next: return MediaSearchQueueLocation.next
    case .later: return MediaSearchQueueLocation.later
    default: return nil
    }
  }

  private static func mapReleaseDate(_ date: INDateComponentsRange?) -> MediaSearchReleaseDateRange? {
    guard let date = date else { return nil }
    let start = date.startDateComponents?.year
    let end = date.endDateComponents?.year
    if start == nil && end == nil { return nil }
    return MediaSearchReleaseDateRange(
      startYear: start.map { Double($0) },
      endYear: end.map { Double($0) }
    )
  }
}
