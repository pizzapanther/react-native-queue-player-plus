import UIKit

/// Bundled fallback artwork used by the lock-screen / NowPlayingInfo
/// surface when the consumer-supplied `track.artworkUrl` is missing
/// or unreachable. Lazy-loaded once at first use; cached for the
/// process lifetime.
///
/// Resource resolution follows the CocoaPods `resource_bundles` rule:
/// the lib's podspec packages `ios/Resources/RNQP-placeholder.jpg`
/// into `QueuePlayer.bundle` inside the consuming app. We locate
/// the bundle relative to this class's framework, then load the
/// JPEG. Last-resort fallback is a 1×1 transparent UIImage so the
/// surface NEVER hands `nil` to MPNowPlayingInfoCenter — null artwork
/// is the failure mode that drops notification controls on some
/// Android skins (mirror that defence on iOS too).
enum PlaceholderArtwork {

  /// The active placeholder: the consumer-supplied image when one is
  /// configured + loadable, otherwise the bundled RNQP placeholder.
  static var image: UIImage { lock.withLock { customImage } ?? bundledImage }

  /// Set (or clear) the consumer-supplied placeholder from
  /// `PlayerConfig.placeholderArtworkUri`. Loaded from a local `file://`
  /// image the same way the bundled placeholder is (`UIImage(contentsOfFile:)`).
  /// Only `file://` is honoured (scheme match is case-insensitive) — the
  /// built-in is a local file, and a remote URL would break offline +
  /// change behaviour; anything else (or an unloadable file) leaves the
  /// bundled placeholder in effect. Decode is synchronous, so a very large
  /// consumer image briefly blocks the `configure` call. Called from
  /// `configure` on the player queue; the readers are on main.
  static func setCustom(uri: String?) {
    guard let uri = uri, !uri.isEmpty,
          let url = URL(string: uri), url.scheme?.lowercased() == "file" else {
      lock.withLock { customImage = nil }
      return
    }
    let image = UIImage(contentsOfFile: url.path)
    lock.withLock { customImage = image }
  }

  /// Written from `configure()` on the player queue, read wherever the
  /// now-playing surfaces build their snapshot.
  private static let lock = NSLock()
  private static var customImage: UIImage?
  private static let bundledImage: UIImage = loadImage()

  private static func loadImage() -> UIImage {
    let frameworkBundle = Bundle(for: BundleToken.self)
    if let resourceURL = frameworkBundle.url(forResource: "QueuePlayer", withExtension: "bundle"),
       let resourceBundle = Bundle(url: resourceURL),
       let path = resourceBundle.path(forResource: "RNQP-placeholder", ofType: "jpg"),
       let img = UIImage(contentsOfFile: path) {
      return img
    }
    // Static-library fallback: resources sit at the framework root
    // when the host app links the lib statically (no .bundle suffix).
    if let path = frameworkBundle.path(forResource: "RNQP-placeholder", ofType: "jpg"),
       let img = UIImage(contentsOfFile: path) {
      return img
    }
    return makeTransparentFallback()
  }

  private static func makeTransparentFallback() -> UIImage {
    let renderer = UIGraphicsImageRenderer(size: CGSize(width: 1, height: 1))
    return renderer.image { ctx in
      UIColor.clear.setFill()
      ctx.fill(CGRect(x: 0, y: 0, width: 1, height: 1))
    }
  }

  private final class BundleToken {}
}
