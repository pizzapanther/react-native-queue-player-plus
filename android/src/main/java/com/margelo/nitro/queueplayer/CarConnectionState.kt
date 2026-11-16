package com.margelo.nitro.queueplayer

import androidx.annotation.VisibleForTesting
import java.util.concurrent.atomic.AtomicInteger

/**
 * Process-wide count of attached headless (automotive) controllers, backing
 * the `isCarConnected()` getter. Holds the same "a car is connected" state
 * the `onCarConnect` / `onCarDisconnect` events transition between, so a
 * consumer that missed the connect event — because it fired before the
 * consumer registered its handler in this process — can still gate car-only
 * work (e.g. browse-snapshot pushes) on the current connection.
 *
 * The count is process-scoped rather than tied to a [PlaybackService]
 * instance so it stays correct across a service restart while a controller
 * remains attached: the new instance's `onConnect` and the dying instance's
 * late `onConnect`/`onDisconnected` all mutate this single source of truth, so
 * the getter can't momentarily read disconnected mid-handoff. It is
 * floor-guarded so a stray disconnect can't drive it negative, and process
 * death clears it (a fresh process starts disconnected), which is correct.
 *
 * Only [PlaybackServiceCallback] mutates this — at the same headless
 * connect / disconnect points that drive the events. This count is separate
 * from that callback's per-instance counter, which stays per-service so the
 * events keep their per-session first/last edge semantics.
 */
internal object CarConnectionState {
  private val count = AtomicInteger(0)

  fun isConnected(): Boolean = count.get() > 0

  /** A headless controller attached. */
  fun retain() {
    count.incrementAndGet()
  }

  /** A headless controller detached; never drops below zero. */
  fun release() {
    count.updateAndGet { if (it > 0) it - 1 else 0 }
  }

  @VisibleForTesting
  fun reset() {
    count.set(0)
  }
}
