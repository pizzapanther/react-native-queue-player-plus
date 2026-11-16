package com.margelo.nitro.queueplayer.cast.chromecast

import android.content.Context
import android.util.Log
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import androidx.mediarouter.media.MediaRouter.RouteInfo
import com.google.android.gms.cast.CastDevice
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession as GmsCastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.margelo.nitro.queueplayer.CastReceiver

/**
 * Chromecast backend — owns discovery via MediaRouter and session
 * lifecycle via GMS SessionManager, plus the transport calls that
 * address the SDK session directly. Queue load and transport-state
 * translation live in [ChromecastSession] and [RemoteMediaCallbacks].
 */
internal class ChromecastBackend(
  private val context: Context,
  internal val castContext: CastContext,
) {

  // ----- Callback slots wired by CastManager ----------------------------

  var onReceiversChanged: ((List<CastReceiver>) -> Unit)? = null
  var onSessionConnected: ((GmsCastSession, deviceId: String) -> Unit)? = null
  var onSessionDisconnected: ((reason: String) -> Unit)? = null
  var onSessionError: ((reason: String) -> Unit)? = null

  // ----- Internal state -------------------------------------------------

  private val mediaRouter = MediaRouter.getInstance(context)
  private val routeSelector: MediaRouteSelector = MediaRouteSelector.Builder()
    .addControlCategory(
      CastMediaControlIntent.categoryForCast(
        castContext.castOptions.receiverApplicationId
      )
    )
    .build()

  private val routesById = mutableMapOf<String, RouteInfo>()
  private val sessionManager = castContext.sessionManager
  private var sessionListener: SessionManagerListener<GmsCastSession>? = null
  private var discoveryActive = false

  private val mediaRouterCallback = object : MediaRouter.Callback() {
    override fun onRouteAdded(router: MediaRouter, route: RouteInfo) = sync()
    override fun onRouteChanged(router: MediaRouter, route: RouteInfo) = sync()
    override fun onRouteRemoved(router: MediaRouter, route: RouteInfo) = sync()

    private fun sync() {
      val routes = mediaRouter.routes.filter { it.matchesSelector(routeSelector) }
      routesById.clear()
      val receivers = mutableListOf<CastReceiver>()
      for (route in routes) {
        val device = CastDevice.getFromBundle(route.extras) ?: continue
        val id = "chromecast:${device.deviceId ?: route.id}"
        routesById[id] = route
        receivers.add(ChromecastReceiverMapper.map(device))
      }
      onReceiversChanged?.invoke(receivers)
    }
  }

  // ----- Lifecycle ------------------------------------------------------

  fun startDiscovery() {
    if (discoveryActive) return
    discoveryActive = true
    mediaRouter.addCallback(
      routeSelector,
      mediaRouterCallback,
      MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN
    )
    ensureSessionListener()
    // Seed initial snapshot from MediaRouter's existing known routes.
    val routes = mediaRouter.routes.filter { it.matchesSelector(routeSelector) }
    routesById.clear()
    val receivers = mutableListOf<CastReceiver>()
    for (route in routes) {
      val device = CastDevice.getFromBundle(route.extras) ?: continue
      val id = "chromecast:${device.deviceId ?: route.id}"
      routesById[id] = route
      receivers.add(ChromecastReceiverMapper.map(device))
    }
    onReceiversChanged?.invoke(receivers)
  }

  fun stopDiscovery() {
    if (!discoveryActive) return
    discoveryActive = false
    mediaRouter.removeCallback(mediaRouterCallback)
    // Drop the discovered set + publish empty so the receiver list clears
    // when scanning stops, matching the AirPlay backend. Re-opening
    // discovery re-seeds from MediaRouter's known routes in startDiscovery.
    routesById.clear()
    onReceiversChanged?.invoke(emptyList())
  }

  fun connect(receiverId: String) {
    val route = routesById[receiverId]
    if (route == null) {
      onSessionError?.invoke("receiver-not-found")
      return
    }
    mediaRouter.selectRoute(route)
  }

  fun disconnect() {
    sessionManager.endCurrentSession(true)
    mediaRouter.unselect(MediaRouter.UNSELECT_REASON_DISCONNECTED)
  }

  fun setVolume(value: Double) {
    val clamped = value.coerceIn(0.0, 1.0)
    runCatching { sessionManager.currentCastSession?.volume = clamped }
  }

  fun play() {
    sessionManager.currentCastSession?.remoteMediaClient?.play()
  }

  fun pause() {
    sessionManager.currentCastSession?.remoteMediaClient?.pause()
  }

  fun stop() {
    sessionManager.currentCastSession?.remoteMediaClient?.stop()
  }

  fun seekTo(positionMs: Long) {
    val client = sessionManager.currentCastSession?.remoteMediaClient ?: return
    client.seek(com.google.android.gms.cast.MediaSeekOptions.Builder().setPosition(positionMs).build())
  }

  // ----- Session listener -----------------------------------------------

  private fun ensureSessionListener() {
    if (sessionListener != null) return
    val listener = object : SessionManagerListener<GmsCastSession> {
      override fun onSessionStarting(session: GmsCastSession) {}
      override fun onSessionStarted(session: GmsCastSession, sessionId: String) {
        val deviceId = session.castDevice?.deviceId ?: ""
        onSessionConnected?.invoke(session, deviceId)
      }
      override fun onSessionStartFailed(session: GmsCastSession, error: Int) {
        onSessionError?.invoke("start-failed:$error")
      }
      override fun onSessionEnding(session: GmsCastSession) {}
      override fun onSessionEnded(session: GmsCastSession, error: Int) {
        onSessionDisconnected?.invoke(if (error == 0) "ended" else "ended:$error")
      }
      override fun onSessionResuming(session: GmsCastSession, sessionId: String) {}
      override fun onSessionResumed(session: GmsCastSession, wasSuspended: Boolean) {
        val deviceId = session.castDevice?.deviceId ?: ""
        onSessionConnected?.invoke(session, deviceId)
      }
      override fun onSessionResumeFailed(session: GmsCastSession, error: Int) {
        onSessionError?.invoke("resume-failed:$error")
      }
      override fun onSessionSuspended(session: GmsCastSession, reason: Int) {
        onSessionDisconnected?.invoke("suspended:$reason")
      }
    }
    sessionListener = listener
    sessionManager.addSessionManagerListener(listener, GmsCastSession::class.java)
  }
}
