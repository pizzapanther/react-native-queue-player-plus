package com.margelo.nitro.queueplayer

import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/**
 * Robolectric coverage for [PlaybackServiceCallback]'s
 * `MediaLibrarySession.Callback` browse-side surface —
 * `onGetLibraryRoot`, `onGetItem`, `onGetChildren`. Drives the
 * [BrowseDataProvider] cache on the live service and asserts the
 * `LibraryResult` shapes the callback returns.
 *
 * Search callbacks (`onSearch`, `onGetSearchResult`) live in their
 * own dedicated test file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@UnstableApi
class PlaybackServiceCallbackBrowseTest {

  private lateinit var controller: ServiceController<PlaybackService>
  private lateinit var service: PlaybackService
  private lateinit var callback: PlaybackServiceCallback
  private lateinit var session: MediaLibraryService.MediaLibrarySession
  private lateinit var browser: MediaSession.ControllerInfo

  private fun item(
    id: String,
    title: String = "T-$id",
    playable: Boolean = true,
    hasChildren: Boolean? = null
  ) = BrowseItem(
    id = id,
    title = title,
    subtitle = null,
    artworkUrl = null,
    playable = playable,
    hasChildren = hasChildren
  )

  @Before
  fun setUp() {
    controller = Robolectric.buildService(PlaybackService::class.java).create()
    service = controller.get()
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val intent = Intent(context.applicationContext, PlaybackService::class.java)
      .setAction(PlaybackService.ACTION_LOCAL_BIND)
    val binder = service.onBind(intent) as PlaybackService.LocalBinder
    callback = PlaybackServiceCallback(service.callbackEnvironment)
    session = binder.session
    browser = MediaSession.ControllerInfo.createTestOnlyControllerInfo(
      "test-browser", 0, 0, 0, 0, false, android.os.Bundle.EMPTY, false
    )
  }

  @After
  fun tearDown() {
    controller.destroy()
  }

  // --- onGetLibraryRoot

  @Test
  fun `onGetLibraryRoot always returns the lib-owned stable root id`() {
    // The lib owns the AA-facing root id. Returning a stable id
    // regardless of snapshot state is required because Android
    // Auto's gearhead caches the first root id it sees and keys
    // subscriptions on it; mutating the id later leaves the
    // controller subscribed to a stale id and any later
    // `notifyChildrenChanged` fired on the new id is dropped.
    val result = callback.onGetLibraryRoot(session, browser, null).get()
    assertEquals(LibraryResult.RESULT_SUCCESS, result.resultCode)
    val rootItem = result.value!!
    assertEquals(PlaybackServiceCallback.LIB_ROOT_ID, rootItem.mediaId)
    assertEquals(true, rootItem.mediaMetadata.isBrowsable)
    assertEquals(false, rootItem.mediaMetadata.isPlayable)
  }

  @Test
  fun `onGetLibraryRoot returns the same lib root id after a snapshot lands`() {
    service.browseDataProvider.setSnapshot(
      BrowseSnapshot(
        rootId = "the-root",
        sections = arrayOf(
          BrowseSection(id = "a", title = "A", icon = null, artworkUrl = null, items = arrayOf(item("a-1")))
        )
      )
    )
    val result = callback.onGetLibraryRoot(session, browser, null).get()
    assertEquals(LibraryResult.RESULT_SUCCESS, result.resultCode)
    val rootItem = result.value!!
    assertEquals(PlaybackServiceCallback.LIB_ROOT_ID, rootItem.mediaId)
    assertEquals(true, rootItem.mediaMetadata.isBrowsable)
    assertEquals(false, rootItem.mediaMetadata.isPlayable)
  }

  // --- onGetChildren

  @Test
  fun `onGetChildren returns an empty list and queues the parentId when no snapshot is set`() {
    val result = callback.onGetChildren(
      session, browser, parentId = "any", page = 0, pageSize = 50, params = null
    ).get()
    assertEquals(LibraryResult.RESULT_SUCCESS, result.resultCode)
    assertTrue(
      "controller paints a loading state from an empty list rather than an error",
      result.value!!.isEmpty()
    )
    assertEquals(setOf("any"), service.pendingBrowseRequests.snapshotForTest())
  }

  @Test
  fun `onGetChildren with snapshot present does not enqueue a pending request`() {
    service.browseDataProvider.setSnapshot(
      BrowseSnapshot(
        rootId = "root",
        sections = arrayOf(
          BrowseSection(id = "a", title = "A", icon = null, artworkUrl = null, items = arrayOf(item("a-1")))
        )
      )
    )
    callback.onGetChildren(
      session, browser, parentId = "root", page = 0, pageSize = 50, params = null
    ).get()
    assertTrue(service.pendingBrowseRequests.snapshotForTest().isEmpty())
  }

  @Test
  fun `notifyChildrenChangedForParents drains the pending queue`() {
    callback.onGetChildren(
      session, browser, parentId = "root", page = 0, pageSize = 50, params = null
    ).get()
    callback.onGetChildren(
      session, browser, parentId = "section-a", page = 0, pageSize = 50, params = null
    ).get()
    assertEquals(
      setOf("root", "section-a"),
      service.pendingBrowseRequests.snapshotForTest()
    )

    service.notifyChildrenChangedForParents(emptySet())

    assertTrue(
      "drained set is empty after a single drain",
      service.pendingBrowseRequests.snapshotForTest().isEmpty()
    )
  }

  @Test
  fun `onGetChildren of rootId returns synthetic browsable section items`() {
    service.browseDataProvider.setSnapshot(
      BrowseSnapshot(
        rootId = "root",
        sections = arrayOf(
          BrowseSection(id = "recents", title = "Recently Played", icon = null, artworkUrl = null, items = arrayOf()),
          BrowseSection(id = "genres", title = "Genres", icon = null, artworkUrl = null, items = arrayOf())
        )
      )
    )
    val result = callback.onGetChildren(
      session, browser, parentId = "root", page = 0, pageSize = 50, params = null
    ).get()
    assertEquals(LibraryResult.RESULT_SUCCESS, result.resultCode)
    val items = result.value!!
    assertEquals(2, items.size)
    assertEquals("recents", items[0].mediaId)
    assertEquals("Recently Played", items[0].mediaMetadata.title)
    assertEquals(true, items[0].mediaMetadata.isBrowsable)
    assertEquals(false, items[0].mediaMetadata.isPlayable)
  }

  @Test
  fun `onGetChildren of a section id returns the section's items`() {
    service.browseDataProvider.setSnapshot(
      BrowseSnapshot(
        rootId = "root",
        sections = arrayOf(
          BrowseSection(
            id = "recents",
            title = "Recents",
            icon = null,
            artworkUrl = null, items = arrayOf(item("a"), item("b"))
          )
        )
      )
    )
    val result = callback.onGetChildren(
      session, browser, parentId = "recents", page = 0, pageSize = 50, params = null
    ).get()
    assertEquals(LibraryResult.RESULT_SUCCESS, result.resultCode)
    val items = result.value!!
    assertEquals(2, items.size)
    assertEquals("a", items[0].mediaId)
    assertEquals(true, items[0].mediaMetadata.isPlayable)
  }

  @Test
  fun `onGetChildren caps a section at MAX_ITEMS_PER_NODE`() {
    val many = Array(PlaybackServiceCallback.MAX_ITEMS_PER_NODE + 100) { item("x-$it") }
    service.browseDataProvider.setSnapshot(
      BrowseSnapshot(
        rootId = "root",
        sections = arrayOf(
          BrowseSection(id = "big", title = "Big", icon = null, artworkUrl = null, items = many)
        )
      )
    )
    // Android Auto requests whole nodes (pageSize = Int.MAX_VALUE); the cap must
    // apply regardless so a fat node can't dump thousands of rows on the host.
    val result = callback.onGetChildren(
      session, browser, parentId = "big", page = 0, pageSize = Int.MAX_VALUE, params = null
    ).get()
    assertEquals(LibraryResult.RESULT_SUCCESS, result.resultCode)
    assertEquals(PlaybackServiceCallback.MAX_ITEMS_PER_NODE, result.value!!.size)
  }

  @Test
  fun `onGetChildren leaves an at-cap section untouched`() {
    val exact = Array(PlaybackServiceCallback.MAX_ITEMS_PER_NODE) { item("x-$it") }
    service.browseDataProvider.setSnapshot(
      BrowseSnapshot(
        rootId = "root",
        sections = arrayOf(
          BrowseSection(id = "exact", title = "Exact", icon = null, artworkUrl = null, items = exact)
        )
      )
    )
    val result = callback.onGetChildren(
      session, browser, parentId = "exact", page = 0, pageSize = Int.MAX_VALUE, params = null
    ).get()
    assertEquals(PlaybackServiceCallback.MAX_ITEMS_PER_NODE, result.value!!.size)
  }

  @Test
  fun `onGetChildren of an unknown parentId dispatches to onBrowseRequestForChildren`() {
    service.browseDataProvider.setSnapshot(
      BrowseSnapshot(rootId = "root", sections = arrayOf())
    )
    val result = callback.onGetChildren(
      session, browser, parentId = "unknown", page = 0, pageSize = 50, params = null
    ).get()
    // Default Environment returns immediateFuture(emptyList()) — the
    // dispatch path resolves to a SUCCESS with an empty children list.
    // This test also locks the contract that callbackDispatchScope
    // does NOT require the test main thread (Robolectric drives via
    // .get() on Main; a Main-bound dispatcher would deadlock here).
    assertEquals(LibraryResult.RESULT_SUCCESS, result.resultCode)
    assertEquals(0, result.value!!.size)
  }

  @Test
  fun `onGetChildren of an unknown parentId maps consumer-supplied items into LibraryResult`() {
    service.browseDataProvider.setSnapshot(
      BrowseSnapshot(rootId = "root", sections = arrayOf())
    )
    val resolved = listOf(item("lazy-1"), item("lazy-2", "T-2"))
    val resolvingEnvironment = object : PlaybackServiceCallback.Environment {
      override val browseDataProvider get() = service.browseDataProvider
      override val searchResultCache get() = service.searchResultCache
      override fun commandHandler() = null
      override fun mediaButtonDispatch() = null
      override fun playerCommandsFor(
        capability: SkipCapability
      ): Player.Commands =
        service.playerCommandsFor(capability)
      override fun onBrowseRequestForChildren(
        parentId: String
      ): ListenableFuture<List<BrowseItem>> = Futures.immediateFuture(resolved)
    }
    val cb = PlaybackServiceCallback(resolvingEnvironment)
    val result = cb.onGetChildren(
      session, browser, parentId = "lazy", page = 0, pageSize = 50, params = null
    ).get()
    assertEquals(LibraryResult.RESULT_SUCCESS, result.resultCode)
    assertEquals(2, result.value!!.size)
    assertEquals("lazy-1", result.value!![0].mediaId)
    assertEquals("lazy-2", result.value!![1].mediaId)
  }

  @Test
  fun `onGetChildren caps the lazy onBrowseRequest path at MAX_ITEMS_PER_NODE`() {
    service.browseDataProvider.setSnapshot(
      BrowseSnapshot(rootId = "root", sections = arrayOf())
    )
    val resolved =
      (0 until PlaybackServiceCallback.MAX_ITEMS_PER_NODE + 100).map { item("lazy-$it") }
    val resolvingEnvironment = object : PlaybackServiceCallback.Environment {
      override val browseDataProvider get() = service.browseDataProvider
      override val searchResultCache get() = service.searchResultCache
      override fun commandHandler() = null
      override fun mediaButtonDispatch() = null
      override fun playerCommandsFor(
        capability: SkipCapability
      ): Player.Commands =
        service.playerCommandsFor(capability)
      override fun onBrowseRequestForChildren(
        parentId: String
      ): ListenableFuture<List<BrowseItem>> = Futures.immediateFuture(resolved)
    }
    val cb = PlaybackServiceCallback(resolvingEnvironment)
    val result = cb.onGetChildren(
      session, browser, parentId = "lazy-fat", page = 0, pageSize = Int.MAX_VALUE, params = null
    ).get()
    assertEquals(LibraryResult.RESULT_SUCCESS, result.resultCode)
    assertEquals(PlaybackServiceCallback.MAX_ITEMS_PER_NODE, result.value!!.size)
  }

  @Test
  fun `onGetChildren paginates by page + pageSize`() {
    val items = (1..7).map { item("i$it") }.toTypedArray()
    service.browseDataProvider.setSnapshot(
      BrowseSnapshot(
        rootId = "root",
        sections = arrayOf(BrowseSection(id = "all", title = "All", icon = null, artworkUrl = null, items = items))
      )
    )
    val page0 = callback.onGetChildren(
      session, browser, parentId = "all", page = 0, pageSize = 3, params = null
    ).get().value!!
    assertEquals(3, page0.size)
    assertEquals("i1", page0[0].mediaId)

    val page2 = callback.onGetChildren(
      session, browser, parentId = "all", page = 2, pageSize = 3, params = null
    ).get().value!!
    assertEquals("partial last page", 1, page2.size)
    assertEquals("i7", page2[0].mediaId)
  }

  // --- onGetItem

  @Test
  fun `onGetItem returns ERROR_BAD_VALUE for unknown id when no snapshot is set`() {
    val result = callback.onGetItem(session, browser, "any").get()
    assertEquals(LibraryResult.RESULT_ERROR_BAD_VALUE, result.resultCode)
  }

  @Test
  fun `onGetItem on the lib-owned root id returns the synthetic root item`() {
    // The lib's stable root id resolves to the synthetic root
    // MediaItem regardless of snapshot state. This is the id
    // `onGetLibraryRoot` always returns.
    val result = callback.onGetItem(
      session, browser, PlaybackServiceCallback.LIB_ROOT_ID
    ).get()
    assertEquals(LibraryResult.RESULT_SUCCESS, result.resultCode)
    assertEquals(PlaybackServiceCallback.LIB_ROOT_ID, result.value!!.mediaId)
    assertEquals(true, result.value!!.mediaMetadata.isBrowsable)
  }

  @Test
  fun `onGetItem on the consumer's snapshot rootId also returns the synthetic root item`() {
    // For back-compat, queries against the consumer-supplied
    // `snapshot.rootId` also resolve to the synthetic root — the
    // lib remaps internally without exposing the consumer's id.
    service.browseDataProvider.setSnapshot(
      BrowseSnapshot(rootId = "the-root", sections = arrayOf())
    )
    val result = callback.onGetItem(session, browser, "the-root").get()
    assertEquals(LibraryResult.RESULT_SUCCESS, result.resultCode)
    assertEquals(PlaybackServiceCallback.LIB_ROOT_ID, result.value!!.mediaId)
    assertEquals(true, result.value!!.mediaMetadata.isBrowsable)
  }

  @Test
  fun `onGetItem on a section id returns the section as a browsable item`() {
    service.browseDataProvider.setSnapshot(
      BrowseSnapshot(
        rootId = "root",
        sections = arrayOf(
          BrowseSection(id = "recents", title = "Recents", icon = null, artworkUrl = null, items = arrayOf())
        )
      )
    )
    val result = callback.onGetItem(session, browser, "recents").get()
    assertEquals(LibraryResult.RESULT_SUCCESS, result.resultCode)
    val v = result.value!!
    assertEquals("recents", v.mediaId)
    assertEquals("Recents", v.mediaMetadata.title)
    assertEquals(true, v.mediaMetadata.isBrowsable)
  }

  @Test
  fun `onGetItem on a leaf id returns the playable item`() {
    service.browseDataProvider.setSnapshot(
      BrowseSnapshot(
        rootId = "root",
        sections = arrayOf(
          BrowseSection(id = "recents", title = "R", icon = null, artworkUrl = null, items = arrayOf(item("song-1")))
        )
      )
    )
    val result = callback.onGetItem(session, browser, "song-1").get()
    assertEquals(LibraryResult.RESULT_SUCCESS, result.resultCode)
    assertEquals("song-1", result.value!!.mediaId)
    assertEquals(true, result.value!!.mediaMetadata.isPlayable)
  }

  @Test
  fun `onGetItem on a missing id returns BAD_VALUE`() {
    service.browseDataProvider.setSnapshot(
      BrowseSnapshot(rootId = "root", sections = arrayOf())
    )
    val result = callback.onGetItem(session, browser, "missing").get()
    assertEquals(LibraryResult.RESULT_ERROR_BAD_VALUE, result.resultCode)
  }

  // --- onSearch / onGetSearchResult — wired via SearchResultCache

  // --- Headless connector hook

  @Test
  fun `onConnect from a known headless package fires the environment hook`() {
    var called: String? = null
    val testCallback = PlaybackServiceCallback(object : PlaybackServiceCallback.Environment {
      override val browseDataProvider = service.browseDataProvider
      override val searchResultCache = service.searchResultCache
      override fun commandHandler() = null
      override fun mediaButtonDispatch() = null
      override fun playerCommandsFor(
        capability: SkipCapability
      ): Player.Commands =
        service.playerCommandsFor(capability)
      override fun onHeadlessControllerConnect(packageName: String) {
        called = packageName
      }
    })
    val autoController = MediaSession.ControllerInfo.createTestOnlyControllerInfo(
      "com.google.android.projection.gearhead", 0, 0, 0, 0, false, android.os.Bundle.EMPTY, false
    )
    testCallback.onConnect(session, autoController)
    assertEquals("com.google.android.projection.gearhead", called)
  }

  @Test
  fun `onConnect from a non-headless package does not fire the hook`() {
    var called: String? = null
    val testCallback = PlaybackServiceCallback(object : PlaybackServiceCallback.Environment {
      override val browseDataProvider = service.browseDataProvider
      override val searchResultCache = service.searchResultCache
      override fun commandHandler() = null
      override fun mediaButtonDispatch() = null
      override fun playerCommandsFor(
        capability: SkipCapability
      ): Player.Commands =
        service.playerCommandsFor(capability)
      override fun onHeadlessControllerConnect(packageName: String) {
        called = packageName
      }
    })
    val randomController = MediaSession.ControllerInfo.createTestOnlyControllerInfo(
      "com.example.random", 0, 0, 0, 0, false, android.os.Bundle.EMPTY, false
    )
    testCallback.onConnect(session, randomController)
    assertNotNull("onConnect still accepts the controller", testCallback)
    assertEquals(null, called)
  }
}
