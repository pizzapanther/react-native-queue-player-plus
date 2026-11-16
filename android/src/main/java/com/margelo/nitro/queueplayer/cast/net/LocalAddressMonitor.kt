package com.margelo.nitro.queueplayer.cast.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.Inet4Address

/**
 * Surfaces the host's Wi-Fi IPv4 address. Owned by `LocalMediaServer`,
 * which exposes it to backends as `currentLanAddress`.
 *
 * Detection via [ConnectivityManager.NetworkCallback] on a request for
 * `TRANSPORT_WIFI` (the request's default capabilities exclude VPNs), so
 * only a Wi-Fi network's address is ever served, and a cellular network
 * coming or going neither replaces nor clears it.
 */
class LocalAddressMonitor(private val context: Context) {

  @Volatile
  var currentAddress: String? = null
    private set

  /**
   * The network whose link properties supplied [currentAddress]. Two Wi-Fi
   * networks overlap during a handover — the new one reports its link before
   * the old one is lost — so a loss clears the address only when it is the
   * network the address came from.
   */
  private var addressNetwork: Network? = null

  private var registered = false
  private val callback = object : ConnectivityManager.NetworkCallback() {
    override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
      updateAddress(network, linkProperties)
    }

    override fun onLost(network: Network) {
      if (network == addressNetwork) updateAddress(null, null)
    }
  }

  fun start() {
    if (registered) return
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
      ?: return
    val request = NetworkRequest.Builder()
      .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
      .build()
    cm.registerNetworkCallback(request, callback)
    registered = true
    // Seed synchronously so `currentAddress` is populated immediately —
    // the cast handoff reads it right after connect, before the first
    // async onLinkPropertiesChanged lands. The active network is used
    // only when it passes the request's own filter (Wi-Fi, not a VPN:
    // a VPN over Wi-Fi carries the Wi-Fi transport but a tunnel address).
    // When Wi-Fi is up but not the default network, the address arrives
    // with that network's first onLinkPropertiesChanged instead.
    val active = cm.activeNetwork ?: return
    val caps = cm.getNetworkCapabilities(active) ?: return
    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
      caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    ) {
      updateAddress(active, cm.getLinkProperties(active))
    }
  }

  fun stop() {
    if (!registered) return
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
      ?: return
    cm.unregisterNetworkCallback(callback)
    registered = false
  }

  private fun updateAddress(network: Network?, linkProperties: LinkProperties?) {
    val ipv4 = linkProperties?.linkAddresses
      ?.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
      ?.address?.hostAddress
    addressNetwork = if (ipv4 != null) network else null
    currentAddress = ipv4
  }
}
