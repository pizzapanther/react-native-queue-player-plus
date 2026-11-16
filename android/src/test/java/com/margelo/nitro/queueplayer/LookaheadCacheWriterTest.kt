package com.margelo.nitro.queueplayer

import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LookaheadCacheWriter] unit tests.
 *
 * Two surfaces:
 *
 * 1. **`computeTargets` (pure)** — windowing arithmetic over (tracks,
 *    currentIndex, lookaheadCount), plus the file://-bypass filter.
 *
 * 2. **Orchestration via fake `downloader` strategy on a TestScope**
 *    — verifies the sliding-window enqueue + prune behaviour, the
 *    look-back tail, the in-flight keep/cancel rule, and that
 *    release() drains everything.
 *
 * Plain JUnit (no Robolectric) — `LookaheadCacheWriter`'s
 * orchestration path doesn't touch Android APIs. The production
 * `downloader` (which constructs a real `CacheWriter`) is exercised
 * indirectly by `LookaheadCacheTest` and the lifecycle tests in
 * `TrackPlayerLifecycleTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@UnstableApi
class LookaheadCacheWriterTest {

  // --- computeTargets

  private fun newWriter(): LookaheadCacheWriter =
    LookaheadCacheWriter(
      downloader = { /* unused for computeTargets exercises */ },
      scope = CoroutineScope(StandardTestDispatcher() + SupervisorJob())
    )

  @Test
  fun `computeTargets returns empty for empty queue`() {
    val w = newWriter()
    assertTrue(w.computeTargets(emptyList(), currentIndex = -1, lookaheadCount = 3).isEmpty())
    assertTrue(w.computeTargets(emptyList(), currentIndex = 0, lookaheadCount = 3).isEmpty())
  }

  @Test
  fun `computeTargets clamps a lookaheadCount that would overflow the range`() {
    // The count arrives as a JS Double, so Infinity reaches Int.MAX_VALUE here
    // and `start + count` wraps negative — a range subList rejects outright.
    val w = newWriter()
    val tracks = (1..3).map { httpTrack("t$it") }
    assertEquals(
      2,
      w.computeTargets(tracks, currentIndex = 0, lookaheadCount = Int.MAX_VALUE).size
    )
  }

  @Test
  fun `computeTargets returns empty for non-positive lookaheadCount`() {
    val w = newWriter()
    val q = listOf(httpTrack("a"), httpTrack("b"))
    assertTrue(w.computeTargets(q, currentIndex = 0, lookaheadCount = 0).isEmpty())
    assertTrue(w.computeTargets(q, currentIndex = 0, lookaheadCount = -1).isEmpty())
  }

  @Test
  fun `computeTargets returns next N remote tracks past currentIndex`() {
    val w = newWriter()
    val q = (0..9).map { httpTrack("t$it") }
    val targets = w.computeTargets(q, currentIndex = 2, lookaheadCount = 3)
    assertEquals(listOf("t3", "t4", "t5"), targets.map { it.id })
  }

  @Test
  fun `computeTargets clamps the window to end of queue`() {
    val w = newWriter()
    val q = (0..4).map { httpTrack("t$it") }
    val targets = w.computeTargets(q, currentIndex = 3, lookaheadCount = 3)
    assertEquals(listOf("t4"), targets.map { it.id })
  }

  @Test
  fun `computeTargets returns empty when cur is at the tail`() {
    val w = newWriter()
    val q = (0..4).map { httpTrack("t$it") }
    val targets = w.computeTargets(q, currentIndex = 4, lookaheadCount = 3)
    assertTrue(targets.isEmpty())
  }

  @Test
  fun `computeTargets starts at index 0 when currentIndex is -1`() {
    // Documented contract: cur == -1 (queue populated, no current
    // track yet — e.g. addToQueue into empty queue) prefetches from
    // the front so a subsequent skipToIndex(0) + play() hits cached
    // bytes.
    val w = newWriter()
    val q = (0..4).map { httpTrack("t$it") }
    val targets = w.computeTargets(q, currentIndex = -1, lookaheadCount = 3)
    assertEquals(listOf("t0", "t1", "t2"), targets.map { it.id })
  }

  @Test
  fun `computeTargets clamps a sub-minus-one currentIndex to start at 0`() {
    val w = newWriter()
    val q = (0..2).map { httpTrack("t$it") }
    val targets = w.computeTargets(q, currentIndex = -42, lookaheadCount = 3)
    assertEquals(listOf("t0", "t1", "t2"), targets.map { it.id })
  }

  @Test
  fun `computeTargets filters out file URIs`() {
    val w = newWriter()
    val q = listOf(
      httpTrack("a"),
      makeTrack("local-1", "file:///tmp/song.m4a"),
      httpTrack("c"),
      makeTrack("local-2", "file:///tmp/other.m4a"),
      httpTrack("e")
    )
    // Window starts at index 1 (cur=0, +1) and covers up to 4. Locals at 1, 3 are dropped.
    val targets = w.computeTargets(q, currentIndex = 0, lookaheadCount = 4)
    assertEquals(listOf("c", "e"), targets.map { it.id })
  }

  @Test
  fun `computeTargets filters out asset and content URIs`() {
    val w = newWriter()
    val q = listOf(
      httpTrack("a"),
      makeTrack("asset", "asset:///bundled.mp3"),
      makeTrack("content", "content://media/external/audio/123"),
      httpTrack("d")
    )
    val targets = w.computeTargets(q, currentIndex = 0, lookaheadCount = 3)
    assertEquals(listOf("d"), targets.map { it.id })
  }

  @Test
  fun `computeTargets rejects non-progressive streaming schemes`() {
    // RTSP / RTMP / DASH / HLS variants must be skipped by the
    // prefetch path; the cache only handles seekable byte-range
    // downloads. The Kotlin filter enforces http/https only.
    val w = newWriter()
    val q = listOf(
      makeTrack("rtsp-track", "rtsp://example.com/stream"),
      makeTrack("rtmp-track", "rtmp://example.com/live"),
      makeTrack("dash-track", "dash://example.com/manifest.mpd"),
      makeTrack("ws-track", "ws://example.com/socket"),
      makeTrack("mms-track", "mms://example.com/stream"),
      makeTrack("srt-track", "srt://example.com/feed"),
      makeTrack("udp-track", "udp://239.0.0.1:1234"),
      httpTrack("legit")
    )
    val targets = w.computeTargets(q, currentIndex = -1, lookaheadCount = 8)
    assertEquals(listOf("legit"), targets.map { it.id })
  }

  @Test
  fun `computeTargets accepts both http and https schemes`() {
    val w = newWriter()
    val q = listOf(
      httpTrack("a"),
      makeTrack("https-track", "https://example.com/2.m4a"),
      makeTrack("http-track", "http://example.com/3.m4a")
    )
    val targets = w.computeTargets(q, currentIndex = 0, lookaheadCount = 3)
    assertEquals(listOf("https-track", "http-track"), targets.map { it.id })
  }

  // --- Orchestration

  @Test
  fun `reschedule with empty targets leaves orchestrator idle`() = runTest {
    val downloads = mutableListOf<String>()
    val writer = LookaheadCacheWriter(
      downloader = { track -> downloads.add(track.id ?: "") },
      scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
    )

    writer.reschedule(emptyList(), currentIndex = 0)
    advanceUntilIdle()

    assertNull(writer.orchestratorJob)
    assertTrue(downloads.isEmpty())
  }

  @Test
  fun `reschedule dispatches downloads sequentially in queue order`() = runTest {
    val downloads = mutableListOf<String>()
    val writer = LookaheadCacheWriter(
      downloader = { track -> downloads.add(track.id ?: "") },
      scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
    )

    val q = (0..4).map { httpTrack("t$it") }
    writer.reschedule(q, currentIndex = 0, lookaheadCount = 3)
    advanceUntilIdle()

    // currentIndex = 0 → look-back is suppressed (head IS current).
    assertEquals(listOf("t1", "t2", "t3"), downloads)
  }

  @Test
  fun `reschedule keeps in-flight when its URL is still in the new window`() = runBlocking {
    // Sliding window: lookaheadCount widens but the in-flight URL
    // old-1 remains in the desired set. The orchestrator must KEEP
    // the in-flight download rather than cancel it.
    val started = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val downloads = mutableListOf<String>()
    val mutex = Mutex()
    val writer = LookaheadCacheWriter(
      downloader = { track ->
        mutex.withLock { downloads.add(track.id ?: "") }
        if (track.id == "old-1") {
          started.complete(Unit)
          release.await()
        }
      },
      scope = CoroutineScope(SupervisorJob())
    )

    val queue = listOf(
      httpTrack("cur"), httpTrack("old-1"), httpTrack("old-2"),
      httpTrack("old-3"), httpTrack("old-4")
    )

    writer.reschedule(queue, currentIndex = 0, lookaheadCount = 1)
    started.await()
    val inFlightBefore = writer.inFlightJob

    // Same queue, lookaheadCount widened so old-1 stays in window.
    writer.reschedule(queue, currentIndex = 0, lookaheadCount = 3)

    assertEquals("in-flight URL must be retained when still desired",
      "https://example.com/old-1.m4a", writer.inFlightUrl)
    assertEquals("in-flight Job must be the same instance",
      inFlightBefore, writer.inFlightJob)
    assertFalse("in-flight Job must not be cancelled",
      writer.inFlightJob?.isCancelled ?: true)

    release.complete(Unit)
    writer.orchestratorJob?.join()

    val firstCount = mutex.withLock { downloads.count { it == "old-1" } }
    assertEquals("old-1 must download exactly once", 1, firstCount)
  }

  @Test
  fun `reschedule cancels in-flight when its URL leaves the window`() = runBlocking {
    val started = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val newStarted = CompletableDeferred<Unit>()
    val downloads = mutableListOf<String>()
    val mutex = Mutex()
    val writer = LookaheadCacheWriter(
      downloader = { track ->
        mutex.withLock { downloads.add(track.id ?: "") }
        when (track.id) {
          "old-1" -> { started.complete(Unit); release.await() }
          "new-1" -> newStarted.complete(Unit)
        }
      },
      scope = CoroutineScope(SupervisorJob())
    )

    writer.reschedule(
      tracks = listOf(httpTrack("cur"), httpTrack("old-1"), httpTrack("old-2")),
      currentIndex = 0,
      lookaheadCount = 3
    )
    started.await()

    // Disjoint queue: old-1 leaves the window.
    writer.reschedule(
      tracks = listOf(httpTrack("cur"), httpTrack("new-1")),
      currentIndex = 0,
      lookaheadCount = 3
    )

    newStarted.await()
    release.complete(Unit)
    writer.orchestratorJob?.join()

    val downloaded = mutex.withLock { downloads.toList() }
    assertTrue("new-1 must have been downloaded after old-1 left the window",
      "new-1" in downloaded)
    assertFalse("old-2 must not have been downloaded — pruned out before iteration",
      "old-2" in downloaded)
  }

  @Test
  fun `reschedule appends lookback as the last target after forward window`() = runTest {
    val downloads = mutableListOf<String>()
    val writer = LookaheadCacheWriter(
      downloader = { track -> downloads.add(track.id ?: "") },
      scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
    )

    val q = (0..9).map { httpTrack("t$it") }
    // currentIndex = 5 → forward window [6,7,8] + look-back t0.
    writer.reschedule(q, currentIndex = 5, lookaheadCount = 3)
    advanceUntilIdle()

    assertEquals("look-back t0 must follow the forward window",
      listOf("t6", "t7", "t8", "t0"), downloads)
  }

  @Test
  fun `reschedule omits lookback when current track is t0`() = runTest {
    val downloads = mutableListOf<String>()
    val writer = LookaheadCacheWriter(
      downloader = { track -> downloads.add(track.id ?: "") },
      scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
    )

    val q = (0..4).map { httpTrack("t$it") }
    writer.reschedule(q, currentIndex = 0, lookaheadCount = 3)
    advanceUntilIdle()

    assertEquals("look-back must not duplicate the current track",
      listOf("t1", "t2", "t3"), downloads)
  }

  @Test
  fun `reschedule omits lookback when t0 is non-remote`() = runTest {
    val downloads = mutableListOf<String>()
    val writer = LookaheadCacheWriter(
      downloader = { track -> downloads.add(track.id ?: "") },
      scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
    )

    val q = listOf(
      makeTrack("local-0", "file:///tmp/0.m4a"),
      httpTrack("t1"), httpTrack("t2"), httpTrack("t3"), httpTrack("t4")
    )

    // currentIndex = 2, lookahead = 1 → forward = [t3]; look-back
    // would be t0 but t0 is local → look-back suppressed.
    writer.reschedule(q, currentIndex = 2, lookaheadCount = 1)
    advanceUntilIdle()

    assertEquals("non-remote head must not be appended as a look-back target",
      listOf("t3"), downloads)
  }

  @Test
  fun `reschedule does not re-enqueue an already-cached URL`() = runTest {
    val downloads = mutableListOf<String>()
    val writer = LookaheadCacheWriter(
      downloader = { track -> downloads.add(track.id ?: "") },
      scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()),
      isFullyCached = { url -> url == "https://example.com/t2.m4a" }
    )

    val q = (0..4).map { httpTrack("t$it") }
    writer.reschedule(q, currentIndex = 0, lookaheadCount = 3)
    advanceUntilIdle()

    assertEquals("t2 is already cached and must be skipped",
      listOf("t1", "t3"), downloads)
  }

  @Test
  fun `sliding window keeps cache filled across N successive auto-advances`() = runBlocking {
    // Simulate playback advancing one track at a time. Each
    // reschedule slides the window forward by one; the
    // isFullyCached predicate reports any URL the downloader has
    // recorded so the de-duplication path is exercised. Verify all
    // remote tracks within reach download exactly once across the
    // run.
    //
    // Why Dispatchers.Default + SupervisorJob() instead of the
    // test scheduler used by the orchestration tests above: the
    // CoroutineStart.LAZY Deferred + synchronized(stateLock) shape
    // requires a real dispatcher to run the lazy body — the test
    // scheduler doesn't progress until advanceUntilIdle, but the
    // lock-acquire on the orchestrator coroutine and the
    // reschedule's lock-acquire on main need to be scheduled on
    // distinct workers. CPU-load-sensitive but reliable; the
    // orchestratorJob.join() at the end provides ordering.
    val downloaded = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    val downloads = mutableListOf<String>()
    val mutex = Mutex()
    val writer = LookaheadCacheWriter(
      downloader = { track ->
        mutex.withLock {
          downloads.add(track.id ?: "")
        }
        downloaded.add(track.url)
      },
      scope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
      isFullyCached = { url -> url in downloaded }
    )

    val q = (0..7).map { httpTrack("t$it") }
    for (cur in 0..6) {
      writer.reschedule(q, currentIndex = cur, lookaheadCount = 3)
      writer.orchestratorJob?.join()
    }

    val seen = mutex.withLock { downloads.toList() }
    val unique = seen.toSet()
    val expected = setOf("t0", "t1", "t2", "t3", "t4", "t5", "t6", "t7")
    assertEquals("every remote track in the window range must download at least once",
      expected, unique)
    for (url in unique) {
      assertEquals("track $url must download exactly once across successive advances",
        1, seen.count { it == url })
    }
  }

  @Test
  fun `reschedule swallows per-track IOException and continues to next`() = runTest {
    val downloads = mutableListOf<String>()
    val writer = LookaheadCacheWriter(
      downloader = { track ->
        downloads.add(track.id ?: "")
        if (track.id == "t2") throw java.io.IOException("simulated transient failure")
      },
      scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
    )

    val q = (0..4).map { httpTrack("t$it") }
    writer.reschedule(q, currentIndex = 0, lookaheadCount = 3)
    advanceUntilIdle()

    // t2 threw; the loop kept going.
    assertEquals(listOf("t1", "t2", "t3"), downloads)
  }

  @Test
  fun `previously errored URL re-enqueues on next reschedule`() = runTest {
    // Network-flake recovery: a transient error swallows the
    // download attempt without marking the URL "done"; the next
    // reschedule that still wants that URL must retry it.
    val downloads = mutableListOf<String>()
    val failing = mutableSetOf("t2")
    val writer = LookaheadCacheWriter(
      downloader = { track ->
        downloads.add(track.id ?: "")
        if (track.id in failing) throw java.io.IOException("transient")
      },
      scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
    )

    val q = (0..4).map { httpTrack("t$it") }
    writer.reschedule(q, currentIndex = 0, lookaheadCount = 3)
    advanceUntilIdle()
    assertEquals(listOf("t1", "t2", "t3"), downloads)

    // Connection restored.
    failing.clear()

    writer.reschedule(q, currentIndex = 0, lookaheadCount = 3)
    advanceUntilIdle()

    val t2Count = downloads.count { it == "t2" }
    assertTrue(
      "t2 must re-attempt on next reschedule after a swallowed error (saw $t2Count attempts)",
      t2Count >= 2
    )
  }

  @Test
  fun `network outage drains cleanly and recovers on next reschedule`() = runTest {
    // Complete-outage recovery: every target throws. Orchestrator
    // must drain pendingTargets cleanly (not hang), leaving the
    // writer idle and ready for a fresh reschedule when connectivity
    // returns.
    val downloads = mutableListOf<String>()
    val failing = mutableSetOf("t1", "t2", "t3")
    val writer = LookaheadCacheWriter(
      downloader = { track ->
        downloads.add(track.id ?: "")
        if (track.id in failing) throw java.io.IOException("outage")
      },
      scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
    )

    val q = (0..4).map { httpTrack("t$it") }
    writer.reschedule(q, currentIndex = 0, lookaheadCount = 3)
    advanceUntilIdle()

    assertEquals(listOf("t1", "t2", "t3"), downloads)
    assertNull("orchestrator must be idle after drain", writer.inFlightUrl)

    // Connection restored.
    failing.clear()

    writer.reschedule(q, currentIndex = 0, lookaheadCount = 3)
    advanceUntilIdle()

    assertEquals(
      "second reschedule must retry all 3 missing URLs after the outage",
      6, downloads.size
    )
  }

  @Test
  fun `cancel then reschedule does not spawn duplicate orchestrators`() = runBlocking {
    // Cancel-then-reschedule must hand off cleanly: the cancelled
    // orchestrator's finally block fires AFTER the new reschedule
    // launches a fresh orchestrator. Without an identity check, the
    // stale finally would clobber the new orchestrator's tracking
    // and a subsequent reschedule would launch a second concurrent
    // loop racing over inFlightUrl.
    val firstStarted = CompletableDeferred<Unit>()
    val firstRelease = CompletableDeferred<Unit>()
    val downloads = mutableListOf<String>()
    val writer = LookaheadCacheWriter(
      downloader = { track ->
        synchronized(downloads) { downloads.add(track.id ?: "") }
        if (track.id == "old-1") {
          firstStarted.complete(Unit)
          firstRelease.await()
        }
      },
      scope = CoroutineScope(SupervisorJob())
    )

    writer.reschedule(
      listOf(httpTrack("cur"), httpTrack("old-1"), httpTrack("old-2")),
      currentIndex = 0,
      lookaheadCount = 3
    )
    firstStarted.await()

    writer.cancel()
    writer.reschedule(
      listOf(httpTrack("cur"), httpTrack("new-1"), httpTrack("new-2")),
      currentIndex = 0,
      lookaheadCount = 3
    )

    // Release the cancelled chain — its finally must NOT clobber the
    // new orchestrator's tracking.
    firstRelease.complete(Unit)
    writer.orchestratorJob?.join()

    assertEquals(
      "new-1 must download exactly once — duplicate orchestrators would race",
      1, downloads.count { it == "new-1" }
    )
    assertEquals(
      "new-2 must download exactly once",
      1, downloads.count { it == "new-2" }
    )

    // Post-drain reschedule must successfully relaunch.
    writer.reschedule(
      listOf(httpTrack("cur"), httpTrack("after-drain")),
      currentIndex = 0,
      lookaheadCount = 1
    )
    writer.orchestratorJob?.join()
    assertTrue(
      "post-drain reschedule must launch a fresh orchestrator",
      downloads.contains("after-drain")
    )
  }

  @Test
  fun `cancel stops in-flight job without tearing down scope`() = runBlocking {
    val started1 = CompletableDeferred<Unit>()
    val release1 = CompletableDeferred<Unit>()
    val started2 = CompletableDeferred<Unit>()
    val release2 = CompletableDeferred<Unit>()
    var round = 0
    val writer = LookaheadCacheWriter(
      downloader = {
        round += 1
        if (round == 1) {
          started1.complete(Unit)
          release1.await()
        } else {
          started2.complete(Unit)
          release2.await()
        }
      },
      scope = CoroutineScope(SupervisorJob())
    )

    writer.reschedule(listOf(httpTrack("cur"), httpTrack("next")), currentIndex = 0)
    started1.await()
    val orchestrator = writer.orchestratorJob!!
    writer.cancel()
    release1.complete(Unit)
    orchestrator.join()

    assertNull(writer.orchestratorJob)
    assertNull(writer.inFlightUrl)

    // Scope is still alive — reschedule launches a new orchestrator that
    // begins downloading. Wait for the new download to start before
    // asserting orchestratorJob, otherwise a fast no-op downloader can
    // drain pendingTargets and clear orchestratorJob in its finally
    // block before the assertion reads it.
    writer.reschedule(listOf(httpTrack("cur"), httpTrack("after-cancel")), currentIndex = 0)
    started2.await()
    assertNotNull("scope must survive cancel()", writer.orchestratorJob)
    release2.complete(Unit)
    writer.orchestratorJob?.join()
    Unit
  }

  @Test
  fun `release cancels in-flight job and tears down the scope`() = runBlocking {
    val started = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val scopeJob = SupervisorJob()
    val writer = LookaheadCacheWriter(
      downloader = { _ ->
        started.complete(Unit)
        release.await()
      },
      scope = CoroutineScope(scopeJob)
    )

    writer.reschedule(listOf(httpTrack("cur"), httpTrack("next")), currentIndex = 0)
    started.await()
    val orchestrator = writer.orchestratorJob!!
    writer.release()
    release.complete(Unit)
    orchestrator.join()

    assertTrue("scope must be cancelled after release()", scopeJob.isCancelled)
    assertNull("orchestratorJob must be cleared after release()", writer.orchestratorJob)
  }

  // --- Helpers

  // --- allowsCellularAccess

  @Test
  fun `prefetch is skipped while cellular is not permitted and the network is cellular`() = runTest {
    val downloads = mutableListOf<String>()
    val writer = LookaheadCacheWriter(
      downloader = { track -> downloads.add(track.id ?: "") },
      scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()),
      isOnCellular = { true }
    )
    writer.allowsCellularAccess = false

    writer.reschedule((0..4).map { httpTrack("t$it") }, currentIndex = 0, lookaheadCount = 3)
    advanceUntilIdle()

    assertTrue(downloads.isEmpty())
    assertNull(writer.orchestratorJob)
  }

  @Test
  fun `prefetch runs on a non-cellular network even when cellular is not permitted`() = runTest {
    val downloads = mutableListOf<String>()
    val writer = LookaheadCacheWriter(
      downloader = { track -> downloads.add(track.id ?: "") },
      scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()),
      isOnCellular = { false }
    )
    writer.allowsCellularAccess = false

    writer.reschedule((0..4).map { httpTrack("t$it") }, currentIndex = 0, lookaheadCount = 3)
    advanceUntilIdle()

    assertEquals(listOf("t1", "t2", "t3"), downloads)
  }

  @Test
  fun `prefetch runs on cellular while it is permitted`() = runTest {
    val downloads = mutableListOf<String>()
    val writer = LookaheadCacheWriter(
      downloader = { track -> downloads.add(track.id ?: "") },
      scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()),
      isOnCellular = { true }
    )

    writer.reschedule((0..4).map { httpTrack("t$it") }, currentIndex = 0, lookaheadCount = 3)
    advanceUntilIdle()

    assertEquals(listOf("t1", "t2", "t3"), downloads)
  }

  @Test
  fun `handing off to cellular cancels the download in flight`() = runBlocking<Unit> {
    val started = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    var onCellular = false
    val writer = LookaheadCacheWriter(
      downloader = { started.complete(Unit); release.await() },
      scope = CoroutineScope(SupervisorJob()),
      isOnCellular = { onCellular }
    )
    writer.allowsCellularAccess = false
    val queue = (0..4).map { httpTrack("t$it") }
    writer.reschedule(queue, currentIndex = 0, lookaheadCount = 3)
    started.await()
    // Captured before the handoff: the orchestrator clears `inFlightJob` on its
    // own thread once the cancelled download resumes it, so reading the slot
    // afterwards races that clear.
    val inFlightBefore = writer.inFlightJob

    onCellular = true
    writer.reschedule(queue, currentIndex = 0, lookaheadCount = 3)

    assertTrue("the download in flight must be cancelled", inFlightBefore?.isCancelled == true)
    release.complete(Unit)
  }

  @Test
  fun `turning cellular back on refills the window on the next reschedule`() = runTest {
    val downloads = mutableListOf<String>()
    val writer = LookaheadCacheWriter(
      downloader = { track -> downloads.add(track.id ?: "") },
      scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()),
      isOnCellular = { true }
    )
    val queue = (0..4).map { httpTrack("t$it") }
    writer.allowsCellularAccess = false
    writer.reschedule(queue, currentIndex = 0, lookaheadCount = 3)
    advanceUntilIdle()
    assertTrue(downloads.isEmpty())

    writer.allowsCellularAccess = true
    writer.reschedule(queue, currentIndex = 0, lookaheadCount = 3)
    advanceUntilIdle()

    assertEquals(listOf("t1", "t2", "t3"), downloads)
  }

  private fun httpTrack(id: String): TrackItem =
    makeTrack(id, "https://example.com/$id.m4a")

  private fun makeTrack(id: String, url: String): TrackItem =
    TrackItem(
      id = id,
      url = url,
      title = null,
      artist = null,
      album = null,
      duration = null,
      artworkUrl = null,
      extras = null,
      replayGainTrackGain = null, replayGainTrackPeak = null,
      replayGainAlbumGain = null, replayGainAlbumPeak = null
    )
}
