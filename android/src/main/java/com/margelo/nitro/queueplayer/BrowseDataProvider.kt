package com.margelo.nitro.queueplayer

import androidx.media3.common.util.UnstableApi
import java.util.concurrent.atomic.AtomicReference

/**
 * Native cache for the most-recent [BrowseSnapshot] pushed by the
 * consumer via `setBrowseSnapshot`. The MediaLibrarySession's browse
 * callbacks read from this cache so a cold-wake from Android Auto /
 * Google Assistant can answer `onGetLibraryRoot` / `onGetChildren` /
 * `onGetItem` / `onSearch` without a JS bridge round-trip.
 *
 * Snapshot replacement is atomic — `setSnapshot` performs a single
 * [AtomicReference.set] so a concurrent reader either sees the prior
 * snapshot in full or the new one in full, never a torn mix.
 *
 * Browse tree shape:
 *  - The root parent's id is the snapshot's [BrowseSnapshot.rootId].
 *    Its children are one synthetic browsable item per
 *    [BrowseSection] — id matches the section's id, title matches
 *    the section's title, never playable, always browsable.
 *  - Each section id, when used as a parentId, returns that
 *    section's `items` list paginated.
 *  - Item ids must be unique across the entire snapshot — the
 *    consumer's responsibility per the lib's identity contract
 *    (consumer-supplied data, lib does not interpret).
 *
 * Snapshot updates emit a set of parentIds that need to be
 * re-fetched by connected browse controllers — caller (the
 * MediaLibrarySession.Callback) loops the set + invokes
 * `notifyChildrenChanged` for each.
 */
@UnstableApi
internal class BrowseDataProvider {

  private val cell = AtomicReference<BrowseSnapshot?>(null)

  /** Replace the cached snapshot. Pass `null` to clear. Returns the
   *  set of parentIds whose children list changed (root + every
   *  section id from the prior + new snapshots) so the caller can
   *  fan out `notifyChildrenChanged` notifications.
   *
   *  Returns an empty set when both the prior and new snapshots are
   *  `null` (e.g. clearing a cache that was never populated) — no
   *  controllers to notify in that case. The empty-set case is also
   *  why [PlaybackService.notifyChildrenChangedForParents] still
   *  unions in any [PendingBrowseRequests] that accumulated before
   *  the first snapshot landed: a controller that fetched children
   *  pre-snapshot is in the pending queue, not in the changed set
   *  here. */
  fun setSnapshot(snapshot: BrowseSnapshot?): Set<String> {
    val previous = cell.getAndSet(snapshot)
    val changedParents = mutableSetOf<String>()
    previous?.let { p ->
      changedParents += p.rootId
      p.sections.forEach { changedParents += it.id }
    }
    snapshot?.let { s ->
      changedParents += s.rootId
      s.sections.forEach { changedParents += it.id }
    }
    return changedParents
  }

  /** Read the current snapshot, or `null` if none has been set. */
  fun getSnapshot(): BrowseSnapshot? = cell.get()

  /** True when the parentId addresses the root of the cached
   *  snapshot. False when no snapshot is set. */
  fun isRoot(parentId: String): Boolean =
    cell.get()?.rootId == parentId

  /** Find a section by id. Returns null when no snapshot is set or
   *  no section matches. */
  fun getSection(sectionId: String): BrowseSection? =
    cell.get()?.sections?.firstOrNull { it.id == sectionId }

  /** Search the full snapshot for an item with the given id. Walks
   *  every section in declaration order. Returns null when no
   *  snapshot is set or no item matches. */
  fun getItem(itemId: String): BrowseItem? {
    val snapshot = cell.get() ?: return null
    for (section in snapshot.sections) {
      for (item in section.items) {
        if (item.id == itemId) return item
      }
    }
    return null
  }

  /** Resolve a parentId to its children, paginated.
   *  - `parentId == snapshot.rootId` returns one synthetic item per
   *    section: `id = section.id, title = section.title,
   *    playable = false, hasChildren = true`.
   *  - `parentId == section.id` returns that section's items.
   *  - Anything else returns an empty list.
   *
   *  Pagination: 0-based page index. `pageSize <= 0` is treated as
   *  unbounded (returns the full list from `page * pageSize` —
   *  in practice the page index won't be paged when pageSize is 0
   *  so it returns the full list). Out-of-range pages return empty. */
  fun getChildren(parentId: String, page: Int, pageSize: Int): List<BrowseItem> {
    val snapshot = cell.get() ?: return emptyList()
    val items: List<BrowseItem> = when (parentId) {
      snapshot.rootId -> snapshot.sections.map { section ->
        BrowseItem(
          id = section.id,
          title = section.title,
          subtitle = null,
          artworkUrl = section.artworkUrl,
          playable = false,
          hasChildren = true
        )
      }
      else -> {
        val section = snapshot.sections.firstOrNull { it.id == parentId }
          ?: return emptyList()
        section.items.toList()
      }
    }
    if (pageSize <= 0) return items
    if (page < 0) return emptyList()
    val from = page * pageSize
    if (from >= items.size) return emptyList()
    val to = (from + pageSize).coerceAtMost(items.size)
    return items.subList(from, to)
  }
}
