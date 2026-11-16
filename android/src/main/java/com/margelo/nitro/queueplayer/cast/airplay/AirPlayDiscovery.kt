package com.margelo.nitro.queueplayer.cast.airplay

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.margelo.nitro.queueplayer.cast.Receiver
import com.margelo.nitro.queueplayer.cast.ReceiverCapabilities
import com.margelo.nitro.queueplayer.cast.StreamingModel
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * NSD-based discovery of AirPlay receivers on the LAN. Watches both
 * `_raop._tcp.` (AirPlay 1 / RAOP) and `_airplay._tcp.` (AirPlay 2)
 * service types because most modern receivers advertise on both, and
 * the picker should show them once. Resolved services are deduped by
 * the `deviceid` TXT key (per receiver hardware), and surfaced as
 * [Receiver] instances through the [start] callback.
 *
 * Lifecycle:
 * - Construct once per process (the [AirPlayBackend] holds the only
 *   instance).
 * - [start] is idempotent — calling it twice without an intervening
 *   [stop] is a no-op. The first call grabs a multicast lock, registers
 *   two NSD discovery listeners (one per service type), and begins
 *   feeding the resolve queue.
 * - [stop] tears down both discovery listeners, releases the multicast
 *   lock, and clears the resolved-receiver map. Safe to call when not
 *   running.
 *
 * Resolve strategy:
 * - **API 34+ (UPSIDE_DOWN_CAKE):** Uses `registerServiceInfoCallback`,
 *   which supports concurrent resolves and delivers continuous updates
 *   (IP changes, TXT record updates) until explicitly unregistered.
 * - **Pre-API-34:** Serializes resolves through a queue protected by
 *   [resolveLock] because `NsdManager.resolveService` only allows one
 *   resolve in flight per instance on older APIs.
 *
 * Threading: NsdManager callbacks fire on a binder thread (legacy
 * path) or on the [callbackExecutor] (API 34+ path). We do minimal
 * work on those threads and surface receiver updates to the caller
 * via [start]'s `onUpdate` lambda — callers should marshal to their
 * preferred dispatcher before touching UI state.
 *
 * @property context Application context. Used to grab `NsdManager`
 *   and to construct the [MulticastLockHolder].
 */
internal class AirPlayDiscovery(private val context: Context) {

    private val nsdManager: NsdManager =
        context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val lockHolder = MulticastLockHolder(context)

    private val running = AtomicBoolean(false)

    /**
     * Discovery listeners currently registered with [nsdManager]. One
     * per service type. Cleared by [stop].
     */
    private val activeListeners = mutableListOf<NsdManager.DiscoveryListener>()

    /**
     * Map of `deviceid` (or fallback `serviceName:port` key) → resolved
     * [Receiver]. Used to dedupe across `_raop._tcp.` and
     * `_airplay._tcp.` advertisements of the same physical device.
     */
    private val resolvedByKey: MutableMap<String, Receiver> =
        Collections.synchronizedMap(LinkedHashMap())

    /**
     * Service-name → resolve-key map so [stop] / "service lost"
     * callbacks can remove the right [resolvedByKey] entry without
     * needing the original `NsdServiceInfo`.
     */
    private val keyByServiceName: MutableMap<String, String> = ConcurrentHashMap()

    // --- Resolve infrastructure ---
    // Pre-API-34: serialized queue (NsdManager only allows one resolve
    // in flight per instance on older APIs).
    // API 34+: registerServiceInfoCallback — inherently parallel,
    // continuous updates, must be explicitly unregistered.
    private val useCallbackApi = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    /** Executor for API 34+ ServiceInfoCallback dispatches. */
    private val callbackExecutor: Executor = Executors.newSingleThreadExecutor()

    /**
     * Active ServiceInfoCallbacks registered via the API 34+ path.
     * Tracked so [stop] can unregister them all.
     */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private val activeCallbacks: MutableSet<NsdManager.ServiceInfoCallback> =
        Collections.synchronizedSet(mutableSetOf())

