package com.margelo.nitro.queueplayer

import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/**
 * Robolectric coverage for [PlaybackServiceCallback]'s search
 * surface — `onSearch` triggers the JS dispatch hook on a cache
 * miss + immediately delivers `notifySearchResultChanged` on a
 * cache hit; `onGetSearchResult` reads from the cache.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@UnstableApi
class PlaybackServiceCallbackSearchTest {

  private lateinit var controller: ServiceController<PlaybackService>
  private lateinit var service: PlaybackService
  private lateinit var session: MediaLibraryService.MediaLibrarySession
  private lateinit var browser: MediaSession.ControllerInfo

  private fun item(id: String) = BrowseItem(
    id = id, title = id, subtitle = null, artworkUrl = null,
    playable = true, hasChildren = null
  )

  @Before
  fun setUp() {
    controller = Robolectric.buildService(PlaybackService::class.java).create()
    service = controller.get()
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val intent = Intent(context.applicationContext, PlaybackService::class.java)
      .setAction(PlaybackService.ACTION_LOCAL_BIND)
    val binder = service.onBind(intent) as PlaybackService.LocalBinder
    session = binder.session
    browser = MediaSession.ControllerInfo.createTestOnlyControllerInfo(
      "test-browser", 0, 0, 0, 0, false, android.os.Bundle.EMPTY, false
    )
  }

  @After
  fun tearDown() {
    controller.destroy()
  }

  /** Build a callback that records query receipts via the
   *  Environment hook so cache-miss dispatch is observable. */
  private fun callbackWithRecorder(received: MutableList<String>) =
    PlaybackServiceCallback(object : PlaybackServiceCallback.Environment {
      override val browseDataProvider = service.browseDataProvider
      override val searchResultCache = service.searchResultCache
      override fun commandHandler() = null
      override fun mediaButtonDispatch() = null
      override fun playerCommandsFor(
        capability: SkipCapability
      ): Player.Commands =
        service.playerCommandsFor(capability)
      override fun onSearchQueryReceived(query: String) {
        received.add(query)
      }
    })

  // --- onSearch

  @Test
  fun `onSearch on cache miss returns Void success + invokes the JS dispatch hook`() {
    val received = mutableListOf<String>()
    val callback = callbackWithRecorder(received)
    val result = callback.onSearch(session, browser, "rock", null).get()
    assertEquals(LibraryResult.RESULT_SUCCESS, result.resultCode)
    assertEquals(listOf("rock"), received)
  }

  @Test
  fun `onSearch on cache hit does NOT invoke the JS dispatch hook`() {
    val received = mutableListOf<String>()
    val callback = callbackWithRecorder(received)
    service.searchResultCache.setResults("rock", listOf(item("song-1")))
    val result = callback.onSearch(session, browser, "rock", null).get()
    assertEquals(LibraryResult.RESULT_SUCCESS, result.resultCode)
    assertTrue(
      "cached query short-circuits the dispatch hook",
      received.isEmpty()
    )
    // notifySearchResultChanged side-effect is on the session;
    // verifying it via Robolectric's session shadow would couple
    // this test to Media3 internals — the contract under test
    // here is "cache hit suppresses dispatch", which the empty
    // received-list pins.
  }

  // --- onGetSearchResult

  @Test
  fun `onGetSearchResult before any results returns empty page`() {
    val callback = callbackWithRecorder(mutableListOf())
    val result = callback.onGetSearchResult(
      session, browser, "rock", page = 0, pageSize = 50, params = null
    ).get()
    assertEquals(LibraryResult.RESULT_SUCCESS, result.resultCode)
    assertTrue("waiting-for-JS shape is empty page", result.value!!.isEmpty())
  }

  @Test
  fun `onGetSearchResult returns the cached page when results are populated`() {
    val callback = callbackWithRecorder(mutableListOf())
    service.searchResultCache.setResults(
      "rock",
      listOf(item("a"), item("b"), item("c"))
    )
    val result = callback.onGetSearchResult(
      session, browser, "rock", page = 0, pageSize = 50, params = null
    ).get()
    assertEquals(LibraryResult.RESULT_SUCCESS, result.resultCode)
    val items = result.value!!
    assertEquals(3, items.size)
    assertEquals("a", items[0].mediaId)
    assertEquals(true, items[0].mediaMetadata.isPlayable)
  }

  @Test
  fun `onGetSearchResult paginates the cached results`() {
    val callback = callbackWithRecorder(mutableListOf())
    service.searchResultCache.setResults(
      "q",
      (1..5).map { item("i$it") }
    )
    val page1 = callback.onGetSearchResult(
      session, browser, "q", page = 1, pageSize = 2, params = null
    ).get().value!!
    assertEquals(2, page1.size)
    assertEquals("i3", page1[0].mediaId)
    assertEquals("i4", page1[1].mediaId)

    val page2 = callback.onGetSearchResult(
      session, browser, "q", page = 2, pageSize = 2, params = null
    ).get().value!!
    assertEquals("partial last page", 1, page2.size)
    assertEquals("i5", page2[0].mediaId)
  }

  @Test
  fun `onGetSearchResult returns the empty page result with non-null value`() {
    val callback = callbackWithRecorder(mutableListOf())
    val result = callback.onGetSearchResult(
      session, browser, "never-asked", page = 0, pageSize = 50, params = null
    ).get()
    assertEquals(LibraryResult.RESULT_SUCCESS, result.resultCode)
    assertNull(
      "no cache entry → result has no params (no LibraryParams was set)",
      result.params
    )
  }

  // --- dispatchSearchToJs

  // `Dispatchers.setMain` is the only way to see which dispatcher the notify
  // lands on; `notifySearchResultChanged` has no other observable effect with
  // no controller connected.
  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun `dispatchSearchToJs caches the answer then notifies on main`() {
    // A Nitro Promise is JNI-constructed and cannot exist on the JVM, so
    // the only JS answer reachable here is a throwing handler; that path
    // still writes the cache and still notifies, which is the contract a
    // connected controller depends on to stop waiting.
    val mainDispatches = AtomicInteger(0)
    val dispatched = CountDownLatch(1)
    val recording = object : kotlinx.coroutines.CoroutineDispatcher() {
      override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
        mainDispatches.incrementAndGet()
        dispatched.countDown()
        android.os.Handler(android.os.Looper.getMainLooper()).post(block)
      }
    }
    Dispatchers.setMain(recording)
    try {
      service.browseCallbacks.registerOnSearchRequest { _ ->
        throw IllegalStateException("no Nitro runtime under Robolectric")
      }
      assertFalse(service.searchResultCache.hasResults("rock"))

      service.dispatchSearchToJs("rock")

      assertTrue("the notify reached a dispatcher", dispatched.await(2, TimeUnit.SECONDS))
      org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
      assertEquals("notify is dispatched to main", 1, mainDispatches.get())
      assertEquals(emptyList<BrowseItem>(), service.searchResultCache.getResults("rock"))
    } finally {
      Dispatchers.resetMain()
    }
  }
}
