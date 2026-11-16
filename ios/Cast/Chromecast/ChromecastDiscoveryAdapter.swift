import Foundation
#if canImport(GoogleCast)
import GoogleCast

/// Adapter over `GCKDiscoveryManagerListener` that collapses granular
/// add/update/remove events into a single `onUpdate(devices)` snapshot
/// matching the Android `CastBackend.startDiscovery` contract.
@objc final class ChromecastDiscoveryAdapter: NSObject, GCKDiscoveryManagerListener {

  /// Fires on every change with the full current device list.
  var onUpdate: (([GCKDevice]) -> Void)?

  func didUpdateDeviceList() {
    let manager = GCKCastContext.sharedInstance().discoveryManager
    var devices: [GCKDevice] = []
    for i in 0..<manager.deviceCount {
      devices.append(manager.device(at: i))
    }
    onUpdate?(devices)
  }

  func didStartDiscovery(forDeviceCategory deviceCategory: String) {}
  func willUpdateDeviceList() {}
  func didInsert(_ device: GCKDevice, at index: UInt) {}
  func didRemoveDevice(at index: UInt) {}
}
#endif
