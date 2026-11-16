import AVFoundation
import XCTest
@testable import QueuePlayer

/// Wiring coverage for the iOS `Equalizer` HybridObject.
/// Targets the synchronous internal core.
final class EqualizerHybridTests: XCTestCase {

  private var defaults: UserDefaults!
  private var suiteName: String!

  override func setUp() {
    super.setUp()
    suiteName = "test.equalizerHybrid.\(UUID().uuidString)"
    defaults = UserDefaults(suiteName: suiteName)!
  }

  override func tearDown() {
    defaults.removePersistentDomain(forName: suiteName)
    defaults = nil
    suiteName = nil
    super.tearDown()
  }

  private func makeEqualizer() -> Equalizer {
    return Equalizer(
      provider: AudioTapProvider(),
      presetStore: CustomPresetStore(defaults: defaults)
    )
  }

  func testInitialStateIsDisabledWithFlatGains() {
    let eq = makeEqualizer()
    XCTAssertFalse(try eq.isEnabled())
    let bands = try! eq.getBands()
    XCTAssertEqual(10, bands.count)
    XCTAssertTrue(bands.allSatisfy { $0.gainDb == 0 })
  }

  func testGetPresetsReturns16BuiltinsWhenNoCustom() {
    let eq = makeEqualizer()
    let presets = try! eq.getPresets()
    XCTAssertEqual(16, presets.count)
    XCTAssertTrue(presets.allSatisfy { $0.builtin })
  }

  func testApplyPresetInternalRockUpdatesBandGains() {
    let eq = makeEqualizer()
    XCTAssertTrue(eq.applyPresetInternal(name: "Rock"))
    let bands = try! eq.getBands()
    XCTAssertEqual(5.0, bands[0].gainDb, accuracy: 0.0001)
    XCTAssertEqual(3.0, bands[1].gainDb, accuracy: 0.0001)
  }

  func testApplyPresetInternalUnknownNameIsNoOp() {
    let eq = makeEqualizer()
    XCTAssertFalse(eq.applyPresetInternal(name: "DoesNotExist"))
    let bands = try! eq.getBands()
    XCTAssertTrue(bands.allSatisfy { $0.gainDb == 0 })
  }

  func testApplyPresetUnknownNameRejectsPromise() async throws {
    let eq = makeEqualizer()
    let promise = try eq.applyPreset(name: "Bogus")
    do {
      _ = try await promise.await()
      XCTFail("expected applyPreset(\"Bogus\") to reject")
    } catch {
      XCTAssertTrue(
        "\(error)".contains("Equalizer preset not found: Bogus"),
        "unexpected error message: \(error)"
      )
    }
  }

  func testSaveCustomPresetRejectsBuiltinName() async throws {
    let eq = makeEqualizer()
    let promise = try eq.saveCustomPreset(name: "Flat")
    do {
      _ = try await promise.await()
      XCTFail("expected saveCustomPreset(\"Flat\") to reject")
    } catch {
      XCTAssertTrue(
        "\(error)".contains("Cannot overwrite built-in preset: Flat"),
        "unexpected error message: \(error)"
      )
    }
  }

  func testSetBandGainInternalRoutesToProvider() {
    let eq = makeEqualizer()
    eq.setBandGainInternal(index: 3, gainDb: 5)
    XCTAssertEqual(5.0, try! eq.getBands()[3].gainDb, accuracy: 0.0001)
    // Provider master state should reflect the same gain.
    XCTAssertEqual(5.0, eq.provider.getGains()[3], accuracy: 0.0001)
  }

  func testSetBandGainInternalClampsOutOfSpec() {
    let eq = makeEqualizer()
    eq.setBandGainInternal(index: 0, gainDb: 99)
    eq.setBandGainInternal(index: 1, gainDb: -99)
    let bands = try! eq.getBands()
    XCTAssertEqual(12.0, bands[0].gainDb, accuracy: 0.0001)
    XCTAssertEqual(-12.0, bands[1].gainDb, accuracy: 0.0001)
  }

  func testSetEnabledInternalMirrorsProviderAndFiresListeners() {
    let eq = makeEqualizer()
    var received: [Bool] = []
    // Registration auto-fires once with current state (default false).
    _ = try! eq.onEnabledChange { received.append($0) }
    received.removeAll()
    eq.setEnabledInternal(true)
    eq.setEnabledInternal(false)
    XCTAssertEqual([true, false], received)
    XCTAssertFalse(eq.provider.isEnabledSnapshot())
  }

