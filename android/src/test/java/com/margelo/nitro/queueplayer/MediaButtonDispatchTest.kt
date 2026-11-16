package com.margelo.nitro.queueplayer

import android.content.Intent
import android.view.KeyEvent
import com.margelo.nitro.queueplayer.RemoteCommandDeduper.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric coverage for [MediaButtonDispatch] — key-code → command mapping
 * plus the receipt-time-windowed de-dup that absorbs the same physical press
 * fanned out across the session-callback and broadcast routes, while letting
 * distinct presses through (including synthesised Bluetooth keys with a
 * constant `downTime`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaButtonDispatchTest {

  private lateinit var handler: RecordingHandler
  private lateinit var dispatch: MediaButtonDispatch
  private var now: Long = 1_000L

  @Before
  fun setUp() {
    handler = RecordingHandler()
    now = 1_000L
    dispatch = MediaButtonDispatch(handler, nowUptimeMs = { now })
  }

  @Test
  fun `KEYCODE_MEDIA_PLAY_PAUSE invokes onMediaTogglePlayPause and reports handled`() {
    val handled = dispatch.handle(downIntent(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE), Channel.SESSION_KEY)
    assertTrue(handled)
    assertEquals(1, handler.toggleCalls)
    assertEquals(0, handler.playCalls)
    assertEquals(0, handler.pauseCalls)
    assertEquals(0, handler.skipNextCalls)
    assertEquals(0, handler.skipPreviousCalls)
  }

  @Test
  fun `KEYCODE_HEADSETHOOK invokes onMediaTogglePlayPause`() {
    assertTrue(dispatch.handle(downIntent(KeyEvent.KEYCODE_HEADSETHOOK), Channel.SESSION_KEY))
    assertEquals(1, handler.toggleCalls)
  }

  @Test
  fun `KEYCODE_MEDIA_PLAY invokes onMediaPlay`() {
    assertTrue(dispatch.handle(downIntent(KeyEvent.KEYCODE_MEDIA_PLAY), Channel.SESSION_KEY))
    assertEquals(1, handler.playCalls)
    assertEquals(0, handler.toggleCalls)
  }

  @Test
  fun `KEYCODE_MEDIA_PAUSE invokes onMediaPause`() {
    assertTrue(dispatch.handle(downIntent(KeyEvent.KEYCODE_MEDIA_PAUSE), Channel.SESSION_KEY))
    assertEquals(1, handler.pauseCalls)
  }

  @Test
  fun `KEYCODE_MEDIA_NEXT invokes onMediaSkipNext`() {
    assertTrue(dispatch.handle(downIntent(KeyEvent.KEYCODE_MEDIA_NEXT), Channel.SESSION_KEY))
    assertEquals(1, handler.skipNextCalls)
  }

  @Test
  fun `KEYCODE_MEDIA_PREVIOUS invokes onMediaSkipPrevious`() {
    assertTrue(dispatch.handle(downIntent(KeyEvent.KEYCODE_MEDIA_PREVIOUS), Channel.SESSION_KEY))
    assertEquals(1, handler.skipPreviousCalls)
  }

  @Test
  fun `same press fanned across both routes within the window acts once`() {
    // One physical press arriving on the session callback then the broadcast,
    // 15ms apart — the second is suppressed but still claimed.
    assertTrue(dispatch.handle(downIntent(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE), Channel.SESSION_KEY))
    now += 15
    assertTrue(dispatch.handle(downIntent(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE), Channel.BROADCAST))
    assertEquals(1, handler.toggleCalls)
  }

  @Test
  fun `repeated Bluetooth Next with a constant downTime each fire`() {
    // The regression this fix targets: AVRCP/Bluetooth keys synthesise a
    // constant downTime (here 0). Three distinct presses past the window must
    // all skip — the old downTime-keyed de-dup suppressed every press after the first.
    assertTrue(dispatch.handle(downIntent(KeyEvent.KEYCODE_MEDIA_NEXT, downTime = 0), Channel.SESSION_KEY))
    now += 300
    assertTrue(dispatch.handle(downIntent(KeyEvent.KEYCODE_MEDIA_NEXT, downTime = 0), Channel.SESSION_KEY))
    now += 300
    assertTrue(dispatch.handle(downIntent(KeyEvent.KEYCODE_MEDIA_NEXT, downTime = 0), Channel.SESSION_KEY))
    assertEquals(3, handler.skipNextCalls)
  }

  @Test
  fun `auto-repeat from a held key claims but does not multi-skip`() {
    // The initial press skips; auto-repeat ACTION_DOWNs (repeat > 0), even spaced
    // past the de-dup window, are claimed but not dispatched.
    assertTrue(dispatch.handle(downIntent(KeyEvent.KEYCODE_MEDIA_NEXT, repeat = 0), Channel.SESSION_KEY))
    now += 200
    assertTrue(dispatch.handle(downIntent(KeyEvent.KEYCODE_MEDIA_NEXT, repeat = 1), Channel.SESSION_KEY))
    now += 200
    assertTrue(dispatch.handle(downIntent(KeyEvent.KEYCODE_MEDIA_NEXT, repeat = 2), Channel.SESSION_KEY))
    assertEquals(1, handler.skipNextCalls)
  }

  @Test
  fun `same-channel near-instant redelivery is suppressed`() {
    assertTrue(dispatch.handle(downIntent(KeyEvent.KEYCODE_MEDIA_NEXT), Channel.SESSION_KEY))
    now += 3
    assertTrue(dispatch.handle(downIntent(KeyEvent.KEYCODE_MEDIA_NEXT), Channel.SESSION_KEY))
    assertEquals(1, handler.skipNextCalls)
  }

  @Test
  fun `ACTION_UP no-ops the handler and reports unhandled`() {
    val key = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
    val intent = Intent(Intent.ACTION_MEDIA_BUTTON).apply { putExtra(Intent.EXTRA_KEY_EVENT, key) }
    assertFalse(dispatch.handle(intent, Channel.SESSION_KEY))
    assertEquals(0, handler.toggleCalls)
  }

  @Test
  fun `non-media keycodes report unhandled and do not invoke the handler`() {
    assertFalse(dispatch.handle(downIntent(KeyEvent.KEYCODE_VOLUME_UP), Channel.SESSION_KEY))
    assertEquals(0, handler.playCalls)
    assertEquals(0, handler.toggleCalls)
    assertEquals(0, handler.skipNextCalls)
  }

  @Test
  fun `null intent reports unhandled`() {
    assertFalse(dispatch.handle(null, Channel.SESSION_KEY))
  }

  @Test
  fun `intent without EXTRA_KEY_EVENT reports unhandled`() {
    assertFalse(dispatch.handle(Intent(Intent.ACTION_MEDIA_BUTTON), Channel.SESSION_KEY))
  }

  @Test
  fun `unhandled keycode does not poison de-dup state for the next valid press`() {
    dispatch.handle(downIntent(KeyEvent.KEYCODE_VOLUME_UP), Channel.SESSION_KEY)
    now += 5
    assertTrue(dispatch.handle(downIntent(KeyEvent.KEYCODE_MEDIA_PLAY), Channel.SESSION_KEY))
    assertEquals(1, handler.playCalls)
  }

  // --- Helpers

  private fun downIntent(keyCode: Int, downTime: Long = 0L, repeat: Int = 0): Intent {
    val key = KeyEvent(
      /* downTime = */ downTime,
      /* eventTime = */ downTime,
      /* action = */ KeyEvent.ACTION_DOWN,
      /* code = */ keyCode,
      /* repeat = */ repeat,
    )
    return Intent(Intent.ACTION_MEDIA_BUTTON).apply { putExtra(Intent.EXTRA_KEY_EVENT, key) }
  }

  private class RecordingHandler : PlaybackService.MediaSessionCommandHandler {
    var transportCalls = 0
    var skipNextCmdCalls = 0
    var skipPreviousCmdCalls = 0
    var playCalls = 0
    var pauseCalls = 0
    var toggleCalls = 0
    var skipNextCalls = 0
    var skipPreviousCalls = 0

    override fun onTransportCommand() { transportCalls++ }
    override fun onSkipToNextCommand(): Boolean { skipNextCmdCalls++; return true }
    override fun onSkipToPreviousCommand(): Boolean { skipPreviousCmdCalls++; return true }
    override fun onSeekToMediaItemCommand(mediaItemIndex: Int): Boolean = false
    override fun getCachedSkipCapability(): SkipCapability =
      SkipCapability(canSkipPrevious = true, canSkipNext = true)
    override fun onMediaPlay() { playCalls++ }
    override fun onMediaPause() { pauseCalls++ }
    override fun onMediaTogglePlayPause() { toggleCalls++ }
    override fun onMediaSkipNext() { skipNextCalls++ }
    override fun onMediaSkipPrevious() { skipPreviousCalls++ }
    override fun onAudioFocusEvent(mapping: AudioFocusErrorMapping.Mapping) {}
    override fun onBecomingNoisy() {}
  }
}
