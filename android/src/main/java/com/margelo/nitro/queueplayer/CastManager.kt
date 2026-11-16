package com.margelo.nitro.queueplayer

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import com.facebook.proguard.annotations.DoNotStrip
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession as GmsCastSession
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.margelo.nitro.NitroModules
import com.margelo.nitro.core.Promise
import com.margelo.nitro.queueplayer.cast.AudioRouteState
import com.margelo.nitro.queueplayer.cast.CastBackend
import com.margelo.nitro.queueplayer.cast.CastBackends
import com.margelo.nitro.queueplayer.cast.CastSinkRouter
import com.margelo.nitro.queueplayer.cast.PlaybackStateRouter
import com.margelo.nitro.queueplayer.cast.chromecast.ChromecastSession as RoutedChromecastSession
import com.margelo.nitro.queueplayer.cast.CastSession as RoutedCastSession
import com.margelo.nitro.queueplayer.cast.airplay.AirPlay2JNI
import com.margelo.nitro.queueplayer.cast.airplay.AirPlayBackend
import com.margelo.nitro.queueplayer.cast.airplay.AirPlayReceiverMapper
import com.margelo.nitro.queueplayer.cast.airplay.AirplayJNI
import com.margelo.nitro.queueplayer.cast.chromecast.ChromecastBackend
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// Android 16+ Local Network Protections permission. String literal so the
// reference doesn't depend on the compile SDK exposing the constant.
private const val ACCESS_LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

/**
 * `CastManager` HybridObject. Owns the Chromecast + AirPlay backends and
 * bridges them to the Nitro spec.
 *
 * - Chromecast: routed through `ChromecastBackend`, GMS-availability
 *   gated; on devices without Play Services, `getSupportedProtocols`
 *   omits `chromecast` and the connect path rejects.
 * - AirPlay 1/2: routed through `AirPlayBackend`, registered at process
 *   start by `AirPlayBackendInitializer`. The AAR carries libraop +
 *   libairplay2 for arm64-v8a + x86_64 only; on other ABIs
 *   `AirplayJNI.isAvailable` / `AirPlay2JNI.isAvailable` return false
 *   and the protocol is omitted from `getSupportedProtocols`.
 */
@DoNotStrip
class CastManager : HybridCastManagerSpec() {

  private val context: Context = NitroModules.applicationContext
    ?: throw IllegalStateException("Nitro applicationContext unavailable")

  private val mainHandler = Handler(Looper.getMainLooper())

  // Executor handed to `CastContext.getSharedInstance(context, executor)`
  // for the SDK's internal init work. The getSharedInstance CALL itself,
  // and every MediaRouter / SessionManager touch, must happen on the main
  // thread; this executor only runs the async handshake the SDK offloads.
  private val castInitExecutor = Executors.newSingleThreadExecutor { r ->
    Thread(r, "CastInit").apply { isDaemon = true }
  }

  // Cast SDK init is lazy; only paid for on first cast touch. The state
  // below is confined to the main thread (see `withChromecastBackend`).
  @Volatile
  private var chromecast: ChromecastBackend? = null
  private var castInitStarted = false
  private val pendingBackendBlocks = mutableListOf<(ChromecastBackend) -> Unit>()
  private val gmsAvailable: Boolean by lazy {
    val gms = GoogleApiAvailability.getInstance()
    gms.isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
  }

  // Listener registries.
  private val routeListeners = ConcurrentHashMap<Long, (CastRoute) -> Unit>()
  private val receiversListeners = ConcurrentHashMap<Long, (Array<CastReceiver>) -> Unit>()
  private val discoveryStateListeners = ConcurrentHashMap<Long, (CastDiscoveryState) -> Unit>()
  private val sessionDiedListeners = ConcurrentHashMap<Long, (CastSessionDiedEvent) -> Unit>()
  private val lnaListeners = ConcurrentHashMap<Long, (CastLocalNetworkPermissionEvent) -> Unit>()
  private val nextListenerId = AtomicLong(0)

  @Volatile
  private var discoveryActive = false

  @Volatile
  private var currentRoute: CastRoute = localFallbackRoute()

