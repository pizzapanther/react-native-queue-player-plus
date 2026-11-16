package com.margelo.nitro.queueplayer.cast

import com.margelo.nitro.queueplayer.cast.http.LocalMediaServer

/**
 * Process-wide registry of available [CastBackend]s. Backends self-install
 * during app startup (e.g. AirPlay via androidx.startup
 * `InitializationProvider`); the [com.margelo.nitro.queueplayer.CastManager]
 * looks them up by protocol id.
 *
 * Thread-safe via a synchronized list — registration happens during app
 * init while the rest of the app may already be spinning up other threads.
 */
object CastBackends {

  private val backends = mutableListOf<CastBackend>()
  private val lock = Any()

  /**
   * Process-wide [LocalMediaServer] singleton shared across cast
   * backends (Chromecast `file://` serving today; future DLNA / Sonos).
   * Lazy — never instantiated for apps that don't cast local files.
   * `by lazy` is thread-safe; two concurrent first-touches see the
   * same instance.
   */
  val localMediaServer: LocalMediaServer by lazy { LocalMediaServer() }

  /**
   * Register a backend. Idempotent for the same instance — registering
   * twice is a no-op. Registering a different backend with the same
   * [CastBackend.protocolId] throws — it indicates a real configuration bug.
   */
  fun register(backend: CastBackend) {
    synchronized(lock) {
      if (backends.contains(backend)) return
      val existing = backends.firstOrNull { it.protocolId == backend.protocolId }
      require(existing == null) {
        "A backend for protocol '${backend.protocolId}' is already registered: $existing"
      }
      backends.add(backend)
    }
  }

  /** Snapshot of currently registered backends. Defensive copy — safe to iterate. */
  fun all(): List<CastBackend> = synchronized(lock) { backends.toList() }

  /** Look up a backend by [CastBackend.protocolId], or `null` if absent. */
  fun byProtocol(protocolId: String): CastBackend? = synchronized(lock) {
    backends.firstOrNull { it.protocolId == protocolId }
  }

  /** Clear all registered backends. Used by tests. */
  fun reset() {
    synchronized(lock) { backends.clear() }
  }
}
