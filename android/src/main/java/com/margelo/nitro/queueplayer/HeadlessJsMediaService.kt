package com.margelo.nitro.queueplayer

import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.os.PowerManager.WakeLock
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaLibraryService
import com.facebook.react.ReactApplication
import com.facebook.react.ReactHost
import com.facebook.react.ReactInstanceEventListener
import com.facebook.react.bridge.ReactContext
import com.facebook.react.bridge.UiThreadUtil
import com.facebook.react.jstasks.HeadlessJsTaskConfig
import com.facebook.react.jstasks.HeadlessJsTaskContext.Companion.getInstance
import com.facebook.react.jstasks.HeadlessJsTaskEventListener
import java.util.concurrent.CopyOnWriteArraySet

/**
 * `MediaLibraryService` base that can also drive a JS task on cold-
 * start. Subclasses override [getTaskConfig] to return a non-null
 * [HeadlessJsTaskConfig] when the inbound intent should boot a JS
 * handler (e.g. an Android Auto / Google Assistant
 * `onPlayFromSearch` cold-wake before any Activity has started a
 * React context). Default implementation returns `null` — the
 * service runs as a normal `MediaLibraryService` with no JS task.
 *
 * The React-context lifecycle is handled here:
 *  - If a React context already exists ([reactContext] non-null),
 *    [startTask] schedules the headless task on it directly.
 *  - Otherwise the service kicks the [ReactHost] into starting one
 *    and schedules the task once `onReactContextInitialized` fires.
 *
 * Active JS task IDs are tracked so the service can `stopSelf` once
 * every JS task has finished — important on devices where the OS
 * is aggressive about reclaiming foreground services that have no
 * remaining work.
 *
 * Subclasses receiving a [BroadcastReceiver]-routed intent that
 * triggers a task should call [acquireWakeLockNow] before returning
 * from `onReceive` so the device doesn't doze before the service
 * binds.
 */