  // Per-backend receiver lists are merged into [receiversSnapshot] on
  // every update so the JS layer sees a single combined surface.
  @Volatile
  private var chromecastReceivers: List<CastReceiver> = emptyList()

  @Volatile
  private var airplayReceivers: List<CastReceiver> = emptyList()

  @Volatile
  private var receiversSnapshot: Array<CastReceiver> = emptyArray()

  // Active AirPlay session — populated by [connect] for an
  // `airplay:` receiver, cleared by [disconnect] or a death callback.
  // `AtomicReference` because the death callback fires from a native
  // drain thread without a happens-before edge to the connect-thread
  // assignment.
  private val activeAirplaySession = AtomicReference<RoutedCastSession?>(null)

  /**
   * Active Chromecast remote-player session, set by
   * [wireChromecastCallbacks] on `onSessionConnected` and cleared on
   * disconnect / error. Mirrored into `PlaybackStateRouter` so the
   * transport-side shim sees the active remote without going through
   * a Nitro module lookup.
   */
  private val activeChromecastSession = AtomicReference<RoutedChromecastSession?>(null)

  // Pending Chromecast connect promise — resolved when the SDK fires
  // `onSessionConnected`, rejected when `onSessionError` fires before.
  private val pendingChromecastConnect = AtomicReference<Promise<Unit>?>(null)

  private val airplayScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  // Audio-route observer is cheap (one AudioDeviceCallback registration);
  // start at CastManager construction so `getCurrentAudioRoute` returns
  // a populated value on first read without needing a discovery scan.
  private val audioRouteState = AudioRouteState(context).also { it.start() }

  private fun airplayBackend(): AirPlayBackend? =
    CastBackends.byProtocol("airplay") as? AirPlayBackend

  private fun airplayAvailable(): Boolean =
    airplayBackend() != null && (AirplayJNI.isAvailable || AirPlay2JNI.isAvailable)

  /**
   * Run a Chromecast backend operation on the main thread, lazily
   * initialising the Cast SDK on first use. The SDK + MediaRouter are
   * main-thread-only, so `CastContext.getSharedInstance(context,
   * executor)` is CALLED on the main thread — it returns a Task and runs
   * the heavy init on `castInitExecutor`, and the backend is built back
   * on main in the success callback. Operations requested before init
   * finishes are queued and drained once the backend is ready. No-op when
   * Google Play services are unavailable.
   */
  private fun withChromecastBackend(block: (ChromecastBackend) -> Unit) {
    mainHandler.post {
      if (!gmsAvailable) return@post
      chromecast?.let { block(it); return@post }
      pendingBackendBlocks.add(block)
      if (castInitStarted) return@post
      castInitStarted = true
      try {
        CastContext.getSharedInstance(context, castInitExecutor)
          .addOnSuccessListener { castContext ->
            // The backend ctor touches MediaRouter (main-thread only) and
            // the init-state below is main-confined; post so both hold
            // regardless of which thread GMS dispatches this listener on.
            mainHandler.post { onCastContextReady(castContext) }
          }
          .addOnFailureListener { e ->
            android.util.Log.w("CastManager", "Cast SDK init failed: $e")
            mainHandler.post {
              pendingBackendBlocks.clear()
              castInitStarted = false
            }
          }
      } catch (e: Throwable) {
        android.util.Log.w("CastManager", "Cast SDK init failed: $e")
        pendingBackendBlocks.clear()
        castInitStarted = false
      }
    }
  }

  private fun onCastContextReady(castContext: CastContext) {
    val backend = ChromecastBackend(context, castContext)
    wireChromecastCallbacks(backend)
    chromecast = backend
    val blocks = pendingBackendBlocks.toList()
    pendingBackendBlocks.clear()
    blocks.forEach { it(backend) }
  }

