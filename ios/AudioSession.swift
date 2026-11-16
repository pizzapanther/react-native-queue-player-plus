import AVFoundation

/// AVAudioSession lifecycle + interruption + route-change handling.
///
/// Split out of TrackPlayer so the audio-session behaviours
/// (activate / deactivate / observe interruptions / observe route
/// changes) are isolated from queue + player state. Consumer owns
/// an instance, wires the `on*` callbacks to the outbound event
/// bus, and calls `activate` + `installObservers` during its own
/// lifecycle.
///
/// Interruption semantics (per Apple's AVAudioSession interruption docs):
///
/// - `.began`: system is telling us to pause (incoming call, Siri,
///   FaceTime, alarm). Callers typically call `pause()` on the
///   player and emit a state change with reason `.interruption`.
/// - `.ended` + `.shouldResume` option: system is telling us the
///   interruption ended and we should resume. Callers call `play()`
///   and emit `.interruption` again.
/// - `.ended` without `.shouldResume`: user resolved the
///   interruption in a way that shouldn't restart music (e.g.
///   they declined the call then did something else). No resume.
///
/// Route-change semantics (covers the "headphones unplugged" case):
///
/// - `.oldDeviceUnavailable`: previous output route went away
///   (unplugged headphones, disconnected BT). Pause per Apple HIG
///   — users expect playback to stop, not blast over the speaker.
/// - `.newDeviceAvailable`: new route arrived (BT connected). No
///   automatic action; callers may choose to emit a state change.
/// - Other reasons (category change, wake/sleep, override): emit
///   the reason to the caller for logging; no default action.
final class AudioSession {

  /// AVAudioSession interruption kinds translated to a library
  /// enum so TrackPlayer doesn't need to import AVFoundation
  /// symbols from its event-dispatch path.
  enum InterruptionKind {
    case began
    case endedShouldResume
    case endedShouldNotResume
  }

  /// Route-change reasons we care about. Omits the no-op cases
  /// (`.unknown`, `.override`, `.wakeFromSleep`, …) so the callback
  /// only fires on events the caller should react to.
  ///
  /// `.categoryChange` is intentionally NOT surfaced — our own
  /// `activate()` triggers a category change, so exposing it would
  /// require callers to distinguish their own set-category from
  /// external category changes (which are rare in practice).
  enum RouteChangeKind {
    case oldDeviceUnavailable
    case newDeviceAvailable
  }

  /// The three callback slots are written by the owner on its player queue
  /// (`configure()` / `destroy()`) and read on main by the notification
  /// observers — and `onError` on the player queue as well — so each read and
  /// write goes through one lock.
  private let callbackLock = NSLock()
  private var interruptionCallback: ((InterruptionKind) -> Void)?
  private var routeChangeCallback: ((RouteChangeKind) -> Void)?
  private var errorCallback: ((AudioSessionError) -> Void)?

  /// Fires on AVAudioSession interruption notifications, on main.
  var onInterruption: ((InterruptionKind) -> Void)? {
    get { callbackLock.withLock { interruptionCallback } }
    set { callbackLock.withLock { interruptionCallback = newValue } }
  }

  /// Fires on AVAudioSession route-change notifications for the
  /// subset of reasons the caller should react to, on main.
  var onRouteChange: ((RouteChangeKind) -> Void)? {
    get { callbackLock.withLock { routeChangeCallback } }
    set { callbackLock.withLock { routeChangeCallback = newValue } }
  }

  /// Fires when an AVAudioSession lifecycle call (`setCategory`,
  /// `setActive(true)`, `setActive(false)`) throws. Failure is
  /// non-fatal at the audio-session level — playback itself hasn't
  /// started yet, so the player state machine should not transition
  /// to `.error` on these (do not call `emitState` from this callback).
  var onError: ((AudioSessionError) -> Void)? {
    get { callbackLock.withLock { errorCallback } }
    set { callbackLock.withLock { errorCallback = newValue } }
  }

  private var interruptionObserver: NSObjectProtocol?
  private var routeChangeObserver: NSObjectProtocol?
  private var diagnosticObservers: [NSObjectProtocol] = []

