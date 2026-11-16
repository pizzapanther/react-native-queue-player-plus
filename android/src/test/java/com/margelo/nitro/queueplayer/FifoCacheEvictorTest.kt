package com.margelo.nitro.queueplayer

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * Unit tests for [FifoCacheEvictor]'s eviction ordering. Drives the evictor's
 * [CacheEvictor] callbacks directly against a minimal fake [Cache] whose only
 * live method is `removeSpan`, so the tests assert insertion-order eviction
 * without a real SimpleCache or filesystem. The fake normally delegates back to
 * `onSpanRemoved`; `callsBack = false` models a span the cache index has already
 * dropped, which notifies nothing.
 */
@UnstableApi
class FifoCacheEvictorTest {

  private val removed = mutableListOf<CacheSpan>()

  private fun span(key: String, position: Long = 0, length: Long): CacheSpan =
    CacheSpan(key, position, length, 0L, File("/tmp/$key-$position"))

  @Test
  fun `requiresCacheSpanTouches is false so reads never fire touches`() {
    assertFalse(FifoCacheEvictor(1000).requiresCacheSpanTouches())
  }

  @Test
  fun `evicts the oldest-inserted resource first`() {
    val evictor = FifoCacheEvictor(maxBytes = 250)
    val cache = FakeCache(evictor)
    evictor.onSpanAdded(cache, span("a", length = 100))
    evictor.onSpanAdded(cache, span("b", length = 100))
    evictor.onSpanAdded(cache, span("c", length = 100)) // 300 > 250 → drop oldest
    assertEquals(listOf("a"), removed.map { it.key })
  }

  @Test
  fun `a whole track evicts together — spans of one url share insertion order`() {
    val evictor = FifoCacheEvictor(maxBytes = 200)
    val cache = FakeCache(evictor)
    evictor.onSpanAdded(cache, span("a", position = 0, length = 50))
    evictor.onSpanAdded(cache, span("a", position = 50, length = 50))
    evictor.onSpanAdded(cache, span("b", length = 100))
    evictor.onSpanAdded(cache, span("c", length = 100)) // 300 > 200 → both of a's spans drop first
    assertEquals(listOf("a", "a"), removed.take(2).map { it.key })
  }

  @Test
  fun `access order is ignored — a touched-newest but inserted-first track still evicts first`() {
    // FIFO keys on insertion, not lastTouchTimestamp: the span with the newest
    // touch but the oldest insertion is still the first victim.
    val evictor = FifoCacheEvictor(maxBytes = 150)
    val cache = FakeCache(evictor)
    evictor.onSpanAdded(cache, CacheSpan("first", 0, 100, 9_999L, File("/tmp/first")))
    evictor.onSpanAdded(cache, CacheSpan("second", 0, 100, 1L, File("/tmp/second"))) // 200 > 150
    assertEquals(listOf("first"), removed.map { it.key })
  }

  @Test
  fun `a re-added url gets a fresh newest order after its last span was evicted`() {
    val evictor = FifoCacheEvictor(maxBytes = 150)
    val cache = FakeCache(evictor)
    evictor.onSpanAdded(cache, span("a", length = 100))
    evictor.onSpanAdded(cache, span("b", length = 100)) // evicts a (its last span → key forgotten)
    removed.clear()
    evictor.onSpanAdded(cache, span("a", length = 100)) // a re-inserted as newest → b evicts, not a
    assertEquals(listOf("b"), removed.map { it.key })
  }

