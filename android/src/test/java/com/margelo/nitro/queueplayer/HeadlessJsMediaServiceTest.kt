package com.margelo.nitro.queueplayer

import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric coverage for [HeadlessJsMediaService]'s testable
 * surface: the default no-task `getTaskConfig` contract and the
 * process-wide wake-lock helper.
 *
 * The full `onStartCommand` chain routes through Media3's
 * `MediaLibraryService.onStartCommand` and into native code that
 * Robolectric can't satisfy, so it is out of scope here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@UnstableApi
class HeadlessJsMediaServiceTest {

  @After
  fun tearDown() {
    HeadlessJsMediaService.wakeLock?.let { lock ->
      if (lock.isHeld) lock.release()
    }
    HeadlessJsMediaService.wakeLock = null
  }

  @Test
  fun `default getTaskConfig returns null — services opt in by overriding`() {
    val service = NoOpHeadlessJsMediaService()
    assertNull(
      "default impl returns null so subclasses without an override " +
        "behave as a plain MediaLibraryService",
      service.getTaskConfigForTest(Intent())
    )
  }

  @Test
  fun `activeTasks starts empty`() {
    val service = NoOpHeadlessJsMediaService()
    assertTrue(
      "fresh service has no JS tasks scheduled",
      service.activeTasks.isEmpty()
    )
  }

  @Test
  fun `acquireWakeLockNow holds at most one wake lock across re-entry`() {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    HeadlessJsMediaService.acquireWakeLockNow(context)
    val first = HeadlessJsMediaService.wakeLock
    assertNotNull("first acquire creates a wake lock", first)
    assertTrue("first lock is held", first!!.isHeld)

    HeadlessJsMediaService.acquireWakeLockNow(context)
    val second = HeadlessJsMediaService.wakeLock
    assertSame(
      "second acquire while held does NOT replace the lock",
      first,
      second
    )
    assertTrue("lock still held after re-entry", second!!.isHeld)
  }

  @Test
  fun `acquireWakeLockNow re-acquires after the prior lock has been released`() {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    HeadlessJsMediaService.acquireWakeLockNow(context)
    val first = HeadlessJsMediaService.wakeLock
    assertNotNull(first)
    first!!.release()
    assertFalse(first.isHeld)

    HeadlessJsMediaService.acquireWakeLockNow(context)
    val second = HeadlessJsMediaService.wakeLock
    assertNotNull("released lock triggers a fresh acquire", second)
    assertTrue("re-acquired lock is held", second!!.isHeld)
  }

  /** Exposes [HeadlessJsMediaService.getTaskConfig] for the test —
   *  the protected modifier on the production member would
   *  otherwise be unreachable from this same package because the
   *  protected scope is class-derivation, not package, in Kotlin. */
  internal class NoOpHeadlessJsMediaService : HeadlessJsMediaService() {
    override fun onGetSession(
      controllerInfo: MediaSession.ControllerInfo
    ): MediaLibraryService.MediaLibrarySession? = null

    fun getTaskConfigForTest(intent: Intent?) = getTaskConfig(intent)
  }
}
