package com.margelo.nitro.queueplayer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.SimpleCache
import com.facebook.react.ReactApplication
import androidx.media3.exoplayer.ExoPlayer
import com.facebook.proguard.annotations.DoNotStrip
import com.margelo.nitro.NitroModules
import com.margelo.nitro.core.Promise
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Android TrackPlayer — Nitro HybridObject holding an ExoPlayer
 * instance + runtime config. Mirrors `ios/TrackPlayer.swift`.
 * Lifecycle, transport, queue mutation, skip, gapless, events, and
 * audio focus all live here.
 */
@DoNotStrip
@UnstableApi
class TrackPlayer : HybridTrackPlayerSpec(), PlaybackEngineDelegate {

  // --- Stored state
  //
  // Mirrors iOS TrackPlayer.swift's "Stored state" block. Field
  // visibility is `internal` + `@VisibleForTesting` so lifecycle
  // transitions can be observed without going through the JNI-backed
  // Nitro Promise machinery (which does not load on a plain JVM).

  /**
   * `true` after [configureInternal] has bound + configured the
   * service-side engine; `false` before configure or after destroy.
   * Gates the [player] accessor so test scaffolding that pre-binds
   * the service (without calling configure) sees `player == null`
   * — preserving the lib-visible contract that transport calls
   * are no-ops before configure.
   */
  private var configured: Boolean = false

  /**
   * The underlying ExoPlayer the lib's transport calls operate on.
   * `null` before configure() / after destroy(). Computed every
   * read from `serviceBinder?.engine?.mediaSessionPlayer` so that
   * CrossfadeEngine internal role-swaps (which flip
   * `leadingPlayer` between `playerA` and `playerB` at every fade
   * boundary) do NOT leave a stale ExoPlayer reference cached on
   * `TrackPlayer`. A cached field would silently route subsequent
   * `prepare` / `clearMediaItems` calls onto the now-standby leg,
   * desyncing queue state. (Wake-mode is routed through the engine,
   * which applies it to both legs, so it is immune to this.)
   *
   * ExoPlayer is single-threaded — all access must happen on the
   * main (Looper) thread, per Media3 docs.
   */
  @VisibleForTesting
  internal val player: ExoPlayer?
    get() = if (configured) serviceBinder?.engine?.mediaSessionPlayer else null

  /**
   * Lookahead cache built alongside the ExoPlayer in
   * [configureInternal] and released in [destroyInternal]. Wraps the
   * HTTP data-source factory so remote reads transparently populate
   * an on-disk LRU. Built with [LookaheadCache.DEFAULT_MAX_SIZE_BYTES]
   * by default; `setLookaheadCache` lets consumers disable / resize.
   *
   * Written on main (configure / destroy); read off main by
   * `buildCacheStatusSnapshot` from the `Promise.async` pool
   * (`getLookaheadCacheStatus`) and the cache-status IO dispatcher
   * (`emitCacheStatus` / `onCacheStatusChange`), hence @Volatile.
   */
  @Volatile
  @VisibleForTesting
  internal var lookaheadCache: LookaheadCache? = null
    private set

  /**
   * Proactive prefetcher paired with [lookaheadCache].
   * Reschedules sequential CacheWriter downloads for the next N
   * remote tracks past `currentTrackIndex` after every queue + skip
   * mutation. Default lookahead window = [LookaheadCacheWriter.DEFAULT_LOOKAHEAD_COUNT]
   * (3); [setLookaheadCache] runtime config can override.
   */
  @Volatile
  @VisibleForTesting
  internal var lookaheadCacheWriter: LookaheadCacheWriter? = null
    private set

  /**
   * Watches the default network's transport so prefetch can stop the moment
   * the device hands off to cellular while `allowsCellularAccess` is off.
   * Built with the lookahead stack and released with it.
   */
  @VisibleForTesting
  internal var cellularTransportMonitor: CellularTransportMonitor? = null
    private set

  /**
   * Live lookahead cache config — enabled with
   * [LookaheadCacheWriter.DEFAULT_LOOKAHEAD_COUNT] until
   * [setLookaheadCache] mutates it, on main; read on main by the config
   * paths and off it by [buildCacheStatusSnapshot] on the `Promise.async`
   * pool and the cache-status dispatcher, which is why it is `@Volatile`.
   * Persists across destroy → configure so a consumer who set custom
   * values once doesn't lose them on a reconfigure cycle.
   */
  @Volatile
  @VisibleForTesting
  internal var lookaheadConfig: LookaheadCacheConfig = LookaheadCacheConfig(
    enabled = true,
    lookaheadCount = LookaheadCacheWriter.DEFAULT_LOOKAHEAD_COUNT.toDouble(),
    allowsCellularAccess = true
  )

  /** Configure-time cache disk budget in MB — `PlayerConfig.lookaheadCacheMaxSizeMb`
   *  or the 100 MB default. The size is fixed at cache construction, so this is
   *  read only at configure() and reported read-only via getLookaheadCacheStatus. */
  private fun cacheMaxSizeMbFrom(cfg: PlayerConfig): Double =
    cfg.lookaheadCacheMaxSizeMb
      ?: (LookaheadCache.DEFAULT_MAX_SIZE_BYTES / LookaheadCache.BYTES_PER_MB).toDouble()

  /** Configure-time cache eviction policy — `PlayerConfig.lookaheadCacheEvictionPolicy`
   *  or LRU. Fixed at SimpleCache construction; a change clears + rebuilds the cache
   *  (see [configureInternal]). */
  private fun evictionPolicyFrom(cfg: PlayerConfig): EvictionPolicy =
    cfg.lookaheadCacheEvictionPolicy ?: EvictionPolicy.LRU

  private fun cachePrefs(context: Context) =
    context.getSharedPreferences(CACHE_PREFS_NAME, Context.MODE_PRIVATE)

  /**
   * The eviction policy the cache on disk was last built with, or null before
   * the first configure. Read from disk because the in-memory config starts at
   * the default in every fresh process, which cannot distinguish "unchanged"
   * from "changed back to the default".
   */
  private fun persistedEvictionPolicy(context: Context): EvictionPolicy? =
    // The live cache wins while the process holds one: it is what the policy
    // describes, and the prefs write is asynchronous, so a quick reconfigure
    // would otherwise read a stale value and wipe a correct cache.
    LookaheadCache.appliedEvictionPolicy(defaultCacheDirectory(context))
      ?: cachePrefs(context).getString(KEY_EVICTION_POLICY, null)
        ?.let { name -> EvictionPolicy.entries.firstOrNull { it.name == name } }

  private fun defaultCacheDirectory(context: Context) =
    java.io.File(context.applicationContext.cacheDir, LookaheadCache.DEFAULT_CACHE_DIR_NAME)

  /**
   * `commit()`, not `apply()`: this has to survive a process kill. A queued
   * write lost to one leaves the previous policy recorded, and the next launch
   * reads that as a policy change and wipes a cache that is already correct.
   */
  private fun persistEvictionPolicy(context: Context, policy: EvictionPolicy) {
    val appCtx = context.applicationContext
    cacheIoScope.launch {
      runCatching {
        val prefs = cachePrefs(appCtx)
        if (prefs.getString(KEY_EVICTION_POLICY, null) != policy.name) {
          prefs.edit().putString(KEY_EVICTION_POLICY, policy.name).commit()
        }
      }
    }
  }

  /**
   * Drop the on-disk cache: rename it out of the way on the caller's thread,
   * then delete the files and the index tables off it.
   *
   * The rename is the load-bearing part. `configureInternal` rebuilds a
   * `SimpleCache` in this same directory a few statements later, and
   * `SimpleCache.initialize` reads the directory as it starts; a delete still
   * running underneath makes that read fail and the cache throws for the rest
   * of its life. Renaming is atomic within a filesystem, so the rebuild sees a
   * clean path immediately and the slow part happens where nothing is watching.
   */
  private fun discardCacheDirectory(context: Context): Job? {
    val appCtx = context.applicationContext
    val cacheDir = defaultCacheDirectory(appCtx)
    // A cache built by a previous process has no shared instance to release, so
    // open a provider for it — the index tables are keyed by cache uid and
    // outlive the files, and deleting the directory alone strands one per
    // policy change.
    val provider = LookaheadCache.releaseSharedInstance(cacheDir)
      ?: runCatching { StandaloneDatabaseProvider(appCtx) }.getOrNull()
    val doomed = java.io.File(
      appCtx.cacheDir,
      "${LookaheadCache.DEFAULT_CACHE_DIR_NAME}$DELETING_DIR_SUFFIX${System.nanoTime()}"
    )
    // Claimed before the rename, so the sweep — which runs off the looper and
    // may list the directory at any moment — never collects a directory this
    // process is deleting: otherwise it would match the name the rename just
    // produced and start a second delete of the same tree, with a second
    // database provider dropping the same index tables.
    claimDoomedDirectory(doomed)
    if (!cacheDir.exists() || !cacheDir.renameTo(doomed)) {
      releaseDoomedDirectory(doomed)
      // Without the rename the directory is still the one the rebuild is about
      // to use, so the delete has to finish before this returns. Doing it off
      // thread here would race the rebuild and can drop the *new* cache's index
      // tables, which is worse than the main-thread cost of a path that only
      // fires when a same-filesystem rename fails.
      deleteCacheDirectory(cacheDir, provider)
      return null
    }
    return cacheIoScope.launch {
      try {
        deleteCacheDirectory(doomed, provider)
      } finally {
        releaseDoomedDirectory(doomed)
      }
    }
  }

  private fun deleteCacheDirectory(
    dir: java.io.File,
    provider: StandaloneDatabaseProvider?
  ) {
    runCatching {
      // Reads the uid from inside `dir`, so it drops the index tables belonging
      // to this cache and no other.
      if (provider != null) SimpleCache.delete(dir, provider) else dir.deleteRecursively()
    }
    runCatching { provider?.close() }
  }

  /**
   * Delete directories left behind by a discard that did not finish — a process
   * death between the rename and the delete strands one, and nothing else would
   * ever collect it.
   */
  private fun sweepAbandonedCacheDirectories(context: Context) {
    val appCtx = context.applicationContext
    val prefix = "${LookaheadCache.DEFAULT_CACHE_DIR_NAME}$DELETING_DIR_SUFFIX"
    // Listing and deleting both run off the looper; a directory this process
    // is deleting is claimed before it is renamed into this name pattern, so
    // the listing cannot pick one up mid-discard.
    cacheIoScope.launch {
      val orphans = appCtx.cacheDir.listFiles()
        ?.filter {
          it.isDirectory && it.name.startsWith(prefix) && !isDoomedDirectoryClaimed(it)
        }
        ?: return@launch
      if (orphans.isEmpty()) return@launch
      // Each orphan still carries its own `.uid`, so route it through the same
      // delete to take its index tables with it.
      val provider = runCatching { StandaloneDatabaseProvider(appCtx) }.getOrNull()
      orphans.forEach { runCatching { if (provider != null) SimpleCache.delete(it, provider) else it.deleteRecursively() } }
      runCatching { provider?.close() }
    }
  }

  /**
   * Cache-status listeners registered via [onCacheStatusChange].
   * Multi-listener registry — every JS consumer that subscribes gets
   * its own slot, disposed via the returned unsubscribe closure.
   * Fired on download completion, eviction, config change, and clear.
   */
  private val cacheStatusListeners = ListenerRegistry<(CacheStatus) -> Unit>()
  private val nowPlayingFormatListeners =
    ListenerRegistry<(Variant_NullType_NowPlayingFormat?) -> Unit>()

  /**
   * Cached snapshot of the active item's audio format. Returned
   * synchronously by [getNowPlayingFormat]. Null between queue-end /
   * pre-first-track and the first successful resolve.
   */
  @Volatile
  private var lastEmittedFormat: NowPlayingFormat? = null

  /**
   * Identity key for [lastEmittedFormat]'s source MediaItem. Compared
   * to the player's current MediaItem on resolve to discard a result
   * whose source has been rotated out.
   */
  private var lastEmittedFormatMediaItem: MediaItem? = null

  // The fields below are written on main (via `onMain` / `syncMain`
  // from the Promise adapter path). They are read off-main by the synchronous
  // snapshot accessors — `getCurrentTrackIndex`, `getQueue` —
  // dispatched on the JS thread by Nitro. `@Volatile` gives the
  // read path a happens-before edge on the most recent main-thread
  // write under the JVM memory model. Without it, JS readers could
  // observe stale values indefinitely.
  //
  // Caveat on multi-field consistency: each field is independently
  // volatile, but the pair (`tracks`, `currentTrackIndex`) is NOT
  // atomic across a mutation. A JS caller that reads `getQueue()`
  // and `getCurrentTrackIndex()` in the same pass can observe the
  // new list with the old index (or vice versa) for one tick while
  // a main-thread mutation is mid-write. Consumers needing a
  // consistent snapshot should call a single accessor (e.g. a
  // future `getQueueSnapshot()` that returns both under one lock),
  // or tolerate the one-tick skew. This constraint inherits from
  // the iOS sibling, which shields the pair only via Promise
  // ordering on main.
  //
  // `config` + `repeatModeState` are currently read only on main but
  // carry the same annotation for uniformity.

  @Volatile
  @VisibleForTesting
  internal var tracks: List<TrackItem> = emptyList()

  @Volatile
  @VisibleForTesting
  internal var currentTrackIndex: Int = -1

  @Volatile
  @VisibleForTesting
  internal var config: PlayerConfig =
    PlayerConfig(
      enableDiagnosticLogs = null,
      httpHeaders = null,
      userAgent = null,
      autoRetries = null,
      retryBackoffMs = null,
      networkTimeoutMs = null,
      audioContentType = null,
      audioCategoryOptions = null,
      // iOS-only opt-in; Android has no in-app vocabulary donation API and
      // ignores it, as `donateVoiceVocabulary` itself does.
      voiceVocabularyDonationEnabled = null,
      allowsExternalPlayback = null,
      visualizationEnabled = null,
      clampSeekToBuffered = null,
      lookaheadCacheMaxSizeMb = null, lookaheadCacheEvictionPolicy = null,
      progressUpdateIntervalMs = null, backgroundProgressUpdateIntervalMs = null, placeholderArtworkUri = null, skipToPreviousBehavior = null
    )

  @Volatile
  @VisibleForTesting
  internal var repeatModeState: RepeatMode = RepeatMode.OFF

  /**
   * Canonical volume + playback speed, independent of the active
   * engine. Setters update these and forward to the engine; getters
   * read these (so a mid-fade ramp on the crossfade leg doesn't leak
   * out); the engine swap re-applies them so a gapless↔crossfade swap
   * preserves the user's settings.
   */
  @VisibleForTesting
  internal var volumeState: Float = 1.0f

  @VisibleForTesting
  internal var playbackSpeedState: Float = 1.0f

  // Lock-screen / notification remote-control config (seconds). Pushed to the
  // service on set + re-read by `getRemoteControls`.
  private var remoteSkipMode: RemoteSkipMode = RemoteSkipMode.TRACK
  private var remoteForwardSeconds: Double = 15.0
  private var remoteBackwardSeconds: Double = 15.0

  /**
   * Canonical pitch-correction mode, independent of the active engine.
   * Re-applied at configure + engine swap so it survives a
   * destroy→configure cycle and a gapless↔crossfade swap. Default voice.
   */
  @Volatile
  @VisibleForTesting
  internal var pitchCorrectionModeState: PitchCorrectionMode = PitchCorrectionMode.VOICE

  /**
   * Mode-switch state machine + current state. Driven by
   * [setPlaybackMode]; consumed by [getPlaybackMode] (returns
   * `state.active`) and the boundary-fire listener in the
   * [PlaybackEngineDelegate.onTrackTransition] handler.
   */
  private val playbackModeStateMachine = PlaybackModeStateMachine()

  @Volatile
  private var playbackModeState: PlaybackModeStateMachine.State =
    PlaybackModeStateMachine.Initial

  /**
   * Bridges Chromecast `RemotePlayer` session events back to the
   * lib's existing JS event surface (`onStateChange` etc.). Lives for
   * the process lifetime; the bridge's own scope observes
   * `PlaybackStateRouter.activeFlow` and subscribes / unsubscribes to
   * the active session's StateFlows on every transition.
   */
  private val castEventBridge = com.margelo.nitro.queueplayer.cast.CastEventBridge(this).also {
    it.start()
  }

  /**
   * Main-thread Handler reused across every `onMain` hop. ExoPlayer
   * access (construction, property reads/writes, release) must all
   * happen on the main Looper, so the transport / queue / skip
   * tasks that follow all go through the same helper — cache the
   * handler once rather than allocating per call.
   */
  private val mainHandler = Handler(Looper.getMainLooper())

  /**
   * Main-dispatched scope for the AirPlay receiver-display metadata sync.
   * `Main.immediate` because the sync's progress loop reads ExoPlayer state
   * (isPlaying / position), which must run on the app looper. Process-lifetime,
   * matching this singleton's other long-lived cast hooks; individual syncs are
   * cancelled by `MetadataAwareSession.onDeactivated()`.
   */
  private val metadataSyncScope =
    kotlinx.coroutines.CoroutineScope(
      kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate
    )

  /**
   * Scope for cache file work that must not run on the app looper — recursive
   * directory deletes and the SQLite index drop that goes with them.
   *
   * Process-lifetime and deliberately not cancelled on destroy: a delete that
   * outlives a `destroy()` still has to finish, or the renamed directory it is
   * clearing leaks until the next launch sweeps it.
   */
  private val cacheIoScope =
    kotlinx.coroutines.CoroutineScope(
      kotlinx.coroutines.SupervisorJob() +
        kotlinx.coroutines.Dispatchers.IO +
        // Cache housekeeping must never take the process down. Off the main
        // thread there is no JS call to surface a failure to, and the default
        // handler treats an uncaught one as fatal.
        kotlinx.coroutines.CoroutineExceptionHandler { _, t ->
          Log.w(TAG, "cache maintenance failed", t)
        }
    )

  /**
   * Cache-status snapshots, built one at a time.
   *
   * `onTrackProcessed` fires one of these per prefetched track, and on the
   * unbounded IO dispatcher two builds can finish in either order and deliver
   * out of sequence — a subscriber would watch `tracksFullyCached` go
   * backwards. Serialising the builds keeps the deliveries in the order the
   * snapshots were taken.
   */
  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  private val cacheStatusDispatcher =
    kotlinx.coroutines.Dispatchers.IO.limitedParallelism(1)

