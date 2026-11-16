package com.margelo.nitro.queueplayer

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import java.util.TreeSet

/**
 * FIFO (first-in-first-out) [CacheEvictor] for the lookahead cache.
 *
 * Media3 ships only [androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor];
 * this evicts the OLDEST-INSERTED resource first, regardless of playback access —
 * "keep the newest-fetched," which suits a forward-only prefetch queue. LRU keeps
 * recently-played tracks; FIFO drops the oldest download.
 *
 * Insertion order is keyed by RESOURCE ([CacheSpan.key], the track URL), not
 * per-span: all byte-range spans of one track share a single order, so a whole
 * track evicts together and eviction never strands a partial file by dropping a
 * track's head first.
 *
 * [requiresCacheSpanTouches] returns false: FIFO ignores access time, so Media3
 * skips the per-read span-file rewrite and never calls [onSpanTouched] —
 * insertion order is preserved for free and reads stay cheap. (LRU returns true
 * only to force the recency-bump rewrite.)
 *
 * Callbacks are invoked under the [Cache]'s lock, so the plain fields below need
 * no additional synchronization — mirrors Media3's own evictor. [protectedKeys]
 * is the exception: it is written from outside those callbacks.
 *
 * [setProtectedKeys] names urls eviction must leave alone. Only this evictor
 * honours them — Media3's LRU evictor has no such concept, and LRU is the
 * default policy, so protection ships off unless a consumer selects FIFO. LRU
 * is not equivalent: it bumps the recency of the span just read, not the whole
 * resource, so a long track's already-played spans stay evictable while it is
 * still playing.
 */
@UnstableApi
class FifoCacheEvictor(private val maxBytes: Long) : CacheEvictor {

  private var insertionCounter: Long = 0

  /**
   * Bytes held by the spans this evictor tracks — not the cache's on-disk total.
   *
   * The two differ when the cache notifies an add whose span [fifo] rejects: the
   * bytes land on disk but no removal is ever notified for them, so counting
   * them here would push the evictor permanently over budget and make it evict
   * everything on every add. Untracked bytes are reclaimed when their resource
   * is removed as a whole.
   */
  private var currentSize: Long = 0

  /** Insertion order per resource key (the track URL); stamped once, never bumped. */
  private val insertionOrderByKey = HashMap<String, Long>()

  /**
   * Live span count per resource key, maintained from the [fifo] mutations
   * themselves rather than from the callback count.
   *
   * [CacheSpan] defines no `equals`/`hashCode`, and [Cache] notifies an add even
   * when the set rejects it as a duplicate, so a count derived from callbacks
   * drifts from the set. A drifted count drops an [insertionOrderByKey] entry
   * while spans for that key are still live, which changes the comparator's
   * verdict for elements already in [fifo]; removals then silently fail and
   * [evictToFit] cannot make progress.
   */
  private val spanCountByKey = HashMap<String, Int>()

  /** Spans ordered oldest-first by (resource insertion order, byte position). */
  private val fifo = TreeSet<CacheSpan> { a, b ->
    val oa = insertionOrderByKey[a.key] ?: Long.MAX_VALUE
    val ob = insertionOrderByKey[b.key] ?: Long.MAX_VALUE
    when {
      oa != ob -> oa.compareTo(ob)
      a.position != b.position -> a.position.compareTo(b.position)
      else -> a.lastTouchTimestamp.compareTo(b.lastTouchTimestamp)
    }
  }

  /**
   * Urls eviction must not take. Written from the main thread while callbacks
   * run under the [Cache] lock on whichever thread touched the cache, hence
   * volatile. The setter copies because a Kotlin `Set` is read-only rather than
   * immutable: without the copy a caller could keep mutating the set it handed
   * over and change what is protected behind this evictor's back.
   */
  @Volatile
  private var protectedKeys: Set<String> = emptySet()

