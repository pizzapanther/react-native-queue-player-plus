package com.margelo.nitro.queueplayer.cast.chromecast

import android.os.SystemClock
import androidx.media3.common.DeviceInfo
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Wraps the receiver's `RemoteCastPlayer` so the system + lock-screen hardware
 * volume rocker gets fine-grained, perceptually-even control over the Chromecast
 * device volume.
 *
 * The Cast route's native model is ~20 LINEAR steps over the receiver's
 * `0.0..1.0` device volume (5% per press). Receivers apply their own steep curve,
 * so the audible range collapses into the bottom of that scale — a couple of
 * presses span silent→loud and the rest of the bar is dead. This wrapper
 * advertises a finer [STEP_COUNT]-step remote [DeviceInfo] and maps each step
 * through a compressive taper ([GAMMA] > 1) so equal presses produce roughly
 * equal loudness changes, concentrating resolution at the quiet end where
 * perceived loudness moves fastest. Steps map to `0.0..1.0` and are applied via
 * [setReceiverVolume] (the receiver's device-volume plane).
 *
 * The wrapper only exists while a Chromecast session is bound to the
 * `MediaSession`, so it always reports remote device volume. The step is tracked
 * locally as the authoritative value (no dependence on the asynchronous receiver
 * echo, so rapid presses increment cleanly); a volume change made on the receiver
 * itself is not reflected until the next app-side change.
 */
@UnstableApi
internal class CastVolumeForwardingPlayer(
  player: Player,
  routingControllerId: String?,
  private val setReceiverVolume: (Float) -> Unit,
  private val receiverVolume: () -> Float,
) : ForwardingPlayer(player) {

  private val remoteDeviceInfo: DeviceInfo =
    DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE)
      .setMinVolume(0)
      .setMaxVolume(STEP_COUNT)
      .apply { routingControllerId?.let { setRoutingControllerId(it) } }
      .build()

  // [trackedStep] + [lastApplyMs] are read-modified-written together (getDeviceVolume
  // resyncs, applyStep advances) without being atomic across the two fields. That is
  // safe because Media3 routes device-volume commands to the player on a single
  // (application/main) thread; `@Volatile` only publishes each field to the readback.

  /** Locally-tracked current step; `-1` until first resolved from the receiver. */
  @Volatile
  private var trackedStep: Int = -1

  /** `elapsedRealtime` of the last rocker-driven change; gates the resync. */
  @Volatile
  private var lastApplyMs: Long = 0L

  override fun getDeviceInfo(): DeviceInfo = remoteDeviceInfo

  override fun getAvailableCommands(): Player.Commands =
    Player.Commands.Builder()
      .addAll(super.getAvailableCommands())
      .add(Player.COMMAND_GET_DEVICE_VOLUME)
      .add(Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS)
      .add(Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS)
      .build()

  override fun isCommandAvailable(command: Int): Boolean =
    command in DEVICE_VOLUME_COMMANDS || super.isCommandAvailable(command)

  override fun getDeviceVolume(): Int {
    val receiver = receiverVolume()
    // The locally-tracked step is authoritative while the rocker is in active
    // use — the receiver echo lags rapid presses, so re-reading it would snap
    // the step backwards mid-adjust. Once the rocker has been idle past
    // [RESYNC_IDLE_MS], adopt the receiver's reported volume so a change made on
    // the receiver itself (its own remote / the Home app) re-syncs the rocker.
    if (!receiver.isNaN() &&
      (trackedStep < 0 || SystemClock.elapsedRealtime() - lastApplyMs > RESYNC_IDLE_MS)
    ) {
      trackedStep = volumeToStep(receiver)
    }
    return trackedStep.coerceAtLeast(0)
  }

  override fun setDeviceVolume(volume: Int, flags: Int) = applyStep(volume)

  override fun increaseDeviceVolume(flags: Int) = applyStep(getDeviceVolume() + 1)

  override fun decreaseDeviceVolume(flags: Int) = applyStep(getDeviceVolume() - 1)

  // The receiver advertises the legacy no-flags device-volume commands too, so
  // route them through the same taper rather than letting `ForwardingPlayer`
  // forward them raw to the receiver (which would skip the curve and clip the
  // 0..STEP_COUNT value against the receiver's own ~20-step scale).
  @Deprecated("Superseded by the (volume, flags) variant; both apply the taper.")
  override fun setDeviceVolume(volume: Int) = applyStep(volume)

  @Deprecated("Superseded by the (flags) variant; both apply the taper.")
  override fun increaseDeviceVolume() = applyStep(getDeviceVolume() + 1)

  @Deprecated("Superseded by the (flags) variant; both apply the taper.")
  override fun decreaseDeviceVolume() = applyStep(getDeviceVolume() - 1)

  private fun applyStep(step: Int) {
    val clamped = step.coerceIn(0, STEP_COUNT)
    trackedStep = clamped
    lastApplyMs = SystemClock.elapsedRealtime()
    setReceiverVolume(stepToVolume(clamped))
  }

  companion object {
    /**
     * Hardware-rocker step count over the receiver's device volume. ~30 leaves
     * enough resolution at the quiet end after the taper without a sluggish
     * rocker. Tunable; receiver-agnostic.
     */
    const val STEP_COUNT = 30

    /**
     * Compressive taper exponent ( > 1 ). Concentrates steps at the quiet end
     * where perceived loudness changes fastest, so equal presses feel even.
     * Tunable; deliberately receiver-agnostic (no per-device constant). The
     * receiver quantizes device volume to a coarser grid than the lowest steps
     * resolve to, so the bottom one or two presses may land on the same audible
     * level — acceptable next to the dead range the taper reclaims higher up.
     */
    const val GAMMA = 2.5f

    /**
     * Idle gap after which [getDeviceVolume] adopts the receiver's reported
     * volume instead of the locally-tracked step, so a change made on the
     * receiver itself re-syncs once the rocker is no longer in active use.
     */
    private const val RESYNC_IDLE_MS = 1_500L

    private val DEVICE_VOLUME_COMMANDS = setOf(
      Player.COMMAND_GET_DEVICE_VOLUME,
      Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
      Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
    )

    /** Map a rocker step (`0..STEP_COUNT`) to receiver volume (`0.0..1.0`). */
    fun stepToVolume(step: Int): Float =
      (step.coerceIn(0, STEP_COUNT).toFloat() / STEP_COUNT).pow(GAMMA)

    /** Inverse of [stepToVolume]: receiver volume (`0.0..1.0`) to nearest step. */
    fun volumeToStep(volume: Float): Int {
      if (volume.isNaN()) return 0
      val frac = volume.coerceIn(0f, 1f).pow(1f / GAMMA)
      return (frac * STEP_COUNT).roundToInt().coerceIn(0, STEP_COUNT)
    }
  }
}
