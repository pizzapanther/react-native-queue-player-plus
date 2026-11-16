package com.margelo.nitro.queueplayer.cast.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.net.InetAddress

/**
 * `currentAddress` is the Wi-Fi network's IPv4 address: the callback is
 * registered for Wi-Fi networks only, the `start()` seed uses the active
 * network only when it is Wi-Fi and not a VPN, and the address follows
 * that network's link properties until it is lost.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [ShadowConnectivityManagerRecordingRequests::class])
class LocalAddressMonitorTest {

  private val context: Context = ApplicationProvider.getApplicationContext()
  private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
  private val shadowCm: ShadowConnectivityManagerRecordingRequests = Shadow.extract(cm)

  // The shadow's default active network has netId 0; its transport is
  // whatever capabilities the test puts on it. Any other netId is a
  // network that exists but is not the active one.
  private val activeNetwork: Network = ShadowNetwork.newInstance(0)
  private val otherNetwork: Network = ShadowNetwork.newInstance(1)

  private fun describe(network: Network, transports: List<Int>, ipv4: String, vpn: Boolean = false) {
    val capabilities = ShadowNetworkCapabilities.newInstance()
    transports.forEach { shadowOf(capabilities).addTransportType(it) }
    if (vpn) shadowOf(capabilities).removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    shadowCm.setNetworkCapabilities(network, capabilities)
    shadowCm.setLinkProperties(network, linkProperties(ipv4))
  }

  private fun linkProperties(vararg addresses: String): LinkProperties {
    val linkAddresses = addresses.map { literal ->
      val inet = InetAddress.getByName(literal)
      ReflectionHelpers.callConstructor(
        LinkAddress::class.java,
        ClassParameter.from(InetAddress::class.java, inet),
        ClassParameter.from(Int::class.javaPrimitiveType, if (inet.address.size == 4) 24 else 64),
      )
    }
    return LinkProperties().apply { setLinkAddresses(linkAddresses) }
  }

  @Test
  fun `the callback is registered for Wi-Fi networks only`() {
    LocalAddressMonitor(context).start()

    val request = shadowCm.requests.single()
    assertTrue(request.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
    assertFalse(request.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
    assertTrue(request.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))
  }

  @Test
  fun `start seeds the address from a Wi-Fi active network`() {
    describe(activeNetwork, listOf(NetworkCapabilities.TRANSPORT_WIFI), "192.168.1.10")

    val monitor = LocalAddressMonitor(context).also { it.start() }

    assertEquals("192.168.1.10", monitor.currentAddress)
  }

  @Test
  fun `start does not seed the address from a cellular active network`() {
    describe(otherNetwork, listOf(NetworkCapabilities.TRANSPORT_WIFI), "192.168.1.10")
    describe(activeNetwork, listOf(NetworkCapabilities.TRANSPORT_CELLULAR), "10.0.0.5")

    val monitor = LocalAddressMonitor(context).also { it.start() }

    assertNull("a cellular address is unreachable from a LAN receiver", monitor.currentAddress)
  }

  @Test
  fun `start does not seed the address from a VPN over Wi-Fi`() {
    describe(
      activeNetwork,
      listOf(NetworkCapabilities.TRANSPORT_VPN, NetworkCapabilities.TRANSPORT_WIFI),
      "10.8.0.2",
      vpn = true,
    )

    val monitor = LocalAddressMonitor(context).also { it.start() }

    assertNull("a tunnel address is unreachable from a LAN receiver", monitor.currentAddress)
  }

  @Test
  fun `start registers the callback and seeds nothing when there is no active network`() {
    shadowCm.setDefaultNetworkActive(false)

    val monitor = LocalAddressMonitor(context).also { it.start() }

    assertEquals(1, shadowCm.networkCallbacks.size)
    assertNull(monitor.currentAddress)
  }

  @Test
  fun `a matched network's link properties set the address and its loss clears it`() {
    shadowCm.setDefaultNetworkActive(false)
    val monitor = LocalAddressMonitor(context).also { it.start() }
    val callback = shadowCm.networkCallbacks.single()

    callback.onLinkPropertiesChanged(otherNetwork, linkProperties("192.168.1.20"))
    assertEquals("192.168.1.20", monitor.currentAddress)

    callback.onLost(otherNetwork)
    assertNull(monitor.currentAddress)
  }

  /** A Wi-Fi handover reports the new network's link before the old network
   *  is lost; the loss of the old one must not clear the new address. */
  @Test
  fun `losing a network other than the one that supplied the address keeps it`() {
    shadowCm.setDefaultNetworkActive(false)
    val monitor = LocalAddressMonitor(context).also { it.start() }
    val callback = shadowCm.networkCallbacks.single()
    val oldWifi = otherNetwork
    val newWifi = ShadowNetwork.newInstance(2)

    callback.onLinkPropertiesChanged(oldWifi, linkProperties("192.168.1.20"))
    callback.onLinkPropertiesChanged(newWifi, linkProperties("192.168.2.30"))
    callback.onLost(oldWifi)
    assertEquals("192.168.2.30", monitor.currentAddress)

    callback.onLost(newWifi)
    assertNull(monitor.currentAddress)
  }

  @Test
  fun `the address is the first non-loopback IPv4 among the link addresses`() {
    shadowCm.setDefaultNetworkActive(false)
    val monitor = LocalAddressMonitor(context).also { it.start() }
    val callback = shadowCm.networkCallbacks.single()

    callback.onLinkPropertiesChanged(otherNetwork, linkProperties("fe80::1", "127.0.0.1", "192.168.1.20", "192.168.1.21"))
    assertEquals("192.168.1.20", monitor.currentAddress)

    callback.onLinkPropertiesChanged(otherNetwork, linkProperties("fe80::1"))
    assertNull(monitor.currentAddress)
  }

  @Test
  fun `stop unregisters the callback`() {
    val monitor = LocalAddressMonitor(context).also { it.start() }
    assertEquals(1, shadowCm.networkCallbacks.size)

    monitor.stop()

    assertTrue(shadowCm.networkCallbacks.isEmpty())
  }
}
