package com.margelo.nitro.queueplayer.cast.net

import android.net.ConnectivityManager
import android.net.NetworkRequest
import android.os.Handler
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowConnectivityManager

/**
 * A [ShadowConnectivityManager] that keeps the [NetworkRequest] passed to
 * `registerNetworkCallback`; the stock shadow discards it and stores only
 * the callback.
 */
@Implements(ConnectivityManager::class)
class ShadowConnectivityManagerRecordingRequests : ShadowConnectivityManager() {

  val requests = mutableListOf<NetworkRequest>()

  override fun registerNetworkCallback(
    request: NetworkRequest,
    networkCallback: ConnectivityManager.NetworkCallback,
    handler: Handler?,
  ) {
    requests.add(request)
    super.registerNetworkCallback(request, networkCallback, handler)
  }
}
