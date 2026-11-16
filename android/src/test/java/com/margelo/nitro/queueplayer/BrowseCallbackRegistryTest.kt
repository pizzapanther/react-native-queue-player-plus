package com.margelo.nitro.queueplayer

import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pure-Kotlin coverage for [BrowseCallbackRegistry]'s slot-replace
 * + dispose semantics. The Promise-typed callbacks are stored as
 * function references; tests use simple stand-in lambdas.
 */
@UnstableApi
class BrowseCallbackRegistryTest {

  @Test
  fun `empty registry returns null for every slot`() {
    val r = BrowseCallbackRegistry()
    assertNull(r.browseRequestCallback())
    assertNull(r.searchRequestCallback())
    assertNull(r.playFromSearchRequestCallback())
    assertNull(r.playFromIdRequestCallback())
    assertNull(r.carConnectCallback())
    assertNull(r.carDisconnectCallback())
    assertNull(r.serviceReadyCallback())
  }

  @Test
  fun `registerOnServiceReady stores the callback + disposer clears`() {
    val r = BrowseCallbackRegistry()
    val cb: (ServiceReadyReason) -> Unit = { /* no-op */ }
    val disposer = r.registerOnServiceReady(cb)
    assertSame(cb, r.serviceReadyCallback())
    disposer()
    assertNull("disposer clears the slot", r.serviceReadyCallback())
  }

  @Test
  fun `clear drops the onServiceReady slot too`() {
    val r = BrowseCallbackRegistry()
    r.registerOnServiceReady { }
    assertNotNull(r.serviceReadyCallback())
    r.clear()
    assertNull(r.serviceReadyCallback())
  }

  @Test
  fun `registerOnCarConnect stores the callback + returns disposer that clears`() {
    val r = BrowseCallbackRegistry()
    val cb: () -> Unit = { /* no-op */ }
    val disposer = r.registerOnCarConnect(cb)
    assertSame(cb, r.carConnectCallback())
    disposer()
    assertNull("disposer clears the slot", r.carConnectCallback())
  }

  @Test
  fun `register replaces a prior callback in the same slot`() {
    val r = BrowseCallbackRegistry()
    val first: () -> Unit = { }
    val second: () -> Unit = { }
    r.registerOnCarConnect(first)
    r.registerOnCarConnect(second)
    assertSame(
      "second register replaces the first",
      second,
      r.carConnectCallback()
    )
  }

  @Test
  fun `disposing the prior callback after a replace is a no-op`() {
    // Re-registration before the first disposer fires is the
    // standard "JS handler swapped" path. The first disposer must
    // NOT clear the new (live) registration.
    val r = BrowseCallbackRegistry()
    val first: () -> Unit = { }
    val second: () -> Unit = { }
    val firstDispose = r.registerOnCarConnect(first)
    r.registerOnCarConnect(second)
    firstDispose()
    assertSame(
      "stale dispose does NOT clear the live registration",
      second,
      r.carConnectCallback()
    )
  }

  @Test
  fun `clear drops every slot`() {
    val r = BrowseCallbackRegistry()
    r.registerOnCarConnect { }
    r.registerOnCarDisconnect { }
    assertNotNull(r.carConnectCallback())
    assertNotNull(r.carDisconnectCallback())
    r.clear()
    assertNull(r.carConnectCallback())
    assertNull(r.carDisconnectCallback())
  }
}