  func testSetEnabledInternalIdempotentDoesNotFire() {
    let eq = makeEqualizer()
    var received: [Bool] = []
    _ = try! eq.onEnabledChange { received.append($0) }
    // Discard the auto-fire-on-registration entry.
    received.removeAll()
    eq.setEnabledInternal(false)
    XCTAssertEqual([], received)
  }

  func testOnBandChangeFiresWithSnapshotOnMutation() {
    let eq = makeEqualizer()
    var fired: [EqualizerBand]?
    _ = try! eq.onBandChange { fired = $0 }
    eq.setBandGainInternal(index: 0, gainDb: 4)
    XCTAssertEqual(10, fired?.count)
    XCTAssertEqual(4.0, fired?[0].gainDb ?? 0, accuracy: 0.0001)
  }

  func testCurrentReferencePublishedOnConstruction() {
    let eq = makeEqualizer()
    XCTAssertTrue(Equalizer.current === eq)
  }

  func testApplyPresetInternalAppliesCustomPresetByName() async throws {
    let eq = makeEqualizer()
    // Seed the band gains we want the saved preset to capture, then
    // persist them under a known custom name via the public async
    // surface (matches how JS callers reach `saveCustomPreset`).
    eq.setBandGainInternal(index: 0, gainDb: 6)
    eq.setBandGainInternal(index: 5, gainDb: -3)
    _ = try await eq.saveCustomPreset(name: "TestEqualizerSuite").await()
    // Mutate the live gains so we can prove the apply restores the
    // saved snapshot rather than reading back a stale buffer.
    eq.setBandGainInternal(index: 0, gainDb: 0)
    eq.setBandGainInternal(index: 5, gainDb: 0)
    XCTAssertTrue(eq.applyPresetInternal(name: "TestEqualizerSuite"))
    let bands = try eq.getBands()
    XCTAssertEqual(6.0, bands[0].gainDb, accuracy: 0.0001)
    XCTAssertEqual(-3.0, bands[5].gainDb, accuracy: 0.0001)
  }

  // MARK: - Engine refresh wiring

  /// Wiring the EQ to a PlaybackEngine MUST also register the
  /// provider with the engine. The audible-EQ path on iOS depends
  /// on this so the engine knows whom to ask for `audioMix(for:)`.
  func testWireToPlaybackEngineRegistersProvider() {
    let eq = makeEqualizer()
    let stub = RecordingEngineStub()
    eq.wireToPlaybackEngine(stub)
    XCTAssertTrue(stub.lastSetProvider === eq.provider)
  }

  /// When the user toggles EQ enabled, currently-playing
  /// AVPlayerItems must have their audioMix re-attached. Without
  /// this, the active track keeps its previously-nil audioMix and
  /// the listener hears no change. The Equalizer dispatches the
  /// refresh on the main queue, so the test waits for that hop.
  func testSetEnabledTriggersEngineRefresh() {
    let eq = makeEqualizer()
    let stub = RecordingEngineStub()
    eq.wireToPlaybackEngine(stub)
    let initialRefreshCount = stub.refreshCount
    let exp = expectation(description: "refresh fires for setEnabled true")
    stub.onRefresh = { exp.fulfill() }
    eq.setEnabledInternal(true)
    wait(for: [exp], timeout: 1.0)
    XCTAssertGreaterThan(stub.refreshCount, initialRefreshCount)
  }

  /// Band-gain mutations route to the active item via the
  /// provider's gains-version bump — the running tap recomputes
  /// coefficients on its next render call. Detaching/reattaching
  /// the audioMix on every slider tick would cause an audible
  /// cutout, so the engine refresh path is reserved for the
  /// enable transition.
  func testSetBandGainDoesNotTriggerEngineRefresh() {
    let eq = makeEqualizer()
    let stub = RecordingEngineStub()
    eq.wireToPlaybackEngine(stub)
    let initialRefreshCount = stub.refreshCount
    eq.setBandGainInternal(index: 4, gainDb: -6)
    let exp = expectation(description: "drain main queue")
    DispatchQueue.main.async { exp.fulfill() }
    wait(for: [exp], timeout: 1.0)
    XCTAssertEqual(stub.refreshCount, initialRefreshCount)
  }