  private fun wireChromecastCallbacks(backend: ChromecastBackend) {
    backend.onReceiversChanged = { receivers ->
      chromecastReceivers = receivers
      publishReceivers()
    }
    backend.onSessionConnected = { session, deviceId ->
      // Mint the remote-player session + register with
      // PlaybackStateRouter so the transport-side shim sees the live
      // remote. The app context lets the session build its receiver-only
      // RemoteCastPlayer (it resolves the shared CastContext singleton).
      val remote = RoutedChromecastSession(context, session)
      activeChromecastSession.set(remote)
      PlaybackStateRouter.setActive(remote)

      currentRoute = sessionToRoute(session, deviceId)
      val r = currentRoute
      pendingChromecastConnect.getAndSet(null)?.resolve(Unit)
      mainHandler.post {
        for (l in routeListeners.values) l(r)
      }
    }
    backend.onSessionDisconnected = { reason ->
      // Cast->local always lands paused-at-position, so the end reason
      // doesn't affect the handback play-state; it's only mapped for the
      // JS session-died event below.
      teardownActiveChromecastSession()
      currentRoute = localFallbackRoute()
      val r = currentRoute
      val event = CastSessionDiedEvent(receiverId = "", reason = mapSessionEndReason(reason))
      mainHandler.post {
        for (l in routeListeners.values) l(r)
        for (l in sessionDiedListeners.values) l(event)
      }
    }
    backend.onSessionError = { reason ->
      // Connect/resume failure — tear down + surface the error to JS.
      teardownActiveChromecastSession()
      currentRoute = localFallbackRoute()
      val r = currentRoute
      val event = CastSessionDiedEvent(receiverId = "", reason = mapSessionEndReason(reason))
      pendingChromecastConnect.getAndSet(null)?.reject(
        RuntimeException("Chromecast connect failed: $reason")
      )
      mainHandler.post {
        for (l in routeListeners.values) l(r)
        for (l in sessionDiedListeners.values) l(event)
      }
    }
  }

  /**
   * Close the active Chromecast session (if any) + clear it from the
   * router. Safe to call multiple times; safe when no session is
   * active.
   */
  private fun teardownActiveChromecastSession() {
    val session = activeChromecastSession.getAndSet(null) ?: return
    PlaybackStateRouter.clearActive(session)
    try {
      session.close()
    } catch (t: Throwable) {
      android.util.Log.w("CastManager", "Error closing Chromecast session: $t")
    }
  }

  private fun sessionToRoute(session: GmsCastSession, deviceId: String): CastRoute {
    val device = session.castDevice
    val name = device?.friendlyName ?: deviceId
    return CastRoute(
      castProtocol = CastProtocol.CHROMECAST,
      receiverId = "chromecast:$deviceId",
      name = name,
      iconHint = CastDeviceKind.TV,
      capabilities = CastReceiverCapabilities(
        supportsRemoteVolume = true,
        streamingModel = CastStreamingModel.PULLURL,
        requiresPairing = false,
      ),
      protocolVersion = "",
    )
  }

  private fun airplayReceiverToRoute(receiverId: String): CastRoute {
    val receiver = airplayBackend()?.findReceiver(receiverId)
    if (receiver == null) {
      return CastRoute(
        castProtocol = CastProtocol.AIRPLAY,
        receiverId = receiverId,
        name = receiverId,
        iconHint = CastDeviceKind.UNKNOWN,
        capabilities = CastReceiverCapabilities(
          supportsRemoteVolume = true,
          streamingModel = CastStreamingModel.PUSHPCM,
          requiresPairing = false,
        ),
        protocolVersion = "",
      )
    }
    return CastRoute(
      castProtocol = CastProtocol.AIRPLAY,
      receiverId = receiver.id,
      name = receiver.name,
      iconHint = CastDeviceKind.SPEAKER,
      capabilities = CastReceiverCapabilities(
        supportsRemoteVolume = receiver.capabilities.supportsRemoteVolume,
        streamingModel = CastStreamingModel.PUSHPCM,
        requiresPairing = receiver.capabilities.requiresPairing,
      ),
      protocolVersion = receiver.protocolVersion ?: "",
    )
  }

  private fun mapSessionEndReason(raw: String): CastSessionEndReason {
    if (raw.startsWith("suspended:")) return CastSessionEndReason.NETWORKLOSS
    if (raw.startsWith("ended")) return CastSessionEndReason.ENDED
    return CastSessionEndReason.UNKNOWN
  }

