package com.margelo.nitro.queueplayer.cast.chromecast

import android.os.Looper
import androidx.media3.common.DeviceInfo
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * [CastVolumeForwardingPlayer] maps the hardware volume rocker to the
 * receiver's device volume through a finer step count and a compressive
 * taper, so equal presses produce roughly even loudness changes and the
 * quiet end of the scale gets the resolution the receiver's own curve needs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@UnstableApi
class CastVolumeForwardingPlayerTest {

  private class StubPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
    override fun getState(): State =
      State.Builder().setAvailableCommands(Player.Commands.EMPTY).build()
  }

  private var sentVolume: Float = Float.NaN
  private var receiver: Float = Float.NaN

  private fun player() = CastVolumeForwardingPlayer(
    player = StubPlayer(),
    routingControllerId = "device-id",
    setReceiverVolume = { sentVolume = it; receiver = it },
    receiverVolume = { receiver },
  )

  // -- Curve math (the core logic) --

  @Test
  fun stepEndpointsMapToFullRange() {
    assertEquals(0f, CastVolumeForwardingPlayer.stepToVolume(0), 1e-6f)
    assertEquals(
      1f,
      CastVolumeForwardingPlayer.stepToVolume(CastVolumeForwardingPlayer.STEP_COUNT),
      1e-6f,
    )
  }

  @Test
  fun stepToVolumeIsMonotonicIncreasing() {
    var prev = -1f
    for (s in 0..CastVolumeForwardingPlayer.STEP_COUNT) {
      val v = CastVolumeForwardingPlayer.stepToVolume(s)
      assertTrue("step $s should exceed previous", v > prev)
      prev = v
    }
  }

  @Test
  fun stepRoundTripsThroughVolume() {
    for (s in 0..CastVolumeForwardingPlayer.STEP_COUNT) {
      val v = CastVolumeForwardingPlayer.stepToVolume(s)
      assertEquals(s, CastVolumeForwardingPlayer.volumeToStep(v))
    }
  }

  @Test
  fun taperConcentratesResolutionAtQuietEnd() {
    // The audible range on a curved receiver collapses into the bottom of the
    // 0.0..1.0 device scale; the taper must spend most steps there. A linear
    // 30-step control would give ~5 steps below 0.15.
    val lowSteps = (0..CastVolumeForwardingPlayer.STEP_COUNT)
      .count { CastVolumeForwardingPlayer.stepToVolume(it) <= 0.15f }
    assertTrue("expected many fine steps below 0.15, got $lowSteps", lowSteps >= 12)
  }

  @Test
  fun volumeToStepClampsAndHandlesNaN() {
    assertEquals(0, CastVolumeForwardingPlayer.volumeToStep(Float.NaN))
    assertEquals(0, CastVolumeForwardingPlayer.volumeToStep(-0.5f))
    assertEquals(
      CastVolumeForwardingPlayer.STEP_COUNT,
      CastVolumeForwardingPlayer.volumeToStep(2f),
    )
  }

  // -- Device-volume routing (the override surface) --

  @Test
  fun reportsRemoteDeviceInfoWithStepRange() {
    val info = player().deviceInfo
    assertEquals(DeviceInfo.PLAYBACK_TYPE_REMOTE, info.playbackType)
    assertEquals(0, info.minVolume)
    assertEquals(CastVolumeForwardingPlayer.STEP_COUNT, info.maxVolume)
  }

  @Test
  fun advertisesDeviceVolumeCommands() {
    val p = player()
    assertTrue(p.isCommandAvailable(Player.COMMAND_GET_DEVICE_VOLUME))
    assertTrue(p.isCommandAvailable(Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS))
    assertTrue(p.isCommandAvailable(Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS))
    assertTrue(p.availableCommands.contains(Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS))
  }

  @Test
  fun setDeviceVolumeSendsCurvedReceiverVolume() {
    val p = player()
    p.setDeviceVolume(15, 0)
    assertEquals(15, p.deviceVolume)
    assertEquals(CastVolumeForwardingPlayer.stepToVolume(15), sentVolume, 1e-6f)
  }

  @Test
  fun increaseAndDecreaseStepCleanlyRegardlessOfEcho() {
    val p = player()
    receiver = 0f
    p.setDeviceVolume(10, 0)
    p.increaseDeviceVolume(0)
    p.increaseDeviceVolume(0)
    // Tracked locally, so two presses advance two steps even before the
    // receiver echo updates receiverVolume().
    assertEquals(12, p.deviceVolume)
    p.decreaseDeviceVolume(0)
    assertEquals(11, p.deviceVolume)
  }

  @Test
  fun stepClampsAtBounds() {
    val p = player()
    p.setDeviceVolume(CastVolumeForwardingPlayer.STEP_COUNT, 0)
    p.increaseDeviceVolume(0)
    assertEquals(CastVolumeForwardingPlayer.STEP_COUNT, p.deviceVolume)
    p.setDeviceVolume(0, 0)
    p.decreaseDeviceVolume(0)
    assertEquals(0, p.deviceVolume)
  }

  @Test
  fun initialDeviceVolumeDerivesFromReceiver() {
    receiver = CastVolumeForwardingPlayer.stepToVolume(8)
    assertEquals(8, player().deviceVolume)
  }

  @Test
  fun deviceVolumeIsZeroWhenReceiverVolumeUnknown() {
    receiver = Float.NaN
    assertEquals(0, player().deviceVolume)
  }

  @Test
  fun adoptsExternalReceiverChangeOnceRockerIsIdle() {
    val p = player()
    receiver = 0f
    p.setDeviceVolume(20, 0)
    // A change made on the receiver itself (its remote / Home app).
    receiver = CastVolumeForwardingPlayer.stepToVolume(5)
    // Within the active-use window the locally-tracked step still wins, so a
    // rapid press doesn't snap backwards on the lagging echo.
    assertEquals(20, p.deviceVolume)
    ShadowSystemClock.advanceBy(Duration.ofMillis(2_000))
    assertEquals(5, p.deviceVolume)
  }

  @Test
  fun mutedReadsFalseByDefaultFromStub() {
    assertFalse(player().isDeviceMuted)
  }
}
