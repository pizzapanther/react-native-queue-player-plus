import AVFoundation
import XCTest
@testable import QueuePlayer

/// The rendering item's audio mix is rebuilt only when a ramp was scheduled.
///
/// Rebuilding it means a fresh processing tap on an item already in flight,
/// which AVPlayer silently drops — so a cancel that had no ramp to unwind must
/// leave the mix it is already rendering alone.
final class CrossfadeMixReapplyTests: XCTestCase {

  func testRemovingItemsDoesNotRebuildTheRenderingItemsMix() {
    let engine = CrossfadeEngine(crossfadeDurationSeconds: 5.0, confinedTo: .main, allowsExternalPlayback: false)
    let provider = CountingMixProvider()
    engine.setAudioMixProvider(provider)
    engine.setItems(items(count: 4), startIndex: 0, startPositionSeconds: 0)
    let afterInstall = provider.buildCount

    // The removal loop a rebuild runs when it replaces a divergent tail.
    for item in engine.allMediaItems.suffix(3) {
      engine.remove(item)
    }

    XCTAssertEqual(
      provider.buildCount, afterInstall,
      "removing queued items must not rebuild the mix of the item playing")
  }

  /// Arming happens from the boundary observer during playback, so the armed
  /// half of this contract is covered by the crossfade demo suites rather than
  /// here; what is reachable is that a cancel with no ramp to unwind leaves the
  /// rendering item's mix as it is.
  func testCancellingWithNothingArmedLeavesTheMixAlone() {
    let engine = CrossfadeEngine(crossfadeDurationSeconds: 5.0, confinedTo: .main, allowsExternalPlayback: false)
    let provider = CountingMixProvider()
    engine.setAudioMixProvider(provider)
    engine.setItems(items(count: 2), startIndex: 0, startPositionSeconds: 0)
    let afterInstall = provider.buildCount

    engine.cancelFade()

    XCTAssertEqual(provider.buildCount, afterInstall)
  }

  private func items(count: Int) -> [AVPlayerItem] {
    (0 ..< count).map { index in
      let item = AVPlayerItem(
        url: URL(string: "http://127.0.0.1:1/\(index).m4a")!)
      item.queueItemId = "id-\(index)"
      return item
    }
  }
}

/// Counts how many times a mix is built, which is what the dropped-tap hazard
/// is really about.
private final class CountingMixProvider: AudioMixProvider {
  private(set) var buildCount = 0

  func audioMix(for item: AVPlayerItem) -> AVAudioMix? {
    buildCount += 1
    return AVMutableAudioMix()
  }

  func noteActiveAsset(_ asset: AVAsset?) {}
}
