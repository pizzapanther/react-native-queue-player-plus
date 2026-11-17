package com.margelo.nitro.queueplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.annotation.VisibleForTesting
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import com.margelo.nitro.core.Promise
import com.margelo.nitro.queueplayer.cast.PlaybackStateRouter
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service that owns the ExoPlayer + MediaLibrarySession.
 * In-process clients (the `TrackPlayer` Hybrid) obtain a
 * [LocalBinder] handle via [ACTION_LOCAL_BIND] to access the
 * player + session directly; framework media controllers (Android
 * Auto, Wear, Assistant) bind through the standard
 * [MediaLibraryService] path declared in the manifest.
 *
 * Extends [HeadlessJsMediaService] (which extends
 * [MediaLibraryService] which is a strict superset of
 * `MediaSessionService`) to provide:
 *  - Browse + search affordances to Android Auto / Google Assistant
 *    via [PlaybackServiceCallback].
 *  - JS-task spawning on cold-wake (Auto / Assistant binds the
 *    service before any Activity has booted React) via
 *    [getTaskConfig] returning a [HeadlessJsTaskConfig] for known
 *    wake actions.
 *
 * Threading: ExoPlayer is constructed + accessed on the service's
 * main looper, which equals the application's main looper in this
 * single-process library. Heavy work (decode, buffer, network
 * I/O) happens on ExoPlayer's internal threads regardless. Binder
 * consumers MUST hop to [LocalBinder.handler] before touching the
 * player so the host-handler indirection is canonical.
 */
@UnstableApi
class PlaybackService : HeadlessJsMediaService() {

  companion object {
    /**
     * Intent action consumers use with `bindService` to obtain the
     * [LocalBinder]. Falls through to [MediaLibraryService.onBind]'s
     * default behaviour for any other action so the framework's own
     * media controller connection path keeps working.
     */
    const val ACTION_LOCAL_BIND = "com.margelo.nitro.queueplayer.LOCAL_BIND"

    /**
     * The currently-active playback engine. Mirrors the engine the
     * service is wrapping. Set on engine boot + every swap; cleared
     * on service destroy.
     *
     * The Equalizer + Visualizer Hybrids reach this on construction
     * to wire themselves to an already-running engine — covers the
     * case where a Hybrid is constructed AFTER the service has
     * dispatched its initial wireToPlaybackEngine fires (which
     * would have hit a `null` static reference). Without this, a
     * Hybrid that lazy-constructs after service onCreate would
     * silently never receive any wire.
     */
    @JvmStatic
    @Volatile
    internal var activeEngine: PlaybackEngine? = null
      private set

    @JvmSynthetic
    internal fun setActiveEngine(engine: PlaybackEngine?) {
      activeEngine = engine
    }

    /**
     * NotificationChannel ID for the media-style foreground
     * notification. Fully-qualified to avoid collision with
     * channels created elsewhere in the consumer app. `internal`
     * so [NotificationProvider] can pin it without a cross-class
     * literal.
     */
    internal const val NOTIFICATION_CHANNEL_ID = "com.margelo.nitro.queueplayer.playback"

    /**
     * Notification ID for Media3's notification provider. Stable
     * across the service lifecycle so updates replace the post in
     * place rather than spawning duplicates. `internal` for the
     * same reason as [NOTIFICATION_CHANNEL_ID].
     */
    internal const val NOTIFICATION_ID = 1001

    /**
     * Request code paired with the launcher [PendingIntent] handed
     * to [MediaSession.Builder.setSessionActivity]. Stable across
     * the process so [PendingIntent.FLAG_UPDATE_CURRENT] mutates
     * the existing entry instead of spawning a duplicate.
     */
    private const val REQUEST_CODE_OPEN_APP = 0

    /**
     * Maximum wall-clock wait for a JS-side search-request handler
     * to resolve. After this expires the dispatch surfaces an empty
     * result list to the controller — better a clean empty than a
     * spinner that never goes away. 10 s mirrors the standard
     * Android Auto / Assistant search-tile timeout.
     */
    private const val SEARCH_DISPATCH_TIMEOUT_MS = 10_000L

    /**
     * Maximum wall-clock wait for a cold-start headless dispatch to
     * resolve — covers (a) the JS bridge spinning up + the consumer's
     * `registerPlaybackService` running, AND (b) the consumer's
     * resolution Promise running. 20 s mirrors the iOS
     * `PendingIntentBuffer` TTL: ~3 s for bridge boot + ~17 s of
     * headroom for the consumer's network calls before the controller
     * surfaces "no results" and the user retries. The two platforms'
     * cold-start windows are deliberately kept the same so the
     * behaviour is identical on iPhone + Android.
     */
    private const val HEADLESS_DISPATCH_TIMEOUT_MS = 20_000L

    /**
     * Intent action that flags an `onStartCommand` invocation as a
     * headless-controller cold-wake. [getTaskConfig] returns a
     * non-null config only for this action so an in-process
     * `LOCAL_BIND` start does not also spawn the JS task.
     */
    internal const val ACTION_HEADLESS_WAKE =
      "com.margelo.nitro.queueplayer.HEADLESS_WAKE"

    /** Bundle key carrying the connecting controller's package name
     *  through the wake intent → JS task data map. */
    internal const val EXTRA_WAKE_PACKAGE = "wakePackage"

    /** Bundle key carrying the wake intent's `action` through the JS
     *  task data map so the consumer's playbackService handler can
     *  branch on the wake source if it wants to. */
    internal const val EXTRA_WAKE_ACTION = "wakeAction"

    /** Intent actions that signal a cold-wake — either an in-process
     *  self-wake from `onConnect` (`ACTION_HEADLESS_WAKE`) or an
     *  OS-routed hardware media-button intent landing on a service
     *  whose JS bridge isn't yet up (`ACTION_MEDIA_BUTTON`). */
    private val HEADLESS_WAKE_ACTIONS: Set<String> = setOf(
      ACTION_HEADLESS_WAKE,
      Intent.ACTION_MEDIA_BUTTON
    )

    /**
     * Headless task name handed to React Native's
     * `HeadlessJsTaskService`. Must match the
     * `AppRegistry.registerHeadlessTask` registration the lib's
     * `registerPlaybackService` does on the JS side.
     */
    internal const val HEADLESS_TASK_NAME = "RNQueuePlayerHeadlessTask"

    /** Logcat tag for service-level lifecycle + dispatch warnings.
     *  Member of the `QueuePlayer.<area>` hierarchy used across the
     *  lib's Android sources. */
    private const val LOG_TAG = "QueuePlayer.Service"

    /** Minimum spacing between consecutive `selfWakeForHeadlessClient`
     *  fires from the same service instance. Defence against
     *  gearhead's rapid bind/unbind retry loop when its initial
     *  `onConnect` resolves to a not-supported state. */
    private const val SELF_WAKE_MIN_INTERVAL_MS = 30_000L

    /** Grace window between an idle `onHeadlessJsTaskFinish` and the
     *  actual `stopSelf()`. The JS bundle-load side effects
     *  (`bootstrap.ts` → `configure()` + `installCarService()`) run
     *  AFTER the headless task body resolves; tearing the service
     *  down immediately races that chain. 5s comfortably covers the
     *  cold-start bootstrap timing without keeping a genuinely-idle
     *  service alive for long. */
    private const val IDLE_STOP_GRACE_MS = 5_000L
  }

  /** Timestamp of the last actual self-wake fire — used by the
   *  rate-limiter in [selfWakeForHeadlessClient]. Zero means "never
   *  fired in this service instance". */
  @Volatile
  private var lastSelfWakeAtMs: Long = 0L

  /** True when the consumer app's React host has an active context
   *  AND a foregrounded Activity. Used by [selfWakeForHeadlessClient]
   *  to skip the wake-intent self-start that would otherwise race
   *  the live JS instance. The `currentActivity` check is more
   *  reliable in Expo dev-client mode than reading
   *  `currentReactContext` alone — the context can be null during a
   *  JS reload while the activity is still up. */
  private fun hasLiveReactContext(): Boolean {
    val reactApp = application as? com.facebook.react.ReactApplication ?: return false
    val ctx = reactApp.reactHost?.currentReactContext
    return ctx != null && ctx.currentActivity != null
  }

  // Built in onCreate; released in onDestroy. `lateinit var` (vs
  // `?:`) reflects the contract that any consumer reading these
  // through the binder is guaranteed to be post-onCreate by Android's
  // service lifecycle.
  //
  // The service owns a [GaplessEngine] wrapper around the ExoPlayer.
  // Service-level wiring (audio focus listener, MediaSession.setPlayer)
  // continues to access the underlying player via [engine].`exoPlayer`;
  // the abstract [PlaybackEngine] surface is exposed via
  // [LocalBinder.engine] for [TrackPlayer] and other engine-aware
  // consumers.
  private lateinit var engine: PlaybackEngine
  private val exoPlayer: ExoPlayer get() = engine.mediaSessionPlayer
  private var mediaSession: MediaLibrarySession? = null
  private val mainHandler = Handler(Looper.getMainLooper())