@UnstableApi
public abstract class HeadlessJsMediaService :
  MediaLibraryService(),
  HeadlessJsTaskEventListener {

  /**
   * IDs of every JS task currently running for this service.
   * Reads + writes happen from [onStartCommand] (main thread) and
   * [onHeadlessJsTaskFinish] (also main per Headless contract);
   * `CopyOnWriteArraySet` keeps the snapshot read in
   * [stopSelf] cheap + lock-free.
   */
  internal val activeTasks: MutableSet<Int> = CopyOnWriteArraySet()

  /**
   * Set on the first task spawn so a re-entrant
   * [onStartCommand] for the same wake doesn't double-start the JS
   * handler. Reset is implicit — service destruction reclaims this
   * field along with the rest of the instance.
   */
  private var initialized = false

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val baseReturn = super.onStartCommand(intent, flags, startId)
    val taskConfig = getTaskConfig(intent)
    return if (!initialized && taskConfig != null) {
      initialized = true
      startTask(taskConfig)
      // Wake intents need to be redelivered if the OS kills + restarts
      // the service mid-spawn so the JS bridge boot completes.
      START_REDELIVER_INTENT
    } else {
      // Defer to the base service's restart policy on every other
      // path so MediaLibraryService's sticky behaviour is preserved.
      baseReturn
    }
  }

  /**
   * Build a [HeadlessJsTaskConfig] for [intent] when the intent
   * should trigger a JS-driven handler (e.g. an Auto-side
   * `onPlayFromSearch` cold wake). Return `null` to ignore the
   * intent at the headless layer — the service still runs as a
   * `MediaLibraryService`, just without spawning a JS task.
   *
   * Default returns `null`. Subclasses override to opt in.
   */
  protected open fun getTaskConfig(intent: Intent?): HeadlessJsTaskConfig? = null

  override fun onBind(intent: Intent?): IBinder? {
    return super.onBind(intent)
  }

  /**
   * Spawn [taskConfig] on the React context — creating one if the
   * host hasn't already. Must be invoked on the UI thread; callers
   * coming from a `BroadcastReceiver` should hop via
   * `UiThreadUtil.runOnUiThread` first.
   */
  protected fun startTask(taskConfig: HeadlessJsTaskConfig) {
    UiThreadUtil.assertOnUiThread()
    val context = reactContext
    if (context == null) {
      createReactContextAndScheduleTask(taskConfig)
    } else {
      invokeStartTask(context, taskConfig)
    }
  }

  private fun invokeStartTask(reactContext: ReactContext, taskConfig: HeadlessJsTaskConfig) {
    val headlessJsTaskContext = getInstance(reactContext)
    headlessJsTaskContext.addTaskEventListener(this)
    UiThreadUtil.runOnUiThread {
      val taskId = headlessJsTaskContext.startTask(taskConfig)
      activeTasks.add(taskId)
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    reactContext?.let { context ->
      val headlessJsTaskContext = getInstance(context)
      headlessJsTaskContext.removeTaskEventListener(this)
    }
    wakeLock?.release()
  }

  override fun onHeadlessJsTaskStart(taskId: Int): Unit = Unit

  override fun onHeadlessJsTaskFinish(taskId: Int) {
    activeTasks.remove(taskId)
    if (activeTasks.isEmpty()) {
      stopSelf()
    }
  }

  /**
   * The host's [ReactHost]. Returns `null` when the host
   * `Application` does not implement [ReactApplication] — production
   * Expo / RN consumers always do.
   */
  protected val reactHost: ReactHost?
    get() = (application as? ReactApplication)?.reactHost

  /**
   * The live [ReactContext] from the consumer app's [ReactHost], or
   * `null` when no context has been created yet (the service kicks
   * one off via [createReactContextAndScheduleTask] in that case) OR
   * when the host application isn't a [ReactApplication] at all.
   */
  protected val reactContext: ReactContext?
    get() = reactHost?.currentReactContext

  private fun createReactContextAndScheduleTask(taskConfig: HeadlessJsTaskConfig) {
    val host = reactHost ?: return
    host.addReactInstanceEventListener(
      object : ReactInstanceEventListener {
        override fun onReactContextInitialized(context: ReactContext) {
          invokeStartTask(context, taskConfig)
          host.removeReactInstanceEventListener(this)
        }
      }
    )
    host.start()
  }

  public companion object {
    /**
     * Process-wide partial wake lock acquired before a
     * `BroadcastReceiver`-routed task wakes the service so the
     * device doesn't doze before [startTask] gets to bind. Reused
     * across cold starts; released in [onDestroy].
     */
    @JvmStatic
    @Volatile
    public var wakeLock: WakeLock? = null

    /**
     * Maximum time the wake lock stays held before the OS releases
     * it automatically. Bounds a leak if the service fails to bind
     * + [onDestroy] never runs; comfortable headroom for the
     * BroadcastReceiver-routed task to wake the service + cross
     * into the foreground state where the foreground-service
     * lifecycle takes over the keep-alive guarantee.
     */
    private const val WAKE_LOCK_TIMEOUT_MS: Long = 10_000

    /**
     * Hold a partial wake lock until the service can take over.
     * Idempotent — re-entrancy from a fast double-wake holds at
     * most one lock. Caller is responsible for ensuring the
     * service eventually starts so [onDestroy] can release; the
     * [WAKE_LOCK_TIMEOUT_MS] bound is the safety net for a failed
     * start.
     */
    @JvmStatic
    public fun acquireWakeLockNow(context: Context) {
      val current = wakeLock
      if (current != null && current.isHeld) return
      val powerManager =
        checkNotNull(context.getSystemService(POWER_SERVICE) as PowerManager)
      wakeLock =
        powerManager
          .newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            HeadlessJsMediaService::class.java.canonicalName
          )
          .also { lock ->
            lock.setReferenceCounted(false)
            lock.acquire(WAKE_LOCK_TIMEOUT_MS)
          }
    }
  }
}
