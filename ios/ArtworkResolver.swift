import Foundation
import UIKit

/// Resolves a `track.artworkUrl` to a `UIImage` for the lock-screen /
/// control-center surface. Always delivers a non-nil image: when the
/// URL is null / empty / invalid / unreachable, the bundled
/// `PlaceholderArtwork` image is the floor.
///
/// Single in-flight policy: starting a new `resolve(...)` bumps a
/// monotonic sequence counter. Completions captured against an older
/// sequence are dropped in favour of the latest caller's expectation
/// (e.g. a same-URL re-resolve issued during an in-flight fetch
/// correctly drops the prior request's response).
///
/// Delegates the actual URI → UIImage resolution to [ArtworkLoader],
/// which owns the cache + URLSession. Sharing the loader across
/// callers (e.g. the CarPlay browse builder) means every browse-list
/// fetch warms the now-playing cache and vice versa.
final class ArtworkResolver {

  /// HTTP headers to send on every artwork request. Forwarded to the
  /// underlying loader so the next `resolve(...)` reads fresh.
  var configHeaders: [String: String]? {
    get { loader.configHeaders }
    set { loader.configHeaders = newValue }
  }

  /// User-agent override for artwork requests. Forwarded to the loader.
  var configUserAgent: String? {
    get { loader.configUserAgent }
    set { loader.configUserAgent = newValue }
  }

  /// Owner injects an [ArtworkLoader] when sharing a cache + session
  /// across resolvers. Default constructs a private loader with an
  /// ephemeral session — no cookie jar, no shared on-disk cache.
  init(loader: ArtworkLoader = ArtworkLoader()) {
    self.loader = loader
  }

  /// Convenience: build a private loader bound to [session]. Lets
  /// tests inject a `URLProtocol`-stubbed session without owning the
  /// loader directly.
  convenience init(session: URLSession) {
    self.init(loader: ArtworkLoader(session: session))
  }

  /// Resolve `url` to a UIImage. Completion fires on main with the
  /// placeholder when anything goes wrong (nil URL, non-http(s)
  /// scheme, network failure). Completion is intentionally DROPPED
  /// when the resolver is cancelled (`cancelInFlight`) or superseded
  /// by a later `resolve(...)` — the new caller's completion is the
  /// only one the consumer cares about. Threading: completion fires
  /// on main.
  func resolve(url: String?, completion: @escaping (UIImage) -> Void) {
    let seq = bumpSeq()

    loader.load(uri: url) { [weak self] image in
      guard let self = self else { return }
      guard self.isCurrent(seq) else {
        DiagnosticLog.nowPlaying.log("artwork load superseded")
        return
      }
      DiagnosticLog.nowPlaying.log("artwork load \(image == nil ? "failed or no url" : "succeeded")")
      completion(image ?? PlaceholderArtwork.image)
    }
  }

  /// Bump the sequence counter so any in-flight loader completion
  /// captured against the old seq is dropped. The underlying
  /// `URLSessionDataTask` is intentionally not cancelled — bytes
  /// already in transit will populate the loader's cache for a
  /// future `resolve(...)` that hits the same URL.
  func cancelInFlight() {
    _ = bumpSeq()
  }

  /// Clear the underlying loader's cache + drop the in-flight
  /// completion. Idempotent and reusable: a subsequent `resolve(...)`
  /// is valid and uses the same underlying session.
  func reset() {
    cancelInFlight()
    loader.clearCache()
  }

  private let loader: ArtworkLoader
  /// Monotonic resolve-call counter. Captured into completion closures
  /// to drop responses for superseded requests, including same-URL
  /// re-resolves that the URL-string compare cannot distinguish.
  /// Callers bump this on the queue that owns playback while the loader
  /// reports completions on main, so the counter is the one piece of state
  /// crossing between them and it carries its own lock.
  private let seqLock = NSLock()
  private var currentSeq: UInt64 = 0

  /// Advance the sequence and return the new value.
  private func bumpSeq() -> UInt64 {
    seqLock.lock()
    defer { seqLock.unlock() }
    currentSeq &+= 1
    return currentSeq
  }

  /// Whether `seq` is still the newest request.
  private func isCurrent(_ seq: UInt64) -> Bool {
    seqLock.lock()
    defer { seqLock.unlock() }
    return currentSeq == seq
  }
}
