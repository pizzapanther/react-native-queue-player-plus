package com.margelo.nitro.queueplayer

import androidx.media3.common.util.UnstableApi
import com.margelo.nitro.core.Promise
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/**
 * Single-slot registry for the JS-side automotive callbacks the
 * `MediaLibrarySession.Callback` dispatches into. Each slot holds at
 * most one callback at a time — re-registering replaces the prior
 * callback, matching the JS-side `registerPlaybackService` factory
 * semantics where the latest call wins.
 *
 * Callable from any thread — slots are [MutableStateFlow]-backed so
 * a Media3 callback running on the library executor can invoke the
 * registered callback without contention against a JS-thread
 * registration. Each slot also exposes a suspending `await*Callback`
 * that resumes the moment a callback lands (or returns the current
 * value if one is already registered) — callers no longer poll the
 * slot via `delay(50)` loops.
 *
 * Disposal: each `register*` returns a disposer lambda. Calling the
 * disposer clears the slot iff the registered callback is still
 * the active one (idempotent + safe against late dispose-after-
 * replace).
 */
@UnstableApi
internal class BrowseCallbackRegistry {

  // Browse-tree drilldown — invoked when a controller calls
  // `getChildren(parentId)` and the parent isn't in the cache.
  // Returns a Nitro Promise that JS resolves with the children
  // array. The double-Promise wrapping is Nitro's shape for an
  // async JS callback.
  private val onBrowseRequestSlot =
    MutableStateFlow<((parentId: String) -> Promise<Promise<Array<BrowseItem>>>)?>(null)

  // Search query — invoked when a controller calls `search(query)`
  // and the cache doesn't have an answer.
  private val onSearchRequestSlot =
    MutableStateFlow<((query: String) -> Promise<Promise<Array<BrowseItem>>>)?>(null)

  // "Play from search" — invoked when a controller resolves a
  // search result to playback (Auto's tap-on-search-result, voice
  // search). Returns a TrackItem array to set the queue from.
  private val onPlayFromSearchRequestSlot =
    MutableStateFlow<((request: MediaSearchRequest) -> Promise<Promise<Array<TrackItem>>>)?>(null)

  // "Play from media id" — invoked when a controller resolves a
  // browse-leaf to playback (tap on a song in Auto's browse tree).
  private val onPlayFromIdRequestSlot =
    MutableStateFlow<((mediaId: String) -> Promise<Promise<Array<TrackItem>>>)?>(null)

  // Connection lifecycle — fired when a known headless-eligible
  // controller (Auto / Wear / Assistant) binds to the session.
  private val onCarConnectSlot = MutableStateFlow<(() -> Unit)?>(null)
  private val onCarDisconnectSlot = MutableStateFlow<(() -> Unit)?>(null)

  // Service-bind lifecycle — fired on every successful JS-side
  // `onServiceConnected`. The `ServiceReadyReason` argument carries
  // the lib's reason code; the Nitrogen bridge marshals it as the
  // underlying string value of the TS enum.
  private val onServiceReadySlot =
    MutableStateFlow<((reason: ServiceReadyReason) -> Unit)?>(null)

  fun registerOnBrowseRequest(
    callback: (parentId: String) -> Promise<Promise<Array<BrowseItem>>>
  ): () -> Unit = registerSlot(onBrowseRequestSlot, callback)

  fun registerOnSearchRequest(
    callback: (query: String) -> Promise<Promise<Array<BrowseItem>>>
  ): () -> Unit = registerSlot(onSearchRequestSlot, callback)

  fun registerOnPlayFromSearchRequest(
    callback: (request: MediaSearchRequest) -> Promise<Promise<Array<TrackItem>>>
  ): () -> Unit = registerSlot(onPlayFromSearchRequestSlot, callback)

  fun registerOnPlayFromIdRequest(
    callback: (mediaId: String) -> Promise<Promise<Array<TrackItem>>>
  ): () -> Unit = registerSlot(onPlayFromIdRequestSlot, callback)

  fun registerOnCarConnect(callback: () -> Unit): () -> Unit =
    registerSlot(onCarConnectSlot, callback)

  fun registerOnCarDisconnect(callback: () -> Unit): () -> Unit =
    registerSlot(onCarDisconnectSlot, callback)

  fun registerOnServiceReady(
    callback: (reason: ServiceReadyReason) -> Unit
  ): () -> Unit = registerSlot(onServiceReadySlot, callback)

  fun browseRequestCallback() = onBrowseRequestSlot.value
  fun searchRequestCallback() = onSearchRequestSlot.value
  fun playFromSearchRequestCallback() = onPlayFromSearchRequestSlot.value
  fun playFromIdRequestCallback() = onPlayFromIdRequestSlot.value
  fun carConnectCallback() = onCarConnectSlot.value
  fun carDisconnectCallback() = onCarDisconnectSlot.value
  fun serviceReadyCallback() = onServiceReadySlot.value

  /** Suspend until a callback is registered for [onBrowseRequestSlot],
   *  returning the current registration if one already exists. */
  suspend fun awaitBrowseRequestCallback() =
    onBrowseRequestSlot.filterNotNull().first()

  suspend fun awaitPlayFromSearchRequestCallback() =
    onPlayFromSearchRequestSlot.filterNotNull().first()

  suspend fun awaitPlayFromIdRequestCallback() =
    onPlayFromIdRequestSlot.filterNotNull().first()

  suspend fun awaitCarConnectCallback() =
    onCarConnectSlot.filterNotNull().first()

  fun clear() {
    onBrowseRequestSlot.value = null
    onSearchRequestSlot.value = null
    onPlayFromSearchRequestSlot.value = null
    onPlayFromIdRequestSlot.value = null
    onCarConnectSlot.value = null
    onCarDisconnectSlot.value = null
    onServiceReadySlot.value = null
  }

  /** Atomically replace the slot's value with [callback], returning
   *  a disposer that clears the slot iff [callback] is still the
   *  registered value. */
  private fun <T : Any> registerSlot(
    slot: MutableStateFlow<T?>,
    callback: T
  ): () -> Unit {
    slot.value = callback
    return { slot.compareAndSet(callback, null) }
  }
}
