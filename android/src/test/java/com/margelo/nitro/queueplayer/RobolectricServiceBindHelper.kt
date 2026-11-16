package com.margelo.nitro.queueplayer

import android.content.ComponentName
import android.content.Context
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.robolectric.Robolectric
import org.robolectric.Shadows
import org.robolectric.android.controller.ServiceController

/**
 * Robolectric helper: spin up the in-process [PlaybackService] and
 * drive [TrackPlayer.ensureServiceBound] so a unit test can exercise
 * [TrackPlayer.configureInternal] (which now requires the service
 * binder to be live).
 *
 * Robolectric's `ShadowApplication.bindService` does not instantiate
 * the target service by default — it records the bind request and
 * delivers a stub `onServiceConnected(componentName, null)` unless
 * the test pre-registers the real binder via
 * `setComponentNameAndServiceForBindServiceForIntent`. The helper:
 *
 * 1. Builds the service via `Robolectric.buildService(...).create()`
 *    so `PlaybackService.onCreate` runs and the inner `LocalBinder`
 *    is initialised.
 * 2. Calls `service.onBind(intent)` with the lib's
 *    `ACTION_LOCAL_BIND` action to obtain the real `LocalBinder`.
 * 3. Pre-registers the (component, binder) pair on the shadow
 *    application so the next `bindService` call delivers the real
 *    binder to `onServiceConnected`.
 * 4. Drives `ensureServiceBound` from `Dispatchers.Default` while
 *    pumping the main looper, since `runBlocking` on the main
 *    thread would otherwise starve the connection callback.
 *
 * Tests must call [PlaybackServiceTestHandle.destroy] in `@After`
 * to release the [androidx.media3.session.MediaSession] — Media3
 * enforces a unique session ID per JVM and a leaked session
 * blocks the next [PlaybackService.onCreate] in the same run.
 *
 * Standard Robolectric service-lifecycle driving — no test
 * injection point on the lib side.
 */
@UnstableApi
internal class PlaybackServiceTestHandle(
  private val controller: ServiceController<PlaybackService>,
  private val binder: PlaybackService.LocalBinder,
) {
  /** The bound service's live engine, for asserting local-path transport. */
  val engine: PlaybackEngine get() = binder.engine

  fun destroy() {
    controller.destroy()
  }
}

@UnstableApi
internal fun bindPlaybackServiceForTest(
  player: TrackPlayer,
  context: Context,
  timeoutMs: Long = 5_000
): PlaybackServiceTestHandle {
  val controller = Robolectric.buildService(PlaybackService::class.java).create()
  val service = controller.get()
  val intent = android.content.Intent(context.applicationContext, PlaybackService::class.java)
    .setAction(PlaybackService.ACTION_LOCAL_BIND)
  val binder = service.onBind(intent)!!
  val componentName = ComponentName(context.applicationContext, PlaybackService::class.java)
  Shadows.shadowOf(context.applicationContext as android.app.Application)
    .setComponentNameAndServiceForBindServiceForIntent(intent, componentName, binder)

  runBlocking {
    val scope = CoroutineScope(Dispatchers.Default)
    val deferred = scope.async { player.ensureServiceBound(context) }
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!deferred.isCompleted && System.currentTimeMillis() < deadline) {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      Thread.sleep(5)
    }
    deferred.await()
  }

  return PlaybackServiceTestHandle(controller, binder as PlaybackService.LocalBinder)
}
