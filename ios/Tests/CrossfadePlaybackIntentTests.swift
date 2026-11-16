import AVFoundation
import XCTest
@testable import QueuePlayer

/// `seek(toIndex:)` and `setItems` resume from the transport's intent, never
/// from a leg's rate or status.
///
/// `replaceCurrentItem` drops the player it is called on to rate 0, so each of
/// these paths has to put the rate back. Reading a leg to decide whether to is
/// wrong in two states the engine is routinely in: mid-fade, where the
/// outgoing leading leg has already stopped while the incoming one plays, and
/// at the end of an item, where the leg sits at rate 0 with the transport
/// still wanting playback. Either reading leaves the queue silently paused.
///
/// What is reachable here is the half that must NOT resume. The resuming half
/// needs a leg genuinely at rate 0 with playback still wanted, and only real
/// playback produces that: measured on the simulator, an item pointed at an
/// unreachable URL holds `timeControlStatus == .waitingToPlayAtSpecifiedRate`
/// indefinitely and never drops its rate, so every assertion built on one is
/// vacuous. The `consecutive-auto-advance` and `queue-end-then-set-queue` demo
/// suites cover both halves on both engines.
final class CrossfadePlaybackIntentTests: XCTestCase {

  func testASeekAfterPauseStaysPaused() {
    let engine = makeEngine(count: 3)
    engine.play()
    engine.pause()

    engine.seek(toIndex: 1, position: 0)

    XCTAssertEqual(
      engine.timeControlStatus, .paused,
      "a paused engine is not resumed by a seek")
  }

  func testAReinstallBeforeAnyPlayStaysPaused() {
    let engine = makeEngine(count: 3)

    engine.setItems(items(count: 3, from: 10), startIndex: 0, startPositionSeconds: 0)

    XCTAssertEqual(
      engine.timeControlStatus, .paused,
      "an engine that was never told to play stays stopped")
  }

  func testASeekAfterStopStaysPaused() {
    let engine = makeEngine(count: 3)
    engine.play()
    engine.stop()

    engine.seek(toIndex: 2, position: 0)

    XCTAssertEqual(engine.timeControlStatus, .paused)
  }

  // MARK: - Helpers

  private func items(count: Int, from base: Int = 0) -> [AVPlayerItem] {
    (0 ..< count).map { offset in
      let index = base + offset
      let item = AVPlayerItem(url: URL(string: "http://127.0.0.1:1/\(index).m4a")!)
      item.queueItemId = "id-\(index)"
      return item
    }
  }

  private func makeEngine(count: Int) -> CrossfadeEngine {
    let engine = CrossfadeEngine(crossfadeDurationSeconds: 5.0, confinedTo: .main, allowsExternalPlayback: false)
    engine.setItems(items(count: count), startIndex: 0, startPositionSeconds: 0)
    engines.append(engine)
    return engine
  }

  private var engines: [CrossfadeEngine] = []

  override func tearDown() {
    engines.forEach { $0.release() }
    engines.removeAll()
    super.tearDown()
  }
}
