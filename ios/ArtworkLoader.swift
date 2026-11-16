import Foundation
import UIKit

/// Multi-request artwork loader shared across the CarPlay browse +
/// Now Playing surfaces and the lock-screen / control-center
/// `MPNowPlayingInfoCenter` flow.
///
/// Concurrency model: every `load(uri:completion:)` issues an
/// independent `URLSessionDataTask`. There is no per-loader
/// "single-in-flight" policy — CarPlay's `CPListImageRowItem`
/// rendering needs many image fetches resolved in parallel, and a
/// list scroll cannot tolerate one fetch cancelling the prior. The
/// caller (e.g. [ArtworkResolver] for the now-playing single-slot
/// surface) decides whether late completions should be dropped.
///
/// Caching: `NSCache<NSURL, UIImage>` keyed on the URL. Memory
/// pressure can evict; a re-load after eviction silently re-fetches.
/// The cache is shared across every caller of the same loader
/// instance, so browse-list loads warm subsequent now-playing reads.
///
/// Threading: `load` is called on any thread; completion fires on
/// main. `file://` decodes hop to a utility queue so a multi-MB
/// consumer-supplied bitmap does not hitch the main thread on a
/// track change.
final class ArtworkLoader {

  /// HTTP headers to send on every artwork request. Owner sets from
  /// the live `PlayerConfig.httpHeaders` snapshot. Last write wins.
  /// Reads + writes are NSLock-protected — `load(uri:)` is called
  /// on any thread; `ArtworkResolver` mutates from main while a
  /// fetch may still be in flight on a URLSession queue.
  var configHeaders: [String: String]? {
    get { configLock.withLock { _configHeaders } }
    set { configLock.withLock { _configHeaders = newValue } }
  }

  /// User-agent override for artwork requests. Owner sets from
  /// `PlayerConfig.userAgent`. When nil, URLSession's default UA wins.
  /// Header precedence: `URLRequest.setValue(_:forHTTPHeaderField:)`
  /// is last-write-wins; `User-Agent` is written first then any
  /// matching key in [configHeaders] overrides.
  var configUserAgent: String? {
    get { configLock.withLock { _configUserAgent } }
    set { configLock.withLock { _configUserAgent = newValue } }
  }

  init(session: URLSession = URLSession(configuration: .ephemeral)) {
    self.session = session
  }

  /// Load `uri` to a [UIImage], or `nil` when the URI is missing /
  /// invalid / unreachable. Completion fires on the main queue.
  ///
  /// - `nil` / empty string → `nil` immediately (no fetch).
  /// - `file://` → off-main decode via `UIImage(contentsOfFile:)`.
  /// - `http://` / `https://` → URLSession data task, cached on
  ///   success.
  /// - Any other scheme → `nil` immediately (the lib has no contract
  ///   for `data://`, `blob://`, custom schemes).
  func load(uri: String?, completion: @escaping (UIImage?) -> Void) {
    guard let uri = uri, !uri.isEmpty else {
      DispatchQueue.main.async { completion(nil) }
      return
    }

    if uri.hasPrefix("file://"),
       let nsurl = URL(string: uri) {
      let path = nsurl.path
      DispatchQueue.global(qos: .userInitiated).async {
        let image = UIImage(contentsOfFile: path)
        DispatchQueue.main.async { completion(image) }
      }
      return
    }

    guard let nsurl = URL(string: uri),
          let scheme = nsurl.scheme,
          scheme == "http" || scheme == "https" else {
      DispatchQueue.main.async { completion(nil) }
      return
    }

    if let cached = cache.object(forKey: nsurl as NSURL) {
      DispatchQueue.main.async { completion(cached) }
      return
    }

    var req = URLRequest(url: nsurl)
    req.timeoutInterval = 10
    // Snapshot under the lock so a concurrent setter during fetch
    // build can't interleave the UA write + headers loop.
    let (ua, headers): (String?, [String: String]?) = configLock.withLock {
      (_configUserAgent, _configHeaders)
    }
    if let ua = ua {
      req.setValue(ua, forHTTPHeaderField: "User-Agent")
    }
    if let headers = headers {
      for (key, value) in headers {
        req.setValue(value, forHTTPHeaderField: key)
      }
    }

    let cache = self.cache
    let task = session.dataTask(with: req) { data, _, _ in
      let image: UIImage? = data.flatMap { UIImage(data: $0) }
      if let image = image {
        cache.setObject(image, forKey: nsurl as NSURL)
      }
      DispatchQueue.main.async { completion(image) }
    }
    task.resume()
  }

  /// Drop every cached entry. Owner calls on `destroy()` or whenever
  /// the consumer's auth credentials rotate (a stale cached image
  /// fetched with a now-invalid auth header is still rendered from
  /// the cache; clearing forces a re-fetch with fresh headers).
  func clearCache() {
    cache.removeAllObjects()
  }

  private let cache = NSCache<NSURL, UIImage>()
  private let session: URLSession

  private let configLock = NSLock()
  private var _configHeaders: [String: String]?
  private var _configUserAgent: String?
}