  /**
   * Native cache for the consumer's `BrowseSnapshot`. Read by
   * [PlaybackServiceCallback]'s library callbacks
   * (`onGetLibraryRoot`, `onGetChildren`, `onGetItem`, etc.) so a
   * cold-wake from Android Auto / Assistant doesn't have to wait
   * for a JS bridge round-trip. Written via the binder by
   * `TrackPlayer.setBrowseSnapshot`. Empty by default — the
   * library surface returns `RESULT_ERROR_NOT_SUPPORTED` until a
   * snapshot is pushed.
   */
  internal val browseDataProvider = BrowseDataProvider()

  /**
   * Per-query cache of JS-supplied search results. Populated by
   * [dispatchSearchToJs] once the JS `onSearchRequest` handler
   * responds; read by
   * [PlaybackServiceCallback.onGetSearchResult]. Empty by default —
   * a controller's `getSearchResult` call before the JS responder
   * has answered returns an empty page.
   */
  internal val searchResultCache = SearchResultCache()

  /**
   * Native queue of `parentId`s that a controller asked the
   * MediaLibrarySession to enumerate before the consumer's JS bridge
   * had pushed a [BrowseSnapshot]. Drained by
   * [notifyChildrenChangedForParents] on the next snapshot push so
   * the controller re-fetches with real data. See
   * [PendingBrowseRequests] for the cold-start race rationale.
   */
  internal val pendingBrowseRequests = PendingBrowseRequests()

  /**
   * Holds the JS-side automotive callbacks (`onBrowseRequest`,
   * `onSearchRequest`, `onPlayFromSearchRequest`,
   * `onPlayFromIdRequest`, `onCarConnect`, `onCarDisconnect`).
   * Registered by `TrackPlayer.on*` Hybrid methods; consulted by
   * the [callbackEnvironment] hooks when the `MediaLibrarySession`
   * callback path needs to dispatch into JS.
   */
  internal val browseCallbacks = BrowseCallbackRegistry()

  /**
   * Dependency surface [PlaybackServiceCallback] reads through.
   * Lambda-based providers for fields that mutate over the service
   * lifetime (`commandHandler`, `mediaButtonDispatch`) so the
   * callback always sees the live value without a reference rebuild.
   */
  @VisibleForTesting
  internal val callbackEnvironment = object : PlaybackServiceCallback.Environment {
    override val browseDataProvider: BrowseDataProvider
      get() = this@PlaybackService.browseDataProvider

    override val searchResultCache: SearchResultCache
      get() = this@PlaybackService.searchResultCache

    override fun commandHandler() = this@PlaybackService.commandHandler

    override fun mediaButtonDispatch() = this@PlaybackService.mediaButtonDispatch

    override fun playerCommandsFor(
      capability: SkipCapability
    ): Player.Commands =
      this@PlaybackService.playerCommandsFor(capability)

    override fun onHeadlessControllerConnect(packageName: String) {
      // Cold-wake the JS bridge so the consumer's
      // `registerPlaybackService` can fire its callbacks before
      // Media3's framework dispatches `onAddMediaItems`. Idempotent
      // — `HeadlessJsMediaService.initialized` is set by the JS task
      // spawn so re-entrant connects do not double-spawn.
      this@PlaybackService.selfWakeForHeadlessClient(packageName)
    }

    override fun onFirstHeadlessControllerAttached() {
      // Cold-start race: the FIRST headless controller's onConnect
      // fires BEFORE the JS task spawned by [selfWakeForHeadlessClient]
      // has run `registerPlaybackService`, so reading the callback
      // synchronously would silently drop the dispatch. Launch on
      // [callbackDispatchScope] and poll up to
      // [HEADLESS_DISPATCH_TIMEOUT_MS], matching dispatchAssistantPlay's
      // shape. Subsequent connects in the same session land on a
      // registered callback and fire on the first poll.
      this@PlaybackService.dispatchCarConnect()
    }

    override fun onHeadlessControllerDisconnect() {
      this@PlaybackService.browseCallbacks.carDisconnectCallback()?.invoke()
    }

    override fun onSearchQueryReceived(query: String) {
      this@PlaybackService.dispatchSearchToJs(query)
    }

    override fun onPlayFromSearchRequest(
      request: MediaSearchRequest,
      append: Boolean
    ): ListenableFuture<List<MediaItem>> =
      this@PlaybackService.dispatchPlayFromSearchToJs(request, append)

    override fun onPlayFromIdRequest(
      mediaId: String,
      append: Boolean
    ): ListenableFuture<List<MediaItem>> =
      this@PlaybackService.dispatchPlayFromIdToJs(mediaId, append)

    override fun onPlayFromIdRequestNoSync(mediaId: String) {
      this@PlaybackService.dispatchPlayFromIdToJsFireOnly(mediaId)
    }

    override fun enqueuePendingBrowseRequest(parentId: String) {
      this@PlaybackService.pendingBrowseRequests.enqueue(parentId)
    }

    override fun onBrowseRequestForChildren(
      parentId: String
    ): ListenableFuture<List<BrowseItem>> =
      this@PlaybackService.dispatchBrowseRequestToJs(parentId)

    override fun libraryRootItem(): MediaItem = libraryRootItemFromPackage()
  }

  /** Cached display label for the consumer app. Resolved lazily on
   *  first `onGetLibraryRoot` since `packageManager` reads cost a
   *  PackageManager round-trip. */
  @Volatile
  private var cachedLibraryRootTitle: String? = null

  /** Build the lib-owned root MediaItem with the consumer app's
   *  display label as title (e.g. "RNQP-Demo"). Cross-process
   *  controllers (Android Auto's gearhead) render this title in the
   *  browse drawer; an empty title makes the drawer entry look
   *  broken on some AA builds. */
  private fun libraryRootItemFromPackage(): MediaItem {
    val title = cachedLibraryRootTitle ?: run {
      val resolved = try {
        val pm = packageManager
        val info = pm.getApplicationInfo(packageName, 0)
        pm.getApplicationLabel(info).toString().ifBlank { "Library" }
      } catch (_: Throwable) {
        "Library"
      }
      cachedLibraryRootTitle = resolved
      resolved
    }
    return MediaItemMapper.toMediaItem(
      BrowseItem(
        id = PlaybackServiceCallback.LIB_ROOT_ID,
        title = title,
        subtitle = null,
        artworkUrl = null,
        playable = false,
        hasChildren = true
      )
    )
  }

  /**
   * Binder exposed to in-process clients. Holds direct references
   * to the ExoPlayer + MediaSession + the service's main handler.
   * Single-process library: no IPC marshalling — the binder is
   * effectively a typed pointer. Reads are guaranteed valid
   * between the service's `onCreate` returning and `onDestroy`
   * starting; consumers MUST `unbindService` before the service
   * is destroyed.
   */
  inner class LocalBinder : Binder() {
    val service: PlaybackService get() = this@PlaybackService

    /**
     * Stable identity for this service instance. JS-side
     * `TrackPlayer.serviceConnection.onServiceConnected` compares
     * the new binder's id against the previously-seen value to
     * distinguish a first bind (`first-bind`) from a rebind after
     * an OS-driven service kill (`service-reborn`). The OS rebuilds
     * the service from scratch in the kill-and-restart path, so
     * the new instance gets a fresh `instanceId` and the lib's
     * `onServiceReady` dispatch fires with the right reason.
     */
    val instanceId: java.util.UUID = java.util.UUID.randomUUID()

    /**
     * Strategy-pattern engine wrapping the active player(s).
     * Routes through the abstract [PlaybackEngine] surface so
     * consumers don't depend on the concrete engine class.
     */
    val engine: PlaybackEngine get() = this@PlaybackService.engine
    val session: MediaLibrarySession get() = mediaSession!!
    val handler: Handler get() = mainHandler

    /**
     * Register the [MediaSessionCommandHandler] that the
     * `MediaSession.Callback` consults when a controller
     * (lock-screen, Bluetooth, Android Auto) issues a player
     * command, and that the [MediaButtonDispatch] consults when a
     * raw `Intent.ACTION_MEDIA_BUTTON` lands via callback / service
     * path. `null` clears the registration. Calls run on the service
     * main looper.
     */
    internal fun setCommandHandler(handler: MediaSessionCommandHandler?) {
      if (commandHandler === handler) return
      commandHandler = handler
      mediaButtonDispatch = handler?.let { MediaButtonDispatch(it) }
    }

    /**
     * Push the latest skip-capability-derived [Player.Commands] to
     * every connected controller. Called by [TrackPlayer] whenever
     * `cachedSkipCapability` changes so the lock-screen, Bluetooth
     * AVRCP, Android Auto, and Wear surfaces grey out skip buttons
     * that the queue boundary makes invalid. No-op when no handler
     * is registered (no authoritative capability source) or when no
     * controllers have connected yet (next [PlaybackServiceCallback.onConnect]
     * picks up the fresh capability).
     */
    internal fun refreshAvailableCommands() {
      val handler = commandHandler ?: return
      val session = mediaSession ?: return
      // The cap is global — same Player.Commands for every controller;
      // only the four skip commands flip with the queue boundary.
      val commands = playerCommandsFor(handler.getCachedSkipCapability())
      for (controller in session.connectedControllers) {
        session.setAvailableCommands(
          controller,
          MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS,
          commands,
        )
      }
    }

    /**
     * Apply the lock-screen / notification remote-control config (skip mode +
     * jump intervals). Called on the main thread from
     * [TrackPlayer.setRemoteControls]; re-applies live.
     */
    internal fun setRemoteControls(mode: RemoteSkipMode, forwardMs: Long, backwardMs: Long) {
      this@PlaybackService.applyRemoteControls(mode, forwardMs, backwardMs)
    }

    /**
     * Mirror `PlayerConfig.clampSeekToBuffered` onto the forwarding player's
     * interval skip-forward. Called on the main thread from
     * [TrackPlayer.configureInternal] + on rebind.
     */
    internal fun setClampSeekToBuffered(enabled: Boolean) {
      this@PlaybackService.applyClampSeekToBuffered(enabled)
    }
  }

