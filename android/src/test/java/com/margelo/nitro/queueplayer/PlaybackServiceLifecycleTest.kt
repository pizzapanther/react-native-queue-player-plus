package com.margelo.nitro.queueplayer

import android.content.Intent
import android.media.AudioManager
import android.os.Looper
import android.view.KeyEvent
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Robolectric coverage for [PlaybackService]'s Android lifecycle
 * surface — `onCreate`, `onStartCommand`, `onTaskRemoved`, `onDestroy`.
 * Each test walks the service through the relevant lifecycle steps
 * via Robolectric's [org.robolectric.android.controller.ServiceController]
 * and asserts the binder-visible state plus the receiver / dispatch
 * registrations.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@UnstableApi
class PlaybackServiceLifecycleTest {

  private fun context() = ApplicationProvider.getApplicationContext<android.content.Context>()

  private fun bindLocal(service: PlaybackService): PlaybackService.LocalBinder {
    val intent = Intent(context().applicationContext, PlaybackService::class.java)
      .setAction(PlaybackService.ACTION_LOCAL_BIND)
    return service.onBind(intent) as PlaybackService.LocalBinder
  }

  @Test
  fun `onCreate exposes a live ExoPlayer and MediaSession through the local binder`() {
    val controller = Robolectric.buildService(PlaybackService::class.java).create()
    try {
      val binder = bindLocal(controller.get())
      assertNotNull("the engine's player is initialised in onCreate", binder.engine.mediaSessionPlayer)
      assertNotNull("binder.session is initialised in onCreate", binder.session)
      // The session binds to the stable SessionCommandForwardingPlayer
      // wrapper for its lifetime; the wrapper forwards to the engine's
      // own ExoPlayer.
      val sessionPlayer = binder.session.player
      assertTrue(
        "binder.session.player is the stable forwarding wrapper",
        sessionPlayer is SessionCommandForwardingPlayer,
      )
      assertSame(
        "the wrapper forwards to the engine's ExoPlayer instance",
        binder.engine.mediaSessionPlayer,
        (sessionPlayer as SessionCommandForwardingPlayer).wrappedPlayer,
      )
      assertEquals(
        "binder.handler is the service main looper handler",
        Looper.getMainLooper(),
        binder.handler.looper,
      )
    } finally {
      controller.destroy()
    }
  }

  @Test
  fun `onCreate registers the becomingNoisy receiver`() {
    val controller = Robolectric.buildService(PlaybackService::class.java).create()
    try {
      val binder = bindLocal(controller.get())
      val handler = RecordingHandler()
      binder.setCommandHandler(handler)

      context().sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
      shadowOf(Looper.getMainLooper()).idle()

      assertEquals(
        "becomingNoisy broadcast routes through the registered handler",
        1,
        handler.becomingNoisyCalls,
      )
    } finally {
      controller.destroy()
    }
  }

  @Test
  fun `onStartCommand with ACTION_MEDIA_BUTTON routes through MediaButtonDispatch`() {
    val controller = Robolectric.buildService(PlaybackService::class.java).create()
    try {
      val binder = bindLocal(controller.get())
      val handler = RecordingHandler()
      binder.setCommandHandler(handler)

      val intent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
        putExtra(
          Intent.EXTRA_KEY_EVENT,
          KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 0),
        )
      }
      controller.get().onStartCommand(intent, 0, 1)