  /**
   * Name the urls eviction must leave alone — the playing track and the ones
   * around it. Without this, FIFO evicts the track being played the moment the
   * cache is over budget: [requiresCacheSpanTouches] is false, so a read never
   * bumps a resource's order and the playing track cannot protect itself.
   */
  fun setProtectedKeys(keys: Set<String>) {
    protectedKeys = keys.toSet()
  }

  override fun requiresCacheSpanTouches(): Boolean = false

  override fun onCacheInitialized() {
    // No persisted state to restore; insertion order is rebuilt as spans are added.
  }

  override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
    if (length != C.LENGTH_UNSET.toLong()) {
      evictToFit(cache, length)
    }
  }

  override fun onSpanAdded(cache: Cache, span: CacheSpan) {
    // Stamp the order before inserting: the comparator reads it for every
    // element it touches during the insert.
    insertionOrderByKey.getOrPut(span.key) { insertionCounter++ }
    if (fifo.add(span)) {
      spanCountByKey.merge(span.key, 1, Int::plus)
      currentSize += span.length
    }
    evictToFit(cache, 0)
  }

  override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
    if (!fifo.remove(span)) return
    currentSize -= span.length
    spanCountByKey.merge(span.key, -1, Int::plus)
    retireIfEmpty(span.key)
  }

  /**
   * Forget a resource's insertion order once it holds no spans, so a later
   * re-fetch of the same URL is treated as newly inserted.
   *
   * Retires on `<= 0` rather than `== 0`: `HashMap.merge` stores the value
   * directly for an absent key, so a decrement that outruns an increment lands
   * at -1, and an equality test would pin the entry in place permanently.
   */
  private fun retireIfEmpty(key: String) {
    if ((spanCountByKey[key] ?: 0) <= 0) {
      spanCountByKey.remove(key)
      insertionOrderByKey.remove(key)
    }
  }

  override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
    // Not called while requiresCacheSpanTouches() is false. Handled defensively:
    // swap the span in place, preserving the resource's insertion order —
    // re-stamping it here would move the resource to the back of the queue on a
    // mere access. The count tracks each mutation separately so a swap that
    // only half-applies cannot retire an order whose spans are still live.
    if (fifo.remove(oldSpan)) {
      currentSize -= oldSpan.length
      spanCountByKey.merge(oldSpan.key, -1, Int::plus)
    }
    insertionOrderByKey.getOrPut(newSpan.key) { insertionCounter++ }
    if (fifo.add(newSpan)) {
      currentSize += newSpan.length
      spanCountByKey.merge(newSpan.key, 1, Int::plus)
    }
    // Settle both keys only after both mutations: a same-key swap dips to zero
    // in between, and retiring there would re-stamp the resource as newest.
    retireIfEmpty(oldSpan.key)
    retireIfEmpty(newSpan.key)
  }

  private fun evictToFit(cache: Cache, requiredSpace: Long) {
    // One volatile read, so the whole scan sees a single consistent set.
    val shielded = protectedKeys
    while (currentSize + requiredSpace > maxBytes) {
      // Oldest first, skipping what must survive. Nothing evictable left means
      // over budget is the outcome — the alternative is spinning here.
      val victim = fifo.firstOrNull { it.key !in shielded } ?: return
      // removeSpan synchronously calls back onSpanRemoved, which updates
      // currentSize + fifo, so the loop re-checks against the new total.
      cache.removeSpan(victim)
      if (fifo.contains(victim)) {
        // The cache index no longer holds this span, so removeSpan notified
        // nothing. Drop our own record: its bytes are unreclaimable, but
        // leaving it in place would make it the victim of every future
        // eviction and stop the cache evicting at all. Membership, not the
        // head, is the test — a protected span can sit ahead of the victim.
        // Membership stays exact because a span still in the tree always has a
        // live order entry: the entry is retired only after its last successful
        // removal. Once retired, the span sorts at the Long.MAX_VALUE sentinel,
        // past every live element, so `contains` reports false.
        onSpanRemoved(cache, victim)
      }
    }
  }
}