  private val localBinder by lazy { LocalBinder() }

  /**
   * Currently-registered handler consulted by [SessionCommandForwardingPlayer]
   * before the default routing runs, by [MediaButtonDispatch] for raw
   * media-button intents, by the audio-focus listener, and by
   * [PlaybackServiceCallback.onConnect] for the initial player commands.
   * `null` until
   * [TrackPlayer.configureInternal] registers one; under that
   * state every callback falls through to the Media3 default and
   * raw media-button intents fall through to the framework.
   */
  private var commandHandler: MediaSessionCommandHandler? = null

  /**
   * Per-handler [MediaButtonDispatch] that absorbs duplicate fires
   * when the same physical media-button press reaches the service
   * via more than one path (callback `onMediaButtonEvent`, service
   * `onStartCommand`, or a manifest-registered `MediaButtonReceiver`
   * that forwards into `onStartCommand`). Created/torn down with the
   * handler so its dedup state lives exactly as long as the handler
   * does.
   */
  private var mediaButtonDispatch: MediaButtonDispatch? = null

  /**
   * Tracks the previous [Player.PlaybackSuppressionReason] so the
   * audio-focus listener can detect entry into and exit from
   * `TRANSIENT_AUDIO_FOCUS_LOSS`. Initialized to `REASON_NONE` to
   * match the player's startup state; rewritten on every
   * `onPlaybackSuppressionReasonChanged` fire after the mapping
   * has been computed.
   */
  private var previousSuppressionReason: Int = Player.PLAYBACK_SUPPRESSION_REASON_NONE

  /**
   * Player instance the [audioFocusListener] is currently attached
   * to. For GaplessEngine this is the underlying ExoPlayer; for
   * CrossfadeEngine this is the [CrossfadeForwardingPlayer]
   * wrapper (so listener events follow role swaps via the
   * wrapper's `ForwardingSimpleBasePlayer` listener routing).
   * Tracked here so the next [swapEngineInternal] can remove the
   * listener from the same instance it was attached to.
   */
  private var audioFocusListenerHost: Player? = null

  /**
   * [Player.Listener] that translates Media3's audio-focus-driven
   * state changes into optional native diagnostics. These are normal
   * interruptions, not JS playback errors. `onPlayWhenReadyChanged` with reason
   * `PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS` carries
   * permanent loss; `onPlaybackSuppressionReasonChanged` carries
   * transient loss + regain. Re-attached to the new player on every
   * [configurePlayer] swap.
   */
  private val audioFocusListener = object : Player.Listener {
    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
      val mapping = AudioFocusErrorMapping.mapPlayWhenReadyReason(reason) ?: return
      commandHandler?.onAudioFocusEvent(mapping)
    }

