import Foundation

/// Protocol-neutral media item handed to cast backends for playback.
/// Cast backends (Chromecast, future DLNA / Sonos) consume the typed
/// fields directly — no reflection, no NSDictionary casts inside the
/// backend. Translation from the Nitro `TrackItem` happens at the
/// bridge boundary (`CastMediaItem.from(track:)`), so the protocol-
/// specific backends don't depend on the Nitro JS-bridge contract.
struct CastMediaItem {
  /// Source URL. `file://` → served via `LocalMediaServer` for
  /// receivers that need HTTP. `http(s)://` → passed through. Other
  /// schemes rejected by the backend.
  let url: URL
  let title: String?
  let artist: String?
  let album: String?
  let artwork: URL?
  /// Milliseconds. `nil` when unknown.
  let durationMs: Int64?
  /// Explicit MIME type; when `nil` the backend infers from URL
  /// extension via `MimeTypes`.
  let contentType: String?

  init(
    url: URL,
    title: String? = nil,
    artist: String? = nil,
    album: String? = nil,
    artwork: URL? = nil,
    durationMs: Int64? = nil,
    contentType: String? = nil
  ) {
    self.url = url
    self.title = title
    self.artist = artist
    self.album = album
    self.artwork = artwork
    self.durationMs = durationMs
    self.contentType = contentType
  }
}
