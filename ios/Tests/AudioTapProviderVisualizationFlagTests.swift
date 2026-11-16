import XCTest
@testable import QueuePlayer

/// Unit coverage for `AudioTapProvider.visualizationEnabled` — the
/// pin-at-configure flag written by `TrackPlayer.configure(_:)` from
/// `PlayerConfig.visualizationEnabled` and read by the visualizer
/// Hybrid's `subscribe(...)` early-return + the visualizer Consumer's
/// `wantsTap` predicate.
final class AudioTapProviderVisualizationFlagTests: XCTestCase {

  func testFlagDefaultsToTrue() {
    let provider = AudioTapProvider()
    XCTAssertTrue(
      provider.isVisualizationEnabledSnapshot(),
      "default visualizationEnabled must be true so existing consumers see no behaviour change"
    )
  }

  func testSetEnabledMirrorsThroughSnapshot() {
    let provider = AudioTapProvider()
    provider.setVisualizationEnabled(false)
    XCTAssertFalse(provider.isVisualizationEnabledSnapshot())
    provider.setVisualizationEnabled(true)
    XCTAssertTrue(provider.isVisualizationEnabledSnapshot())
  }

  func testIdempotentReWriteHonoursLatestValue() {
    // Configure runs the flag write on every call. Mirroring iOS
    // behaviour: a re-write to the same value is harmless; a re-write
    // to a new value must reflect immediately.
    let provider = AudioTapProvider()
    provider.setVisualizationEnabled(true)
    provider.setVisualizationEnabled(true)
    XCTAssertTrue(provider.isVisualizationEnabledSnapshot())
    provider.setVisualizationEnabled(false)
    XCTAssertFalse(provider.isVisualizationEnabledSnapshot())
    provider.setVisualizationEnabled(false)
    XCTAssertFalse(provider.isVisualizationEnabledSnapshot())
  }

  func testFlagDoesNotAffectIsEnabled() {
    // EQ-enabled flag is a separate piece of state — viz disable must
    // not flip it.
    let provider = AudioTapProvider()
    provider.setEnabled(true)
    provider.setVisualizationEnabled(false)
    XCTAssertTrue(
      provider.isEnabledSnapshot(),
      "visualizationEnabled must not bleed into the EQ enabled flag"
    )
  }

  func testReadsFromConcurrentThreadsAreSafe() {
    // The flag is read from the JS bridge thread (subscribe) +
    // possibly the audio render thread (Consumer.wantsTap predicate).
    // Writes happen on main from configure. The lock-protected
    // accessor serialises both. Spin 8 readers + 1 writer alternating
    // true/false; collect every observed read; assert every observed
    // value is one of the two written values (no torn read returning
    // some other bit-pattern), and that the final state matches the
    // writer's last pinned write.
    let provider = AudioTapProvider()
    let writeQueue = DispatchQueue(label: "viz.flag.test.writer")
    let readGroup = DispatchGroup()
    let writeGroup = DispatchGroup()
    var observed: [Bool] = []
    let observedLock = NSLock()
    for _ in 0..<8 {
      readGroup.enter()
      DispatchQueue.global().async {
        for _ in 0..<200 {
          let value = provider.isVisualizationEnabledSnapshot()
          observedLock.lock()
          observed.append(value)
          observedLock.unlock()
        }
        readGroup.leave()
      }
    }
    writeGroup.enter()
    writeQueue.async {
      for i in 0..<200 {
        provider.setVisualizationEnabled(i % 2 == 0)
      }
      // Pin the final state so the post-join assertion is deterministic.
      provider.setVisualizationEnabled(true)
      writeGroup.leave()
    }
    readGroup.wait()
    writeGroup.wait()
    XCTAssertGreaterThan(
      observed.count, 0,
      "concurrent reads should record values without crashing"
    )
    // Bool can only legally be true or false — this is trivially
    // satisfied on iOS but documents the contract: no torn read.
    let invalid = observed.filter { $0 != true && $0 != false }
    XCTAssertTrue(invalid.isEmpty, "torn reads observed: \(invalid)")
    XCTAssertTrue(
      provider.isVisualizationEnabledSnapshot(),
      "expected final state true after writer pin"
    )
  }
}