  private fun publishReceivers() {
    val merged = chromecastReceivers + airplayReceivers
    receiversSnapshot = merged.toTypedArray()
    val snap = receiversSnapshot
    mainHandler.post {
      for (l in receiversListeners.values) l(snap)
    }
  }

  private fun localFallbackRoute(): CastRoute = CastRoute(
    castProtocol = CastProtocol.LOCAL,
    receiverId = "",
    name = "Phone speaker",
    iconHint = CastDeviceKind.PHONE,
    capabilities = CastReceiverCapabilities(
      supportsRemoteVolume = false,
      streamingModel = CastStreamingModel.PUSHPCM,
      requiresPairing = false,
    ),
    protocolVersion = "",
  )

  private fun installAirplaySession(session: RoutedCastSession, receiverId: String) {
    closeAirplaySession()
    activeAirplaySession.set(session)
    session.deathListener = { reason ->
      onAirplaySessionDied(receiverId, reason)
    }
    // Wire the session's PCM sink into the ExoPlayer renderer via the
    // process-wide cast sink-router. PcmStream sessions (AirPlay 1/2)
    // are the only subtype that has an audioSink() to feed; RemotePlayer
    // sessions (Chromecast) handle their own transport.
    if (session is RoutedCastSession.PcmStream) {
      mainHandler.post {
        CastSinkRouter.setActiveSession(session)
      }
    }
    currentRoute = airplayReceiverToRoute(receiverId)
    val r = currentRoute
    mainHandler.post {
      for (l in routeListeners.values) l(r)
    }
  }

  private fun closeAirplaySession() {
    val session = activeAirplaySession.getAndSet(null) ?: return
    session.deathListener = null
    // Fire the route change first so TrackPlayer swaps the dedicated
    // AirPlay engine out for the local engine. `session.close()` then
    // releases the AirPlay sink (setting its `released` volatile) before
    // tearing down the native handle, so the renderer thread can't hit a
    // JNI-on-destroyed-handle race.
    if (session is RoutedCastSession.PcmStream) {
      CastSinkRouter.clearActiveSession()
    }
    try {
      session.close()
    } catch (t: Throwable) {
      android.util.Log.w("CastManager", "Error closing AirPlay session: $t")
    }
  }

  private fun onAirplaySessionDied(receiverId: String, reason: String) {
    val session = activeAirplaySession.getAndSet(null)
    // Same as `closeAirplaySession`: fire the route change so the AirPlay
    // engine is swapped out. The session's own close()/death path releases
    // the sink before the native handle disappears.
    if (session is RoutedCastSession.PcmStream) {
      CastSinkRouter.clearActiveSession()
    }
    currentRoute = localFallbackRoute()
    val r = currentRoute
    val event = CastSessionDiedEvent(
      receiverId = receiverId,
      reason = mapAirplaySessionEndReason(reason),
    )
    mainHandler.post {
      for (l in routeListeners.values) l(r)
      for (l in sessionDiedListeners.values) l(event)
    }
  }

  private fun mapAirplaySessionEndReason(raw: String): CastSessionEndReason = when {
    raw.contains("feedbackTimeout", ignoreCase = true) -> CastSessionEndReason.FEEDBACKTIMEOUT
    raw.contains("network", ignoreCase = true) -> CastSessionEndReason.NETWORKLOSS
    else -> CastSessionEndReason.UNKNOWN
  }

  // ----- Nitro spec impl -----------------------------------------------

  override fun getSupportedProtocols(): Array<CastProtocol> {
    val list = mutableListOf<CastProtocol>()
    if (gmsAvailable) list.add(CastProtocol.CHROMECAST)
    if (airplayAvailable()) list.add(CastProtocol.AIRPLAY)
    return list.toTypedArray()
  }

