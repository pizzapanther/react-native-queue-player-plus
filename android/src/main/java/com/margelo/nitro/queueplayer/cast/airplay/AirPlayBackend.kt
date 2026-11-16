package com.margelo.nitro.queueplayer.cast.airplay

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.margelo.nitro.queueplayer.cast.AuthRequiredException
import com.margelo.nitro.queueplayer.cast.CastBackend
import com.margelo.nitro.queueplayer.cast.CastSession
import com.margelo.nitro.queueplayer.cast.DiscoveryState
import com.margelo.nitro.queueplayer.cast.Receiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AirPlay implementation of [CastBackend]. Owns a single
 * [AirPlayDiscovery] instance and gates its actual scanning on the
 * shared [DiscoveryState.effective] flag, so that the lifecycle observer
 * can pause discovery when the app is backgrounded without the JS layer
 * having to issue an explicit `stopDiscovery`.
 *
 * [connect] routes to libraop for AirPlay 1 receivers and libairplay2
 * for AirPlay 2 receivers; the receiver's `protocolVersion` selects
 * the path.
 *
 * Lifecycle:
 * - Constructed once per process by [AirPlayBackendInitializer] from
 *   `androidx.startup`. Registers itself with `CastBackends.register`.
 * - [startDiscovery] stores the caller's `onUpdate` callback, sets
 *   `DiscoveryState.intent = true`, and launches a coroutine that
 *   observes `DiscoveryState.effective`. When effective is `true`, the
 *   underlying [AirPlayDiscovery] is started; when `false`, it is
 *   stopped. This is what gives the lifecycle observer a path to pause
 *   the scan without going through the registry.
 * - [stopDiscovery] sets `DiscoveryState.intent = false` and cancels
 *   the observation job. The discovery instance is stopped explicitly
 *   too in case the effective flow had not yet caught up.
 *
 * @property context Application context. Used by [AirPlayDiscovery] to
 *   grab `NsdManager` and the multicast lock.
 */
/**
 * Tracks a native AP2 session handle that is waiting for user credential
 * input (PIN or password). The TCP socket stays alive so the pairing
 * sequence can continue on the same connection.
 */
private data class PendingPair(
    val handle: Long,
    val receiverId: String,
    val host: String,
    val port: Int,
    val authMode: Int,
    val deviceId: String?,
)

internal class AirPlayBackend(private val context: Context) : CastBackend {

    override val protocolId: String = "airplay"

    private val discovery = AirPlayDiscovery(context)

    /** Device name sent to receivers as X-Apple-Client-Name and in SETUP plist.
     *  Set via Cast.configure({ deviceName }), falls back to Android model name. */
    @Volatile
    private var clientName: String = android.os.Build.MODEL

    /**
     * Set the client device name reported to AirPlay receivers in the
     * RTSP `X-Apple-Client-Name` header + AP2 SETUP plist. Idempotent.
     */
    fun setClientName(name: String) {
        if (name.isNotEmpty()) clientName = name
    }

    /**
     * Whether AirPlay receivers running on Mac computers are hidden from
     * discovery. macOS AirPlay Receiver is un-connectable from a non-Apple
     * sender, so it defaults to hidden; a consumer can surface it via
     * `Cast.configure({ hideAirPlayComputers: false })`.
     */
    fun setHideComputers(hide: Boolean) {
        discovery.setHideComputers(hide)
    }

    /**
     * Look up a discovered AirPlay [Receiver] by id. Returns `null` if the
     * receiver is no longer visible. Used by `CastManager` to build a
     * `CastRoute` for the active connection without exposing the internal
     * domain types beyond the cast package.
     */
    fun findReceiver(receiverId: String): Receiver? = discovery.findReceiver(receiverId)

    /**
     * Complete a deferred AP2 pairing by supplying the PIN / password the
     * receiver demanded. Returns the session and the receiver it was
     * paired with; throws on pairing failure or when no pending pair
     * exists.
     *
     * Callers use this when `CastManager.submitPairingPin(pin)` is invoked
     * out-of-band of a follow-up `connect(receiverId, {password})` call.
     */
    suspend fun submitPairingPin(pin: String): PairedSession = withContext(Dispatchers.IO) {
        val pending = synchronized(this@AirPlayBackend) { pendingPairState }
            ?: throw IllegalStateException("No pending AirPlay 2 pairing")
        val receiver = discovery.findReceiver(pending.receiverId)
            ?: throw IllegalStateException(
                "Receiver ${pending.receiverId} no longer visible — restart the connect flow"
            )
        // resumeAp2Pairing reports the id of the pair it consumed and
        // validated against `receiver`; the read above only locates it.
        resumeAp2Pairing(receiver, pin)
    }

