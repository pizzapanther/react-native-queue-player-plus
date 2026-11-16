import Foundation
#if canImport(GoogleCast)
import GoogleCast

/// Translate a `CastMediaItem` into a Cast SDK `GCKMediaInformation`
/// ready for `GCKRemoteMediaClient.loadMedia(_:with:)`.
///
/// URL decision table:
/// - `file://` (or no scheme) → register via `MediaServerHandle`, use
///   the returned LAN `http://` URL
/// - `http(s)://` → pass through
/// - anything else → throw `unsupportedScheme`
enum MediaInfoBuilder {

  enum BuildError: Error, LocalizedError {
    case unsupportedScheme(String)
    case missingLANAddress
    case localFileNotFound(String)
    case serverUnavailable

    var errorDescription: String? {
      switch self {
      case .unsupportedScheme(let s): return "Unsupported URL scheme '\(s)' for Chromecast"
      case .missingLANAddress: return "No LAN address available — cannot serve local file"
      case .localFileNotFound(let p): return "Local file does not exist: \(p)"
      case .serverUnavailable: return "MediaServerHandle returned nil URL — server not running?"
      }
    }
  }

  static func build(
    item: CastMediaItem,
    handle: MediaServerHandle,
    lanAddress: String?
  ) throws -> GCKMediaInformation {
    let scheme = item.url.scheme?.lowercased()
    let resolvedURL: URL
    switch scheme {
    case "http", "https":
      resolvedURL = item.url
    case "file", nil:
      let path = item.url.path
      guard FileManager.default.fileExists(atPath: path) else {
        throw BuildError.localFileNotFound(path)
      }
      guard let address = lanAddress else {
        throw BuildError.missingLANAddress
      }
      let fileURL = item.url.scheme == "file" ? item.url : URL(fileURLWithPath: path)
      guard let served = handle.register(
        fileURL: fileURL,
        lanAddress: address,
        contentType: item.contentType
      ) else {
        throw BuildError.serverUnavailable
      }
      resolvedURL = served
    default:
      throw BuildError.unsupportedScheme(scheme ?? "")
    }

    let metadata = GCKMediaMetadata(metadataType: .musicTrack)
    if let t = item.title { metadata.setString(t, forKey: kGCKMetadataKeyTitle) }
    if let a = item.artist {
      metadata.setString(a, forKey: kGCKMetadataKeyArtist)
      metadata.setString(a, forKey: kGCKMetadataKeyAlbumArtist)
    }
    if let al = item.album {
      metadata.setString(al, forKey: kGCKMetadataKeyAlbumTitle)
    }
    if let artwork = item.artwork,
       let s = artwork.scheme?.lowercased(),
       s == "http" || s == "https" {
      metadata.addImage(GCKImage(url: artwork, width: 480, height: 480))
    }

    let contentType = item.contentType ?? inferContentType(from: resolvedURL)
    let builder = GCKMediaInformationBuilder(contentURL: resolvedURL)
    builder.streamType = .buffered
    builder.contentType = contentType
    builder.metadata = metadata
    if let ms = item.durationMs, ms > 0 {
      builder.streamDuration = TimeInterval(ms) / 1000.0
    }
    return builder.build()
  }

  private static func inferContentType(from url: URL) -> String {
    let ext = url.pathExtension.lowercased()
    switch ext {
    case "mp3": return "audio/mpeg"
    case "m4a", "mp4": return "audio/mp4"
    case "aac": return "audio/aac"
    case "flac": return "audio/flac"
    case "ogg", "oga", "opus": return "audio/ogg"
    case "wav", "wave": return "audio/x-wav"
    // Some Chromecast receivers reject `audio/*` as a content type
    // hint and fail the load; `application/octet-stream` is the
    // safer fallback when the extension doesn't disambiguate.
    default: return "application/octet-stream"
    }
  }
}
#endif
