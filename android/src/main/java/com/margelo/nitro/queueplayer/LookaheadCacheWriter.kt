package com.margelo.nitro.queueplayer

import android.net.Uri
import android.os.Looper
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sliding-window prefetcher for the lookahead cache.
 *
 * `reschedule(tracks, currentIndex, lookaheadCount)` reconciles the
 * desired window `[cur+1, cur+1+lookaheadCount)` of remote tracks
 * (file:// / asset:// / content:// URIs are skipped — only http/https
 * go through the cache, matching [LookaheadCache.wrapDataSourceFactory])
 * plus an optional look-back tail (`tracks[0]` when remote and not
 * the current track) against the in-flight + pending state. URLs
 * that left the window are pruned from `pendingTargets`; the
 * in-flight download is kept whenever its URL is still desired,
 * cancelled only when it's left the window. The single long-lived
 * orchestrator drains `pendingTargets` one URL at a time via Media3's
 * [CacheWriter] on `Dispatchers.IO`.
 *
 * Wired from TrackPlayer at every queue + skip mutation point.
 * Default count is [DEFAULT_LOOKAHEAD_COUNT] (3);
 * `setLookaheadCache(lookaheadCount:)` adjusts at runtime.
 *
 * Cancelling a `CacheWriter` mid-write keeps partial chunks in
 * SimpleCache. When a URL re-enters the window after a cancel, the
 * next download resumes from the recorded chunks rather than
 * re-fetching the bytes from scratch.
 *
 * **Error-recovery contract.** The prefetcher is designed for
 * flaky-network resilience:
 *
 * - Per-track download failure (IOException, HTTP error, transient
 *   network drop): caught in the orchestrator loop and logged at
 *   `Log.w`; the orchestrator continues to the next pending target.
 *   The failed URL is NOT marked as cached or in-flight, so it
 *   remains eligible for re-attempt on a subsequent reschedule.
 * - Complete network outage (every target throws): orchestrator
 *   drains pendingTargets cleanly, leaves [inFlightUrl] null, and
 *   exits. The next [reschedule] call re-enqueues the still-desired
 *   URLs, retrying each one. There is no in-prefetcher retry loop
 *   or backoff — retry is driven by the existing reschedule trigger
 *   points (every queue mutation, every skip, every natural auto-
 *   advance). When the user advances through a tunnel of cached
 *   tracks and connectivity returns, the next track-end fires
 *   `rescheduleLookahead()` and the prefetcher retries the missing
 *   window.
 * - Cancellation (URL leaves the window): drops just the slot.
 *   Media3 [CacheWriter] keeps any partial chunks already written
 *   to SimpleCache; if the URL re-enters the window later, the next
 *   download resumes from those chunks.
 *
 * Construction: production callers use [create]. Tests use the
 * internal primary constructor with a fake `downloader` strategy +
 * a test scope.
 */