  /// Configure the shared AVAudioSession for playback. `.playback`
  /// category is fixed (keeps the session active in background +
  /// plays over the silent switch — the music-app baseline). The
  /// caller supplies `mode` + `options` from the `PlayerConfig`
  /// translation in `AudioCategoryMapping`. Activating the session
  /// signals the system to route audio to the output.
  ///
  /// Cold-start route-sharing (AirPods / AirPlay 2 / CarPlay) is
  /// driven by the Info.plist `AVInitialRouteSharingPolicy =
  /// LongFormAudio` key set by the `app.plugin.js` Expo config
  /// plugin — this lets iOS pre-establish routing BEFORE the app's
  /// first Swift code runs. The runtime `setCategory` call here
  /// uses the default route-sharing policy so the simulator (which
  /// has no long-form audio device configured) renders audio
  /// through its built-in audio output as expected.
  ///
  /// Errors are NSLog'd + emitted through `onError` (set by the
  /// caller in `wireAudioSession` to route into the JS `onError`
  /// listener registry as a non-fatal `PlaybackError`). Failure
  /// modes that surface here are user-visible: route conflicts,
  /// another app holding the session exclusively during a phone
  /// call, etc.
  func activate(
    mode: AVAudioSession.Mode = .default,
    options: AVAudioSession.CategoryOptions = []
  ) {
    guard configureCategory(mode: mode, options: options) else { return }
    activateIfNeeded()
  }

  /// Apply the playback category without claiming the session. Returns whether
  /// it succeeded, since activating after a failed category is pointless.
  ///
  /// Category and activation are separable on purpose: the category is what
  /// route sharing and the interruption observers need, while activation is
  /// what actually takes audio away from whatever else is playing. Doing both
  /// at configure time silenced another app's audio the moment a consumer
  /// launched, before the user had pressed anything.
  @discardableResult
  func configureCategory(
    mode: AVAudioSession.Mode = .default,
    options: AVAudioSession.CategoryOptions = []
  ) -> Bool {
    do {
      try AVAudioSession.sharedInstance().setCategory(.playback, mode: mode, options: options)
      DiagnosticLog.audioSession.log("category set mode=\(mode.rawValue) options=\(options.rawValue)")
      return true
    } catch {
      let desc = String(describing: error)
      NSLog("[AudioSession] setCategory(.playback) failed: %@", desc)
      onError?(.setCategoryFailed(message: desc, nativeCode: (error as NSError).code))
      // If setCategory failed, setActive is highly likely to fail too — no
      // value in chaining a second error for the same root cause.
      return false
    }
  }

  /// The system took the session for an interruption. A later
  /// `activateIfNeeded` claims it again: the session is no longer ours
  /// whatever this object recorded before the interruption.
  func noteInterrupted() {
    DiagnosticLog.audioSession.log("noteInterrupted")
    isActive = false
  }

  /// Claim the audio session. Idempotent: repeated calls after the first are
  /// no-ops, so every playback entry point can call it without bookkeeping.
  func activateIfNeeded() {
    guard !isActive else {
      DiagnosticLog.audioSession.log("activate skipped: already active")
      return
    }
    do {
      try AVAudioSession.sharedInstance().setActive(true)
      isActive = true
      DiagnosticLog.audioSession.log("activated route=\(Self.outputPorts(AVAudioSession.sharedInstance().currentRoute))")
    } catch {
      let desc = String(describing: error)
      DiagnosticLog.audioSession.log("activate failed code=\((error as NSError).code)")
      NSLog("[AudioSession] setActive(true) failed: %@", desc)
      onError?(.setActiveFailed(message: desc, nativeCode: (error as NSError).code))
    }
  }

  /// Whether this object has claimed the session. Cleared on deactivate so a
  /// later play re-claims it.
  private var isActive = false

  /// Deactivate the session + notify other apps they can resume.
  /// Consumers call this during `destroy` so e.g. a paused music
  /// app or podcast player can auto-resume. Same error-logging
  /// rationale as `activate`.
  func deactivate() {
    DiagnosticLog.audioSession.log("deactivate")
    isActive = false
    do {
      try AVAudioSession.sharedInstance().setActive(
        false, options: [.notifyOthersOnDeactivation])
    } catch {
      let desc = String(describing: error)
      DiagnosticLog.audioSession.log("deactivate failed code=\((error as NSError).code)")
      NSLog("[AudioSession] setActive(false) failed: %@", desc)
      onError?(.deactivateFailed(message: desc, nativeCode: (error as NSError).code))
    }
  }

