import Accelerate
import Foundation

/// Standalone audio processor that applies a linear gain to a
/// Float32 buffer in place. No knowledge of EQ, the audio tap, or
/// `ReplayGainData` — purely "given a Float buffer, multiply by my
/// gain". Composition lives at the call-site (`EQTap` invokes
/// `replayGainProcessor.process(...)` before the EQ stage).
///
/// Threading: `setLinearGain` may be called from any thread (typically
/// the JS bridge / main queue). `process` runs on the audio render
/// thread. The internal `NSLock` serialises the set + read so the
/// render thread always observes a consistent gain value. Matches the
/// project-wide convention (see `AudioTapProvider.gainsSnapshot`,
/// `Visualizer.subsLock`) — short critical sections on render are
/// accepted as the trade for cross-class consistency over a lock-free
/// atomic. Steady state at unity gain hits an early-return after the
/// lock-protected read.
final class ReplayGainProcessor {

  private let lock = NSLock()
  private var linearGain: Float = 1.0

  /// Update the gain. Thread-safe.
  func setLinearGain(_ value: Float) {
    lock.lock()
    linearGain = value
    lock.unlock()
  }

  /// Multiply `frames` Float32 samples in `buffer` by the current
  /// gain in place. Steady-state cost when the gain is unity is one
  /// short lock-protected read plus one Float compare. The
  /// `gain == 1.0` bit-exact check is the unity-skip — both the
  /// reset path (`setLinearGain(1.0)`) and a 0 dB tag (RG2
  /// `pow(10, 0/20) = 1.0`) hit it; both cases mean "no correction
  /// to apply", so the early-return is correct in both.
  func process(buffer: UnsafeMutablePointer<Float>, frames: Int) {
    lock.lock()
    var gain = linearGain
    lock.unlock()

    guard gain != 1.0 else { return }
    vDSP_vsmul(buffer, 1, &gain, buffer, 1, vDSP_Length(frames))
  }
}
