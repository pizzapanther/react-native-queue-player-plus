import MediaPlayer
import XCTest
@testable import QueuePlayer

/// `RemoteCommands` owns the `MPRemoteCommandCenter.shared()` handler
/// registrations + the per-command `isEnabled` state. Tests pin the
/// install / uninstall lifecycle and the `setSkipCapability` ->
/// `nextTrackCommand.isEnabled` / `previousTrackCommand.isEnabled`
/// mapping. Attempting to fire `MPRemoteCommandCenter` handlers from
/// XCTest would require lib-side SPI we deliberately do not ship.
///
/// The shared command center is process-global. `setUp` resets all
/// seven commands the lib touches, `tearDown` calls `uninstall()` to
/// release any handlers leaked from a crashing test.
final class RemoteCommandsTests: XCTestCase {

  private var commands: RemoteCommands!

  override func setUp() {
    super.setUp()
    resetSharedCenter()
    commands = RemoteCommands()
  }

  override func tearDown() {
    commands.uninstall()
    drainMain()
    commands = nil
    resetSharedCenter()
    super.tearDown()
  }

  // MARK: - install / uninstall

  func testInstallEnablesAllOwnedCommands() {
    let center = MPRemoteCommandCenter.shared()
    commands.install()
    drainMain()
    XCTAssertTrue(center.playCommand.isEnabled)
    XCTAssertTrue(center.pauseCommand.isEnabled)
    XCTAssertTrue(center.togglePlayPauseCommand.isEnabled)
    XCTAssertTrue(center.stopCommand.isEnabled)
    XCTAssertTrue(center.nextTrackCommand.isEnabled)
    XCTAssertTrue(center.previousTrackCommand.isEnabled)
    XCTAssertTrue(center.changePlaybackPositionCommand.isEnabled)
  }

  func testUninstallDisablesAllOwnedCommands() {
    let center = MPRemoteCommandCenter.shared()
    commands.install()
    drainMain()
    commands.uninstall()
    drainMain()
    XCTAssertFalse(center.playCommand.isEnabled)
    XCTAssertFalse(center.pauseCommand.isEnabled)
    XCTAssertFalse(center.togglePlayPauseCommand.isEnabled)
    XCTAssertFalse(center.stopCommand.isEnabled)
    XCTAssertFalse(center.nextTrackCommand.isEnabled)
    XCTAssertFalse(center.previousTrackCommand.isEnabled)
    XCTAssertFalse(center.changePlaybackPositionCommand.isEnabled)
  }

  func testInstallTwiceLeavesEnabledStatesIntact() {
    let center = MPRemoteCommandCenter.shared()
    commands.install()
    drainMain()
    commands.install()
    drainMain()
    XCTAssertTrue(center.playCommand.isEnabled)
    XCTAssertTrue(center.nextTrackCommand.isEnabled)
    commands.uninstall()
    drainMain()
    XCTAssertFalse(center.playCommand.isEnabled)
  }

  func testUninstallWithoutInstallIsNoOp() {
    let center = MPRemoteCommandCenter.shared()
    let priorPlay = center.playCommand.isEnabled
    commands.uninstall()
    drainMain()
    commands.uninstall()
    drainMain()
    XCTAssertEqual(center.playCommand.isEnabled, priorPlay)
  }

  func testUninstallDoesNotTouchUnownedCommands() {
    let center = MPRemoteCommandCenter.shared()
    center.seekForwardCommand.isEnabled = true
    center.skipBackwardCommand.isEnabled = true
    commands.install()
    drainMain()
    commands.uninstall()
    drainMain()
    XCTAssertTrue(center.seekForwardCommand.isEnabled,
                  "seekForward is not lib-owned; uninstall must leave it alone")
    XCTAssertTrue(center.skipBackwardCommand.isEnabled,
                  "skipBackward is not lib-owned; uninstall must leave it alone")
  }

  // MARK: - setSkipCapability

  func testSetSkipCapabilityNoOpWhenNotInstalled() {
    let center = MPRemoteCommandCenter.shared()
    let priorNext = center.nextTrackCommand.isEnabled
    let priorPrev = center.previousTrackCommand.isEnabled
    commands.setSkipCapability(canSkipNext: !priorNext, canSkipPrevious: !priorPrev)
    drainMain()
    XCTAssertEqual(center.nextTrackCommand.isEnabled, priorNext)
    XCTAssertEqual(center.previousTrackCommand.isEnabled, priorPrev)
  }

  func testSetSkipCapabilityFlipsIsEnabledWhenInstalled() {
    let center = MPRemoteCommandCenter.shared()
    commands.install()
    drainMain()
    commands.setSkipCapability(canSkipNext: false, canSkipPrevious: false)
    drainMain()
    XCTAssertFalse(center.nextTrackCommand.isEnabled)
    XCTAssertFalse(center.previousTrackCommand.isEnabled)
    commands.setSkipCapability(canSkipNext: true, canSkipPrevious: true)
    drainMain()
    XCTAssertTrue(center.nextTrackCommand.isEnabled)
    XCTAssertTrue(center.previousTrackCommand.isEnabled)
  }

  func testSetSkipCapabilityNoOpAfterUninstall() {
    let center = MPRemoteCommandCenter.shared()
    commands.install()
    drainMain()
    commands.uninstall()
    drainMain()
    let priorNext = center.nextTrackCommand.isEnabled
    let priorPrev = center.previousTrackCommand.isEnabled
    commands.setSkipCapability(canSkipNext: true, canSkipPrevious: true)
    drainMain()
    XCTAssertEqual(center.nextTrackCommand.isEnabled, priorNext)
    XCTAssertEqual(center.previousTrackCommand.isEnabled, priorPrev)
  }

  // MARK: - Helpers

  /// Reset the seven lib-owned commands on the process-global
  /// `MPRemoteCommandCenter.shared()`. Targets registered by other
  /// tests / debug code are best-effort cleared via `removeTarget(nil)`
  /// (Apple-documented as "remove all targets").
  private func resetSharedCenter() {
    let center = MPRemoteCommandCenter.shared()
    let owned: [MPRemoteCommand] = [
      center.playCommand,
      center.pauseCommand,
      center.togglePlayPauseCommand,
      center.stopCommand,
      center.nextTrackCommand,
      center.previousTrackCommand,
      center.changePlaybackPositionCommand,
    ]
    for cmd in owned {
      cmd.removeTarget(nil)
      cmd.isEnabled = false
    }
  }

  /// Lets the command-centre writes handed to main land. `RemoteCommands`
  /// enqueues them rather than applying them inline, so an assertion taken in
  /// the same turn still sees the previous state.
  private func drainMain() {
    let drained = expectation(description: "main queue drained")
    DispatchQueue.main.async { drained.fulfill() }
    wait(for: [drained], timeout: 2.0)
  }

}
