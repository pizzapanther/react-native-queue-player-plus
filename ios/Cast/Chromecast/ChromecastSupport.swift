import Foundation
#if canImport(GoogleCast)
import GoogleCast
#endif

/// Top-level install entry point for the iOS Chromecast backend.
/// Consumer apps call `ChromecastSupport.installWithDefaults()` from
/// their `AppDelegate.application(_:didFinishLaunchingWithOptions:)`
/// (the lib's Expo plugin emits the call site when `{ chromecast:
/// true }` is configured).
@objc(ChromecastSupport)
public final class ChromecastSupport: NSObject {

  /// Google's Default Media Receiver — handles MP4/MP3/HLS with no
  /// custom receiver code.
  @objc public static let defaultReceiverApplicationID = "CC1AD845"

  /// Info.plist key the lib's Expo plugin writes when the consumer
  /// pins a host-app receiver ID at build time.
  @objc public static let infoPlistReceiverApplicationIDKey =
    "QueuePlayerChromecastReceiverApplicationID"

  /// Reads receiver app ID from Info.plist, falls back to default.
  /// Stable selector for the AppDelegate snippet's NSSelectorFromString
  /// dispatch.
  @objc public static func installWithDefaults() {
    let configured = (Bundle.main.infoDictionary?[infoPlistReceiverApplicationIDKey] as? String)?
      .trimmingCharacters(in: .whitespacesAndNewlines)
    let receiverID = (configured?.isEmpty == false ? configured! : defaultReceiverApplicationID)
    install(receiverApplicationID: receiverID)
  }

  /// Installs and configures `GCKCastContext` with the supplied
  /// receiver application ID. Must be called on the main thread.
  /// Idempotent — calling twice is a no-op.
  @objc public static func install(receiverApplicationID: String = defaultReceiverApplicationID) {
    #if canImport(GoogleCast)
    guard Thread.isMainThread else {
      DispatchQueue.main.async { install(receiverApplicationID: receiverApplicationID) }
      return
    }

    if GCKCastContext.isSharedInstanceInitialized() { return }

    let discoveryCriteria = GCKDiscoveryCriteria(applicationID: receiverApplicationID)
    let options = GCKCastOptions(discoveryCriteria: discoveryCriteria)
    options.physicalVolumeButtonsWillControlDeviceVolume = true
    options.suspendSessionsWhenBackgrounded = false
    // Defer discovery entirely to `Cast.startDiscovery()` (typically
    // the picker open). With autostart on, the SDK opens a
    // `_googlecast._tcp.` Bonjour browser + multicast UDP socket
    // synchronously inside `setSharedInstanceWith`; the first
    // multicast send triggers the iOS Local Network permission prompt
    // during a window the user has not asked for cast yet — wasteful
    // for cast-disabled users + a known UX regression.
    // `ChromecastBackend.startDiscovery` calls
    // `discoveryManager.startDiscovery()` explicitly when JS asks.
    options.disableDiscoveryAutostart = true

    GCKCastContext.setSharedInstanceWith(options)
    #endif
  }
}
