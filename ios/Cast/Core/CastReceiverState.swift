import Foundation

/// Receiver-driven playback state, protocol-neutral. Chromecast maps
/// `GCKMediaPlayerState` → this; DLNA / Sonos backends map their own
/// equivalents. The lib's `PlayerState` (the JS-facing enum) is derived
/// from this in `CastEventBridge`.
enum RemotePlaybackStateKind: String {
  case idle, loading, playing, paused, buffering, ended, error
}

/// Reason a track ended on the receiver. Maps each platform's idle-
/// reason enum onto one neutral value.
enum RemoteEndReasonKind: String {
  case finished, interrupted, canceled, error
}

/// Receiver-reported media error payload. `code` is platform-native;
/// `message` is the platform-native description when available.
struct RemoteMediaErrorPayload {
  let code: Int
  let message: String?
}
