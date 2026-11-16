import Foundation
import Intents

/// Public `@objc` registration shim for SiriKit `INPlayMediaIntent`
/// dispatch. Consumer app delegates wire this into their
/// `application(_:handlerFor:)` to route "Hey Siri play X on MyApp"
/// voice utterances through the lib's `PlayMediaIntentHandler`.
///
/// Two consumer integrations are supported:
///
/// **Swift app (with QueuePlayer pod imported):**
/// ```swift
/// func application(
///   _ application: UIApplication,
///   handlerFor intent: INIntent
/// ) -> Any? {
///   return QueuePlayerMediaIntents.handler(forIntent: intent)
/// }
/// ```
///
/// **Expo / cross-module Swift (no QueuePlayer import):**
/// `NSClassFromString("QueuePlayerMediaIntents")` + a
/// `NSSelectorFromString("handlerForIntent:")` dispatch through
/// `NSObject.perform(_:with:)`. The `@objc(<bare-name>)` annotation
/// + the `@objc` static method signature make this resolvable
/// regardless of pod module name.
///
/// Returns `nil` for any intent the lib does not handle, leaving
/// the consumer's `application(_:handlerFor:)` free to fall through
/// to its own intent handlers.
@available(iOS 13.0, *)
@objc(QueuePlayerMediaIntents)
public final class QueuePlayerMediaIntents: NSObject {

  /// Returns the lib's intent handler for `intent`, or `nil` if the
  /// intent type is outside the lib's surface. Consumer wires this
  /// into their `UIApplicationDelegate.application(_:handlerFor:)`. For an
  /// `INPlayMediaIntent` it also asks `QueuePlayerSceneHost` to start React
  /// Native when no React root exists yet.
  @objc(handlerForIntent:)
  public static func handler(forIntent intent: INIntent) -> Any? {
    if intent is INPlayMediaIntent {
      // Siri calls application(_:handlerFor:) on the main queue.
      if Thread.isMainThread {
        MainActor.assumeIsolated { QueuePlayerSceneHost.requestJavaScript() }
      } else {
        DispatchQueue.main.async { QueuePlayerSceneHost.requestJavaScript() }
      }
      return PlayMediaIntentHandler()
    }
    return nil
  }
}
