import XCTest
@testable import QueuePlayer

/// `CarPlayBridge` tests cover only the registration/dispose/clear
/// state machine — the slot semantics. The
/// Android-side `BrowseCallbackRegistry` exposes suspending
/// `await*Callback()` helpers because its dispatch sites coroutine-
/// poll for cold-start race recovery; iOS uses NotificationCenter
/// (`CarPlayBrowseDataProvider.didUpdateNotification`) + the
/// `CarPlayCoordinator.pending` queue for the same shape, so the
/// iOS bridge stays slot-only.
final class CarPlayBridgeTests: XCTestCase {

  func testFreshBridgeHasNoCallbacks() {
    let bridge = CarPlayBridge()
    // The browse / playFromId slot reads return a Promise of a
    // Nitrogen-bridged type, which Swift cxx-interop excludes from
    // `@testable import`. Their slots use the identical `Slot<>`
    // machinery asserted here for connect/disconnect.
    XCTAssertNil(bridge.carConnectCallback())
    XCTAssertNil(bridge.carDisconnectCallback())
  }

  func testOnCarConnectRegistersAndFires() {
    let bridge = CarPlayBridge()
    var fired = 0
    _ = bridge.registerOnCarConnect { fired += 1 }
    bridge.carConnectCallback()?()
    XCTAssertEqual(fired, 1)
  }

  func testOnCarConnectReplaceSwapsTheActiveCallback() {
    let bridge = CarPlayBridge()
    var firstFired = 0
    var secondFired = 0
    _ = bridge.registerOnCarConnect { firstFired += 1 }
    _ = bridge.registerOnCarConnect { secondFired += 1 }
    bridge.carConnectCallback()?()
    XCTAssertEqual(firstFired, 0,
                   "second register replaces the first; the first never fires again")
    XCTAssertEqual(secondFired, 1)
  }

  func testDisposerClearsTheRegisteredSlot() {
    let bridge = CarPlayBridge()
    let dispose = bridge.registerOnCarConnect { /* no-op */ }
    XCTAssertNotNil(bridge.carConnectCallback())
    dispose()
    XCTAssertNil(bridge.carConnectCallback())
  }

  func testStaleDisposerLeavesActiveCallbackAlone() {
    let bridge = CarPlayBridge()
    let staleDispose = bridge.registerOnCarConnect { /* first */ }
    var secondFired = 0
    _ = bridge.registerOnCarConnect { secondFired += 1 }

    // The stale disposer (from the first registration) must not clear
    // the second callback's slot — the token check guards this.
    staleDispose()
    bridge.carConnectCallback()?()
    XCTAssertEqual(secondFired, 1)
  }

  func testClearWipesEveryRegisteredSlot() {
    let bridge = CarPlayBridge()
    _ = bridge.registerOnCarConnect { }
    _ = bridge.registerOnCarDisconnect { }

    bridge.clear()

    XCTAssertNil(bridge.carConnectCallback())
    XCTAssertNil(bridge.carDisconnectCallback())
  }

  func testRegistrationsAreSlotIsolated() {
    let bridge = CarPlayBridge()
    var connectFired = 0
    var disconnectFired = 0
    _ = bridge.registerOnCarConnect { connectFired += 1 }
    _ = bridge.registerOnCarDisconnect { disconnectFired += 1 }
    bridge.carConnectCallback()?()
    bridge.carDisconnectCallback()?()
    XCTAssertEqual(connectFired, 1)
    XCTAssertEqual(disconnectFired, 1)
  }
}
