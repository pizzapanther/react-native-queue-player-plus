import XCTest
@testable import QueuePlayer

/// `PlayerConfig.enableDiagnosticLogs` turns the library's diagnostic log
/// lines on at `configure()`; omitting it or destroying the player turns them off.
final class DiagnosticLogTests: XCTestCase {

  private var player: TrackPlayer!

  override func setUp() async throws {
    try await super.setUp()
    player = TrackPlayer()
  }

  override func tearDown() async throws {
    if let player {
      _ = try await player.destroy().await()
    }
    player = nil
    try await super.tearDown()
  }

  func testConfigureSetsDiagnosticLoggingAndDestroyClearsIt() async throws {
    let player = try XCTUnwrap(self.player)
    _ = try await player.configure(config: PlayerFixtures.config(enableDiagnosticLogs: true)).await()
    XCTAssertTrue(DiagnosticLog.isEnabled)
    _ = try await player.configure(config: PlayerFixtures.config()).await()
    XCTAssertFalse(DiagnosticLog.isEnabled, "omitted means off")
    _ = try await player.configure(config: PlayerFixtures.config(enableDiagnosticLogs: true)).await()
    _ = try await player.destroy().await()
    XCTAssertFalse(DiagnosticLog.isEnabled)
  }

  func testALogMessageIsBuiltOnlyWhileLoggingIsOn() {
    var built = 0
    func message() -> String { built += 1; return "test" }
    DiagnosticLog.isEnabled = false
    DiagnosticLog.player.log(message())
    XCTAssertEqual(built, 0)
    DiagnosticLog.isEnabled = true
    DiagnosticLog.player.log(message())
    DiagnosticLog.isEnabled = false
    XCTAssertEqual(built, 1)
  }
}