    /** A session established by [submitPairingPin] and the receiver it was paired with. */
    data class PairedSession(val session: CastSession, val receiverId: String)

    /**
     * Persistent store for AirPlay 2 pair-setup auth keys + passwords,
     * keyed by the receiver's mDNS `deviceid` (MAC address).
     *
     * `MODE_PRIVATE` SharedPreferences are app-sandboxed (other apps on
     * a non-rooted device cannot read them) but are stored in
     * cleartext. Consumer apps with elevated security requirements
     * should swap this for `EncryptedSharedPreferences` from
     * `androidx.security:security-crypto`; the persistence surface is
     * intentionally narrow (one SharedPreferences instance) so an
     * encrypted-store substitution stays local.
     */
    private val authPrefs: SharedPreferences by lazy {
        context.getSharedPreferences("airplay2_auth_keys", Context.MODE_PRIVATE)
    }

    init {
        if (AirPlay2JNI.isAvailable) {
            // Persistent file log — survives USB disconnection.
            val logDir = context.filesDir
            val logPath = java.io.File(logDir, "airplay2.log").absolutePath
            AirPlay2JNI.nativeAp2InitFileLog(logPath)
            Log.i(TAG, "File logging initialized: $logPath")
        } else {
            Log.w(TAG, "AirPlay 2 native lib unavailable; backend will reject connect() for AP2 receivers")
        }
    }

    /**
     * A native AP2 session that returned STATE_PAIR and is waiting for
     * user-provided credential. Guarded by [synchronized] on `this`.
     */
    @Volatile
    private var pendingPairState: PendingPair? = null

    /**
     * Scope used to observe [DiscoveryState.effective]. Tied to a
     * [SupervisorJob] so a transient collector failure does not tear
     * down the backend. Lives for the lifetime of the process —
     * [stopDiscovery] cancels the active job but the scope itself
     * stays alive so the next [startDiscovery] can re-launch.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Active observation job, or `null` while discovery is stopped. */
    private var effectiveJob: Job? = null

    /**
     * The most recent `onUpdate` callback handed in via
     * [startDiscovery]. Stored so the effective-state observer can
     * forward [AirPlayDiscovery] callbacks to it without rewiring on
     * every effective flip.
     */
    private var currentOnUpdate: ((List<Receiver>) -> Unit)? = null

    /**
     * Track whether the underlying discovery is currently scanning so
     * we can avoid double-start / double-stop calls when the effective
     * flow re-emits an unchanged value (StateFlow conflates equal
     * values, so this is mostly defensive).
     */
    private var scanning: Boolean = false

    override fun startDiscovery(onUpdate: (List<Receiver>) -> Unit) {
        currentOnUpdate = onUpdate
        DiscoveryState.setIntent(true)

        // Cancel any prior observer before starting a new one so the
        // contract from CastBackend ("idempotent — calling startDiscovery
        // when already running should not duplicate listeners") holds.
        effectiveJob?.cancel()
        effectiveJob = scope.launch {
            DiscoveryState.effective.collectLatest { effective ->
                applyEffective(effective)
            }
        }
    }

    override fun stopDiscovery() {
        DiscoveryState.setIntent(false)
        effectiveJob?.cancel()
        effectiveJob = null
        if (scanning) {
            discovery.stop()
            scanning = false
        }
        currentOnUpdate = null
    }

    /**
     * Connect to a previously-discovered AirPlay receiver.
     *
     * Routes to [connectAp1] (libraop) or [connectAp2] (libairplay2) based
     * on the receiver's [Receiver.protocolVersion]. The two paths share
     * lookup/validation but diverge at the native layer.
     */
    override suspend fun connect(
        receiverId: String,
        options: Map<String, Any?>,
    ): CastSession = withContext(Dispatchers.IO) {
        val receiver = discovery.findReceiver(receiverId)
            ?: throw IllegalStateException(
                "Receiver $receiverId is no longer visible on the network"
            )

        val host = receiver.extras["host"]
            ?: throw IllegalStateException(
                "Receiver $receiverId has no host address in extras"
            )
        val port = receiver.extras["port"]?.toIntOrNull() ?: DEFAULT_RTSP_PORT

        Log.i(TAG, "Routing ${receiver.name}: protocolVersion=${receiver.protocolVersion}")
        if (receiver.protocolVersion == "2") {
            connectAp2(receiver, host, port, options)
        } else {
            connectAp1(receiver, host, port, options)
        }
    }

