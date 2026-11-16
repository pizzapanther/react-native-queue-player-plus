package com.margelo.nitro.queueplayer.cast.airplay

import android.content.Context
import android.net.wifi.WifiManager

/**
 * Refcounted wrapper around `WifiManager.MulticastLock`. Holding a
 * multicast lock is a hard requirement for NSD discovery on AirPlay's
 * mDNS-based services — without it, the Wi-Fi chip silently drops
 * multicast packets and the discovery callback never sees the receivers
 * even though `_raop._tcp.` is being broadcast on the LAN.
 *
 * Refcounting matters because both `_raop._tcp.` and `_airplay._tcp.`
 * discoveries are run in parallel from the same `AirPlayDiscovery`,
 * and a future `:android-cast-dlna` would also want a multicast lock.
 * Acquiring the lock twice and releasing it twice should leave the lock
 * held while at least one caller still wants it.
 *
 * Thread-safe: every public method synchronizes on this instance because
 * the underlying `MulticastLock` is not itself thread-safe.
 *
 * @property context Application context. Used to grab the
 *   `WifiManager` system service. Pass the application context, not an
 *   activity context — the lock survives across activities.
 */
internal class MulticastLockHolder(context: Context) {

    private val wifiManager: WifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var lock: WifiManager.MulticastLock? = null
    private var refCount: Int = 0

    /**
     * Acquire a reference. The first call creates the underlying
     * `MulticastLock` and acquires it; subsequent calls just bump the
     * refcount.
     */
    @Synchronized
    fun acquire() {
        if (refCount == 0) {
            lock = wifiManager.createMulticastLock(LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        refCount++
    }

    /**
     * Release a reference. The last call releases and clears the
     * underlying lock. Safe to call when refcount is already 0 — the
     * lock is just absent.
     */
    @Synchronized
    fun release() {
        if (refCount == 0) return
        refCount--
        if (refCount == 0) {
            lock?.let { if (it.isHeld) it.release() }
            lock = null
        }
    }

    /**
     * Diagnostic — `true` while the underlying lock is held. Used by
     * tests; production code should not branch on this.
     */
    @Synchronized
    fun isHeld(): Boolean = lock?.isHeld == true

    private companion object {
        const val LOCK_TAG = "QueuePlayer.AirPlayDiscovery"
    }
}