  override fun configure(config: CastConfigureOptions): Promise<Unit> {
    airplayBackend()?.apply {
      setClientName(config.deviceName)
      // Absent flag → hide (the un-connectable Mac receiver is a dead
      // picker entry by default; consumers opt in to showing it).
      setHideComputers(config.hideAirPlayComputers ?: true)
    }
    return Promise.resolved(Unit)
  }

  override fun startDiscovery(): Promise<Unit> {
    discoveryActive = true
    withChromecastBackend { it.startDiscovery() }
    airplayBackend()?.startDiscovery { receivers ->
      airplayReceivers = receivers.map(AirPlayReceiverMapper::map)
      publishReceivers()
    }
    fireDiscoveryState()
    return Promise.resolved(Unit)
  }

  override fun stopDiscovery(): Promise<Unit> {
    discoveryActive = false
    withChromecastBackend { it.stopDiscovery() }
    airplayBackend()?.stopDiscovery()
    fireDiscoveryState()
    return Promise.resolved(Unit)
  }

  override fun isDiscovering(): Boolean = discoveryActive

  override fun getReceivers(): Array<CastReceiver> = receiversSnapshot

  override fun getCurrentRoute(): CastRoute = currentRoute

  override fun connect(receiverId: String, options: CastConnectOptions): Promise<Unit> {
    val promise = Promise<Unit>()
    when {
      receiverId.startsWith("chromecast:") -> {
        // Reject any prior in-flight Chromecast connect — only one
        // attempt may be live at a time.
        pendingChromecastConnect.getAndSet(promise)?.reject(
          RuntimeException("Superseded by a newer Chromecast connect attempt")
        )
        withChromecastBackend { it.connect(receiverId) }
      }
      receiverId.startsWith("airplay:") -> {
        val backend = airplayBackend()
        if (backend == null) {
          promise.reject(IllegalStateException("AirPlay backend not available on this build"))
        } else {
          val airplayOptions: Map<String, Any?> =
            if (options.password.isEmpty()) emptyMap()
            else mapOf("password" to options.password)
          airplayScope.launch {
            try {
              val session = backend.connect(receiverId, airplayOptions)
              installAirplaySession(session, receiverId)
              promise.resolve(Unit)
            } catch (t: Throwable) {
              promise.reject(t)
            }
          }
        }
      }
      else -> promise.reject(IllegalArgumentException("Unsupported receiverId prefix"))
    }
    return promise
  }

  override fun disconnect(): Promise<Unit> {
    closeAirplaySession()
    withChromecastBackend { it.disconnect() }
    if (currentRoute.castProtocol != CastProtocol.LOCAL) {
      currentRoute = localFallbackRoute()
      val r = currentRoute
      mainHandler.post {
        for (l in routeListeners.values) l(r)
      }
    }
    return Promise.resolved(Unit)
  }

  override fun showSystemPicker(): Promise<Unit> {
    // Android has no system Cast picker equivalent to iOS's
    // `AVRoutePickerView`. Consumers render their own picker UI;
    // the demo ships one at `RNQP-Demo/src/components/RoutePicker`.
    return Promise.resolved(Unit)
  }

  /**
   * Last-observed receiver volume (`0.0..1.0`), or `NaN` when no cast session
   * is active or none has been reported yet. For Chromecast this is the device
   * volume (`CastSession.volume`) observed via the session's `Cast.Listener` —
   * the same plane `setRouteVolume` writes. AirPlay receivers don't report
   * current volume on the wire, so they return NaN.
   */
  override fun getRouteVolume(): Double {
    val cc = activeChromecastSession.get()
    if (cc != null) {
      val v = cc.volume.value
      if (!v.isNaN()) return v.toDouble()
    }
    return Double.NaN
  }

  override fun setRouteVolume(value: Double): Promise<Unit> {
    if (!currentRoute.capabilities.supportsRemoteVolume) {
      return Promise.resolved(Unit)
    }
    val clamped = value.coerceIn(0.0, 1.0)
    when (currentRoute.castProtocol) {
      CastProtocol.CHROMECAST -> withChromecastBackend { it.setVolume(clamped) }
      CastProtocol.AIRPLAY -> activeAirplaySession.get()?.setVolume(clamped.toFloat())
      else -> { /* no-op for local */ }
    }
    return Promise.resolved(Unit)
  }

