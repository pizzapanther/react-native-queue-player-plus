package com.margelo.nitro.queueplayer

/**
 * Cross-channel de-duplicator for remote media-button commands (Bluetooth/AVRCP,
 * notification, lock screen, Android Auto, wired headset).
 *
 * A single physical press can reach the service through more than one native
 * route — the MediaSession `onMediaButtonEvent` callback and/or a legacy
 * `ACTION_MEDIA_BUTTON` broadcast that lands in `onStartCommand` (directly or
 * via a manifest-registered `MediaButtonReceiver`) — and Media3 can itself
 * deliver one event twice on a single route (androidx/media#3083). Without
 * de-duplication those fan out into multiple skips.
 *
 * The de-dup keys on RECEIPT TIME and CHANNEL — deliberately NOT on
 * `KeyEvent.downTime`, which is unreliable for synthesised Bluetooth key events
 * (frequently a constant 0). A downTime-keyed comparison that never expires
 * suppresses every later press forever once the first one is cached, so only
 * the first Next/Previous over Bluetooth ever fires.
 *
 * Rules, per command, anchored on the last EMITTED occurrence:
 *  - a duplicate from a DIFFERENT channel within [crossWindowMs] is one press
 *    fanned out across routes -> suppress.
 *  - a duplicate from the SAME channel within [sameWindowMs] is a near-instant
 *    redelivery -> suppress.
 *  - everything else is emitted. Distinct presses are channel-consistent and
 *    arrive far more than a window apart, so they always pass. The anchor
 *    advances ONLY on emit, never on a suppressed event, so a burst of
 *    duplicates cannot keep extending the window.
 *
 * Bias is toward emitting: when in doubt, act rather than swallow a real press.
 *
 * Pure and time-injected ([nowUptimeMs] is passed in), so it needs no
 * Android runtime. [shouldEmit] is `@Synchronized` so it is safe regardless of
 * which thread an emit site runs on (today they are all main-thread-confined).
 */
internal class RemoteCommandDeduper(
  private val crossWindowMs: Long = DEFAULT_CROSS_WINDOW_MS,
  private val sameWindowMs: Long = DEFAULT_SAME_WINDOW_MS,
) {
  /** The native route a media-button delivery arrived on. */
  enum class Channel {
    /** `MediaSession.Callback.onMediaButtonEvent`. */
    SESSION_KEY,

    /** `ACTION_MEDIA_BUTTON` broadcast reaching `Service.onStartCommand`. */
    BROADCAST,
  }

  private data class Anchor(val channel: Channel, val uptimeMs: Long)

  private val lastByCommand = HashMap<String, Anchor>()

  /**
   * @param command stable identity for the resolved transport command.
   * @param channel the native route this delivery arrived on.
   * @param nowUptimeMs caller passes `SystemClock.uptimeMillis()` (injected for tests).
   * @return true if [command] should be acted on; false if it is a duplicate to drop.
   */
  @Synchronized
  fun shouldEmit(command: String, channel: Channel, nowUptimeMs: Long): Boolean {
    val last = lastByCommand[command]
    if (last != null) {
      val elapsed = nowUptimeMs - last.uptimeMs
      val window = if (channel == last.channel) sameWindowMs else crossWindowMs
      // Lower bound is a defensive guard: uptime is monotonic so elapsed is
      // never negative in practice, but a negative would emit (bias to act).
      if (elapsed in 0 until window) return false
    }
    lastByCommand[command] = Anchor(channel, nowUptimeMs)
    return true
  }

  companion object {
    /** Broadcast vs in-process delivery skew is tens of ms, occasionally ~100. */
    const val DEFAULT_CROSS_WINDOW_MS = 200L

    /** Same-channel redelivery is near-instant; deliberate same-channel double
     *  presses are >= ~150 ms apart, so they pass. */
    const val DEFAULT_SAME_WINDOW_MS = 80L
  }
}
