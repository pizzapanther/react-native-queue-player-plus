import XCTest
@testable import QueuePlayer

/// Shared fixtures for tests that need a configured `TrackPlayer` with a real
/// playable queue.
enum PlayerFixtures {

  static func config(
    autoRetries: Double? = nil, enableDiagnosticLogs: Bool? = nil,
    allowsExternalPlayback: Bool? = nil
  ) -> PlayerConfig {
    return PlayerConfig(
      httpHeaders: nil, userAgent: nil, placeholderArtworkUri: nil,
      autoRetries: autoRetries, retryBackoffMs: nil, networkTimeoutMs: nil,
      audioContentType: nil, audioCategoryOptions: nil,
      voiceVocabularyDonationEnabled: nil,
      allowsExternalPlayback: allowsExternalPlayback,
      visualizationEnabled: nil, clampSeekToBuffered: nil,
      skipToPreviousBehavior: nil, lookaheadCacheMaxSizeMb: nil,
      lookaheadCacheEvictionPolicy: nil, progressUpdateIntervalMs: nil,
      backgroundProgressUpdateIntervalMs: nil, enableDiagnosticLogs: enableDiagnosticLogs
    )
  }

  static func track(id: String, url: String, isVideo: Bool? = nil) -> TrackItem {
    return TrackItem(
      id: id, url: url, title: nil, artist: nil, album: nil,
      duration: nil, artworkUrl: nil, extras: nil,
      replayGainTrackGain: nil, replayGainTrackPeak: nil,
      replayGainAlbumGain: nil, replayGainAlbumPeak: nil, isVideo: isVideo
    )
  }

  /// Queue entries backed by a bundled local asset, so the queue seats real
  /// playable items without any network fetch or prefetch scheduling.
  static func localTracks(count: Int, for testCase: XCTestCase) throws -> [TrackItem] {
    let url = try fixtureURL(name: "silence-aac-128", ext: "m4a", for: testCase)
    return (0 ..< count).map { track(id: "t\($0)", url: url.absoluteString) }
  }

  /// A file under `Tests/Fixtures`, bundled through `test_spec.resources`.
  /// A miss is a tests-harness `pod install` regression.
  static func fixtureURL(name: String, ext: String, for testCase: XCTestCase) throws -> URL {
    let bundle = Bundle(for: type(of: testCase))
    return try XCTUnwrap(
      bundle.url(forResource: name, withExtension: ext)
        ?? bundle.urls(forResourcesWithExtension: ext, subdirectory: nil)?
          .first(where: { $0.deletingPathExtension().lastPathComponent == name }),
      "fixture \(name).\(ext) not bundled — pod install regression in tests-harness"
    )
  }
}
