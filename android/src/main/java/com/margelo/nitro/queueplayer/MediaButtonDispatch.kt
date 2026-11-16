package com.margelo.nitro.queueplayer

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import androidx.core.content.IntentCompat

/**
 * Dispatcher for raw `Intent.ACTION_MEDIA_BUTTON` intents. Two runtime paths
 * can fire the same physical media-button press:
 *  - [RemoteCommandDeduper.Channel.SESSION_KEY] — `MediaSession.Callback.onMediaButtonEvent`
 *  - [RemoteCommandDeduper.Channel.BROADCAST] — `Service.onStartCommand` with
 *    `ACTION_MEDIA_BUTTON` (directly, or via a manifest-registered
 *    `MediaButtonReceiver` that forwards into `onStartCommand`)
 *
 * Without de-dup the same press would invoke the handler on both paths.
 * De-dup is delegated to [RemoteCommandDeduper], which keys on receipt time +
 * channel (never `KeyEvent.downTime`, which Bluetooth/AVRCP synthesise as a
 * constant and which would suppress every later press of a key forever).
 *
 * Threading: [handle] is invoked from the service main looper by every caller;
 * the deduper is `@Synchronized` regardless.
 */
internal class MediaButtonDispatch(
  private val handler: PlaybackService.MediaSessionCommandHandler,
  private val deduper: RemoteCommandDeduper = RemoteCommandDeduper(),
  private val nowUptimeMs: () -> Long = { SystemClock.uptimeMillis() },
) {

  /**
   * Route the supplied intent to [handler] when it carries a recognised
   * `ACTION_DOWN` media-key event arriving on [channel]. Returns `true` when
   * the lib has claimed the event (so further routing is suppressed), `false`
   * otherwise — a `null` intent, a missing key event, an `ACTION_UP`, or an
   * unrecognised key code all return `false` and leave de-dup state untouched.
   *
   * A recognised key is always claimed; it is dispatched to [handler] unless
   * the deduper classifies it as a near-simultaneous duplicate of a press
   * already acted on, in which case it is silently dropped.
   */
  fun handle(intent: Intent?, channel: RemoteCommandDeduper.Channel): Boolean {
    val keyEvent = extractKeyEvent(intent) ?: return false
    if (keyEvent.action != KeyEvent.ACTION_DOWN) return false
    val command = commandFor(keyEvent.keyCode) ?: return false
    // Ignore auto-repeat from a held key: the initial press has already acted,
    // and holding a key should not fan into repeated skips. Still claim it so
    // the platform does not fall back to its own handling of the repeat.
    if (keyEvent.repeatCount > 0) return true
    if (deduper.shouldEmit(command, channel, nowUptimeMs())) {
      dispatch(command)
    }
    return true
  }

  /** Stable command identity for a media key code, or `null` if unrecognised. */
  private fun commandFor(keyCode: Int): String? = when (keyCode) {
    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
    KeyEvent.KEYCODE_HEADSETHOOK -> CMD_TOGGLE
    KeyEvent.KEYCODE_MEDIA_PLAY -> CMD_PLAY
    KeyEvent.KEYCODE_MEDIA_PAUSE -> CMD_PAUSE
    KeyEvent.KEYCODE_MEDIA_NEXT -> CMD_NEXT
    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> CMD_PREVIOUS
    else -> null
  }

  private fun dispatch(command: String) {
    when (command) {
      CMD_TOGGLE -> handler.onMediaTogglePlayPause()
      CMD_PLAY -> handler.onMediaPlay()
      CMD_PAUSE -> handler.onMediaPause()
      CMD_NEXT -> handler.onMediaSkipNext()
      CMD_PREVIOUS -> handler.onMediaSkipPrevious()
    }
  }

  private fun extractKeyEvent(intent: Intent?): KeyEvent? {
    if (intent == null) return null
    return IntentCompat.getParcelableExtra(intent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
  }

  private companion object {
    const val CMD_TOGGLE = "togglePlayPause"
    const val CMD_PLAY = "play"
    const val CMD_PAUSE = "pause"
    const val CMD_NEXT = "skipNext"
    const val CMD_PREVIOUS = "skipPrevious"
  }
}
