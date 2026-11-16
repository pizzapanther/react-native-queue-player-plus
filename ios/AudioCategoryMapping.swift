import AVFoundation

/// Maps `PlayerConfig.audioContentType` + `PlayerConfig.audioCategoryOptions`
/// to the AVAudioSession `Mode` + `CategoryOptions` used during
/// `AudioSession.activate()`.
///
/// Pure function; no instance state. Mirrors `PlaybackErrorMapping`.
///
/// `audioContentType == .speech` triggers two effects:
/// 1. AVAudioSession mode flips to `.spokenAudio` (per Apple HIG —
///    spoken-audio sessions PAUSE under nav-voice instead of ducking).
/// 2. `interruptSpokenAudio` defaults to `true` (interrupt other apps'
///    spoken-audio sessions, mix with their music). Consumer-supplied
///    `interruptSpokenAudio` overrides the default.
enum AudioCategoryMapping {

  /// AVAudioSession mode for the supplied content type. `nil` /
  /// `.music` → `.default`; `.speech` → `.spokenAudio`.
  static func mode(for contentType: AudioContentType?) -> AVAudioSession.Mode {
    if contentType == .speech {
      return .spokenAudio
    }
    return .default
  }

  /// AVAudioSession category options. Each consumer-supplied flag
  /// maps to a single option; `interruptSpokenAudio` defaults
  /// `true` when `contentType == .speech`, `false` otherwise.
  static func options(
    for opts: AudioCategoryOptions?,
    contentType: AudioContentType?
  ) -> AVAudioSession.CategoryOptions {
    var result: AVAudioSession.CategoryOptions = []
    if opts?.duckOthers == true {
      result.insert(.duckOthers)
    }
    if opts?.mixWithOthers == true {
      result.insert(.mixWithOthers)
    }
    let speechDefault = (contentType == .speech)
    let interruptSpokenAudio = opts?.interruptSpokenAudio ?? speechDefault
    if interruptSpokenAudio {
      result.insert(.interruptSpokenAudioAndMixWithOthers)
    }
    return result
  }
}
