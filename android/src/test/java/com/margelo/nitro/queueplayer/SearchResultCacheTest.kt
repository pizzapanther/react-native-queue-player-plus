package com.margelo.nitro.queueplayer

import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-Kotlin coverage for [SearchResultCache]. No Robolectric —
 * the cache is a thread-safe map with paginated read.
 */
@UnstableApi
class SearchResultCacheTest {

  private fun item(id: String) = BrowseItem(
    id = id,
    title = id,
    subtitle = null,
    artworkUrl = null,
    playable = true,
    hasChildren = null
  )

  @Test
  fun `empty cache returns null + zero count + hasResults false`() {
    val cache = SearchResultCache()
    assertNull(cache.getResults("any"))
    assertEquals(0, cache.count("any"))
    assertFalse(cache.hasResults("any"))
    assertTrue(cache.getPage("any", page = 0, pageSize = 50).isEmpty())
  }

  @Test
  fun `setResults stores by query + getResults reads back`() {
    val cache = SearchResultCache()
    val results = listOf(item("a"), item("b"))
    cache.setResults("foo", results)
    assertEquals(results, cache.getResults("foo"))
    assertEquals(2, cache.count("foo"))
    assertTrue(cache.hasResults("foo"))
  }

  @Test
  fun `setResults of empty list keeps the query as 'answered with zero'`() {
    val cache = SearchResultCache()
    cache.setResults("foo", emptyList())
    assertTrue("query is recorded as answered", cache.hasResults("foo"))
    assertEquals(0, cache.count("foo"))
  }

  @Test
  fun `setResults replaces a prior entry for the same query`() {
    val cache = SearchResultCache()
    cache.setResults("foo", listOf(item("v1-only")))
    cache.setResults("foo", listOf(item("v2-only")))
    val results = cache.getResults("foo")
    assertEquals(1, results!!.size)
    assertEquals("v2-only", results[0].id)
  }

  @Test
  fun `clearResults drops a single query`() {
    val cache = SearchResultCache()
    cache.setResults("foo", listOf(item("a")))
    cache.setResults("bar", listOf(item("b")))
    cache.clearResults("foo")
    assertFalse(cache.hasResults("foo"))
    assertTrue(cache.hasResults("bar"))
  }

  @Test
  fun `clear drops every query`() {
    val cache = SearchResultCache()
    cache.setResults("foo", listOf(item("a")))
    cache.setResults("bar", listOf(item("b")))
    cache.clear()
    assertFalse(cache.hasResults("foo"))
    assertFalse(cache.hasResults("bar"))
  }

  @Test
  fun `getPage paginates the result list`() {
    val cache = SearchResultCache()
    cache.setResults("q", (1..7).map { item("i$it") })
    val page0 = cache.getPage("q", page = 0, pageSize = 3)
    assertEquals(3, page0.size)
    assertEquals("i1", page0[0].id)

    val page2 = cache.getPage("q", page = 2, pageSize = 3)
    assertEquals(1, page2.size)
    assertEquals("i7", page2[0].id)

    val page3 = cache.getPage("q", page = 3, pageSize = 3)
    assertTrue(page3.isEmpty())
  }

  @Test
  fun `getPage with pageSize lt 1 returns full list`() {
    val cache = SearchResultCache()
    cache.setResults("q", (1..3).map { item("i$it") })
    assertEquals(3, cache.getPage("q", page = 0, pageSize = 0).size)
    assertEquals(3, cache.getPage("q", page = 0, pageSize = -1).size)
  }

  @Test
  fun `getPage with negative page returns empty`() {
    val cache = SearchResultCache()
    cache.setResults("q", listOf(item("a")))
    assertTrue(cache.getPage("q", page = -1, pageSize = 5).isEmpty())
  }

  @Test
  fun `setResults defensively copies its input`() {
    // Callers shouldn't be able to mutate the cache by holding the
    // list reference they passed in.
    val cache = SearchResultCache()
    val mutable = mutableListOf(item("a"))
    cache.setResults("q", mutable)
    mutable.add(item("b"))
    assertEquals(
      "external mutation of caller's list does not bleed into the cache",
      1,
      cache.count("q")
    )
  }
}
