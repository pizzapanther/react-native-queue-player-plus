import Foundation

/// Pure-Swift input-validation helpers for the JS↔native boundary.
///
/// JS numbers cross into Swift as `Double`. Two failure modes need
/// guarding before the value reaches AVFoundation / `Int(_:)` /
/// `CMTime`:
///
/// 1. **Non-finite inputs** (`NaN`, `±Infinity`, `signalingNaN`).
///    `Int(.nan)` and `Int(.infinity)` are undefined behaviour in
///    Swift (trap in `-O0`, garbage in `-O`); `CMTime(seconds:
///    .nan, …)` resolves to `CMTime.invalid` which AVPlayer
///    documents as a no-op since iOS 7 but the contract is fragile.
/// 2. **Out-of-range inputs.** Negative track indices, fractional
///    indices, or indices past `tracks.count` are silent no-ops at
///    the public API surface (matches the documented contract +
///    Android parity).
///
/// Helpers live here as pure functions, decoupled from `TrackPlayer`
/// (which depends on AVQueuePlayer + AVAudioSession + Nitrogen-generated
/// types).
///
/// Gated behind `@_spi(QueuePlayerTests)` so the helpers stay off
/// the library's consumer-facing public surface — same SPI scope
/// used by `AVQueueBuilder.makeItemRaw`. The `@_spi` annotation
/// goes on each function (not the enum) to match the
/// project-empirical working pattern; both shapes are valid SPI
/// per the Swift language spec, but the per-method form is what
/// the rest of the codebase uses (see `AVQueueBuilder`,
/// `QueueMutationArithmetic`).
public enum InputGuards {

  /// Validate a `Double` that JS sends as a queue index. Returns
  /// `nil` for non-finite, negative, or out-of-range values; the
  /// returned `Int` (when non-nil) is bounded by `count` and
  /// guaranteed to be in `0..<count`. Fractional values silently
  /// floor — matches Android `(value * 1.0).toLong()` semantic and
  /// the documented "out-of-range is a silent no-op" contract.
  ///
  /// Validating in `Double` space before the `Int` conversion
  /// matters because `Int(.nan)` / `Int(.infinity)` are UB; once
  /// the bounds checks pass, `Int(value)` is safe.
  @_spi(QueuePlayerTests)
  public static func validQueueIndex(_ value: Double, count: Int) -> Int? {
    guard value.isFinite, value >= 0, value < Double(count) else { return nil }
    return Int(value)
  }

  /// Convert a `Double` insert position to the `Int` the queue arithmetic
  /// clamps. `nil` for non-finite values (dropped: append). A finite value
  /// past what `Int` holds is clamped to `count`, since every position past
  /// the end means "append" anyway; `Int(value)` on such a value traps.
  @_spi(QueuePlayerTests)
  public static func insertPosition(_ value: Double?, count: Int) -> Int? {
    guard let value, value.isFinite else { return nil }
    if value < 0 { return 0 }
    if value >= Double(count) { return count }
    return Int(value)
  }

  /// Validate a `Double` seek position (seconds). Returns `nil` for
  /// non-finite values; otherwise returns the value unchanged. The
  /// caller is responsible for clamping negative values to 0 and
  /// values past the duration to the duration — that logic depends
  /// on the live `currentItem.duration` and is not in scope for a
  /// pure-Swift helper.
  @_spi(QueuePlayerTests)
  public static func sanitisedSeekPosition(_ value: Double) -> Double? {
    guard value.isFinite else { return nil }
    // Finiteness alone is not enough: a finite 1e300 traps the same way
    // `.infinity` does once it is scaled to milliseconds and converted to
    // `Int64`. The bound is far past any real media duration, so clamping to
    // it never changes a seek a consumer could observe.
    return max(-maxSeekSeconds, min(maxSeekSeconds, value))
  }

  private static let maxSeekSeconds: Double = 1e12

  /// Validate a `Double` lookahead count. Returns a non-negative `Int`,
  /// clamped in `Double` space so an enormous or infinite consumer value
  /// cannot trap the conversion. The prefetcher bounds its own window by the
  /// queue length, so the upper clamp loses nothing observable.
  ///
  /// `.infinity` clamps to the bound rather than falling back to zero — it
  /// asks for as much lookahead as possible, and answering "none" would invert
  /// the request. Only `NaN`, which asks for nothing intelligible, reads as
  /// zero; comparison against it is always false, so it cannot be clamped.
  @_spi(QueuePlayerTests)
  public static func validLookaheadCount(_ value: Double) -> Int {
    guard !value.isNaN else { return 0 }
    return Int(max(0, min(value, 10_000)))
  }
}
