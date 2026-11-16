package com.margelo.nitro.queueplayer.cast

/**
 * Contract every cast protocol implements. One [CastBackend] per
 * protocol family — `AirPlayBackend` for the AirPlay 1/2 family,
 * `ChromecastBackend` for Cast, future DLNA / Sonos similarly. The
 * core module never knows which backends are present.
 *
 * The interface is intentionally tiny. Anything richer lives on the
 * concrete backend or on the [CastSession] subtype that [connect]
 * returns — volume on the session, the AirPlay 2 pairing-PIN flow on
 * `AirPlayBackend.submitPairingPin`. Keeps the registry pluggable + the
 * routing layer protocol-agnostic.
 */
interface CastBackend {

  /** Stable string identifier matching the `protocol:` prefix on receiver IDs. */
  val protocolId: String

  /**
   * Begin discovery. Callback fires every time the discovered set
   * changes — backends should debounce. Idempotent: calling when
   * already running must not duplicate listeners.
   */
  fun startDiscovery(onUpdate: (List<Receiver>) -> Unit)

  /** Stop discovery + release resources. Safe when not running. */
  fun stopDiscovery()

  /**
   * Connect to a discovered receiver. Suspending because protocol
   * handshakes are async. Throws on failure; the caller surfaces the
   * error through the JS promise.
   *
   * @param options Protocol-specific. Recognised keys: `"password"`
   *   (String) — receiver password / PIN for authenticated receivers.
   */
  suspend fun connect(
    receiverId: String,
    options: Map<String, Any?> = emptyMap(),
  ): CastSession
}
