import AVFoundation
import AVKit
import UIKit

/// Wraps `AVAudioSession.routeChangeNotification` and translates the
/// current audio route into the lib's Nitro `CastRoute` shape.
/// Survives backgrounding via re-subscribe (iOS may tear down
/// background notification observers).
///
/// `startObserving()` delivers the current route synchronously to
/// freshly-subscribed listeners (mirrors Android StateFlow replay).
final class AirPlayRouteState {

  /// Callback fired on every route change + once synchronously on
  /// `startObserving()`. CastManager sets this to forward to JS
  /// listeners + update its cached currentRoute.
  var onRouteChanged: ((CastRoute) -> Void)?

  /// Companion callback firing on every route change + once
  /// synchronously on subscribe, delivering the full audio-output
  /// snapshot (BT, wired, USB, HDMI, AirPlay, etc.). CastManager
  /// forwards to the JS `onAudioRouteChange` listener registry.
  var onAudioRouteChanged: ((AudioRoute) -> Void)?

  private var isObserving = false

  func startObserving() {
    guard !isObserving else { return }
    isObserving = true
    NotificationCenter.default.addObserver(
      self,
      selector: #selector(handleRouteChange),
      name: AVAudioSession.routeChangeNotification,
      object: nil
    )
    patchRouteSharingPolicy()
    deliverCurrentRoute()
  }

  func stopObserving() {
    guard isObserving else { return }
    isObserving = false
    NotificationCenter.default.removeObserver(
      self,
      name: AVAudioSession.routeChangeNotification,
      object: nil
    )
  }

  /// Snapshot the current cast-style route. Callable any time; doesn't
  /// require an active observer.
  func currentRoute() -> CastRoute {
    let route = AVAudioSession.sharedInstance().currentRoute
    guard let output = route.outputs.first else {
      return Self.localRoute()
    }
    return Self.castRouteFrom(port: output)
  }

  /// Snapshot the current audio output (broader surface than
  /// `currentRoute` — covers BT, wired, USB, HDMI, etc.).
  func currentAudioRoute() -> AudioRoute {
    let route = AVAudioSession.sharedInstance().currentRoute
    guard let output = route.outputs.first else {
      return Self.localAudioRoute()
    }
    return Self.audioRouteFrom(port: output)
  }

  /// Present the system `AVRoutePickerView` sheet programmatically by
  /// dispatching a tap to the picker's internal UIButton. Used by
  /// `CastManager.showSystemPicker()`.
  ///
  /// Workaround: `AVRoutePickerView` has no public API to present its
  /// sheet, so this walks the view's subviews for its `UIButton` and
  /// fires its `.touchUpInside` actions. The walk is recursive rather
  /// than a direct child lookup because the button's depth has changed
  /// between iOS releases. When no button is found the call is a no-op;
  /// a consumer that needs a guaranteed sheet renders its own
  /// `AVRoutePickerView`, which is the supported pattern.
  /// TODO: monitor upstream for fixes — https://github.com/react-native-video/react-native-video/issues/3119
  /// (the same subview walk, and the request for a public entry point).
  func showSystemPicker() {
    DispatchQueue.main.async {
      let picker = self.getOrCreatePicker()
      if let button = Self.firstButton(in: picker) {
        button.sendActions(for: .touchUpInside)
      }
    }
  }

  /// Recursive first-button search — Apple has moved the internal
  /// button between top-level and nested children across iOS releases.
  private static func firstButton(in view: UIView) -> UIButton? {
    for subview in view.subviews {
      if let button = subview as? UIButton { return button }
      if let nested = firstButton(in: subview) { return nested }
    }
    return nil
  }

  // MARK: - Internal

  @objc private func handleRouteChange(_ notification: Notification) {
    deliverCurrentRoute()
  }

  private func deliverCurrentRoute() {
    onRouteChanged?(currentRoute())
    onAudioRouteChanged?(currentAudioRoute())
  }

  /// WWDC '23 guidance: any app intending long-form audio playback
  /// should set `.longFormAudio` so the OS routes to the user's last-
  /// active long-form output (AirPods, AirPlay 2, CarPlay) on cold
  /// start. Idempotent — bails if already set.
  private func patchRouteSharingPolicy() {
    let session = AVAudioSession.sharedInstance()
    if session.routeSharingPolicy == .longFormAudio { return }
    do {
      try session.setCategory(
        session.category,
        mode: session.mode,
        policy: .longFormAudio,
        options: session.categoryOptions
      )
    } catch {
      // Best-effort — falls back to default routing.
    }
  }

  private var hiddenPicker: AVRoutePickerView?

