package com.margelo.nitro.queueplayer

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Lookahead cache backed by Media3's [SimpleCache] + LRU evictor.
 * Android-side mirror of the iOS `LookaheadCache.swift`.
 *
 * Constructs the on-disk cache rooted at
 * `context.cacheDir/<cacheDirName>` using a [StandaloneDatabaseProvider]
 * to track entries across process restarts. Wired into the playback
 * data-source chain via [wrapDataSourceFactory]; proactive cache-
 * warming runs through [LookaheadCacheWriter].
 *
 * **SimpleCache + DatabaseProvider are per-folder process singletons.**
 * Media3 enforces a one-live-SimpleCache-per-directory invariant via a
 * static `lockedFolders` registry — release + reconstruct within the
 * same process races the registry update. The shared instances live in
 * the companion object's [sharedInstances] map keyed by absolute folder
 * path; subsequent `LookaheadCache` constructions targeting the same
 * folder reuse the existing SimpleCache. The first construction's
 * `maxSizeBytes` wins for the process lifetime — consumers wanting a
 * different cache budget must use a different `cacheDirName` or restart
 * the process. Per-instance state (the MIME store) is NOT shared.
 */
@UnstableApi
class LookaheadCache(
  context: Context,
  maxSizeBytes: Long,
  cacheDirName: String = DEFAULT_CACHE_DIR_NAME,
  evictionPolicy: EvictionPolicy = EvictionPolicy.LRU
) {

  /**
   * Resolved on-disk cache directory: `context.cacheDir/<cacheDirName>`.
   * Resolved through the application context, as the shared-instance key is,
   * so a wrapper context with its own `cacheDir` cannot split the two.
   */
  @VisibleForTesting
  val cacheDir: File = File(context.applicationContext.cacheDir, cacheDirName)

  private val shared: SharedCacheInstance =
    sharedInstanceFor(context.applicationContext, cacheDir, maxSizeBytes, evictionPolicy)

  /**
   * The Media3 [SimpleCache] backing the lookahead store. Exposed
   * `internal` so tests can inspect cache keys + sizes without
   * round-tripping through the data-source chain.
   */
  @VisibleForTesting
  internal val simpleCache: SimpleCache = shared.cache

  private val protectingEvictor: FifoCacheEvictor? = shared.protectingEvictor

  /**
   * Name the urls eviction must not take — the playing track and its
   * neighbours. Eviction runs on every span commit, so without this the entry
   * backing the playing track can be deleted while it plays.
   *
   * A no-op under the LRU policy, whose evictor has no protected-key concept.
   * LRU is not an equivalent safeguard — it bumps the recency of the span just
   * read, not the whole resource — so on Android the playing track is only
   * genuinely protected under FIFO. iOS protects under both policies.
   */
  fun setProtectedUrls(urls: Set<String>) {
    protectingEvictor?.setProtectedKeys(urls)
  }

  /**
   * Whether this cache's evictor honours [setProtectedUrls]. False under LRU,
   * letting callers skip building a set that would be discarded.
   */
  val honoursProtection: Boolean
    get() = protectingEvictor != null

  /**
   * Per-URL MIME store populated by the prefetch path's
   * Content-Type-capturing DataSource wrapper. Mirrors the iOS
   * cache's content-type → file-extension persistence, but applied
   * via [androidx.media3.common.MediaItem.Builder.setMimeType] on
   * playback rather than file naming (Media3's SimpleCache stores
   * spans, not single playable files).
   *
   * In-memory only: re-populates after a process restart on the
   * first prefetch hit. Drop on [release] / [clear].
   */
  private val mimeStore = ConcurrentHashMap<String, String>()

  /**
   * Wrap an upstream HTTP [DataSource.Factory] so every read first
   * checks [simpleCache] and writes downloaded chunks into it.
   *
   * Caller must pass the bare HTTP factory (e.g. `DefaultHttpDataSource.Factory`),
   * NOT a [androidx.media3.datasource.DefaultDataSource.Factory]:
   * `DefaultDataSource` sniffs scheme and routes file:// / asset:// /
   * content:// URIs through built-in DataSources that should bypass
   * the cache (local files don't need disk caching of disk reads).
   * Wrapping the bare HTTP factory means only remote playback
   * traffic hits the LRU cache.
   *
   * `FLAG_IGNORE_CACHE_ON_ERROR` lets a corrupt cache entry
   * transparently fall through to a re-download instead of
   * surfacing a load error.
   */
  fun wrapDataSourceFactory(upstream: DataSource.Factory): DataSource.Factory {
    // Read-only for playback. A write here would commit whatever the origin
    // answered with — a server's error envelope included — as a track, and the
    // body check that refuses one belongs on the prefetch path, where a false
    // refusal costs a prefetch instead of the track. `null` sets Media3's
    // `cacheIsReadOnly`, which is tested before the default-sink fallback.
    // So playing a track does not cache it and does not complete a partial
    // prefetch of it; only the prefetcher writes. Matches iOS, whose
    // read-through server also only reads.
    val cached = cacheDataSourceFactory(upstream).setCacheWriteDataSinkFactory(null)
    // Decided per data source, which Media3 creates per item load, so a
    // change to `servesPlayback` reaches the next item without rebuilding
    // the player; the item already loading keeps the source it opened.
    return DataSource.Factory {
      if (servesPlayback) cached.createDataSource() else upstream.createDataSource()
    }
  }

  /**
   * Whether the player's reads go through this cache. False while the
   * consumer has the cache disabled: every read then goes to the origin and
   * nothing is written, whatever the disk already holds. Written on main by
   * `TrackPlayer`, read on Media3's loading thread when an item opens.
   */
  @Volatile
  var servesPlayback: Boolean = true

  /**
   * Build a [CacheDataSource.Factory] rooted at [simpleCache] +
   * `upstream`. Shared between [wrapDataSourceFactory] (player-side
   * read path) and [LookaheadCacheWriter] (proactive prefetch). Both
   * call sites use identical `FLAG_IGNORE_CACHE_ON_ERROR` so cache
   * read + write semantics stay symmetric — a single source of truth
   * for the flag set.
   *
   * The same factory instance can be reused across many
   * `createDataSource()` calls; CacheDataSource.Factory is just a
   * builder. The writer hoists construction to the create() factory
   * so per-track downloads don't re-allocate the factory.
   */
  fun cacheDataSourceFactory(upstream: DataSource.Factory): CacheDataSource.Factory =
    CacheDataSource.Factory()
      .setCache(simpleCache)
      .setUpstreamDataSourceFactory(upstream)
      .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
      // Attach an EventListener so cache-ignore fall-throughs are
      // diagnosable in production logs. Two events fire:
      //   - `onCachedBytesRead(cacheSizeBytes, cachedBytesRead)` —
      //     a normal cache-hit read. High-volume; we deliberately
      //     don't log.
      //   - `onCacheIgnored(reason)` — the FLAG_IGNORE_CACHE_ON_ERROR
      //     fallthrough fired (corrupt cache entry, key missing,
      //     etc.). Low-volume + indicates a real "the cache isn't
      //     helping" signal. Log the reason so a "why is this not
      //     cached" investigation has a trail.
      .setEventListener(object : CacheDataSource.EventListener {
        override fun onCachedBytesRead(cacheSizeBytes: Long, cachedBytesRead: Long) {
          // Accumulated rather than logged per read: this fires per buffer, so
          // logging here would flood. The running total is what answers
          // "is playback actually being served from the cache", which cannot
          // be told apart from a network read by listening.
          cachedBytesReadTotal.addAndGet(cachedBytesRead)
        }
        override fun onCacheIgnored(reason: Int) {
          val name = when (reason) {
            CacheDataSource.CACHE_IGNORED_REASON_ERROR -> "ERROR"
            CacheDataSource.CACHE_IGNORED_REASON_UNSET_LENGTH -> "UNSET_LENGTH"
            else -> "UNKNOWN($reason)"
          }
          Log.w(
            TAG,
            "CacheDataSource ignored cache: $name"
          )
        }
      })

  /**
   * Total bytes served to the player from cache rather than upstream.
   * Monotonic for the cache's lifetime; read as a delta across an interval.
   */
  val cachedBytesReadTotal = java.util.concurrent.atomic.AtomicLong(0)

  /**
   * Drop per-instance state. The [SimpleCache] + database provider
   * stay alive (process-singleton, see class doc) — releasing them
   * would race Media3's `lockedFolders` static registry on the next
   * [LookaheadCache] construction targeting the same directory.
   */
  fun release() {
    // Clear the MIME store. A racing prefetch coroutine landing a
    // `recordMimeType` post-cancel can't leak a stale entry past
    // the cache's lifetime — mirrors `clearLookaheadCacheInternal`'s
    // cancel-then-clear shape.
    mimeStore.clear()
  }

  /**
   * Record the MIME type observed for `url`'s download. Called by
   * the prefetcher's response observer when an HTTP fetch lands a
   * recognisable `Content-Type`. URL-derived hints fall in via
   * [CacheMimeTypes.resolve] when Content-Type is opaque.
   */
  internal fun recordMimeType(url: String, contentType: String?) {
    val mime = CacheMimeTypes.resolve(url, contentType) ?: return
    mimeStore[url] = mime
  }

  /**
   * Returns the cached MIME type observed for `url`, or `null` if
   * the URL hasn't been prefetched in this process. Consumers wire
   * this into [androidx.media3.common.MediaItem.Builder.setMimeType]
   * so Media3's MediaSource picks the right extractor without
   * relying on byte sniffing.
   */
  fun mimeTypeFor(url: String): String? = mimeStore[url]

  // --- Status surface ---

  /**
   * Bytes currently held on disk. Reads the total Media3 already maintains, so
   * it is O(1) and needs no lock beyond the cache's own.
   *
   * Also more accurate than summing per-key cached bytes, which reports zero
   * for any resource whose content length has not been recorded yet — a
   * chunked download in flight occupies disk but has no length until it
   * completes. Used by [TrackPlayer.getLookaheadCacheStatus] for `currentSizeMb`.
   */
  // Every SimpleCache method rejects a released cache, and this is read off the
  // looper that releases it. A cache that has gone away holds nothing.
  fun currentSizeBytes(): Long = runCatching { simpleCache.cacheSpace }.getOrDefault(0L)

  /**
   * Whether the cache holds the entire content for `key`. True
   * iff the recorded content length matches the cached byte count
   * starting from offset 0. Used by
   * [TrackPlayer.getLookaheadCacheStatus] for the
   * `tracksFullyCached` count.
   *
   * Returns false when no entry exists, when the content length is
   * unknown (download not yet completed metadata write), or when
   * the cached span set doesn't cover [0, length).
   */
  fun isFullyCached(key: String): Boolean {
    val length = contentLength(key) ?: return false
    return simpleCache.getCachedBytes(key, 0, length) >= length
  }

  /**
   * Number of cache keys whose content is fully cached on disk
   * (queue-agnostic). Mirrors the semantics of [currentSizeBytes]
   * (cache state, independent of any current queue). Used by
   * [TrackPlayer.getLookaheadCacheStatus] for the `tracksFullyCached`
   * field — the count reflects the cache itself, not the intersection
   * with `self.tracks`.
   */
  fun fullyCachedCount(): Int = runCatching {
    simpleCache.keys.count { key ->
      val length = contentLength(key) ?: return@count false
      simpleCache.getCachedBytes(key, 0, length) >= length
    }
  }.getOrDefault(0)

  /**
   * Wipe every cached entry + free the on-disk space. Used by
   * `clearLookaheadCache()` JS surface. Walks
   * `simpleCache.keys` + calls `removeResource` per key — Media3
   * handles the file deletion + index update internally.
   */
  fun clear() {
    // Every SimpleCache method rejects a released cache, the key walk included.
    // A policy change releases the shared instance out from under an in-flight
    // wipe, and this now runs off the caller's thread, so an escaping throw
    // reaches the default handler rather than the JS call that asked for it.
    // Nothing is left to clear in that case — the release took the files with it.
    val keys = runCatching { simpleCache.keys.toList() }.getOrElse { return }
    for (key in keys) {
      runCatching { simpleCache.removeResource(key) }.getOrElse { return }
    }
    mimeStore.clear()
  }

  /** Read the recorded content length for `key`, or null when unknown. */
  private fun contentLength(key: String): Long? {
    val metadata = simpleCache.getContentMetadata(key)
    val length = ContentMetadata.getContentLength(metadata)
    return if (length == C.LENGTH_UNSET.toLong()) null else length
  }

  companion object {
    /** Logcat tag for LookaheadCache log emits. Matches the
     * `QueuePlayer.<area>` hierarchy used by the rest of the lib. */
    private const val TAG = "QueuePlayer.Cache"

    /**
     * Default cache subdirectory under `context.cacheDir`. Mirrors
     * the iOS sibling's `FileManager.temporaryDirectory/audio_lookahead/`
     * so cross-platform docs  reference a single name.
     */
    const val DEFAULT_CACHE_DIR_NAME = "audio_lookahead"

    /** 1 MB in bytes. Centralised here so MB ↔ bytes conversions
     *  across the cache surface use a single named constant rather
     *  than re-inlining `1024L * 1024L`. */
    const val BYTES_PER_MB: Long = 1024L * 1024L

    /**
     * Default cache budget. Plan section 4.7 default for the consumer-facing
     * API is 500 MB; the bootstrap budget is deliberately smaller so
     * a fresh install does not surprise users with half a gigabyte
     * of automatic disk usage. Overridden at configure() by the
     * consumer-supplied `PlayerConfig.lookaheadCacheMaxSizeMb`.
     */
    const val DEFAULT_MAX_SIZE_BYTES: Long = 100L * BYTES_PER_MB

    /**
     * Convert a `Double`-typed cache budget (MB) from
     * `PlayerConfig.lookaheadCacheMaxSizeMb` into a `Long` byte budget. Multiplies
     * in Double space BEFORE the `Long` cast so a 0.5 MB request
     * produces 524288 bytes intermediate (then floored to
     * [BYTES_PER_MB] = 1 MB on the final return) rather than 0 bytes
     * (which the bare `0.5.toLong() * BYTES_PER_MB = 0` shape would
     * produce). Floors at [BYTES_PER_MB] (1 MB) for cross-platform
     * parity with iOS.
     *
     * Defends against `NaN` / `±Infinity` from the JS bridge:
     * `Double.NaN.toLong()` returns 0 (then floored), but
     * `Double.POSITIVE_INFINITY.toLong()` silently returns
     * [Long.MAX_VALUE] which would request a multi-exabyte cache.
     * Treat non-finite inputs as "garbage in → safe minimum" and
     * return [BYTES_PER_MB] directly. Mirrors iOS's
     * `LookaheadCache.maxBytes(forMb:)` defence.
     */
    @JvmStatic
    fun maxBytesForMb(maxCacheSizeMb: Double): Long {
      if (!maxCacheSizeMb.isFinite()) return BYTES_PER_MB
      val raw = maxOf(0.0, maxCacheSizeMb) * BYTES_PER_MB.toDouble()
      return raw.toLong().coerceAtLeast(BYTES_PER_MB)
    }

    /**
     * Process-wide map of folder → live SimpleCache + DatabaseProvider.
     * The first construction for a folder owns the [maxSizeBytes] for
     * the lifetime of the process; subsequent constructions reuse the
     * existing instance regardless of their requested size.
     */
    private val sharedInstances = ConcurrentHashMap<String, SharedCacheInstance>()

    @Synchronized
    private fun sharedInstanceFor(
      appContext: Context,
      cacheDir: File,
      maxSizeBytes: Long,
      evictionPolicy: EvictionPolicy
    ): SharedCacheInstance {
      val key = cacheDir.absolutePath
      sharedInstances[key]?.let { return it }
      val db = StandaloneDatabaseProvider(appContext)
      val evictor = when (evictionPolicy) {
        EvictionPolicy.LRU -> LeastRecentlyUsedCacheEvictor(maxSizeBytes)
        EvictionPolicy.FIFO -> FifoCacheEvictor(maxSizeBytes)
      }
      val cache = SimpleCache(cacheDir, evictor, db)
      val newInstance = SharedCacheInstance(cache, db, evictor as? FifoCacheEvictor, evictionPolicy)
      sharedInstances[key] = newInstance
      return newInstance
    }

    /**
     * The eviction policy the live cache for [cacheDir] was built with, or null
     * when this process holds no cache there. Media3 fixes the evictor at
     * construction, so the instance is the record of what was applied.
     */
    @JvmStatic
    internal fun appliedEvictionPolicy(cacheDir: File): EvictionPolicy? =
      sharedInstances[cacheDir.absolutePath]?.evictionPolicy

    /**
     * Release + forget the shared SimpleCache for [cacheDir] so the next
     * [LookaheadCache] construction builds a fresh one. Used when the eviction
     * policy changes — Media3 fixes the evictor at SimpleCache construction, so
     * a new policy needs a new cache. `SimpleCache.release()` frees Media3's
     * folder lock; the caller deletes the on-disk files separately to clear.
     */
    @JvmStatic
    @Synchronized
    internal fun releaseSharedInstance(cacheDir: File): StandaloneDatabaseProvider? {
      val instance = sharedInstances.remove(cacheDir.absolutePath) ?: return null
      instance.cache.release()
      // Handed back so the caller can drop the cache's index tables along with
      // its files. Index storage is keyed by the cache uid, so deleting the
      // directory alone strands one table per policy change.
      return instance.databaseProvider
    }
  }

  /**
   * Pair of process-singleton SimpleCache + DatabaseProvider for a
   * single cache folder. Held by the companion object's
   * [sharedInstances] map until [releaseSharedInstance] drops it for a
   * policy change; a destroy leaves it in place.
   */
  private data class SharedCacheInstance(
    val cache: SimpleCache,
    val databaseProvider: StandaloneDatabaseProvider,
    /** Null under LRU, which has no protected-key concept. */
    val protectingEvictor: FifoCacheEvictor?,
    val evictionPolicy: EvictionPolicy,
  )
}
