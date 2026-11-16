package com.margelo.nitro.queueplayer

import androidx.media3.common.C
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * `MediaSession`-stable wrapper that intercepts external-controller
 * transport commands before they reach the underlying player.
 *
 * The session binds to this one wrapper for its lifetime; [retarget]
 * swaps the wrapped delegate as the active engine (gapless / crossfade)
 * or cast player changes, so the session's bound player identity never
 * churns. `ForwardingSimpleBasePlayer` (Media3 1.5+) is the supported
 * runtime-swappable Player base — the same one [CrossfadeForwardingPlayer]
 * uses — and exposes the `handle*` overrides that replace the deprecated
 * `MediaSession.Callback.onPlayerCommandRequest`.
 *
 * A controller command routes session → the matching `handle*` override.
 * The JS bridge drives the underlying engine player directly and never
 * reaches this wrapper, so [isControllerRequest] gates interception to
 * genuine external-controller requests (lock screen / Auto / Assistant /
 * Bluetooth), leaving any internal mutation to fall straight through.
 *
 * Two behaviours preserved from the old callback:
 *  - Queue-aware skip: a controller skip-next / skip-prev runs the lib's
 *    queue arithmetic via [PlaybackService.MediaSessionCommandHandler] and
 *    vetoes Media3's default seek when the lib handled it.
 *  - System-reason stamping: play / pause / seek / speed commands stamp
 *    `system` on the state-change channel before the command executes.
 */
@UnstableApi
internal class SessionCommandForwardingPlayer(
  initialPlayer: Player,
  private val session: () -> MediaSession?,
  private val commandHandler: () -> PlaybackService.MediaSessionCommandHandler?,
) : ForwardingSimpleBasePlayer(initialPlayer) {

  /**
   * Jump intervals (ms) for interval-skip mode, read on each controller
   * seek-forward/back so a runtime `setRemoteControls` change takes effect
   * without rebuilding the player. `@Volatile` — set from the main thread,
   * read on the application thread during a controller command.
   */
  @Volatile var forwardJumpMs: Long = 15_000
  @Volatile var backwardJumpMs: Long = 15_000

  /**
   * Mirrors the `clampSeekToBuffered` player config for the controller
   * interval-skip path so a lock-screen skip-forward obeys the same clamp as a
   * JS `seekTo`. When enabled, an interval skip-forward on an unknown-duration
   * source (`duration <= 0`, a live / open-ended stream) is capped at the
   * buffered position rather than jumping into the unbuffered void. `@Volatile`
   * — set from the main thread, read on the application thread during a seek.
   */
  @Volatile var clampSeekToBuffered: Boolean = false

  /**
   * Point the wrapped delegate at [player] — the active engine's session
   * player or a cast player — and refresh cached state. The same
   * runtime-swap entry point [CrossfadeForwardingPlayer] uses; keeps the
   * session bound to this stable wrapper across engine and cast
   * transitions instead of churning `MediaSession.setPlayer`.
   */
  fun retarget(player: Player) {
    setPlayer(player)
    invalidateState()
  }

  override fun handleSeek(
    mediaItemIndex: Int,
    positionMs: Long,
    @Player.Command seekCommand: Int,
  ): ListenableFuture<*> {
    if (!isControllerRequest()) {
      return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
    }
    return when (seekCommand) {
      Player.COMMAND_SEEK_TO_NEXT,
      Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM ->
        if (commandHandler()?.onSkipToNextCommand() == true) Futures.immediateVoidFuture()
        else super.handleSeek(mediaItemIndex, positionMs, seekCommand)
      Player.COMMAND_SEEK_TO_PREVIOUS,
      Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM ->
        if (commandHandler()?.onSkipToPreviousCommand() == true) Futures.immediateVoidFuture()
        else super.handleSeek(mediaItemIndex, positionMs, seekCommand)
      // Android Auto's queue-row tap. A raw per-item seek would move the
      // player without updating the lib's canonical current index — the
      // SEEK transition handler only syncs the index for player-originated
      // transitions, not controller seeks. Route through the skip path so
      // the index + track-change reason land; an index the lib declines
      // (out of range) falls through to the default seek.
      Player.COMMAND_SEEK_TO_MEDIA_ITEM ->
        if (commandHandler()?.onSeekToMediaItemCommand(mediaItemIndex) == true) Futures.immediateVoidFuture()
        else super.handleSeek(mediaItemIndex, positionMs, seekCommand)
      // Interval-skip mode: recompute the target with the runtime jump interval
      // rather than the wrapped player's build-time seek increment, so the value
      // is adjustable at runtime.
      Player.COMMAND_SEEK_FORWARD -> {
        commandHandler()?.onTransportCommand()
        // No-op on a loaded-but-unseekable track so a lock-screen skip can't
        // reset it to the start (mirrors seekToInternal's seekability guard).
        if (isLoadedAndUnseekable()) return Futures.immediateVoidFuture()
        val raw = currentPosition + forwardJumpMs
        val dur = duration
        var target = if (dur != C.TIME_UNSET && raw > dur) dur else raw
        // Mirror seekTo's `clampSeekToBuffered`: cap at the buffered edge when
        // the target is past what's downloaded (a fully-buffered track reports
        // buffered >= target, so nothing is capped).
        if (clampSeekToBuffered) {
          val buffered = bufferedPosition
          if (buffered != C.TIME_UNSET && buffered > 0L && target > buffered) {
            target = buffered
          }
        }
        // Forward the computed absolute target as an in-item seek. Passing the
        // original COMMAND_SEEK_FORWARD would route the wrapped player to
        // seekForward(), discarding target and jumping by its own build-time
        // seekForwardIncrement — defeating the runtime interval.
        super.handleSeek(
          mediaItemIndex,
          target.coerceAtLeast(0L),
          Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
        )
      }
      Player.COMMAND_SEEK_BACK -> {
        commandHandler()?.onTransportCommand()
        if (isLoadedAndUnseekable()) return Futures.immediateVoidFuture()
        super.handleSeek(
          mediaItemIndex,
          (currentPosition - backwardJumpMs).coerceAtLeast(0L),
          Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
        )
      }
      else -> {
        commandHandler()?.onTransportCommand()
        super.handleSeek(mediaItemIndex, positionMs, seekCommand)
      }
    }
  }

  // True once the current item is loaded (STATE_READY / ENDED) AND confirmed
  // unseekable — the state in which a raw seek would reset to the start. During
  // initial buffering the window isn't populated yet, so an early skip isn't
  // blocked.
  private fun isLoadedAndUnseekable(): Boolean {
    val loaded = playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED
    return loaded && !isCurrentMediaItemSeekable
  }

  override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
    if (isControllerRequest()) commandHandler()?.onTransportCommand()
    return super.handleSetPlayWhenReady(playWhenReady)
  }

  override fun handleSetPlaybackParameters(
    playbackParameters: PlaybackParameters,
  ): ListenableFuture<*> {
    if (isControllerRequest()) commandHandler()?.onTransportCommand()
    return super.handleSetPlaybackParameters(playbackParameters)
  }

  /**
   * True while a controller-triggered Player method is executing.
   * `getControllerForCurrentRequest` is non-null only for the duration
   * of a controller-originated call on the application thread, which is
   * exactly when the `handle*` overrides run.
   */
  private fun isControllerRequest(): Boolean =
    session()?.controllerForCurrentRequest != null
}
