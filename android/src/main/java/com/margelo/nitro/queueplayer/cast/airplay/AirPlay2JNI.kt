package com.margelo.nitro.queueplayer.cast.airplay

/**
 * JNI surface for the native libairplay2 library. Methods are
 * registered via `JNI_OnLoad` in airplay2_jni.cpp using RegisterNatives.
 *
 * Session lifecycle:
 *   [nativeAp2Open] → [nativeAp2Connect] → stream/control → [nativeAp2Disconnect] → [nativeAp2Destroy]
 *
 * The handle returned by [nativeAp2Open] is an opaque pointer (Long);
 * pass it to every subsequent call.
 */
object AirPlay2JNI {

    /** State constants matching ap2_state enum in airplay2_session.h. */
    const val STATE_INIT          = 0
    const val STATE_INFO          = 1
    const val STATE_AUTH_SETUP    = 2
    const val STATE_PAIR          = 3
    const val STATE_ENCRYPTED     = 4
    const val STATE_SETUP_SESSION = 5
    const val STATE_SETUP_STREAM  = 6
    const val STATE_RECORD        = 7
    const val STATE_STREAMING     = 8
    const val STATE_TEARDOWN      = 9

    /** Auth mode constants matching ap2_auth_mode enum in airplay2_session.h. */
    const val AUTH_TRANSIENT = 0
    const val AUTH_PIN       = 1
    const val AUTH_PASSWORD  = 2

    /**
     * `true` when libairplay2.so loaded successfully. Mirrors
     * [AirplayJNI.isAvailable] — ABIs without prebuilt native libs report
     * `false` and callers must skip every JNI entry rather than crash.
     */
    @JvmStatic
    val isAvailable: Boolean = try {
      System.loadLibrary("airplay2")
      true
    } catch (t: Throwable) {
      android.util.Log.w("AirPlay2JNI", "libairplay2.so unavailable on this ABI: $t")
      false
    }

    /** Session-death reason codes matching airplay2_jni.cpp AP2_DIED_*. */
    const val DIED_FEEDBACK_TIMEOUT = 1

    /**
     * Registry of live sessions, keyed by native handle. Populated by
     * [AirPlay2Session] on construction, removed on close. Native code
     * upcalls into [onNativeSessionDied] with a handle and we dispatch
     * to the registered session's own `onRemoteDied`.
     */
    private val sessionRegistry: MutableMap<Long, (Int) -> Unit> =
        java.util.concurrent.ConcurrentHashMap()

    @JvmStatic
    internal fun registerSession(handle: Long, onDied: (Int) -> Unit) {
        sessionRegistry[handle] = onDied
    }

    @JvmStatic
    internal fun unregisterSession(handle: Long) {
        sessionRegistry.remove(handle)
    }

    /**
     * Called from the native drain thread when the session is declared dead.
     * Runs on whatever thread JNI attaches us to; dispatch must be minimal —
     * forward to the registered handler and return.
     */
    @JvmStatic
    fun onNativeSessionDied(handle: Long, reason: Int) {
        sessionRegistry[handle]?.invoke(reason)
    }

    // -- Session lifecycle --

    @JvmStatic external fun nativeAp2Open(
        host: String, port: Int,
        sampleRate: Int, sampleSize: Int, channels: Int,
        volume: Float,
    ): Long

    @JvmStatic external fun nativeAp2SetAuthKey(handle: Long, authKey: String)
    @JvmStatic external fun nativeAp2SetDeviceName(handle: Long, name: String)
    @JvmStatic external fun nativeAp2Connect(handle: Long): Boolean
    @JvmStatic external fun nativeAp2Disconnect(handle: Long)
    @JvmStatic external fun nativeAp2Destroy(handle: Long)

    // -- Pairing --

    @JvmStatic external fun nativeAp2PairPinStart(handle: Long): Boolean
    @JvmStatic external fun nativeAp2PairSetup(handle: Long, credential: String): String?
    @JvmStatic external fun nativeAp2GetAuthMode(handle: Long): Int
    @JvmStatic external fun nativeAp2GetDeviceId(handle: Long): String?
    @JvmStatic external fun nativeAp2SetPassword(handle: Long, password: String)

    // -- Audio --

    /** Feed PCM data into the native ring buffer. Returns bytes consumed. */
    @JvmStatic external fun nativeAp2FeedPcm(
        handle: Long, pcm: ByteArray, offset: Int, length: Int,
    ): Int

    @JvmStatic external fun nativeAp2GetBytesSent(handle: Long): Long
    @JvmStatic external fun nativeAp2RingAvailable(handle: Long): Int

    // -- Control --

    @JvmStatic external fun nativeAp2SetVolume(handle: Long, volume: Float)
    @JvmStatic external fun nativeAp2SetMetadata(handle: Long, title: String?, artist: String?, album: String?)
    @JvmStatic external fun nativeAp2SetArtwork(handle: Long, jpeg: ByteArray, length: Int)
    @JvmStatic external fun nativeAp2SetProgressMs(handle: Long, elapsed: Long, duration: Long)
    @JvmStatic external fun nativeAp2Pause(handle: Long)
    @JvmStatic external fun nativeAp2Resume(handle: Long)

    // -- Status --

    @JvmStatic external fun nativeAp2GetState(handle: Long): Int

    // -- File logging (persists on device when USB disconnected) --

    @JvmStatic external fun nativeAp2InitFileLog(path: String)
    @JvmStatic external fun nativeAp2CloseFileLog()
}