  private func getOrCreatePicker() -> AVRoutePickerView {
    let currentWindow = Self.currentKeyWindow()
    // Recreate the cached picker if the window it was attached to
    // is no longer the foreground key window (multi-scene teardown,
    // new scene launch). Otherwise its `sendActions` dispatches into
    // a discarded view hierarchy.
    if let p = hiddenPicker, p.window === currentWindow, currentWindow != nil {
      return p
    }
    // The picker must be in the view hierarchy with a non-zero frame
    // and laid-out subviews for `sendActions(for:.touchUpInside)` on
    // the internal button to present the system picker sheet. Hidden
    // views (`isHidden = true`) skip layout in UIKit, so the internal
    // button isn't created — `firstButton(in:)` returns nil and the
    // tap silently no-ops. `alpha = 0` + a small frame keeps the
    // picker invisible to the user while UIKit still lays out its
    // private subview tree.
    hiddenPicker?.removeFromSuperview()
    let picker = AVRoutePickerView(frame: CGRect(x: 0, y: 0, width: 40, height: 40))
    picker.alpha = 0
    picker.isUserInteractionEnabled = true
    picker.prioritizesVideoDevices = false
    if let window = currentWindow {
      window.addSubview(picker)
      picker.setNeedsLayout()
      picker.layoutIfNeeded()
    }
    hiddenPicker = picker
    return picker
  }

  /// Foreground-active scene's key window — the right presenter for a
  /// programmatically-triggered system picker. `connectedScenes.first`
  /// is an arbitrary scene (could be a background scene on iPad with
  /// split-view), and `windows.first` is an arbitrary window inside
  /// that scene; either can mis-route the picker presentation. Filter
  /// to foreground-active + key.
  private static func currentKeyWindow() -> UIWindow? {
    return UIApplication.shared.connectedScenes
      .compactMap { $0 as? UIWindowScene }
      .first(where: { $0.activationState == .foregroundActive })?
      .windows
      .first(where: { $0.isKeyWindow })
  }

  // MARK: - Route mapping

  static func castRouteFrom(port: AVAudioSessionPortDescription) -> CastRoute {
    let proto = mapProtocol(port.portType)
    let iconHint = mapDeviceKind(port.portType, portName: port.portName)
    return CastRoute(
      castProtocol: proto,
      receiverId: proto == .local ? "" : port.uid,
      name: port.portName,
      iconHint: iconHint,
      capabilities: CastReceiverCapabilities(
        // iOS handles AirPlay volume via hardware buttons; the app
        // does not have programmatic volume control over AirPlay.
        supportsRemoteVolume: false,
        streamingModel: .pushpcm,
        requiresPairing: false
      ),
      protocolVersion: ""
    )
  }

  static func localRoute() -> CastRoute {
    CastRoute(
      castProtocol: .local,
      receiverId: "",
      name: "Phone speaker",
      iconHint: .phone,
      capabilities: CastReceiverCapabilities(
        supportsRemoteVolume: false,
        streamingModel: .pushpcm,
        requiresPairing: false
      ),
      protocolVersion: ""
    )
  }

  static func audioRouteFrom(port: AVAudioSessionPortDescription) -> AudioRoute {
    let kind = mapAudioRouteKind(port.portType)
    return AudioRoute(
      kind: kind,
      name: port.portName,
      identifier: port.uid,
      isExternal: kind != .speaker && kind != .receiver,
      isSystemManaged: true
    )
  }

  static func localAudioRoute() -> AudioRoute {
    AudioRoute(
      kind: .speaker,
      name: "Phone speaker",
      identifier: "",
      isExternal: false,
      isSystemManaged: true
    )
  }

  private static func mapProtocol(_ portType: AVAudioSession.Port) -> CastProtocol {
    switch portType {
    case .airPlay: return .airplay
    default: return .local
    }
  }

  /// Map an `AVAudioSession.Port` to the lib's `AudioRouteKind`. Every
  /// real-world consumer-music output port is covered; rarely-seen
  /// pro-audio routes (`.AVB`, `.displayPort`, `.thunderbolt`) + the
  /// I/O-only ports (`.builtInMic`, `.continuityMicrophone`, etc.)
  /// fall through to `.unknown` so the picker still renders the port
  /// name without forcing a kind-classification we can't justify.
  /// Enum case names are Nitrogen-lowercased (e.g. `bluetootha2dp`) —
  /// JS reads the original camelCase via the string-union surface.
  private static func mapAudioRouteKind(_ portType: AVAudioSession.Port) -> AudioRouteKind {
    switch portType {
    case .builtInSpeaker: return .speaker
    case .builtInReceiver: return .receiver
    case .headphones, .lineOut: return .wired
    case .usbAudio: return .usb
    case .HDMI: return .hdmi
    case .bluetoothA2DP: return .bluetootha2dp
    case .bluetoothLE: return .bluetoothle
    case .bluetoothHFP: return .bluetoothhfp
    case .carAudio: return .carplay
    case .airPlay: return .airplay
    default: return .unknown
    }
  }

  private static func mapDeviceKind(
    _ portType: AVAudioSession.Port,
    portName: String
  ) -> CastDeviceKind {
    switch portType {
    case .builtInSpeaker, .builtInReceiver: return .phone
    case .headphones, .bluetoothA2DP, .bluetoothLE, .bluetoothHFP: return .speaker
    case .airPlay:
      let lower = portName.lowercased()
      return (lower.contains("tv") || lower.contains("apple tv")) ? .tv : .speaker
    case .HDMI: return .tv
    case .carAudio: return .speaker
    default: return .unknown
    }
  }
}
