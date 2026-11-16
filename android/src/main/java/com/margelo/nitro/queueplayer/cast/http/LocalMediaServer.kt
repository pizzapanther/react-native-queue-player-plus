package com.margelo.nitro.queueplayer.cast.http

import android.content.Context
import com.margelo.nitro.queueplayer.cast.net.LocalAddressMonitor
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking

/**
 * Process-wide local HTTP server for URL-handoff cast backends
 * (Chromecast, future DLNA / Sonos). Serves consumer-supplied
 * `file://` URLs (offline content the host app has on disk) to
 * receivers that can only pull `http://` — the classic Chromecast
 * offline-content path.
 *
 * **Not related to the lookahead cache.** The lookahead cache stays
 * fully native, simple, and streaming-focused. This server exposes
 * only files the consumer app has explicitly put in `setQueue` with
 * a `file://` URL.
 *
 * Binds to `0.0.0.0` on an OS-assigned port. Range / HEAD /
 * Content-Length fall out of Ktor's `LocalFileContent` + `PartialContent`
 * plugin. Refcounted via `acquire(sessionId, context)` / `release(sessionId)`.
 */
class LocalMediaServer {
  private val lock = Any()
  // Ktor 3.x — `embeddedServer(...)` returns the engine-specific
  // `EmbeddedServer<TEngine, TConfig>` wrapper rather than the raw
  // `ApplicationEngine`. Holding the CIO-specialised type keeps the
  // `stop()` shim type-safe.
  private var engine: io.ktor.server.engine.EmbeddedServer<
    io.ktor.server.cio.CIOApplicationEngine,
    io.ktor.server.cio.CIOApplicationEngine.Configuration
  >? = null
  private val refCount = AtomicInteger(0)
  private val registry = MediaTokenRegistry()

  @Volatile
  var serverPort: Int = -1
    private set

  @Volatile
  private var addressMonitor: LocalAddressMonitor? = null

  val currentLanAddress: String?
    get() = addressMonitor?.currentAddress

  fun acquire(sessionId: String, context: Context): MediaServerHandle {
    val first = refCount.getAndIncrement() == 0
    if (first) {
      ensureStarted()
      ensureAddressMonitor(context)
    }
    return MediaServerHandle(this, sessionId)
  }

  // Lazily create + start the LAN-address monitor the first time the
  // server is acquired. `start()` seeds `currentAddress` synchronously
  // when the active network is Wi-Fi, so the address is available to
  // the very first `currentLanAddress` read (the cast handoff loads
  // immediately on connect, before any async network callback lands).
  private fun ensureAddressMonitor(context: Context) {
    synchronized(lock) {
      if (addressMonitor != null) return
      addressMonitor = LocalAddressMonitor(context.applicationContext).also { it.start() }
    }
  }

  internal fun release(sessionId: String) {
    registry.revokeAll(sessionId)
    val last = refCount.decrementAndGet() == 0
    if (last) stop()
  }

  internal fun register(sessionId: String, file: File, contentType: String?): String =
    registry.register(sessionId, file, contentType)

  private fun ensureStarted() {
    synchronized(lock) {
      if (engine != null) return
      // Listen on every interface; the URL host selects the Wi-Fi one.
      // URLs handed back to receivers go through
      // `MediaServerHandle.register(file, lanAddress, contentType)` with the
      // host from `currentLanAddress` (`LocalAddressMonitor.currentAddress`,
      // the Wi-Fi network's address). Tokens are 32 hex chars (high
      // entropy); unauthorized callers cannot enumerate served media.
      val started = embeddedServer(CIO, port = 0, host = "0.0.0.0") {
        install(PartialContent)
        install(AutoHeadResponse)
        routing {
          get("/media/{token}") {
            val token = call.parameters["token"]
            val media = token?.let { registry.resolve(it) }
            if (media == null || !media.file.isFile) {
              call.respond(HttpStatusCode.NotFound)
              return@get
            }
            call.respond(
              LocalFileContent(media.file, ContentType.parse(media.contentType)),
            )
          }
        }
      }
      started.start(wait = false)
      // `engine.resolvedConnectors()` is suspend, but CIO's
      // `startSuspend(false)` awaits `cioConnectors` before returning
      // — so by the time `start(wait = false)` returns, the deferred
      // is already complete and the bridge below never actually parks.
      val resolved = runBlocking { started.engine.resolvedConnectors() }
      serverPort = resolved.firstOrNull()?.port ?: -1
      engine = started
    }
  }

  private fun stop() {
    synchronized(lock) {
      engine?.stop(gracePeriodMillis = 100, timeoutMillis = 500)
      engine = null
      serverPort = -1
      addressMonitor?.stop()
      addressMonitor = null
    }
  }
}