  /// `setAllBandGainsInternal` (the path under preset apply + reset)
  /// also avoids the engine refresh — same gains-version mechanism
  /// keeps the active tap in sync without rebuilding the audioMix.
  func testSetAllBandGainsDoesNotTriggerEngineRefresh() {
    let eq = makeEqualizer()
    let stub = RecordingEngineStub()
    eq.wireToPlaybackEngine(stub)
    let initialRefreshCount = stub.refreshCount
    eq.setAllBandGainsInternal([3, 3, 3, 3, 3, 3, 3, 3, 3, 3])
    let exp = expectation(description: "drain main queue")
    DispatchQueue.main.async { exp.fulfill() }
    wait(for: [exp], timeout: 1.0)
    XCTAssertEqual(stub.refreshCount, initialRefreshCount)
  }

  /// Each band-gain mutation bumps the provider's monotonic gains
  /// version so per-item taps know to recompute coefficients on
  /// their next render call.
  func testSetBandGainBumpsProviderVersion() {
    let eq = makeEqualizer()
    let initial = eq.provider.gainsSnapshot().version
    eq.setBandGainInternal(index: 2, gainDb: 4)
    let after = eq.provider.gainsSnapshot().version
    XCTAssertGreaterThan(after, initial)
  }

  /// Idempotent setEnabled (false → false) is a no-op and must NOT
  /// pay the refresh cost — keeps the path cheap when the JS
  /// surface fires redundant toggles during reload.
  func testIdempotentSetEnabledDoesNotRefresh() {
    let eq = makeEqualizer()
    let stub = RecordingEngineStub()
    eq.wireToPlaybackEngine(stub)
    let initial = stub.refreshCount
    eq.setEnabledInternal(false)
    // No expectation here — assert the count is unchanged after a
    // brief main-queue drain so any spurious dispatch would land.
    let exp = expectation(description: "drain main queue")
    DispatchQueue.main.async { exp.fulfill() }
    wait(for: [exp], timeout: 1.0)
    XCTAssertEqual(stub.refreshCount, initial)
  }
}

/// Counts `setAudioMixProvider` + `refreshAllItemMixes` calls so
/// the wiring tests above can assert the EQ drives the engine.
/// All other PlaybackEngine methods are no-op stubs.
private final class RecordingEngineStub: PlaybackEngine {
  var lastSetProvider: AudioMixProvider?
  var refreshCount: Int = 0
  var onRefresh: (() -> Void)?

  func release() {}
  func setItems(_ items: [AVPlayerItem], startIndex: Int, startPositionSeconds: TimeInterval) {}
  @discardableResult
  func insertItems(_ items: [AVPlayerItem], after: AVPlayerItem?) -> Bool { true }
  func remove(_ item: AVPlayerItem) {}
  func removeAllItems() {}
  func canInsert(_ item: AVPlayerItem, after: AVPlayerItem?) -> Bool { true }
  func play() {}
  func pause() {}
  func stop() {}
  func seek(toIndex index: Int, position: TimeInterval) {}
  func skipToNext() {}
  func skipToPrevious() {}
  func setVolume(_ volume: Float) {}
  func setPlaybackSpeed(_ speed: Float) {}
  func seedPlaybackSpeed(_ speed: Float) {}
  func setRepeatMode(_ mode: RepeatMode) {}
  func setPitchCorrectionMode(_ mode: PitchCorrectionMode) {}

  var currentMediaItemIndex: Int { -1 }
  var currentPositionSeconds: TimeInterval { 0 }
  var currentDurationSeconds: TimeInterval { 0 }
  var bufferedPositionSeconds: TimeInterval { 0 }
  var isPlaying: Bool { false }
  var timeControlStatus: AVPlayer.TimeControlStatus { .paused }
  var currentMediaItem: AVPlayerItem? { nil }
  var allMediaItems: [AVPlayerItem] { [] }

  func setAudioMixProvider(_ provider: AudioMixProvider?) {
    lastSetProvider = provider
  }

  func refreshAllItemMixes() {
    refreshCount += 1
    onRefresh?()
  }

  /// No fade to schedule — this stub exercises the rest of the surface.
  var isFadePendingOrActive: Bool { false }

  /// Nothing attached — this stub exercises the rest of the surface.
  var attachedQueueItemIds: [String] { [] }

  var delegate: PlaybackEngineDelegate?
}
