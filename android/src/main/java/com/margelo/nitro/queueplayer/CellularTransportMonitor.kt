package com.margelo.nitro.queueplayer

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * Reports whether the default network is cellular, and fires when that
 * changes.
 *
 * Prefetch downloads whole upcoming tracks, so a consumer that has turned
 * `allowsCellularAccess` off needs the window to empty the moment the device
 * hands off to cellular — otherwise a download started on Wi-Fi keeps pulling
 * over the metered link until some unrelated event reschedules. Reading the
 * transport per reschedule instead would put a synchronous binder call on the
 * app looper on every skip and track advance.
 *
 * A VPN carries its underlying network's transports alongside `TRANSPORT_VPN`,
 * so a cellular-backed VPN still reads as cellular here.
 *
 * Unknown connectivity reads as not-cellular, so a missing service or a
 * revoked callback never silently stops prefetch.
 */
internal class CellularTransportMonitor(private val context: Context) {

  /**
   * Fired when [isOnCellular] flips, on the ConnectivityManager callback
   * thread — a listener that touches main-confined state has to hop.
   */
  var onTransportChanged: (() -> Unit)? = null

  @Volatile
  var isOnCellular: Boolean = false
    private set

  private var registered = false

  private val callback = object : ConnectivityManager.NetworkCallback() {
    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
      update(caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
    }

    override fun onLost(network: Network) {
      update(false)
    }
  }

  fun start() {
    if (registered) return
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
      ?: return
    // The registration can throw (`TooManyRequestsException` once a process
    // holds 100 callbacks); prefetch then runs without cellular awareness
    // rather than `configure()` failing.
    if (runCatching { cm.registerDefaultNetworkCallback(callback) }.isFailure) return
    registered = true
    // Seed from the active network: the first reschedule can land before the
    // first callback does.
    val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
    isOnCellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
  }

  fun stop() {
    if (!registered) return
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    registered = false
    isOnCellular = false
    cm?.let { runCatching { it.unregisterNetworkCallback(callback) } }
  }

  private fun update(onCellular: Boolean) {
    if (onCellular == isOnCellular) return
    isOnCellular = onCellular
    onTransportChanged?.invoke()
  }
}
