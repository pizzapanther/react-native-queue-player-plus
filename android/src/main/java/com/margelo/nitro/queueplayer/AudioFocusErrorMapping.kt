package com.margelo.nitro.queueplayer

import androidx.media3.common.Player

/**
 * Maps Media3 [Player.Listener] audio-focus reason ints to the lib-
 * diagnostic `nativeDomain` string + a stable human-readable message.
 * These normal focus transitions are logged only when diagnostic logging
 * is enabled; they are not sent through `TrackPlayer.onError`.
 *
 * Media3 already drives the actual pause / duck / resume response
 * via `setAudioAttributes(handleAudioFocus = true)`. The mapping
 * exists purely for native diagnostics. Consumers observe resulting
 * transitions through the playback-state stream.
 *
 * **`AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK` is intentionally NOT
 * surfaced.** Media3 ducks internally on can-duck losses and does
 * not expose that state via any public [Player.Listener] reason —
 * neither `PLAY_WHEN_READY_CHANGE_REASON_*` nor
 * `PLAYBACK_SUPPRESSION_REASON_*`. The only way to observe it is
 * to register a parallel `AudioFocusRequest`, which contends with
 * Media3's request against the same `USAGE_MEDIA` attributes and
 * can displace the focus Media3 holds. That's worse than the lost
 * observability, so the can-duck variant is dropped from the
 * surface.
 *
 * Three `nativeDomain` strings, all sharing the `AUDIO_FOCUS_`
 * prefix so native logs can group focus events. Unknown reason ints
 * map to `null` so the caller drops
 * them — keeps unknown future codes from leaking through as
 * malformed events.
 */
internal object AudioFocusErrorMapping {

  /** Output pair surfaced by the mapping functions. */
  data class Mapping(val nativeDomain: String, val message: String)

  /** Shared prefix for the focus `nativeDomain` strings. */
  const val NATIVE_DOMAIN_PREFIX = "AUDIO_FOCUS_"

  /**
   * Translate a [Player.Listener.onPlayWhenReadyChanged] reason
   * into the lib-standardized event pair. Only
   * [Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS]
   * produces a mapping — every other reason is a non-focus
   * transition (USER, REMOTE, END_OF_MEDIA_ITEM, SUPPRESSED) and
   * returns `null` so the caller drops the event.
   */
  fun mapPlayWhenReadyReason(reason: Int): Mapping? =
    if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) {
      Mapping(
        nativeDomain = NATIVE_DOMAIN_PREFIX + "LOSS_PERMANENT",
        message = "Audio focus lost permanently; Media3 has paused playback.",
      )
    } else {
      null
    }

  /**
   * Translate a [Player.Listener.onPlaybackSuppressionReasonChanged]
   * transition into the lib-standardized event pair. Two transitions
   * map:
   *   - any → [Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS]
   *     ⇒ `LOSS_TRANSIENT`
   *   - [Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS]
   *     → [Player.PLAYBACK_SUPPRESSION_REASON_NONE] ⇒ `GAIN`
   *
   * Other transitions (e.g. NONE → UNSUITABLE_AUDIO_OUTPUT, or
   * NONE → NONE redundant fires) return `null`.
   */
  fun mapSuppressionReason(reason: Int, previous: Int): Mapping? = when {
    reason == Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS &&
      previous != Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS ->
      Mapping(
        nativeDomain = NATIVE_DOMAIN_PREFIX + "LOSS_TRANSIENT",
        message = "Audio focus lost transiently; Media3 has paused playback.",
      )
    reason == Player.PLAYBACK_SUPPRESSION_REASON_NONE &&
      previous == Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS ->
      Mapping(
        nativeDomain = NATIVE_DOMAIN_PREFIX + "GAIN",
        message = "Audio focus regained.",
      )
    else -> null
  }
}