  @Test
  fun `a url retires its insertion order once its last span is gone`() {
    // The cache notifies an add even when the evictor's own set rejects the
    // span, so a count taken from the callbacks over-counts. Here both adds are
    // the same span, so one entry exists and one removal empties the url.
    val evictor = FifoCacheEvictor(maxBytes = 1000)
    val cache = FakeCache(evictor)
    val only = CacheSpan("a", 0, 100, 1L, File("/tmp/a"))
    evictor.onSpanAdded(cache, only)
    evictor.onSpanAdded(cache, only)
    evictor.onSpanAdded(cache, span("b", length = 100))
    evictor.onSpanRemoved(cache, only)
    removed.clear()

    // `a` is empty, so re-adding it stamps a fresh newest order and `b` — now
    // the oldest — is the victim. An over-count leaves `a` on its old order and
    // evicts `a` instead.
    evictor.onSpanAdded(cache, span("a", length = 100))
    evictor.onSpanAdded(cache, span("c", length = 900)) // 1100 > maxBytes
    assertEquals(listOf("b"), removed.map { it.key })
  }

  @Test
  fun `a url keeps its insertion order while any of its spans remain`() {
    val evictor = FifoCacheEvictor(maxBytes = 1000)
    val cache = FakeCache(evictor)
    val first = CacheSpan("a", 0, 100, 1L, File("/tmp/a-1"))
    val second = CacheSpan("a", 100, 100, 1L, File("/tmp/a-2"))
    evictor.onSpanAdded(cache, first)
    evictor.onSpanAdded(cache, second)
    evictor.onSpanAdded(cache, second) // rejected by the set, still notified
    evictor.onSpanAdded(cache, span("b", length = 100))
    evictor.onSpanRemoved(cache, first)
    removed.clear()

    // `a` still holds `second`, so it keeps its original (oldest) order and is
    // still the first victim. A retired order would sort it newest and evict b.
    evictor.onSpanAdded(cache, span("c", length = 900))
    assertEquals("a", removed.first().key)
    removed.clear()

    // With `a` now empty, a re-add stamps a fresh newest order. An over-count
    // from the rejected add leaves `a` on its old order and evicts it again.
    evictor.onSpanRemoved(cache, second)
    evictor.onSpanAdded(cache, span("a", length = 100))
    evictor.onSpanAdded(cache, span("d", length = 900))
    assertEquals("b", removed.first().key)
  }

  @Test(timeout = 5_000)
  fun `eviction advances past a span the cache no longer holds`() {
    // removeSpan is a no-op here, modelling a span the cache index has already
    // dropped: no onSpanRemoved callback, so the head does not move on its own.
    // The evictor must retire its own record and carry on — spinning wedges the
    // cache's lock, and giving up makes that span the victim of every later
    // eviction, which stops eviction permanently.
    val evictor = FifoCacheEvictor(maxBytes = 150)
    val deaf = FakeCache(evictor, callsBack = false)
    evictor.onSpanAdded(deaf, span("a", length = 100))
    evictor.onSpanAdded(deaf, span("b", length = 100))
    evictor.onSpanAdded(deaf, span("c", length = 100))
    assertEquals(listOf("a", "b"), removed.map { it.key })
  }

  @Test
  fun `a protected url survives eviction and the next-oldest goes instead`() {
    val evictor = FifoCacheEvictor(maxBytes = 250)
    val cache = FakeCache(evictor)
    evictor.setProtectedKeys(setOf("a"))
    evictor.onSpanAdded(cache, span("a", length = 100))
    evictor.onSpanAdded(cache, span("b", length = 100))
    evictor.onSpanAdded(cache, span("c", length = 100)) // 300 > 250
    assertEquals(listOf("b"), removed.map { it.key })
  }

  @Test(timeout = 5_000)
  fun `eviction gives up when every remaining url is protected`() {
    // Over budget with nothing evictable is a real state: the protected window
    // can outgrow the budget. Staying over budget is the outcome; spinning
    // under the cache's lock is not.
    val evictor = FifoCacheEvictor(maxBytes = 150)
    val cache = FakeCache(evictor)
    evictor.setProtectedKeys(setOf("a", "b"))
    evictor.onSpanAdded(cache, span("a", length = 100))
    evictor.onSpanAdded(cache, span("b", length = 100)) // 200 > 150, both shielded
    assertEquals(emptyList<String>(), removed.map { it.key })
  }

