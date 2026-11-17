package com.margelo.nitro.queueplayer

import android.content.Intent
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors

/**
 * `MediaLibrarySession.Callback` for [PlaybackService]. Extracted
 * from the service class to keep it lean — the service now owns
 * the lifecycle wiring while this class owns the controller-facing
 * protocol.
 *
 * Lifecycle: one instance per `MediaLibrarySession`, constructed in
 * [PlaybackService.onCreate] and discarded in `onDestroy`. Reads
 * dynamic state (the registered command handler, media-button
 * dispatcher) via lambda providers so the service can hot-swap
 * those references without rebuilding the callback.
 *
 * Threading: every override runs on the service's main looper.
 * Library-callback futures are immediate ([Futures.immediateFuture])
 * — the [BrowseDataProvider] cache is in-memory + lock-free, no
 * async work needed.
 */
@UnstableApi
internal class PlaybackServiceCallback(
  private val environment: Environment,
) : MediaLibrarySession.Callback {

  /**
   * Dependency surface the callback reads through. Cleanly separates
   * the callback's protocol from [PlaybackService]'s lifecycle —
   * tests can fake this without spinning up a service.
   */
  internal interface Environment {
    /** Native cache for the consumer's browse snapshot. */
    val browseDataProvider: BrowseDataProvider

    /** Per-query cache of JS-supplied search results. */
    val searchResultCache: SearchResultCache

    /** The currently-registered transport-command handler — `null`
     *  before `TrackPlayer.configureInternal` wires itself in. */
    fun commandHandler(): PlaybackService.MediaSessionCommandHandler?

    /** The current media-button dispatcher — `null` before a handler
     *  is registered. */
    fun mediaButtonDispatch(): MediaButtonDispatch?

    /** Translate the lib's queue state into the [Player.Commands] set
     *  controllers should see. Only the skip pair greys out (the
     *  repeat-mode + queue-position arithmetic disallows it); every
     *  other command — play/pause, seek, etc. — is preserved so the
     *  API 33+ System UI derives the play/pause button + the draggable
     *  seek scrubber from the session's transport commands. */
    fun playerCommandsFor(
      capability: SkipCapability
    ): Player.Commands

    /** Invoked when a controller from a known headless-eligible
     *  package (Android Auto, Wear OS, Google Assistant) connects.
     *  Production wires the React-context cold-start through here so
     *  "Hey Google, play …" works from a phone with the app killed.
     *  Default no-op so callers without a wired service fall
     *  through harmlessly. */
    fun onHeadlessControllerConnect(packageName: String) {}

    /** Invoked when a controller's `MediaBrowser.search(query, …)`
     *  arrives and the [searchResultCache] doesn't yet have an
     *  answer. Production wires this to the JS-side `onSearchRequest`
     *  callback that fills the cache + triggers
     *  `notifySearchResultChanged`. Default no-op. */
    fun onSearchQueryReceived(query: String) {}

    /** Resolve a structured voice-search request (Google Assistant,
     *  Android Auto voice tile, future Siri intent extension) into
     *  the playable [MediaItem] list to hand to the player. The
     *  JS-side wrapper additionally calls `setQueue` + `play` so the
     *  lib's authoritative track list stays in sync; the returned
     *  list is what Media3 hands back to the requesting controller.
     *
     *  Default returns an empty list — when no JS handler is
     *  registered, the controller's "play X" voice command surfaces
     *  as "no results". */
    fun onPlayFromSearchRequest(
      request: MediaSearchRequest,
      append: Boolean = false
    ): ListenableFuture<List<MediaItem>> =
      Futures.immediateFuture(emptyList())

    /** Resolve a tap on a browse-leaf media id into the playable
     *  [MediaItem] list. Same JS-wrapper contract as
     *  [onPlayFromSearchRequest] — JS calls `setQueue` + `play`.
     *
     *  Default returns an empty list. */
    fun onPlayFromIdRequest(
      mediaId: String,
      append: Boolean = false
    ): ListenableFuture<List<MediaItem>> =
      Futures.immediateFuture(emptyList())

    /** Browse-leaf tap variant that is fire-and-forget: invoke the
     *  JS-registered `onPlayFromIdRequest` callback so the consumer
     *  can resolve the tracks and call `setQueue(tracks, startAt) +
     *  play()` itself, but do NOT sync the lib's authoritative tracks
     *  state from the resolved list. The JS-side `setQueue` is the
     *  only queue writer on this path; applying the resolved tracks
     *  a second time inside the lib would reset `currentTrackIndex`
     *  to 0 even though playback correctly starts at the tapped
     *  track's position. Default no-op. */
    fun onPlayFromIdRequestNoSync(mediaId: String) {}

    /** Record a `parentId` whose `onGetChildren` arrived before any
     *  snapshot had landed. The caller surfaces an empty children
     *  list to the controller; once the consumer's
     *  `setBrowseSnapshot` fires, the [PlaybackService] drains the
     *  queue + emits `notifyChildrenChanged` so the controller
     *  re-fetches with real data. Default no-op so tests + setups
     *  without a queue can fall through harmlessly. */
    fun enqueuePendingBrowseRequest(parentId: String) {}

    /** Resolve a `parentId` that the snapshot does NOT contain via
     *  the JS-registered `onBrowseRequest` callback. Used when a
     *  consumer ships a flat browse-snapshot at the top level + lets
     *  drill-downs lazy-fetch (e.g. "Albums" → JS returns an
     *  album list on tap). Default returns an empty list. */
    fun onBrowseRequestForChildren(
      parentId: String
    ): ListenableFuture<List<BrowseItem>> =
      Futures.immediateFuture(emptyList())

    /** Invoked when the LAST headless-eligible controller (Android
     *  Auto, Wear OS, Google Assistant) disconnects. Mirrors iOS
     *  scene-detach. Default no-op. */
    fun onHeadlessControllerDisconnect() {}

    /** Invoked when the FIRST headless-eligible controller of this
     *  service-lifetime session attaches. Subsequent controllers
     *  (Wear joining after Auto, Assistant binding for voice search)
     *  do not re-fire. Mirrors iOS scene-attach. Default no-op. */
    fun onFirstHeadlessControllerAttached() {}

    /** Build the lib-owned root `MediaItem` returned from
     *  `onGetLibraryRoot`. mediaId is the fixed [LIB_ROOT_ID]; title
     *  is the consumer app's display label when available so the
     *  AA browse drawer reads correctly. Override in production wires
     *  the PackageManager + cached label; the default constructs a
     *  generic "Library" item suitable for tests. */
    fun libraryRootItem(): MediaItem =
      MediaItemMapper.toMediaItem(
        BrowseItem(
          id = LIB_ROOT_ID,
          title = "Library",
          subtitle = null,
          artworkUrl = null,
          playable = false,
          hasChildren = true
        )
      )
  }

  // FIRST/LAST tracking for [Environment.onFirstHeadlessControllerAttached]
  // + [Environment.onHeadlessControllerDisconnect].
  private val headlessControllerCount =
    java.util.concurrent.atomic.AtomicInteger(0)

  // region — MediaSession.Callback (transport)

  override fun onAddMediaItems(
    mediaSession: MediaSession,
    controller: MediaSession.ControllerInfo,
    mediaItems: MutableList<MediaItem>
  ): ListenableFuture<MutableList<MediaItem>> {
    // Add-to-queue dispatch: Assistant's "queue up X by Y" arrives as
    // a search request, and "add this album" arrives as a mediaId-only
    // item. Both route through the JS handler to resolve the actual
    // track list, which Media3 then appends to the existing queue.
    // Browse-leaf tap is a SET (replace), not an ADD — that path is
    // handled in `onSetMediaItems` and uses a fire-and-forget noop
    // result so the JS-side setQueue is the only queue writer.
    val unresolved = mediaItems.firstOrNull { it.localConfiguration == null }
    if (unresolved != null) {
      val requestMetadata = unresolved.requestMetadata
      val searchQuery = requestMetadata.searchQuery?.takeIf { it.isNotEmpty() }
      if (searchQuery != null) {
        val request = VoiceSearchExtrasParser.parse(
          searchQuery,
          requestMetadata.extras
        )
        return Futures.transform(
          environment.onPlayFromSearchRequest(request, append = true),
          { items -> items.toMutableList() },
          MoreExecutors.directExecutor()
        )
      }
      val mediaId = unresolved.mediaId.takeIf { it.isNotEmpty() }
      if (mediaId != null) {
        return Futures.transform(
          environment.onPlayFromIdRequest(mediaId, append = true),
          { items -> items.toMutableList() },
          MoreExecutors.directExecutor()
        )
      }
    }
    return super.onAddMediaItems(mediaSession, controller, mediaItems)
  }

  override fun onSetMediaItems(
    mediaSession: MediaSession,
    controller: MediaSession.ControllerInfo,
    mediaItems: MutableList<MediaItem>,
    startIndex: Int,
    startPositionMs: Long
  ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
    val unresolved = mediaItems.firstOrNull { it.localConfiguration == null }
    if (unresolved != null) {
      val requestMetadata = unresolved.requestMetadata
      val searchQuery = requestMetadata.searchQuery?.takeIf { it.isNotEmpty() }
      if (searchQuery != null) {
        // Voice search (Assistant): the JS-resolved track list is the
        // authoritative queue. Index 0 is the right entry — Assistant's
        // "play X" contract is "begin at the top of the resolved set."
        val request = VoiceSearchExtrasParser.parse(
          searchQuery,
          requestMetadata.extras
        )
        return Futures.transform(
          environment.onPlayFromSearchRequest(request),
          { items ->
            MediaSession.MediaItemsWithStartPosition(
              items,
              0,
              androidx.media3.common.C.TIME_UNSET
            )
          },
          MoreExecutors.directExecutor()
        )
      }
      val mediaId = unresolved.mediaId.takeIf { it.isNotEmpty() }
      if (mediaId != null) {
        // Browse-leaf tap (Android Auto row tap): the JS handler owns
        // the queue. It receives the mediaId, resolves the full track
        // list + correct start index, and calls `setQueue(tracks,
        // startAtIndex) + play()` itself — same pattern as iOS
        // CarPlay's row-tap path. Fire the dispatch + return a noop
        // result so Media3 doesn't overwrite the JS-driven queue with
        // its own `player.setMediaItems(items, 0, TIME_UNSET)` (which
        // would force playback at index 0 regardless of the tapped
        // track's position in the album).
        //
        // The dispatch is the no-sync variant: it invokes the JS
        // callback for its `setQueue(tracks, startAt)` side effect
        // and discards the resolved track list, so the lib never
        // re-applies the tracks (which would reset
        // `currentTrackIndex` to 0).
        // An empty result would clear the player and emit a transition
        // against the previous canonical index. Return an internal marker
        // that SessionCommandForwardingPlayer consumes as a successful no-op,
        // leaving the JS handler as the only queue writer.
        environment.onPlayFromIdRequestNoSync(mediaId)
        return Futures.immediateFuture(
          MediaSession.MediaItemsWithStartPosition(
            listOf(SessionCommandForwardingPlayer.JS_MANAGED_QUEUE_ITEM),
            androidx.media3.common.C.INDEX_UNSET,
            androidx.media3.common.C.TIME_UNSET
          )
        )
      }
    }
    return super.onSetMediaItems(
      mediaSession, controller, mediaItems, startIndex, startPositionMs
    )
  }

  override fun onConnect(
    session: MediaSession,
    controller: MediaSession.ControllerInfo
  ): MediaSession.ConnectionResult {
    if (controller.packageName in HEADLESS_ELIGIBLE_PACKAGES) {
      val previousCount = headlessControllerCount.getAndIncrement()
      // A headless controller is now attached — record it in the process-wide
      // count the `isCarConnected()` getter reads.
      CarConnectionState.retain()
      environment.onHeadlessControllerConnect(controller.packageName)
      // Fire onCarConnect only on the FIRST headless controller of
      // this service-lifetime session — subsequent connects (Wear
      // joining after Auto, Assistant binding for voice search)
      // share the running session.
      if (previousCount == 0) {
        environment.onFirstHeadlessControllerAttached()
      }
    }
    // Always set BOTH session AND player commands. Setting only
    // player commands while returning an AcceptedResultBuilder makes
    // Media3's legacy MediaBrowserCompat bridge resolve `onGetRoot`
    // to null — Android Auto's gearhead logs `MediaBrowserService:
    // No root for client …` and enters a bind/unbind retry storm
    // that exhausts the service. See androidx/media#1774.
    //
    // The session-command default is the same set Media3's default
    // `onConnect` would emit, so phone-side controllers (in-app
    // MediaController, lock-screen, BT AVRCP) behave identically to
    // before. The skip-capability filtering on the player-commands
    // side is preserved unchanged.
    val builder = MediaSession.ConnectionResult.AcceptedResultBuilder(session)
      .setAvailableSessionCommands(
        MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
      )
    val handler = environment.commandHandler()
    if (handler != null) {
      builder.setAvailablePlayerCommands(
        environment.playerCommandsFor(handler.getCachedSkipCapability())
      )
    }
    return builder.build()
  }

  override fun onDisconnected(
    session: MediaSession,
    controller: MediaSession.ControllerInfo
  ) {
    if (controller.packageName in HEADLESS_ELIGIBLE_PACKAGES) {
      // Snapshot the previous count BEFORE the decrement so we can
      // gate the LAST-disconnect fire on a genuine `>0 → 0`
      // transition. A stray disconnect on a controller that was never
      // tracked via onConnect (e.g. service-restart edge) would
      // otherwise have driven the counter negative; the prior version
      // reset to 0 + fired onHeadlessControllerDisconnect, which
      // surfaced a phantom LAST disconnect with no real attach behind
      // it. With this guard, the fire only happens when we actually
      // transitioned from at-least-one to zero attached controllers.
      val previousCount = headlessControllerCount.get()
      val remaining = headlessControllerCount.decrementAndGet()
      if (remaining < 0) {
        // Untracked controller — snap the counter back to a sane
        // floor and DO NOT fire onHeadlessControllerDisconnect.
        headlessControllerCount.set(0)
      } else if (previousCount > 0 && remaining == 0) {
        environment.onHeadlessControllerDisconnect()
      }
      // Drop this controller from the process-wide connection count the
      // `isCarConnected()` getter reads (floor-guarded against a stray
      // disconnect, so it can't go negative).
      CarConnectionState.release()
    }
    super.onDisconnected(session, controller)
  }

  override fun onMediaButtonEvent(
    session: MediaSession,
    controllerInfo: MediaSession.ControllerInfo,
    intent: Intent
  ): Boolean {
    val dispatch = environment.mediaButtonDispatch() ?: return false
    return dispatch.handle(intent, RemoteCommandDeduper.Channel.SESSION_KEY)
  }

  /**
   * Cold-start playback resumption: a hardware / Bluetooth media button that
   * wakes the service while nothing is loaded. Returns an empty queue by
   * design — the lib does not reconstruct a queue here; a consumer that wants
   * resume-from-cold restores its own queue on launch via `setQueue`. The
   * override exists (rather than being omitted) because Media3 requires a
   * declared `MediaButtonReceiver` to pair with an `onPlaybackResumption`.
   */
  override fun onPlaybackResumption(
    mediaSession: MediaSession,
    controller: MediaSession.ControllerInfo,
    isForPlayback: Boolean
  ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
    Futures.immediateFuture(
      MediaSession.MediaItemsWithStartPosition(
        emptyList(),
        androidx.media3.common.C.INDEX_UNSET,
        androidx.media3.common.C.TIME_UNSET
      )
    )

  // endregion

  // region — MediaLibrarySession.Callback (browse + library)

  override fun onGetLibraryRoot(
    session: MediaLibrarySession,
    browser: MediaSession.ControllerInfo,
    params: LibraryParams?
  ): ListenableFuture<LibraryResult<MediaItem>> {
    // ALWAYS return the same library-owned root MediaItem with a
    // stable mediaId. Android Auto's gearhead caches the first
    // `onGetLibraryRoot` response across the bind session — mutating
    // the root id after the snapshot lands leaves gearhead subscribed
    // to a stale id, and our `notifyChildrenChanged` (fired on the
    // new id) never delivers. Canonical pattern: fixed id forever,
    // independent of whether the consumer has called
    // `setBrowseSnapshot` yet.
    //
    // Attach content-style extras so gearhead renders the root's
    // browsable children as list rows (Apple Music + Spotify shape).
    // Without these, gearhead picks an undocumented default that can
    // vary by gearhead version. CONTENT_STYLE_SUPPORTED=true signals
    // to AA that the app participates in the content-style protocol.
    val rootItem = environment.libraryRootItem()
    val styledParams = LibraryParams.Builder()
      .setExtras(rootContentStyleExtras())
      .build()
    return Futures.immediateFuture(LibraryResult.ofItem(rootItem, styledParams))
  }

  private fun rootContentStyleExtras(): android.os.Bundle =
    android.os.Bundle().apply {
      putBoolean(CONTENT_STYLE_SUPPORTED, true)
      putInt(CONTENT_STYLE_BROWSABLE_HINT, CONTENT_STYLE_LIST_ITEM)
      putInt(CONTENT_STYLE_PLAYABLE_HINT, CONTENT_STYLE_LIST_ITEM)
      // Signals to Android Auto that the app handles
      // `MediaBrowserCompat.search(...)`. Gearhead surfaces a floating
      // search affordance once the flag is set; the actual results
      // flow through the existing `onSearch` / `onGetSearchResult`
      // callbacks that route to the consumer's `onSearchRequest`
      // handler.
      putBoolean(MEDIA_SEARCH_SUPPORTED, true)
    }

  /** One-shot flag: AA has no tab-icon API today
   *  (`androidx/media#646`); first time a consumer ships an icon on a
   *  section, surface the limitation in logcat so they know the field
   *  is ignored on this platform. The CarPlay side honours icons. */
  @Volatile
  private var iconLimitationLogged = false

  private fun maybeLogIconLimitation(sections: Array<BrowseSection>) {
    if (iconLimitationLogged) return
    if (sections.none { it.icon != null }) return
    iconLimitationLogged = true
    android.util.Log.i(
      AUTO_LOG_TAG,
      "BrowseSection.icon set on one or more sections but Android Auto " +
        "exposes no tab-icon API (androidx/media#646). Icons render on " +
        "CarPlay only; AA shows section titles as text-only tabs."
    )
  }

  @Volatile
  private var overCapLogged = false

  private fun warnOnRootTabsOverCap(actual: Int) {
    if (overCapLogged) return
    overCapLogged = true
    android.util.Log.w(
      AUTO_LOG_TAG,
      "BrowseSnapshot has $actual sections; Android Auto caps top-tabs " +
        "at $MAX_ROOT_TABS. Truncating to the first $MAX_ROOT_TABS. " +
        "Fold extras via in-tab drilldown for full coverage."
    )
  }

  // Browse parentIds already warned about exceeding the per-node cap, so the
  // dev-facing log fires once per offending node rather than on every re-fetch.
  private val overCapNodesLogged =
    java.util.Collections.synchronizedSet(HashSet<String>())

  /** Cap a browse node's children at [MAX_ITEMS_PER_NODE]. CarPlay hard-caps a
   *  list at `CPListTemplate.maximumItemCount` (500) and Android Auto requests
   *  whole nodes, so an over-cap node is truncated with a loud warning naming
   *  the offending parentId + count — the consumer should fold larger catalogs
   *  into a hierarchy (A-Z buckets, by-artist) rather than a flat mega-list. */
  private fun capNodeChildren(parentId: String, items: List<BrowseItem>): List<BrowseItem> {
    if (items.size <= MAX_ITEMS_PER_NODE) return items
    if (overCapNodesLogged.add(parentId)) {
      android.util.Log.w(
        AUTO_LOG_TAG,
        "Browse node '$parentId' returned ${items.size} items, over the " +
          "$MAX_ITEMS_PER_NODE per-node cap (CarPlay CPListTemplate.maximumItemCount; " +
          "Android Auto requests whole nodes). Truncating to the first " +
          "$MAX_ITEMS_PER_NODE — use a hierarchical browse (A-Z / by-artist) for larger catalogs."
      )
    }
    return items.take(MAX_ITEMS_PER_NODE)
  }

  override fun onGetItem(
    session: MediaLibrarySession,
    browser: MediaSession.ControllerInfo,
    mediaId: String
  ): ListenableFuture<LibraryResult<MediaItem>> {
    if (mediaId == LIB_ROOT_ID) {
      return Futures.immediateFuture(
        LibraryResult.ofItem(environment.libraryRootItem(), null)
      )
    }
    val provider = environment.browseDataProvider
    val snapshot = provider.getSnapshot()
      ?: return Futures.immediateFuture(
        LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
      )
    if (mediaId == snapshot.rootId) {
      return Futures.immediateFuture(
        LibraryResult.ofItem(environment.libraryRootItem(), null)
      )
    }
    provider.getSection(mediaId)?.let { section ->
      val item = MediaItemMapper.toMediaItem(
        BrowseItem(
          id = section.id,
          title = section.title,
          subtitle = null,
          artworkUrl = null,
          playable = false,
          hasChildren = true
        )
      )
      return Futures.immediateFuture(LibraryResult.ofItem(item, null))
    }
    provider.getItem(mediaId)?.let { item ->
      return Futures.immediateFuture(
        LibraryResult.ofItem(MediaItemMapper.toMediaItem(item), null)
      )
    }
    return Futures.immediateFuture(
      LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
    )
  }

  override fun onGetChildren(
    session: MediaLibrarySession,
    browser: MediaSession.ControllerInfo,
    parentId: String,
    page: Int,
    pageSize: Int,
    params: LibraryParams?
  ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
    val provider = environment.browseDataProvider
    val snapshot = provider.getSnapshot()
    // Lib-owned root: always served by us, never delegated to the
    // consumer's snapshot rootId field. Bridges to the snapshot's
    // top-level sections when one is loaded; surfaces a single
    // "Loading library…" placeholder otherwise so gearhead has
    // something to render besides "No items".
    if (parentId == LIB_ROOT_ID) {
      if (snapshot == null) {
        environment.enqueuePendingBrowseRequest(parentId)
        val loading = MediaItemMapper.toMediaItem(
          BrowseItem(
            id = LIB_ROOT_LOADING_ID,
            title = "Loading library…",
            subtitle = null,
            artworkUrl = null,
            playable = false,
            hasChildren = false
          )
        )
        return Futures.immediateFuture(
          LibraryResult.ofItemList(ImmutableList.of(loading), params)
        )
      }
      val rootChildren = provider.getChildren(snapshot.rootId, page, pageSize)
      if (rootChildren.isEmpty()) {
        return Futures.immediateFuture(
          LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
        )
      }
      // Cap at MAX_ROOT_TABS — Android Auto's top-tab strip truncates
      // anything past 4 anyway; capping in-lib makes the truncation
      // explicit + logs a one-time warning for consumers shipping
      // over-cap snapshots.
      val capped = if (rootChildren.size > MAX_ROOT_TABS) {
        warnOnRootTabsOverCap(rootChildren.size)
        rootChildren.take(MAX_ROOT_TABS)
      } else {
        rootChildren
      }
      maybeLogIconLimitation(snapshot.sections)
      val mapped = ImmutableList.copyOf(MediaItemMapper.toMediaItems(capped))
      return Futures.immediateFuture(LibraryResult.ofItemList(mapped, params))
    }
    if (snapshot == null) {
      // Cold-start race for a non-root parentId: queue + return empty
      // so the next snapshot push fans out a `notifyChildrenChanged`.
      environment.enqueuePendingBrowseRequest(parentId)
      return Futures.immediateFuture(
        LibraryResult.ofItemList(ImmutableList.of(), params)
      )
    }
    val effectiveParentId = parentId
    if (!provider.isRoot(effectiveParentId) && provider.getSection(effectiveParentId) == null) {
      // Snapshot present but [parentId] isn't in the cache — dispatch
      // the JS-registered `onBrowseRequest` to lazy-fetch children.
      // Common path for consumers that ship a top-level snapshot +
      // expect drilldowns to resolve on demand.
      val resolveFuture = environment.onBrowseRequestForChildren(effectiveParentId)
      return Futures.transform(
        resolveFuture,
        { items ->
          val capped = capNodeChildren(effectiveParentId, items)
          val mapped = ImmutableList.copyOf(MediaItemMapper.toMediaItems(capped))
          LibraryResult.ofItemList(mapped, params)
        },
        MoreExecutors.directExecutor()
      )
    }
    val children = capNodeChildren(
      effectiveParentId,
      provider.getChildren(effectiveParentId, page, pageSize)
    )
    val mapped = ImmutableList.copyOf(MediaItemMapper.toMediaItems(children))
    return Futures.immediateFuture(LibraryResult.ofItemList(mapped, params))
  }

  override fun onSearch(
    session: MediaLibrarySession,
    browser: MediaSession.ControllerInfo,
    query: String,
    params: LibraryParams?
  ): ListenableFuture<LibraryResult<Void>> {
    // Spec contract per Media3: `onSearch` returns Void on success;
    // results are delivered later via
    // `notifySearchResultChanged(controller, query, count, params)`
    // and pulled by the controller's subsequent `getSearchResult`
    // call. Trigger the JS dispatch hook so the consumer's
    // `onSearchRequest` callback can fill the cache. The cache might
    // already hold a prior answer for this exact query — emit
    // notifySearchResultChanged immediately in that case so the
    // controller pages the cached results without round-tripping
    // through JS again.
    val cache = environment.searchResultCache
    if (cache.hasResults(query)) {
      session.notifySearchResultChanged(browser, query, cache.count(query), params)
    } else {
      environment.onSearchQueryReceived(query)
    }
    return Futures.immediateFuture(LibraryResult.ofVoid(params))
  }

  override fun onGetSearchResult(
    session: MediaLibrarySession,
    browser: MediaSession.ControllerInfo,
    query: String,
    page: Int,
    pageSize: Int,
    params: LibraryParams?
  ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
    val cache = environment.searchResultCache
    if (!cache.hasResults(query)) {
      // No JS answer yet. Returning an empty page is the standard
      // Media3 "still waiting" shape; the controller will re-pull
      // when `notifySearchResultChanged` fires.
      return Futures.immediateFuture(
        LibraryResult.ofItemList(ImmutableList.of(), params)
      )
    }
    val items = cache.getPage(query, page, pageSize)
    val mapped = ImmutableList.copyOf(MediaItemMapper.toMediaItems(items))
    return Futures.immediateFuture(LibraryResult.ofItemList(mapped, params))
  }

  // NOTE: `onSubscribe` + `onUnsubscribe` are intentionally NOT
  // overridden here. The default Media3 implementation returns
  // `LibraryResult.ofVoid()` after calling `onGetItem` to validate
  // the parentId — that's the SUCCESS path that registers the
  // controller in `MediaLibrarySessionImpl`'s internal subscription
  // map, which is what gates downstream `notifyChildrenChanged`
  // delivery. Custom overrides that returned `RESULT_ERROR_*`
  // silently dropped every change-notify for legacy MediaBrowserCompat
  // controllers (Android Auto's gearhead included).
  // Refs: androidx/media#561, #644 confirm the contract.

  // endregion

  internal companion object {
    /** Logcat tag for automotive-surface warnings emitted from this
     *  callback. Part of the `QueuePlayer.<area>` hierarchy used
     *  across the lib's Android sources. */
    private const val AUTO_LOG_TAG = "QueuePlayer.Auto"

    /**
     * Controller package names that warrant a headless cold-start
     * of the consumer's React context — Android Auto's projection
     * connector, Wear OS's companion app, Google Assistant. The
     * `onConnect` hook calls
     * [Environment.onHeadlessControllerConnect] for these so the
     * service can drive a JS-task spawn that boots the bridge before
     * Media3's framework dispatches the controller's first action.
     */
    val HEADLESS_ELIGIBLE_PACKAGES: Set<String> = setOf(
      "com.google.android.projection.gearhead",
      "com.google.android.wearable.app",
      "com.google.android.googlequicksearchbox",
      // Android Auto's voice-tile Assistant, distinct from the
      // phone-side Google app (`googlequicksearchbox`). Triggers the
      // same headless cold-wake path so "Hey Google, play …" from
      // inside the car works on a phone with the app killed.
      "com.google.android.carassistant"
    )

    /**
     * Fixed, lib-owned root id returned from `onGetLibraryRoot` for
     * EVERY controller, regardless of whether the consumer has called
     * `setBrowseSnapshot` yet. Stable across the service lifetime —
     * Android Auto's gearhead caches the first root id it sees and
     * keys subscriptions on it, so mutating the id later (e.g. when
     * a real snapshot lands) leaves the controller subscribed to a
     * stale id and `notifyChildrenChanged` fired on the new id never
     * delivers.
     */
    internal const val LIB_ROOT_ID = "rnqp.root"

    /**
     * Synthetic placeholder MediaItem id used as the only child of
     * [LIB_ROOT_ID] when the consumer hasn't pushed a
     * [BrowseSnapshot] yet. Gives gearhead something to render
     * besides "No items" while the JS bridge boots + the snapshot
     * lands. Replaced by the real top-level children on the next
     * `notifyChildrenChanged`.
     */
    internal const val LIB_ROOT_LOADING_ID = "rnqp.root.loading"

    /**
     * Maximum number of root-level browsable children Android Auto
     * renders as top tabs. Apple Music + Spotify ship 4 tabs; gearhead
     * truncates anything beyond that. Lib matches by capping the
     * BrowseSnapshot's sections to this number on its way to the
     * gearhead-facing children list.
     */
    internal const val MAX_ROOT_TABS = 4

    /**
     * Per-browsable-node item cap. CarPlay hard-caps a `CPListTemplate` at
     * `maximumItemCount` (500 on current iOS — an Apple-exclusive private
     * capability handles larger lists). Android Auto requests whole nodes, so
     * the lib applies the same ceiling here for parity. Consumers fold larger
     * catalogs into a hierarchy (A-Z / by-artist / by-genre) instead of a flat
     * list past this size.
     */
    internal const val MAX_ITEMS_PER_NODE = 500

    /** Legacy `MediaBrowserCompat` content-style extras keys.
     *  Media3's session bridge re-exports the same values, so using the
     *  string constants directly stays stable across Media3 versions —
     *  the underlying keys are documented at
     *  developer.android.com/training/cars/media#default-content-style. */
    internal const val CONTENT_STYLE_SUPPORTED =
      "android.media.browse.CONTENT_STYLE_SUPPORTED"
    internal const val CONTENT_STYLE_BROWSABLE_HINT =
      "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
    internal const val CONTENT_STYLE_PLAYABLE_HINT =
      "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
    internal const val CONTENT_STYLE_LIST_ITEM = 1

    /** Root extras flag: when `true`, gearhead surfaces a search
     *  affordance ("Search artists, albums, songs" mic / magnifying-
     *  glass entry). The actual search dispatch flows through
     *  `MediaBrowserCompat.search` → Media3
     *  `MediaLibrarySession.Callback.onSearch`. */
    internal const val MEDIA_SEARCH_SUPPORTED =
      "android.media.browse.SEARCH_SUPPORTED"
  }
}