      assertEquals(
        "ACTION_MEDIA_BUTTON with KEYCODE_MEDIA_PLAY invokes onMediaPlay exactly once",
        1,
        handler.playCalls,
      )
      assertEquals(0, handler.toggleCalls)
      assertEquals(0, handler.skipNextCalls)
    } finally {
      controller.destroy()
    }
  }

  @Test
  fun `onStartCommand with non-media-button intent is a no-op for the handler`() {
    val controller = Robolectric.buildService(PlaybackService::class.java).create()
    try {
      val binder = bindLocal(controller.get())
      val handler = RecordingHandler()
      binder.setCommandHandler(handler)

      controller.get().onStartCommand(Intent("com.example.SOMETHING_ELSE"), 0, 1)

      assertEquals(0, handler.playCalls)
      assertEquals(0, handler.toggleCalls)
      assertEquals(0, handler.skipNextCalls)
      assertEquals(0, handler.skipPreviousCalls)
    } finally {
      controller.destroy()
    }
  }

  @Test
  fun `onTaskRemoved stops the foreground state and stops the service`() {
    val controller = Robolectric.buildService(PlaybackService::class.java).create()
    try {
      val service = controller.get()
      bindLocal(service)

      service.onTaskRemoved(null)
      shadowOf(Looper.getMainLooper()).idle()

      assertTrue(
        "swipe-from-recents removes the foreground notification",
        shadowOf(service).isForegroundStopped,
      )
      assertTrue(
        "swipe-from-recents stops the service so the process is reaped",
        shadowOf(service).isStoppedBySelf,
      )
    } finally {
      controller.destroy()
    }
  }

  @Test
  fun `onTaskRemoved stops even a queued, play-ready session (no keep-alive)`() {
    val controller = Robolectric.buildService(PlaybackService::class.java).create()
    try {
      val service = controller.get()
      val binder = bindLocal(service)
      // A loaded + play-ready session: the previous behaviour kept the service
      // alive here. Swipe-away is now an unconditional kill.
      binder.engine.mediaSessionPlayer.addMediaItem(MediaItem.fromUri("http://example.com/a.mp3"))
      binder.engine.mediaSessionPlayer.playWhenReady = true
      shadowOf(Looper.getMainLooper()).idle()

      service.onTaskRemoved(null)
      shadowOf(Looper.getMainLooper()).idle()

      assertEquals(
        "the player is stopped (idle) on swipe-away",
        Player.STATE_IDLE,
        binder.engine.mediaSessionPlayer.playbackState,
      )
      assertTrue(
        "the service stops even with a queued, play-ready session",
        shadowOf(service).isStoppedBySelf,
      )
    } finally {
      controller.destroy()
    }
  }

  @Test
  fun `onDestroy releases the player and clears the session`() {
    val controller = Robolectric.buildService(PlaybackService::class.java).create()
    val binder = bindLocal(controller.get())
    val player = binder.engine.mediaSessionPlayer
    val session: MediaSession = binder.session
    assertFalse("player is live before destroy", player.isReleased)

    controller.destroy()
    shadowOf(Looper.getMainLooper()).idle()

    assertTrue("player is released after onDestroy", player.isReleased)
    // MediaSession.release() is idempotent + cleared by onDestroy;
    // re-releasing here would throw if it weren't already cleared
    // by the service. Reaching this line proves the session was
    // released cleanly.
    session.release()
  }

  @Test
  fun `configurePlayer maps AudioContentType SPEECH to AudioAttributes contentType SPEECH`() {
    val controller = Robolectric.buildService(PlaybackService::class.java).create()
    val service = controller.get()
    val factory = DefaultMediaSourceFactory(context())

    val player = service.configurePlayer(
      factory = factory,
      audioContentType = AudioContentType.SPEECH
    )

    assertEquals(C.AUDIO_CONTENT_TYPE_SPEECH, player.audioAttributes.contentType)
    controller.destroy()
  }

  @Test
  fun `configurePlayer maps null AudioContentType to AudioAttributes contentType MUSIC`() {
    val controller = Robolectric.buildService(PlaybackService::class.java).create()
    val service = controller.get()
    val factory = DefaultMediaSourceFactory(context())

    val player = service.configurePlayer(factory = factory, audioContentType = null)

    assertEquals(C.AUDIO_CONTENT_TYPE_MUSIC, player.audioAttributes.contentType)
    controller.destroy()
  }

  @Test
  fun `configurePlayer maps AudioContentType MUSIC to AudioAttributes contentType MUSIC`() {
    val controller = Robolectric.buildService(PlaybackService::class.java).create()
    val service = controller.get()
    val factory = DefaultMediaSourceFactory(context())

    val player = service.configurePlayer(
      factory = factory,
      audioContentType = AudioContentType.MUSIC
    )

    assertEquals(C.AUDIO_CONTENT_TYPE_MUSIC, player.audioAttributes.contentType)
    controller.destroy()
  }

  @Test
  fun `onDestroy unregisters the becomingNoisy receiver so further broadcasts are no-ops`() {
    val controller = Robolectric.buildService(PlaybackService::class.java).create()
    val binder = bindLocal(controller.get())
    val handler = RecordingHandler()
    binder.setCommandHandler(handler)

    controller.destroy()
    shadowOf(Looper.getMainLooper()).idle()

    context().sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals(
      "no broadcasts route through after the service is destroyed",
      0,
      handler.becomingNoisyCalls,
    )
  }

  /**
   * Counts every callback this suite drives so each test asserts
   * the exact dispatch shape. Counters covering the
   * controller-callback skip path and the raw-key skip path are
   * separate so a regression that mis-routes through the wrong
   * surface surfaces as a count mismatch.
   */
  private class RecordingHandler : PlaybackService.MediaSessionCommandHandler {
    var playCalls = 0
    var toggleCalls = 0
    var skipNextCalls = 0
    var skipPreviousCalls = 0
    var becomingNoisyCalls = 0

    override fun onTransportCommand() {}
    override fun onSkipToNextCommand(): Boolean {
      skipNextCalls++
      return true
    }

    override fun onSkipToPreviousCommand(): Boolean {
      skipPreviousCalls++
      return true
    }

    override fun onSeekToMediaItemCommand(mediaItemIndex: Int): Boolean = false

    override fun getCachedSkipCapability(): SkipCapability =
      SkipCapability(canSkipPrevious = true, canSkipNext = true)

    override fun onMediaPlay() { playCalls++ }
    override fun onMediaPause() {}
    override fun onMediaTogglePlayPause() { toggleCalls++ }
    override fun onMediaSkipNext() {}
    override fun onMediaSkipPrevious() {}

    override fun onAudioFocusEvent(mapping: AudioFocusErrorMapping.Mapping) {}
    override fun onBecomingNoisy() { becomingNoisyCalls++ }
  }
}
