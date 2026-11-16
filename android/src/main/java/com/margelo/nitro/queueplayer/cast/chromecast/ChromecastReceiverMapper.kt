package com.margelo.nitro.queueplayer.cast.chromecast

import com.google.android.gms.cast.CastDevice
import com.margelo.nitro.queueplayer.CastDeviceKind
import com.margelo.nitro.queueplayer.CastProtocol
import com.margelo.nitro.queueplayer.CastReceiver
import com.margelo.nitro.queueplayer.CastReceiverCapabilities
import com.margelo.nitro.queueplayer.CastStreamingModel

/**
 * Maps `CastDevice` → Nitro `CastReceiver`. ID scheme
 * `chromecast:<deviceId>` namespaces by protocol for cross-backend dedup.
 */
internal object ChromecastReceiverMapper {

  fun map(device: CastDevice): CastReceiver {
    val deviceId = device.deviceId ?: device.friendlyName ?: "unknown"
    val name = device.friendlyName ?: deviceId
    val iconHint = iconHintFor(device)
    return CastReceiver(
      id = "chromecast:$deviceId",
      castProtocol = CastProtocol.CHROMECAST,
      name = name,
      iconHint = iconHint,
      capabilities = CastReceiverCapabilities(
        supportsRemoteVolume = true,
        streamingModel = CastStreamingModel.PULLURL,
        requiresPairing = false,
      ),
      protocolVersion = "",
      modelName = device.modelName ?: "",
    )
  }

  private fun iconHintFor(device: CastDevice): CastDeviceKind {
    val model = (device.modelName ?: "").lowercase()
    return when {
      "tv" in model || "chromecast" in model -> CastDeviceKind.TV
      "home" in model || "nest" in model || "audio" in model -> CastDeviceKind.SPEAKER
      "display" in model -> CastDeviceKind.DISPLAY
      else -> CastDeviceKind.UNKNOWN
    }
  }
}
