package com.margelo.nitro.queueplayer.cast.http

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Per-session view onto [LocalMediaServer]. A backend acquires on
 * session start, registers files as the user loads tracks, and
 * `close()`s on session end — which revokes outstanding tokens and
 * decrements the server's refcount.
 *
 * Not thread-safe for `close` vs concurrent `register` calls — by
 * design the cast session owns it and serialises lifecycle vs
 * media-load calls.
 */
class MediaServerHandle internal constructor(
  private val server: LocalMediaServer,
  val sessionId: String,
) {
  private val closed = AtomicBoolean(false)

  /**
   * Register a file and return the absolute LAN URL the receiver
   * should fetch. [lanAddress] supplied by caller because the source
   * of truth (network monitor) lives a layer up.
   */
  fun register(file: File, lanAddress: String, contentType: String? = null): String {
    check(!closed.get()) { "MediaServerHandle is closed" }
    val token = server.register(sessionId, file, contentType)
    val port = server.serverPort
    return "http://$lanAddress:$port/media/$token"
  }

  fun close() {
    if (closed.compareAndSet(false, true)) {
      server.release(sessionId)
    }
  }
}
