package com.margelo.nitro.queueplayer

import androidx.media3.common.util.UnstableApi
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-query cache of search results, populated by the JS-side
 * search responder + read by `onGetSearchResult` on the
 * `MediaLibrarySession.Callback`. Keyed on the raw query string —
 * matching what `MediaBrowser.search(query)` and
 * `MediaBrowser.getSearchResult(query, ...)` thread through.
 *
 * Concurrency: backed by [ConcurrentHashMap] so the JS responder
 * thread (callback dispatch) can write while a controller's reader
 * thread (Media3's library executor) is paginating an in-flight
 * query.
 *
 * Eviction: simple — when a new result list arrives for a query,
 * the prior entry is replaced. `clear()` drops everything (e.g. on
 * a fresh BrowseSnapshot push, the consumer might want to clear
 * stale results — call site decides).
 */
@UnstableApi
class SearchResultCache {

  private val results = ConcurrentHashMap<String, List<BrowseItem>>()

  /** Replace the cached results for [query]. Empty list keeps the
   *  query in the cache as "answered with zero results". */
  fun setResults(query: String, items: List<BrowseItem>) {
    results[query] = items.toList()
  }

  /** Drop the cached results for [query], if any. Used when the
   *  caller wants to force a fresh JS dispatch on the next
   *  `onSearch`. */
  fun clearResults(query: String) {
    results.remove(query)
  }

  /** Drop every cached query. */
  fun clear() {
    results.clear()
  }

  /** Read the full result list for [query], or `null` when the
   *  query hasn't been answered yet. */
  fun getResults(query: String): List<BrowseItem>? = results[query]

  /** Page slice for [query]. Returns empty list when no entry is
   *  cached for the query. Pagination semantics match
   *  [BrowseDataProvider.getChildren]: 0-based page, `pageSize <= 0`
   *  treats as unbounded, negative page returns empty,
   *  out-of-range page returns empty, partial last page returns
   *  the tail. */
  fun getPage(query: String, page: Int, pageSize: Int): List<BrowseItem> {
    val full = results[query] ?: return emptyList()
    if (pageSize <= 0) return full
    if (page < 0) return emptyList()
    val from = page * pageSize
    if (from >= full.size) return emptyList()
    val to = (from + pageSize).coerceAtMost(full.size)
    return full.subList(from, to)
  }

  /** Total item count cached for [query]. Returned by
   *  `notifySearchResultChanged`. Returns 0 when no entry. */
  fun count(query: String): Int = results[query]?.size ?: 0

  /** True when [query] has been answered (even with zero results). */
  fun hasResults(query: String): Boolean = results.containsKey(query)
}