    /** Legacy resolve queue lock — only used on pre-API-34. */
    private val resolveLock = Any()
    private var resolveInFlight = false
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()

    private var onUpdateCallback: ((List<Receiver>) -> Unit)? = null

    /**
     * When `true`, Mac-computer AirPlay receivers are filtered out of the
     * emitted snapshot. Defaults to hidden (see [setHideComputers]); holds
     * even before the first `Cast.configure` call.
     */
    @Volatile
    private var hideComputers = true

    /**
     * Toggle whether Mac-computer AirPlay receivers appear in the device
     * list. Re-emits the current snapshot on change so the picker updates
     * live. Does not affect [findReceiver] — a connect lookup by id still
     * resolves a hidden receiver.
     */
    fun setHideComputers(hide: Boolean) {
        if (hideComputers == hide) return
        hideComputers = hide
        emit()
    }

    /**
     * Begin discovery. Idempotent: a second call without an intervening
     * [stop] returns immediately. The [onUpdate] callback fires on
     * every dedupe-merged change to the resolved set, including the
     * empty set on first start (so subscribers can clear stale state).
     */
    fun start(onUpdate: (List<Receiver>) -> Unit) {
        if (!running.compareAndSet(false, true)) return
        onUpdateCallback = onUpdate
        lockHolder.acquire()
        SERVICE_TYPES.forEach { svc -> startOne(svc) }
        // Fire an empty initial snapshot so subscribers see a clean
        // starting state. Resolved devices will follow as NsdManager
        // calls back.
        emit()
    }

