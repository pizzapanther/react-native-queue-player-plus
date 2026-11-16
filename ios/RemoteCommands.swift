import Foundation
import MediaPlayer

/// Owns the `MPRemoteCommandCenter.shared()` handler registrations
/// that drive lock-screen / control-center / CarPlay / external-
/// keyboard transport. Each handler dispatches into the owner's
/// `onCommand` closure, where `TrackPlayer` stamps
/// `pendingStateChangeReason = .system` and routes through the
/// existing public transport methods.
///
/// Threading: callable from any queue. Every public method hands its work to
/// main, which owns `MPRemoteCommandCenter`. Command handlers still arrive
/// on main — `dispatch` asserts that — and the consumer hops from there.
/// Handlers registered with `addTarget` fire on main per Apple's docs.
final class RemoteCommands {

  enum Command {
    case play
    case pause
    case togglePlayPause
    case stop
    case nextTrack
    case previousTrack
    case skipForward(seconds: Double)
    case skipBackward(seconds: Double)
    case changePlaybackPosition(seconds: Double)
  }

  /// Set by the owner during `configure()` and cleared during `destroy()`,
  /// both on the owner's player queue; invoked synchronously on main from
  /// the handler, so the slot is read and written under a lock. Owner is
  /// responsible for stamping the state-change reason BEFORE routing
  /// into transport calls.
  var onCommand: ((Command) -> Void)? {
    get { commandLock.withLock { commandCallback } }
    set { commandLock.withLock { commandCallback = newValue } }
  }
  private let commandLock = NSLock()
  private var commandCallback: ((Command) -> Void)?

  /// Drive `MPRemoteCommandCenter` `isEnabled` for the skip commands.
  /// Greys out the lock-screen / CarPlay buttons that would no-op
  /// (e.g. previous at queue head with `repeatMode: 'off'`). No-op
  /// when not installed — the lib must not write to the shared
  /// command center after `uninstall()` has released ownership.
  func setSkipCapability(canSkipNext: Bool, canSkipPrevious: Bool) {
    RemoteCommands.onMain { self.setSkipCapabilityOnMain(canSkipNext: canSkipNext, canSkipPrevious: canSkipPrevious) }
  }

  @MainActor
  private func setSkipCapabilityOnMain(canSkipNext: Bool, canSkipPrevious: Bool) {
    // Only greys out the next/previous-TRACK buttons. In `.interval` mode the
    // lock-screen shows skip-forward/back, which always work (seek within the
    // current track), so queue-boundary capability doesn't apply.
    guard installed, skipMode == .track else { return }
    let center = MPRemoteCommandCenter.shared()
    center.nextTrackCommand.isEnabled = canSkipNext
    center.previousTrackCommand.isEnabled = canSkipPrevious
  }

  /// Choose track-skip vs interval-skip for the lock-screen / control-center
  /// skip slots, and set the jump intervals (seconds). The two modes are
  /// mutually exclusive: `.track` enables next/previous-track, `.interval`
  /// enables skip-forward/back by the configured seconds. Re-applies live so a
  /// consumer can toggle at runtime. The lib only owns the skip-interval
  /// commands while in `.interval` mode.
  func setRemoteControls(mode: RemoteSkipMode, forwardInterval: Double, backwardInterval: Double) {
    RemoteCommands.onMain {
      self.setRemoteControlsOnMain(
        mode: mode, forwardInterval: forwardInterval, backwardInterval: backwardInterval)
    }
  }

  @MainActor
  private func setRemoteControlsOnMain(
    mode: RemoteSkipMode, forwardInterval: Double, backwardInterval: Double
  ) {
    self.skipMode = mode
    self.forwardInterval = forwardInterval
    self.backwardInterval = backwardInterval
    guard installed else { return }
    applySkipMode()
  }

  /// Reconcile the shared command center with `skipMode` + intervals. Called
  /// from `install()` and `setRemoteControls`.
  private func applySkipMode() {
    let center = MPRemoteCommandCenter.shared()
    switch skipMode {
    case .interval:
      center.skipForwardCommand.preferredIntervals = [NSNumber(value: forwardInterval)]
      center.skipBackwardCommand.preferredIntervals = [NSNumber(value: backwardInterval)]
      if skipForwardTarget == nil {
        skipForwardTarget = center.skipForwardCommand.addTarget { [weak self] event in
          guard let self = self,
                let event = event as? MPSkipIntervalCommandEvent else { return .commandFailed }
          self.dispatch(.skipForward(seconds: event.interval))
          return .success
        }
      }
      if skipBackwardTarget == nil {
        skipBackwardTarget = center.skipBackwardCommand.addTarget { [weak self] event in
          guard let self = self,
                let event = event as? MPSkipIntervalCommandEvent else { return .commandFailed }
          self.dispatch(.skipBackward(seconds: event.interval))
          return .success
        }
      }
      center.skipForwardCommand.isEnabled = true
      center.skipBackwardCommand.isEnabled = true
      center.nextTrackCommand.isEnabled = false
      center.previousTrackCommand.isEnabled = false
    case .track:
      // Release the skip-interval commands (only if we owned them) and restore
      // next/previous-track. Actual grey-out follows via `setSkipCapability`.
      releaseSkipCommands(center)
      center.nextTrackCommand.isEnabled = true
      center.previousTrackCommand.isEnabled = true
    }
  }