  /**
   * Connection to the in-process [PlaybackService]. `onServiceConnected`
   * stashes the [PlaybackService.LocalBinder] and completes
   * [serviceReady] only when [boundContext] is non-null — a stale
   * connection callback from a prior bind cycle (configure → destroy →
   * configure rapid-fire) must not complete the fresh deferred.
   * `onServiceDisconnected` clears the binder + replaces [serviceReady]
   * with a fresh [CompletableDeferred] so a subsequent [configure]
   * re-binds cleanly.
   */
  private val serviceConnection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
      if (boundContext == null) return
      val typedBinder = binder as? PlaybackService.LocalBinder
      serviceBinder = typedBinder
      // Reset the auto-rebind backoff state — successful connect
      // means any pending rebound from a prior disconnect has
      // landed; the next disconnect starts the backoff fresh.
      rebindAttempt = 0
      rebindHandler.removeCallbacks(rebindRunnable)
      // Apply browse-callback registrations made while [serviceBinder]
      // was null — before the first bind, or between a service kill and
      // this rebind. Without the drain those registrations never reach
      // the service and `dispatchCarConnect` times out waiting for a JS
      // carConnect callback that was never installed.
      drainPendingBrowseRegistrations()
      drainPendingSnapshot()
      // Re-apply the remote-control config to the (possibly reborn) service.
      // TrackPlayer is the source of truth; a fresh PlaybackService starts at
      // the track-skip / 15s default, and config set before the first bind
      // never reached a service, so re-push on every connect.
      typedBinder?.setRemoteControls(
        remoteSkipMode,
        (remoteForwardSeconds * 1000).toLong(),
        (remoteBackwardSeconds * 1000).toLong(),
      )
      // Same re-push for the seek-clamp config — a reborn service starts at the
      // default (off) and config set before the first bind never reached one.
      typedBinder?.setClampSeekToBuffered(config.clampSeekToBuffered == true)
      serviceReady.complete(Unit)
      // Service-rebirth detection: compare the new binder's stable
      // identity against the last-seen one. A different `instanceId`
      // means the OS killed + re-created the service (memory
      // reclaim, doze, idle-bucket reaper) — the consumer should
      // re-push the browse snapshot because the new service starts
      // with an empty `BrowseDataProvider`. The first bind in a
      // JS-process lifetime fires `first-bind`; subsequent rebinds
      // against a new instance fire `service-reborn`.
      typedBinder?.let { tb ->
        val reason = decideReadyReason(lastSeenBinderInstanceId, tb.instanceId)
        lastSeenBinderInstanceId = tb.instanceId
        if (reason != null) {
          tb.service.browseCallbacks.serviceReadyCallback()?.invoke(reason)
        }
      }
    }

    override fun onServiceDisconnected(name: ComponentName?) {
      serviceBinder = null
      serviceReady = CompletableDeferred()
      // Intentionally NOT clearing `lastSeenBinderInstanceId` here —
      // the next `onServiceConnected` compares against the prior id
      // to detect rebirth. Clearing would force every reconnect to
      // look like a fresh first-bind.
      //
      // The OS-driven service kill (memory reclaim, doze, idle
      // bucket reaper) fires this callback shortly before rebuilding
      // the service. Kick off the rebind watchdog so the lib reaches
      // the freshly-rebuilt service automatically; without this the
      // consumer's JS-side carService is left talking to a dead
      // binder reference until the user explicitly re-binds.
      scheduleRebindIfNeeded()
    }
  }

  /** Last `LocalBinder.instanceId` observed by
   *  [serviceConnection.onServiceConnected]. Used to distinguish
   *  first-bind from service-reborn on the next connect. `@Volatile`
   *  so the OS-thread connection callback sees the latest value
   *  written by a prior callback on a different thread. */
  @Volatile
  private var lastSeenBinderInstanceId: java.util.UUID? = null

  /** Auto-rebind watchdog state. Capped retries with exponential
   *  backoff matched against `ensureServiceBound`'s 5s timeout —
   *  the worst case 5+1+0.2 ≈ 6s total schedule covers the OS's
   *  typical service-restart window without burning a bind storm
   *  on a permanently-failing service. */
  private val rebindBackoffsMs = longArrayOf(200, 500, 1_000, 2_000, 5_000)
  @Volatile
  private var rebindAttempt: Int = 0
  private val rebindHandler =
    android.os.Handler(android.os.Looper.getMainLooper())
  private val rebindRunnable = Runnable { attemptRebind() }

  /** Schedule a `bindService` retry after a service-disconnect.
   *  No-op when the consumer has destroyed the player
   *  (`boundContext == null`) or when a retry is already queued
   *  (idempotent). */
  @MainThread
  private fun scheduleRebindIfNeeded() {
    if (boundContext == null) return
    val attempt = rebindAttempt
    if (attempt >= rebindBackoffsMs.size) {
      emitRebindFailedError(
        "Service rebind gave up after ${rebindBackoffsMs.size} attempts"
      )
      rebindAttempt = 0
      return
    }
    rebindHandler.removeCallbacks(rebindRunnable)
    rebindHandler.postDelayed(rebindRunnable, rebindBackoffsMs[attempt])
    rebindAttempt = attempt + 1
    android.util.Log.i(
      "RNQP-Rebind",
      "Scheduling service rebind attempt ${rebindAttempt}/" +
        "${rebindBackoffsMs.size} in ${rebindBackoffsMs[attempt]}ms"
    )
  }

  /** Fire one bind attempt. Called on the main looper from
   *  [rebindHandler]. The bind result is asynchronous — successful
   *  binds land in [serviceConnection.onServiceConnected] which
   *  resets `rebindAttempt`; failed binds schedule the next backoff
   *  step via the same watchdog path. */
  @MainThread
  private fun attemptRebind() {
    val ctx = boundContext ?: return
    if (serviceBinder != null) {
      // Race: a successful bind landed between scheduling + firing.
      // Reset the counter and bail.
      rebindAttempt = 0
      return
    }
    val intent = Intent(ctx, PlaybackService::class.java)
      .setAction(PlaybackService.ACTION_LOCAL_BIND)
    val didBind = try {
      ctx.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    } catch (t: Throwable) {
      android.util.Log.w(
        "RNQP-Rebind",
        "bindService threw on rebind attempt ${rebindAttempt}: ${t.message}"
      )
      false
    }
    if (!didBind) {
      // bindService returned false (or threw) — schedule the next
      // backoff step. The watchdog gives up after the array is
      // exhausted.
      scheduleRebindIfNeeded()
    }
    // didBind == true → `onServiceConnected` will fire and reset
    // `rebindAttempt`. If it never fires within reason the next
    // disconnect would re-arm the watchdog; the connection-side
    // timeout sits inside `ensureServiceBound`'s 5s await, which
    // is the configure-path concern.
  }

  /** Cancel any pending rebind attempt. Called from
   *  [destroyInternal] so a teardown-during-rebind cycle doesn't
   *  leak a delayed runnable. */
  @MainThread
  private fun cancelPendingRebind() {
    rebindHandler.removeCallbacks(rebindRunnable)
    rebindAttempt = 0
  }

  /** Surface a permanent rebind failure to JS via the standard
   *  error channel. Same shape as [emitBindFailedError] but tagged
   *  `PLAYBACK_SERVICE_REBIND_FAILED` so the consumer can
   *  distinguish initial-bind failures from rebirth-recovery
   *  failures. */
  private fun emitRebindFailedError(reason: String) {
    val err = PlaybackError(
      code = PlaybackErrorCode.UNKNOWN,
      message = "PlaybackService rebind failed",
      fatal = false,
      nativeCode = 0.0,
      nativeDomain = "PLAYBACK_SERVICE_REBIND_FAILED",
      nativeMessage = reason,
      queueItemId = "",
      url = "",
    )
    errorListeners.forEach { it(err) }
  }

  /**
   * Live binder handle once the service is bound. Read on main
   * during [configureInternal] and on whatever thread calls
   * [destroyInternal]; `@Volatile` keeps the connection-callback
   * write visible to those readers without a lock.
   */
  @Volatile
  private var serviceBinder: PlaybackService.LocalBinder? = null

  /**
   * Completes when the service binds successfully. [ensureServiceBound]
   * awaits this with a 5s timeout. Replaced with a fresh deferred on
   * disconnect or [destroyInternal] so the next bind cycle starts from
   * a pending state.
   */
  @Volatile
  private var serviceReady: CompletableDeferred<Unit> = CompletableDeferred()

  /**
   * Application context used for the active bind, kept so
   * [destroyInternal] can call `unbindService` against the same
   * context that originally called `bindService`.
   */
  @Volatile
  private var boundContext: Context? = null

  // --- Events — callback + observer state
  //
  // Mirrors iOS `TrackPlayer.swift`'s "Events" block. Five
  // registration methods (onStateChange / onTrackChange /
  // onProgress / onError / onQueueEnd) each store a single
  // callback and return an unsubscribe closure.
  //
  // State + track change flow out of Player.Listener. Progress
  // flows out of a Handler-based periodic tick at 2 Hz (matches
  // iOS `addPeriodicTimeObserver` rate; NOTES.md section 5 names this as
  // load-bearing for backgrounded playback). Error flows out of
  // Player.Listener.onPlayerError. Queue end fires when the
  // player reaches STATE_ENDED with a non-empty track list (no
  // repeat mode wrapping).
  //
  // All callback reads + writes happen on main via `onMainSync` —
  // registration/unregistration from a JS caller funnels through
  // that, and every listener body already runs on the
  // applicationLooper (main) by Media3 contract.
  //
  // Multi-listener registries — each `onXxx(callback)` registers an
  // entry, returns a dispose closure that removes by id. Lets
  // multiple consumers (Player UI, harness, test runner) subscribe
  // simultaneously without clobbering each other.
  private val stateChangeListeners = ListenerRegistry<(PlayerState, StateChangeReason) -> Unit>()
  private val trackChangeListeners = ListenerRegistry<(TrackItem?, Double, TrackChangeReason, Double?, TrackItem?, Boolean) -> Unit>()
  private val timedMetadataListeners = ListenerRegistry<(TimedMetadata) -> Unit>()
  private val queueChangeListeners = ListenerRegistry<(QueueChangeDelta, Double, QueueChangeReason) -> Unit>()
  private val progressListeners = ListenerRegistry<(PlayerProgress) -> Unit>()
  // Buffer state — discrete empty/buffering/stalled/full for the active track, derived
  // from Media3's playback state + buffered-ahead. Recomputed on the engine
  // state-change delegate (sub-tick latency for STATE_BUFFERING↔READY) + the
  // progress tick; emitted only on an actual change.
  private val bufferStateListeners = ListenerRegistry<(BufferState) -> Unit>()
  @Volatile private var currentBufferState: BufferState = BufferState.EMPTY
  private var lastEmittedBufferState: BufferState? = null
  // True once the current track has actually reached a playing state — the
  // discriminator between an initial `buffering` load and a mid-playback
  // `stalled` rebuffer. Reset on track change / stop / clear.
  @Volatile private var hasStartedPlaying: Boolean = false
  // Lib-level playback intent (play() → true, pause()/stop() → false). A
  // not-full buffer only reads as `stalled` while playback is both started and
  // intended; a paused load reads as `buffering`.
  @Volatile private var wantsToPlay: Boolean = false
  // Fully-buffered — the whole track has finished downloading (bufferedPosition
  // reaches duration). Distinct from BufferState.full ("keeps up"). Recomputed
  // on the progress tick (bufferedPosition grows continuously) + track change;
  // emitted on change. Reset per track.
  private val fullyBufferedListeners = ListenerRegistry<(Boolean) -> Unit>()
  @Volatile private var currentFullyBuffered: Boolean = false
  private var lastEmittedFullyBuffered: Boolean? = null
  // (milestone, trackIndex) — 25/50/75/90% playback thresholds.
  private val milestoneListeners = ListenerRegistry<(Double, Double) -> Unit>()
  // Per-playthrough milestone state. Main-thread-confined (mutated only
  // from the progress runnable, seekToInternal, and the track-change
  // handlers — all on main), so no extra synchronisation is needed.
  private val milestoneTracker = MilestoneTracker()
  // Throttles onProgress JS emission to the configured foreground / background
  // cadence. Main-thread-confined (mutated only from the progress runnable +
  // the ProcessLifecycleOwner callbacks, all on main).
  private val progressEmissionGate = ProgressEmissionGate()
  // ProcessLifecycleOwner observer driving the gate's background state. Held in
  // a nullable field so a re-configureInternal without an intervening
  // destroyInternal is a no-op instead of leaking a second
  // observer. Registered in configureInternal, removed in destroyInternal.
  private var progressLifecycleObserver: DefaultLifecycleObserver? = null
  private val errorListeners = ListenerRegistry<(PlaybackError) -> Unit>()
  private val queueEndListeners = ListenerRegistry<() -> Unit>()
  // Workaround: the callback carries a `SkipCapability` struct rather than
  // two boolean arguments, because Nitrogen miscompiles a `(bool, bool) ->
  // void` callback. No margelo/nitro issue found for it.
  private val skipCapabilityListeners = ListenerRegistry<(SkipCapability) -> Unit>()

  /**
   * Authoritative snapshot of (canSkipPrevious, canSkipNext) — single
   * `@Volatile` reference write keeps the pair atomic across cross-
   * thread reads. JS getters read this once. Recomputed by
   * [recomputeCapabilities] at every `tracks` / `currentTrackIndex`
   * / `repeatModeState` write site, with dedup — the
   * `skipCapabilityListeners` registry fires only when the pair
   * actually changes.
   */
  @Volatile
  private var cachedSkipCapability: SkipCapability =
    SkipCapability(canSkipPrevious = false, canSkipNext = false)

  // --- Sleep timer (native, engine-agnostic — survives engine swaps) ---
  private val sleepTimerCore = SleepTimerCore()
  private val sleepTimerListeners = ListenerRegistry<(SleepTimerState) -> Unit>()
  private var sleepTimerRunnable: Runnable? = null
  // True while playback volume is currently ramped down by the timer's fade, so
  // we know a restore to `volumeState` is owed on clear/fire.
  private var sleepTimerFading = false
  // Set just before the fire calls `engine.pause()`. That pause reaches Media3
  // as a USER_REQUEST play-when-ready change, which `onStateMaybeChanged`
  // stamps over `pendingStateChangeReason`; this one-shot flag lets
  // `emitStateIfChanged` re-label the resulting PAUSED emit as 'sleep-timer'.
  private var pendingSleepTimerPause = false

  /**
   * Cached classification of the active track's playback source.
   * Recomputed in [handleMediaItemTransition] whenever the active
   * track changes. Read by [getCurrentTrackSource] for sync JS access.
   * `@Volatile` mirrors the other JS-visible cached snapshots
   * ([cachedSkipCapability]).
   */
  @Volatile
  private var currentTrackSource: TrackSource? = null

  /**
   * Records `System.nanoTime()` when Media3 fires
   * `onPositionDiscontinuity(REASON_AUTO_TRANSITION)` — i.e. when
   * the engine internally moves from the end of one track to the
   * start of the next. The matching `onMediaItemTransition` callback
   * fires shortly after; the delta surfaces as `onTrackChange`'s
   * `transitionGapMs`. Read + written on main only.
   */
  private var gaplessGapPrevEndedAtNs: Long = 0L

  /**
   * Debug-only timestamp backing the verbose `RNQP-GAPLESS` Log.d emits.
   * Every read and write sits inside `if (BuildConfig.DEBUG)`, so R8
   * strips the branches in Release builds; only the field itself remains.
   */
  private var gaplessLogLastChangedAtNs: Long = 0L

  /** Stashed by transport methods before calling player.play()/pause()/stop()/seekTo().
   *  Consumed by [emitStateIfChanged] on the next listener fire that
   *  produces a non-buffering terminal state. Defaults to [StateChangeReason.SYSTEM]
   *  when no stash is pending (covers auto-advance, interruptions, etc.).
   *
   *  Read + written on main only — no volatility needed. */
  @VisibleForTesting
  internal var pendingStateChangeReason: StateChangeReason? = null

  /** Stashed by skip methods before issuing `player.seekTo(index, 0L)`.
   *  Consumed by [handleMediaItemTransition] when Media3 fires a
   *  REASON_SEEK transition. Defaults to [TrackChangeReason.AUTO_ADVANCE]
   *  for natural end-of-track advances. */
  @VisibleForTesting
  internal var pendingTrackChangeReason: TrackChangeReason? = null

  /** Set to the incoming-track index by [onCrossfadeBegin] the
   *  instant a crossfade fade-in begins. Consumed by
   *  [handleMediaItemTransition] to dedup the structural Media3
   *  echo that fires post-`swapRoles` for the same incoming index.
   *  Cleared on consume + on every non-AUTO transition path. */
  @Volatile
  private var crossfadeFadeStartEmittedIdx: Int? = null

  /** Last (currentTrackIndex, standardized PlaybackErrorCode) tuple
   *  emitted via [errorListeners]. Used by [handlePlayerError] to
   *  dedup repeated events from a retry storm (ExoPlayer's
   *  transient-error retry firing onPlayerError multiple times for
   *  the same item + cause). Mirrors iOS `lastErrorQueueItemId` +
   *  `lastErrorCode` at TrackPlayer.swift.
   *
   *  Why `currentTrackIndex` (lib-internal authoritative) and not
   *  `currentMediaItem.mediaId` (consumer-supplied): consumer
   *  `track.id` may duplicate across queue positions, so two
   *  distinct queue positions with the same id + same code would
   *  incorrectly dedup. Lib-internal index is unique per position
   *  and naturally clears on track change.
   *
   *  Cleared on track-change ([handleMediaItemTransition]),
   *  setQueue, clearQueue, [destroyInternal], AND on retry-
   *  initiation so the retry-then-fail can fire a fresh error. */
  private var lastErrorKey: Pair<Int, PlaybackErrorCode>? = null

  /**
   * Positions whose playback error has been reported to the consumer and not
   * recovered since. Empty when nothing is outstanding, and `-1` is never a
   * member — a failure carrying no track records nothing, which is what keeps
   * `currentTrackIndex in this` from reading as stopped while no track is
   * seated.
   *
   * A set, not one position: under crossfade the standby leg prerolls the next
   * track, so a broken one is reported while the leading track still plays and
   * both are outstanding at once. Holding only the latest would hand the
   * listener the standby's failure a second time when the engine seats it.
   * The queue is stopped when `currentTrackIndex` is a member.
   *
   * Written only where [handlePlayerError] hands an `onError` out, so it names
   * a track the consumer has been told about and nothing has recovered.
   * [retryFailedItem] and every skip drop the entry for the position they
   * attempt; a track change keeps only the announced position's; `setQueue`,
   * `clearQueue`, `destroy` and `shuffleQueue` empty it, and `addToQueue`,
   * `removeFromQueue` and `moveInQueue` keep only the current track's, carried
   * to its new index. `onCrossfadeCancel` deliberately keeps the set: it
   * returns to the track already playing rather than announcing a new one, and
   * the prerolled track is still queued and still broken. Deliberately NOT a
   * PLAYING emit: a source the player keeps re-attempting flips to playing
   * between attempts, so clearing there would reopen a track's report.
   *
   * Separate from [lastErrorKey] even though both name a failing track: that
   * one exists to dedup repeat reports and is cleared whenever a repeat would
   * be wanted again, which is not the same question as whether the queue is
   * still stopped. Mirrors the iOS `reportedFailureQueueItemIds` set.
   */
  private val reportedFailureIndices = mutableSetOf<Int>()

  /** Auto-retry counter per queue position, keyed on
   *  `currentTrackIndex` (lib-internal authoritative position; never
   *  derived from consumer fields). Decremented on each
   *  transient-error fire; when 0, the error surfaces to JS.
   *  Re-populated on `setQueueInternal` /
   *  `addToQueueInternal` from `effectiveAutoRetries()` (clamped
   *  0..5; default 1). Restored to the configured value by `retry()`, so
   *  a manual attempt gives whatever fails next its configured automatic
   *  allowance. `play()` restores the same configured value when the counter
   *  is 0 — stuck-recovery on a user re-tap. Cleared on `removeFromQueue` / `moveInQueue`
   *  (index shifts invalidate keys; budget resets are an acceptable
   *  trade-off vs walking + remapping the map per shifted index). */
  private var retryAttemptsRemaining: MutableMap<Int, Int> = mutableMapOf()

  private var announcedTrack: TrackItem? = null
  private var announcedTrackPlaying = false

  /** Last [PlayerState] emitted to subscribers. Suppresses duplicate
   *  fires — Media3 listener can emit several status transitions
   *  that collapse onto the same PlayerState. */
  private var lastReportedState: PlayerState = PlayerState.NONE

  /** Engine TrackPlayer last set itself as delegate on. Stored so
   *  `tearDownEventObservers` clears the same delegate slot the
   *  install path filled, even though within a single configure
   *  cycle install + teardown always run against the same engine
   *  instance. Nullable because configure wires it once; destroy
   *  clears it before releasing. */
  private var engineDelegateRegistration: PlaybackEngine? = null

  /** Runnable that re-posts itself every 500 ms to tick progress.
   *  Started when the first onProgress subscriber registers, paused
   *  when none remain. Goes away in destroy. */
  private var progressTickRunnable: Runnable? = null

  /** True while [progressTickRunnable] is actively posting. Guards
   *  against double-start races. */
  private var progressTickActive: Boolean = false

  /** Serialises `configure()` and `destroy()` so two concurrent
   *  invocations cannot interleave their teardown / rebuild steps.
   *  Each path's body executes under [withLock], so a configure that
   *  is mid-`destroyInternal` cannot be re-entered by a second
   *  configure while the first is still in `ensureServiceBound` /
   *  `configureInternal`, and a destroy fired during a configure
   *  waits until the rebuild completes before tearing down again. */
  private val lifecycleMutex = Mutex()

  // --- Lifecycle

  override fun configure(config: PlayerConfig): Promise<Unit> =
    Promise.async {
      lifecycleMutex.withLock {
        // Read `applicationContext` on main — the field is written
        // from the React Native init path and has no documented
        // thread-safety guarantee; reading here also serialises
        // configure/destroy on the same main-thread lock step.
        val context = onMain {
          NitroModules.applicationContext
            ?: throw IllegalStateException(
              "[QP_NOT_INSTALLED] NitroModules.applicationContext is null - " +
                "TrackPlayer.configure called before NitroModules was installed " +
                "by React Native."
            )
        }
        // Universal auto-destroy contract: every `configure()` call
        // tears down any prior engine, service binding, and pipeline
        // state before reinitialising. On the first configure of a
        // session this is a no-op (`destroyInternal` short-circuits
        // when `player == null` + `boundContext == null`). On
        // subsequent calls it is a real teardown — including
        // `unbindService` — so the service is re-bound below for the
        // new config. Effect: configure is always a clean-slate
        // operation; consumers don't need to remember to call
        // `destroy()` before re-configuring.
        onMain { destroyInternal() }
        ensureServiceBound(context)
        onMain { configureInternal(config, context) }
      }
    }

  override fun destroy(): Promise<Unit> =
    Promise.async {
      lifecycleMutex.withLock {
        onMain { destroyInternal() }
      }
    }

  /**
   * Bind the in-process [PlaybackService] so the player is owned by
   * the service rather than constructed inline by [configureInternal].
   * Returns immediately once [serviceBinder] is non-null; otherwise
   * issues `bindService(BIND_AUTO_CREATE)` and awaits the connection
   * callback with a 5s timeout. Called from [configure] before
   * [configureInternal] so the synchronous `@MainThread` body can
   * assume [serviceBinder] is non-null.
   *
   * Bind failure surfaces twice: as a typed [PlaybackError] on
   * [errorListeners] (`nativeDomain = "PLAYBACK_SERVICE_BIND_FAILED"`)
   * for JS consumers subscribed to `onError`, and as a thrown
   * exception so the calling [configure] Promise rejects.
   */
  @VisibleForTesting
  internal suspend fun ensureServiceBound(context: Context) {
    if (serviceBinder != null) return
    onMain {
      if (boundContext == null) {
        val appContext = context.applicationContext
        val intent = Intent(appContext, PlaybackService::class.java)
          .setAction(PlaybackService.ACTION_LOCAL_BIND)
        // Stash the context BEFORE bindService so the callback's
        // `boundContext != null` guard sees this cycle's bind. Cleared
        // back to null below if bindService rejects.
        boundContext = appContext
        val didBind = appContext.bindService(
          intent, serviceConnection, Context.BIND_AUTO_CREATE
        )
        if (!didBind) {
          boundContext = null
          serviceReady.completeExceptionally(
            IllegalStateException("PlaybackService bindService returned false")
          )
        }
      }
    }
    val ready = withTimeoutOrNull(5_000) {
      runCatching { serviceReady.await() }
    }
    if (ready == null) {
      emitBindFailedError("Timed out after 5000ms waiting for onServiceConnected")
      throw IllegalStateException(
        "PlaybackService bindService timed out after 5000ms"
      )
    }
    val ex = ready.exceptionOrNull()
    if (ex != null) {
      emitBindFailedError(ex.message ?: ex::class.java.simpleName)
      throw ex
    }
  }

  /**
   * Emit a `PLAYBACK_SERVICE_BIND_FAILED` PlaybackError to JS
   * consumers subscribed to `onError`. Non-fatal at the lib layer —
   * the consuming `configure()` / mutation Promise still rejects so
   * the consumer's await chain breaks. The error event lets a top-
   * level UI surface the failure (manifest typo, packaging issue,
   * service crash on first launch) without parsing rejection text.
   */
  private fun emitBindFailedError(reason: String) {
    val err = PlaybackError(
      code = PlaybackErrorCode.UNKNOWN,
      message = "PlaybackService bind failed",
      fatal = false,
      nativeCode = 0.0,
      nativeDomain = "PLAYBACK_SERVICE_BIND_FAILED",
      nativeMessage = reason,
      queueItemId = "",
      url = "",
    )
    errorListeners.forEach { it(err) }
  }

  /**
   * Lifecycle configure body. `internal`, bypassing the Nitro Promise
   * path. Callers MUST hop to the main thread first — ExoPlayer
   * construction binds to the calling Looper. Production path goes
   * through `onMain`.
   *
   * Builds the ExoPlayer with a [MediaSource.Factory] that attaches
   * the config's HTTP headers + User-Agent on every network request.
   * The factory is bound to the ExoPlayer at construction time —
   * Media3 does not support swapping factories on a live instance.
   * Per the universal auto-destroy contract every `configure()` call
   * tears the player down upstream so this body is purely
   * constructive — `player` is guaranteed null on entry from the
   * production path. Any caller driving `configureInternal` directly
   * must call `destroyInternal` first between configures.
   */
  @VisibleForTesting
  @MainThread
  internal fun configureInternal(newConfig: PlayerConfig, context: Context) {
    // Eviction policy is fixed at SimpleCache construction; a change clears the
    // cache + rebuilds under the new evictor (infrequent — see FifoCacheEvictor).
    // The service still holds the engine `configurePlayer` replaces further
    // down — `destroyInternal` drops this player's references, not the
    // service's engine — and that engine reads through the shared cache, so it
    // is stopped before the cache is released and its directory renamed away.
    // Compare against what was last APPLIED, not against the in-memory config.
    // A fresh process starts at the LRU default, so a FIFO consumer would
    // otherwise read every cold launch as a policy change and wipe its cache
    // before it could ever be used.
    val previousEvictionPolicy =
      persistedEvictionPolicy(context) ?: evictionPolicyFrom(this.config)
    this.config = newConfig
    // Swap the cover-art placeholder bitmap to the consumer-supplied image
    // (or back to the built-in when unset). The no-art metadata URI is
    // threaded separately at each buildMediaItems call.
    PlaceholderArtwork.setCustom(newConfig.placeholderArtworkUri)
    if (evictionPolicyFrom(newConfig) != previousEvictionPolicy) {
      serviceBinder?.engine?.stop()
      lookaheadCacheWriter?.release()
      lookaheadCacheWriter = null
      lookaheadCache?.release()
      lookaheadCache = null
      cellularTransportMonitor?.stop()
      cellularTransportMonitor = null
      discardCacheDirectory(context)
    }
    // Collect what a previous process left mid-delete. Directories this
    // process is still deleting are claimed and skipped.
    sweepAbandonedCacheDirectories(context)
    // Seed the progress-emission throttle from config and start observing app
    // background/foreground. Both are idempotent, so a re-configure refreshes
    // the rate without double-registering the lifecycle observer.
    applyProgressEmissionIntervals()
    installProgressLifecycleObserver()
    // Pin-at-configure visualization-disabled flag. Mirrored to the
    // `VisualizerConfig` singleton so the engines' field-init read
    // (below, via `binder.service.configurePlayer` which constructs a
    // fresh engine) picks up the new value, and so
    // `Visualizer.subscribe(...)` can read it live. Default `true`
    // preserves existing behaviour for consumers who never set the
    // new field.
    VisualizationGate.setEnabled(newConfig.visualizationEnabled ?: true)
    buildLookaheadStack(newConfig, context)
    // Recorded only once a cache actually exists under this policy, so a failed
    // build cannot leave a policy stamped that nothing was built with.
    if (lookaheadCache != null) {
      persistEvictionPolicy(context, evictionPolicyFrom(newConfig))
    }
    // configure() destroys first, so the queue is empty here and this clears
    // protection rather than seeding it. That is the point: the evictor lives
    // in a process-wide shared instance that outlives a destroy/configure
    // cycle, so without this it would keep shielding the previous session's
    // tracks until the next queue mutation.
    refreshCacheProtection()
    val mediaSourceFactory =
      MediaItemBuilder.buildMediaSourceFactory(context, newConfig, lookaheadCache)
    val binder = serviceBinder
      ?: error(
        "PlaybackService not bound — ensureServiceBound was not called " +
          "before configureInternal"
      )
    // configurePlayer constructs a fresh GaplessEngine + binds it
    // via swapEngineInternal. The `player` accessor reads through
    // serviceBinder.engine.mediaSessionPlayer so it picks up the
    // newly-installed engine on the next read — no explicit
    // refresh needed here.
    binder.service.configurePlayer(
      factory = mediaSourceFactory,
      audioContentType = config.audioContentType
    )
    // Mirror the seek-clamp config onto the forwarding player so a controller
    // interval skip-forward obeys the same clamp as a JS seekTo.
    binder.setClampSeekToBuffered(newConfig.clampSeekToBuffered == true)
    configured = true
    // configurePlayer always rebuilds the default GaplessEngine, so the mode
    // state must reset to match. Otherwise getPlaybackMode() keeps reporting a
    // stale crossfade selection after a clean-slate reconfigure while the
    // engine is actually gapless — and a later setPlaybackMode(crossfade)
    // would no-op against that stale state and never rebuild the
    // CrossfadeEngine.
    playbackModeState = PlaybackModeStateMachine.Initial
    // Re-apply persisted config to the fresh engine. These survive a
    // destroy→configure cycle and may be set before the first configure,
    // so the first engine must pick them up — matching the engine-swap
    // path. (ExoPlayer's setPlaybackSpeed is independent of play state.)
    serviceBinder?.engine?.let { engine ->
      engine.setRepeatMode(repeatModeState)
      engine.setVolume(volumeState)
      engine.setPlaybackSpeed(playbackSpeedState)
      engine.setPitchCorrectionMode(pitchCorrectionModeState)
      installSuppliedReplayGainResolver(engine)
    }
    installEventObservers()
    binder.setCommandHandler(mediaSessionCommandHandler)
    current = this
  }

  /**
   * Bridge from `PlaybackService` — its [SessionCommandForwardingPlayer],
   * [MediaButtonDispatch] and audio-focus listener — back into this
   * TrackPlayer. Stamps the `system` pending-reason for transports
   * that the system surface drives (lock-screen, Bluetooth, Android
   * Auto, hardware media keys), and routes skip-next / skip-previous
   * through the queue-aware `skipTo*Internal` bodies so retry
   * counters, lookahead reset, capability recomputation, and
   * `pendingTrackChangeReason` (set inside the bodies) all stay
   * consistent. Mirrors iOS `wireRemoteCommands`.
   *
   * Skip handlers always return `true`: even when the body early-
   * returns (no player, empty queue), the lib has now claimed
   * responsibility for the skip — Media3's default
   * `seekToNextMediaItem` would also no-op against the same empty
   * timeline, so suppressing the default routing avoids a redundant
   * second hop without changing the user-visible result.
   */
  @VisibleForTesting
  internal val mediaSessionCommandHandler =
    object : PlaybackService.MediaSessionCommandHandler {
      override fun onTransportCommand() {
        pendingStateChangeReason = StateChangeReason.SYSTEM
      }

      override fun onSkipToNextCommand(): Boolean {
        // Cast routing before the local body, same early-return contract as
        // the public transport methods. The `true` return suppresses the
        // forwarding player's default seek either way, so the receiver gets
        // exactly one skip. The reason stamp sits on the local path because
        // that is the only path whose listener consumes it.
        if (com.margelo.nitro.queueplayer.cast.CastTransportRouter.routeSkipToNext()) return true
        pendingStateChangeReason = StateChangeReason.SYSTEM
        skipToNextInternal()
        return true
      }

      override fun onSkipToPreviousCommand(): Boolean {
        if (com.margelo.nitro.queueplayer.cast.CastTransportRouter.routeSkipToPrevious()) return true
        pendingStateChangeReason = StateChangeReason.SYSTEM
        skipToPreviousInternal()
        return true
      }

      override fun onSeekToMediaItemCommand(mediaItemIndex: Int): Boolean {
        // Decline an out-of-range index so the default per-item seek
        // no-ops it (Media3 passes C.INDEX_UNSET when the seek implies
        // no move).
        if (mediaItemIndex < 0 || mediaItemIndex >= tracks.size) return false
        // Same path as the public skipToIndex: jump the receiver while
        // casting, else the local engine. Both write currentTrackIndex +
        // the USER_SKIP_TO_INDEX reason that a raw controller seek skips.
        // The tapped track starts at position 0 — the controller's
        // positionMs is intentionally not threaded through, matching the
        // public skipToIndex "play this track" contract.
        if (com.margelo.nitro.queueplayer.cast.CastTransportRouter.routeSkipToIndex(mediaItemIndex)) {
          return true
        }
        pendingStateChangeReason = StateChangeReason.SYSTEM
        skipToIndexInternal(mediaItemIndex)
        return true
      }

      override fun onMediaPlay() {
        if (pauseForVideoTrack()) return
        if (com.margelo.nitro.queueplayer.cast.CastTransportRouter.routePlay()) return
        pendingStateChangeReason = StateChangeReason.SYSTEM
        serviceBinder?.engine?.play()
      }

      override fun onMediaPause() {
        if (com.margelo.nitro.queueplayer.cast.CastTransportRouter.routePause()) return
        pendingStateChangeReason = StateChangeReason.SYSTEM
        serviceBinder?.engine?.pause()
      }

      override fun onMediaTogglePlayPause() {
        if (com.margelo.nitro.queueplayer.cast.CastTransportRouter.routeTogglePlayPause()) return
        pendingStateChangeReason = StateChangeReason.SYSTEM
        val engine = serviceBinder?.engine ?: return
        // `playWhenReady` (intent to play) is read off the underlying
        // ExoPlayer because the engine surface intentionally doesn't
        // expose intent vs. effective playback. Toggling on
        // `isPlaying` would loop on buffering states.
        val playWhenReady = player?.playWhenReady ?: return
        if (playWhenReady) engine.pause() else engine.play()
      }

      override fun onMediaSkipNext() {
        if (com.margelo.nitro.queueplayer.cast.CastTransportRouter.routeSkipToNext()) return
        pendingStateChangeReason = StateChangeReason.SYSTEM
        skipToNextInternal()
      }

      override fun onMediaSkipPrevious() {
        if (com.margelo.nitro.queueplayer.cast.CastTransportRouter.routeSkipToPrevious()) return
        pendingStateChangeReason = StateChangeReason.SYSTEM
        skipToPreviousInternal()
      }

      override fun getCachedSkipCapability(): SkipCapability =
        cachedSkipCapability

      override fun onAudioFocusEvent(mapping: AudioFocusErrorMapping.Mapping) {
        // Focus handoffs (including Android Auto connection) are normal
        // transport changes, not failures. Media3 owns pause/duck/resume;
        // the engine delegate reports the resulting playback state.
        if (config.enableDiagnosticLogs == true) {
          Log.d("QueuePlayer.AudioFocus", "${mapping.nativeDomain}: ${mapping.message}")
        }
      }

      override fun onBecomingNoisy() {
        // Output disconnects use onStateChange's route-change reason. Do
        // not also turn the expected safety pause into a playback error.
        if (config.enableDiagnosticLogs == true) {
          Log.d("QueuePlayer.AudioFocus", "Audio output route changed; Media3 handles playback pause.")
        }
      }
    }

  /**
   * Build the lookahead cache + writer and assign to `lookaheadCache`
   * and `lookaheadCacheWriter`. When the cache is disabled or its
   * construction fails (wedged / read-only directory), both fields
   * are left null and `buildMediaSourceFactory` falls back to a bare
   * HTTP DataSource chain. Caching is a performance optimisation,
   * not a correctness gate.
   */
  private fun buildLookaheadStack(config: PlayerConfig, context: Context) {
    // Ahead of the writer, which captures the monitor's reading at every
    // reschedule, and a transport flip has to move the window itself. Built
    // regardless of `enabled` for the same reason the cache is: prefetch can
    // be turned on at runtime, and the writer built then needs a monitor.
    cellularTransportMonitor?.stop()
    cellularTransportMonitor = CellularTransportMonitor(context).apply {
      // The callback arrives on a ConnectivityManager thread; the reschedule
      // is main-confined.
      onTransportChanged = { mainHandler.post { rescheduleLookahead() } }
      start()
    }
    // Build the cache regardless of `enabled` so caching can be turned on at
    // runtime (setLookaheadCacheInternal) without a configure() cycle. Media3's
    // SimpleCache fixes its size at construction and allows one instance per
    // directory, so the instance must exist up front; `enabled` then only gates
    // whether the writer prefetches into it. An unused empty cache is cheap.
    // Single source of truth for the MB → byte conversion; mirrors iOS's
    // `LookaheadCache.maxBytes(forMb:)`. Handles the multiply-before-cast bug
    // (0.5 MB request → 524288 bytes intermediate, not 0), the 1 MB floor, and
    // the NaN/±Infinity defence on JS-bridged input.
    val maxSizeBytes = LookaheadCache.maxBytesForMb(
      cacheMaxSizeMbFrom(config)
    )
    val cache: LookaheadCache? = try {
      LookaheadCache(
        context = context,
        maxSizeBytes = maxSizeBytes,
        evictionPolicy = evictionPolicyFrom(config)
      )
    } catch (err: java.io.IOException) {
      // SimpleCache construction wedged or read-only — log and run without a
      // cache.
      Log.w(
        PLAYER_LOG_TAG,
        "LookaheadCache construction failed; running without cache",
        err
      )
      null
    }
    lookaheadCache = cache
    cache?.servesPlayback = lookaheadConfig.enabled
    // Writer + player factory share state via the cache, not via the
    // http factory instance. Writer is only useful when there's a
    // cache to write into — gate on both `lookaheadConfig.enabled`
    // AND a successful cache construction.
    lookaheadCacheWriter = if (lookaheadConfig.enabled && cache != null) {
      LookaheadCacheWriter.create(
        cache = cache,
        httpFactory = MediaItemBuilder.buildHttpFactory(
          headers = config.httpHeaders,
          userAgent = config.userAgent,
          networkTimeoutMs = MediaItemBuilder.effectiveNetworkTimeoutMs(
            config.networkTimeoutMs
          )
        ),
        isOnCellular = { cellularTransportMonitor?.isOnCellular == true }
      ).apply {
        defaultLookaheadCount = lookaheadConfig.lookaheadCount.toInt()
          .coerceAtLeast(0)
        allowsCellularAccess = lookaheadConfig.allowsCellularAccess ?: true
        onTrackProcessed = { onMainPostStatusEmit() }
      }
    } else {
      null
    }
  }

  /**
   * Lifecycle destroy body. Releases the ExoPlayer instance and
   * resets every piece of mutable state so a subsequent configure()
   * starts from the same clean slate as a fresh TrackPlayer. Safe
   * to call multiple times, and safe before configure() (no-op).
   */
  @VisibleForTesting
  @MainThread
  internal fun destroyInternal() {
    removeProgressLifecycleObserver()
    // Cancel any in-flight rebind retry before clearing boundContext
    // so the watchdog doesn't fire a doomed bind against a torn-down
    // player.
    cancelPendingRebind()
    serviceBinder?.setCommandHandler(null)
    releasePlayerAndState()
    boundContext?.let { ctx ->
      try {
        ctx.unbindService(serviceConnection)
      } catch (_: RuntimeException) {
        // Service was never actually bound (IllegalArgumentException
        // when bindService returned false), or process-death edge
        // cases on OEM-modified frameworks (SecurityException). Both
        // mean "no live binding to release" — safe to swallow.
      }
    }
    boundContext = null
    serviceBinder = null
    serviceReady = CompletableDeferred()
    // Persist `config` + `repeatModeState` + `lookaheadConfig`
    // across destroy → configure so a consumer who set custom
    // values (HTTP headers / UA / repeat mode / cache size) once
    // doesn't lose them on a reconfigure cycle. Symmetric with the
    // [lookaheadConfig] docstring above.
    //
    // Callbacks also survive — matches iOS, where the subscription is
    // owned by the JS consumer, not the HybridObject. Consumers call
    // the returned unsubscribe closure explicitly when they're done.
    //
    // Pending-reason channels reset with the player so a subsequent
    // configure() starts from a clean dispatch state.
    pendingStateChangeReason = null
    pendingTrackChangeReason = null
    lastErrorKey = null
    reportedFailureIndices.clear()
    cancelPendingRetry()
    retryAttemptsRemaining.clear()
    if (current === this) current = null
  }

  /**
   * Tear-down for the writer, the cache reference and per-position
   * state, called from [destroyInternal]. The writer's coroutine scope
   * is cancelled before the cache's MIME store is cleared so a racing
   * `recordMimeType` cannot land after the clear. The Media3 SimpleCache
   * is a per-folder process singleton and is not released here —
   * `LookaheadCache.release()` drops only per-instance state; an
   * eviction-policy change in [configureInternal] releases and rebuilds
   * it via [discardCacheDirectory].
   */
  private fun releasePlayerAndState() {
    // Cancel any armed sleep timer up front — the engine (and its volume) is
    // going away, so don't touch it or emit; just stop the tick + reset state.
    stopSleepTimerTick()
    sleepTimerCore.clear()
    sleepTimerFading = false
    pendingSleepTimerPause = false
    tearDownEventObservers()
    lookaheadCacheWriter?.release()
    lookaheadCacheWriter = null
    // Before dropping the reference: the evictor is process-wide and outlives
    // this player, so its protected set has to be cleared here or those entries
    // stay unevictable until the next configure moves the window.
    // `LookaheadCache.release()` does not touch it.
    lookaheadCache?.setProtectedUrls(emptySet())
    lookaheadCache?.release()
    lookaheadCache = null
    cellularTransportMonitor?.stop()
    cellularTransportMonitor = null
    // Mark unconfigured so the [player] accessor returns null even
    // though the service may still be bound (it unbinds at the
    // bottom of destroyInternal). The PlaybackService releases its
    // own engine (which owns the ExoPlayer instances) on service
    // unbind / onDestroy.
    configured = false
    tracks = emptyList()
    // The clear emits nothing, so a mirror built from `onQueueChange` deltas
    // would splice the next delta onto the queue this just dropped. Moving
    // the revision past the next emit's value is what a subscriber reads as
    // a missed event, and re-reads.
    queueRevision += 1
    currentTrackIndex = -1
    currentTrackSource = null
    currentBufferState = BufferState.EMPTY
    lastEmittedBufferState = null
    currentFullyBuffered = false
    lastEmittedFullyBuffered = null
    hasStartedPlaying = false
    wantsToPlay = false
    lastReportedState = PlayerState.NONE
    announcedTrack = null
    announcedTrackPlaying = false
    refreshNowPlayingFormatForActiveItem()
    recomputeCapabilities()
  }

  /**
   * Run `body` on the main thread, suspending until it completes.
   * Returns synchronously if the caller is already on main (avoids
   * an unnecessary Handler round-trip). Mirrors iOS `onMain`.
   */
  private suspend fun <T> onMain(body: () -> T): T {
    if (Looper.myLooper() == Looper.getMainLooper()) {
      return body()
    }
    return suspendCoroutine { cont ->
      mainHandler.post {
        try {
          cont.resume(body())
        } catch (t: Throwable) {
          cont.resumeWithException(t)
        }
      }
    }
  }

  /**
   * Blocking main-thread hop for the synchronous snapshot readers
   * ([getState], [getVolume], [getPlaybackSpeed], [getCurrentTrackIndex]
   *.). ExoPlayer's `Player` interface documents that
   * reads must happen on the application thread — `ExoPlayerImpl`
   * calls `verifyApplicationThread()` on property reads in Media3
   * 1.9.3 which throws in debug builds.
   *
   * Nitro dispatches sync methods on the JS thread, so without a
   * blocking hop a JS `getState()` call would crash ExoPlayer.
   * CountDownLatch is intentional over `postAtFrontOfQueue` — the
   * wait is expected to be short (single property read), and the
   * blocking wait guarantees a fresh snapshot rather than a stale
   * cached value.
   *
   * Calling this from main deadlocks by definition — always guard
   * with the `Looper.myLooper()` fast-path.
   */
  private fun <T> syncMain(body: () -> T): T {
    if (Looper.myLooper() == Looper.getMainLooper()) {
      return body()
    }
    val done = CountDownLatch(1)
    var result: Result<T>? = null
    mainHandler.post {
      result = runCatching { body() }
      done.countDown()
    }
    done.await()
    return result!!.getOrThrow()
  }

  // --- Queue: bulk set / clear / get / current-index
  //
  // The bulk setter (setQueue) is the Media3 gapless integration
  // point: `setMediaItems` hands ExoPlayer the entire queue in one
  // call, which is what enables continuous decoding across item
  // boundaries. `addToQueue` / `removeFromQueue` / `moveInQueue`
  // mutate the queue incrementally without disturbing
  // the current item or the gapless pipeline.

  override fun setQueue(
    tracks: Array<TrackItem>,
    startAtIndex: Double?
  ): Promise<Unit> =
    Promise.async {
      // Cast routing: when a remote-player session is active the
      // queue lands on the receiver via `RemoteMediaClient.queueLoad`
      // and the local engine stays idle. `routeSetQueue` is suspending
      // (it awaits the receiver round-trip) so the call happens
      // outside `onMain` — `ChromecastSession.load` does its own
      // main-thread hop internally.
      val routed = com.margelo.nitro.queueplayer.cast.CastTransportRouter
        .routeSetQueue(
          tracks = tracks.toList(),
          startIndex = startAtIndex?.toInt() ?: 0,
          startPositionMs = 0L,
          playWhenReady = true,
        )
      if (routed) {
        // Mirror the queue into the local engine (paused) so JS reads
        // (`getQueue` / `getCurrentTrackIndex`) reflect the user-visible
        // queue AND the engine is primed at the right item to take over
        // when cast disconnects. `setQueueInternal` only prepares — it
        // never starts playback, and transport during cast routes to the
        // receiver, so the local engine stays paused.
        val snapshot = tracks.toList()
        onMain { setQueueInternal(snapshot, startAtIndex?.toInt()) }
        return@async
      }
      onMain {
        setQueueInternal(tracks.toList(), startAtIndex?.toInt())
      }
    }

  /**
   * Hand off current local playback to a newly-connected cast receiver:
   * load the current queue at the current playhead + play/pause, then
   * pause the local engine (which keeps its queue, paused, for the
   * cast-disconnect resume). Suspending — called from the cast bridge's
   * scope on session activation.
   */
  internal suspend fun handoffCurrentPlaybackToCast() {
    val snapshot = onMain {
      val engine = serviceBinder?.engine
      val positionMs = engine?.currentPositionMs ?: 0L
      val playing = engine?.isPlaying ?: false
      // Silence local immediately (after the state read) so the device
      // speaker doesn't overlap the receiver during the load round-trip.
      engine?.pause()
      val q = tracks
      val idx = currentTrackIndex
      if (idx < 0 || idx >= q.size) null
      else CastHandoffSnapshot(q.toList(), idx, positionMs, playing)
    } ?: return
    // Seed the bridge's resume snapshot from this local pre-handoff state
    // (captured above) so a disconnect before the receiver reports — the
    // load round-trip below has not settled yet — still restores local
    // continuity. Receiver updates override these as they arrive.
    castEventBridge.seedResumeSnapshot(
      trackIndex = snapshot.index,
      positionMs = snapshot.positionMs,
    )
    // A failed receiver load (e.g. unreachable LAN address, receiver
    // rejects the item) must never crash the app — fall back to local
    // playback, restoring the play-state captured above so the user isn't
    // left silent after the local engine was paused for the handoff.
    try {
      com.margelo.nitro.queueplayer.cast.CastTransportRouter.routeSetQueue(
        tracks = snapshot.tracks,
        startIndex = snapshot.index,
        startPositionMs = snapshot.positionMs,
        playWhenReady = snapshot.playing,
      )
    } catch (t: Throwable) {
      Log.w(PLAYER_LOG_TAG, "Cast handoff failed; staying on local playback", t)
      if (snapshot.playing) onMain { serviceBinder?.engine?.play() }
    }
  }

  private data class CastHandoffSnapshot(
    val tracks: List<TrackItem>,
    val index: Int,
    val positionMs: Long,
    val playing: Boolean,
  )

  /**
   * Return local playback to the receiver's last track + position after a
   * cast session ends. The local engine kept its (paused) queue during
   * cast, so this seeks it to the handback point and stays PAUSED — the
   * user resumes locally. This is the conventional cast->local handback;
   * it never auto-blasts the phone speaker on disconnect. A negative
   * [trackIndex] (receiver index never resolved) is a no-op.
   */
  internal fun resumeLocalAfterCast(trackIndex: Int, positionMs: Long) {
    onMainSync {
      val engine = serviceBinder?.engine ?: return@onMainSync
      // Bound the seek by the engine's prepared timeline, not just the
      // lib queue: seeking past the window count throws
      // IllegalSeekPositionException.
      if (trackIndex < 0 || trackIndex >= engine.allMediaItems.size) return@onMainSync
      currentTrackIndex = trackIndex
      engine.seekTo(trackIndex, maxOf(0L, positionMs))
      // The index drifted throughout the cast session without touching the
      // local prefetch or protection windows, so both are stale for the track
      // local playback is about to resume on.
      rescheduleLookahead()
      recomputeCapabilities()
    }
  }

  /**
   * Replace the entire queue and prime the ExoPlayer for playback.
   *
   * Steps:
   * 1. Stash the authoritative track list on `self.tracks`.
   * 2. Resolve `startAtIndex`: nil / out-of-range / negative coerce
   *    to 0; clamped to [0, newTracks.size - 1] for populated queues.
   *    Empty queue ignores the parameter (currentTrackIndex stays -1).
   * 3. Hand ExoPlayer the full MediaItem batch via
   *    `setMediaItems(items, resolvedStart, 0)` so the built-in
   *    gapless pipeline sees every track AND the player's current
   *    item is the consumer's chosen start index.
   * 4. Call `prepare()` — required after `setMediaItems` for ExoPlayer
   *    to transition out of `STATE_IDLE`. Does NOT start playback
   *    (caller invokes `play()` explicitly).
   */
  @VisibleForTesting
  @MainThread
  internal fun setQueueInternal(
    newTracks: List<TrackItem>,
    startAtIndex: Int? = null
  ) {
    val p = player ?: return
    val items = applyTrackListMetadata(newTracks) ?: return
    val resolvedStart =
      if (newTracks.isEmpty()) 0
      else (startAtIndex ?: 0).coerceIn(0, newTracks.size - 1)
    if (newTracks.isNotEmpty()) {
      this.currentTrackIndex = resolvedStart
    }
    pauseForVideoTrack()
    serviceBinder?.engine?.setMediaItems(
      items,
      startIndex = resolvedStart,
      startPositionMs = 0L
    )
    if (items.isNotEmpty()) {
      p.prepare()
    }
    serviceBinder?.engine?.setWakeMode(pickWakeMode(newTracks))
    rescheduleLookahead()
    recomputeCapabilities()
    emitQueueChange(
      QueueChangeReason.SET_QUEUE,
      inserted = tracks.toTypedArray(),
      insertedAt = if (tracks.isEmpty()) -1 else 0
    )
  }

  /**
   * Apply the queue-state side-effects of replacing the track list
   * WITHOUT setting items on the underlying engine. Shared by
   * [setQueueInternal] (which then sets engine items + prepares) and
   * [applyResolvedAssistantQueue] (where Media3's framework sets the
   * items on the player itself after `onAddMediaItems` /
   * `onSetMediaItems` returns the resolved list).
   *
   * Returns the [MediaItem] list built from [newTracks], or `null`
   * when the bound context is missing (no service binding yet —
   * caller bails).
   */
  @MainThread
  private fun applyTrackListMetadata(
    newTracks: List<TrackItem>
  ): List<MediaItem>? {
    val ctx = boundContext ?: return null
    this.tracks = newTracks
    this.currentTrackIndex = if (newTracks.isEmpty()) -1 else 0
    // Stash reason so Media3's REASON_PLAYLIST_CHANGED transition
    // maps to our QUEUE_REPLACED. Stashing here keeps the reason
    // channel symmetric with skip / seek.
    pendingTrackChangeReason = TrackChangeReason.QUEUE_REPLACED
    // Fresh queue → fresh dedup state. An error on the prior queue's
    // last item must not suppress an identical coded error on the
    // new queue's first item.
    lastErrorKey = null
    reportedFailureIndices.clear()
    cancelPendingRetry()
    // Reset auto-retry counters: new queue → new budget per index.
    val attempts = effectiveAutoRetries()
    retryAttemptsRemaining =
      (newTracks.indices.associateWith { attempts }).toMutableMap()
    return MediaItemBuilder.buildMediaItems(ctx, newTracks, lookaheadCache, config.placeholderArtworkUri)
  }

  /**
   * Sync the lib's authoritative queue state to a track list resolved
   * by an automotive controller (Android Auto / Google Assistant
   * voice search) before Media3's framework calls `player.setMediaItems`
   * itself. Reuses [applyTrackListMetadata] so the
   * `tracks` / `currentTrackIndex` / retry-budget side-effects match
   * the canonical [setQueueInternal] path; the engine call is
   * omitted because Media3's framework sets the items on the player
   * after `onAddMediaItems` / `onSetMediaItems` returns the resolved
   * list.
   *
   * Returns the [MediaItem] list the framework should set on the
   * player so the caller can hand it back to Media3 in one shape.
   */
  @MainThread
  internal fun applyResolvedAssistantQueue(
    newTracks: List<TrackItem>,
    append: Boolean = false
  ): List<MediaItem> {
    // `onAddMediaItems` means Media3 will APPEND what this returns, so the
    // lib's queue has to grow by the same amount. Replacing it here would
    // leave the player holding old + new while getQueue() reported only the
    // new tracks, and every index operation after that maps against the
    // wrong list. `onSetMediaItems` is a genuine replace and keeps that.
    // Media3 sets the resolved items on the session player after this returns,
    // which under crossfade addresses the leading leg only. An armed standby
    // leg still holds the queue as it stood when it was armed, so promoting it
    // would drop everything added here and auto-advance would stop at the old
    // queue end. Settle the fade first; the next boundary re-arms from the
    // updated queue. `CrossfadeEngine.addMediaItems` does this for the paths
    // that do go through the engine — this is the path that cannot.
    serviceBinder?.engine?.settlePendingFade()
    val resolved = if (append) this.tracks + newTracks else newTracks
    val appendAt = this.tracks.size
    val previousIndex = this.currentTrackIndex
    val items = applyTrackListMetadata(resolved) ?: return emptyList()
    if (append) {
      // An append does not move the listener off what is playing.
      this.currentTrackIndex = previousIndex
      pendingTrackChangeReason = null
    }
    serviceBinder?.engine?.setWakeMode(pickWakeMode(resolved))
    rescheduleLookahead()
    recomputeCapabilities()
    // The tracks came from a voice / Android Auto request, so a subscriber has
    // no other way to learn them — carry them either way.
    emitQueueChange(
      if (append) QueueChangeReason.ADD else QueueChangeReason.SET_QUEUE,
      inserted = if (append) resolved.drop(appendAt).toTypedArray() else resolved.toTypedArray(),
      insertedAt = if (append) appendAt else if (resolved.isEmpty()) -1 else 0
    )
    // Media3 appends only what it is handed back, so return the added slice.
    return if (append) items.drop(appendAt) else items
  }

  override fun addToQueue(tracks: Array<TrackItem>, insertBefore: Double?): Promise<Unit> =
    Promise.async {
      // Mirror `seekToInternal`'s non-finite guard: NaN/±Infinity
      // from JS would `.toInt()` to MIN/MAX or 0 silently. When
      // insertBefore is non-finite, drop it (treat as "append at
      // end") rather than silently jumping to position 0.
      val newTracks = tracks.toList()
      val sanitisedInsert = insertBefore?.takeIf { it.isFinite() }?.toInt()
      // Mutate the local source-of-truth queue and capture the clamped
      // insert index against the pre-mutation queue, then mirror the
      // insert to the receiver. `routeAddToQueue` is suspending (it
      // awaits the receiver round-trip) so the call happens outside
      // `onMain`. Mirrors iOS `addToQueue`.
      val insertAt = onMain { addToQueueInternal(newTracks, sanitisedInsert) }
      if (newTracks.isNotEmpty() && insertAt >= 0) {
        com.margelo.nitro.queueplayer.cast.CastTransportRouter
          .routeAddToQueue(newTracks, insertAt)
      }
    }

  /**
   * Incrementally extend the queue. Does NOT disturb the currently-
   * playing item — Media3 `addMediaItems` inserts without re-priming
   * the pipeline, and we mirror the index math onto the authoritative
   * `tracks` list + `currentTrackIndex` via [QueueMutationArithmetic].
   *
   * Does NOT stash [pendingTrackChangeReason]. Media3 fires
   * `REASON_PLAYLIST_CHANGED` on the subsequent transition, which
   * [handleMediaItemTransition] routes to the default
   * [TrackChangeReason.QUEUE_REPLACED]. addToQueue / removeFromQueue
   * / moveInQueue all fall through to that same default by design
   * — the PlayerState spec does not yet carry a finer-grained
   * "queue mutated incrementally" reason.
   */
  @VisibleForTesting
  internal fun addToQueueInternal(newTracks: List<TrackItem>, insertBefore: Int?): Int {
    // Empty input → no track-list shift, no lookahead window shift,
    // no reschedule needed. The trailing `rescheduleLookahead()` is
    // intentionally not run on this branch. `-1` signals the caller's
    // cast mirror to skip the receiver insert.
    if (newTracks.isEmpty()) return -1
    val p = player ?: return -1
    val ctx = boundContext ?: return -1

    val at = QueueMutationArithmetic.clampInsertBefore(insertBefore, tracks.size)
    // A mutation renumbers positions, so every recorded index but the current
    // track's is stale afterwards. The one that matters is the stop on the
    // track being heard: it follows the index through the mutation, and the
    // rest go rather than being carried onto whatever now sits at their number.
    val wasStoppedOnCurrent = currentTrackIndex in reportedFailureIndices
    this.tracks = tracks.toMutableList().apply { addAll(at, newTracks) }
    this.currentTrackIndex = QueueMutationArithmetic.currentIndexAfterAdd(
      currentIndex = currentTrackIndex,
      insertAt = at,
      count = newTracks.size
    )

    // Index-keyed retry-budget map: insertion at `at` shifts every
    // existing key >= at up by `newTracks.size`. The shift must run in
    // descending key order: each iteration removes `oldIdx` and writes
    // `oldIdx + shift`, and in ascending order the write for key k lands
    // on k + shift, and the later iteration for k + shift removes it, so
    // that budget is lost.
    cancelPendingRetry()
    val attempts = effectiveAutoRetries()
    val shift = newTracks.size
    val shifted = retryAttemptsRemaining
      .filterKeys { it >= at }
      .toSortedMap(compareByDescending { it })
    for ((oldIdx, count) in shifted) {
      retryAttemptsRemaining.remove(oldIdx)
      retryAttemptsRemaining[oldIdx + shift] = count
    }
    for (newIdx in at until at + shift) {
      retryAttemptsRemaining[newIdx] = attempts
    }
    reportedFailureIndices.clear()
    if (wasStoppedOnCurrent) reportedFailureIndices.add(currentTrackIndex)
    val items = MediaItemBuilder.buildMediaItems(ctx, newTracks, lookaheadCache, config.placeholderArtworkUri)
    serviceBinder?.engine?.addMediaItems(items, insertBefore = at)
    // Diverges from iOS: add-into-empty-queue on iOS leaves the
    // AVQueuePlayer empty until the consumer calls skipToIndex(0).
    // Android primes the pipeline here so a subsequent
    // skipToIndex(0) + play() starts instantly rather than going
    // through an IDLE → prepare → BUFFERING transition at play
    // time. Deliberate improvement — cur stays at -1 so the
    // documented "empty-queue add does not auto-start playback"
    // contract is still intact.
    if (p.playbackState == Player.STATE_IDLE && p.mediaItemCount > 0) {
      p.prepare()
    }
    // Re-evaluate against the post-add `tracks` list — adding even
    // one remote URL into a previously-local queue must upgrade the
    // wake-mode to NETWORK so the WiFi wake lock is held while
    // streaming.
    serviceBinder?.engine?.setWakeMode(pickWakeMode(this.tracks))
    rescheduleLookahead()
    recomputeCapabilities()
    emitQueueChange(
      QueueChangeReason.ADD,
      inserted = newTracks.toTypedArray(),
      insertedAt = at
    )
    return at
  }

  override fun removeFromQueue(indices: DoubleArray): Promise<Unit> =
    Promise.async {
      // Mutate the local source-of-truth queue and capture the
      // sanitised-AND-pin-filtered indices the body actually removed,
      // then mirror that exact set to the receiver. `routeRemoveFromQueue`
      // is suspending so it runs outside `onMain`. Mirrors iOS
      // `removeFromQueue`.
      val rawIndices = indices.map { it.toInt() }
      val castIndices = onMain { removeFromQueueInternal(rawIndices) }
      if (castIndices.isNotEmpty()) {
        com.margelo.nitro.queueplayer.cast.CastTransportRouter
          .routeRemoveFromQueue(castIndices)
      }
    }

  @VisibleForTesting
  internal fun removeFromQueueInternal(rawIndices: List<Int>): List<Int> {
    val p = player ?: return emptyList()
    val trackCountBefore = tracks.size
    // The currently-playing track is pinned: it cannot be removed via
    // removeFromQueue (a consumer that wants it gone skips off it first,
    // then removes it). Drop the current index from the set — removing
    // only the current track is a no-op; a mixed request drops the other
    // tracks and keeps the current one. Matches iOS removeFromQueueBody.
    val sanitised = QueueMutationArithmetic.sanitiseRemoveIndices(
      rawIndices, trackCountBefore
    ).filter { it != currentTrackIndex }
    // Sanitised-empty (all inputs out-of-range / negative dups
    // collapsing to nothing, or only the pinned current index) → no
    // track-list change, no lookahead window shift. Skip the reschedule.
    if (sanitised.isEmpty()) return emptyList()

    val oldCur = currentTrackIndex
    val descending = sanitised.asReversed()

    // Apply to the authoritative track list first. Descending order
    // so each removal index stays valid as the list shrinks.
    val newTracks = tracks.toMutableList()
    for (i in descending) {
      newTracks.removeAt(i)
    }
    this.tracks = newTracks
    // A mutation renumbers positions, so every recorded index but the current
    // track's is stale afterwards. The one that matters is the stop on the
    // track being heard: it follows the index through the mutation, and the
    // rest go rather than being carried onto whatever now sits at their number.
    val wasStoppedOnCurrent = currentTrackIndex in reportedFailureIndices
    this.currentTrackIndex = QueueMutationArithmetic.currentIndexAfterRemove(
      currentIndex = oldCur,
      sanitisedIndices = sanitised,
      trackCountBefore = trackCountBefore
    )

    // Forward removals to the engine. The engine sorts descending
    // and walks each index individually so positions before each
    // removal stay stable.
    //
    // Ordering note: Kotlin state writes land BEFORE the engine
    // removal, so an `onMediaItemTransition` listener fired during
    // an auto-advance triggered by removing the current item sees
    // the post-remove `currentTrackIndex`. A listener that needs
    // the pre-mutation reason must cache `oldCur` itself.
    val inBoundsDescending = descending.filter { it < p.mediaItemCount }
    if (inBoundsDescending.isNotEmpty()) {
      serviceBinder?.engine?.removeMediaItems(inBoundsDescending.toIntArray())
    }
    // Index-keyed retry-budget map: removals shift remaining keys
    // down. We could walk the survivors with an offset count to
    // preserve in-flight budgets for unaffected items, but the
    // simpler clear+repopulate is intentional:
    //
    // - **Structural-reset semantics**: removeFromQueue is a
    //   coarse mutation. Resetting budgets aligns with how
    //   setQueue / clearQueue / shuffleQueue all behave — every
    //   queue mutation that's not a single-item move is a fresh
    //   slate.
    // - **Trade-off**: a failing item that's NOT the one being
    //   removed gets its budget reset. A consumer hitting a flaky
    //   item, mid-retry, who removes a different item, gives the
    //   flaky item another full budget. Generous-not-stingy is the
    //   right default for a UX where the user's action signals
    //   "try again with fresh expectations".
    // - **Why not preserve via remap**: requires walking the map
    //   AND the sanitised-indices in lockstep with offset
    //   accounting. The clear-and-repopulate is intentional.
    cancelPendingRetry()
    val attempts = effectiveAutoRetries()
    retryAttemptsRemaining.clear()
    for (i in this.tracks.indices) retryAttemptsRemaining[i] = attempts
    reportedFailureIndices.clear()
    if (wasStoppedOnCurrent) reportedFailureIndices.add(currentTrackIndex)
    // Mirror the wake-mode hook on `setQueueInternal` /
    // `addToQueueInternal`: removing the last remote URL out of a
    // mixed queue must downgrade to LOCAL so the WiFi wake lock
    // doesn't stay held for the rest of the session.
    serviceBinder?.engine?.setWakeMode(pickWakeMode(this.tracks))
    rescheduleLookahead()
    recomputeCapabilities()
    // Reached only past the `sanitised.isEmpty()` guard, so ≥1 track was
    // removed; tracks + index are final.
    emitQueueChange(QueueChangeReason.REMOVE, removed = sanitised)
    return sanitised
  }

  override fun moveInQueue(fromIndex: Double, toIndex: Double): Promise<Unit> =
    Promise.async {
      // Mutate the local source-of-truth queue and capture the
      // validated (from, to) only when the body actually moved, then
      // mirror the reorder to the receiver. `routeMoveInQueue` is
      // suspending so it runs outside `onMain`. Mirrors iOS `moveInQueue`.
      val move = onMain {
        // Mirror `seekToInternal`'s non-finite guard.
        if (!fromIndex.isFinite() || !toIndex.isFinite()) return@onMain null
        val from = fromIndex.toInt()
        val to = toIndex.toInt()
        if (moveInQueueInternal(from, to)) from to to else null
      }
      if (move != null) {
        com.margelo.nitro.queueplayer.cast.CastTransportRouter
          .routeMoveInQueue(move.first, move.second)
      }
    }

  /**
   * Move a single track to a new position. The currently-playing track
   * is pinned: moving it (`from == currentTrackIndex`) is a no-op on
   * both platforms — only the tracks around the current one reorder.
   * This keeps the contract identical cross-platform (iOS's
   * AVQueuePlayer cannot reorder the play-head item without restarting
   * it, so both platforms simply disallow it).
   */
  @VisibleForTesting
  internal fun moveInQueueInternal(from: Int, to: Int): Boolean {
    val p = player ?: return false
    if (from < 0 || from >= tracks.size) return false
    if (to < 0 || to >= tracks.size) return false
    // from == to is a no-op move — track list unchanged, no
    // lookahead window shift. Skip the trailing reschedule.
    if (from == to) return false
    // The currently-playing track is pinned; moving it is a no-op.
    // Matches iOS moveInQueueBody.
    if (from == currentTrackIndex) return false

    val mutable = tracks.toMutableList()
    val moved = mutable.removeAt(from)
    mutable.add(to, moved)
    this.tracks = mutable

    // A mutation renumbers positions, so every recorded index but the current
    // track's is stale afterwards. The one that matters is the stop on the
    // track being heard: it follows the index through the mutation, and the
    // rest go rather than being carried onto whatever now sits at their number.
    val wasStoppedOnCurrent = currentTrackIndex in reportedFailureIndices
    this.currentTrackIndex = QueueMutationArithmetic.currentIndexAfterMove(
      currentIndex = currentTrackIndex,
      fromIndex = from,
      toIndex = to
    )

    if (from < p.mediaItemCount && to < p.mediaItemCount) {
      serviceBinder?.engine?.moveMediaItem(from, to)
    }
    // Index-keyed retry-budget map: a move shuffles indices
    // arbitrarily. Cheapest correct path is to clear + repopulate
    // (same trade-off as removeFromQueueInternal — structural reset
    // semantics for queue mutations).
    cancelPendingRetry()
    val attempts = effectiveAutoRetries()
    retryAttemptsRemaining.clear()
    for (i in this.tracks.indices) retryAttemptsRemaining[i] = attempts
    reportedFailureIndices.clear()
    if (wasStoppedOnCurrent) reportedFailureIndices.add(currentTrackIndex)
    rescheduleLookahead()
    recomputeCapabilities()
    // Reached only past the invalid / `from == to` / pinned-current
    // guards, so the order actually changed.
    emitQueueChange(QueueChangeReason.MOVE, movedFrom = from, movedTo = to)
    return true
  }

  override fun getQueue(): Promise<Array<TrackItem>> =
    Promise.async { onMain { tracks.toTypedArray() } }

  override fun updateMetadata(index: Double, metadata: TrackMetadataUpdate): Promise<Unit> =
    Promise.async { onMain { updateMetadataInternal(index, metadata) } }

  @VisibleForTesting
  internal fun updateMetadataInternal(index: Double, metadata: TrackMetadataUpdate) {
    if (!index.isFinite() || index < 0 || index >= tracks.size || index % 1.0 != 0.0) return
    val ctx = boundContext ?: return
    val engine = serviceBinder?.engine ?: return
    val position = index.toInt()
    val old = tracks[position]
    val updated = old.copy(
      title = metadata.title ?: old.title,
      artist = metadata.artist ?: old.artist,
      album = metadata.albumTitle ?: old.album,
      artworkUrl = metadata.artworkUrl ?: old.artworkUrl,
    )
    if (old == updated) return
    tracks = tracks.toMutableList().apply { this[position] = updated }
    if (position == currentTrackIndex) announcedTrack = updated
    engine.updateMetadata(position, MediaItemBuilder.buildMetadata(ctx, updated, config.placeholderArtworkUri))
    emitQueueChange(QueueChangeReason.UPDATE_METADATA, inserted = arrayOf(updated), insertedAt = position)
  }

  override fun clearQueue(): Promise<Unit> =
    Promise.async {
      // Clear the local source-of-truth queue, then mirror the clear to
      // the receiver (removes every receiver queue item). `routeClearQueue`
      // is suspending so it runs outside `onMain`. Mirrors iOS `clearQueue`.
      onMain { clearQueueInternal() }
      com.margelo.nitro.queueplayer.cast.CastTransportRouter.routeClearQueue()
    }

  @VisibleForTesting
  internal fun clearQueueInternal() {
    val p = player ?: return
    val queueWasNonEmpty = tracks.isNotEmpty()
    // Reset authoritative state BEFORE the ExoPlayer mutation.
    // `clearMediaItems()` fires `onMediaItemTransition(null,
    // REASON_PLAYLIST_CHANGED)` synchronously, which routes through
    // `handleMediaItemTransition` → `trackChangeListeners.forEach` →
    // reads `tracks[currentTrackIndex]`. With the old order
    // (clear → reset), the listener would read the pre-clear state
    // and fire onTrackChange with (oldTrack, oldIndex, QUEUE_REPLACED)
    // instead of (null, -1, QUEUE_REPLACED), leaving JS hooks /
    // useActiveTrack stuck on stale values. setQueueInternal already
    // follows this invariant — same rule applies here.
    pendingTrackChangeReason = TrackChangeReason.QUEUE_REPLACED
    // Reset error-dedup so a subsequent queue's identical-coded error
    // isn't suppressed by a stale entry from the cleared queue.
    lastErrorKey = null
    reportedFailureIndices.clear()
    cancelPendingRetry()
    retryAttemptsRemaining.clear()
    tracks = emptyList()
    currentTrackIndex = -1
    // Fire queue-change BEFORE setMediaItems (which synchronously fires
    // onTrackChange via onMediaItemTransition) so a `useQueue()` consumer
    // sees the empty queue + index -1 together. Only when the queue was
    // actually non-empty — clearing an empty queue is a no-op.
    if (queueWasNonEmpty) emitQueueChange(QueueChangeReason.CLEAR)
    // Route through the engine surface (not directly through the
    // leading leg) so CrossfadeEngine can also tear down the
    // standby leg's preroll'd items + cancel any in-flight fade.
    // setMediaItems(emptyList(), 0, 0L) is the canonical "drain"
    // signal both engine impls handle correctly.
    serviceBinder?.engine?.setMediaItems(emptyList(), startIndex = 0, startPositionMs = 0L)
    // Drop the WiFi wake lock if the prior queue was remote.
    // Distinguished from `pickWakeMode(emptyList())` (which returns
    // NETWORK conservatively because we don't know what's coming
    // next) — post-clear there's nothing playing AND nothing
    // scheduled, so LOCAL is correct. The next setQueue picks the
    // mode based on the new queue's URI mix. The engine.setMediaItems
    // call above already drained both legs; routing through the engine
    // applies the wake-mode write to BOTH crossfade legs, not just the
    // leading one.
    serviceBinder?.engine?.setWakeMode(C.WAKE_MODE_LOCAL)
    // Drop any cached now-playing format + emit null so JS-side
    // consumers of `onNowPlayingFormatChange` see the queue-cleared
    // state without waiting for ExoPlayer's `currentMediaItem` to
    // settle (it can briefly retain the last item post-clearMediaItems).
    refreshNowPlayingFormatForActiveItem()
    rescheduleLookahead()
    recomputeCapabilities()
    // Queue empty → settle buffer state + fully-buffered to their empty
    // defaults and emit the transition. The engine reaching STATE_IDLE above
    // recomputes buffer state too, but reset the intent flags + recompute
    // explicitly so an immediate re-queue of the same first item (no
    // track-change fire) starts as `buffering`, not `stalled`.
    hasStartedPlaying = false
    wantsToPlay = false
    recomputeBufferState()
    recomputeFullyBuffered()
  }

  // --- Transport: pass-through to ExoPlayer.
  // No event wiring here — state transitions flow through the player
  // listener + polling tick.

  override fun play(): Promise<Unit> =
    Promise.async {
      onMain {
        if (pauseForVideoTrack()) return@onMain
        // Cast routing: a live remote-player session takes the
        // transport command; otherwise fall through to the local
        // engine. Same shape at every transport entry point — see
        // `CastTransportRouter` for the early-return contract.
        if (com.margelo.nitro.queueplayer.cast.CastTransportRouter.routePlay()) return@onMain
        // Stuck-recovery: if the user re-taps play after an exhausted-retries
        // error, hand the track back the allowance the consumer configured and
        // re-prepare. Not a hard-coded 1: a consumer who set `autoRetries: 0`
        // asked for none. Mirrors iOS `play()` stuck-recovery.
        if (currentTrackIndex >= 0 && currentTrackIndex in reportedFailureIndices) {
          retryAttemptsRemaining[currentTrackIndex] = effectiveAutoRetries()
          lastErrorKey = null
          // `retryFailedItem`, not a bare `prepare()`: a bare prepare re-uses
          // the wedged DataSource this exists to discard. It removes and re-adds
          // the item, which rebuilds the source from the MediaSource.Factory.
          retryFailedItem(currentTrackIndex)
        } else if (currentTrackIndex >= 0 &&
          player?.playbackState == Player.STATE_IDLE &&
          (player?.mediaItemCount ?: 0) > 0
        ) {
          // With no current track there is no position to re-seat, which the
          // index guard above covers; the branch below refuses to start
          // anything in that state at all.
          // A failure leaves ExoPlayer in STATE_IDLE, and a skip off the failed
          // track seeks without re-preparing — so `play()` would set
          // `playWhenReady` on a player that never leaves IDLE and nothing would
          // be heard. `retryFailedItem`, not a bare `prepare()`: on a same-index
          // skip the item is the one that failed, and a bare prepare re-uses the
          // DataSource that wedged. The timeline has to hold items for prepare to
          // reach anything — between `clearQueue` and the next `setMediaItems`
          // it is empty, and preparing there lands in STATE_ENDED, which the
          // line below reads as a finished queue.
          retryFailedItem(currentTrackIndex)
        }
        // At the end of the queue there is nothing to play: ExoPlayer stays
        // STATE_ENDED through play(), and a playWhenReady left set would
        // start the next track a skip seats without anyone asking. A skip, a
        // seek or a fresh queue leaves this state; play() alone restarts
        // nothing — the same contract as iOS.
        if (player?.playbackState == Player.STATE_ENDED && tracks.isNotEmpty()) return@onMain
        // Nothing is seated: an add into an empty queue primes the player and
        // deliberately leaves the current index at -1, so starting here would
        // play position 0 while every read still answers for no track. The
        // consumer seats one — `skipToIndex(0)` — and plays. iOS reaches the
        // same outcome by leaving its queue player empty until then.
        if (currentTrackIndex < 0) return@onMain
        // Stash USER on the pending-reason channel so the
        // subsequent Player.Listener fire attributes the transition
        // correctly.
        pendingStateChangeReason = StateChangeReason.USER
        wantsToPlay = true
        serviceBinder?.engine?.play()
        recomputeBufferState()
      }
    }

  override fun retry(): Promise<Unit> =
    Promise.async {
      onMain {
        // During cast the receiver owns playback — forward a play so a failed
        // receiver item re-attempts.
        if (com.margelo.nitro.queueplayer.cast.CastTransportRouter.routePlay()) return@onMain
        // A user asking for a retry has a reason, and the library is not the
        // one to judge it. This is the manual attempt once the automatic one
        // is spent, so gating it on the player's state would gate recovery on
        // the state of a player that has already failed to recover — and a
        // wedged decoder still reporting PLAYING is exactly when someone
        // reaches for the button. retryFailedItem below discards the wedged
        // DataSource (a bare prepare() would re-use it), restores the
        // pre-failure position, and resumes playing.
        //
        // Only the bounds remain: no current track is genuinely nothing to
        // retry, which is not second-guessing the request.
        if (currentTrackIndex < 0) return@onMain
        // The configured budget, not a hard-coded 1: this restores the track's
        // automatic allowance for whatever fails *next*, and a consumer who
        // set `autoRetries: 0` asked for none.
        retryAttemptsRemaining[currentTrackIndex] = effectiveAutoRetries()
        lastErrorKey = null
        pendingStateChangeReason = StateChangeReason.USER
        wantsToPlay = true
        // Drops this track's report on its way through, before its own
        // current-track bail, so a retry always clears what it attempts.
        retryFailedItem(currentTrackIndex)
        recomputeBufferState()
      }
    }

  override fun pause(): Promise<Unit> =
    Promise.async {
      onMain {
        if (com.margelo.nitro.queueplayer.cast.CastTransportRouter.routePause()) return@onMain
        pendingStateChangeReason = StateChangeReason.USER
        wantsToPlay = false
        serviceBinder?.engine?.pause()
        recomputeBufferState()
      }
    }

  /**
   * "Stop" = pause + seek to start of current item. Does NOT clear
   * the queue — consumers wanting a full reset call clearQueue()
   *. iOS parity.
   */
  override fun stop(): Promise<Unit> =
    Promise.async {
      onMain {
        if (com.margelo.nitro.queueplayer.cast.CastTransportRouter.routeStop()) return@onMain
        val engine = serviceBinder?.engine ?: return@onMain
        pendingStateChangeReason = StateChangeReason.USER
        wantsToPlay = false
        hasStartedPlaying = false
        engine.pause()
        // Skip the seek when no track is current (queue empty OR
        // configure transient where engine items exist but the
        // canonical model hasn't pinned an index yet); jumping to
        // index 0 in that window would force the head off whatever
        // the engine's stale current item happens to be.
        if (currentTrackIndex >= 0) {
          engine.seekTo(currentTrackIndex, 0L)
        }
        recomputeBufferState()
      }
    }

  /**
   * Seek within the current track. [position] is in seconds; negative
   * values clamp to 0, values past the duration clamp to duration.
   * ExoPlayer takes milliseconds so we convert here.
   */
  override fun seekTo(position: Double): Promise<Unit> =
    Promise.async {
      onMain {
        val positionMs = (position.coerceAtLeast(0.0) * 1000.0).toLong()
        if (com.margelo.nitro.queueplayer.cast.CastTransportRouter.routeSeekTo(positionMs)) return@onMain
        seekToInternal(position)
      }
    }

  @VisibleForTesting
  internal fun seekToInternal(position: Double) {
    val p = player ?: return
    // Sanitise non-finite JS inputs (NaN / ±Infinity). Without this
    // `(Infinity * 1000.0).toLong()` resolves to Long.MAX_VALUE which
    // ExoPlayer interprets as "seek past end"; NaN silently floors to
    // 0 which is benign but misleading. Treat both as invalid and
    // no-op rather than letting the clamp fall through.
    //
    // No `rescheduleLookahead()` call anywhere in this function:
    // intra-track seek does NOT shift the lookahead window
    // (`tracks` + `currentTrackIndex` are unchanged), so the
    // prefetcher's existing chain stays valid. The omission is
    // deliberate, not a forgotten case.
    if (!position.isFinite()) return
    // Protect against ExoPlayer's "seek on an unseekable track silently resets
    // to the start" behaviour: no-op the seek once the item is loaded
    // (STATE_READY / ENDED) AND confirmed unseekable (a live source, or a VBR
    // MP3 with no seek index). Gating on loaded state means an early seek on an
    // eventually-seekable track — whose window isn't populated yet — still
    // lands. Seekable tracks (incl. CBR MP3 / ADTS / AMR via constant-bitrate
    // seeking) seek freely and re-buffer from the target.
    val loaded = p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_ENDED
    if (loaded && !p.isCurrentMediaItemSeekable) return
    // Clamp against the ACTIVE leg's duration. During a crossfade the
    // active track is the incoming (standby) leg, so reading the
    // leading player's `duration` would clamp against the wrong track.
    // `currentDurationMs` retargets to the active leg mid-fade and
    // equals `player.duration` on the gapless engine.
    val durationMs = serviceBinder?.engine?.currentDurationMs ?: p.duration
    val clampedSeconds = if (position < 0.0) {
      0.0
    } else {
      // `currentDurationMs` normalizes an unknown duration to 0; the
      // `?: p.duration` fallback reports TIME_UNSET. Treat BOTH as "duration
      // not known yet" and pass the seek through unclamped — clamping an
      // early-load or duration-less/live track to a 0 duration would snap
      // every forward seek back to position 0.
      if (durationMs == C.TIME_UNSET || durationMs <= 0L) {
        position
      } else {
        val durationSeconds = durationMs / MS_PER_SECOND
        if (position > durationSeconds) durationSeconds else position
      }
    }
    val finalSeconds = clampToBufferedIfEnabled(clampedSeconds)
    // currentTrackIndex == -1 only when the timeline is empty;
    // Media3's seekTo short-circuits cleanly in that case so the
    // coerce here is benign rather than load-bearing.
    serviceBinder?.engine?.seekTo(
      currentTrackIndex.coerceAtLeast(0),
      (finalSeconds * MS_PER_SECOND).toLong(),
    )
    // Move the milestone baseline to the seek target without emitting, so
    // thresholds the seek jumps over are suppressed (they weren't played).
    milestoneTracker.seek(
      effectiveMilestoneDuration(serviceBinder?.engine?.currentDurationMs ?: C.TIME_UNSET),
      finalSeconds,
    )
  }

  // Cap `targetSeconds` at the current buffered extent when `clampSeekToBuffered`
  // is enabled and the target is past what has buffered. A local or fully-
  // buffered track reports `bufferedPosition >= duration >= target`, so nothing
  // is clamped and a seek reaches the end freely; a partly-buffered stream caps
  // at the downloaded edge so a scrub doesn't overshoot into a re-buffer. Reads
  // the live buffered position (not a tracked mark) so a seek-back that evicted
  // the ahead-buffer clamps to what's actually there.
  private fun clampToBufferedIfEnabled(targetSeconds: Double): Double {
    if (config.clampSeekToBuffered != true) return targetSeconds
    val bufferedMs = player?.bufferedPosition ?: return targetSeconds
    if (bufferedMs == C.TIME_UNSET || bufferedMs <= 0L) return targetSeconds
    val bufferedSeconds = bufferedMs / MS_PER_SECOND
    return if (targetSeconds > bufferedSeconds) bufferedSeconds else targetSeconds
  }

  // Map the live player's Media3 state + lib intent flags to a discrete
  // [BufferState]; the translation itself lives in [BufferStateTranslator].
  private fun computeBufferState(): BufferState {
    val p = player ?: return BufferState.EMPTY
    return BufferStateTranslator.translate(p.playbackState, hasStartedPlaying, wantsToPlay)
  }

  // Recompute the current buffer state and fire `onBufferStateChange` on an
  // actual change. Runs on the engine state-change delegate and the transport
  // methods — every input (playbackState, playWhenReady, the intent flags)
  // changes through one of those, so (unlike iOS, whose crossfade engine has no
  // per-item buffer KVO and leans on the progress tick) the tick is
  // deliberately NOT a recompute site here. Main-thread confined (Media3 reads).
  internal fun recomputeBufferState() {
    val computed = computeBufferState()
    currentBufferState = computed
    if (computed != lastEmittedBufferState) {
      lastEmittedBufferState = computed
      bufferStateListeners.forEach { it(computed) }
    }
  }

  // The whole track has downloaded when the buffered position reaches the
  // duration. Falls back to the consumer-supplied track duration (the same
  // fallback milestones use) when the player can't report one — e.g. a
  // transcoded stream. Constant-bitrate seeking also supplies an estimated
  // duration for CBR MP3, so fully-buffered is approximate for those. A live /
  // dynamic window never completes.
  private fun computeFullyBuffered(): Boolean {
    val p = player ?: return false
    if (p.isCurrentMediaItemLive || p.isCurrentMediaItemDynamic) return false
    // A local file or a fully-cached source is entirely on disk — fully
    // available regardless of the player's windowed in-memory buffer (ExoPlayer
    // only holds ~tens of seconds ahead, so bufferedPosition never reaches
    // duration for a long file even when nothing is left to fetch).
    val source = currentTrackSource
    if (source == TrackSource.LOCAL || source == TrackSource.CACHED) return true
    val durSeconds = effectiveMilestoneDuration(p.duration)
    if (durSeconds <= 0.0) return false
    val bufferedMs = p.bufferedPosition
    if (bufferedMs == C.TIME_UNSET) return false
    return bufferedMs / MS_PER_SECOND >= durSeconds - FULLY_BUFFERED_EPSILON_MS / MS_PER_SECOND
  }

  // Recompute fully-buffered and fire `onFullyBufferedChange` on a change. Runs
  // on the progress tick (bufferedPosition grows continuously toward duration)
  // and at track change. Main-thread confined (Media3 reads).
  internal fun recomputeFullyBuffered() {
    val computed = computeFullyBuffered()
    currentFullyBuffered = computed
    if (computed != lastEmittedFullyBuffered) {
      lastEmittedFullyBuffered = computed
      fullyBufferedListeners.forEach { it(computed) }
    }
  }

  // --- Skip
  //
  // Android ExoPlayer is natively bidirectional — `seekTo(mediaItemIndex,
  // positionMs)` moves to any item in the currently-set queue without
  // the iOS-side AVQueuePlayer-forward-only-rebuild ceremony. The
  // skip methods delegate the index math to [QueueSkipArithmetic]
  // (pure port of the iOS sibling) and then call
  // `player.seekTo(newIndex, 0L)`.
  //
  // When `tracks` is empty and `currentTrackIndex` stays at -1, the
  // arithmetic returns null and each skip method silently no-ops.

  override fun skipToIndex(index: Double): Promise<Unit> =
    Promise.async {
      onMain {
        // Mirror `seekToInternal`'s non-finite guard. Without this
        // `Double.NaN.toInt()` returns 0 — `skipToIndex(NaN)` would
        // silently jump to track 0 instead of no-op'ing. iOS uses
        // `InputGuards.validQueueIndex` for the same guarantee.
        if (!index.isFinite()) return@onMain
        val target = index.toInt()
        if (target < 0 || target >= tracks.size) return@onMain
        // During cast, jump the receiver to the target index; its status
        // echo drives currentTrackIndex + the metadata/active-track update
        // back through the bridge. The local engine stays paused, so a
        // local seek here would desync from the receiver. `routeSkipToIndex`
        // is synchronous, so it's safe inside `onMain`. Mirrors iOS
        // `skipToIndex`.
        if (com.margelo.nitro.queueplayer.cast.CastTransportRouter.routeSkipToIndex(target)) {
          return@onMain
        }
        skipToIndexInternal(target)
      }
    }

  /**
   * Explicit consumer-target skip. Honours the index in every
   * repeat mode, including `QUEUE` (it never wraps — the target is
   * absolute). Out-of-range / empty-queue inputs silently no-op
   * (iOS parity).
   */
  @VisibleForTesting
  internal fun skipToIndexInternal(target: Int) {
    val p = player ?: return
    if (target < 0 || target >= tracks.size) return
    val engine = serviceBinder?.engine ?: return
    // Adding to an empty queue primes Media3 at 0 while our selection stays
    // at -1. Selecting that primed item is a logical track change, but a
    // same-item seek does not produce onMediaItemTransition from Media3.
    val nativeItemUnchanged = p.currentMediaItemIndex == target
    val selectionChanged = currentTrackIndex != target
    currentTrackIndex = target
    pauseForVideoTrack()
    // Seating a position is an attempt at it, so the report against it no
    // longer describes what is about to play. Cleared here rather than left to
    // the media-item transition, which does not fire for a skip that seats the
    // track already selected. Mirrors iOS `applyNewCurrentIndex`.
    reportedFailureIndices.remove(target)
    pendingTrackChangeReason = if (selectionChanged || !nativeItemUnchanged)
      TrackChangeReason.USER_SKIP_TO_INDEX else null
    // Guard against IllegalSeekPositionException: `seekTo(index, 0L)`
    // throws when `index` is outside `mediaItemCount`. When the lib
    // is between `clearQueue` and the next `setMediaItems`, the
    // player's timeline is empty and we only update the logical
    // index — a future setQueue re-issues the seek as it sets the
    // media items.
    if (target < p.mediaItemCount) {
      engine.seekTo(target, 0L)
      if (selectionChanged && nativeItemUnchanged && pendingTrackChangeReason != null) {
        onTrackTransition(engine, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
      }
    }
    rescheduleLookahead()
    recomputeCapabilities()
  }

  override fun skipToNext(): Promise<Unit> =
    Promise.async {
      onMain {
        if (com.margelo.nitro.queueplayer.cast.CastTransportRouter.routeSkipToNext()) return@onMain
        skipToNextInternal()
      }
    }

  @VisibleForTesting
  internal fun skipToNextInternal() {
    val p = player ?: return
    val next = computeNextIndex() ?: return
    currentTrackIndex = next
    pauseForVideoTrack()
    reportedFailureIndices.remove(next)
    pendingTrackChangeReason = TrackChangeReason.USER_SKIP_NEXT
    if (next < p.mediaItemCount) {
      serviceBinder?.engine?.seekTo(next, 0L)
    }
    rescheduleLookahead()
    recomputeCapabilities()
  }

  override fun skipToPrevious(): Promise<Unit> =
    Promise.async {
      onMain {
        if (com.margelo.nitro.queueplayer.cast.CastTransportRouter.routeSkipToPrevious()) return@onMain
        skipToPreviousInternal()
      }
    }

  /**
   * Skip-previous, resolved by [QueueSkipArithmetic.resolveSkipToPrevious]
   * from `PlayerConfig.skipToPreviousBehavior`. With the default PREVIOUS this
   * goes to the previous track if there is one (OFF/TRACK don't wrap, QUEUE
   * wraps; TRACK navigates like OFF and repeat-one persists). With
   * RESTART_OR_PREVIOUS it restarts the current track (seek to 0) when past
   * the threshold, otherwise goes to the previous track.
   */
  @VisibleForTesting
  internal fun skipToPreviousInternal() {
    val p = player ?: return
    val action = QueueSkipArithmetic.resolveSkipToPrevious(
      trackCount = tracks.size,
      currentIndex = currentTrackIndex,
      repeatMode = repeatModeAsSkipMode(),
      restartEnabled = skipRestartEnabled(),
      positionMs = serviceBinder?.engine?.currentPositionMs ?: 0L
    )
    when (action) {
      is QueueSkipArithmetic.SkipToPreviousAction.Previous -> {
        val prev = action.index
        currentTrackIndex = prev
        pauseForVideoTrack()
        reportedFailureIndices.remove(prev)
        pendingTrackChangeReason = TrackChangeReason.USER_SKIP_PREVIOUS
        if (prev < p.mediaItemCount) {
          serviceBinder?.engine?.seekTo(prev, 0L)
        }
        rescheduleLookahead()
        recomputeCapabilities()
      }
      QueueSkipArithmetic.SkipToPreviousAction.RestartCurrent -> {
        // Restart the current track from 0 — a seek, not a track change.
        // A same-index seek emits no onMediaItemTransition, so no
        // onTrackChange; leave currentTrackIndex + pendingTrackChangeReason
        // untouched (stashing a reason no transition consumes would leak
        // into the next real skip).
        if (currentTrackIndex in 0 until p.mediaItemCount) {
          reportedFailureIndices.remove(currentTrackIndex)
          serviceBinder?.engine?.seekTo(currentTrackIndex, 0L)
        }
      }
      QueueSkipArithmetic.SkipToPreviousAction.NoOp -> return
    }
  }

  /**
   * Next-track index. Mirrors iOS `TrackPlayer.computeNextIndex`:
   * a thin adapter that translates the Nitrogen [RepeatMode] enum
   * into [QueueSkipRepeatMode] and delegates to [QueueSkipArithmetic].
   * `internal` so the test harness can exercise the adapter and
   * `repeatModeState`-read together.
   */
  @VisibleForTesting
  internal fun computeNextIndex(): Int? =
    QueueSkipArithmetic.computeNext(
      trackCount = tracks.size,
      currentIndex = currentTrackIndex,
      repeatMode = repeatModeAsSkipMode()
    )

  private fun repeatModeAsSkipMode(): QueueSkipRepeatMode = when (repeatModeState) {
    RepeatMode.OFF -> QueueSkipRepeatMode.OFF
    RepeatMode.TRACK -> QueueSkipRepeatMode.TRACK
    RepeatMode.QUEUE -> QueueSkipRepeatMode.QUEUE
  }

  /**
   * Whether skip-to-previous restarts the current track past the threshold
   * (`PlayerConfig.skipToPreviousBehavior == RESTART_OR_PREVIOUS`). Read live
   * from the current config at each skip + capability recompute.
   */
  private fun skipRestartEnabled(): Boolean =
    config.skipToPreviousBehavior == SkipToPreviousBehavior.RESTART_OR_PREVIOUS

  /**
   * Recompute [cachedSkipCapability] from
   * authoritative state and fire [onSkipCapabilityChange] if either
   * flipped.
   *
   * **Threading invariant:** must be called on main. Reads `tracks`
   * / `currentTrackIndex` / `repeatModeState`, all of which mutate
   * only on main, so the read is race-free; the listener fire
   * iterates a snapshot under [ListenerRegistry]'s internal lock
   * so subscriber add/remove during the fire round is safe.
   *
   * **Dedup:** the listener fires only when the (prev, next) tuple
   * actually changes. Hook points: setQueueInternal,
   * addToQueueInternal, removeFromQueueInternal, moveInQueueInternal,
   * clearQueueInternal, skipToIndexInternal / skipToNextInternal /
   * skipToPreviousInternal (all index writes), setRepeatModeInternal,
   * shuffleQueueInternal, handleMediaItemTransition (covers Media3
   * REASON_AUTO + REASON_REPEAT auto-advance), the crossfade
   * fade-start / cancel index writes, emitRemoteTrackChange and
   * resumeLocalAfterCast (cast), destroyInternal.
   */
  @MainThread
  internal fun recomputeCapabilities() {
    val mode = repeatModeAsSkipMode()
    val count = tracks.size
    val cur = currentTrackIndex
    val newPrev = QueueSkipArithmetic.canSkipPrevious(count, cur, mode, skipRestartEnabled())
    val newNext = QueueSkipArithmetic.canSkipNext(count, cur, mode)
    val current = cachedSkipCapability
    val capChanged =
      newPrev != current.canSkipPrevious || newNext != current.canSkipNext
    if (!capChanged) return
    val snapshot = SkipCapability(
      canSkipPrevious = newPrev, canSkipNext = newNext)
    cachedSkipCapability = snapshot
    skipCapabilityListeners.forEach { it(snapshot) }
    // Push the new capability into MediaSession.setAvailableCommands for
    // every connected controller so external surfaces (lock-screen,
    // Bluetooth AVRCP, Android Auto, Wear) grey out skip buttons that the
    // queue boundary makes invalid.
    serviceBinder?.refreshAvailableCommands()
  }

  /**
   * Pick the appropriate wake-mode for the queue's URI mix.
   * `WAKE_MODE_NETWORK` (CPU + WiFi) when any track is remote
   * (http/https); `WAKE_MODE_LOCAL` (CPU only) when every track is
   * local (file:// / content:// / asset:// / RTSP-or-other-non-http
   * which the cache also doesn't consume). Empty queues default to
   * the conservative `WAKE_MODE_NETWORK` since we don't know what
   * the next setQueue will land.
   *
   * Delegates the scheme check to [UrlSchemes.isRemote] so the
   * classification stays consistent with [LookaheadCacheWriter].
   */
  private fun pickWakeMode(queue: List<TrackItem>): Int {
    if (queue.isEmpty()) return C.WAKE_MODE_NETWORK
    val anyRemote = queue.any { UrlSchemes.isRemote(it.url) }
    return if (anyRemote) C.WAKE_MODE_NETWORK else C.WAKE_MODE_LOCAL
  }

  // --- Playback configuration
  //
  // Lightweight property writers — each hops to main, applies the
  // change, resolves. Matches iOS `TrackPlayer.swift` "Playback
  // configuration" block.

  override fun setPlaybackMode(mode: PlaybackMode): Promise<Unit> =
    Promise.async {
      onMain {
        val (newState, effect) = playbackModeStateMachine.setRequested(
          state = playbackModeState,
          requested = mode,
        )
        playbackModeState = newState
        when (effect) {
          is PlaybackModeStateMachine.TransitionEffect.NoOp -> Unit
          is PlaybackModeStateMachine.TransitionEffect.ImmediateSwap -> {
            applyEngineSwapForMode(newState.active)
          }
        }
      }
    }

  override fun getPlaybackMode(): PlaybackMode = playbackModeState.active

  /**
   * Build a fresh engine of the requested kind and route the swap
   * through [PlaybackService.swapEngine] so service-level wiring
   * (audio-focus listener, `MediaSession.setPlayer`, Equalizer
   * multi-session re-attach) happens atomically. Caller has
   * already updated [playbackModeState] before invoking.
   *
   * Resume position is captured from the active engine BEFORE
   * the swap (the swap releases the old engine — reading
   * `newEngine.currentPositionMs` after would always return 0).
   *
   * Queue re-install goes through `MediaItemBuilder.buildMediaItems`
   * so HTTP headers, user-agent, MediaMetadata (title / artist /
   * album / artwork), and MIME hints survive the swap — the same
   * builder every other queue path uses. The new engine receives
   * a fresh `MediaSource.Factory` built via
   * `MediaItemBuilder.buildMediaSourceFactory` so per-config
   * HTTP headers + the lookahead cache wiring continue to apply
   * post-swap.
   */
  private fun applyEngineSwapForMode(active: PlaybackMode) {
    val service = serviceBinder?.service ?: return
    val activeEngine = serviceBinder?.engine
    // Capture pre-swap state — must read BEFORE constructing /
    // installing the new engine because `swapEngine` releases the
    // old one. The wasPlaying capture is what carries playback
    // continuity across the immediate engine swap.
    val resumeMs = (activeEngine?.currentPositionMs ?: 0L).coerceAtLeast(0L)
    val resumeIndex = currentTrackIndex
    val wasPlaying = activeEngine?.isPlaying ?: false
    // Per-leg fresh factory for CrossfadeEngine (sharing factories
    // across two ExoPlayer instances is a documented Media3
    // anti-pattern); GaplessEngine needs only one.
    val capturedConfig = config
    val capturedCache = lookaheadCache
    // When an AirPlay (push-PCM) session is active, stream through the
    // dedicated `AirPlayEngine` — a standalone engine bound directly to the
    // session's sink, with no forced float output and no audio processors, so
    // the receiver gets clean 16-bit PCM. It is completely separate from the
    // gapless / crossfade engines. The AirPlay branch wins regardless of the
    // user's requested mode (crossfade is unavailable while AirPlay is active —
    // the push wire protocol has no sender-side cross-stream mixing). On
    // disconnect `activeSession()` is null, so the swap falls through to the
    // user's requested mode (gapless or crossfade re-instates).
    val airplaySink =
      (com.margelo.nitro.queueplayer.cast.CastSinkRouter.activeSession()
        as? com.margelo.nitro.queueplayer.cast.CastSession.PcmStream)?.audioSink()

    val newEngine: PlaybackEngine = when {
      airplaySink != null -> AirPlayEngine(
        context = service,
        airplaySink = airplaySink,
        mediaSourceFactory = MediaItemBuilder.buildMediaSourceFactory(
          context = service,
          config = capturedConfig,
          lookaheadCache = capturedCache,
        ),
        audioContentType = config.audioContentType,
      )
      active.kind == PlaybackModeKind.CROSSFADE -> {
        // PlaybackModeStateMachine.requireValid guarantees this is
        // non-null + within [1000, 12000] when kind == CROSSFADE.
        // Fail loud rather than silently degrading to 0 (which
        // would route through CrossfadeEngine's hard-cut path +
        // skip onCrossfadeBegin entirely).
        val crossfadeMs = active.crossfadeDurationMs
          ?: throw IllegalStateException(
            "PlaybackMode(crossfade) missing crossfadeDurationMs at engine construction"
          )
        CrossfadeEngine(
          context = service,
          mediaSourceFactoryProvider = {
            MediaItemBuilder.buildMediaSourceFactory(
              context = service,
              config = capturedConfig,
              lookaheadCache = capturedCache,
            )
          },
          audioContentType = config.audioContentType,
          crossfadeDurationMs = crossfadeMs.toLong(),
        )
      }
      else -> GaplessEngine(
        context = service,
        mediaSourceFactory = MediaItemBuilder.buildMediaSourceFactory(
          context = service,
          config = capturedConfig,
          lookaheadCache = capturedCache,
        ),
        audioContentType = config.audioContentType,
      )
    }
    service.swapEngine(newEngine)
    // No explicit `player` refresh needed — `player` is a computed
    // accessor that reads through `serviceBinder.engine.mediaSessionPlayer`
    // every call, so the next transport call resolves against the
    // new engine. This also covers CrossfadeEngine internal
    // role-swaps where leadingPlayer flips between playerA + playerB
    // at every fade boundary.
    // Re-install the canonical queue model on the new engine so
    // playback continues from the current track / position. The
    // post-swap engine starts empty by construction.
    if (resumeIndex >= 0 && resumeIndex < tracks.count()) {
      val media = MediaItemBuilder.buildMediaItems(service, tracks, lookaheadCache, config.placeholderArtworkUri)
      newEngine.setMediaItems(
        items = media,
        startIndex = resumeIndex,
        startPositionMs = resumeMs,
      )
      // Honour pre-swap playing state so a mid-play setPlaybackMode
      // keeps playing on the new engine. Without this, every
      // immediate swap silently pauses the user.
      if (wasPlaying) {
        newEngine.play()
      }
    }

    // Re-apply canonical config — a freshly-built engine starts at
    // defaults (repeat off, volume 1, speed 1), so without this a
    // gapless↔crossfade swap silently drops the user's settings.
    newEngine.setRepeatMode(repeatModeState)
    newEngine.setVolume(volumeState)
    newEngine.setPlaybackSpeed(playbackSpeedState)
    newEngine.setPitchCorrectionMode(pitchCorrectionModeState)
    installSuppliedReplayGainResolver(newEngine)
    // Drive the AirPlay receiver-display metadata sync. When the new engine is
    // the dedicated AirPlay engine, hand the now-active session this engine's
    // ExoPlayer — registered in AirPlayEngine.init, so CastSinkRouter.player()
    // returns it at this point — plus the main-dispatched scope. Activated
    // after the queue install so the first metadata push reads a populated
    // player, not a blank one. Deactivation is driven by AirPlayEngine.release()
    // when the engine is swapped out / released.
    if (newEngine is AirPlayEngine) {
      val session = com.margelo.nitro.queueplayer.cast.CastSinkRouter.activeSession()
      val player = com.margelo.nitro.queueplayer.cast.CastSinkRouter.player()
      if (session is com.margelo.nitro.queueplayer.cast.MetadataAwareSession && player != null) {
        session.onActivated(player, metadataSyncScope)
      }
    }
  }

  override fun setPlaybackSpeed(rate: Double): Promise<Unit> =
    Promise.async {
      onMain {
        // Mirror `seekToInternal`'s non-finite guard. NaN →
        // undefined ExoPlayer audio behaviour at the AudioSink;
        // ±Infinity → IllegalArgumentException from
        // `PlaybackParameters` constructor.
        if (!rate.isFinite()) return@onMain
        setPlaybackSpeedInternal(rate)
      }
    }

  /**
   * Main-thread body for [setPlaybackSpeed]. The engine applies the
   * active pitch-correction mode's pitch alongside the speed (voice/music
   * preserve pitch; none lets it follow rate). Exposed `@VisibleForTesting`;
   * centralizes the cast + speed-application logic rather than routing
   * straight through `ExoPlayer.playbackParameters`.
   *
   * Diverges from iOS: on iOS `player.rate = Float(rate)` implicitly
   * starts playback when the player was paused. Android's
   * `PlaybackParameters` changes playback speed without touching
   * `playWhenReady`, matching Media3 consumer expectations. Callers
   * that want to resume playback must call play() separately.
   */
  @VisibleForTesting
  internal fun setPlaybackSpeedInternal(rate: Double) {
    // Clamp to a 0.25 floor: rate=0 produces undefined audio behaviour
    // at the AudioSink (PlaybackParameters(0f, pitch) is unspecified).
    // Cross-platform contract documented in TrackPlayer.nitro.ts.
    val safeRate = maxOf(0.25f, rate.toFloat())
    playbackSpeedState = safeRate
    if (player == null) return
    val engine = serviceBinder?.engine ?: return
    // The engine applies the active pitch-correction mode's pitch
    // alongside the speed (voice/music preserve pitch; none lets it
    // follow rate). At unity rate the resulting params are (1,1).
    engine.setPlaybackSpeed(safeRate)
  }

  override fun setPitchCorrectionMode(mode: PitchCorrectionMode): Promise<Unit> =
    Promise.async {
      onMain {
        pitchCorrectionModeState = mode
        serviceBinder?.engine?.setPitchCorrectionMode(mode)
      }
    }

  override fun setVolume(volume: Double): Promise<Unit> =
    Promise.async {
      onMain {
        // Mirror `seekToInternal`'s non-finite guard. NaN →
        // undefined audio output; ±Infinity → ditto.
        if (!volume.isFinite()) return@onMain
        if (com.margelo.nitro.queueplayer.cast.CastTransportRouter.routeSetVolume(volume.toFloat())) return@onMain
        setVolumeInternal(volume)
      }
    }

  /**
   * Main-thread body for [setVolume]. ExoPlayer accepts 0.0f..1.0f;
   * values outside the range are silently ignored at the audio sink.
   * JS callers are expected to clamp — matches iOS which passes
   * `Float(volume)` straight through.
   */
  @VisibleForTesting
  internal fun setVolumeInternal(volume: Double) {
    volumeState = volume.toFloat()
    if (player == null) return
    serviceBinder?.engine?.setVolume(volume.toFloat())
  }

  // --- Sleep timer ---

  override fun setSleepTimer(seconds: Double): Promise<Unit> =
    Promise.async { onMain { setSleepTimerInternal(seconds) } }

  override fun setSleepTimerToTrackEnd(): Promise<Unit> =
    Promise.async { onMain { armSleepTimerToTrackEndInternal() } }

  override fun clearSleepTimer(): Promise<Unit> =
    Promise.async { onMain { clearSleepTimerInternal() } }

  override fun getSleepTimer(): SleepTimerState = syncMain { sleepTimerSnapshot() }

  override fun onSleepTimerChange(callback: (state: SleepTimerState) -> Unit): () -> Unit {
    val id = sleepTimerListeners.add(callback)
    mainHandler.post {
      if (sleepTimerListeners.contains(id)) callback(sleepTimerSnapshot())
    }
    return { sleepTimerListeners.remove(id) }
  }

  override fun onBufferStateChange(callback: (state: BufferState) -> Unit): () -> Unit {
    val id = bufferStateListeners.add(callback)
    // Buffer state is delivered by the engine state-change delegate, not the
    // progress tick — just fire once on subscribe with the current state so a
    // UI renders immediately.
    mainHandler.post {
      if (bufferStateListeners.contains(id)) callback(currentBufferState)
    }
    return { bufferStateListeners.remove(id) }
  }

  override fun onFullyBufferedChange(callback: (fullyBuffered: Boolean) -> Unit): () -> Unit {
    val id = fullyBufferedListeners.add(callback)
    // Keep the progress tick alive (bufferedPosition grows continuously toward
    // duration) + fire once on subscribe with the current value.
    onMainSync { startProgressTickIfNeeded() }
    mainHandler.post {
      if (fullyBufferedListeners.contains(id)) callback(currentFullyBuffered)
    }
    return {
      fullyBufferedListeners.remove(id)
      onMainSync { maybeStopProgressTick() }
    }
  }

  /** Arm a wall-clock duration timer; a non-positive value clears any timer. */
  @VisibleForTesting
  internal fun setSleepTimerInternal(seconds: Double) {
    if (!seconds.isFinite() || seconds <= 0.0) {
      clearSleepTimerInternal()
      return
    }
    restoreSleepTimerVolume()
    sleepTimerCore.armDuration(seconds, System.currentTimeMillis())
    startSleepTimerTick()
    emitSleepTimerChanged()
  }

  @VisibleForTesting
  internal fun armSleepTimerToTrackEndInternal() {
    restoreSleepTimerVolume()
    sleepTimerCore.armEndOfTrack()
    startSleepTimerTick()
    emitSleepTimerChanged()
  }

  @VisibleForTesting
  internal fun clearSleepTimerInternal() {
    val wasActive = sleepTimerCore.isActive
    restoreSleepTimerVolume()
    sleepTimerCore.clear()
    stopSleepTimerTick()
    if (wasActive) emitSleepTimerChanged()
  }

  private fun sleepTimerSnapshot(): SleepTimerState {
    val now = System.currentTimeMillis()
    return SleepTimerState(
      active = sleepTimerCore.isActive,
      endsAtEpochMs = sleepTimerCore.deadlineEpochMs?.toDouble(),
      remainingSeconds = sleepTimerCore.remainingSeconds(now)?.toDouble(),
      endOfTrack = sleepTimerCore.isEndOfTrack,
    )
  }

  private fun emitSleepTimerChanged() {
    val snapshot = sleepTimerSnapshot()
    sleepTimerListeners.forEach { it(snapshot) }
  }

  private fun startSleepTimerTick() {
    if (sleepTimerRunnable != null) return
    val runnable = object : Runnable {
      override fun run() {
        if (sleepTimerRunnable == null) return
        onSleepTimerTick()
        // onSleepTimerTick nulls the runnable when the timer fires/clears; only
        // reschedule if we're still the active tick.
        if (sleepTimerRunnable === this) {
          mainHandler.postDelayed(this, SLEEP_TIMER_TICK_INTERVAL_MS)
        }
      }
    }
    sleepTimerRunnable = runnable
    mainHandler.postDelayed(runnable, SLEEP_TIMER_TICK_INTERVAL_MS)
  }

  private fun stopSleepTimerTick() {
    sleepTimerRunnable?.let { mainHandler.removeCallbacks(it) }
    sleepTimerRunnable = null
  }

  private fun onSleepTimerTick() {
    // While casting, the local engine is intentionally frozen (its position
    // never advances), so feed the state machine the receiver's position /
    // duration instead — otherwise the end-of-track tail rule never trips.
    val remote = com.margelo.nitro.queueplayer.cast.PlaybackStateRouter.activeRemote()
    val durationSec: Double
    val positionSec: Double
    if (remote != null) {
      val durMs = remote.durationMs.value
      durationSec = if (durMs <= 0L) 0.0 else durMs / 1000.0
      positionSec = maxOf(0L, remote.positionMs.value) / 1000.0
    } else {
      val durationMs = serviceBinder?.engine?.currentDurationMs ?: C.TIME_UNSET
      durationSec = if (durationMs == C.TIME_UNSET || durationMs <= 0L) 0.0 else durationMs / 1000.0
      positionSec = maxOf(0L, serviceBinder?.engine?.currentPositionMs ?: 0L) / 1000.0
    }
    val result = sleepTimerCore.tick(System.currentTimeMillis(), durationSec, positionSec)
    applySleepTimerResult(result)
  }

  /**
   * Apply a [SleepTimerCore.Tick] decision — fade, state emit, boundary pause.
   * Shared by the polling tick and the real track-end signal ([fireEndOfTrackSleepTimer]).
   */
  private fun applySleepTimerResult(result: SleepTimerCore.Tick) {
    applySleepTimerFade(result.fadeFraction)
    if (result.stateChanged) emitSleepTimerChanged()
    if (result.pauseNow) {
      // Route the boundary pause to the receiver when casting (it emits its own
      // paused state); only the local-engine pause needs the sleep-timer flag —
      // `onStateMaybeChanged` would otherwise label the paused emit 'user'.
      if (!com.margelo.nitro.queueplayer.cast.CastTransportRouter.routePause()) {
        pendingSleepTimerPause = true
        serviceBinder?.engine?.pause()
      }
    }
    if (!sleepTimerCore.isActive) stopSleepTimerTick()
  }

  /**
   * Fire the end-of-track sleep timer at a real auto-transition boundary (the
   * current track finished and Media3 advanced). Called from the engine's
   * track-transition callback so the pause lands at the boundary and fires even
   * when the track duration was never known — no-op unless end-of-track is armed.
   */
  private fun fireEndOfTrackSleepTimer() {
    val result = sleepTimerCore.fireAtTrackEnd()
    if (result.pauseNow) applySleepTimerResult(result)
  }

  private fun applySleepTimerFade(fraction: Double) {
    // Fade is local-only: the receiver's volume is user/system-owned and
    // `volumeState` tracks the local level, so ramping it on the receiver would
    // clobber the cast volume. When casting the boundary pause still routes to
    // the receiver; the pre-pause fade is skipped. Undo any in-progress local
    // fade first so a cast session appearing mid-fade can't strand the local
    // engine at a ramped-down volume for a later cast→local return.
    if (com.margelo.nitro.queueplayer.cast.PlaybackStateRouter.activeRemote() != null) {
      restoreSleepTimerVolume()
      return
    }
    if (fraction < 1.0) {
      serviceBinder?.engine?.setVolume((volumeState * fraction).toFloat())
      sleepTimerFading = true
    } else if (sleepTimerFading) {
      serviceBinder?.engine?.setVolume(volumeState)
      sleepTimerFading = false
    }
  }

  private fun restoreSleepTimerVolume() {
    if (sleepTimerFading) {
      serviceBinder?.engine?.setVolume(volumeState)
      sleepTimerFading = false
    }
  }

  override fun setRepeatMode(mode: RepeatMode): Promise<Unit> =
    Promise.async { onMain { setRepeatModeInternal(mode) } }

  override fun setRemoteControls(options: RemoteControlOptions): Promise<Unit> =
    Promise.async { onMain { setRemoteControlsInternal(options) } }

  /**
   * Store the remote-control config and push it to the playback service, which
   * swaps the notification button layout (track vs interval) live and points
   * the forwarding player at the new jump intervals. No-op on the service side
   * until a session exists; the config is re-applied on session build.
   */
  @VisibleForTesting
  internal fun setRemoteControlsInternal(options: RemoteControlOptions) {
    // Guard nonsensical negatives (a negative forward interval would seek
    // backward); non-positive is clamped to 0.
    remoteSkipMode = options.skipMode
    remoteForwardSeconds = options.forwardJumpInterval.coerceAtLeast(0.0)
    remoteBackwardSeconds = options.backwardJumpInterval.coerceAtLeast(0.0)
    serviceBinder?.setRemoteControls(
      options.skipMode,
      (remoteForwardSeconds * 1000).toLong(),
      (remoteBackwardSeconds * 1000).toLong(),
    )
  }

  override fun getRemoteControls(): RemoteControlOptions = syncMain {
    RemoteControlOptions(remoteSkipMode, remoteForwardSeconds, remoteBackwardSeconds)
  }

  override fun getBufferState(): BufferState = currentBufferState

  override fun getIsSeekable(): Boolean = syncMain { player?.isCurrentMediaItemSeekable ?: true }

  override fun isFullyBuffered(): Boolean = currentFullyBuffered

  /**
   * Store the repeat mode on [repeatModeState] (consumed by
   * [QueueSkipArithmetic] when the user presses Next/Previous)
   * AND push it onto ExoPlayer's native repeat mode so that
   * natural end-of-queue advance honours the same contract.
   *
   * Without the native mirror, `RepeatMode.QUEUE` at the last
   * track's natural end would transition ExoPlayer into
   * `STATE_ENDED` instead of wrapping to track 0 — skipToNext
   * would still wrap correctly via the arithmetic, but the
   * auto-advance path (gapless) would break at the boundary.
   */
  @VisibleForTesting
  internal fun setRepeatModeInternal(mode: RepeatMode) {
    repeatModeState = mode
    if (player == null) return
    serviceBinder?.engine?.setRepeatMode(mode)
    // Both canSkipNext and canSkipPrevious depend on repeat mode
    // (e.g. at idx 0 / last index, OFF↔QUEUE flips the boolean
    // because QUEUE wraps). Recompute is cheap; dedup keeps the
    // listener silent when the (prev, next) tuple is unchanged.
    recomputeCapabilities()
  }

  /// Destructive shuffle. Mutates `tracks` in-place via
  /// Fisher-Yates (Kotlin stdlib `shuffled()`), resets
  /// `currentTrackIndex` to 0, and resumes playback on the new
  /// tracks[0] if the player was playing pre-shuffle. Returns the
  /// post-shuffle snapshot for the consumer app to immediately
  /// reflect the new ordering in its UI.
  ///
  /// Empty queue → returns `ShuffleResult(tracks=[], currentIndex=-1, currentTrack=null)`
  /// without mutating state.
  override fun shuffleQueue(): Promise<ShuffleResult> =
    Promise.async { onMain { shuffleQueueInternal() } }

  @VisibleForTesting
  internal fun shuffleQueueInternal(): ShuffleResult {
    val p = player
    val ctx = boundContext
    if (tracks.isEmpty() || p == null || ctx == null) {
      // Empty queue OR no player attached (configure() not yet called).
      // Return empty snapshot WITHOUT mutating tracks — silently
      // shuffling an in-memory queue while the player can't actually
      // start would lie to the consumer.
      return ShuffleResult(
        tracks = emptyArray(),
        currentIndex = -1.0,
        currentTrack = null,
      )
    }

    // Capture playing state BEFORE the mutation so we know whether to
    // resume after the rebuild.
    val wasPlaying = p.isPlaying

    // Shuffle positions rather than tracks: the permutation is what
    // onQueueChange reports, and it cannot be recovered from the reordered
    // list afterwards. Kotlin stdlib `shuffled()` is Fisher-Yates (documented).
    val order = tracks.indices.shuffled()
    val newTracks = order.map { tracks[it] }

    this.tracks = newTracks
    this.currentTrackIndex = 0
    pendingTrackChangeReason = TrackChangeReason.QUEUE_REPLACED
    // Destructive shuffle replaces every position; reset retry budget.
    cancelPendingRetry()
    val attempts = effectiveAutoRetries()
    retryAttemptsRemaining.clear()
    for (i in newTracks.indices) retryAttemptsRemaining[i] = attempts
    // The permutation decides what lands at position 0, so the track the queue
    // stopped on is not the one the index now names. The stop is dropped with
    // the retry budget rather than carried onto whichever track was moved to
    // the front.
    reportedFailureIndices.clear()

    val items = MediaItemBuilder.buildMediaItems(ctx, newTracks, lookaheadCache, config.placeholderArtworkUri)
    serviceBinder?.engine?.setMediaItems(items, startIndex = 0, startPositionMs = 0L)
    p.prepare()
    serviceBinder?.engine?.setWakeMode(pickWakeMode(newTracks))

    // Resume playback if the user was playing before the shuffle.
    // Stays paused otherwise — consumer can call play() if they
    // want to start from the new track 0.
    if (wasPlaying) serviceBinder?.engine?.play()

    rescheduleLookahead()
    recomputeCapabilities()
    // Skip a single-track shuffle — the order can't change.
    if (newTracks.size > 1) emitQueueChange(QueueChangeReason.SHUFFLE, order = order)

    return ShuffleResult(
      tracks = newTracks.toTypedArray(),
      currentIndex = 0.0,
      currentTrack = newTracks[0],
    )
  }

  // --- State snapshots: player-local readers
  //
  // Media3 1.9.3 requires ExoPlayer reads on the application
  // thread: `ExoPlayerImpl.verifyApplicationThread()` asserts on
  // `playbackState`, `isPlaying`, `volume`, `playbackParameters`,
  // etc. Nitro dispatches synchronous methods on the JS thread,
  // so the non-Promise readers below funnel through `syncMain`
  // to hop to main and block until the snapshot completes. The
  // blocking wait is acceptable because: (a) each read is a single
  // property access, (b) once a future listener wires a listener-backed state
  // cache, these readers switch to the cached value and this hop
  // goes away.

  /**
   * Snapshot of the active playback source's state mapped onto the public
   * [PlayerState] enum — the cast receiver while a session owns playback,
   * otherwise the local ExoPlayer. Safe to call from any thread: the receiver
   * read is lock-free, and the local read hops to main via [syncMain] so
   * Media3's thread-assertion does not fire.
   */
  override fun getState(): PlayerState {
    // The receiver owns playback during a cast session — the local player is
    // parked and paused for its duration, so its derived state describes
    // nothing the user can hear. Until the receiver has reported (session
    // still activating, holding its initial IDLE) the local read is the
    // better answer; after that the receiver is authoritative, including a
    // later IDLE, which means it genuinely has nothing loaded.
    val remote = com.margelo.nitro.queueplayer.cast.PlaybackStateRouter.activeRemote()
    if (remote != null && remote.hasReportedState) {
      return com.margelo.nitro.queueplayer.cast.mapRemotePlaybackState(remote.state.value)
    }
    return syncMain { getStateInternal() }
  }

  @VisibleForTesting
  internal fun getStateInternal(): PlayerState {
    val p = player ?: return PlayerState.NONE
    return TransportStateTranslator.translate(
      playbackState = p.playbackState,
      isPlaying = p.isPlaying,
      playWhenReady = p.playWhenReady,
      playbackSuppressionReason = p.playbackSuppressionReason,
      hasTracks = tracks.isNotEmpty(),
      currentTrackIndex = currentTrackIndex
    )
  }

  // No `syncMain` hop on the next five getters: each reads a single
  // `@Volatile` field that is only written on main, so the JVM
  // memory model gives the cross-thread happens-before edge for
  // free. iOS hops through `onPlayerQueueSync` instead: its state is
  // confined to `playerQueue`, and Swift has no `@Volatile`-equivalent
  // storage class.
  override fun getCurrentTrackIndex(): Double = currentTrackIndex.toDouble()

  override fun getCurrentTrackSource(): TrackSource? = currentTrackSource

  override fun getCanSkipNext(): Boolean = cachedSkipCapability.canSkipNext
  override fun getCanSkipPrevious(): Boolean = cachedSkipCapability.canSkipPrevious
  override fun getSkipCapability(): SkipCapability = cachedSkipCapability

  override fun getPlaybackSpeed(): Double =
    syncMain { playbackSpeedState.toDouble() }

  override fun getPitchCorrectionMode(): PitchCorrectionMode =
    syncMain { pitchCorrectionModeState }

  override fun getVolume(): Double =
    syncMain { volumeState.toDouble() }

  override fun getProgress(): PlayerProgress = syncMain { readProgress() }

  // --- Events — public registration surface
  //
  // Each event stores a single callback and returns an unsubscribe
  // closure that clears it. Registration + clear funnel through
  // `onMainSync` so a JS-side register/clear race against a listener
  // fire (also on main) cannot corrupt the optional slot.
  //
  // onProgress additionally (re)starts the periodic tick Runnable on
  // register + stops it on the final unsubscribe, so the timer only
  // runs when a subscriber exists.

  override fun onStateChange(
    callback: (state: PlayerState, reason: StateChangeReason) -> Unit
  ): () -> Unit {
    val id = stateChangeListeners.add(callback)
    return { stateChangeListeners.remove(id) }
  }

  override fun onTrackChange(
    callback: (track: TrackItem?, index: Double, reason: TrackChangeReason, transitionGapMs: Double?, previousTrack: TrackItem?, wasPlaying: Boolean) -> Unit
  ): () -> Unit {
    val id = trackChangeListeners.add(callback)
    return { trackChangeListeners.remove(id) }
  }

  override fun onQueueChange(
    callback: (delta: QueueChangeDelta, currentIndex: Double, reason: QueueChangeReason) -> Unit
  ): () -> Unit {
    val id = queueChangeListeners.add(callback)
    return { queueChangeListeners.remove(id) }
  }

  override fun onTimedMetadata(callback: (metadata: TimedMetadata) -> Unit): () -> Unit {
    val id = timedMetadataListeners.add(callback)
    return { timedMetadataListeners.remove(id) }
  }

  /**
   * Emit a queue change as a description of what happened rather than a copy of
   * the queue. Callers pass only the fields their mutation defines; the rest
   * stay empty, and `revision` lets a subscriber notice a dropped event.
   * Main-thread only — every queue mutation commits on main, so [tracks] /
   * [currentTrackIndex] are settled here. Callers gate this on an actual
   * content/order change so a no-op mutation never emits.
   */
  @MainThread
  private fun emitQueueChange(
    reason: QueueChangeReason,
    inserted: Array<TrackItem> = emptyArray(),
    insertedAt: Int = -1,
    removed: List<Int> = emptyList(),
    movedFrom: Int = -1,
    movedTo: Int = -1,
    order: List<Int> = emptyList()
  ) {
    queueRevision += 1
    val delta = QueueChangeDelta(
      revision = queueRevision.toDouble(),
      length = tracks.size.toDouble(),
      inserted = inserted,
      insertedAt = insertedAt.toDouble(),
      removed = removed.map { it.toDouble() }.toDoubleArray(),
      movedFrom = movedFrom.toDouble(),
      movedTo = movedTo.toDouble(),
      order = order.map { it.toDouble() }.toDoubleArray()
    )
    val index = currentTrackIndex.toDouble()
    queueChangeListeners.forEach { it(delta, index, reason) }
  }

  /** Increments once per queue-change emit; a gap tells a subscriber it missed one. */
  private var queueRevision: Long = 0

  override fun onProgress(
    callback: (progress: PlayerProgress) -> Unit
  ): () -> Unit {
    val id = progressListeners.add(callback)
    onMainSync { startProgressTickIfNeeded() }
    return {
      progressListeners.remove(id)
      // The tick also serves milestone + fully-buffered subscribers — stop only
      // when nothing else needs it.
      onMainSync { maybeStopProgressTick() }
    }
  }

  override fun onPlaybackMilestone(
    callback: (milestone: Double, trackIndex: Double) -> Unit
  ): () -> Unit {
    val id = milestoneListeners.add(callback)
    onMainSync { startProgressTickIfNeeded() }
    return {
      milestoneListeners.remove(id)
      onMainSync { maybeStopProgressTick() }
    }
  }

  override fun onError(callback: (error: PlaybackError) -> Unit): () -> Unit {
    val id = errorListeners.add(callback)
    return { errorListeners.remove(id) }
  }

  override fun onQueueEnd(callback: () -> Unit): () -> Unit {
    val id = queueEndListeners.add(callback)
    return { queueEndListeners.remove(id) }
  }

  /**
   * Subscribe to skip-capability changes. Auto-fires the callback
   * once on registration with the current cached values (mirrors
   * Media3 `Player.Listener.onAvailableCommandsChanged`) so new
   * subscribers don't have to seed via the getters first. The
   * auto-fire hops to main so the cache read happens on the same
   * thread that mutates it; subsequent fires happen synchronously
   * from [recomputeCapabilities] (which is itself main-bound).
   *
   * **Dispose race guard:** the deferred main fire checks the
   * subscriber is still registered before invoking the callback.
   * Strict-mode subscribe → dispose → subscribe sequences fire
   * the cleanup before the deferred fire runs; without the guard,
   * the auto-fire would call into an already-unmounted React
   * component's `setSnapshot`.
   */
  override fun onSkipCapabilityChange(
    callback: (capability: SkipCapability) -> Unit
  ): () -> Unit {
    val id = skipCapabilityListeners.add(callback)
    mainHandler.post {
      if (skipCapabilityListeners.contains(id)) {
        callback(cachedSkipCapability)
      }
    }
    return { skipCapabilityListeners.remove(id) }
  }

  // --- Automotive / voice handler registration ---

  /**
   * Atomically replace the cached `BrowseSnapshot` on the bound
   * [PlaybackService] and notify every connected `MediaController`
   * that the affected parent IDs changed so they re-pull. Push-
   * semantics — most-recent call wins. No-op when the service
   * isn't bound (the snapshot will land on the next consumer call
   * once binding completes).
   */
  override fun setBrowseSnapshot(snapshot: BrowseSnapshot) {
    val binder = serviceBinder
    if (binder == null) {
      // Service not bound yet (cold-start before configure binds) or
      // mid-rebirth (disconnect window). Stash the latest snapshot;
      // [drainPendingSnapshot] applies it once the binder is live.
      // Most-recent-wins — the consumer's latest push is what AA
      // sees, even if a stale one was queued.
      pendingSnapshot = snapshot
      return
    }
    val service = binder.service
    val changedParents = service.browseDataProvider.setSnapshot(snapshot)
    // Always include the lib-owned stable root id. Cross-process
    // controllers (Android Auto's gearhead) subscribed under
    // `LIB_ROOT_ID` from the initial `onGetLibraryRoot` call;
    // `notifyChildrenChanged` must fire on THAT id (not the
    // consumer-defined `snapshot.rootId`) for AA to refresh its
    // browse drawer. The consumer's rootId is still honoured by the
    // BrowseDataProvider's section indexing — this fan-out just adds
    // the lib's external-facing root.
    val withLibRoot = changedParents + PlaybackServiceCallback.LIB_ROOT_ID
    binder.handler.post { service.notifyChildrenChangedForParents(withLibRoot) }
  }

  /** Latest snapshot the consumer pushed while the service binder
   *  was null. Drained on the next [onServiceConnected]. Single-slot
   *  (most-recent-wins) — the consumer's latest push is what AA sees
   *  after the rebind, even if a stale one was queued earlier. */
  @Volatile
  private var pendingSnapshot: BrowseSnapshot? = null

  @MainThread
  internal fun drainPendingSnapshot() {
    val snapshot = pendingSnapshot ?: return
    pendingSnapshot = null
    setBrowseSnapshot(snapshot)
  }

  override fun onBrowseRequest(
    callback: (parentId: String) -> Promise<Promise<Array<BrowseItem>>>
  ): () -> Unit = deferredRegister { it.registerOnBrowseRequest(callback) }

  override fun onSearchRequest(
    callback: (query: String) -> Promise<Promise<Array<BrowseItem>>>
  ): () -> Unit = deferredRegister { it.registerOnSearchRequest(callback) }

  override fun onPlayFromSearchRequest(
    callback: (request: MediaSearchRequest) -> Promise<Promise<Array<TrackItem>>>
  ): () -> Unit = deferredRegister { it.registerOnPlayFromSearchRequest(callback) }

  override fun onPlayFromIdRequest(
    callback: (mediaId: String) -> Promise<Promise<Array<TrackItem>>>
  ): () -> Unit = deferredRegister { it.registerOnPlayFromIdRequest(callback) }

  // Donation is iOS-only (Siri INVocabulary). Accepted on Android to
  // keep the cross-platform spec shape consistent; discarded silently.
  // JSDoc in the .nitro.ts spec documents the asymmetry.
  override fun donateVoiceVocabulary(vocabulary: VoiceVocabulary) {}

  override fun onCarConnect(callback: () -> Unit): () -> Unit =
    deferredRegister { it.registerOnCarConnect(callback) }

  override fun onCarDisconnect(callback: () -> Unit): () -> Unit =
    deferredRegister { it.registerOnCarDisconnect(callback) }

  override fun isCarConnected(): Boolean = CarConnectionState.isConnected()

  override fun onServiceReady(
    callback: (reason: ServiceReadyReason) -> Unit
  ): () -> Unit = deferredRegister { it.registerOnServiceReady(callback) }

  /** Registrations the consumer made BEFORE [serviceBinder] became
   *  non-null. Drained in [drainPendingBrowseRegistrations] once the
   *  service binds. Each entry installs itself against the live
   *  registry + stores its disposer in the entry's own slot so
   *  consumer dispose-calls fan out correctly to the active
   *  registration. */
  private val pendingBrowseRegistrations =
    java.util.Collections.synchronizedList(mutableListOf<() -> Unit>())

  /**
   * Wrap a `BrowseCallbackRegistry.registerOnXxx` call so it can be
   * invoked before the service is bound. Pre-bind: the registration
   * is queued; the returned disposer cancels it from the queue (and
   * disposes the live registration too, if the queue has since been
   * drained). Post-bind: registers immediately. Either way the
   * returned disposer matches the consumer's expectation that
   * calling it tears the registration down regardless of timing.
   * `registerPlaybackService` may run before `configure()` resolves, and
   * [serviceBinder] is null again between an OS service kill and the
   * rebind (`onServiceDisconnected`); both cases queue here and are
   * applied by [drainPendingBrowseRegistrations] on the next
   * `onServiceConnected`.
   */
  private fun deferredRegister(
    register: (BrowseCallbackRegistry) -> () -> Unit
  ): () -> Unit {
    serviceBinder?.let {
      return register(it.service.browseCallbacks)
    }
    val liveDisposer = arrayOf<(() -> Unit)?>(null)
    var canceled = false
    val flush = {
      val binder = serviceBinder
      if (!canceled && binder != null) {
        liveDisposer[0] = register(binder.service.browseCallbacks)
      }
    }
    pendingBrowseRegistrations.add(flush)
    return {
      canceled = true
      pendingBrowseRegistrations.remove(flush)
      liveDisposer[0]?.invoke()
      liveDisposer[0] = null
    }
  }

  /** Apply every pending browse registration against the now-live
   *  service. Called from the service-connection callback. */
  @MainThread
  internal fun drainPendingBrowseRegistrations() {
    val drained: List<() -> Unit>
    synchronized(pendingBrowseRegistrations) {
      drained = pendingBrowseRegistrations.toList()
      pendingBrowseRegistrations.clear()
    }
    drained.forEach { it() }
  }

  // --- Lookahead cache ---

  override fun setLookaheadCache(config: LookaheadCacheConfig): Promise<Unit> =
    Promise.async {
      onMain {
        setLookaheadCacheInternal(config)
      }
    }

  /**
   * Asynchronous because the snapshot counts fully-cached entries, which walks
   * every cache key and takes the cache's monitor per key — contended against
   * the prefetch thread. Resolved off both the JS thread and the app looper;
   * every field it reads is published for that.
   */
  override fun getLookaheadCacheStatus(): Promise<CacheStatus> =
    Promise.async { buildCacheStatusSnapshot() }

  /**
   * Wipe the cache and resolve once it is actually gone.
   *
   * The delete is file IO, so it runs off the app looper; the promise resolves
   * after it, because a consumer that awaits this and then reads
   * `getLookaheadCacheStatus()` must not see the pre-wipe size.
   */
  override fun clearLookaheadCache(): Promise<Unit> =
    Promise.async {
      onMain { clearLookaheadCacheInternal() }?.join()
      onMain { emitCacheStatus() }
    }

  override fun onCacheStatusChange(callback: (status: CacheStatus) -> Unit): () -> Unit {
    val id = cacheStatusListeners.add(callback)
    // Deliver the current status once. Otherwise a settings screen shows its
    // placeholder until the next prefetch completes — which never happens for a
    // fully-cached queue or a consumer running with prefetch off.
    cacheIoScope.launch(cacheStatusDispatcher) {
      val snapshot = buildCacheStatusSnapshot()
      mainHandler.post {
        if (cacheStatusListeners.contains(id)) callback(snapshot)
      }
    }
    return { cacheStatusListeners.remove(id) }
  }

  /**
   * Returns the active item's runtime audio format, or the null
   * variant when nothing is playable / format hasn't loaded yet.
   * Cached by [refreshNowPlayingFormatForActiveItem]; never blocks.
   *
   * An empty / out-of-range queue short-circuits to NULL regardless
   * of the cache — the canonical-state branch below.
   */
  override fun getNowPlayingFormat(): Variant_NullType_NowPlayingFormat {
    // Lib canonical state takes precedence over the cached format.
    // After `clearQueue` / queue-end the underlying ExoPlayer's
    // `currentMediaItem` can briefly retain the last item; without
    // this guard the read returns the prior track's stale codec /
    // bitrate / duration even though `currentTrackIndex == -1` and
    // the JS-facing state shows track == null.
    if (tracks.isEmpty() || currentTrackIndex < 0 || currentTrackIndex >= tracks.size) {
      return Variant_NullType_NowPlayingFormat.create(com.margelo.nitro.core.NullType.NULL)
    }
    val cached = lastEmittedFormat
    return if (cached == null) Variant_NullType_NowPlayingFormat.create(com.margelo.nitro.core.NullType.NULL)
           else Variant_NullType_NowPlayingFormat.create(cached)
  }

  override fun onNowPlayingFormatChange(
    callback: (format: Variant_NullType_NowPlayingFormat?) -> Unit
  ): () -> Unit {
    val id = nowPlayingFormatListeners.add(callback)
    return { nowPlayingFormatListeners.remove(id) }
  }

  // ReplayGain mode — JS-facing surface. Writes through the
  // process-wide [ReplayGainConfig] singleton; the active engine
  // (gapless or crossfade) reads the mode live from
  // `ReplayGainConfig.getMode()` on every metadata-driven recompute,
  // and the recompute call below handles the user-flips-mode-mid-track
  // case where no fresh metadata is going to fire. The cached
  // per-engine `ReplayGainData` re-selects under the new mode and the
  // processor sees the updated linear gain within ~1 audio buffer.
  override fun setReplayGainMode(mode: ReplayGainMode): Promise<Unit> =
    Promise.async {
      ReplayGainConfig.setMode(mode)
      onMain {
        when (val engine = serviceBinder?.engine) {
          is GaplessEngine -> engine.recomputeReplayGainFromConfig()
          is CrossfadeEngine -> engine.recomputeReplayGainFromConfig()
          else -> Unit
        }
        refreshNowPlayingFormatForActiveItem()
      }
    }

  override fun getReplayGainMode(): ReplayGainMode = ReplayGainConfig.getMode()

  /**
   * Hand a freshly-installed engine the reader for consumer-supplied
   * ReplayGain. The engine holds no queue metadata of its own — it
   * plays `MediaItem`s — so the lookup from a queue index back to the
   * consumer's `TrackItem` lives here, where the canonical [tracks]
   * list does.
   *
   * The lambda reads [tracks] on every invocation rather than closing
   * over a snapshot, so a `setQueue` carrying corrected values is
   * picked up by the next recompute. Called wherever an engine is
   * installed — the configure-time rebuild and the playback-mode swap —
   * because a fresh engine starts with no resolver.
   */
  private fun installSuppliedReplayGainResolver(engine: PlaybackEngine?) {
    val resolver: (Int) -> SuppliedReplayGain? = { index ->
      tracks.getOrNull(index)?.let { SuppliedReplayGain.from(it) }
    }
    when (engine) {
      is GaplessEngine -> engine.suppliedReplayGainResolver = resolver
      is CrossfadeEngine -> engine.suppliedReplayGainResolver = resolver
      else -> Unit
    }
  }

  /**
   * Re-resolve the active item's format and emit if it differs from
   * the last cached value. Call sites: [Player.Listener.onMediaItemTransition]
   * (item change → emit null until format loads) and
   * [Player.Listener.onTracksChanged] (format ready → emit real value).
   * Same-MediaItem identity check + value-equality dedup suppress
   * redundant fires from Media3's frequent track-set events.
   */
  @MainThread
  internal fun refreshNowPlayingFormatForActiveItem() {
    val p = player
    if (p == null) {
      if (lastEmittedFormat != null || lastEmittedFormatMediaItem != null) {
        lastEmittedFormat = null
        lastEmittedFormatMediaItem = null
        nowPlayingFormatListeners.forEach {
          it(Variant_NullType_NowPlayingFormat.create(com.margelo.nitro.core.NullType.NULL))
        }
      }
      return
    }
    val currentItem = p.currentMediaItem
    if (currentItem == null) {
      if (lastEmittedFormat != null || lastEmittedFormatMediaItem != null) {
        lastEmittedFormat = null
        lastEmittedFormatMediaItem = null
        nowPlayingFormatListeners.forEach {
          it(Variant_NullType_NowPlayingFormat.create(com.margelo.nitro.core.NullType.NULL))
        }
      }
      return
    }
    val resolved = NowPlayingFormatExtractor.extract(p, serviceBinder?.engine)
    val itemChanged = lastEmittedFormatMediaItem !== currentItem
    if (itemChanged && lastEmittedFormat != null) {
      lastEmittedFormat = null
      lastEmittedFormatMediaItem = null
      nowPlayingFormatListeners.forEach {
        it(Variant_NullType_NowPlayingFormat.create(com.margelo.nitro.core.NullType.NULL))
      }
    }
    if (resolved == null) return
    // Stamp the source identity at the same point we cache the format,
    // never before — otherwise an early `extract` returning null pins
    // the new MediaItem in cache without an emit, and the followup
    // onTracksChanged sees `itemChanged == false` and skips the resolve.
    if (resolved == lastEmittedFormat && lastEmittedFormatMediaItem === currentItem) return
    lastEmittedFormat = resolved
    lastEmittedFormatMediaItem = currentItem
    nowPlayingFormatListeners.forEach {
      it(Variant_NullType_NowPlayingFormat.create(resolved))
    }
  }

  /**
   * Apply a new lookahead cache config at runtime. Both dimensions are
   * live — no configure() cycle needed:
   *
   * - `enabled` toggle:
   *   - true → false: tears down the WRITER (no more proactive prefetch)
   *     and routes the player's reads to the origin from the next item
   *     load; the item already loading keeps the source it opened. The
   *     cache OBJECT stays alive (Media3 allows one per directory) but is
   *     neither read nor written while disabled.
   *   - false → true: rebuilds the writer against the existing cache
   *     (the cache is built at configure() regardless of `enabled`) and
   *     routes reads through it again, so caching turns on without a
   *     configure() cycle.
   *
   * - `lookaheadCount` change: live update via
   *   `LookaheadCacheWriter.defaultLookaheadCount`. Applies on the
   *   next reschedule; in-flight downloads aren't interrupted.
   *
   * Cache SIZE + eviction policy are not part of this runtime config — both are
   * fixed at `configure()` from `PlayerConfig` (Media3's SimpleCache is immutable
   * after construction). Control actual usage at runtime via `enabled` +
   * `lookaheadCount`.
   *
   * After any change, emits a status snapshot so consumers see the new
   * config.
   */
  @MainThread
  @VisibleForTesting
  internal fun setLookaheadCacheInternal(newConfig: LookaheadCacheConfig) {
    val previous = this.lookaheadConfig
    this.lookaheadConfig = newConfig

    val countChanged = newConfig.lookaheadCount != previous.lookaheadCount
    val enableChanged = newConfig.enabled != previous.enabled
    val cellularChanged =
      (newConfig.allowsCellularAccess ?: true) != (previous.allowsCellularAccess ?: true)

    // The player's reads follow the flag from the next item load: disabled
    // means the origin, whatever the disk holds. The cache object stays —
    // Media3 allows one instance per directory — but nothing reads or writes
    // it until it is enabled again.
    if (enableChanged) {
      lookaheadCache?.servesPlayback = newConfig.enabled
    }
    // Disable → tear down the writer.
    if (enableChanged && !newConfig.enabled) {
      lookaheadCacheWriter?.release()
      lookaheadCacheWriter = null
    }

    // Enable → (re)build the WRITER against the existing cache. The cache is
    // constructed at configure() regardless of `enabled`, so turning caching on
    // is a runtime switch with no configure() cycle. Uses the stored config's
    // http params (headers / userAgent / timeout) captured at configure time.
    if (enableChanged && newConfig.enabled && lookaheadCacheWriter == null) {
      val cache = lookaheadCache
      if (cache != null) {
        lookaheadCacheWriter = LookaheadCacheWriter.create(
          cache = cache,
          httpFactory = MediaItemBuilder.buildHttpFactory(
            headers = config.httpHeaders,
            userAgent = config.userAgent,
            networkTimeoutMs = MediaItemBuilder.effectiveNetworkTimeoutMs(
              config.networkTimeoutMs
            )
          ),
          isOnCellular = { cellularTransportMonitor?.isOnCellular == true }
        ).apply {
          defaultLookaheadCount =
            newConfig.lookaheadCount.toInt().coerceAtLeast(0)
          allowsCellularAccess = newConfig.allowsCellularAccess ?: true
          onTrackProcessed = { onMainPostStatusEmit() }
        }
        rescheduleLookahead()
      }
    }

    // Live policy update — safe regardless of player lifecycle. `lookaheadCount`
    // resizes the window; `allowsCellularAccess` decides whether the window is
    // fetched at all, so turning it off empties it and cancels the prefetch in
    // flight and turning it back on refills it. Both take effect on the single
    // reschedule below. Skipped when the enable branch above just rebuilt the
    // writer, which already applied both values and rescheduled.
    val writer = lookaheadCacheWriter
    if (!enableChanged && writer != null && (countChanged || cellularChanged)) {
      if (countChanged) {
        writer.defaultLookaheadCount =
          newConfig.lookaheadCount.toInt().coerceAtLeast(0)
      }
      if (cellularChanged) {
        writer.allowsCellularAccess = newConfig.allowsCellularAccess ?: true
      }
      rescheduleLookahead()
    }

    // `lookaheadCount` sizes the protected window, so a change to it must move
    // the window even when the branches above did not reschedule — prefetch
    // disabled leaves no writer, and a combined disable+count change skips the
    // count branch entirely. The cache still evicts in both cases.
    refreshCacheProtection()
    emitCacheStatus()
  }

  /**
   * Cancel the prefetch and wipe the cache, returning the job doing the file
   * work so a caller can await it. Null when there is no cache to clear.
   *
   * The wipe is a per-resource delete walk, so it runs off the app looper.
   * Cancelling the prefetch first is what keeps it from racing: a `CacheWriter`
   * mid-write into a key just removed re-creates the entry seconds later.
   *
   * Deliberately does NOT call [rescheduleLookahead] — an immediate refill makes
   * `clearLookaheadCache` impossible to observe at zero from JS, and "clear"
   * means "wipe and stop" (the consumer re-triggers prefetch via
   * setLookaheadCache, skipToIndex, or setQueue). Matches iOS.
   */
  @MainThread
  @VisibleForTesting
  internal fun clearLookaheadCacheInternal(): Job? {
    val cache = lookaheadCache ?: return null
    // Stops the blocking CacheWriter too, not just the coroutine. Its cancelled
    // flag is polled between chunk reads, so a span already mid-write can still
    // land after the wipe; this closes the window rather than eliminating it.
    lookaheadCacheWriter?.cancel()
    // File work only. Emitting from in here would make the job depend on the
    // main thread, which deadlocks anyone awaiting it with a blocking wait on
    // that thread. The caller emits instead.
    return cacheIoScope.launch { cache.clear() }
  }

  /**
   * Build a [CacheStatus] snapshot from the current cache + writer
   * state. Called off the app looper — from the `Promise.async` pool
   * (`getLookaheadCacheStatus`) and the serialised cache-status IO
   * dispatcher (`emitCacheStatus` / `onCacheStatusChange`); reads
   * `lookaheadCache` / `lookaheadCacheWriter` as @Volatile snapshots.
   */
  @VisibleForTesting
  internal fun buildCacheStatusSnapshot(): CacheStatus {
    val cache = lookaheadCache
    val writer = lookaheadCacheWriter
    val currentSizeBytes = cache?.currentSizeBytes() ?: 0L
    // Queue-agnostic: count fully-cached entries in the cache itself,
    // not the intersection with `self.tracks`. Mirrors the semantics
    // of `currentSizeMb` (total cache disk usage) and `currentlyCaching`
    // (active downloads) — all three reflect cache state independent
    // of any current queue. Per-track local/stream/cache attribution
    // for the active queue is exposed via `getCurrentTrackSource()`.
    val tracksFullyCached = cache?.fullyCachedCount() ?: 0
    val currentlyCaching = writer?.currentlyDownloadingUrl
      ?.let { arrayOf(it) }
      ?: emptyArray()
    return CacheStatus(
      enabled = lookaheadConfig.enabled,
      currentSizeMb = currentSizeBytes.toDouble() / LookaheadCache.BYTES_PER_MB.toDouble(),
      maxSizeMb = cacheMaxSizeMbFrom(config),
      tracksFullyCached = tracksFullyCached.toDouble(),
      currentlyCaching = currentlyCaching
    )
  }

  /**
   * Fire the cache-status listeners with a fresh snapshot. Called from main
   * and from the writer's IO thread (`onTrackProcessed`); the registry is
   * copy-on-write and the listeners run on main either way.
   */
  private fun emitCacheStatus() {
    if (cacheStatusListeners.isEmpty) return
    // Built off the app looper. The snapshot counts fully-cached entries,
    // which walks every cache key and takes the cache's monitor per key —
    // contended against the prefetch thread that is committing downloads —
    // and this fires after every prefetch iteration whenever a consumer is
    // subscribed. Listeners are still called on the looper they expect.
    cacheIoScope.launch(cacheStatusDispatcher) {
      val snapshot = buildCacheStatusSnapshot()
      mainHandler.post { cacheStatusListeners.forEach { it(snapshot) } }
    }
  }

  /**
   * Emit a cache-status snapshot. Bound to the writer's `onTrackProcessed`
   * hook, so it fires once per prefetched track.
   */
  private fun onMainPostStatusEmit() {
    // `emitCacheStatus` does its own hopping — off the looper to build, back
    // onto it to deliver — so there is nothing to post first.
    emitCacheStatus()
  }

  // --- Events — observer wiring + dispatch

  /**
   * Wire this TrackPlayer as the [PlaybackEngine] delegate. The
   * engine owns the underlying `Player.Listener`; its callbacks
   * route through [PlaybackEngineDelegate] into the same notify
   * methods the legacy direct-listener path used. Idempotent —
   * a previously-registered delegate is cleared first. Called from
   * [configureInternal] AFTER the binder is validated.
   */
  @MainThread
  private fun installEventObservers() {
    tearDownEventObservers()
    val binder = serviceBinder
      ?: error("installEventObservers called before PlaybackService binder is ready")
    val engine = binder.engine
    engine.delegate = this
    engineDelegateRegistration = engine
    // A configure() during an active useProgress subscription routes
    // through tearDownEventObservers above, which stops the periodic
    // tick. The subscriber is still registered but no new
    // onProgress-register edge will fire to restart it. Restart here
    // when subscribers are present so progress UI keeps ticking
    // across reconfigure.
    startProgressTickIfNeeded()
  }

  private fun tearDownEventObservers() {
    // A retry posted before teardown would otherwise land on a released engine.
    // Cancelled here rather than in stopProgressTick, which also runs when the
    // last progress subscriber unsubscribes — a screen unmounting must not kill
    // an in-flight retry.
    cancelPendingRetry()
    // Clear the delegate from the engine TrackPlayer last wired
    // itself onto, even if [serviceBinder] has been re-bound to a
    // fresh engine since.
    engineDelegateRegistration?.let { engine ->
      if (engine.delegate === this) engine.delegate = null
    }
    // And from whatever engine is bound now. A playback-mode swap carries the
    // delegate across to the incoming engine without this registration
    // following it, so clearing only the remembered one unwires an engine
    // that has already been released and leaves the live one still
    // delivering into a torn-down player.
    serviceBinder?.engine?.let { engine ->
      if (engine.delegate === this) engine.delegate = null
    }
    engineDelegateRegistration = null
    stopProgressTick()
  }

  // --- PlaybackEngineDelegate — engine signal routing ---

  override fun onTimedMetadata(engine: PlaybackEngine, metadata: androidx.media3.common.Metadata) {
    if (serviceBinder?.engine !== engine || currentTrackIndex !in tracks.indices) return
    if (com.margelo.nitro.queueplayer.cast.PlaybackStateRouter.activeRemote() != null) return
    val update = TimedMetadataParser.parse(metadata) ?: return
    timedMetadataListeners.forEach { it(update) }
  }

  override fun onStateMaybeChanged(engine: PlaybackEngine, playWhenReadyReason: Int?) {
    if (engine.mediaSessionPlayer.playWhenReady && pauseForVideoTrack()) return
    if (playWhenReadyReason != null) {
      // Stamp the translated reason BEFORE [emitStateIfChanged]
      // reads it. The stamp is unconditional (not a conditional
      // overlay) because a stale stash from an earlier transition
      // — e.g. INTERRUPTION from a focus loss — would otherwise
      // mislabel the following USER-initiated resume.
      pendingStateChangeReason = mapPlayWhenReadyReason(playWhenReadyReason)
    }
    emitStateIfChanged()
    // Buffer state rides the same Media3 state transitions (STATE_BUFFERING ↔
    // READY), so recompute here for sub-progress-tick latency.
    recomputeBufferState()
    // Queue-end detection lives on [onPlaybackEnded] — firing it
    // here would invoke [queueEndListeners] twice on a natural
    // STATE_ENDED (state-emit dedup masks the redundant emit, but
    // the listener fan-out is unconditional).
  }

  override fun onIsPlayingChanged(engine: PlaybackEngine, isPlaying: Boolean) {
    if (BuildConfig.DEBUG && isPlaying && gaplessLogLastChangedAtNs > 0L) {
      val now = System.nanoTime()
      val deltaMs = (now - gaplessLogLastChangedAtNs).toDouble() / 1_000_000.0
      Log.d(
        GAPLESS_LOG_TAG,
        "playing trackIdx=$currentTrackIndex ts_ns=$now delta_since_changed_ms=${"%.3f".format(deltaMs)}"
      )
      gaplessLogLastChangedAtNs = 0L
    }
  }

  override fun onTrackTransition(engine: PlaybackEngine, platformReason: Int) {
    handleMediaItemTransition(platformReason)
    refreshNowPlayingFormatForActiveItem()
  }

  override fun onActiveItemFormatChanged(engine: PlaybackEngine) {
    refreshNowPlayingFormatForActiveItem()
    backfillActiveItemDurationIfNeeded(engine)
  }

  /**
   * Write `METADATA_KEY_DURATION` onto the current item from the player's
   * decoded duration when the consumer didn't supply one, so the API 33+
   * System UI seekbar has a real total time. One-shot per item — once the
   * item carries a positive duration, [MediaItemBuilder.durationToBackfill]
   * returns null, so re-fires (and the `onTracksChanged` re-entrancy the
   * `replaceMediaItem` triggers) are no-ops. Skipped mid-crossfade so the
   * outgoing leg isn't rewritten during a fade; the incoming item backfills
   * once it becomes leading and this callback re-fires. Main thread.
   */
  @MainThread
  private fun backfillActiveItemDurationIfNeeded(engine: PlaybackEngine) {
    if (engine.isCrossfadeActive) return
    val p = player ?: return
    val idx = p.currentMediaItemIndex
    if (idx < 0) return
    val item = p.currentMediaItem ?: return
    val dur = MediaItemBuilder.durationToBackfill(
      item.mediaMetadata.durationMs, p.duration
    ) ?: return
    val updated = item.buildUpon()
      .setMediaMetadata(item.mediaMetadata.buildUpon().setDurationMs(dur).build())
      .build()
    p.replaceMediaItem(idx, updated)
  }

  override fun onError(
    engine: PlaybackEngine, exception: PlaybackException, failedIndex: Int
  ) {
    handlePlayerError(exception, failedIndex)
  }

  override fun onPlaybackEnded(engine: PlaybackEngine) {
    val ms = player ?: return
    maybeEmitQueueEnd(ms.playbackState)
  }

  override fun onCrossfadeBegin(engine: PlaybackEngine, incomingIndex: Int) {
    // Fade-start: the standby leg is now audibly playing (volume 0
    // ramping up). Flip JS-facing active track + lock-screen / Now
    // Playing surface to the incoming track immediately so the
    // user sees the new track's title/artwork the moment they
    // start hearing it. The leading leg continues fading out for
    // the configured `crossfadeDurationMs`; engine accessors
    // already prefer the standby leg while `fadeJob` is active.
    //
    // Reason is hardcoded `AUTO_ADVANCE` — fade-start is always an
    // auto-advance event; any user-driven transition (skipToNext /
    // skipToIndex / setMediaItems) would have called `cancelFade`
    // first, so a stashed `pendingTrackChangeReason` here is stale
    // and should not re-attribute the fade.
    if (incomingIndex !in tracks.indices) return
    currentTrackIndex = incomingIndex
    lastErrorKey = null
    reportedFailureIndices.retainAll(setOf(incomingIndex))
    val track = tracks[incomingIndex]
    currentTrackSource = classifyTrackSource(track.url, ::servedFromCache)
    crossfadeFadeStartEmittedIdx = incomingIndex
    pendingTrackChangeReason = null
    pauseForVideoTrack()
    logPlaybackSource(track.url)
    // New active track => new milestone playthrough (the matching
    // structural transition echo is deduped in handleMediaItemTransition).
    milestoneTracker.reset()
    emitTrackChange(track, incomingIndex.toDouble(), TrackChangeReason.AUTO_ADVANCE, null)
    refreshNowPlayingFormatForActiveItem()
    recomputeCapabilities()
    rescheduleLookahead()
  }

  /**
   * Records what the player is actually reading for the active track.
   *
   * The reported source says whether the file is on disk; it does not say the
   * bytes reaching the decoder came from there. Media3 reads through
   * [LookaheadCache]'s CacheDataSource, so a rising cached-bytes total is the
   * proof that playback is being served from the cache rather than the origin.
   */
  private fun logPlaybackSource(url: String?) {
    // Debug builds only, and never the query string: stream URLs carry auth
    // tokens there, and logcat is readable by every app on older devices.
    if (!BuildConfig.DEBUG) return
    val cachedBytes = lookaheadCache?.cachedBytesReadTotal?.get() ?: 0L
    Log.i(
      DIAG_TAG,
      "now-playing source=$currentTrackSource cachedBytesRead=$cachedBytes " +
        "url=${url?.let(PlaybackErrorMapping::stripQuery)}"
    )
  }

  override fun onCrossfadeCancel(engine: PlaybackEngine, revertedIndex: Int) {
    // Fade-cancelled mid-flight (pause / stop / focus loss / queue
    // mutation). JS already received `onTrackChange(incoming,
    // reason: AUTO_ADVANCE)` from the prior fade-start fire; the
    // incoming track is no longer the active one. Revert
    // `currentTrackIndex` to the leading-leg index and emit
    // `onTrackChange(leading, reason: CROSSFADE_CANCELLED)` so
    // consumer UI can rewind to the leading track.
    if (revertedIndex !in tracks.indices) return
    currentTrackIndex = revertedIndex
    lastErrorKey = null
    // The reported set is left alone. A cancel goes back to the track that was
    // already playing rather than announcing a new one, and the track the
    // standby leg was prerolling is still in the queue and still broken — the
    // engine seats it next. Dropping its report here would hand the listener
    // the same failure a second time when it does.
    crossfadeFadeStartEmittedIdx = null
    val track = tracks[revertedIndex]
    currentTrackSource = classifyTrackSource(track.url, ::servedFromCache)
    pendingTrackChangeReason = null
    emitTrackChange(track, revertedIndex.toDouble(), TrackChangeReason.CROSSFADE_CANCELLED, null)
    refreshNowPlayingFormatForActiveItem()
    recomputeCapabilities()
    rescheduleLookahead()
  }

  override fun onAutoTransitionDiscontinuity(engine: PlaybackEngine) {
    val now = System.nanoTime()
    gaplessGapPrevEndedAtNs = now
    if (BuildConfig.DEBUG) {
      Log.d(
        GAPLESS_LOG_TAG,
        "item-ended trackIdx=$currentTrackIndex ts_ns=$now"
      )
    }
  }

  /**
   * Translate Media3 playback state + playWhenReady + suppression
   * reason into our [PlayerState] enum and fire [stateChangeListeners]
   * when the value actually changed. Consumes
   * [pendingStateChangeReason] when the emitted state is terminal
   * (PLAYING / PAUSED / ENDED / ERROR); leaves it pending across
   * intermediate BUFFERING fires so a user-initiated transition
   * labels the final state correctly.
   */
  @VisibleForTesting
  @MainThread
  internal fun emitStateIfChanged() {
    val computed = getStateInternal()
    val reason = if (computed == PlayerState.BUFFERING) {
      // Intermediate state — pass as SYSTEM and preserve the
      // pending reason for the terminal emit.
      StateChangeReason.SYSTEM
    } else if (pendingSleepTimerPause && computed == PlayerState.PAUSED) {
      // The sleep-timer fire paused playback; label this PAUSED emit
      // 'sleep-timer' regardless of the USER_REQUEST reason Media3 stamped.
      // Gated on PAUSED so a not-yet-consumed flag can never mislabel a
      // later PLAYING emit.
      StateChangeReason.SLEEP_TIMER
    } else {
      pendingStateChangeReason ?: StateChangeReason.SYSTEM
    }
    val fired = emitState(computed, reason)
    // Clear the pending reason only when the emit actually fired
    // AND the fired state was terminal (not BUFFERING). A dedup'd
    // emit must NOT consume the pending reason: the listener
    // didn't see it, so the next state change is still the user's
    // turn to attribute.
    if (fired && computed != PlayerState.BUFFERING) {
      pendingStateChangeReason = null
      pendingSleepTimerPause = false
    }
  }

  // Keep the outgoing snapshot through queue replacement/removal and through
  // state changes caused by loading or pausing the incoming item.
  private fun emitTrackChange(track: TrackItem?, index: Double, reason: TrackChangeReason, gap: Double?) {
    val previousTrack = announcedTrack
    val wasPlaying = previousTrack != null && announcedTrackPlaying
    announcedTrack = track
    announcedTrackPlaying = track != null && track.isVideo != true && lastReportedState == PlayerState.PLAYING
    trackChangeListeners.forEach { it(track, index, reason, gap, previousTrack, wasPlaying) }
  }

  /**
   * Single source of dedup truth for state emits. Returns true when
   * the listener actually fired (state changed since the last emit),
   * false when dedup'd. Used directly by terminal states (ERROR,
   * QUEUE_END) and indirectly via [emitStateIfChanged].
   */
  private fun emitState(state: PlayerState, reason: StateChangeReason): Boolean {
    if (announcedTrack != null && tracks.getOrNull(currentTrackIndex) === announcedTrack) {
      announcedTrackPlaying = state == PlayerState.PLAYING
    }
    if (state == lastReportedState) return false
    lastReportedState = state
    // Once the track actually plays, a later STATE_BUFFERING is a mid-playback
    // `stalled`, not the initial `buffering` load.
    if (state == PlayerState.PLAYING) hasStartedPlaying = true
    stateChangeListeners.forEach { it(state, reason) }
    return true
  }

  /**
   * Cast event bridge entry point. The cast-side observer
   * [com.margelo.nitro.queueplayer.cast.CastEventBridge] calls this
   * when a remote session's state changes. Same dedup as the local
   * [emitState] path — JS sees one event per real transition,
   * regardless of which engine produced it.
   */
  internal fun emitRemoteState(state: PlayerState, reason: StateChangeReason) {
    emitState(state, reason)
  }

  /**
   * Cast event bridge entry point for receiver-driven track changes.
   * Updates the canonical [currentTrackIndex] (so `getCurrentTrackIndex`
   * and the active-track seed reflect the receiver) and fires
   * [trackChangeListeners], so the JS now-playing surface follows the
   * receiver's queue position as it auto-advances or is skipped.
   */
  internal fun emitRemoteTrackChange(index: Int) {
    if (index < 0 || index >= tracks.size) return
    if (index == currentTrackIndex) return
    currentTrackIndex = index
    val track = tracks[index]
    pauseForVideoTrack()
    // No engine call happens on this path, so nothing downstream
    // reclassifies the source or recomputes the skip capability the
    // way handleMediaItemTransition does for a local advance.
    currentTrackSource = classifyTrackSource(track.url, ::servedFromCache)
    emitTrackChange(track, index.toDouble(), TrackChangeReason.AUTO_ADVANCE, null)
    recomputeCapabilities()
  }

  /**
   * Queue-end detection: fires [queueEndListeners] + emits ENDED
   * with reason QUEUE_END when the player hits `STATE_ENDED`
   * naturally (no repeat-mode wrapping). Mirrors the iOS
   * `handleCurrentItemDidChange` queue-end branch.
   */
  @VisibleForTesting
  internal fun maybeEmitQueueEnd(playbackState: Int) {
    if (playbackState != Player.STATE_ENDED) return
    if (tracks.isEmpty()) return
    // End-of-track sleep timer on the LAST track: STATE_ENDED fires no
    // MEDIA_ITEM_TRANSITION, so cover the final boundary here — matching iOS,
    // whose per-item handlePlayerItemDidPlayToEndTime fires on the last track
    // too. Idempotent, so it can't double-fire with the poll / transition path.
    fireEndOfTrackSleepTimer()
    queueEndListeners.forEach { it() }
    emitState(PlayerState.ENDED, StateChangeReason.QUEUE_END)
    // Park the engine. Reaching the end of the queue is not the same as being
    // paused — `playWhenReady` stays set through STATE_ENDED — so a queue
    // installed afterwards would start playing without anyone asking. Repeat
    // modes wrap without ever reaching STATE_ENDED, so they are unaffected.
    serviceBinder?.engine?.pause()
    // The intent to play ends with the queue: nothing that seats a track from
    // here — a skip, a seek, a fresh queue, an engine swap — starts it until
    // play() is called again.
    wantsToPlay = false
    // The pause's inline callback stamps USER_REQUEST; ENDED is already
    // reported, so that emit deduplicates and the stamp would label the next
    // terminal emit a user action. Nobody asked for this pause.
    pendingStateChangeReason = null
  }

  /**
   * Handle a Media3 currentMediaItem transition: sync
   * [currentTrackIndex] from ExoPlayer's `currentMediaItemIndex`,
   * fire [trackChangeListeners] with the translated reason.
   *
   * Transition reasons from Media3:
   * - REASON_AUTO → [TrackChangeReason.AUTO_ADVANCE] (natural end
   *   of previous item + next item in queue)
   * - REASON_REPEAT → [TrackChangeReason.AUTO_ADVANCE] (track
   *   loop under REPEAT_MODE_ONE)
   * - REASON_SEEK → consume [pendingTrackChangeReason] if set
   *   (user-skip), else fallback to [TrackChangeReason.SEEK]
   * - REASON_PLAYLIST_CHANGED → [TrackChangeReason.QUEUE_REPLACED]
   */
  /**
   * Whether playback of [url] is served from the disk cache: the cache
   * holds the whole file AND is enabled. Disabled, every read goes to the
   * origin whatever the disk holds, so a complete copy does not make the
   * track `CACHED`. An item whose data source opened before a toggle keeps
   * that source, so its report can differ from the flag for that one item.
   */
  private fun servedFromCache(url: String): Boolean =
    lookaheadConfig.enabled && lookaheadCache?.isFullyCached(url) == true

  /** Keep a video selected for the app, with both local and remote audio paused. */
  private fun pauseForVideoTrack(): Boolean {
    if (tracks.getOrNull(currentTrackIndex)?.isVideo != true) return false
    wantsToPlay = false
    val engine = serviceBinder?.engine
    // A normal crossfade pause reverts to the outgoing item. Keep the video
    // as the selected incoming item before pausing instead.
    engine?.settlePendingFadeByPromoting()
    engine?.pause()
    com.margelo.nitro.queueplayer.cast.CastTransportRouter.routePause()
    return true
  }

  @VisibleForTesting
  internal fun handleMediaItemTransition(reason: Int) {
    val p = player ?: return
    // Priming an unselected queue does not change the active track. Announce
    // it when skipToIndex selects it, rather than emitting (null, -1) here.
    // A real clear still emits its empty selection because tracks is empty.
    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED &&
      currentTrackIndex < 0 && tracks.isNotEmpty()
    ) return
    val translated = translateTransitionReason(reason, p)
    pauseForVideoTrack()

    // End-of-track sleep timer: a natural track boundary — auto-advance to the
    // next track OR a repeat-one loop of the same track (both translate to
    // AUTO_ADVANCE) — is the real end of the current track, so pause here.
    // fireAtTrackEnd is idempotent, so a deduped crossfade echo can't double-fire.
    if (translated == TrackChangeReason.AUTO_ADVANCE) fireEndOfTrackSleepTimer()

    // Natural track change → reset error dedup so a new item's
    // identical-coded error fires. Without this, a TIMEOUT on
    // track 2 (after a TIMEOUT on track 1) would be silently
    // suppressed even though they're independent failures. Mirrors
    // iOS reset in `handleCurrentItemDidChange`.
    lastErrorKey = null
    // A report against some other track says nothing about the one now
    // being announced, so it goes. A report against the track being
    // announced stands — clearing it would leave nothing to hold on.
    reportedFailureIndices.retainAll(setOf(currentTrackIndex))
    val track = if (currentTrackIndex in tracks.indices) tracks[currentTrackIndex] else null
    currentTrackSource = classifyTrackSource(track?.url, ::servedFromCache)

    // Crossfade post-swap echo: fade-start already fired
    // `onCrossfadeBegin` which dispatched the JS-facing track-
    // change for this incoming index. The structural Media3
    // transition that lands a moment later (after `swapRoles`
    // re-binds the wrapper) carries the same index — skip the
    // duplicate listener fire. Capabilities already up-to-date
    // from fade-start; recomputeCapabilities below is a no-op.
    val skipListenerFire =
      translated == TrackChangeReason.AUTO_ADVANCE &&
        crossfadeFadeStartEmittedIdx == currentTrackIndex
    crossfadeFadeStartEmittedIdx = null
    // Measure + consume the gapless auto-advance silence gap. The end
    // timestamp is cleared on ANY auto-advance (even a deduped crossfade
    // echo) so a stale value can never leak into the next real transition;
    // the measured value only rides the emit below, which is null for every
    // non-gapless-auto-advance transition.
    var transitionGapMs: Double? = null
    if (translated == TrackChangeReason.AUTO_ADVANCE && gaplessGapPrevEndedAtNs > 0L) {
      val now = System.nanoTime()
      val gapMs = (now - gaplessGapPrevEndedAtNs).toDouble() / 1_000_000.0
      transitionGapMs = gapMs
      gaplessGapPrevEndedAtNs = 0L
      if (BuildConfig.DEBUG) {
        gaplessLogLastChangedAtNs = now
        Log.d(
          GAPLESS_LOG_TAG,
          "item-changed trackIdx=$currentTrackIndex ts_ns=$now delta_since_ended_ms=${"%.3f".format(gapMs)}"
        )
      }
    }
    if (!skipListenerFire) {
      // Real track change (incl. a repeat-one loop, reason REPEAT) =>
      // new milestone playthrough. The crossfade echo (skipListenerFire)
      // already reset at onCrossfadeBegin, so it's excluded here.
      milestoneTracker.reset()
      // New track => its initial load counts as `buffering` (not `stalled`)
      // until it reaches a playing state, and it starts not-fully-buffered.
      hasStartedPlaying = false
      recomputeFullyBuffered()
      emitTrackChange(track, currentTrackIndex.toDouble(), translated, transitionGapMs)
    }

    // Native auto-advance (REASON_AUTO / REASON_REPEAT) shifts
    // currentTrackIndex via syncCurrentTrackIndexFromPlayer above
    // without any explicit mutation method being called. Recompute
    // capabilities here so the boundary-flip canSkipNext under OFF
    // (last-track auto-advance leaving cur on the last track with
    // no next) lands. PLAYLIST_CHANGED / SEEK funnel through their
    // originating mutation/skip body which already recomputes.
    recomputeCapabilities()
  }

  /**
   * Translate Media3's `MEDIA_ITEM_TRANSITION_REASON_*` into our
   * [TrackChangeReason] domain enum, applying the state-sync policy:
   * `currentTrackIndex` is overridden from `p.currentMediaItemIndex`
   * only on transitions Media3 originates (AUTO / REPEAT) and SEEK
   * transitions (which report Media3's settled target index). Never
   * sync PLAYLIST_CHANGED, where caller-driven mutation arithmetic is
   * authoritative. Consumes any
   * `pendingTrackChangeReason` stashed by the originating mutation
   * body. AUTO triggers a lookahead reschedule because the index
   * shifted; REPEAT_MODE_ONE leaves the index identical so the
   * window is unchanged.
   */
  private fun translateTransitionReason(
    reason: Int, p: ExoPlayer
  ): TrackChangeReason = when (reason) {
    Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> {
      syncCurrentTrackIndexFromPlayer(p)
      rescheduleLookahead()
      TrackChangeReason.AUTO_ADVANCE
    }
    Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> {
      syncCurrentTrackIndexFromPlayer(p)
      TrackChangeReason.AUTO_ADVANCE
    }
    Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> {
      val stashed = pendingTrackChangeReason
      pendingTrackChangeReason = null
      // Media3 has already moved to the target when it reports this
      // transition. Read its settled index before the callback snapshots the
      // track; this also covers skips to items added immediately beforehand.
      syncCurrentTrackIndexFromPlayer(p)
      stashed ?: TrackChangeReason.SEEK
    }
    Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> {
      // Do NOT sync from player. ExoPlayer's currentMediaItemIndex
      // after a timeline rebuild is not necessarily the logically
      // correct value (e.g. add-into-empty-queue contract keeps cur
      // at -1, but ExoPlayer will roll to 0).
      val stashed = pendingTrackChangeReason
      pendingTrackChangeReason = null
      stashed ?: TrackChangeReason.QUEUE_REPLACED
    }
    else -> TrackChangeReason.AUTO_ADVANCE
  }

  /**
   * reschedule the proactive prefetcher after a
   * queue/skip mutation. Reads `tracks` + `currentTrackIndex` (both
   * @Volatile) and dispatches the next N remote tracks to
   * [LookaheadCacheWriter]. Always refreshes cache protection; the prefetch
   * dispatch is skipped when the writer hasn't been built (configure not yet
   * called, or prefetch disabled).
   */
  @MainThread
  private fun rescheduleLookahead() {
    // Ahead of the writer guard on purpose: the cache is built at configure()
    // regardless of `lookaheadConfig.enabled`, so a consumer with prefetch off
    // still has a live cache that evicts, and it would otherwise run with an
    // empty protected set.
    refreshCacheProtection()
    val writer = lookaheadCacheWriter ?: return
    writer.reschedule(tracks, currentTrackIndex)
  }

  /**
   * Tell the cache which entries eviction must not take: the playing track, the
   * tracks being prefetched ahead of it, and one behind for an immediate
   * skip-back.
   *
   * Bounded deliberately — protecting the whole remaining queue would leave
   * nothing evictable and turn each commit into a cache wipe.
   */
  @MainThread
  private fun refreshCacheProtection() {
    val cache = lookaheadCache ?: return
    // Under LRU the set is discarded, and this runs on every queue mutation.
    if (!cache.honoursProtection) return
    val cur = currentTrackIndex
    if (cur < 0 || cur >= tracks.size) {
      cache.setProtectedUrls(emptySet())
      return
    }
    // The count arrives from JS as a Double, so Infinity and NaN are reachable.
    // Both survive `toInt()` as Int.MAX_VALUE / 0, and `cur + Int.MAX_VALUE`
    // wraps negative, which would make the subList bounds throw on main.
    // Bounded by MAX_PROTECTED_AHEAD as well as by the queue: a consumer count
    // at or above the queue length would protect every entry, leaving the
    // evictor nothing to reclaim and the cache growing past its budget with no
    // ceiling. The window that matters is the one around the playhead.
    val ahead = lookaheadConfig.lookaheadCount
      .takeIf { it.isFinite() }
      ?.toInt()
      ?.coerceIn(0, minOf(tracks.size, MAX_PROTECTED_AHEAD))
      ?: 0
    val lower = (cur - 1).coerceAtLeast(0)
    val upper = (cur + ahead).coerceAtMost(tracks.size - 1)
    cache.setProtectedUrls(tracks.subList(lower, upper + 1).map { it.url }.toSet())
  }

  private fun syncCurrentTrackIndexFromPlayer(p: ExoPlayer) {
    val playerIndex = p.currentMediaItemIndex
    currentTrackIndex = if (playerIndex in tracks.indices) playerIndex else -1
  }

  /** Scheduled auto-retry, held so teardown can cancel it. */
  private var pendingRetryRunnable: Runnable? = null

  /**
   * Track index whose automatic attempt is scheduled but has not run yet. A
   * failure naming it is an echo of the one being retried, so it is not
   * reported while the attempt is still owed.
   */
  private var pendingRetryIndex: Int = -1

  /**
   * Drop a scheduled automatic attempt.
   *
   * Called wherever the queue changes, because the attempt is keyed by
   * position: after a mutation that index names a different track, and both
   * the attempt itself and the echo suppression would then be aimed at it.
   */
  private fun cancelPendingRetry() {
    pendingRetryRunnable?.let { mainHandler.removeCallbacks(it) }
    pendingRetryRunnable = null
    pendingRetryIndex = -1
  }

  /**
   * Translate [PlaybackException] into a [PlaybackError] + emit on
   * both [errorListeners] and as a terminal ERROR state. Mirrors iOS
   * `handlePlayerItemFailedToPlayToEndTime` — transient network
   * conditions surface as non-fatal so consumers can offer retry
   * UX; codec / decoding errors surface as fatal.
   */
  private fun handlePlayerError(error: PlaybackException, failedIndex: Int = -1) {
    val mapped = PlaybackErrorMapping.classify(error)
    // Attribute to the track that actually failed. Under crossfade the
    // standby leg can fail while prerolling an upcoming track, and the
    // leading leg plays on — keying any of the three uses below off
    // currentTrackIndex would dedup against the wrong track, spend the wrong
    // track's retry budget, and report the wrong url to the consumer.
    val idx = if (failedIndex in tracks.indices) failedIndex else currentTrackIndex
    // Dedup by (failing index, standardized code). Mirrors iOS
    // `(queueItemId, code)` dedup. ExoPlayer can fire onPlayerError
    // multiple times for a single failed item under retry storms.
    val key = idx to mapped
    // An echo of a failure whose attempt has not run yet belongs to that
    // attempt, not to the consumer.
    if (idx >= 0 && idx == pendingRetryIndex) return
    // Already reported and not yet recovered. Under crossfade the standby leg
    // prerolls the next track, so a broken one is reported while the leading
    // track still plays; the engine then seats it and it fails again as the
    // current track. `lastErrorKey` cannot carry that on its own — the track
    // change between the two clears it — so one thing asked for would report
    // twice. The state still has to be announced when this becomes the track
    // the listener is on, because the report was raised before it was.
    if (idx >= 0 && idx in reportedFailureIndices) {
      if (idx == currentTrackIndex) emitState(PlayerState.ERROR, StateChangeReason.ERROR)
      return
    }
    // Once the queue has stopped on the current track, the tracks behind it go
    // quiet: the listener has been told the track they are on failed, and one
    // error per unplayable track in the run is noise they cannot act on. While
    // playback is healthy this does not fire, so a standby leg failing to
    // preroll under crossfade is still surfaced. Mirrors iOS.
    if (idx >= 0 && idx != currentTrackIndex &&
        currentTrackIndex in reportedFailureIndices) {
      return
    }
    if (lastErrorKey == key) return
    lastErrorKey = key
    // Auto-retry path: transient errors with retries left
    // schedule a delayed `prepare()`. Skip the JS error emit + state
    // ERROR transition until retries exhausted; consumer sees onError
    // only when the lib has given up. Mirrors iOS path.
    // Only the track being played is retried. A standby leg's preroll failing
    // is the *next* track failing to load early, and the cancel that precedes
    // the report reverts `currentTrackIndex` to the leading track, so an
    // attempt scheduled for it could never run. It is surfaced instead, and
    // gets its own automatic attempt when it becomes current and loads fresh.
    if (idx >= 0 &&
        idx == currentTrackIndex &&
        PlaybackErrorMapping.isTransient(mapped) &&
        (retryAttemptsRemaining[idx] ?: 0) > 0) {
      retryAttemptsRemaining[idx] = retryAttemptsRemaining[idx]!! - 1
      // Clear dedup so the retry-then-fail (if any) fires fresh.
      lastErrorKey = null
      val backoff = effectiveRetryBackoffMs()
      // Hold the runnable so teardown can drop it. A retry posted moments
      // before destroy() would otherwise fire against a released engine.
      pendingRetryRunnable?.let { mainHandler.removeCallbacks(it) }
      val retry = Runnable { retryFailedItem(idx) }
      pendingRetryRunnable = retry
      pendingRetryIndex = idx
      mainHandler.postDelayed(retry, backoff.toLong())
      return
    }
    // Surface the failing track's URL so consumers can correlate the
    // error to the specific item even after Media3 advances past it.
    // queueItemId stays empty on Android — the concept is iOS-specific
    // (AVPlayerItem associated-object identity); Media3 has no
    // equivalent native handle. URL is the diagnostic field that
    // matters cross-platform.
    val failedUrl = if (idx in tracks.indices) tracks[idx].url else ""
    val err = PlaybackErrorMapping.buildPlaybackError(
      error, mapped, queueItemId = "", url = failedUrl,
    )
    // The consumer now knows this track failed, so the queue holds on it until
    // something asks for playback again. Recorded for whichever track failed,
    // current or not: that is what keeps the standby leg's report from being
    // handed to the listener a second time when the engine seats it.
    if (idx >= 0) reportedFailureIndices.add(idx)
    errorListeners.forEach { it(err) }
    // The state is the current track's. A standby leg's preroll failing is
    // reported above and leaves the leading leg playing; announcing ERROR for
    // it would put a terminal state under audio that is still running, and
    // the next engine callback would flip it straight back to PLAYING.
    if (idx == currentTrackIndex) {
      emitState(PlayerState.ERROR, StateChangeReason.ERROR)
    }
  }

  // --- Auto-retry + stuck-recovery

  /** Lib-clamped auto-retry budget. Reads from `config.autoRetries`,
   *  defaults to 1, clamps to [0, 5]. */
  @VisibleForTesting
  internal fun effectiveAutoRetries(): Int =
    (config.autoRetries?.toInt() ?: 1).coerceIn(0, 5)

  /** Lib-clamped retry backoff in milliseconds. Defaults to 500,
   *  clamps to [200, 5000]. Floor at 200ms prevents retry storms
   *  against misbehaving servers (e.g. 503 with `Retry-After: 0`). */
  @VisibleForTesting
  internal fun effectiveRetryBackoffMs(): Int =
    (config.retryBackoffMs?.toInt() ?: 500).coerceIn(200, 5000)

  /** Try to recover from a transient failure by re-preparing the
   *  ExoPlayer at the current position. Called from
   *  [handlePlayerError] (auto-retry) and from [retry] (the
   *  consumer-facing one-shot). Mirrors iOS `retryFailedItem`, which
   *  rebuilds the AVQueuePlayer via `fullRebuildPlayerQueue` and seeks
   *  back to the captured position.
   *
   *  Implementation: remove + re-add the failed item at its index,
   *  `seekTo` the captured position, then `prepare()` — rather than
   *  bare `prepare()`, which re-uses the existing wedged DataSource
   *  on a "Cannot Open"-class failure and may not actually recover.
   *  Re-adding the item forces Media3 to construct a fresh DataSource
   *  from the MediaSource.Factory (carries the current `httpFactory`
   *  + cache wrapper). */
  internal fun retryFailedItem(atIndex: Int) {
    // The attempt has arrived, so whatever fails next is a new failure rather
    // than an echo of the one being retried. A scheduled attempt for the same
    // index is withdrawn with it: left posted, it would fire after the backoff
    // and rebuild the track a second time behind a user's own retry.
    if (pendingRetryIndex == atIndex) {
      pendingRetryRunnable?.let { mainHandler.removeCallbacks(it) }
      pendingRetryIndex = -1
      pendingRetryRunnable = null
    }
    // The attempt re-seats the item, so the queue is no longer stopped on it.
    reportedFailureIndices.remove(atIndex)
    // The failed item must still be the current one; a skip or a queue mutation
    // since the failure makes the retry moot. Checked before the fade is
    // settled, because settling is an audible cut and a retry that is about to
    // return has no business making one.
    if (atIndex != currentTrackIndex) return
    // Settle a fade before reading the player. `player` is the leading leg,
    // which mid-fade holds the OUTGOING track while `currentTrackIndex` has
    // already flipped to the incoming one — so every read below would describe
    // a different track than the index check just approved, and a retry would
    // rebuild and restart the track the user is leaving. Promoting first makes
    // the leading leg the track being heard, which is the one a retry is for.
    serviceBinder?.engine?.settlePendingFadeByPromoting()
    val p = player ?: return
    val current = p.currentMediaItem
    if (current != null) {
      // Act on the index that was approved above, not on whatever the engine
      // happens to be pointing at. They agree in every path found so far — the
      // standby leg carries the whole queue so its index is in the queue's own
      // space, and the settle above puts the leading leg on the current track —
      // but nothing enforces it, and a disagreement means the engine has not
      // caught up with a mutation. Rebuilding at either index would then be
      // wrong: the track being heard gets torn down and restarted at another
      // track's position, because `resumeMs` below is that track's. Refuse
      // instead; whatever asks next attempts it again.
      if (atIndex >= p.mediaItemCount || p.currentMediaItemIndex != atIndex) return
      val idx = atIndex
      val resumeMs = p.currentPosition.coerceAtLeast(0L)
      // Force a FRESH MediaSource/DataSource for the failed item without
      // collapsing the rest of the timeline. `replaceMediaItem(idx, current)`
      // with the SAME uri does an in-place update that RE-USES the wedged
      // HttpDataSource (the very thing the retry exists to discard);
      // `setMediaItem(item)` drops the whole queue (breaking auto-advance +
      // the crossfade full-queue look-ahead model). remove + re-add rebuilds
      // the single source from the MediaSource.Factory and keeps every other
      // item in place.
      p.removeMediaItem(idx)
      p.addMediaItem(idx, current)
      // remove + add moves the selection off `idx`; re-select it and restore
      // the pre-failure position before re-preparing from STATE_IDLE.
      p.seekTo(idx, resumeMs)
      p.prepare()
    } else {
      p.prepare()
    }
    // Resume only if playback is wanted NOW, not at failure time: a manual
    // retry() carries no failure-time flag, and a pause during the retry
    // backoff must win over the state the failure was scheduled under.
    if (wantsToPlay && !p.isPlaying) serviceBinder?.engine?.play()
  }

  /**
   * Translate a Media3 `PLAY_WHEN_READY_CHANGE_REASON_*` value into
   * the library's [StateChangeReason]. Returns a total result
   * (never null) so the listener can unconditionally overwrite
   * [pendingStateChangeReason] — otherwise a stale stash from an
   * earlier transition (e.g. INTERRUPTION from a focus loss)
   * would mislabel a subsequent USER-initiated resume.
   */
  @VisibleForTesting
  internal fun mapPlayWhenReadyReason(reason: Int): StateChangeReason = when (reason) {
    Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS ->
      StateChangeReason.INTERRUPTION
    Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY ->
      StateChangeReason.ROUTE_CHANGE
    Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM ->
      // End-of-queue variant — the subsequent maybeEmitQueueEnd
      // fires QUEUE_END as a terminal state; the SYSTEM stash is
      // the correct in-transit reason until then.
      StateChangeReason.SYSTEM
    Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST ->
      // Canonical user intent. Transport bodies stash USER before
      // calling player.play()/pause()/stop(); redundant write here
      // is harmless, and covers the edge case where Media3 fires
      // USER_REQUEST for a path that did NOT go through our
      // transport.
      StateChangeReason.USER
    Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE ->
      // Default to SYSTEM so consumers get a neutral non-user
      // reason rather than leaving a stale stash in place.
      StateChangeReason.SYSTEM
    else -> StateChangeReason.SYSTEM
  }

  // --- Events — progress tick

  /**
   * Start the 500 ms periodic progress ticker if a subscriber
   * exists and the ticker isn't already running. Mirrors iOS
   * `addPeriodicTimeObserver` at 2 Hz. A native `Handler` (not a JS
   * `setInterval`) is load-bearing for backgrounded playback: the
   * foreground playback service keeps the process — and this
   * app-scoped `ReactHost` — alive, so the Handler keeps ticking and
   * events keep dispatching into JS while the app is backgrounded,
   * with no separate headless keepalive task.
   */
  private fun startProgressTickIfNeeded() {
    if (progressTickActive) return
    // The tick drives progress emission, milestone detection, and fully-buffered
    // tracking, so a subscriber to any of those keeps it alive.
    if (progressListeners.isEmpty && milestoneListeners.isEmpty && fullyBufferedListeners.isEmpty) return
    progressTickActive = true
    val runnable = object : Runnable {
      override fun run() {
        if (!progressTickActive) return
        emitProgressTick()
        processMilestones()
        recomputeFullyBuffered()
        mainHandler.postDelayed(this, PROGRESS_TICK_INTERVAL_MS)
      }
    }
    progressTickRunnable = runnable
    mainHandler.postDelayed(runnable, PROGRESS_TICK_INTERVAL_MS)
  }

  private fun stopProgressTick() {
    progressTickActive = false
    progressTickRunnable?.let { mainHandler.removeCallbacks(it) }
    progressTickRunnable = null
  }

  // Stop the tick only when nothing needs it (no progress / milestone /
  // fully-buffered subscribers).
  private fun maybeStopProgressTick() {
    if (progressListeners.isEmpty && milestoneListeners.isEmpty && fullyBufferedListeners.isEmpty) {
      stopProgressTick()
    }
  }

  // Foreground onProgress cadence in ms. Reads config.progressUpdateIntervalMs,
  // defaults to 500, floors at the 500ms native tick rate, ignores non-finite /
  // non-positive inputs.
  private fun effectiveForegroundProgressIntervalMs(): Double {
    val raw = config.progressUpdateIntervalMs
    if (raw == null || !raw.isFinite() || raw <= 0.0) return PROGRESS_TICK_INTERVAL_MS.toDouble()
    return maxOf(PROGRESS_TICK_INTERVAL_MS.toDouble(), raw)
  }

  // Background onProgress cadence in ms. Reads
  // config.backgroundProgressUpdateIntervalMs, defaults to the effective
  // foreground value (no background reduction), floors at 500, ignores
  // non-finite / non-positive inputs.
  private fun effectiveBackgroundProgressIntervalMs(): Double {
    val foreground = effectiveForegroundProgressIntervalMs()
    val raw = config.backgroundProgressUpdateIntervalMs
    if (raw == null || !raw.isFinite() || raw <= 0.0) return foreground
    return maxOf(PROGRESS_TICK_INTERVAL_MS.toDouble(), raw)
  }

  // Push the configured foreground + background cadences into the throttle.
  // Called on every configureInternal(); the gate preserves its runtime state.
  private fun applyProgressEmissionIntervals() {
    progressEmissionGate.setIntervals(
      foregroundMs = effectiveForegroundProgressIntervalMs(),
      backgroundMs = effectiveBackgroundProgressIntervalMs(),
      samplerPeriodMs = PROGRESS_TICK_INTERVAL_MS.toDouble()
    )
  }

  // Observe app background/foreground so the throttle can switch cadence.
  // Idempotent — a no-op when already installed. ProcessLifecycleOwner replays
  // onStart on register when the process is STARTED, seeding the foreground
  // state. The 700ms onStop debounce is fine here (throttle engages ~700ms
  // after backgrounding).
  private fun installProgressLifecycleObserver() {
    if (progressLifecycleObserver != null) return
    val observer = object : DefaultLifecycleObserver {
      override fun onStart(owner: LifecycleOwner) {
        progressEmissionGate.setBackgrounded(false)
      }

      override fun onStop(owner: LifecycleOwner) {
        progressEmissionGate.setBackgrounded(true)
      }
    }
    progressLifecycleObserver = observer
    ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
  }

  // Remove the lifecycle observer registered by installProgressLifecycleObserver.
  // Idempotent.
  private fun removeProgressLifecycleObserver() {
    progressLifecycleObserver?.let { ProcessLifecycleOwner.get().lifecycle.removeObserver(it) }
    progressLifecycleObserver = null
  }

  /**
   * One progress tick body. Reads position + duration + buffered-
   * ahead from ExoPlayer + fires every entry in [progressListeners].
   * Runs on main. Guards against a nulled-out player or no-listeners
   * state between ticks.
   */
  @VisibleForTesting
  @MainThread
  internal fun emitProgressTick() {
    if (progressListeners.isEmpty) return
    val remote = com.margelo.nitro.queueplayer.cast.PlaybackStateRouter.activeRemote()
    if (remote != null) {
      if (remote.state.value != com.margelo.nitro.queueplayer.cast.RemotePlaybackState.PLAYING) return
    } else {
      if (tracks.isEmpty() || currentTrackIndex < 0 || serviceBinder?.engine?.isPlaying != true) return
    }
    // Inactive ticks must not consume the cadence gate. Buffer and milestone
    // work still runs separately in the tick runnable.
    if (!progressEmissionGate.shouldEmit(SystemClock.uptimeMillis())) return
    val progress = readProgress()
    progressListeners.forEach { it(progress) }
  }

  /** Read on main without changing event throttling or requiring subscribers. */
  private fun readProgress(): PlayerProgress {
    // During an active cast session the local engine is paused, so report
    // the receiver's position + duration — the tick keeps running while
    // casting, so this is the progress source the UI follows.
    com.margelo.nitro.queueplayer.cast.PlaybackStateRouter.activeRemote()?.let { remote ->
      val posMs = remote.positionMs.value
      val durMs = remote.durationMs.value
      val rPosition = posMs / MS_PER_SECOND
      val rDuration = if (durMs <= 0L) 0.0 else durMs / MS_PER_SECOND
      val rProgress = PlayerProgress(
        position = if (rPosition.isFinite()) rPosition else 0.0,
        duration = rDuration,
        buffered = 0.0,
      )
      return rProgress
    }
    val engine = serviceBinder?.engine
    // Lib canonical state takes precedence over the engine's
    // raw position read. After `clearQueue` / queue-end ExoPlayer
    // can briefly retain the prior item's `currentPosition` and
    // `duration` until it self-transitions to `STATE_IDLE`, which
    // would surface to JS as a stale `3:47 / 3:51` even though
    // `tracks.isEmpty()` and `currentTrackIndex == -1`. Empty-
    // queue progress is `0 / 0`.
    if (engine == null || tracks.isEmpty() || currentTrackIndex < 0) {
      return PlayerProgress(position = 0.0, duration = 0.0, buffered = 0.0)
    }
    // Read via the engine accessor (not `player.currentPosition`)
    // so a CrossfadeEngine in the middle of a fade reports the
    // INCOMING leg's position via `activePlayer` retargeting,
    // not the outgoing leg's near-end position.
    val positionMs = engine.currentPositionMs
    val durationMs = engine.currentDurationMs
    val bufferedMs = engine.bufferedPositionMs

    val position = positionMs / MS_PER_SECOND
    val duration = if (durationMs == C.TIME_UNSET) 0.0 else durationMs / MS_PER_SECOND
    val buffered = if (bufferedMs == C.TIME_UNSET || bufferedMs < positionMs) {
      0.0
    } else {
      (bufferedMs - positionMs) / MS_PER_SECOND
    }
    val safePosition = if (position.isFinite()) position else 0.0
    return PlayerProgress(
      position = safePosition, duration = duration, buffered = buffered)
  }

  /**
   * Advance the milestone tracker off the same periodic tick and emit any
   * 25/50/75/90% thresholds forward playback just crossed. Runs the
   * tracker even with no subscribers (so a mid-stream subscribe doesn't
   * retroactively fire already-passed thresholds); emission is gated on
   * having listeners. Tracks the local engine position — during an active
   * cast session the local engine is paused, so milestones pause too.
   */
  private fun processMilestones() {
    if (tracks.isEmpty() || currentTrackIndex < 0) return
    val engine = serviceBinder?.engine ?: return
    val positionMs = engine.currentPositionMs
    if (positionMs < 0L) return
    val positionSec = positionMs / MS_PER_SECOND
    val durationSec = effectiveMilestoneDuration(engine.currentDurationMs)
    val crossed = milestoneTracker.tick(durationSec, positionSec)
    if (crossed.isEmpty() || milestoneListeners.isEmpty) return
    warnIfReactContextGone("onPlaybackMilestone")
    val idx = currentTrackIndex.toDouble()
    for (m in crossed) {
      milestoneListeners.forEach { it(m.toDouble(), idx) }
    }
  }

  // Dev-only canary: a native milestone is being emitted while the app-scoped
  // ReactHost has no live ReactContext (the JS runtime is gone). The foreground
  // playback service is meant to keep that host alive for as long as playback
  // continues, so this should never fire; if it does, an event was computed
  // with no live JS to receive it — a host-app ReactHost teardown the lib
  // assumes-but-can't-enforce. Diagnostic only; no production behaviour change.
  private fun warnIfReactContextGone(event: String) {
    if (!BuildConfig.DEBUG) return
    val host = (boundContext as? ReactApplication)?.reactHost
    if (host?.currentReactContext == null) {
      Log.w(PLAYER_LOG_TAG, "native event '$event' fired with no live ReactContext")
    }
  }

  /**
   * Engine-reported duration when known (> 0), else the consumer-supplied
   * [TrackItem.duration] (seconds) for the active track, else 0 (no
   * computable duration -> no milestones).
   */
  private fun effectiveMilestoneDuration(durationMs: Long): Double {
    // Keep the TIME_UNSET check: the engine accessors normalize unknown
    // duration to 0L (so the tick path only ever passes 0), but the seek
    // path passes a literal `?: C.TIME_UNSET` when the engine is null.
    if (durationMs != C.TIME_UNSET && durationMs > 0L) return durationMs / MS_PER_SECOND
    val td = tracks.getOrNull(currentTrackIndex)?.duration
    return if (td != null && td > 0.0) td else 0.0
  }

  // --- Events — helpers

  /** Synchronously run [body] on the main thread. Used for callback
   *  storage — those calls aren't Promise-wrapped and arrive from
   *  whatever thread Nitro's sync-function dispatcher uses. */
  private fun onMainSync(body: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
      body()
    } else {
      val done = CountDownLatch(1)
      var err: Throwable? = null
      mainHandler.post {
        try { body() } catch (t: Throwable) { err = t }
        done.countDown()
      }
      done.await()
      err?.let { throw it }
    }
  }

  /**
   * Stash a [TrackChangeReason] that the next
   * [handleMediaItemTransition] with `REASON_SEEK` will consume.
   * Production code reaches this field via `skipTo*Internal` bodies.
   */
  @VisibleForTesting
  internal fun setPendingTrackChangeReasonForTest(reason: TrackChangeReason?) {
    pendingTrackChangeReason = reason
  }

  /**
   * Reset the [lastReportedState] dedup edge, forcing [emitStateIfChanged]
   * to emit from an already-NONE state without a real Player.Listener fire.
   */
  @VisibleForTesting
  internal fun resetLastReportedStateForTest() {
    lastReportedState = PlayerState.NONE
  }

  companion object {
    /** Prefs holding the eviction policy the on-disk cache was built with. */
    private const val CACHE_PREFS_NAME = "rnqp_lookahead_cache"
    private const val KEY_EVICTION_POLICY = "eviction_policy"

    /** Marks a cache directory renamed aside and awaiting deletion. */
    private const val DELETING_DIR_SUFFIX = ".deleting-"

    /**
     * Renamed cache directories this process is currently deleting.
     *
     * The sweep collects anything wearing the deleting- prefix, which is
     * exactly the name a discard just produced — so without this it starts a
     * second delete of a tree already being deleted, with a second database
     * provider dropping the same index tables underneath the first.
     *
     * Process-wide because the cache is: a second [TrackPlayer] configured in
     * the same process sweeps the same directory. Held only while a delete is
     * in flight, so a directory abandoned by a previous process — the case the
     * sweep exists for — is never claimed and is always collected.
     */
    private val doomedCacheDirectories = java.util.Collections.synchronizedSet(
      mutableSetOf<String>())

    private fun claimDoomedDirectory(dir: java.io.File) {
      doomedCacheDirectories.add(dir.absolutePath)
    }

    private fun releaseDoomedDirectory(dir: java.io.File) {
      doomedCacheDirectories.remove(dir.absolutePath)
    }

    private fun isDoomedDirectoryClaimed(dir: java.io.File): Boolean =
      doomedCacheDirectories.contains(dir.absolutePath)

    /** Progress tick cadence — matches iOS 2 Hz. */
    private const val PROGRESS_TICK_INTERVAL_MS = 500L
    /** Sleep-timer countdown/fade cadence — 500ms gives a smooth 10s fade. */
    private const val SLEEP_TIMER_TICK_INTERVAL_MS = 500L

    /** Tolerance for "bufferedPosition has reached duration" — a CBR-estimated
     *  duration or rounding can leave a sub-second gap when fully downloaded. */
    private const val FULLY_BUFFERED_EPSILON_MS = 1000L

    /** Conversion factor between milliseconds and seconds. Centralises
     *  the literal so position / duration / buffered translations all
     *  reference one named constant. */
    private const val MS_PER_SECOND = 1000.0

    /**
     * Upper bound on how far ahead of the playhead cache eviction is blocked.
     *
     * Independent of the consumer's lookahead count on purpose: protecting the
     * whole queue leaves the evictor nothing to reclaim, and the cache then
     * grows past its budget with no ceiling.
     */
    private const val MAX_PROTECTED_AHEAD = 10

    /**
     * Tag for the verbose gapless-transition logs (item-ended /
     * item-changed / playing). All emit sites are guarded by
     * `BuildConfig.DEBUG` so Release builds are silent. Mirrors the
     * iOS `[RNQP-GAPLESS]` NSLog convention.
     */
    private const val GAPLESS_LOG_TAG = "RNQP-GAPLESS"

    /** Playback-source diagnostics: what the player actually reads from. */
    private const val DIAG_TAG = "RNQP-DIAG"

    /** Logcat tag for player-level lifecycle warnings. Part of the
     * `QueuePlayer.<area>` hierarchy used across the lib's Android
     * sources. */
    private const val PLAYER_LOG_TAG = "QueuePlayer"

    /**
     * Most-recently-constructed [TrackPlayer] instance. Set in
     * [configureInternal], cleared in [destroyInternal]. Read by
     * [PlaybackService] when an automotive controller's voice-search
     * resolves to a track list, so the service can sync the lib's
     * authoritative `tracks` state before Media3's framework call to
     * `player.setMediaItems` lands. Mirrors the
     * `Equalizer.current` / `Visualizer.current` accessor pattern.
     */
    @JvmStatic
    @Volatile
    var current: TrackPlayer? = null
      private set

    init {
      // One process-wide listener on the cast route, forwarding to whichever
      // instance is configured. Push-PCM sessions (AirPlay 1/2) force-degrade
      // the active engine from Crossfade to Gapless because the wire protocol
      // cannot mix two ExoPlayers into one sink; clearing the session
      // re-applies the user's requested mode (so crossfade re-instates).
      // Registered once here rather than per instance: `CastSinkRouter` is a
      // process-wide object, and a per-instance listener kept every instance
      // ever constructed reachable and ran the swap on all of them.
      com.margelo.nitro.queueplayer.cast.CastSinkRouter.addRouteChangeListener { _ ->
        val player = current ?: return@addRouteChangeListener
        // Re-apply against the user's requested mode on every flip.
        // `applyEngineSwapForMode` consults `CastSinkRouter.isRemoteActive`
        // for the degrade decision, so the swap settles to the right
        // engine for the new route state.
        val mode = player.playbackModeState.active
        player.mainHandler.post { player.applyEngineSwapForMode(mode) }
      }
    }

    /**
     * Classify the active-track playback source. `null` when the URL
     * is null or its scheme is neither `file://` nor `http(s)://`
     * (e.g. `data:`, `blob:`, custom schemes — the lib has no contract
     * to honour for those today). `http(s)://` URLs split on whether
     * `isUrlCached` returns true ([TrackSource.CACHED]) or false
     * ([TrackSource.STREAMING]). `isUrlCached` is invoked at most once,
     * only for `http(s)://` URLs.
     */
    @JvmStatic
    internal fun classifyTrackSource(
      url: String?, isUrlCached: (String) -> Boolean
    ): TrackSource? {
      if (url == null) return null
      if (url.startsWith("file://")) return TrackSource.LOCAL
      val isHttp = url.startsWith("http://") || url.startsWith("https://")
      if (!isHttp) return null
      if (isUrlCached(url)) return TrackSource.CACHED
      return TrackSource.STREAMING
    }

    /**
     * Decide which [ServiceReadyReason] (if any) to dispatch on a
     * fresh service-bind. Pure function — no service controller needed.
     *
     *  - First-ever bind ([prior] == null): [ServiceReadyReason.FIRST_BIND].
     *  - Bind against a different binder identity:
     *    [ServiceReadyReason.SERVICE_REBORN] — the OS killed +
     *    re-created the service.
     *  - Bind against the same binder identity (e.g. ServiceConnection
     *    redelivery): `null` — no dispatch.
     */
    @JvmStatic
    internal fun decideReadyReason(
      prior: java.util.UUID?, current: java.util.UUID
    ): ServiceReadyReason? = when {
      prior == null -> ServiceReadyReason.FIRST_BIND
      prior != current -> ServiceReadyReason.SERVICE_REBORN
      else -> null
    }
  }
}
