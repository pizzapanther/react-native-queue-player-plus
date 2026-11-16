package com.margelo.nitro.queueplayer

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Robolectric coverage for [PlaybackService.buildSessionActivityPendingIntent]
 * and the `setSessionActivity` wiring on the MediaSession built in
 * [PlaybackService.onCreate]. The notification body tap on the
 * media-style notification is what makes
 * `MediaSession.sessionActivity` reach `DefaultMediaNotificationProvider`,
 * so the contract is: when the consumer app exposes a launcher
 * activity, the session carries the launcher PendingIntent; when it
 * does not, the session carries no session activity and the service
 * still constructs cleanly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@UnstableApi
class PlaybackServiceSessionActivityTest {

  private fun context(): Context = ApplicationProvider.getApplicationContext()

  /**
   * Register a MAIN/LAUNCHER activity for the test package so
   * `PackageManager.getLaunchIntentForPackage(packageName)` resolves
   * to a real component. Mirrors what every published consumer app
   * declares in its manifest.
   */
  private fun registerLauncherActivity(): ComponentName {
    val ctx = context()
    val launcher = ComponentName(ctx.packageName, "${ctx.packageName}.LauncherActivity")
    val shadowPm = shadowOf(ctx.packageManager)
    shadowPm.addActivityIfNotPresent(launcher)
    val filter = IntentFilter(Intent.ACTION_MAIN).apply {
      addCategory(Intent.CATEGORY_LAUNCHER)
    }
    shadowPm.addIntentFilterForActivity(launcher, filter)
    return launcher
  }

  @Test
  fun `onCreate wires the launcher PendingIntent as the MediaSession sessionActivity`() {
    val launcher = registerLauncherActivity()
    val controller = Robolectric.buildService(PlaybackService::class.java).create()
    try {
      val service = controller.get()
      val intent = Intent(context(), PlaybackService::class.java)
        .setAction(PlaybackService.ACTION_LOCAL_BIND)
      val binder = service.onBind(intent) as PlaybackService.LocalBinder

      val sessionActivity: PendingIntent? = binder.session.sessionActivity
      assertNotNull(
        "session activity is wired when the consumer exposes a launcher",
        sessionActivity,
      )

      val saved = shadowOf(sessionActivity!!).savedIntent
      assertEquals(
        "wrapped intent targets the consumer app package",
        launcher.packageName,
        saved.component?.packageName,
      )
      val expectedFlags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
      assertEquals(
        "wrapped intent carries SINGLE_TOP | CLEAR_TOP",
        expectedFlags,
        saved.flags and expectedFlags,
      )
      val piFlags = shadowOf(sessionActivity).flags
      assertTrue(
        "PendingIntent is built with FLAG_IMMUTABLE",
        piFlags and PendingIntent.FLAG_IMMUTABLE != 0,
      )
      assertTrue(
        "PendingIntent is built with FLAG_UPDATE_CURRENT",
        piFlags and PendingIntent.FLAG_UPDATE_CURRENT != 0,
      )
    } finally {
      controller.destroy()
    }
  }

  @Test
  fun `onCreate leaves sessionActivity null when the consumer exposes no launcher`() {
    // Robolectric's default test app declares no MAIN/LAUNCHER
    // activity, so `PackageManager.getLaunchIntentForPackage` returns
    // null and the `also { ... }.let(...)` branch in the service
    // skips `setSessionActivity`. The service must still construct.
    val controller = Robolectric.buildService(PlaybackService::class.java).create()
    try {
      val service = controller.get()
      val intent = Intent(context(), PlaybackService::class.java)
        .setAction(PlaybackService.ACTION_LOCAL_BIND)
      val binder = service.onBind(intent) as PlaybackService.LocalBinder

      assertNull(
        "session activity is unset when the consumer is launcher-less",
        binder.session.sessionActivity,
      )
      assertNotNull("service still exposes a live MediaSession", binder.session)
    } finally {
      controller.destroy()
    }
  }
}
