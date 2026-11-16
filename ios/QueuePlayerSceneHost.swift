import Foundation
import OSLog
import UIKit

/// Owns the window that hosts the app's single React Native root across phone scenes.
///
/// The config plugin's generated AppDelegate and SceneDelegate reach this class through
/// `NSClassFromString("QueuePlayerSceneHost")` and `perform`, because consumer Swift cannot import
/// the QueuePlayer module. Every entry point is a class method with an explicit Obj-C selector.
///
/// - The AppDelegate observes `javaScriptRequestedNotification`. When `hasReactRoot` is false it
///   starts React Native into an unattached window and registers that window here.
/// - The SceneDelegate registers the window Expo creates on a normal launch, moves an existing root
///   into a newly connected phone scene with `adoptRootIntoScene:`, and refuses a second phone
///   window with `refuseSceneIfDuplicate:`.
///
/// Main thread only. UIKit creates a scene delegate per scene, so the host window lives here, in
/// static state, rather than on any delegate.
@MainActor
@objc(QueuePlayerSceneHost)
final class QueuePlayerSceneHost: NSObject {

  /// Posted when CarPlay connects, or Siri asks for the play-media intent handler, while no React
  /// root exists.
  static let javaScriptRequestedNotification =
    Notification.Name("QueuePlayerJavaScriptRequested")

  private static let logger = Logger(subsystem: "com.queueplayer.lib", category: "SceneHost")

  private static var host: UIWindow?

  /// Set when the scene `host` belongs to disconnects; cleared whenever a new host is recorded. A
  /// scene reads `.unattached` both after it disconnects and before its own connection completes, so
  /// the activation state cannot tell the two apart.
  private static var hostSceneDisconnected = false

  private static var disconnectObserver: NSObjectProtocol?

  /// `persistentIdentifier`s of phone scene sessions refused as a second window. An entry is
  /// removed by `forgetRefusedScene:` when that scene disconnects.
  private static var refusedSessionIds = Set<String>()

  /// True when a host window exists and has a root view controller.
  @objc(hasReactRoot)
  static func hasReactRoot() -> NSNumber {
    NSNumber(value: host?.rootViewController != nil)
  }

  @objc(registerHostWindow:)
  static func registerHostWindow(_ window: UIWindow) {
    setHost(window)
  }

  /// Moves the host window's root view controller into a new window of `scene`, makes it key and
  /// records it as the host. Returns the new window; the host window itself when it already belongs
  /// to `scene`; or nil when there is no root to move or the host window belongs to another
  /// connected scene.
  @objc(adoptRootIntoScene:)
  static func adoptRootIntoScene(_ scene: UIWindowScene) -> UIWindow? {
    guard let old = host, let controller = old.rootViewController else { return nil }
    if let current = connectedHostScene() {
      return current === scene ? old : nil
    }
    old.rootViewController = nil
    old.isHidden = true
    let window = UIWindow(windowScene: scene)
    // RN's Appearance.setColorScheme writes overrideUserInterfaceStyle only to windows of the scenes
    // connected at call time, so a scheme forced before this scene existed lives on the old window.
    window.backgroundColor = old.backgroundColor
    window.overrideUserInterfaceStyle = old.overrideUserInterfaceStyle
    window.tintColor = old.tintColor
    window.rootViewController = controller
    window.makeKeyAndVisible()
    setHost(window)
    logger.info("adopted root into scene \(scene.session.persistentIdentifier, privacy: .public)")
    return window
  }

  /// Returns true, and asks UIKit to destroy `scene`'s session, when `scene` is a phone window
  /// while the host window is in another connected scene. The destruction and the request to bring
  /// the host scene forward run on the next main-queue turn, outside the connect callback.
  @objc(refuseSceneIfDuplicate:)
  static func refuseSceneIfDuplicate(_ scene: UIScene) -> NSNumber {
    guard scene.session.role == .windowApplication,
          let hostScene = connectedHostScene(),
          hostScene !== scene
    else { return NSNumber(value: false) }
    let session = scene.session
    let hostSession = hostScene.session
    refusedSessionIds.insert(session.persistentIdentifier)
    logger.info("refusing second phone scene \(session.persistentIdentifier, privacy: .public)")
    DispatchQueue.main.async {
      UIApplication.shared.requestSceneSessionDestruction(session, options: nil) { error in
        logger.error("scene destruction failed: \(error.localizedDescription, privacy: .public)")
      }
      if #available(iOS 17.0, *) {
        UIApplication.shared.activateSceneSession(
          for: UISceneSessionActivationRequest(session: hostSession)
        ) { error in
          logger.error("host scene activation failed: \(error.localizedDescription, privacy: .public)")
        }
      } else {
        UIApplication.shared.requestSceneSessionActivation(
          hostSession, userActivity: nil, options: nil
        ) { error in
          logger.error("host scene activation failed: \(error.localizedDescription, privacy: .public)")
        }
      }
    }
    return NSNumber(value: true)
  }

  @objc(isRefusedScene:)
  static func isRefusedScene(_ scene: UIScene) -> NSNumber {
    NSNumber(value: refusedSessionIds.contains(scene.session.persistentIdentifier))
  }

  @objc(forgetRefusedScene:)
  static func forgetRefusedScene(_ scene: UIScene) {
    refusedSessionIds.remove(scene.session.persistentIdentifier)
  }

  /// Posts `javaScriptRequestedNotification` unless a React root already exists.
  static func requestJavaScript() {
    guard !hasReactRoot().boolValue else { return }
    logger.info("requesting JavaScript start")
    NotificationCenter.default.post(name: javaScriptRequestedNotification, object: nil)
  }

  private static func setHost(_ window: UIWindow) {
    host = window
    hostSceneDisconnected = false
    guard disconnectObserver == nil else { return }
    disconnectObserver = NotificationCenter.default.addObserver(
      forName: UIScene.didDisconnectNotification, object: nil, queue: nil
    ) { note in
      let disconnected = (note.object as AnyObject?).map(ObjectIdentifier.init)
      // UIKit posts scene life-cycle notifications on the main thread.
      MainActor.assumeIsolated {
        guard let scene = host?.windowScene, disconnected == ObjectIdentifier(scene) else { return }
        hostSceneDisconnected = true
      }
    }
  }

  /// The scene the host window belongs to, or nil when it has none or that scene has disconnected.
  private static func connectedHostScene() -> UIWindowScene? {
    hostSceneDisconnected ? nil : host?.windowScene
  }
}
