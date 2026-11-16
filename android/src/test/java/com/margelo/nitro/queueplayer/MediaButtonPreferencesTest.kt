package com.margelo.nitro.queueplayer

import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/**
 * Robolectric coverage for the media button preferences the notification
 * compact view + Android Auto control row consume, produced by
 * [PlaybackService.buildMediaButtonPreferences] and read back off the
 * session's public `mediaButtonPreferences`.
 *
 * The lib supplies only the back / forward buttons. Play/pause belongs to
 * Media3's central slot: `DefaultMediaNotificationProvider` synthesizes a
 * state-aware play/pause from `COMMAND_PLAY_PAUSE`, and Android Auto fills
 * the empty central slot the same way — so a lib-supplied `SLOT_CENTRAL`
 * button is dropped on the notification and forces a static, non-toggling
 * icon on Android Auto.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@UnstableApi
class MediaButtonPreferencesTest {

  private lateinit var controller: ServiceController<PlaybackService>
  private lateinit var service: PlaybackService
  private lateinit var session: MediaSession

  @Before
  fun setUp() {
    controller = Robolectric.buildService(PlaybackService::class.java).create()
    service = controller.get()
    val intent = Intent(
      ApplicationProvider.getApplicationContext<android.content.Context>(),
      PlaybackService::class.java,
    ).setAction(PlaybackService.ACTION_LOCAL_BIND)
    val binder = service.onBind(intent) as PlaybackService.LocalBinder
    session = binder.session
  }

  @After
  fun tearDown() {
    controller.destroy()
  }

  @Test
  fun `track mode supplies previous plus next and leaves play-pause to Media3`() {
    service.applyRemoteControls(RemoteSkipMode.TRACK, 15_000L, 15_000L)
    val prefs = session.mediaButtonPreferences
    assertNoLibPlayPause(prefs)
    assertTrue(
      "previous occupies the back slot",
      prefs.any { it.slots.contains(CommandButton.SLOT_BACK) },
    )
    assertTrue(
      "next occupies the forward slot",
      prefs.any { it.slots.contains(CommandButton.SLOT_FORWARD) },
    )
  }

  @Test
  fun `interval mode supplies skip-back plus skip-forward and leaves play-pause to Media3`() {
    service.applyRemoteControls(RemoteSkipMode.INTERVAL, 15_000L, 10_000L)
    val prefs = session.mediaButtonPreferences
    assertNoLibPlayPause(prefs)
    assertTrue(
      "skip-back occupies the back slot",
      prefs.any { it.slots.contains(CommandButton.SLOT_BACK) },
    )
    assertTrue(
      "skip-forward occupies the forward slot",
      prefs.any { it.slots.contains(CommandButton.SLOT_FORWARD) },
    )
  }

  private fun assertNoLibPlayPause(prefs: List<CommandButton>) {
    assertFalse(
      "play/pause is Media3's central-slot button, never supplied by the lib",
      prefs.any { it.playerCommand == Player.COMMAND_PLAY_PAUSE },
    )
    assertFalse(
      "the lib never claims SLOT_CENTRAL — Media3 owns it",
      prefs.any { it.slots.contains(CommandButton.SLOT_CENTRAL) },
    )
  }
}