  /// Remove our skip-interval targets and disable those commands — but only the
  /// ones we registered, so a co-resident audio framework's handlers are left
  /// untouched (politeness matches the next/prev-only default).
  private func releaseSkipCommands(_ center: MPRemoteCommandCenter) {
    if let t = skipForwardTarget {
      center.skipForwardCommand.removeTarget(t)
      center.skipForwardCommand.isEnabled = false
      skipForwardTarget = nil
    }
    if let t = skipBackwardTarget {
      center.skipBackwardCommand.removeTarget(t)
      center.skipBackwardCommand.isEnabled = false
      skipBackwardTarget = nil
    }
  }

  /// Register handlers + enable the supported commands. Idempotent.
  func install() {
    RemoteCommands.onMain { self.installOnMain() }
  }

  @MainActor
  private func installOnMain() {
    guard !installed else { return }
    installed = true
    DiagnosticLog.remoteCommand.log("install")
    let center = MPRemoteCommandCenter.shared()

    playTarget = center.playCommand.addTarget { [weak self] _ in
      guard let self = self else { return .commandFailed }
      self.dispatch(.play)
      return .success
    }
    pauseTarget = center.pauseCommand.addTarget { [weak self] _ in
      guard let self = self else { return .commandFailed }
      self.dispatch(.pause)
      return .success
    }
    togglePlayPauseTarget = center.togglePlayPauseCommand.addTarget { [weak self] _ in
      guard let self = self else { return .commandFailed }
      self.dispatch(.togglePlayPause)
      return .success
    }
    stopTarget = center.stopCommand.addTarget { [weak self] _ in
      guard let self = self else { return .commandFailed }
      self.dispatch(.stop)
      return .success
    }
    nextTrackTarget = center.nextTrackCommand.addTarget { [weak self] _ in
      guard let self = self else { return .commandFailed }
      self.dispatch(.nextTrack)
      return .success
    }
    previousTrackTarget = center.previousTrackCommand.addTarget { [weak self] _ in
      guard let self = self else { return .commandFailed }
      self.dispatch(.previousTrack)
      return .success
    }
    changePlaybackPositionTarget =
      center.changePlaybackPositionCommand.addTarget { [weak self] event in
        guard let self = self else { return .commandFailed }
        guard let event = event as? MPChangePlaybackPositionCommandEvent else {
          DiagnosticLog.remoteCommand.log("rejected changePlaybackPosition: unexpected event type")
          return .commandFailed
        }
        self.dispatch(.changePlaybackPosition(seconds: event.positionTime))
        return .success
      }

    center.playCommand.isEnabled = true
    center.pauseCommand.isEnabled = true
    center.togglePlayPauseCommand.isEnabled = true
    center.stopCommand.isEnabled = true
    center.changePlaybackPositionCommand.isEnabled = true

    // Reconcile the skip slots to the current mode. `.track` (default) enables
    // next/previous-track and leaves skipForward/skipBackward untouched;
    // `.interval` owns + enables skipForward/skipBackward and disables next/prev.
    applySkipMode()
  }

  /// Tear down handler registrations + disable the commands the lib
  /// owns. Idempotent. Never touches commands the lib didn't enable
  /// in `install()`.
  func uninstall() {
    RemoteCommands.onMain { self.uninstallOnMain() }
  }

  @MainActor
  private func uninstallOnMain() {
    guard installed else { return }
    installed = false
    DiagnosticLog.remoteCommand.log("uninstall")
    let center = MPRemoteCommandCenter.shared()

    if let t = playTarget { center.playCommand.removeTarget(t) }
    if let t = pauseTarget { center.pauseCommand.removeTarget(t) }
    if let t = togglePlayPauseTarget { center.togglePlayPauseCommand.removeTarget(t) }
    if let t = stopTarget { center.stopCommand.removeTarget(t) }
    if let t = nextTrackTarget { center.nextTrackCommand.removeTarget(t) }
    if let t = previousTrackTarget { center.previousTrackCommand.removeTarget(t) }
    if let t = changePlaybackPositionTarget {
      center.changePlaybackPositionCommand.removeTarget(t)
    }
    releaseSkipCommands(center)
    playTarget = nil
    pauseTarget = nil
    togglePlayPauseTarget = nil
    stopTarget = nil
    nextTrackTarget = nil
    previousTrackTarget = nil
    changePlaybackPositionTarget = nil

    center.playCommand.isEnabled = false
    center.pauseCommand.isEnabled = false
    center.togglePlayPauseCommand.isEnabled = false
    center.stopCommand.isEnabled = false
    center.nextTrackCommand.isEnabled = false
    center.previousTrackCommand.isEnabled = false
    center.changePlaybackPositionCommand.isEnabled = false
  }

  /// Hands `body` to main, which owns `MPRemoteCommandCenter` and therefore
  /// the target registrations and `installed` flag below. Always asynchronous,
  /// so an install and a capability update issued in order land in order.
  private static func onMain(_ body: @escaping @MainActor () -> Void) {
    DispatchQueue.main.async { MainActor.assumeIsolated(body) }
  }

  private func dispatch(_ command: Command) {
    assert(Thread.isMainThread,
           "RemoteCommands.dispatch must run on the main thread — " +
           "MPRemoteCommandCenter handlers are documented as main-queue.")
    DiagnosticLog.remoteCommand.log("received \(String(describing: command))")
    onCommand?(command)
  }

  private var installed = false
  private var playTarget: Any?
  private var pauseTarget: Any?
  private var togglePlayPauseTarget: Any?
  private var stopTarget: Any?
  private var nextTrackTarget: Any?
  private var previousTrackTarget: Any?
  private var changePlaybackPositionTarget: Any?
  private var skipForwardTarget: Any?
  private var skipBackwardTarget: Any?

  private var skipMode: RemoteSkipMode = .track
  private var forwardInterval: Double = 15
  private var backwardInterval: Double = 15
}
