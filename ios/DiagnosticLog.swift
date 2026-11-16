import Foundation
import os

/// Diagnostic lines for the unified log (subsystem
/// `com.margelo.nitro.queueplayer`), written only while
/// `PlayerConfig.enableDiagnosticLogs` is on. Lines are written at default
/// level, which the system keeps in its on-disk log store, and are marked
/// public so they survive export. Callers therefore pass state, timings and
/// reasons, never track metadata, URLs or consumer ids.
enum DiagnosticLog {

  /// One log category.
  struct Channel {
    fileprivate let logger: Logger

    /// Writes `message` when diagnostic logging is on. The message is not
    /// built when it is off.
    func log(_ message: @autoclosure () -> String) {
      guard DiagnosticLog.isEnabled else { return }
      let text = message()
      logger.log("\(text, privacy: .public)")
    }
  }

  static let nowPlaying = channel("NowPlaying")
  static let audioSession = channel("AudioSession")
  static let remoteCommand = channel("RemoteCommand")
  static let player = channel("Player")
  static let carPlay = channel("CarPlay")
  static let app = channel("App")

  /// Set from `PlayerConfig.enableDiagnosticLogs` at `configure()`; cleared at
  /// `destroy()`. Process-wide: the most recent `configure()` or `destroy()`
  /// of any player decides it. Read from any queue.
  static var isEnabled: Bool {
    get { enabled.withLock { $0 } }
    set { enabled.withLock { $0 = newValue } }
  }

  private static let enabled = OSAllocatedUnfairLock(initialState: false)

  private static func channel(_ category: String) -> Channel {
    Channel(logger: Logger(subsystem: "com.margelo.nitro.queueplayer", category: category))
  }
}