@UnstableApi
class LookaheadCacheWriter @VisibleForTesting internal constructor(
  @get:VisibleForTesting internal val downloader: suspend (TrackItem) -> Unit,
  @get:VisibleForTesting internal val scope: CoroutineScope,
  @get:VisibleForTesting internal val isFullyCached: (String) -> Boolean = { false },
  /** True when the active network is cellular. Consulted only while
   *  [allowsCellularAccess] is false. */
  internal val isOnCellular: () -> Boolean = { false }
) {

  /**
   * Whether prefetch may run over cellular. False empties the desired
   * window while the active network is cellular, so nothing is
   * enqueued and an in-flight download is cancelled on the next
   * [reschedule]. Set from `setLookaheadCache`.
   */
  @Volatile
  internal var allowsCellularAccess: Boolean = true

  /**
   * Optional callback fired after each per-track download iteration
   * completes (success, failure, or cancellation handled). Used by
   * [TrackPlayer.onCacheStatusChange] surface to emit a
   * status snapshot whenever the cache state changes. Set on
   * production via [TrackPlayer.configureInternal]; tests leave
   * it null.
   */
  @get:VisibleForTesting
  @Volatile
  internal var onTrackProcessed: (() -> Unit)? = null

  /**
   * Source URL of the in-flight download, or `null` when no
   * download is mid-flight. Used by the orchestrator to decide
   * whether to cancel + by `reschedule` to know which URL is
   * already covered.
   */
  @get:VisibleForTesting
  @Volatile
  internal var inFlightUrl: String? = null
    private set

  /**
   * The Job running the in-flight download. Held so a `reschedule`
   * that drops the in-flight URL from the desired set can cancel it.
   */
  @get:VisibleForTesting
  @Volatile
  internal var inFlightJob: Job? = null
    private set

  /**
   * Lock guarding [pendingTargets], [inFlightUrl], [inFlightJob] and
   * [orchestratorJob] for cross-thread coordination. `reschedule` is
   * called on main; the orchestrator coroutine runs on `Dispatchers.IO`
   * and pops/peeks `pendingTargets` from there. The lock keeps the
   * pop-then-set-inFlight + reschedule-prune-then-cancel sequences
   * atomic relative to each other.
   */
  private val stateLock = Any()

  /**
   * FIFO of tracks the orchestrator hasn't started yet, in the
   * order they should download. `reschedule` prunes entries whose
   * URL has left the desired window and appends new desired URLs
   * in window order with the look-back tail last. Access guarded
   * by [stateLock].
   */
  @VisibleForTesting
  internal val pendingTargets: ArrayDeque<TrackItem> = ArrayDeque()

  /**
   * Long-lived orchestrator coroutine that drains [pendingTargets]
   * one URL at a time. Re-launched lazily by [reschedule] whenever
   * `pendingTargets` becomes non-empty and the previous orchestrator
   * has finished or doesn't exist.
   */
  @get:VisibleForTesting
  @Volatile
  internal var orchestratorJob: Job? = null
    private set

  /**
   * Source URL of the currently-downloading entry, or `null` when
   * no download is mid-flight. Mirrors [inFlightUrl] — exposed for
   * the cache-status snapshot. Multiple parallel downloads are not
   * supported in v1; the snapshot's `currentlyCaching` field stays
   * single-element.
   */
  val currentlyDownloadingUrl: String?
    get() = inFlightUrl

  /**
   * Default lookahead window applied when [reschedule] is called
   * without an explicit count. Mutable so
   * `setLookaheadCache(lookaheadCount:)` can update the live default.
   * The change applies on the next reschedule call (no mid-flight
   * window shift).
   */
  @get:VisibleForTesting
  @Volatile
  internal var defaultLookaheadCount: Int = DEFAULT_LOOKAHEAD_COUNT

  /**
   * Reconcile the prefetcher against a new (tracks, currentIndex,
   * lookaheadCount) snapshot. Computes the desired window
   * `[cur+1, cur+1+lookaheadCount)` of remote tracks plus an
   * optional look-back tail (`tracks[0]` when remote and not the
   * current track). Prunes pending entries that have left the
   * window, cancels the in-flight download only when its URL is
   * no longer desired, and appends any newly-desired URL not
   * already cached, in-flight, or pending. Re-launches the
   * orchestrator if it's dormant.
   */
  fun reschedule(
    tracks: List<TrackItem>,
    currentIndex: Int,
    lookaheadCount: Int = defaultLookaheadCount
  ) {
    requireMainThread()
    val desired = mutableListOf<TrackItem>()
    desired.addAll(computeTargets(tracks, currentIndex, lookaheadCount))
    lookbackTarget(tracks, currentIndex)?.let { desired.add(it) }
    // Prefetch downloads whole upcoming tracks, so on a metered connection it
    // can spend a lot of data on tracks the listener skips past. When it is not
    // permitted nothing is desired, and the reconciliation below prunes what is
    // pending and cancels what is in flight. The next reschedule refills it —
    // a track advance, a skip, a queue change, or the flag going back on.
    if (!allowsCellularAccess && isOnCellular()) {
      desired.clear()
    }
    val desiredUrls = desired.map { it.url }.toSet()

    val toCancel: Job?
    val needsOrchestrator: Boolean
    synchronized(stateLock) {
      // Prune pending entries whose URL is no longer desired,
      // preserving the existing order for retained ones.
      val retainedPending = pendingTargets.filter { it.url in desiredUrls }
      pendingTargets.clear()
      pendingTargets.addAll(retainedPending)

      // Cancel the in-flight download only when its URL is no longer
      // desired. Keeping an in-flight download whose URL is still
      // desired means a track-change reschedule (auto-advance,
      // skipToIndex within the window) doesn't waste the bytes
      // already on the wire.
      val inFlight = inFlightUrl
      toCancel = if (inFlight != null && inFlight !in desiredUrls) inFlightJob else null

      // Compute the set of URLs already accounted for so we don't
      // re-enqueue them. The in-flight URL is included only if its
      // job is alive (not cancelled and not completed). A cancelled
      // in-flight URL won't actually finish, so it must be free to
      // re-enqueue when the next reschedule re-includes it (e.g.
      // tight skip-back pattern that re-targets the URL we just
      // cancelled).
      val knownUrls = pendingTargets.map { it.url }.toMutableSet()
      if (inFlight != null && inFlightJob?.isActive == true) {
        knownUrls.add(inFlight)
      }

      for (track in desired) {
        if (track.url in knownUrls) continue
        if (isFullyCached(track.url)) continue
        pendingTargets.add(track)
        knownUrls.add(track.url)
      }

      needsOrchestrator = pendingTargets.isNotEmpty()
    }

    // Cancel outside the lock — Job.cancel() is fast but can run
    // listeners on this thread.
    // Cancelling the job is what stops the blocking write too: the download
    // body relays its coroutine's cancellation onto its own `CacheWriter`.
    toCancel?.cancel()

    if (needsOrchestrator) {
      ensureOrchestratorRunning()
    }
  }

  /**
   * Look-back target: the queue head (`tracks[0]`) when remote
   * and not the current track. Returns null for an empty queue,
   * a non-remote head, or when the head IS the current track
   * (cur == 0).
   */
  @VisibleForTesting
  internal fun lookbackTarget(
    tracks: List<TrackItem>,
    currentIndex: Int
  ): TrackItem? {
    val head = tracks.firstOrNull() ?: return null
    if (!isRemote(head.url)) return null
    if (currentIndex in tracks.indices && tracks[currentIndex].url == head.url) {
      return null
    }
    return head
  }

  /** Drain pending targets, cancel the in-flight download, and stop the orchestrator. */
  fun cancel() {
    requireMainThread()
    val toCancel: List<Job>
    synchronized(stateLock) {
      pendingTargets.clear()
      toCancel = listOfNotNull(inFlightJob, orchestratorJob)
      inFlightJob = null
      inFlightUrl = null
      orchestratorJob = null
    }
    toCancel.forEach { it.cancel() }
  }

  /**
   * Cancel + tear down the coroutine scope. Called before
   * `LookaheadCache.release()` clears the MIME store, so a prefetch
   * still in flight cannot record a MIME type after the clear.
   */
  fun release() {
    requireMainThread()
    cancel()
    scope.cancel()
  }

  /**
   * The public entry points (reschedule / cancel / release) are main-only; this
   * trap turns a future off-main caller into a crash at the call site
   * so the mistake is visible there. The fields themselves —
   * `inFlightUrl`, `inFlightJob`, `orchestratorJob`, `pendingTargets`
   * — are also written from the IO orchestrator coroutine, so they are
   * guarded by `stateLock`, not by thread affinity.
   *
   * `runCatching` swallows the "Method myLooper in android.os.Looper
   * not mocked" exception that fires on a JVM host without the Android
   * framework present; production reads the looper normally.
   */
  private fun requireMainThread() {
    val onMain = runCatching {
      Looper.myLooper() == Looper.getMainLooper()
    }.getOrNull() ?: return
    check(onMain) { "LookaheadCacheWriter must be called on the main thread" }
  }

  /**
   * Pure index/URL-scheme arithmetic — exposed for unit testing.
   * Returns the prefix of remote tracks in the lookahead window
   * `[currentIndex + 1, currentIndex + 1 + lookaheadCount)` clamped
   * to `tracks.size`. Local URIs (file:// / asset:// / content:// /
   * any non-http(s) scheme) are filtered out — they bypass the cache
   * via `DefaultDataSource`'s scheme-aware dispatch, so prefetching
   * them would waste disk + bandwidth on local data.
   */
  @VisibleForTesting
  internal fun computeTargets(
    tracks: List<TrackItem>,
    currentIndex: Int,
    lookaheadCount: Int
  ): List<TrackItem> {
    if (lookaheadCount <= 0 || tracks.isEmpty()) return emptyList()
    val start = (currentIndex + 1).coerceAtLeast(0)
    if (start >= tracks.size) return emptyList()
    // `start + lookaheadCount` overflows to a negative for a very large count,
    // which coerceAtMost then keeps and subList rejects. The count reaches here
    // from a JS Double, so Int.MAX_VALUE is one `Infinity` away.
    val remaining = tracks.size - start
    val end = start + lookaheadCount.coerceAtMost(remaining)
    return tracks.subList(start, end).filter { isRemote(it.url) }
  }

  /**
   * Cheap scheme check — delegates to [UrlSchemes.isRemote] so the
   * classification stays consistent with [TrackPlayer.pickWakeMode].
   * Recognises http / https only; file:// / asset:// / content:// /
   * any other scheme is treated as local + skipped.
   */
  private fun isRemote(url: String): Boolean = UrlSchemes.isRemote(url)

  /**
   * Launch a fresh orchestrator if none is running. The orchestrator
   * runs on `Dispatchers.IO` and coordinates with `reschedule` /
   * `cancel` (both on main) via [stateLock]. release() cancels the
   * scope and the loop body observes via `isActive`.
   */
  private fun ensureOrchestratorRunning() {
    synchronized(stateLock) {
      val existing = orchestratorJob
      if (existing != null && existing.isActive) {
        return
      }
      orchestratorJob = scope.launch {
        // Capture this coroutine's own Job identity so the finally
        // can compare against orchestratorJob. coroutineContext[Job]
        // returns the receiver coroutine — stable across the body.
        val thisJob = coroutineContext[Job]
        try {
          runOrchestrator()
        } finally {
          // Identity check: only clear ownership if this trailing
          // hook belongs to the still-current orchestrator. If a
          // cancel() + reschedule() ran between the loop body
          // returning and this finally, `orchestratorJob` already
          // points at a fresh job; clobbering it would leak the new
          // orchestrator out of tracking and let a subsequent
          // reschedule launch a second concurrent loop.
          var needsRelaunch = false
          synchronized(stateLock) {
            if (orchestratorJob === thisJob) {
              orchestratorJob = null
              needsRelaunch = pendingTargets.isNotEmpty()
            }
          }
          if (needsRelaunch) {
            ensureOrchestratorRunning()
          }
        }
      }
    }
  }

  /**
   * Drain [pendingTargets] one URL at a time. Pops the next track
   * under [stateLock], launches the download on `Dispatchers.IO`,
   * then loops. Exits when `pendingTargets` is empty or the
   * coroutine is cancelled.
   */
  private suspend fun runOrchestrator() {
    while (true) {
      val track: TrackItem = synchronized(stateLock) {
        if (pendingTargets.isEmpty()) null else pendingTargets.removeFirst()
      } ?: return
      if (!isStillActive()) return

      // Skip if already cached — covers the race where another
      // `reschedule` enqueued the URL just before a download via a
      // different path completed.
      if (isFullyCached(track.url)) {
        onTrackProcessed?.invoke()
        continue
      }

      // Inherit the scope's dispatcher (Dispatchers.IO in
      // production, the test dispatcher in unit tests). Avoids a
      // hard-coded `Dispatchers.IO` that would deadlock under
      // `runTest`'s virtual scheduler. CoroutineStart.LAZY so
      // [inFlightUrl]/[inFlightJob] publish before the downloader
      // body has any chance to observe them — `await()` below
      // triggers the lazy start.
      //
      // Cross-platform parity: iOS uses an `AsyncBarrier` to achieve
      // the same publish-before-body semantics (Swift Tasks have no
      // equivalent lazy-start option).
      val downloadDeferred: Deferred<Unit> = scope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
        downloader(track)
      }
      synchronized(stateLock) {
        inFlightUrl = track.url
        inFlightJob = downloadDeferred
      }

      try {
        downloadDeferred.await()
      } catch (e: CancellationException) {
        // Cancellation of the in-flight download drops just the slot
        // — orchestrator carries on to the next pending target. Only
        // rethrow when the orchestrator itself was cancelled (the
        // outer scope cancellation propagates to `await()` from the
        // calling coroutine, which is what we need to honour). When
        // we rethrow we also cancel the deferred so the bytes-on-the-
        // wire are torn down.
        if (!isStillActive()) {
          downloadDeferred.cancel()
          throw e
        }
      } catch (e: Exception) {
        // Swallow per-track failures so one bad URL doesn't break
        // prefetch for the rest of the window. The failed track
        // retries on the next reschedule (queue mutation / skip).
        // Errors (OutOfMemoryError etc.) propagate — those are
        // not transient and shouldn't be hidden.
        //
        // Log via runCatching: on a JVM host without the Android
        // framework present, android.util.Log is not stubbed, so a
        // Log.w call would throw "Method w in android.util.Log not
        // mocked" and terminate the prefetch loop. Production reads
        // this log normally.
        runCatching { Log.w(TAG, "Lookahead prefetch failed for ${track.url}: ${e.message}") }
      }

      synchronized(stateLock) {
        inFlightUrl = null
        inFlightJob = null
      }
      onTrackProcessed?.invoke()
    }
  }

  /**
   * Read the active flag from the currently-executing coroutine's
   * Job. Used inside [runOrchestrator] so a parent cancellation
   * surfaced via `cancel()` / `release()` halts the loop on the
   * next iteration check.
   */
  private suspend fun isStillActive(): Boolean =
    kotlin.coroutines.coroutineContext[Job]?.isActive ?: true

  companion object {
    private const val TAG = "QueuePlayer.CacheWriter"

    /**
     * Default lookahead window size. Consumers may override at
     * runtime via `setLookaheadCache(lookaheadCount:)`.
     */
    const val DEFAULT_LOOKAHEAD_COUNT: Int = 3

    /**
     * Production factory. Wires a fresh [CoroutineScope] on
     * `Dispatchers.IO` (with a [SupervisorJob] so a single failed
     * prefetch doesn't poison the scope) + the default [CacheWriter]-
     * based per-track downloader. Hoists the
     * [LookaheadCache.cacheDataSourceFactory] construction so per-track
     * downloads only re-call `createDataSource()` (cheap) instead of
     * rebuilding the factory (allocation per track). The
     * `isFullyCached` predicate threads through to the prefetcher so
     * already-cached URLs short-circuit before a CacheWriter is built.
     */
    fun create(
      cache: LookaheadCache,
      httpFactory: DataSource.Factory,
      isOnCellular: () -> Boolean
    ): LookaheadCacheWriter {
      val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
      // Wrap the upstream HTTP factory so each prefetch download
      // captures the response Content-Type into the cache's MIME
      // store. Cache hits don't go through the upstream factory, so
      // this is fired only on actual network downloads — exactly the
      // window where we'd want to learn the format. Mirrors iOS's
      // file-extension persistence on the cached file.
      val mimeCapturingFactory = MimeCapturingDataSourceFactory(
        upstream = MediaValidatingDataSourceFactory(httpFactory),
        onMime = { uri, contentType -> cache.recordMimeType(uri, contentType) }
      )
      val cacheDataSourceFactory = cache.cacheDataSourceFactory(mimeCapturingFactory)
      return LookaheadCacheWriter(
        downloader = { track -> downloadViaCacheWriter(track, cacheDataSourceFactory) },
        scope = scope,
        isFullyCached = cache::isFullyCached,
        isOnCellular = isOnCellular
      )
    }

    /**
     * Per-track download body. Builds a fresh [CacheDataSource] from the
     * shared factory and runs the synchronous `CacheWriter.cache()` on
     * `Dispatchers.IO`. `cache()` blocks and polls only its own cancelled
     * flag, so the coroutine's cancellation is relayed onto that flag by a
     * child that exists to be cancelled with it — each writer is tied to
     * its own job, which is what keeps a cancel from reaching the download
     * the orchestrator starts next.
     */
    private suspend fun downloadViaCacheWriter(
      track: TrackItem,
      cacheDataSourceFactory: CacheDataSource.Factory,
    ) = withContext(Dispatchers.IO) {
      val cacheDataSource = cacheDataSourceFactory.createDataSource()
      // Cache key = source URL. The consumer-supplied `track.id` is
      // a metadata field that varies per app (a server-side hash, a
      // catalogue URI, an app-internal counter); keying by URL keeps the
      // cache generic + correct across apps + matches iOS. Same
      // URL → same cache file (collision === "already cached").
      val dataSpec = DataSpec.Builder()
        .setUri(Uri.parse(track.url))
        .setKey(track.url)
        .build()
      val writer = CacheWriter(cacheDataSource, dataSpec, /* temporaryBuffer = */ null, /* progressListener = */ null)
      // Unconfined, so the relay resumes on the thread that cancels the job
      // rather than waiting for an IO worker: the workers are what a
      // blocking `cache()` occupies, and a cancel that queued behind them
      // would leave the download running for as long as they stay busy.
      val cancelRelay = launch(Dispatchers.Unconfined) {
        try {
          awaitCancellation()
        } finally {
          writer.cancel()
        }
      }
      try {
        ensureActive()
        writer.cache()
      } catch (e: IOException) {
        // A cancelled writer throws InterruptedIOException; report it as the
        // cancellation it is so the orchestrator does not log a failed prefetch.
        if (!isActive) throw CancellationException("prefetch cancelled").also { it.initCause(e) }
        throw e
      } finally {
        cancelRelay.cancel()
      }
    }
  }
}
