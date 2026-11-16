package com.margelo.nitro.queueplayer.cast

/**
 * A discovered cast target. One [Receiver] per remote device the user
 * could potentially connect to. Backends namespace [id] with their own
 * [protocol] prefix (e.g. `airplay:livingroom-homepod`,
 * `chromecast:abc-123`) so the registry can deduplicate cleanly across
 * backends.
 */
data class Receiver(
  val id: String,
  val protocol: String,
  val name: String,
  val iconHint: String,
  val capabilities: ReceiverCapabilities,
  val protocolVersion: String? = null,
  val extras: Map<String, String> = emptyMap(),
)

/**
 * Typed capability flags shared between [Receiver] and [Route]. Picker
 * UI reads these to decide what controls to render — never branch on
 * protocol name in UI code.
 */
data class ReceiverCapabilities(
  val supportsRemoteVolume: Boolean,
  val streamingModel: StreamingModel,
  val requiresPairing: Boolean,
)

/** Cast paradigm: PCM-stream (AirPlay) vs URL-pull (Chromecast / DLNA). */
enum class StreamingModel {
  PUSH_PCM,
  PULL_URL,
}
