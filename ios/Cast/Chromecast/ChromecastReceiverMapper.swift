import Foundation
#if canImport(GoogleCast)
import GoogleCast

/// Maps `GCKDevice` to the Nitro `CastReceiver` struct used by
/// `CastManager.getReceivers()` + the receivers-changed event. ID
/// scheme `chromecast:<unique-id>` namespaces by protocol so the
/// merged receiver list can dedupe across active backends.
final class ChromecastReceiverMapper {

  static func mapToReceiver(_ device: GCKDevice) -> CastReceiver {
    let id = "chromecast:\(device.uniqueID)"
    let name = device.friendlyName ?? device.deviceID
    let iconHint = iconHintFor(device: device)
    return CastReceiver(
      id: id,
      castProtocol: .chromecast,
      name: name,
      iconHint: castDeviceKind(from: iconHint),
      capabilities: CastReceiverCapabilities(
        supportsRemoteVolume: true,
        streamingModel: .pullurl,
        requiresPairing: false
      ),
      protocolVersion: "",
      modelName: device.modelName ?? ""
    )
  }

  private static func iconHintFor(device: GCKDevice) -> String {
    let modelLower = (device.modelName ?? "").lowercased()
    if modelLower.contains("tv") || modelLower.contains("chromecast") { return "tv" }
    if modelLower.contains("home") || modelLower.contains("nest") || modelLower.contains("audio") { return "speaker" }
    if modelLower.contains("display") { return "display" }
    return "unknown"
  }

  private static func castDeviceKind(from hint: String) -> CastDeviceKind {
    switch hint {
    case "tv": return .tv
    case "speaker": return .speaker
    case "display": return .display
    case "phone": return .phone
    default: return .unknown
    }
  }
}
#endif
