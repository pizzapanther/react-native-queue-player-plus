package com.margelo.nitro.queueplayer.cast.airplay

import com.margelo.nitro.queueplayer.CastDeviceKind
import com.margelo.nitro.queueplayer.CastProtocol
import com.margelo.nitro.queueplayer.CastReceiver
import com.margelo.nitro.queueplayer.CastReceiverCapabilities
import com.margelo.nitro.queueplayer.CastStreamingModel
import com.margelo.nitro.queueplayer.cast.Receiver

/**
 * Maps the AirPlay backend's internal [Receiver] to the Nitro
 * [CastReceiver] surface. Mirrors
 * [com.margelo.nitro.queueplayer.cast.chromecast.ChromecastReceiverMapper]
 * — the picker reads only Nitro types, never the internal domain shape.
 */
internal object AirPlayReceiverMapper {

  fun map(receiver: Receiver): CastReceiver = CastReceiver(
    id = receiver.id,
    castProtocol = CastProtocol.AIRPLAY,
    name = receiver.name,
    iconHint = iconHintFor(receiver.iconHint, receiver.name),
    capabilities = CastReceiverCapabilities(
      supportsRemoteVolume = receiver.capabilities.supportsRemoteVolume,
      streamingModel = CastStreamingModel.PUSHPCM,
      requiresPairing = receiver.capabilities.requiresPairing,
    ),
    protocolVersion = receiver.protocolVersion ?: "",
    modelName = receiver.extras["model"] ?: "",
  )

  private fun iconHintFor(hint: String, name: String): CastDeviceKind {
    val combined = (hint + " " + name).lowercase()
    return when {
      "tv" in combined || "apple tv" in combined -> CastDeviceKind.TV
      "speaker" in combined || "homepod" in combined || "audio" in combined ||
        "airport" in combined || "express" in combined -> CastDeviceKind.SPEAKER
      "display" in combined -> CastDeviceKind.DISPLAY
      else -> CastDeviceKind.UNKNOWN
    }
  }
}