    /** AirPlay 1 connection via libraop. */
    private fun connectAp1(
        receiver: Receiver, host: String, port: Int,
        options: Map<String, Any?>,
    ): CastSession {
        val password = options["password"] as? String

        Log.i(TAG, "Connecting AP1 to ${receiver.name} ($host:$port)" +
            if (password != null) " [with password]" else "")

        val handle = AirplayJNI.nativeOpen(
            host, port,
            AirplayJNI.CODEC_PCM,
            FRAME_LENGTH,
            LATENCY_FRAMES,
            AirplayJNI.CRYPTO_RSA, false,
            SAMPLE_RATE, SAMPLE_SIZE, CHANNELS,
            DEFAULT_VOLUME_DB,
        )

        check(handle != 0L) {
            "libraop nativeOpen failed for $host:$port"
        }

        AirplayJNI.nativeSetDeviceName(handle, clientName)

        if (password != null) {
            AirplayJNI.nativeSetPassword(handle, password)
        }

        val connected = AirplayJNI.nativeConnect(handle, host, port)
        if (!connected) {
            val rtspStatus = AirplayJNI.nativeGetLastRtspStatus(handle)
            AirplayJNI.nativeDestroy(handle)
            if (rtspStatus == 401 || rtspStatus == 403) {
                // AP1 only supports password-based RTSP auth; PIN pairing
                // is an AP2 concept.
                throw AuthRequiredException(
                    message = "Receiver ${receiver.name} requires authentication (RTSP $rtspStatus)",
                    authMode = "password",
                )
            }
            throw RuntimeException(
                "RTSP handshake failed for AP1 ${receiver.name} ($host:$port, RTSP $rtspStatus)"
            )
        }

        Log.i(TAG, "Connected AP1 to ${receiver.name} ($host:$port)")
        return AirPlaySession(context, handle, host, port)
    }