    /**
     * Stop discovery. Tears down both NSD listeners, releases the
     * multicast lock, and clears the resolved-receiver map. Safe to
     * call when not running.
     */
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        synchronized(activeListeners) {
            activeListeners.forEach { listener ->
                runCatching { nsdManager.stopServiceDiscovery(listener) }
                    .onFailure { Log.w(TAG, "stopServiceDiscovery threw", it) }
            }
            activeListeners.clear()
        }
        if (useCallbackApi) {
            tearDownCallbacks()
        } else {
            synchronized(resolveLock) {
                resolveQueue.clear()
                resolveInFlight = false
            }
        }
        resolvedByKey.clear()
        keyByServiceName.clear()
        lockHolder.release()
        // Tell subscribers we are now empty so picker UI can clear, then
        // drop the callback. Clearing the callback first would suppress
        // the empty-snapshot delivery; ordering matters here.
        emit()
        onUpdateCallback = null
    }

    private fun startOne(serviceType: String) {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(svc: String, errorCode: Int) {
                Log.w(TAG, "onStartDiscoveryFailed svc=$svc code=$errorCode")
            }

            override fun onStopDiscoveryFailed(svc: String, errorCode: Int) {
                Log.w(TAG, "onStopDiscoveryFailed svc=$svc code=$errorCode")
            }

            override fun onDiscoveryStarted(svc: String) {
                Log.d(TAG, "onDiscoveryStarted svc=$svc")
            }

            override fun onDiscoveryStopped(svc: String) {
                Log.d(TAG, "onDiscoveryStopped svc=$svc")
            }

            override fun onServiceFound(svcInfo: NsdServiceInfo) {
                Log.d(TAG, "onServiceFound name=${svcInfo.serviceName} type=${svcInfo.serviceType}")
                resolveDiscoveredService(svcInfo)
            }

            override fun onServiceLost(svcInfo: NsdServiceInfo) {
                Log.d(TAG, "onServiceLost name=${svcInfo.serviceName}")
                handleServiceLost(svcInfo)
            }
        }

        synchronized(activeListeners) { activeListeners.add(listener) }
        runCatching {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure {
            Log.w(TAG, "discoverServices($serviceType) threw", it)
            synchronized(activeListeners) { activeListeners.remove(listener) }
        }
    }

    /**
     * Resolve a discovered service. Dispatches to the API 34+
     * [registerServiceInfoCallback] path or the legacy serialized
     * queue, depending on the device's API level.
     */
    private fun resolveDiscoveredService(svcInfo: NsdServiceInfo) {
        if (useCallbackApi) {
            resolveViaCallback(svcInfo)
        } else {
            enqueueResolveLegacy(svcInfo)
        }
    }

    // ---- API 34+ path: registerServiceInfoCallback ----

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun resolveViaCallback(svcInfo: NsdServiceInfo) {
        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                Log.w(TAG, "onServiceInfoCallbackRegistrationFailed name=${svcInfo.serviceName} code=$errorCode")
                activeCallbacks.remove(this)
            }

            override fun onServiceUpdated(resolvedInfo: NsdServiceInfo) {
                Log.d(TAG, "onServiceUpdated name=${resolvedInfo.serviceName} port=${resolvedInfo.port}")
                handleResolved(resolvedInfo)
            }

            override fun onServiceLost() {
                Log.d(TAG, "onServiceLost (callback) name=${svcInfo.serviceName}")
                // The discovery-level onServiceLost already handles
                // removal from resolvedByKey; the callback-level lost
                // just means "info is momentarily stale". We unregister
                // the callback since we'll get a fresh one on re-discovery.
                runCatching { nsdManager.unregisterServiceInfoCallback(this) }
                activeCallbacks.remove(this)
            }

            override fun onServiceInfoCallbackUnregistered() {
                activeCallbacks.remove(this)
            }
        }
        activeCallbacks.add(callback)
        runCatching {
            nsdManager.registerServiceInfoCallback(svcInfo, callbackExecutor, callback)
        }.onFailure {
            Log.w(TAG, "registerServiceInfoCallback threw", it)
            activeCallbacks.remove(callback)
        }
    }

    /** Unregister all active API 34+ ServiceInfoCallbacks. */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun tearDownCallbacks() {
        synchronized(activeCallbacks) {
            activeCallbacks.toList().forEach { cb ->
                runCatching { nsdManager.unregisterServiceInfoCallback(cb) }
                    .onFailure { Log.w(TAG, "unregisterServiceInfoCallback threw", it) }
            }
            activeCallbacks.clear()
        }
    }

    // ---- Pre-API-34 path: serialized resolve queue ----

    private fun enqueueResolveLegacy(svcInfo: NsdServiceInfo) {
        synchronized(resolveLock) {
            resolveQueue.addLast(svcInfo)
            pumpResolveLocked()
        }
    }

    /** Must be called while holding [resolveLock]. */
    private fun pumpResolveLocked() {
        if (resolveInFlight) return
        val next = resolveQueue.removeFirstOrNull() ?: return
        resolveInFlight = true
        @Suppress("DEPRECATION")
        nsdManager.resolveService(next, makeResolveListener())
    }

    @Suppress("DEPRECATION")
    private fun makeResolveListener(): NsdManager.ResolveListener =
        object : NsdManager.ResolveListener {
            override fun onResolveFailed(svcInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "onResolveFailed name=${svcInfo.serviceName} code=$errorCode")
                synchronized(resolveLock) {
                    resolveInFlight = false
                    pumpResolveLocked()
                }
            }

            override fun onServiceResolved(svcInfo: NsdServiceInfo) {
                Log.d(TAG, "onServiceResolved name=${svcInfo.serviceName} host=${svcInfo.host} port=${svcInfo.port}")
                handleResolved(svcInfo)
                synchronized(resolveLock) {
                    resolveInFlight = false
                    pumpResolveLocked()
                }
            }
        }

    private fun handleResolved(svcInfo: NsdServiceInfo) {
        val attrs = decodeAttributes(svcInfo)
        val parsed = AirPlayTxtParser.parse(attrs)
        val key = parsed.deviceId
            ?: extractMacFromRaopName(svcInfo.serviceName)
            ?: "${svcInfo.serviceName}:${svcInfo.port}"
        val displayName = friendlyName(svcInfo, parsed)
        // RAOP is audio-only by design — every _raop._tcp. device is an
        // audio receiver. Default to "speaker" when the model string
        // didn't match any known token.
        val deviceClass = if (parsed.deviceClass == "unknown" &&
            svcInfo.serviceType.contains("raop", ignoreCase = true)) {
            "speaker"
        } else {
            parsed.deviceClass
        }
        val receiver = Receiver(
            id = "airplay:$key",
            protocol = "airplay",
            name = displayName,
            iconHint = deviceClass,
            capabilities = ReceiverCapabilities(
                supportsRemoteVolume = true, // libraop / cliap2 both support volume
                streamingModel = StreamingModel.PUSH_PCM,
                requiresPairing = parsed.requiresPairing,
            ),
            protocolVersion = if (parsed.supportsAirPlay2 ||
                svcInfo.serviceType.contains("airplay", ignoreCase = true)) "2" else "1",
            extras = buildExtras(svcInfo, parsed),
        )

        keyByServiceName[svcInfo.serviceName] = key
        val existing = resolvedByKey[key]
        resolvedByKey[key] = if (existing != null) mergeReceivers(existing, receiver) else receiver
        emit()
    }

    private fun handleServiceLost(svcInfo: NsdServiceInfo) {
        val key = keyByServiceName.remove(svcInfo.serviceName) ?: return
        // Only drop the entry if no other service-name still maps to
        // this key (e.g. the same device's `_airplay._tcp.` is still
        // around even though `_raop._tcp.` went away).
        val stillReferenced = keyByServiceName.values.any { it == key }
        if (!stillReferenced) {
            resolvedByKey.remove(key)
            emit()
        }
    }

    private fun decodeAttributes(svcInfo: NsdServiceInfo): Map<String, String?> {
        // NsdServiceInfo.attributes is API 21+ but the values are
        // ByteArray? — decode them as UTF-8 strings (the format used by
        // every AirPlay implementation in the wild).
        val out = LinkedHashMap<String, String?>()
        for ((k, v) in svcInfo.attributes) {
            out[k] = v?.toString(Charsets.UTF_8)
        }
        return out
    }

    private fun friendlyName(svcInfo: NsdServiceInfo, parsed: AirPlayTxtParser.Parsed): String {
        val raw = svcInfo.serviceName
        // RAOP service names are "<MAC>@<FriendlyName>" — the friendly
        // part is AFTER the @. shairport-sync uses "@<hex><name>" where
        // the friendly part is BEFORE the @. Detect the RAOP format by
        // checking if the part before @ is a 12-char hex string.
        val atIdx = raw.indexOf('@')
        if (atIdx > 0) {
            val before = raw.substring(0, atIdx)
            if (RAOP_MAC_REGEX.matches(before)) {
                return raw.substring(atIdx + 1).trim().ifBlank { parsed.modelString ?: "AirPlay receiver" }
            }
            return before.trim().ifBlank { parsed.modelString ?: "AirPlay receiver" }
        }
        return raw.trim().ifBlank { parsed.modelString ?: "AirPlay receiver" }
    }

    /**
     * Extract a normalized MAC from a RAOP service name of the form
     * `<12-hex-chars>@<FriendlyName>`. Returns the MAC in colon-separated
     * uppercase format (e.g. `"CE:71:CA:37:C8:FF"`) to match the
     * `deviceid` TXT field format used by `_airplay._tcp.`, enabling
     * cross-service dedup. Returns null if the name doesn't match.
     */
    private fun extractMacFromRaopName(serviceName: String): String? {
        val atIdx = serviceName.indexOf('@')
        if (atIdx <= 0) return null
        val before = serviceName.substring(0, atIdx)
        if (!RAOP_MAC_REGEX.matches(before)) return null
        val upper = before.uppercase()
        return upper.chunked(2).joinToString(":")
    }

    /**
     * Merge two Receiver entries for the same device discovered via
     * different service types. Prefers non-default values: a real
     * friendly name over a MAC, a specific deviceClass over "unknown",
     * and unions the extras maps.
     */
    private fun mergeReceivers(existing: Receiver, incoming: Receiver): Receiver {
        val name = if (looksLikeMac(existing.name)) incoming.name else existing.name
        val iconHint = if (existing.iconHint == "unknown" && incoming.iconHint != "unknown") {
            incoming.iconHint
        } else {
            existing.iconHint
        }
        val mergedExtras = LinkedHashMap(existing.extras)
        for ((k, v) in incoming.extras) {
            if (mergedExtras[k] == null || mergedExtras[k] == "unknown") {
                mergedExtras[k] = v
            } else if (k == "raopServiceSeen" || k == "airplayServiceSeen") {
                mergedExtras[k] = v
            }
        }
        // Prefer "2" over "1" — the _airplay._tcp service carries the
        // authoritative features bits; the _raop._tcp service may not.
        val version = if (incoming.protocolVersion == "2") "2"
            else existing.protocolVersion ?: incoming.protocolVersion
        return existing.copy(
            name = name,
            iconHint = iconHint,
            protocolVersion = version,
            extras = mergedExtras,
        )
    }

    private fun looksLikeMac(name: String): Boolean =
        RAOP_MAC_REGEX.matches(name) || MAC_WITH_COLONS.matches(name)

    private fun buildExtras(
        svcInfo: NsdServiceInfo,
        parsed: AirPlayTxtParser.Parsed,
    ): Map<String, String> {
        val extras = LinkedHashMap<String, String>()
        extras["deviceClass"] = parsed.deviceClass
        extras["supportsAirPlay2"] = parsed.supportsAirPlay2.toString()
        if (svcInfo.serviceType.contains("airplay", ignoreCase = true)) {
            extras["airplayServiceSeen"] = "true"
        }
        if (svcInfo.serviceType.contains("raop", ignoreCase = true)) {
            extras["raopServiceSeen"] = "true"
        }
        parsed.deviceId?.let { extras["deviceid"] = it }
        parsed.serverVersion?.let { extras["serverVersion"] = it }
        parsed.modelString?.let { extras["model"] = it }
        // host can be null on some Android versions if the service was
        // resolved over IPv4 only — guard before reading. The
        // `NsdServiceInfo.host` getter is deprecated on API 34+ in
        // favour of `getHostAddresses()`, but the deprecated path is
        // the only one that exists below 34, so we use it
        // unconditionally and silence the warning.
        @Suppress("DEPRECATION")
        try {
            svcInfo.host?.hostAddress?.let { extras["host"] = it }
        } catch (_: Throwable) {
            // ignore — diagnostic only
        }
        extras["port"] = svcInfo.port.toString()
        return extras
    }

    /**
     * Look up a previously-discovered receiver by its [Receiver.id].
     * Returns null if the receiver is no longer in the resolved set
     * (e.g. it went offline since the last scan).
     */
    fun findReceiver(receiverId: String): Receiver? =
        synchronized(resolvedByKey) { resolvedByKey.values.firstOrNull { it.id == receiverId } }

    private fun emit() {
        val snapshot = synchronized(resolvedByKey) { resolvedByKey.values.toList() }
        onUpdateCallback?.invoke(visibleReceivers(snapshot, hideComputers))
    }

    private companion object {
        const val TAG = "AirPlayDiscovery"
        val SERVICE_TYPES = listOf("_raop._tcp.", "_airplay._tcp.")
        val RAOP_MAC_REGEX = Regex("^[0-9A-Fa-f]{12}$")
        val MAC_WITH_COLONS = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")
    }
}

/**
 * The receivers a picker should show given whether Mac computers are
 * hidden. `AirPlayTxtParser` classifies every Mac model (`Mac17,4`,
 * `MacBookPro18,3`, `iMac21,1`, …) as `deviceClass == "computer"`; macOS
 * AirPlay Receiver is un-connectable from a non-Apple sender, so those
 * entries are dropped when [hideComputers]. Keying on the classified
 * `deviceClass` (not the raw model string) is robust to the `_raop._tcp.`
 * vs `_airplay._tcp.` merge, which can leave the merged `iconHint` as
 * "speaker" while the extras still carry the correct class.
 */
internal fun visibleReceivers(all: List<Receiver>, hideComputers: Boolean): List<Receiver> =
    if (hideComputers) all.filterNot { it.extras["deviceClass"] == "computer" } else all
