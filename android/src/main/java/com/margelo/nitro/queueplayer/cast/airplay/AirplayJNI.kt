package com.margelo.nitro.queueplayer.cast.airplay

/**
 * JNI surface for the native libraop library. Methods are registered
 * via `JNI_OnLoad` in airplay_jni.cpp using RegisterNatives.
 *
 * Session lifecycle: [nativeOpen] → [nativeConnect] → send/control → [nativeDisconnect] → [nativeDestroy].
 * The handle returned by [nativeOpen] is an opaque pointer (Long); pass it to every subsequent call.
 */
object AirplayJNI {

    /** RAOP codec constants (maps to raop_codec_t). */
    const val CODEC_PCM = 0
    const val CODEC_ALAC_RAW = 1
    const val CODEC_ALAC = 2
    const val CODEC_AAC = 3
    const val CODEC_AAC_ELC = 4

    /** RAOP crypto constants (maps to raop_crypto_t). */
    const val CRYPTO_CLEAR = 0
    const val CRYPTO_RSA = 1

    /** RAOP state constants (maps to raop_state_t). */
    const val STATE_DOWN = 0
    const val STATE_FLUSHING = 1
    const val STATE_FLUSHED = 2
    const val STATE_STREAMING = 3

    /**
     * `true` when libraop.so loaded successfully. ABIs without prebuilt
     * native libs (armeabi-v7a, x86) report `false`, and callers must
     * skip every JNI entry rather than crash with `UnsatisfiedLinkError`.
     */
    @JvmStatic
    val isAvailable: Boolean = try {
      System.loadLibrary("raop")
      true
    } catch (t: Throwable) {
      android.util.Log.w("AirplayJNI", "libraop.so unavailable on this ABI: $t")
      false
    }

    // -- Session lifecycle --

    /**
     * Create a RAOP client session. Returns a handle (opaque pointer), or 0 on failure.
     */
    @JvmStatic external fun nativeOpen(
        host: String, port: Int,
        codec: Int, frameLen: Int, latencyFrames: Int,
        crypto: Int, auth: Boolean,
        sampleRate: Int, sampleSize: Int, channels: Int,
        volume: Float,
    ): Long

    @JvmStatic external fun nativeConnect(handle: Long, host: String, port: Int): Boolean
    @JvmStatic external fun nativeDisconnect(handle: Long): Boolean
    @JvmStatic external fun nativeDestroy(handle: Long)

    // -- Audio streaming (ring-buffer API) --

    /** Feed PCM bytes into the ring buffer. Returns bytes consumed (== length, or 0 if full). */
    @JvmStatic external fun nativeFeedPcm(handle: Long, data: ByteArray, offset: Int, length: Int): Int

    /** Total PCM bytes successfully sent to the RAOP receiver (for position tracking). */
    @JvmStatic external fun nativeBytesSent(handle: Long): Long

    /** Bytes waiting in the ring buffer, not yet sent. Used for hasPendingData(). */
    @JvmStatic external fun nativeRingAvailable(handle: Long): Int

    // -- Transport control --

    @JvmStatic external fun nativePause(handle: Long)
    @JvmStatic external fun nativeStop(handle: Long)
    @JvmStatic external fun nativeFlush(handle: Long): Boolean

    // -- Configuration --

    @JvmStatic external fun nativeGetLastRtspStatus(handle: Long): Int
    @JvmStatic external fun nativeSetPassword(handle: Long, password: String?)
    @JvmStatic external fun nativeSetDeviceName(handle: Long, name: String)

    // -- Volume & metadata --

    @JvmStatic external fun nativeSetVolume(handle: Long, volume: Float): Boolean
    @JvmStatic external fun nativeSetProgressMs(handle: Long, elapsed: Int, duration: Int): Boolean
    @JvmStatic external fun nativeSetDaap(handle: Long, title: String?, artist: String?, album: String?): Boolean
    @JvmStatic external fun nativeSetArtwork(handle: Long, imageData: ByteArray, length: Int, contentType: String): Boolean

    // -- State queries --

    @JvmStatic external fun nativeIsConnected(handle: Long): Boolean
    @JvmStatic external fun nativeKeepalive(handle: Long): Boolean
}
