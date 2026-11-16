import AVFoundation
import XCTest
@testable import QueuePlayer

/// Provider-layer coverage for the shared `AudioTapProvider`. These
/// tests cover the configuration + bypass + consumer-registration
/// surface.
///
/// Filename note: kept at `EqualizerAudioMixProviderTests.swift`
/// even though the class is `AudioTapProviderTests`. Renaming the
/// file to match the class triggers the Swift batched-compile
/// cxx-interop artifact documented in `NOTES.md` section 6 — alphabetical
/// repositioning shuffles the file into a different translation
/// unit batch and `CustomPresetStoreTests` loses access to lib
/// symbols whose signatures touch Nitro C++-backed types. XCTest
/// discovery is by class name not filename; the mismatch is purely
/// at the file system layer.
final class AudioTapProviderTests: XCTestCase {

  private func makeConsumer(
    wantsTap: @escaping () -> Bool = { false },
    onAudioFrame: @escaping (UnsafePointer<Float>, Int, Double) -> Void = { _, _, _ in },
    onActiveAssetChange: @escaping (AVAsset?) -> Void = { _ in }
  ) -> AudioTapProvider.Consumer {
    return AudioTapProvider.Consumer(
      wantsTap: wantsTap,
      onAudioFrame: onAudioFrame,
      onActiveAssetChange: onActiveAssetChange
    )
  }

  func testConsumerWantingTapGetsAWorkingMixForALoadedLocalAsset() async throws {
    let provider = AudioTapProvider()
    provider.register(makeConsumer(wantsTap: { true }))

    let url = try requireFixture(name: "silence-cbr-128", ext: "mp3")
    let asset = AVURLAsset(url: url)
    // The mix keys its input parameters to an `AVAssetTrack`, so the tracks
    // key has to be loaded before there is anything to key them to.
    let tracks = try await asset.load(.tracks)
    let item = AVPlayerItem(asset: asset)

    let mix = try XCTUnwrap(
      provider.audioMix(for: item),
      "a registered consumer wanting the tap must receive a mix")
    let params = try XCTUnwrap(
      mix.inputParameters.first as? AVMutableAudioMixInputParameters,
      "the mix must carry input parameters for the asset's audio track")
    XCTAssertNotNil(
      params.audioTapProcessor,
      "the input parameters must carry the processing tap — a mix without one "
        + "silently drops ReplayGain, the equalizer and the visualizer")

    let audioTrackID = try XCTUnwrap(
      tracks.first { $0.mediaType == .audio }?.trackID)
    XCTAssertEqual(params.trackID, audioTrackID)
  }

  func testNoConsumersWantingTapReturnsNilEvenForLocalAssets() {
    let provider = AudioTapProvider()
    XCTAssertFalse(provider.isEnabledSnapshot())

    let url = URL(string: "https://example.test/song.mp3")!
    let asset = AVURLAsset(url: url)
    let item = AVPlayerItem(asset: asset)
    XCTAssertNil(provider.audioMix(for: item))
  }

  func testConsumerWantingTapStillReturnsNilForHLS() {
    let provider = AudioTapProvider()
    provider.register(makeConsumer(wantsTap: { true }))

    let url = URL(string: "https://example.test/stream.m3u8")!
    let asset = AVURLAsset(url: url)
    let item = AVPlayerItem(asset: asset)
    XCTAssertNil(provider.audioMix(for: item))
  }

  func testConsumerWantingTapReturnsNilWhenAssetTracksNotLoaded() {
    // A fresh AVURLAsset over a remote URL hasn't loaded `tracks`;
    // the provider returns nil so the engine can retry via
    // refreshAllItemMixes once KVO fires.
    let provider = AudioTapProvider()
    provider.register(makeConsumer(wantsTap: { true }))

    let url = URL(string: "https://example.test/song.mp3")!
    let asset = AVURLAsset(url: url)
    let item = AVPlayerItem(asset: asset)
    XCTAssertNil(provider.audioMix(for: item))
  }

  func testGainsRoundTripThroughTheProvider() {
    let target: [Double] = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]
    let provider = AudioTapProvider(initialGains: target)
    XCTAssertEqual(target, provider.getGains())

    let next: [Double] = [-12, 0, 0, 0, 0, 0, 0, 0, 0, 12]
    provider.setGains(next)
    XCTAssertEqual(next, provider.getGains())
  }

  func testInitialGainsDefaultToFlat() {
    let provider = AudioTapProvider()
    XCTAssertEqual(Array(repeating: 0.0, count: 10), provider.getGains())
  }

  func testActiveAssetChangeFiresForRegisteredConsumers() {
    let provider = AudioTapProvider()
    var receivedAsset: AVAsset?
    provider.register(
      makeConsumer(onActiveAssetChange: { asset in receivedAsset = asset })
    )

    let url = URL(string: "https://example.test/song.mp3")!
    let asset = AVURLAsset(url: url)
    let item = AVPlayerItem(asset: asset)
    _ = provider.audioMix(for: item)
    XCTAssertTrue(receivedAsset === asset)
  }

  func testUnregisterDropsConsumer() {
    let provider = AudioTapProvider()
    var fires = 0
    let consumer = makeConsumer(onActiveAssetChange: { _ in fires += 1 })
    provider.register(consumer)
    let url = URL(string: "https://example.test/song.mp3")!
    let asset = AVURLAsset(url: url)
    let item = AVPlayerItem(asset: asset)
    _ = provider.audioMix(for: item)
    XCTAssertEqual(1, fires)

    provider.unregister(consumer)
    _ = provider.audioMix(for: item)
    XCTAssertEqual(1, fires, "unregistered consumer must not see further events")
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