    override fun onPlaybackSuppressionReasonChanged(reason: Int) {
      val previous = previousSuppressionReason
      previousSuppressionReason = reason
      val mapping = AudioFocusErrorMapping.mapSuppressionReason(reason, previous) ?: return
      commandHandler?.onAudioFocusEvent(mapping)
    }
  }

  /**
   * Receives [AudioManager.ACTION_AUDIO_BECOMING_NOISY] (headphone
   * unplug, A2DP disconnect, USB DAC removal). Media3 already pauses
   * playback in response to this broadcast via
   * `setHandleAudioBecomingNoisy(true)` on the bound player; the
   * receiver is purely a native diagnostic side-channel. Consumers observe
   * the resulting state change with reason `route-change`. The receiver is NOT
   * coupled to a specific player instance — [configurePlayer] swaps
   * the ExoPlayer without disturbing the registration; the next
   * broadcast routes through whatever handler is currently registered.
   */
  private val becomingNoisyReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      if (intent.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) return
      commandHandler?.onBecomingNoisy()
    }
  }

  /**
   * Tracks whether [becomingNoisyReceiver] is currently registered so
   * [onDestroy] is idempotent — `unregisterReceiver` throws
   * `IllegalArgumentException` for receivers that aren't registered,
   * which would surface if a service ever transitions through
   * `onDestroy` more than once (e.g. a partial-init failure path or a
   * test harness that walks the lifecycle twice).
   */
  private var becomingNoisyReceiverRegistered = false

  /**
   * The receiver's CastPlayer while a Chromecast session is active —
   * the MediaSession is bound to it so the system notification +
   * lockscreen follow the receiver. Non-null only while cast-bound;
   * main-thread only. The local engine keeps running idle underneath.
   */
  private var castSessionPlayer: Player? = null

  /**
   * The local engine's MediaSession player — what is bound when not
   * casting. Kept current by [swapEngineInternal] so a cast disconnect
   * restores the right instance even if the engine changed (gapless↔
   * crossfade) mid-cast.
   */
  private var localSessionPlayer: Player? = null

  /**
   * Stable wrapper the [mediaSession] is bound to for its lifetime.
   * External-controller commands route through its `handle*` overrides
   * (queue-aware skip + system-reason stamping); engine and cast swaps
   * retarget its delegate via [SessionCommandForwardingPlayer.retarget]
   * rather than re-binding the session player.
   */
  private var sessionForwardingPlayer: SessionCommandForwardingPlayer? = null

  // Remote-control config (lock-screen / notification skip mode + jump
  // intervals). Set from TrackPlayer via [LocalBinder.setRemoteControls];
  // drives the notification button layout + the forwarding player's intervals.
  private var remoteSkipMode: RemoteSkipMode = RemoteSkipMode.TRACK
  private var remoteForwardMs: Long = 15_000
  private var remoteBackwardMs: Long = 15_000
  // Mirrors PlayerConfig.clampSeekToBuffered so the forwarding player's
  // interval skip-forward obeys the same clamp as a JS seekTo. Survives a
  // session rebuild (re-applied below); re-pushed by TrackPlayer on rebind.
  private var clampSeekToBuffered: Boolean = false

  /**
   * Main-thread scope observing [PlaybackStateRouter.activeFlow] to bind
   * the receiver's CastPlayer onto the MediaSession on cast connect and
   * restore the local player on disconnect. Cancelled in [onDestroy].
   * AirPlay never reaches here — `activeFlow` is RemotePlayer-only and a
   * PcmStream (AirPlay) session is not a RemotePlayer.
   */
  private val castBindScope = kotlinx.coroutines.CoroutineScope(
    kotlinx.coroutines.SupervisorJob() + Dispatchers.Main.immediate
  )

  override fun onCreate() {
    super.onCreate()
    // Channel must exist before Media3 posts; `DefaultMediaNotificationProvider`
    // does not create it. Android dedups by ID, so re-creation on each
    // onCreate is idempotent.
    createNotificationChannel()
    engine = GaplessEngine(this)
    exoPlayer.addListener(audioFocusListener)
    audioFocusListenerHost = exoPlayer
    localSessionPlayer = exoPlayer
    // Unique per-onCreate session ID. Media3 keeps a process-wide
    // static `MediaSession` registry keyed by ID — `MediaSession`'s
    // constructor throws `Session ID must be unique` if a stale
    // session for the same ID is still in the map. Service lifecycle
    // is async (onDestroy of an old instance can race with onCreate
    // of a fresh one), so a per-instance UUID is the only fully
    // race-free strategy.
    val callback = PlaybackServiceCallback(callbackEnvironment)
    // Bind the session to a stable forwarding wrapper so external-
    // controller commands route through its handle* overrides. The
    // session + handler are read lazily: `mediaSession` is assigned just
    // below, and `commandHandler` is registered later by TrackPlayer.
    val forwardingPlayer = SessionCommandForwardingPlayer(
      exoPlayer,
      session = { mediaSession },
      commandHandler = { commandHandler },
    )
    sessionForwardingPlayer = forwardingPlayer
    // Re-apply any remote-control config set before this session existed (or
    // carried across a reconfigure); the notification layout reads
    // `remoteSkipMode` in `buildMediaButtonPreferences()` below.
    forwardingPlayer.forwardJumpMs = remoteForwardMs
    forwardingPlayer.backwardJumpMs = remoteBackwardMs
    forwardingPlayer.clampSeekToBuffered = clampSeekToBuffered
    val session = MediaLibrarySession.Builder(this, forwardingPlayer, callback)
      .setId(UUID.randomUUID().toString())
      .setBitmapLoader(buildBitmapLoader())
      .setMediaButtonPreferences(buildMediaButtonPreferences())
      // Skip when consumer is launcher-less (rare service-only consumer); the
      // session has no sessionActivity in that case, so the notification body
      // is built without a content intent.
      .also { builder -> buildSessionActivityPendingIntent()?.let(builder::setSessionActivity) }
      .build()
    mediaSession = session
    addSession(session)
    observeCastBinding()
    // Routes Media3's foreground-promotion notification through this
    // channel + notification ID and uses the lib's
    // [NotificationProvider] subclass; the provider reads media-button
    // preferences off the session above to populate the compact view.
    setMediaNotificationProvider(NotificationProvider(this))
    // ContextCompat handles the API < 34 fallback. Explicit
    // RECEIVER_NOT_EXPORTED satisfies the Android 14 requirement for
    // dynamically-registered receivers and matches the lint guidance
    // for system broadcasts that the app does not need to expose to
    // other UIDs.
    ContextCompat.registerReceiver(
      this,
      becomingNoisyReceiver,
      IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
      ContextCompat.RECEIVER_NOT_EXPORTED,
    )
    becomingNoisyReceiverRegistered = true

    // Wire the equalizer's IEqualizerEngine to ExoPlayer's audio
    // session as soon as the engine publishes a session ID. The
    // Equalizer Hybrid is a JS-side singleton; the static reference
    // captures the most recently constructed instance which always
    // matches the JS getEqualizer() singleton in practice. The
    // Visualizer Hybrid follows the same pattern — its FFT
    // processors attach via the engine's bufferSinks accumulator.
    setActiveEngine(engine)
    Equalizer.current?.wireToPlaybackEngine(engine)
    Visualizer.current?.wireToPlaybackEngine(engine)
  }

  /**
   * Resolve the consumer app's launcher activity at runtime and wrap
   * it in a [PendingIntent] that Media3 hands to
   * `DefaultMediaNotificationProvider` as the notification's content
   * intent. Returns `null` when the consumer process exposes no
   * launcher activity (rare service-only hosts) so the
   * `setSessionActivity` call site can skip the wiring without
   * crashing the service.
   *
   * `FLAG_ACTIVITY_SINGLE_TOP | FLAG_ACTIVITY_CLEAR_TOP` matches the
   * standard "tap to return to running app" semantics — reuse the
   * existing task instead of spawning a duplicate, and clear any
   * activities stacked on top of the launcher so the user lands
   * back on the app's root.
   *
   * `FLAG_IMMUTABLE` satisfies API 31+'s mutability requirement
   * (Media3 does not mutate the intent) and `FLAG_UPDATE_CURRENT`
   * keeps a single PendingIntent slot per [REQUEST_CODE_OPEN_APP]
   * across service-recreate cycles.
   */
  private fun buildSessionActivityPendingIntent(): PendingIntent? {
    val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return null
    launchIntent.flags = launchIntent.flags or
        Intent.FLAG_ACTIVITY_SINGLE_TOP or
        Intent.FLAG_ACTIVITY_CLEAR_TOP
    return PendingIntent.getActivity(
      this,
      REQUEST_CODE_OPEN_APP,
      launchIntent,
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
  }

  /**
   * Build the BitmapLoader chain Media3 uses to resolve artwork for
   * the lock-screen + notification surface:
   *   PlaceholderFallbackBitmapLoader → CacheBitmapLoader →
   *   DataSourceBitmapLoader.
   *
   * `DataSourceBitmapLoader` reuses Media3's own DataSource stack
   * (the same one ExoPlayer drives for HTTP playback), so consumer
   * `userAgent` + custom HTTP headers configured on the player's
   * `MediaSource.Factory` propagate into artwork fetches without a
   * second HTTP client. It mirrors Media3's own default loader config:
   * `setMaximumOutputDimension(MediaSession.getBitmapDimensionLimit(...))`
   * downsamples an oversized consumer image during decode (device-adaptive
   * cap; no full-resolution bitmap for a small control), and
   * `setMakeShared(true)` puts the bitmap in shared memory so the System UI
   * process reuses it instead of copying. Supplying our own loader (for the
   * placeholder guarantee below) had otherwise dropped both defaults, which
   * is what left artwork decoding unbounded. `CacheBitmapLoader` deduplicates
   * repeat requests for the same URI inside a single foreground session.
   * The outer [PlaceholderFallbackBitmapLoader] guarantees a bitmap
   * always reaches the notification — the OEM-skin failure mode
   * where a null bitmap drops the transport controls is closed.
   *
   * Resolving the placeholder bitmap performs a synchronous read of
   * `res/raw/rnqp_placeholder.jpg` once per [onCreate] (~10KB JPEG,
   * ~5–15ms). Acceptable on the service-creation path; the result
   * is cached for the service's lifetime by [PlaceholderArtwork].
   */
  @VisibleForTesting
  internal fun buildBitmapLoader(): PlaceholderFallbackBitmapLoader =
    PlaceholderFallbackBitmapLoader(
      delegate = CacheBitmapLoader(
        DataSourceBitmapLoader.Builder(this)
          .setMaximumOutputDimension(MediaSession.getBitmapDimensionLimit(this))
          .setMakeShared(true)
          .build()
      ),
      // Read on each fallback so a placeholder set at `configure` (after
      // this loader was built in onCreate) is reflected — `PlaceholderArtwork`
      // returns the consumer image when one is configured, else the built-in.
      placeholder = { PlaceholderArtwork.bitmap(applicationContext) },
    )

  /**
   * The back + forward buttons for the notification compact view and the
   * Android Auto control row. Play/pause is intentionally NOT supplied:
   * Media3 owns the central slot (`CommandButton.SLOT_CENTRAL`) and
   * synthesizes a state-aware play/pause from `COMMAND_PLAY_PAUSE`. A
   * caller-supplied central button is dropped on the notification path
   * (`DefaultMediaNotificationProvider` builds its own play/pause) and on
   * Android Auto forces a static, non-toggling icon — so supplying only
   * back/forward lets Media3 render the toggling play/pause on both
   * surfaces. The expanded view adds the scrub progress bar automatically
   * when the session reports a known duration.
   */
  private fun buildMediaButtonPreferences(): List<CommandButton> {
    // In `interval` mode the back/forward slots jump within the current track
    // (COMMAND_SEEK_BACK / COMMAND_SEEK_FORWARD, honored by
    // [SessionCommandForwardingPlayer] with the runtime interval); in `track`
    // mode they are previous/next-track.
    return when (remoteSkipMode) {
      RemoteSkipMode.INTERVAL -> listOf(
        CommandButton.Builder(CommandButton.ICON_SKIP_BACK)
          .setPlayerCommand(Player.COMMAND_SEEK_BACK)
          .setSlots(CommandButton.SLOT_BACK)
          .setDisplayName(getString(R.string.rnqp_action_jump_backward))
          .build(),
        CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD)
          .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
          .setSlots(CommandButton.SLOT_FORWARD)
          .setDisplayName(getString(R.string.rnqp_action_jump_forward))
          .build(),
      )
      RemoteSkipMode.TRACK -> listOf(
        CommandButton.Builder(CommandButton.ICON_PREVIOUS)
          .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS)
          .setSlots(CommandButton.SLOT_BACK)
          .setDisplayName(getString(R.string.rnqp_action_previous))
          .build(),
        CommandButton.Builder(CommandButton.ICON_NEXT)
          .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT)
          .setSlots(CommandButton.SLOT_FORWARD)
          .setDisplayName(getString(R.string.rnqp_action_next))
          .build(),
      )
    }
  }

  /**
   * Apply a remote-control config: point the forwarding player at the new
   * jump intervals and swap the notification button layout live. Called from
   * [LocalBinder.setRemoteControls] on the main thread.
   */
  internal fun applyRemoteControls(mode: RemoteSkipMode, forwardMs: Long, backwardMs: Long) {
    remoteSkipMode = mode
    remoteForwardMs = forwardMs
    remoteBackwardMs = backwardMs
    sessionForwardingPlayer?.forwardJumpMs = forwardMs
    sessionForwardingPlayer?.backwardJumpMs = backwardMs
    mediaSession?.setMediaButtonPreferences(buildMediaButtonPreferences())
  }

  /**
   * Mirror `PlayerConfig.clampSeekToBuffered` onto the forwarding player so the
   * controller interval skip-forward obeys the same clamp as a JS `seekTo`.
   * Called from [LocalBinder.setClampSeekToBuffered] on the main thread.
   */
  internal fun applyClampSeekToBuffered(enabled: Boolean) {
    clampSeekToBuffered = enabled
    sessionForwardingPlayer?.clampSeekToBuffered = enabled
  }

  /**
   * Create the playback NotificationChannel. No-op below API 26
   * (channels do not exist). Settings tune the channel as a silent,
   * lockscreen-visible transport surface — not a message indicator.
   */
  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val channel = NotificationChannel(
      NOTIFICATION_CHANNEL_ID,
      getString(R.string.rnqp_playback_channel_name),
      NotificationManager.IMPORTANCE_LOW
    ).apply {
      description = getString(R.string.rnqp_playback_channel_description)
      setShowBadge(false)
      lockscreenVisibility = Notification.VISIBILITY_PUBLIC
    }
    manager.createNotificationChannel(channel)
  }

  /**
   * Replace the ExoPlayer attached to the current MediaSession with
   * one built around the supplied [factory]. The session itself is
   * NOT recreated — Media3 supports `MediaSession.setPlayer(...)`
   * for atomic player swaps, which keeps the session ID stable +
   * avoids the "Session ID must be unique" static-registry collision.
   *
   * Called from `TrackPlayer.configureInternal` when the consumer's
   * `PlayerConfig` headers / userAgent / audioContentType change —
   * Media3 doesn't support swapping a `MediaSource.Factory` on a
   * live player, so the factory is bound at player build time. The
   * `audioContentType` likewise feeds into the new player's
   * `AudioAttributes` and is re-applied on every swap.
   */
  fun configurePlayer(
    factory: MediaSource.Factory,
    audioContentType: AudioContentType? = null
  ): ExoPlayer {
    val newEngine = GaplessEngine(
      context = this,
      mediaSourceFactory = factory,
      audioContentType = audioContentType
    )
    return swapEngineInternal(newEngine).mediaSessionPlayer
  }

  /**
   * Mode-switch entry point — replace the active engine with one
   * implementing the consumer's requested `PlaybackMode`. Called
   * by `TrackPlayer.setPlaybackMode` when the
   * `PlaybackModeStateMachine` returns `ImmediateSwap`.
   *
   * Sibling to [configurePlayer]: `configurePlayer` rebuilds the
   * engine when the consumer's `PlayerConfig` changes and always
   * stays on `GaplessEngine`; `swapEngine` flips between
   * `GaplessEngine` and `CrossfadeEngine` when the mode changes.
   *
   * Same service-level wiring: re-attach `audioFocusListener` to
   * the new leading player, swap `MediaSession` to the new
   * player, carry the delegate over, re-wire the Equalizer, and
   * release the old engine. Queue migration is the caller's
   * responsibility — `TrackPlayer` seeds the new engine through
   * `setMediaItems` after this returns.
   */
  fun swapEngine(newEngine: PlaybackEngine): PlaybackEngine {
    return swapEngineInternal(newEngine)
  }

  private fun swapEngineInternal(newEngine: PlaybackEngine): PlaybackEngine {
    val oldEngine = engine
    val oldPlayer = oldEngine.mediaSessionPlayer
    val newPlayer = newEngine.mediaSessionPlayer

    // Bind MediaSession to a stable wrapper for CrossfadeEngine so
    // the lock-screen scrubber survives mid-fade role swaps. The
    // wrapper retargets via setPlayer + invalidateState whenever the
    // engine flips currentRoleIsA. GaplessEngine binds directly —
    // single ExoPlayer, no role to swap.
    val sessionPlayer: Player = if (newEngine is CrossfadeEngine) {
      val wrapper = CrossfadeForwardingPlayer(newEngine)
      newEngine.onRoleSwap = { wrapper.onRoleSwapped() }
      newEngine.onCrossfadeBegin = { wrapper.onCrossfadeBegin() }
      // Mid-fade cancel re-binds the wrapper back to the leading
      // leg — `onRoleSwapped` already does setPlayer(leadingPlayer)
      // + invalidateState which is exactly what we need on cancel
      // (no role flip happened, leading is still leading).
      newEngine.onCrossfadeCancel = { wrapper.onRoleSwapped() }
      wrapper
    } else {
      newPlayer
    }

    audioFocusListenerHost?.removeListener(audioFocusListener)
    previousSuppressionReason = Player.PLAYBACK_SUPPRESSION_REASON_NONE
    // Audio-focus listener attaches to whatever is bound to the
    // MediaSession — for CrossfadeEngine that's the wrapper which
    // forwards Player.Listener events from whichever leg is
    // currently leading via ForwardingSimpleBasePlayer's listener
    // routing. For GaplessEngine the wrapper IS the underlying
    // player. Either way, the listener follows role swaps without
    // leaking against a specific leg. Tracked in
    // audioFocusListenerHost so the next swap can remove from the
    // same instance.
    localSessionPlayer = sessionPlayer
    // While cast-bound the MediaSession stays on the CastPlayer; record
    // the new engine's player so the cast disconnect binds it, but don't
    // steal the session surface from the receiver here.
    if (castSessionPlayer == null) {
      sessionPlayer.addListener(audioFocusListener)
      audioFocusListenerHost = sessionPlayer
      sessionForwardingPlayer?.retarget(sessionPlayer)
    }

    newEngine.delegate = oldEngine.delegate
    engine = newEngine

    setActiveEngine(engine)
    Equalizer.current?.wireToPlaybackEngine(engine)
    Visualizer.current?.wireToPlaybackEngine(engine)
    oldEngine.release()
    return newEngine
  }

  /**
   * Observe the active Chromecast session and bind/unbind its CastPlayer
   * onto the MediaSession. The collector runs on the main thread because
   * both `MediaSession.setPlayer` and the lazy `CastPlayer` construction
   * (`session.mediaSessionPlayer`) are main-thread-bound.
   */
  private fun observeCastBinding() {
    castBindScope.launch {
      PlaybackStateRouter.activeFlow.collect { session ->
        val castPlayer = session?.mediaSessionPlayer
        if (castPlayer != null) bindCastSessionPlayer(castPlayer)
        else unbindCastSessionPlayer()
      }
    }
  }

  /**
   * Bind the receiver's CastPlayer to the MediaSession for the cast
   * session's duration so the system notification + lockscreen follow
   * the receiver. The receiver owns audio, so the local audio-focus
   * listener is detached (it tracks LOCAL focus, irrelevant while the
   * device isn't decoding). Idempotent; main thread only.
   */
  private fun bindCastSessionPlayer(castPlayer: Player) {
    if (castSessionPlayer === castPlayer) return
    audioFocusListenerHost?.removeListener(audioFocusListener)
    audioFocusListenerHost = null
    castSessionPlayer = castPlayer
    sessionForwardingPlayer?.retarget(castPlayer)
  }

  /**
   * Restore the local engine's player on cast disconnect, reattach the
   * audio-focus listener, then release the cast player — setPlayer
   * before release, the same ordering [swapEngineInternal] uses, so the
   * MediaSession never points at a released player. The CastPlayer's
   * binding lifetime is owned here (ChromecastSession builds it but does
   * not release it). No-op when not cast-bound; main thread only.
   */
  private fun unbindCastSessionPlayer() {
    val castPlayer = castSessionPlayer ?: return
    castSessionPlayer = null
    val local = localSessionPlayer ?: engine.mediaSessionPlayer
    audioFocusListenerHost?.removeListener(audioFocusListener)
    previousSuppressionReason = Player.PLAYBACK_SUPPRESSION_REASON_NONE
    local.addListener(audioFocusListener)
    audioFocusListenerHost = local
    sessionForwardingPlayer?.retarget(local)
    castPlayer.release()
  }

  override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
    mediaSession

  override fun onBind(intent: Intent?): IBinder? {
    if (intent?.action == ACTION_LOCAL_BIND) {
      return localBinder
    }
    return super.onBind(intent)
  }

  /**
   * Path B of the media-button routing. A raw media-button intent
   * reaches the service either because a manifest-registered
   * `MediaButtonReceiver` forwarded it, or because the OS started
   * the service directly with `ACTION_MEDIA_BUTTON`. The dispatcher
   * dedups against any prior fire of the same physical press routed
   * through path A (`onMediaButtonEvent`). Unrecognised actions are
   * ignored. The `super` call keeps `MediaLibraryService` lifecycle
   * semantics intact.
   */
  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == Intent.ACTION_MEDIA_BUTTON) {
      mediaButtonDispatch?.handle(intent, RemoteCommandDeduper.Channel.BROADCAST)
    }
    return super.onStartCommand(intent, flags, startId)
  }

  /**
   * Swiping the app away from recents is an explicit user kill: stop playback,
   * clear the notification, and stop the service so the process is reaped.
   * Unconditional by design — a paused-but-queued session is NOT kept alive
   * here (resurrecting audio the user just dismissed would be wrong). This is
   * distinct from home-backgrounding, where the foreground service keeps
   * playing. `stopSelf()` on this explicit action also prevents the
   * `START_STICKY` recreation the OS applies only to system-killed services.
   *
   * Deliberately does not call `super.onTaskRemoved` (whose only job is a
   * conditional `pauseAllPlayersAndStopSelf`): this service owns exactly one
   * session, so stopping its player + the explicit `stopSelf` below is the
   * complete kill. A future multi-session change would need to stop each.
   */
  override fun onTaskRemoved(rootIntent: Intent?) {
    // Stop the engine, not the session's player. Under crossfade the session
    // is bound to a forwarding wrapper that addresses one leg, so stopping it
    // leaves the other leg running: the task is swiped away, the notification
    // and session go, and audio keeps playing with nothing left to control
    // it. The engine stops both legs and abandons audio focus.
    engine.stop()
    // And the session's player. While cast-bound that is the receiver, which
    // the engine does not own — stopping only the legs leaves the Chromecast
    // playing after the task, notification and session are all gone. Off cast
    // it is the forwarding wrapper around a leg the engine just stopped.
    mediaSession?.player?.stop()
    stopForeground(Service.STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  override fun onDestroy() {
    callbackDispatchScope.cancel()
    browseCallbacks.clear()
    pendingBrowseRequests.clear()
    // Drop any pending idle stopSelf check — the service is going
    // away anyway, no need to fire the re-check on a dead instance.
    stopSelfHandler.removeCallbacks(stopSelfRunnable)
    if (becomingNoisyReceiverRegistered) {
      unregisterReceiver(becomingNoisyReceiver)
      becomingNoisyReceiverRegistered = false
    }
    // Detach the equalizer + visualizer before tearing the engine
    // down so the session-ID listener + audio-buffer sinks don't
    // fire against a released engine.
    setActiveEngine(null)
    Equalizer.current?.unwireFromPlaybackEngine()
    Visualizer.current?.unwireFromPlaybackEngine()
    audioFocusListenerHost?.removeListener(audioFocusListener)
    audioFocusListenerHost = null
    castBindScope.cancel()
    castSessionPlayer?.release()
    castSessionPlayer = null
    commandHandler = null
    mediaButtonDispatch = null
    mediaSession?.release()
    mediaSession = null
    sessionForwardingPlayer = null
    engine.release()
    super.onDestroy()
  }

  /**
   * Coroutine scope tied to the service lifetime. Headless callback
   * dispatches (e.g. an Auto search awaiting JS) launch on this
   * scope so they cancel cleanly when [onDestroy] fires.
   *
   * `Dispatchers.IO` (not `Main`): the dispatch body is suspending
   * await on a Nitro Promise round-trip — never touches Player or
   * UI state — so blocking Main here would needlessly stall Media3
   * + UI. Future consumers run on `directExecutor()` and don't
   * inherit this dispatcher.
   */
  private val callbackDispatchScope =
    kotlinx.coroutines.CoroutineScope(
      kotlinx.coroutines.SupervisorJob() + Dispatchers.IO
    )

  /**
   * Dispatch [query] to the JS-registered `onSearchRequest`
   * callback, await the result, write the items into
   * [searchResultCache], and emit `notifySearchResultChanged` for
   * every connected controller so they re-pull via
   * `getSearchResult`. No-op when no callback is registered.
   *
   * Threading: launched on [callbackDispatchScope] so the await
   * doesn't block the Media3 callback thread + cancels when the
   * service is destroyed. JS callbacks return a Nitro
   * `Promise<Promise<...>>` (outer = Hybrid wrapper, inner = JS
   * promise) — both layers are awaited.
   */
  internal fun dispatchSearchToJs(query: String) {
    val callback = browseCallbacks.searchRequestCallback() ?: return
    callbackDispatchScope.launch {
      val items: List<BrowseItem> = try {
        kotlinx.coroutines.withTimeoutOrNull(SEARCH_DISPATCH_TIMEOUT_MS) {
          callback(query).await().await().toList()
        } ?: emptyList()
      } catch (t: kotlinx.coroutines.CancellationException) {
        // The service is being destroyed; nothing below may run.
        throw t
      } catch (t: Throwable) {
        // JS handler threw — surface as an empty answered result so
        // the controller stops waiting. The thrown error is the
        // consumer's bug; logging helps diagnose without taking the
        // process down.
        android.util.Log.w(
          LOG_TAG,
          "onSearchRequest('$query') threw — surfacing empty results",
          t
        )
        emptyList()
      }
      searchResultCache.setResults(query, items)
      // `mediaSession` is owned by main and nulled by onDestroy there; the
      // notify has to read it on the same thread or it races the release.
      withContext(Dispatchers.Main) {
        notifySearchResultChanged(query, items.size)
      }
    }
  }

  /**
   * Dispatch a structured voice-search [request] (Google Assistant,
   * Auto voice tile, future Siri intent extension) to the
   * JS-registered `onPlayFromSearchRequest` handler. Awaits the
   * resolved [TrackItem] list, applies it to the lib's authoritative
   * queue state, and returns the [MediaItem] list Media3's framework
   * should set on the player.
   *
   * On a cold-start Assistant boot, the JS bridge may not yet have
   * registered the callback when `onAddMediaItems` lands. The
   * coroutine waits up to [HEADLESS_DISPATCH_TIMEOUT_MS] for the
   * registration to land before timing out + returning an empty
   * list (which surfaces as "no results" to Assistant — the user
   * retries).
   */
  internal fun dispatchPlayFromSearchToJs(
    request: MediaSearchRequest,
    append: Boolean = false
  ): ListenableFuture<List<MediaItem>> = dispatchAssistantPlay(
    methodLabel = "onPlayFromSearchRequest",
    append = append,
    awaitSlot = { browseCallbacks.awaitPlayFromSearchRequestCallback() }
  ) { browseCallbacks.playFromSearchRequestCallback()?.invoke(request) }

  /**
   * Dispatch a browse-leaf-tap [mediaId] (Auto / Wear OS browse-row
   * tap) to the JS-registered `onPlayFromIdRequest` handler. Same
   * cold-start race-handling + queue-sync flow as
   * [dispatchPlayFromSearchToJs].
   */
  internal fun dispatchPlayFromIdToJs(
    mediaId: String,
    append: Boolean = false
  ): ListenableFuture<List<MediaItem>> = dispatchAssistantPlay(
    methodLabel = "onPlayFromIdRequest",
    append = append,
    awaitSlot = { browseCallbacks.awaitPlayFromIdRequestCallback() }
  ) { browseCallbacks.playFromIdRequestCallback()?.invoke(mediaId) }

  /**
   * Fire-and-forget variant of [dispatchPlayFromIdToJs]: invoke the
   * JS callback for its `setQueue + play` side effect and discard the
   * resolved track list. Used by the browse-leaf tap
   * (`onSetMediaItems` mediaId path) where the JS handler is the
   * authoritative queue writer; calling the full sync variant would
   * trigger [TrackPlayer.applyResolvedAssistantQueue] which resets
   * `currentTrackIndex` to 0 and breaks skip-previous / skip-next
   * after a tap at any index > 0.
   *
   * Carries the same cold-start race handling as the sync variant —
   * the JS callback may not be registered when the OS hands off the
   * tap, so the dispatch waits up to [HEADLESS_DISPATCH_TIMEOUT_MS]
   * for `playFromIdRequestCallback()` to return non-null before
   * timing out. Errors are logged + swallowed; the JS side is the
   * surface for user-facing failure handling.
   */
  internal fun dispatchPlayFromIdToJsFireOnly(mediaId: String) {
    callbackDispatchScope.launch {
      try {
        kotlinx.coroutines.withTimeoutOrNull(HEADLESS_DISPATCH_TIMEOUT_MS) {
          val promise: Promise<Promise<Array<TrackItem>>>? =
            browseCallbacks.playFromIdRequestCallback()?.invoke(mediaId)
              ?: run {
                browseCallbacks.awaitPlayFromIdRequestCallback()
                browseCallbacks.playFromIdRequestCallback()?.invoke(mediaId)
              }
          promise?.await()?.await()
        }
      } catch (t: kotlinx.coroutines.CancellationException) {
        // The service is being destroyed; nothing below may run.
        throw t
      } catch (t: Throwable) {
        android.util.Log.w(
          LOG_TAG,
          "onPlayFromIdRequest (fire-only) threw — discarding",
          t
        )
      }
    }
  }

  /** Dispatch an unknown-parent browse drilldown to the JS-registered
   *  `onBrowseRequest` handler. Called by [PlaybackServiceCallback]
   *  when `onGetChildren` fires for a parentId that the cached
   *  snapshot does not contain. */
  internal fun dispatchBrowseRequestToJs(
    parentId: String
  ): ListenableFuture<List<BrowseItem>> {
    val future =
      SettableFuture.create<List<BrowseItem>>()
    val job = callbackDispatchScope.launch {
      val items: List<BrowseItem> = try {
        kotlinx.coroutines.withTimeoutOrNull(HEADLESS_DISPATCH_TIMEOUT_MS) {
          val promise: Promise<Promise<Array<BrowseItem>>>? =
            browseCallbacks.browseRequestCallback()?.invoke(parentId)
              ?: run {
                browseCallbacks.awaitBrowseRequestCallback()
                browseCallbacks.browseRequestCallback()?.invoke(parentId)
              }
          promise?.await()?.await()?.toList() ?: emptyList()
        } ?: emptyList()
      } catch (t: kotlinx.coroutines.CancellationException) {
        // The service is being destroyed; nothing below may run.
        throw t
      } catch (t: Throwable) {
        android.util.Log.w(
          LOG_TAG,
          "onBrowseRequest('$parentId') threw — surfacing empty children",
          t
        )
        emptyList()
      }
      future.set(items)
    }
    // Cancel the launched dispatch when the controller drops the
    // future (Media3 cancels in-flight resolution on disconnect).
    // Without this the coroutine would still hold the JS Promise
    // chain alive for a controller that has already given up.
    future.addListener(
      Runnable { if (future.isCancelled) job.cancel() },
      MoreExecutors.directExecutor()
    )
    return future
  }

  private inline fun dispatchAssistantPlay(
    methodLabel: String,
    append: Boolean,
    crossinline awaitSlot: suspend () -> Unit,
    crossinline invoke: () -> Promise<Promise<Array<TrackItem>>>?
  ): ListenableFuture<List<MediaItem>> {
    val future =
      SettableFuture.create<List<MediaItem>>()
    val job = callbackDispatchScope.launch {
      val tracks: List<TrackItem> = try {
        kotlinx.coroutines.withTimeoutOrNull(HEADLESS_DISPATCH_TIMEOUT_MS) {
          // [invoke] reads the registry slot; on null we suspend on
          // [awaitSlot] (the slot's StateFlow) until a registration
          // lands. The first non-null read after that wins.
          val promise = invoke() ?: run {
            awaitSlot()
            invoke()
          }
          promise?.await()?.await()?.toList() ?: emptyList()
        } ?: emptyList()
      } catch (t: kotlinx.coroutines.CancellationException) {
        // The service is being destroyed; nothing below may run.
        throw t
      } catch (t: Throwable) {
        android.util.Log.w(
          LOG_TAG,
          "$methodLabel threw — surfacing empty result",
          t
        )
        emptyList()
      }
      // Sync the lib's authoritative tracks state to the resolved
      // list. Media3's framework calls `player.setMediaItems(items, ...)`
      // with whatever this future resolves to, so we MUST return the
      // real items — returning emptyList here would wipe the queue
      // immediately after Media3 installs it.
      val items = applyAssistantQueueOnMain(tracks, append)
      future.set(items)
    }
    // Cancel the launched dispatch when the controller drops the
    // future (Media3 cancels in-flight resolution when the controller
    // disconnects). Without this the coroutine would still run +
    // mutate the lib's queue state for a controller that gave up.
    future.addListener(
      Runnable {
        if (future.isCancelled) job.cancel()
      },
      MoreExecutors.directExecutor()
    )
    return future
  }

  /**
   * Fire the consumer's `onCarConnect` callback once it's registered.
   * The FIRST automotive controller's [onConnect] races the JS-bridge
   * boot — calling [browseCallbacks.carConnectCallback] synchronously
   * returns `null` until [registerPlaybackService] has run. Suspends
   * on the slot's StateFlow up to [HEADLESS_DISPATCH_TIMEOUT_MS];
   * warns on timeout (consumer didn't wire `onCarConnect`).
   */
  internal fun dispatchCarConnect() {
    callbackDispatchScope.launch {
      val callback = kotlinx.coroutines.withTimeoutOrNull(
        HEADLESS_DISPATCH_TIMEOUT_MS
      ) {
        browseCallbacks.awaitCarConnectCallback()
      }
      if (callback == null) {
        android.util.Log.w(
          LOG_TAG,
          "onCarConnect — no JS callback registered after " +
            "${HEADLESS_DISPATCH_TIMEOUT_MS}ms (consumer did not wire it?)"
        )
        return@launch
      }
      withContext(Dispatchers.Main) {
        callback()
      }
    }
  }

  private suspend fun applyAssistantQueueOnMain(
    tracks: List<TrackItem>,
    append: Boolean
  ): List<MediaItem> {
    if (tracks.isEmpty()) return emptyList()
    val player = TrackPlayer.current ?: return emptyList()
    return withContext(Dispatchers.Main) {
      player.applyResolvedAssistantQueue(tracks, append)
    }
  }

  /**
   * Boot the JS task IF a headless-eligible controller has connected
   * AND no JS task is already running. Idempotent — the
   * `HeadlessJsMediaService.initialized` flag + the wake-intent
   * action gate prevent re-entrancy.
   */
  internal fun selfWakeForHeadlessClient(packageName: String) {
    // Skip the wake-intent self-start when the JS bridge is already
    // alive. The headless-wake path is for the cold-start case where
    // the OS has bound our service from a backgrounded / killed app
    // and we need a JS context to dispatch into. Firing startService
    // while a foreground JS context is running races with the live
    // React instance and on observed devices (Pixel 6a, Android 14)
    // tears down the bridge — playback stops, the UI hangs, the
    // process eventually errors out.
    //
    // Detection: read the ReactApplication's current React context
    // directly. This signals "JS bridge is alive" much earlier than
    // checking `browseCallbacks.carConnectCallback()` — the bridge
    // exists hundreds of milliseconds before the consumer's
    // `registerPlaybackService` call lands. gearhead's first
    // `onConnect` arrives in that window, so the callback-based
    // guard misses it (logged as the bind/unbind retry storm).
    if (hasLiveReactContext()) {
      android.util.Log.i(
        LOG_TAG,
        "selfWakeForHeadlessClient($packageName) skipped — React context already alive"
      )
      return
    }
    // Defence-in-depth: even if the context guard misses, rate-limit
    // self-wake to one per 30s. gearhead's retry-on-error loop fires
    // `onConnect` several times per second when our `onConnect` /
    // `onGetLibraryRoot` returns the wrong shape; without this gate,
    // each retry queues another `startService` → JS-task spawn,
    // amplifying the failure cascade.
    val now = System.currentTimeMillis()
    val last = lastSelfWakeAtMs
    if (last > 0 && now - last < SELF_WAKE_MIN_INTERVAL_MS) {
      android.util.Log.i(
        LOG_TAG,
        "selfWakeForHeadlessClient($packageName) rate-limited (${now - last}ms since last)"
      )
      return
    }
    lastSelfWakeAtMs = now
    android.util.Log.i(LOG_TAG, "selfWakeForHeadlessClient($packageName)")
    val intent = Intent(this, this::class.java).apply {
      action = ACTION_HEADLESS_WAKE
      putExtra(EXTRA_WAKE_PACKAGE, packageName)
    }
    try {
      // Plain `startService` rather than the foreground variant —
      // the wake intent's job is to fire `onStartCommand` so
      // [getTaskConfig] returns a non-null config + the JS context
      // boots. Foreground promotion is handled later by Media3's
      // own notification flow when playback actually begins. Using
      // `startForegroundService` here would oblige us to call
      // `startForeground` within ~5 s on Android 14+, but we have
      // nothing to post yet.
      startService(intent)
    } catch (e: IllegalStateException) {
      // Android 12+ throws when a service start happens off the
      // privileged window ("Not allowed to start service Intent…:
      // app is in background"). The service is already alive
      // (the controller bound to it before this ran), so JS never
      // wakes + the controller times out. Log so the failure is
      // visible in the bug report.
      android.util.Log.w(
        LOG_TAG,
        "selfWakeForHeadlessClient($packageName) blocked by background-start rules",
        e
      )
    } catch (e: SecurityException) {
      // OEM-skinned devices occasionally tighten the permission
      // surface for self-start; same fallback as the IllegalState
      // case above.
      android.util.Log.w(
        LOG_TAG,
        "selfWakeForHeadlessClient($packageName) denied by SecurityException",
        e
      )
    }
  }

  override fun getTaskConfig(intent: Intent?): com.facebook.react.jstasks.HeadlessJsTaskConfig? {
    val action = intent?.action ?: return null
    if (action !in HEADLESS_WAKE_ACTIONS) return null
    // Non-ReactApplication hosts cannot spawn a JS task —
    // short-circuit here so `Arguments.createMap()` never has to load
    // the React native libs such a host lacks.
    if ((application as? com.facebook.react.ReactApplication) == null) return null
    // Belt-and-braces: if a wake intent somehow lands while a React
    // context is already alive (foreground app, prior wake already
    // ran the task to completion + the context survived), reject the
    // spawn. Returning a non-null config queues a second JS task that
    // would race the live context.
    if (hasLiveReactContext()) {
      android.util.Log.i(
        LOG_TAG,
        "getTaskConfig skipping $action — React context already alive"
      )
      return null
    }
    val data = com.facebook.react.bridge.Arguments.createMap().apply {
      putString(EXTRA_WAKE_ACTION, action)
      intent.getStringExtra(EXTRA_WAKE_PACKAGE)?.let {
        putString(EXTRA_WAKE_PACKAGE, it)
      }
    }
    return com.facebook.react.jstasks.HeadlessJsTaskConfig(
      HEADLESS_TASK_NAME,
      data,
      0,
      true
    )
  }

  override fun onHeadlessJsTaskFinish(taskId: Int) {
    activeTasks.remove(taskId)
    // A media service stays alive while the player has items —
    // browse/voice callbacks remain dispatchable as long as the
    // session is up. But a cold-wake that finished without any
    // playback (e.g. JS booted, dispatch resolved empty) leaves
    // the service in foreground-eligible-but-idle state which
    // Android 14+ treats as an orphan; tear it down.
    //
    // Deferred 5s grace window: the JS-side cold-start path runs
    // `bootstrap.ts` as a bundle-load side effect AFTER the
    // headless task body resolves. That side effect calls
    // `configure()` + `installCarService()` which push the browse
    // snapshot + bind controllers. Tearing the service down
    // immediately on task-finish races that side-effect chain —
    // the service stops just as the consumer's bootstrap is wiring
    // up. The 5s window lets that chain complete; the re-check at
    // fire time bails if any of the relevant signals (media,
    // active tasks, connected controllers) have arrived.
    if (activeTasks.isEmpty() && !hasMediaQueued()) {
      scheduleIdleStopSelfCheck()
    }
  }

  private val stopSelfHandler =
    android.os.Handler(android.os.Looper.getMainLooper())
  private val stopSelfRunnable = Runnable {
    if (
      activeTasks.isEmpty() &&
      !hasMediaQueued() &&
      !hasConnectedControllers()
    ) {
      android.util.Log.i(
        LOG_TAG,
        "Idle grace window elapsed; calling stopSelf()"
      )
      stopSelf()
    } else {
      android.util.Log.i(
        LOG_TAG,
        "Idle grace window re-check: service has active surface, " +
          "leaving alive (activeTasks=${activeTasks.size}, " +
          "hasMedia=${hasMediaQueued()}, " +
          "controllers=${mediaSession?.connectedControllers?.size ?: 0})"
      )
    }
  }

  private fun scheduleIdleStopSelfCheck() {
    stopSelfHandler.removeCallbacks(stopSelfRunnable)
    stopSelfHandler.postDelayed(stopSelfRunnable, IDLE_STOP_GRACE_MS)
    android.util.Log.i(
      LOG_TAG,
      "Scheduling idle stopSelf check in ${IDLE_STOP_GRACE_MS}ms"
    )
  }

  /**
   * True when the active session's player has at least one media
   * item queued. Read by [onHeadlessJsTaskFinish] to decide whether
   * to keep the service alive after the JS task body completes.
   */
  private fun hasMediaQueued(): Boolean =
    (mediaSession?.player?.mediaItemCount ?: 0) > 0

  /**
   * True when any automotive / Wear / Assistant controller is bound
   * to the active session. Read by the deferred stopSelf check so a
   * controller that connected during the grace window keeps the
   * service alive.
   */
  private fun hasConnectedControllers(): Boolean =
    (mediaSession?.connectedControllers?.isNotEmpty() ?: false)

  /**
   * Fire `MediaLibrarySession.notifyChildrenChanged` for every
   * `parentId` in [parentIds] PLUS every parent the
   * [pendingBrowseRequests] queue accumulated while the snapshot
   * was empty. Invoked by `TrackPlayer.setBrowseSnapshot` after a
   * fresh snapshot lands. Caller is on main; the session's own
   * thread-safety contract handles the controller iteration.
   *
   * Draining the pending queue here closes the cold-start race —
   * a controller that fetched children before any snapshot existed
   * gets re-notified once data is available, so its UI replaces
   * the empty placeholder with the real items.
   */
  internal fun notifyChildrenChangedForParents(parentIds: Set<String>) {
    val drained = pendingBrowseRequests.drainAll()
    val all = if (drained.isEmpty()) parentIds else parentIds + drained
    val session = mediaSession ?: return
    for (controller in session.connectedControllers) {
      for (parentId in all) {
        // Pass count=Int.MAX_VALUE so the controller refetches the
        // whole subtree under the parent — the snapshot's section
        // sizes drive the actual returned count.
        session.notifyChildrenChanged(controller, parentId, Int.MAX_VALUE, null)
      }
    }
  }

  /**
   * Fire `notifySearchResultChanged` for [query] across every
   * connected controller after [searchResultCache] is populated.
   * Caller is on main; the session's own thread-safety contract
   * handles the controller iteration.
   */
  private fun notifySearchResultChanged(query: String, count: Int) {
    val session = mediaSession ?: return
    for (controller in session.connectedControllers) {
      session.notifySearchResultChanged(controller, query, count, null)
    }
  }

  /**
   * Translate a [SkipCapability] into the [Player.Commands] set that
   * controllers should see. The cap reflects repeat-mode + queue
   * position rather than Media3's timeline-derived default, so
   * `repeat=queue` at the last index correctly leaves skip-next
   * enabled; only the four skip commands flip with the cap. Every
   * other command (play/pause, seek, set speed, etc.) is preserved
   * from `DEFAULT_PLAYER_COMMANDS`.
   */
  internal fun playerCommandsFor(
    capability: SkipCapability
  ): Player.Commands {
    val builder = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
    if (!capability.canSkipNext) {
      builder.remove(Player.COMMAND_SEEK_TO_NEXT)
      builder.remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
    }
    if (!capability.canSkipPrevious) {
      builder.remove(Player.COMMAND_SEEK_TO_PREVIOUS)
      builder.remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
    }
    return builder.build()
  }

  /**
   * Interface [SessionCommandForwardingPlayer] consults before letting Media3 route
   * a controller-initiated command to the bound player. Calls run on
   * the service main looper. Skip-next / skip-previous / seek-to-queue-item
   * return true when the implementation has handled the command itself,
   * so the default routing is suppressed; `onTransportCommand` fires for
   * play / pause / in-item seek / setPlaybackSpeed and is purely a
   * side-channel notification (no return value).
   */
  internal interface MediaSessionCommandHandler {
    /** Stamps `system` on the state-change channel for the upcoming
     *  play / pause / seek / setPlaybackSpeed default routing. */
    fun onTransportCommand()
    /** Returns true if the lib intercepted the skip and the default
     *  routing should be suppressed. */
    fun onSkipToNextCommand(): Boolean
    /** Returns true if the lib intercepted the skip and the default
     *  routing should be suppressed. */
    fun onSkipToPreviousCommand(): Boolean
    /** Routes an external controller's tap on a queue row (Android Auto's
     *  "seek to this item") through the lib's `skipToIndex` path so the
     *  canonical current index + track-change reason land. Returns true
     *  when handled so the default per-item seek is suppressed; returns
     *  false for an out-of-range index so the default routing no-ops it. */
    fun onSeekToMediaItemCommand(mediaItemIndex: Int): Boolean

    /**
     * Returns the lib's current authoritative skip capability —
     * driven by repeat-mode + queue position + queue size on the
     * `TrackPlayer` side. Read by [PlaybackServiceCallback.onConnect] and
     * [LocalBinder.refreshAvailableCommands] so the available
     * `Player.Commands` reflect what skip arithmetic actually allows.
     */
    fun getCachedSkipCapability(): SkipCapability

    // The following are invoked when a raw `KEYCODE_MEDIA_*` lands via
    // either the `onMediaButtonEvent` callback or `onStartCommand` —
    // there is no Media3 default routing to fall through to in that
    // case, so the handler executes the action itself.

    /** Start playback with `system` reason stamped. */
    fun onMediaPlay()
    /** Pause playback with `system` reason stamped. */
    fun onMediaPause()
    /** Toggle play/pause based on the current player state, with
     *  `system` reason stamped. */
    fun onMediaTogglePlayPause()
    /** Queue-aware skip-next with `system` reason stamped. */
    fun onMediaSkipNext()
    /** Queue-aware skip-previous with `system` reason stamped. */
    fun onMediaSkipPrevious()

    /**
     * Record a granular Media3 audio-focus diagnostic. The lib does NOT
     * change player response from here —
     * Media3's `setAudioAttributes(handleAudioFocus = true)` keeps
     * driving pause / duck / auto-resume. Resulting state changes reach
     * JS through the engine delegate, not the error channel.
     */
    fun onAudioFocusEvent(mapping: AudioFocusErrorMapping.Mapping)

    /**
     * Record an output-route diagnostic on headphone unplug / A2DP disconnect / USB DAC
     * removal. The lib does NOT pause the player here —
     * `setHandleAudioBecomingNoisy(true)` on the bound player already
     * routes the broadcast through Media3's own pause logic. The
     * handler must not report this expected pause as a playback error.
     */
    fun onBecomingNoisy()
  }

}
