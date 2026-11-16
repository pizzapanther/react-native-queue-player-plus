package com.margelo.nitro.queueplayer

import android.content.Context
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric coverage for [NotificationProvider]. Posting a real
 * notification requires a live `MediaSession` + foreground service
 * with the device's NotificationManager — that path lives in the
 * demo cycle's lock-screen / notification suites.
 *
 * Unit-level scope: pin the constructor wiring (channel ID +
 * notification ID resolved from [PlaybackService] companions, channel-
 * name string-resource), confirm the provider subclasses Media3's
 * [DefaultMediaNotificationProvider] (so the prev / play-pause / next
 * compact view + scrub-progress expanded view ship for free), and
 * confirm the provider attaches cleanly when the service stands up
 * the BitmapLoader chain in `onCreate`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@UnstableApi
class NotificationProviderTest {

  @Test
  fun `provider constructs against application context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val provider = NotificationProvider(context)
    assertNotNull(provider)
  }

  @Test
  fun `provider subclasses Media3's DefaultMediaNotificationProvider`() {
    assertTrue(
      "the lib's provider extends Media3's default provider so the standard compact + expanded layout, action mapping, and scrub progress are inherited",
      DefaultMediaNotificationProvider::class.java.isAssignableFrom(NotificationProvider::class.java),
    )
  }

  @Test
  fun `PlaybackService channel and notification IDs are stable values the post-side surfaces consume`() {
    // The provider posts under the channel ID + notification ID the
    // `onCreate` `createNotificationChannel` call writes. Pinning them
    // here prevents a silent rename from splitting the channel
    // registration from the post.
    assertEquals(
      "com.margelo.nitro.queueplayer.playback",
      PlaybackService.NOTIFICATION_CHANNEL_ID,
    )
    assertEquals(1001, PlaybackService.NOTIFICATION_ID)
  }

  @Test
  fun `provider attaches to a fully-built PlaybackService without throwing`() {
    val controller = Robolectric.buildService(PlaybackService::class.java).create()
    try {
      // Service.onCreate has already constructed the player + session
      // + bitmap-loader chain + called setMediaNotificationProvider.
      // Reaching this line proves the provider's constructor is
      // compatible with the session BitmapLoader wiring (placeholder
      // → cache → data-source). Any incompatibility (resource ID
      // mismatch, missing context method, ICON_* enum drift) would
      // surface as an exception during onCreate.
      val service = controller.get()
      val intent = Intent(
        ApplicationProvider.getApplicationContext<Context>(),
        PlaybackService::class.java,
      ).setAction(PlaybackService.ACTION_LOCAL_BIND)
      val binder = service.onBind(intent) as PlaybackService.LocalBinder
      assertNotNull("session is wired up post-onCreate", binder.session)
    } finally {
      controller.destroy()
    }
  }
}