    /**
     * AirPlay 2 connection via libairplay2.
     *
     * Two-phase flow:
     * 1. First call (no credential): probe + pair attempt. If transient
     *    pairing succeeds → streaming. If pairing required → stash the
     *    live handle in [pendingPairState], throw [AuthRequiredException]
     *    with authMode so the UI can prompt for PIN or password.
     * 2. Second call (with credential): retrieve pending handle, run
     *    pair-pin-start (PIN mode only) + pair-setup → streaming.
     *
     * Auth key persistence: successful pair-setup stores the auth key in
     * SharedPreferences keyed by device_id. Subsequent connects inject the
     * stored key for pair-verify (no user prompt needed).
     */
    private fun connectAp2(
        receiver: Receiver, host: String, port: Int,
        options: Map<String, Any?>,
    ): CastSession {
        val credential = options["password"] as? String

        // --- Phase 2: credential supplied → resume pending pairing ---
        if (credential != null) {
            return resumeAp2Pairing(receiver, credential).session
        }

        // --- Phase 1: initial connection attempt ---
        Log.i(TAG, "Connecting AP2 to ${receiver.name} ($host:$port)")

        // Destroy any stale pending handle from a prior failed attempt
        destroyPendingPair()

        val handle = AirPlay2JNI.nativeAp2Open(
            host, port,
            SAMPLE_RATE, SAMPLE_SIZE, CHANNELS,
            AP2_CONNECT_VOLUME,
        )

        check(handle != 0L) {
            "libairplay2 nativeAp2Open failed for $host:$port"
        }

        // Receiver-displayed device name; uses the configured client
        // name (set via `CastManager.configure`, defaults to Build.MODEL).
        AirPlay2JNI.nativeAp2SetDeviceName(handle, clientName)

        // Look up mDNS device ID for auth key / password persistence
        val mDnsDeviceId = receiver.extras["deviceid"]

        // Inject persisted password for PASSWORD_REQUIRED receivers
        // (stored from a prior successful pair-setup). PIN-mode receivers
        // pair afresh on every reconnect and won't hit this path.
        if (mDnsDeviceId != null) {
            authPrefs.getString("pw_$mDnsDeviceId", null)?.takeIf { it.isNotEmpty() }
                ?.let { AirPlay2JNI.nativeAp2SetPassword(handle, it) }
        }

        // Inject stored auth key for pair-verify reconnection
        if (mDnsDeviceId != null) {
            val storedKey = authPrefs.getString("key_$mDnsDeviceId", null)
            if (storedKey != null) {
                Log.i(TAG, "Injecting stored auth key for $mDnsDeviceId")
                AirPlay2JNI.nativeAp2SetAuthKey(handle, storedKey)
            }
        }

        val connected = AirPlay2JNI.nativeAp2Connect(handle)
        if (connected) {
            Log.i(TAG, "Connected AP2 to ${receiver.name} ($host:$port)")
            return AirPlay2Session(context, handle, host, port)
        }

        // Connection failed — check if pairing is required
        val state = AirPlay2JNI.nativeAp2GetState(handle)
        if (state == AirPlay2JNI.STATE_PAIR) {
            val authMode = AirPlay2JNI.nativeAp2GetAuthMode(handle)
            val deviceId = AirPlay2JNI.nativeAp2GetDeviceId(handle)

            Log.i(TAG, "AP2 pairing required: authMode=$authMode deviceId=$deviceId")

            // Clear stale auth key — if pair-verify failed, the stored
            // key is no longer valid and the receiver wants a new
            // pair-setup round.
            if (deviceId != null) {
                val hadKey = authPrefs.getString("key_$deviceId", null) != null
                if (hadKey) {
                    Log.i(TAG, "Clearing stale auth key for $deviceId (pair-verify failed)")
                    authPrefs.edit().remove("key_$deviceId").apply()
                }
            }

            // Trigger pair-pin-start for PIN mode only.
            // PIN mode: receiver displays a PIN the user must enter.
            // PASSWORD mode: user already knows the password — go
            // straight to pair-setup, no pair-pin-start needed.
            if (authMode == AirPlay2JNI.AUTH_PIN) {
                Log.i(TAG, "AP2 pair-pin-start for ${receiver.name} (authMode=$authMode)")
                if (!AirPlay2JNI.nativeAp2PairPinStart(handle)) {
                    AirPlay2JNI.nativeAp2Destroy(handle)
                    throw RuntimeException(
                        "AP2 pair-pin-start failed for ${receiver.name}"
                    )
                }
            }

            // Stash the live handle — TCP stays open for pair-setup
            synchronized(this) {
                pendingPairState = PendingPair(
                    handle = handle,
                    receiverId = receiver.id,
                    host = host,
                    port = port,
                    authMode = authMode,
                    deviceId = deviceId,
                )
            }

            // authMode is surfaced via AuthRequiredException.authMode and
            // lands on the JS error object's `authMode` field through the
            // promise-rejection userInfo — consumers no longer parse the
            // message string. Message stays human-readable for logs.
            val modeLabel = when (authMode) {
                AirPlay2JNI.AUTH_PIN -> "pin"
                AirPlay2JNI.AUTH_PASSWORD -> "password"
                else -> "unknown"
            }
            throw AuthRequiredException(
                message = "Receiver ${receiver.name} requires $modeLabel authentication",
                authMode = modeLabel,
            )
        }

        // Non-pairing failure — destroy and throw
        AirPlay2JNI.nativeAp2Destroy(handle)
        throw RuntimeException(
            "AP2 connection failed for ${receiver.name} ($host:$port, state=$state)"
        )
    }

