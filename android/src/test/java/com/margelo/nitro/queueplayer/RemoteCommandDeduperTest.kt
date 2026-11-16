package com.margelo.nitro.queueplayer

import com.margelo.nitro.queueplayer.RemoteCommandDeduper.Channel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Truth-table coverage for the cross-channel remote-command deduper.
 * Windows are pinned (cross=200, same=80) so the cases are exact. Pure JVM —
 * no Android runtime, time is injected.
 */
class RemoteCommandDeduperTest {
  private val next = "skipNext"
  private val play = "play"

  private fun deduper() = RemoteCommandDeduper(crossWindowMs = 200, sameWindowMs = 80)

  @Test
  fun firstPressEmits() {
    assertTrue(deduper().shouldEmit(next, Channel.BROADCAST, 1000))
  }

  @Test
  fun crossChannelFanoutWithinWindowSuppressed() {
    val d = deduper()
    assertTrue(d.shouldEmit(next, Channel.BROADCAST, 1000))
    assertFalse(d.shouldEmit(next, Channel.SESSION_KEY, 1015)) // same press, other route, 15ms
  }

  @Test
  fun distinctPressesSameChannelBothEmit() {
    val d = deduper()
    assertTrue(d.shouldEmit(next, Channel.BROADCAST, 1000))
    assertTrue(d.shouldEmit(next, Channel.BROADCAST, 1300)) // deliberate skip 300ms later
  }

  @Test
  fun bluetoothWedge_distinctPressesPassWithNoDownTimeSignal() {
    // downTime is not an input. Distinct presses on one channel, each past the
    // window, all pass — the constant-downTime Bluetooth wedge cannot recur.
    val d = deduper()
    assertTrue(d.shouldEmit(next, Channel.BROADCAST, 1000))
    assertTrue(d.shouldEmit(next, Channel.BROADCAST, 1250))
    assertTrue(d.shouldEmit(next, Channel.BROADCAST, 1600))
  }

  @Test
  fun sameChannelRedeliveryWithinSameWindowSuppressed() {
    val d = deduper()
    assertTrue(d.shouldEmit(next, Channel.SESSION_KEY, 1000))
    assertFalse(d.shouldEmit(next, Channel.SESSION_KEY, 1003)) // 3ms near-instant redelivery
  }

  @Test
  fun fastDoublePressSameChannelJustOutsideSameWindowEmits() {
    val d = deduper()
    assertTrue(d.shouldEmit(next, Channel.SESSION_KEY, 1000))
    assertTrue(d.shouldEmit(next, Channel.SESSION_KEY, 1090)) // 90ms > sameWindow(80)
  }

  @Test
  fun sameChannelUsesSameWindowNotCrossWindow() {
    // 150ms on the same channel: > same(80) but < cross(200) -> still a distinct press.
    val d = deduper()
    assertTrue(d.shouldEmit(next, Channel.BROADCAST, 1000))
    assertTrue(d.shouldEmit(next, Channel.BROADCAST, 1150))
  }

  @Test
  fun crossChannelOutsideWindowEmits() {
    val d = deduper()
    assertTrue(d.shouldEmit(next, Channel.BROADCAST, 1000))
    assertTrue(d.shouldEmit(next, Channel.SESSION_KEY, 1250)) // 250 > cross(200)
  }

  @Test
  fun interleavedCommandsAreIndependent() {
    val d = deduper()
    assertTrue(d.shouldEmit(next, Channel.BROADCAST, 1000))
    assertTrue(d.shouldEmit(play, Channel.BROADCAST, 1010))     // different command
    assertFalse(d.shouldEmit(next, Channel.SESSION_KEY, 1015))  // next twin vs next anchor
    assertFalse(d.shouldEmit(play, Channel.SESSION_KEY, 1020))  // play twin vs play anchor
  }

  @Test
  fun anchorNotAdvancedOnSuppress() {
    // A burst of suppressed duplicates must not keep extending the window.
    val d = deduper()
    assertTrue(d.shouldEmit(next, Channel.BROADCAST, 1000))     // emit, anchor @1000
    assertFalse(d.shouldEmit(next, Channel.SESSION_KEY, 1050))  // suppress, anchor stays @1000
    assertFalse(d.shouldEmit(next, Channel.SESSION_KEY, 1100))  // suppress, anchor stays @1000
    // 210ms after the EMIT (not the last suppress); had the anchor advanced this would suppress.
    assertTrue(d.shouldEmit(next, Channel.SESSION_KEY, 1210))
  }

  @Test
  fun crossChannelFanoutTwoPressesEachActOnce() {
    // Device fans every press across BROADCAST(lead)+SESSION; presses 300ms apart.
    val d = deduper()
    assertTrue(d.shouldEmit(next, Channel.BROADCAST, 1000))     // press 1 lead
    assertFalse(d.shouldEmit(next, Channel.SESSION_KEY, 1015))  // press 1 twin
    assertTrue(d.shouldEmit(next, Channel.BROADCAST, 1300))     // press 2 lead (same channel, > same window)
    assertFalse(d.shouldEmit(next, Channel.SESSION_KEY, 1315))  // press 2 twin
  }
}
