package com.margelo.nitro.queueplayer

import androidx.media3.common.util.UnstableApi
import java.util.concurrent.atomic.AtomicReference

/**
 * Native-side queue of `parentId`s a controller asked the
 * MediaLibrarySession to enumerate before the consumer's JS bridge
 * had pushed a [BrowseSnapshot] into [BrowseDataProvider]. The empty
 * `onGetChildren` response is the "still loading" UX hint to the
 * controller; once a snapshot lands, [PlaybackService] drains the
 * queue and fires `notifyChildrenChanged` for each pending parent
 * so the controller re-fetches with real data.
 *
 * The cold-start race the queue closes:
 *
 *  1. Android Auto / Google Assistant binds to [PlaybackService]
 *     before any Activity has booted React.
 *  2. The session's `onConnect` triggers
 *     [PlaybackService.selfWakeForHeadlessClient] which spawns the
 *     JS task — but the bridge boot + bundle evaluation +
 *     `registerPlaybackService` call take ~1-3 s.
 *  3. Mid-boot the controller calls `getChildren(rootId)`.
 *     [BrowseDataProvider.getSnapshot] returns `null` because no JS
 *     consumer has called `setBrowseSnapshot` yet.
 *  4. Without this queue, the callback would return
 *     `RESULT_ERROR_NOT_SUPPORTED` and the controller would display
 *     a hard error. With this queue, the callback returns an empty
 *     list (controller paints a skeleton) and registers the parent
 *     for replay once the snapshot lands.
 *
 * Threading: every method is safe to call from any thread —
 * [AtomicReference] guards the underlying set.
 */
@UnstableApi
internal class PendingBrowseRequests {

  private val cell = AtomicReference<Set<String>>(emptySet())

  /**
   * Record [parentId] as a pending request. Idempotent — duplicate
   * enqueues collapse, so a single [drainAll] yields one
   * `notifyChildrenChanged` per unique parent regardless of how many
   * controllers asked for it before the snapshot landed.
   */
  fun enqueue(parentId: String) {
    while (true) {
      val current = cell.get()
      if (parentId in current) return
      val next = current + parentId
      if (cell.compareAndSet(current, next)) return
    }
  }

  /** Atomically take + clear the pending set. Subsequent calls return
   *  empty until the next [enqueue]. */
  fun drainAll(): Set<String> = cell.getAndSet(emptySet())

  /** Discard every pending entry without firing any notification.
   *  Invoked from [PlaybackService.onDestroy] so a service teardown
   *  during a cold-start stall doesn't strand a stale entry across
   *  the next service spin-up. */
  fun clear() {
    cell.set(emptySet())
  }

  /** Snapshot the pending parents without consuming. Mainly for
   *  tests — production code drains via [drainAll]. */
  internal fun snapshotForTest(): Set<String> = cell.get()
}