    /**
     * Resume a pending AP2 pairing with user-provided credential, from
     * either `connect()` re-invoked with a password or [submitPairingPin].
     * Consumes the pending pair, validates it is for [receiver], and
     * returns the session together with that receiver's id.
     */
    private fun resumeAp2Pairing(receiver: Receiver, credential: String): PairedSession {
        val pending = synchronized(this) {
            val p = pendingPairState
            pendingPairState = null
            p
        } ?: throw RuntimeException(
            "No pending AP2 pairing session for ${receiver.name}"
        )

        // Validate it's the same receiver
        if (pending.receiverId != receiver.id) {
            AirPlay2JNI.nativeAp2Destroy(pending.handle)
            throw RuntimeException(
                "Pending pairing is for ${pending.receiverId}, not ${receiver.id}"
            )
        }

        val handle = pending.handle

        // pair-pin-start already sent in Phase 1 (before user prompt)

        Log.i(TAG, "AP2 pair-setup for ${receiver.name} (authMode=${pending.authMode})")
        val authKey = AirPlay2JNI.nativeAp2PairSetup(handle, credential)
        if (authKey == null) {
            AirPlay2JNI.nativeAp2Destroy(handle)
            throw RuntimeException(
                "AP2 pair-setup failed for ${receiver.name}"
            )
        }

        // Persist auth key + password for future pair-verify reconnection
        val deviceId = pending.deviceId
        if (deviceId != null && authKey.isNotEmpty()) {
            Log.i(TAG, "Storing auth key for deviceId=$deviceId (192 hex)")
            authPrefs.edit()
                .putString("key_$deviceId", authKey)
                .putString("pw_$deviceId", credential)
                .apply()
        }

        // Destroy the pairing handle — connection is closed after pair-setup.
        // Per OwnTone: pairing and streaming are separate operations.
        // The Mac needs time to commit the pairing before pair-verify works.
        AirPlay2JNI.nativeAp2Destroy(handle)
        Log.i(TAG, "Pair-setup complete, destroyed pairing handle")

        // Now do a fresh connect with the stored auth key — this triggers
        // pair-verify on a clean connection.
        val host = pending.host
        val port = pending.port
        val newHandle = AirPlay2JNI.nativeAp2Open(
            host, port,
            SAMPLE_RATE, SAMPLE_SIZE, CHANNELS,
            AP2_CONNECT_VOLUME,
        )
        if (newHandle == 0L) {
            throw RuntimeException("AP2 session create failed for reconnect")
        }

        // Inject stored auth key + password for Digest auth
        AirPlay2JNI.nativeAp2SetAuthKey(newHandle, authKey)
        AirPlay2JNI.nativeAp2SetDeviceName(newHandle, clientName)

        // Set password for HTTP Digest auth if receiver requires it
        val storedPassword = authPrefs.getString("pw_${deviceId}", null)
        if (!storedPassword.isNullOrEmpty()) {
            AirPlay2JNI.nativeAp2SetPassword(newHandle, storedPassword)
        }

        Log.i(TAG, "Reconnecting AP2 to ${receiver.name} with stored auth key")
        val connected = AirPlay2JNI.nativeAp2Connect(newHandle)
        if (!connected) {
            AirPlay2JNI.nativeAp2Destroy(newHandle)
            throw RuntimeException(
                "AP2 pair-verify/connect failed for ${receiver.name}"
            )
        }

        Log.i(TAG, "Connected AP2 to ${receiver.name} via pair-verify")
        return PairedSession(AirPlay2Session(context, newHandle, host, port), pending.receiverId)
    }

    /** Destroy any stale pending pairing handle. */
    private fun destroyPendingPair() {
        val stale = synchronized(this) {
            val p = pendingPairState
            pendingPairState = null
            p
        }
        if (stale != null) {
            Log.i(TAG, "Destroying stale pending pair handle for ${stale.receiverId}")
            AirPlay2JNI.nativeAp2Destroy(stale.handle)
        }
    }

    /**
     * Apply the effective discovery state. Starts or stops the
     * underlying [AirPlayDiscovery] as needed. The forwarded
     * `currentOnUpdate` is captured at the moment of start so a late
     * caller does not strand themselves with no callback.
     */
    private fun applyEffective(effective: Boolean) {
        if (effective && !scanning) {
            val cb = currentOnUpdate ?: return
            discovery.start(cb)
            scanning = true
        } else if (!effective && scanning) {
            discovery.stop()
            scanning = false
        }
    }

    /**
     * Test hook — releases the coroutine scope. Production code never
     * tears down the backend, but JVM unit tests need to drain the
     * scope to avoid leaking dispatcher threads between cases.
     */
    internal fun shutdownForTest() {
        effectiveJob?.cancel()
        effectiveJob = null
        scope.cancel()
    }

    private companion object {
        const val TAG = "AirPlayBackend"

        // libraop defaults matching AirPlay 1 standard: 44100 Hz, 16-bit, stereo
        const val SAMPLE_RATE = 44100
        const val SAMPLE_SIZE = 16
        const val CHANNELS = 2

        // Standard RAOP frame length — 352 samples per frame.
        const val FRAME_LENGTH = 352

        // ~2 seconds of latency at 44100 Hz — standard for AirPlay 1 receivers.
        const val LATENCY_FRAMES = 44100 * 2 / FRAME_LENGTH

        // Default RTSP port for AirPlay receivers.
        const val DEFAULT_RTSP_PORT = 5000

        // libraop volume scale: -30 dB silent, 0 dB max. Connect at a low,
        // safe level (0.30 linear → -21 dB) so a fresh AirPlay 1 connection
        // is audible but never blasts the receiver; the user / hardware
        // volume buttons adjust up afterward. The volume command IS still
        // sent on connect (receivers need it for correct audio decode) — just
        // sent quiet, not at max. (Receivers map this dB to their own 0-100
        // display via a non-linear curve — some floor the low end — so the
        // displayed % varies per device.)
        const val DEFAULT_VOLUME_DB = -21f

        // AP2 uses 0.0–1.0 linear scale (0=mute, 1=full). Connect at 0.30
        // (the native maps this to -21 dB) for the same reason as
        // DEFAULT_VOLUME_DB above: audible but never blast on connect.
        const val AP2_CONNECT_VOLUME = 0.30f
    }
}
