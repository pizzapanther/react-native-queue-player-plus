import AVFoundation
import XCTest
@testable import QueuePlayer

/// The crossfade leading leg installs its EQ / visualizer / ReplayGain tap-mix
/// before it renders. AVPlayer honours `audioMix` only before an item renders,
/// and `AVPlayerItem.status == .readyToPlay` does not imply the asset's `tracks`
/// key has loaded — the provider yields nil until it is — so a leg that wants
/// the tap is held at rate 0 until the tracks load and the mix is installed,
/// then resumed. Miss that window and the tap is absent for the whole track.
///
/// The incoming-leg attach, which is reached only through a real crossfade's
/// arm chain, is covered end-to-end by the demo `boundary-audio-chain` suite
/// (it drives an actual boundary on device); a unit test that drives the real
/// fade is too sensitive to a loaded CI simulator's arm-chain timing.
@MainActor
final class CrossfadeEngineIncomingMixTests: XCTestCase {

  /// A tap-wanting consumer holds a freshly-seated leading leg at rate 0 until
  /// its `tracks` key loads and the tap-bearing mix is installed, then resumes
  /// it — the leg never renders untapped, and it does not strand at rate 0.
  func testLeadingLegHeldForTapUntilMixInstalledThenResumes() async throws {
    let url = try requireFixture(name: "silence-cbr-128", ext: "mp3")
    let provider = TapWantingTracksGatedMixProvider()
    let engine = CrossfadeEngine(
      crossfadeDurationSeconds: 0.5, confinedTo: .main, allowsExternalPlayback: false)
    defer { engine.release() }
    engine.setAudioMixProvider(provider)
    engine.setItems(
      [makeItem(url: url, id: "leading"), makeItem(url: url, id: "incoming")],
      startIndex: 0,
      startPositionSeconds: 0
    )

    // A fresh `AVURLAsset` starts `.unknown`, so the leading item is not yet
    // `.readyToPlay` here. A tap is wanted, so play() must hold the leg at
    // rate 0 rather than start it rendering before the mix is installed.
    engine.play()
    XCTAssertEqual(
      engine.timeControlStatus, .paused,
      "a tap-wanting leading leg must be held at rate 0 until its mix is "
        + "installed, not started rendering by play()"
    )

    // The `.readyToPlay` observer loads tracks, installs the tap-bearing mix,
    // then resumes the held leg — it must reach `.playing`, not strand.
    try await waitUntil("the held leading leg resumes playing") {
      engine.timeControlStatus == .playing
    }
    let leading = try XCTUnwrap(engine.currentMediaItem)
    let installed = leading.audioMix?.inputParameters.first
      as? AVMutableAudioMixInputParameters
    XCTAssertTrue(
      installed === provider.issued["leading"],
      "the leading leg must carry the provider's tap-bearing mix once playing; "
        + "a nil or foreign mix means it resumed untapped"
    )
  }

  // MARK: - helpers

  /// Built without `tracks` in `automaticallyLoadedAssetKeys`, so the asset's
  /// tracks key is unloaded until something explicitly loads it — the state a
  /// remote item is in when the arm chain picks it up.
  private func makeItem(url: URL, id: String) -> AVPlayerItem {
    let item = AVPlayerItem(asset: AVURLAsset(url: url))
    item.queueItemId = id
    return item
  }

  private func waitUntil(
    _ what: String,
    timeout: TimeInterval = 20,
    _ condition: () -> Bool
  ) async throws {
    let deadline = Date().addingTimeInterval(timeout)
    while Date() < deadline {
      if condition() { return }
      try await Task.sleep(nanoseconds: 20_000_000)
    }
    XCTFail("timed out waiting for \(what)")
  }

  private func requireFixture(name: String, ext: String) throws -> URL {
    let bundle = Bundle(for: type(of: self))
    if let url = bundle.url(forResource: name, withExtension: ext) {
      return url
    }
    let candidates = bundle.urls(forResourcesWithExtension: ext, subdirectory: nil) ?? []
    if let match = candidates.first(where: { $0.deletingPathExtension().lastPathComponent == name }) {
      return match
    }
    // Fixtures are bundled via `test_spec.resources` in QueuePlayer.podspec.
    // A miss means the test-harness pod install regressed — that's a real
    // infrastructure failure, not a conditionally-inapplicable test.
    return try XCTUnwrap(
      nil as URL?,
      "fixture \(name).\(ext) not bundled — pod install regression in tests-harness"
    )
  }
}

/// Like `AudioTapProvider`, reports a live tap consumer so the crossfade engine
/// holds a not-yet-ready leading leg for the tap rather than resuming it
/// immediately. Issues a real tap-bearing mix once tracks load.
private final class TapWantingTracksGatedMixProvider: AudioMixProvider {
  private(set) var issued: [String: AVMutableAudioMixInputParameters] = [:]

  func anyConsumerWantsTap() -> Bool { true }

  func audioMix(for item: AVPlayerItem) -> AVAudioMix? {
    guard case .loaded(let tracks) = item.asset.status(of: .tracks),
          let track = tracks.first(where: { $0.mediaType == .audio }) else {
      return nil
    }
    let params = AVMutableAudioMixInputParameters(track: track)
    let mix = AVMutableAudioMix()
    mix.inputParameters = [params]
    issued[item.queueItemId ?? ""] = params
    return mix
  }
}
