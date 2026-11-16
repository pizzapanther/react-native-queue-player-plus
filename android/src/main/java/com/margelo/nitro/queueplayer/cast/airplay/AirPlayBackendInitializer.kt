package com.margelo.nitro.queueplayer.cast.airplay

import android.content.Context
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.startup.Initializer
import com.margelo.nitro.queueplayer.cast.CastBackends

/**
 * `androidx.startup` initializer for the AirPlay cast backend. Runs
 * at app startup before the first React Native activity, constructs
 * the [AirPlayBackend], registers it with [CastBackends], and wires
 * the [AirPlayDiscoveryLifecycleObserver] against
 * `ProcessLifecycleOwner.lifecycle` so discovery pauses while the app
 * is fully backgrounded.
 *
 * Registered via the lib's `AndroidManifest.xml` `<meta-data>` entry
 * under `androidx.startup.InitializationProvider`. The provider has
 * no predecessors, so it runs immediately on app start without
 * ordering constraints.
 *
 * Idempotency: `CastBackends.register` short-circuits when the same
 * backend instance is already registered, so even if
 * `androidx.startup` somehow ran us twice (it shouldn't) the worst
 * case is a no-op.
 */
class AirPlayBackendInitializer : Initializer<Unit> {

    override fun create(context: Context) {
        val appContext = context.applicationContext
        val backend = AirPlayBackend(appContext)
        CastBackends.register(backend)

        // ProcessLifecycleOwner is a process-scoped LifecycleOwner. Its
        // observers must be added on the main thread. androidx.startup
        // runs on the main thread by default, so this is safe — but
        // re-affirm with a comment because adding observers from a
        // background thread is a silent crash trap.
        ProcessLifecycleOwner.get().lifecycle.addObserver(AirPlayDiscoveryLifecycleObserver())
    }

    /** No predecessors. */
    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