  override fun submitPairingPin(pin: String): Promise<Unit> {
    val promise = Promise<Unit>()
    val backend = airplayBackend()
    if (backend == null) {
      promise.reject(IllegalStateException("AirPlay backend not available on this build"))
      return promise
    }
    airplayScope.launch {
      try {
        val paired = backend.submitPairingPin(pin)
        installAirplaySession(paired.session, paired.receiverId)
        promise.resolve(Unit)
      } catch (t: Throwable) {
        promise.reject(t)
      }
    }
    return promise
  }

  override fun getLocalNetworkPermissionState(): CastLocalNetworkPermission =
    localNetworkPermissionState()

  override fun requestLocalNetworkPermission(): Promise<CastLocalNetworkPermission> =
    // The library holds only the application context, so it cannot raise
    // the system permission dialog (that needs an Activity). Below target
    // SDK 37 the permission is not enforced anyway. A host app targeting
    // SDK 37+ requests the runtime grant from its own Activity — as it
    // already does for POST_NOTIFICATIONS — and this resolves with the
    // current state for parity with iOS's probe-based API.
    Promise.resolved(localNetworkPermissionState())

  /**
   * Honest local-network permission state. Android 16+ Local Network
   * Protections gate LAN access (mDNS Cast / AirPlay discovery) only for
   * apps TARGETING SDK 37+; below that target LAN access is unrestricted,
   * so the state is effectively granted regardless of any formal grant.
   * When enforced, reads the live `ACCESS_LOCAL_NETWORK` grant.
   */
  private fun localNetworkPermissionState(): CastLocalNetworkPermission {
    if (context.applicationInfo.targetSdkVersion < 37) {
      return CastLocalNetworkPermission.GRANTED
    }
    val granted = context.checkSelfPermission(ACCESS_LOCAL_NETWORK_PERMISSION) ==
      PackageManager.PERMISSION_GRANTED
    return if (granted) CastLocalNetworkPermission.GRANTED
    else CastLocalNetworkPermission.DENIED
  }

  override fun onRouteChange(callback: (route: CastRoute) -> Unit): () -> Unit {
    val id = nextListenerId.incrementAndGet()
    routeListeners[id] = callback
    callback(currentRoute)
    return { routeListeners.remove(id) }
  }

  override fun onReceiversChange(callback: (receivers: Array<CastReceiver>) -> Unit): () -> Unit {
    val id = nextListenerId.incrementAndGet()
    receiversListeners[id] = callback
    callback(receiversSnapshot)
    return { receiversListeners.remove(id) }
  }

  override fun onDiscoveryStateChange(callback: (state: CastDiscoveryState) -> Unit): () -> Unit {
    val id = nextListenerId.incrementAndGet()
    discoveryStateListeners[id] = callback
    callback(CastDiscoveryState(isDiscovering = discoveryActive))
    return { discoveryStateListeners.remove(id) }
  }

  override fun onSessionDied(callback: (event: CastSessionDiedEvent) -> Unit): () -> Unit {
    val id = nextListenerId.incrementAndGet()
    sessionDiedListeners[id] = callback
    return { sessionDiedListeners.remove(id) }
  }

  override fun onLocalNetworkPermissionChange(
    callback: (event: CastLocalNetworkPermissionEvent) -> Unit
  ): () -> Unit {
    val id = nextListenerId.incrementAndGet()
    lnaListeners[id] = callback
    callback(CastLocalNetworkPermissionEvent(state = localNetworkPermissionState()))
    return { lnaListeners.remove(id) }
  }

  private fun fireDiscoveryState() {
    val state = CastDiscoveryState(isDiscovering = discoveryActive)
    mainHandler.post {
      for (l in discoveryStateListeners.values) l(state)
    }
  }

  // ----- Audio route -----

  override fun getCurrentAudioRoute(): AudioRoute = audioRouteState.currentRoute()

  override fun onAudioRouteChange(callback: (route: AudioRoute) -> Unit): () -> Unit =
    audioRouteState.addListener(callback)
}