  @Test(timeout = 5_000)
  fun `a protected span ahead of the victim does not stall the self-heal`() {
    // The victim is not the head here: `a` is protected and sorts first, so a
    // progress check that compared the head would never see the removal fail
    // and would spin under the cache's lock.
    val evictor = FifoCacheEvictor(maxBytes = 150)
    val deaf = FakeCache(evictor, callsBack = false)
    evictor.setProtectedKeys(setOf("a"))
    evictor.onSpanAdded(deaf, span("a", length = 100))
    evictor.onSpanAdded(deaf, span("b", length = 100)) // 200 > 150; a shielded
    assertEquals(listOf("b"), removed.map { it.key })
  }

  @Test
  fun `dropping protection makes the url evictable again`() {
    val evictor = FifoCacheEvictor(maxBytes = 250)
    val cache = FakeCache(evictor)
    evictor.setProtectedKeys(setOf("a"))
    evictor.onSpanAdded(cache, span("a", length = 100))
    evictor.onSpanAdded(cache, span("b", length = 100))
    evictor.setProtectedKeys(emptySet())
    evictor.onSpanAdded(cache, span("c", length = 100)) // 300 > 250
    assertEquals(listOf("a"), removed.map { it.key })
  }

  @Test
  fun `the protected set is copied so a caller's later mutation cannot leak in`() {
    val evictor = FifoCacheEvictor(maxBytes = 250)
    val cache = FakeCache(evictor)
    val live = mutableSetOf("a")
    evictor.setProtectedKeys(live)
    live.add("b") // must not reach the evictor
    evictor.onSpanAdded(cache, span("a", length = 100))
    evictor.onSpanAdded(cache, span("b", length = 100))
    evictor.onSpanAdded(cache, span("c", length = 100)) // 300 > 250
    assertEquals(listOf("b"), removed.map { it.key })
  }

  /** Minimal [Cache]: only `removeSpan` is live (records + calls back the evictor). */
  private inner class FakeCache(
    private val evictor: FifoCacheEvictor,
    private val callsBack: Boolean = true
  ) : Cache {
    override fun removeSpan(span: CacheSpan) {
      removed.add(span)
      if (callsBack) evictor.onSpanRemoved(this, span)
    }

    override fun getUid(): Long = throw UnsupportedOperationException()
    override fun release() = throw UnsupportedOperationException()
    override fun addListener(key: String, listener: Cache.Listener): java.util.NavigableSet<CacheSpan> =
      throw UnsupportedOperationException()
    override fun removeListener(key: String, listener: Cache.Listener) =
      throw UnsupportedOperationException()
    override fun getCachedSpans(key: String): java.util.NavigableSet<CacheSpan> =
      throw UnsupportedOperationException()
    override fun getKeys(): MutableSet<String> = throw UnsupportedOperationException()
    override fun getCacheSpace(): Long = throw UnsupportedOperationException()
    override fun startReadWrite(key: String, position: Long, length: Long): CacheSpan =
      throw UnsupportedOperationException()
    override fun startReadWriteNonBlocking(key: String, position: Long, length: Long): CacheSpan =
      throw UnsupportedOperationException()
    override fun startFile(key: String, position: Long, length: Long): File =
      throw UnsupportedOperationException()
    override fun commitFile(file: File, length: Long) = throw UnsupportedOperationException()
    override fun releaseHoleSpan(holeSpan: CacheSpan) = throw UnsupportedOperationException()
    override fun removeResource(key: String) = throw UnsupportedOperationException()
    override fun isCached(key: String, position: Long, length: Long): Boolean =
      throw UnsupportedOperationException()
    override fun getCachedLength(key: String, position: Long, length: Long): Long =
      throw UnsupportedOperationException()
    override fun getCachedBytes(key: String, position: Long, length: Long): Long =
      throw UnsupportedOperationException()
    override fun applyContentMetadataMutations(key: String, mutations: ContentMetadataMutations) =
      throw UnsupportedOperationException()
    override fun getContentMetadata(key: String): ContentMetadata =
      throw UnsupportedOperationException()
  }
}
