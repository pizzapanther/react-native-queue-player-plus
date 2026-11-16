package com.margelo.nitro.queueplayer.cast.airplay

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.margelo.nitro.queueplayer.cast.DiscoveryState

/**
 * Process-lifecycle observer that pauses AirPlay discovery while the
 * host process is fully backgrounded. Multicast scans are battery-
 * expensive and the picker UI cannot consume the receiver list while
 * the app is invisible, so we stop scanning until the user comes back.
 *
 * Wired in by [AirPlayBackendInitializer] against
 * `androidx.lifecycle.ProcessLifecycleOwner`. The observer is
 * deliberately tiny — it only flips the shared
 * [DiscoveryState.foregrounded] flag. The actual scan start/stop is
 * driven by [AirPlayBackend], which observes
 * [DiscoveryState.effective] (= `intent && foregrounded`) and reacts.
 *
 * The observer never *initiates* discovery — it only reflects whether
 * the process is in the foreground. If the JS layer never called
 * `startDiscovery()`, this observer's transitions are still recorded
 * (so a future call sees the right effective state immediately) but no
 * actual scan happens because intent is still false.
 *
 * Process scope: lifetime is the entire process. There is no
 * `removeObserver` path because once the process is alive we always
 * want to know its foreground/background state. The cost is one
 * weak callback registration on `ProcessLifecycleOwner.lifecycle`.
 */
internal class AirPlayDiscoveryLifecycleObserver : DefaultLifecycleObserver {

    /**
     * Process moved into the foreground (the user can see the app).
     * Flip [DiscoveryState.foregrounded] to `true`. If [DiscoveryState]'s
     * `intent` was already on, this re-enables effective discovery and
     * `AirPlayBackend` will resume the scan.
     */
    override fun onStart(owner: LifecycleOwner) {
        DiscoveryState.setForegrounded(true)
    }

    /**
     * Process moved fully into the background (no visible activities).
     * Flip [DiscoveryState.foregrounded] to `false`. If discovery was
     * effective, this disables it and `AirPlayBackend` stops the scan
     * within one StateFlow turn (typically <1ms).
     *
     * Subtle: `ProcessLifecycleOwner.onStop` fires after a 700ms delay
     * (the framework debounces brief activity transitions like
     * configuration changes). That delay is by design — it avoids
     * tearing down discovery for what is actually a screen rotation.
     */
    override fun onStop(owner: LifecycleOwner) {
        DiscoveryState.setForegrounded(false)
    }
}
