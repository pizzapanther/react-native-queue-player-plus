import Intents
import UIKit
import XCTest
@testable import QueuePlayer

/// `QueuePlayerSceneHost` contracts the config plugin's generated AppDelegate and SceneDelegate rely
/// on. The unit tests run without an app host and so without a window scene; adopting a root into a
/// scene and refusing a second phone window are covered by the demo app on the simulator.
@MainActor
final class QueuePlayerSceneHostTests: XCTestCase {

  private var windows: [UIWindow] = []

  override func tearDown() {
    // The host state is static: leave a registered window with no root so no later test sees a
    // React root, and hide every window a test created.
    for window in windows {
      window.rootViewController = nil
      window.isHidden = true
    }
    windows.removeAll()
    QueuePlayerSceneHost.registerHostWindow(UIWindow(frame: .zero))
    super.tearDown()
  }

  private func makeUnattachedWindow(root: UIViewController? = nil) -> UIWindow {
    let window = UIWindow(frame: .zero)
    window.rootViewController = root
    windows.append(window)
    return window
  }

  func testEverySelectorResolvesOnTheClassObject() throws {
    let cls: AnyClass = try XCTUnwrap(NSClassFromString("QueuePlayerSceneHost"))
    let meta: AnyClass = try XCTUnwrap(object_getClass(cls))
    for name in [
      "hasReactRoot", "registerHostWindow:", "adoptRootIntoScene:", "refuseSceneIfDuplicate:",
      "isRefusedScene:", "forgetRefusedScene:",
    ] {
      XCTAssertTrue(meta.instancesRespond(to: NSSelectorFromString(name)), name)
    }
    XCTAssertEqual(
      QueuePlayerSceneHost.javaScriptRequestedNotification.rawValue,
      "QueuePlayerJavaScriptRequested")
  }

  func testHasReactRootReflectsTheHostWindowsRootController() {
    let window = makeUnattachedWindow()
    QueuePlayerSceneHost.registerHostWindow(window)
    XCTAssertFalse(QueuePlayerSceneHost.hasReactRoot().boolValue)

    window.rootViewController = UIViewController()
    XCTAssertTrue(QueuePlayerSceneHost.hasReactRoot().boolValue)
  }

  func testPlayMediaIntentRequestsJavaScriptWhenNoRootExists() {
    QueuePlayerSceneHost.registerHostWindow(makeUnattachedWindow())
    let posted = expectation(
      forNotification: QueuePlayerSceneHost.javaScriptRequestedNotification, object: nil)

    XCTAssertNotNil(QueuePlayerMediaIntents.handler(forIntent: INPlayMediaIntent()))

    wait(for: [posted], timeout: 1)
  }

  func testOtherIntentsDoNotRequestJavaScript() {
    QueuePlayerSceneHost.registerHostWindow(makeUnattachedWindow())
    let posted = expectation(
      forNotification: QueuePlayerSceneHost.javaScriptRequestedNotification, object: nil)
    posted.isInverted = true

    XCTAssertNil(QueuePlayerMediaIntents.handler(forIntent: INSearchForMediaIntent()))

    wait(for: [posted], timeout: 0.2)
  }

  func testPlayMediaIntentDoesNotRequestJavaScriptWhileARootExists() {
    QueuePlayerSceneHost.registerHostWindow(makeUnattachedWindow(root: UIViewController()))
    let posted = expectation(
      forNotification: QueuePlayerSceneHost.javaScriptRequestedNotification, object: nil)
    posted.isInverted = true

    XCTAssertNotNil(QueuePlayerMediaIntents.handler(forIntent: INPlayMediaIntent()))

    wait(for: [posted], timeout: 0.2)
  }
}