  /// Subscribe to AVAudioSession interruption + route-change
  /// notifications. Safe to call repeatedly — previous observers
  /// are torn down first. Observer bodies dispatch to main before
  /// invoking the public `on*` callbacks so the caller's handlers
  /// observe a consistent main-thread contract.
  func installObservers() {
    tearDownObservers()

    let center = NotificationCenter.default
    let session = AVAudioSession.sharedInstance()

    interruptionObserver = center.addObserver(
      forName: AVAudioSession.interruptionNotification,
      object: session,
      queue: .main
    ) { [weak self] notification in
      self?.handleInterruption(notification)
    }

    routeChangeObserver = center.addObserver(
      forName: AVAudioSession.routeChangeNotification,
      object: session,
      queue: .main
    ) { [weak self] notification in
      self?.handleRouteChange(notification)
    }

    // Logged only: the system's own view of the session and media services,
    // to compare with the lib's `isActive` in a diagnostic capture. No queue,
    // so the posting thread never waits on main.
    func logOnly(_ name: Notification.Name, _ line: @escaping (Notification) -> String) {
      diagnosticObservers.append(
        center.addObserver(forName: name, object: session, queue: nil) { notification in
          DiagnosticLog.audioSession.log(line(notification))
        })
    }
    logOnly(AVAudioSession.mediaServicesWereLostNotification) { _ in "system: media services lost" }
    logOnly(AVAudioSession.mediaServicesWereResetNotification) { _ in "system: media services reset" }
    // Declared only in the iOS 27 SDK. Swift 6.4 is the first compiler shipped with
    // that SDK (Xcode 27), so earlier Xcodes compile the block out.
    #if compiler(>=6.4)
    if #available(iOS 27.0, *) {
      logOnly(AVAudioSession.didBecomeActiveNotification) { _ in "system: session became active" }
      logOnly(AVAudioSession.didBecomeInactiveNotification) { notification in
        let context = notification.userInfo?[AVAudioSession.deactivationContextKey]
          as? AVAudioSession.DeactivationContext
        let source = context.map { String($0.source.rawValue) } ?? "-"
        let reason = context?.interruptionContext.map { String($0.reason.rawValue) } ?? "-"
        return "system: session became inactive source=\(source) interruptionReason=\(reason)"
      }
      logOnly(AVAudioSession.resumptionRecommendationNotification) { notification in
        let context = notification.userInfo?[AVAudioSession.resumptionContextKey]
          as? AVAudioSession.ResumptionContext
        return "system: resumption recommendation=\(context.map { String($0.recommendation.rawValue) } ?? "-")"
      }
    }
    #endif
  }

  func tearDownObservers() {
    if let observer = interruptionObserver {
      NotificationCenter.default.removeObserver(observer)
      interruptionObserver = nil
    }
    if let observer = routeChangeObserver {
      NotificationCenter.default.removeObserver(observer)
      routeChangeObserver = nil
    }
    diagnosticObservers.forEach { NotificationCenter.default.removeObserver($0) }
    diagnosticObservers.removeAll()
  }

  private func handleInterruption(_ notification: Notification) {
    guard let userInfo = notification.userInfo,
          let rawType = userInfo[AVAudioSessionInterruptionTypeKey] as? UInt,
          let type = AVAudioSession.InterruptionType(rawValue: rawType) else {
      return
    }
    DiagnosticLog.audioSession.log(Self.interruptionLine(rawType, userInfo: userInfo))

    switch type {
    case .began:
      onInterruption?(.began)
    case .ended:
      if let rawOptions = userInfo[AVAudioSessionInterruptionOptionKey] as? UInt {
        let options = AVAudioSession.InterruptionOptions(rawValue: rawOptions)
        onInterruption?(options.contains(.shouldResume)
          ? .endedShouldResume
          : .endedShouldNotResume)
      } else {
        onInterruption?(.endedShouldNotResume)
      }
    @unknown default:
      break
    }
  }

  private static func interruptionLine(_ rawType: UInt, userInfo: [AnyHashable: Any]) -> String {
    let options = (userInfo[AVAudioSessionInterruptionOptionKey] as? UInt).map(String.init) ?? "-"
    let reason = (userInfo[AVAudioSessionInterruptionReasonKey] as? UInt).map(String.init) ?? "-"
    return "interruption type=\(rawType) options=\(options) reason=\(reason)"
  }

  private static func outputPorts(_ route: AVAudioSessionRouteDescription) -> String {
    route.outputs.map(\.portType.rawValue).joined(separator: ",")
  }

  private static func routeChangeLine(_ rawReason: UInt, userInfo: [AnyHashable: Any]) -> String {
    let previous = (userInfo[AVAudioSessionRouteChangePreviousRouteKey] as? AVAudioSessionRouteDescription)
      .map(outputPorts) ?? "-"
    return "routeChange reason=\(rawReason) from=\(previous) to=\(outputPorts(AVAudioSession.sharedInstance().currentRoute))"
  }

  private func handleRouteChange(_ notification: Notification) {
    guard let userInfo = notification.userInfo,
          let rawReason = userInfo[AVAudioSessionRouteChangeReasonKey] as? UInt,
          let reason = AVAudioSession.RouteChangeReason(rawValue: rawReason) else {
      return
    }
    DiagnosticLog.audioSession.log(Self.routeChangeLine(rawReason, userInfo: userInfo))

    switch reason {
    case .oldDeviceUnavailable:
      onRouteChange?(.oldDeviceUnavailable)
    case .newDeviceAvailable:
      onRouteChange?(.newDeviceAvailable)
    default:
      // .unknown, .override, .wakeFromSleep, .categoryChange (see
      // RouteChangeKind doc), .noSuitableRouteForCategory,
      // .routeConfigurationChange — no default action.
      break
    }
  }
}
