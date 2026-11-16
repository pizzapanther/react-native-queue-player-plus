package com.margelo.nitro.queueplayer.cast.airplay

import android.util.Log
import androidx.media3.common.Player
import androidx.media3.exoplayer.audio.AudioSink
import com.margelo.nitro.queueplayer.cast.CastSession
import com.margelo.nitro.queueplayer.cast.MetadataAwareSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * A live AirPlay 2 streaming session backed by libairplay2 via [AirPlay2JNI].
 *
 * Lifecycle: created by [AirPlayBackend.connect], which opens the native
 * session, runs the full connection sequence (pair → SETUP → RECORD →
 * start audio), and hands back this session. [audioSink] returns the
 * [AirPlay2Sink] that ExoPlayer pushes decoded PCM into. [close] tears
 * down the native session cleanly.
 *
 * @param handle opaque native session pointer from [AirPlay2JNI.nativeAp2Open]
 * @param host receiver IP address
 * @param port receiver RTSP port
 */
class AirPlay2Session(
    private val context: android.content.Context,
    private val handle: Long,
    private val host: String,
    private val port: Int,
) : CastSession.PcmStream(), MetadataAwareSession, MetadataSyncTarget {

    companion object {
        private const val TAG = "AirPlay2Session"
    }

    private val sink = AirPlay2Sink(handle)
    @Volatile private var closed = false
    // Swapped on the player's looper by onActivated, read and cleared on the
    // disconnecting thread — the exchange has to be atomic or a teardown can
    // read a stale null and leave the previous sync running.
    private val metadataSync = AtomicReference<AirPlayMetadataSync?>()

    /** Internal scope for fire-and-forget native teardown. */
    private val teardownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        AirPlay2JNI.registerSession(handle) { reason -> onRemoteDied(reason) }
    }

    override fun audioSink(): AudioSink = sink

    override fun setVolume(value: Float) {
        if (closed) return
        AirPlay2JNI.nativeAp2SetVolume(handle, value)
    }

    override fun onActivated(player: Player, scope: CoroutineScope) {
        if (closed) return
        // Stop any prior sync before starting the replacement, so a repeat
        // activation (the player swaps under a still-connected session) never
        // leaves an orphaned sync running against the previous player.
        val next = AirPlayMetadataSync(context, this, player, scope)
        metadataSync.getAndSet(next)?.stop()
        next.start()
        // A close() that ran while this was publishing exchanged out a null
        // and tore down without seeing `next`, so re-check: otherwise a live
        // sync stays bound to a closed session for the process lifetime.
        if (closed) metadataSync.getAndSet(null)?.stop()
    }

    override fun onDeactivated() {
        metadataSync.getAndSet(null)?.stop()
    }

    /**
     * Invoked from the native drain thread (via [AirPlay2JNI.onNativeSessionDied])
     * when the receiver has stopped responding. Notifies any [deathListener]
     * so `CastManager` can revert routing to local, then runs the normal close
     * path — native TEARDOWN is skipped because the socket is dead.
     */
    private fun onRemoteDied(reason: Int) {
        if (closed) return
        Log.w(TAG, "Remote receiver died (reason=$reason) — tearing down session")
        val label = when (reason) {
            AirPlay2JNI.DIED_FEEDBACK_TIMEOUT -> "feedback-timeout"
            else -> "unknown"
        }
        deathListener?.invoke(label)
        close()
    }

    override fun close() {
        if (closed) return
        closed = true
        onDeactivated()
        Log.i(TAG, "Closing AirPlay 2 session to $host:$port")
        sink.release()
        AirPlay2JNI.unregisterSession(handle)

        /* Native TEARDOWN can block on a dead socket — never run it on the
         * caller's thread (`CastManager.disconnect()` on the JS thread, a
         * replacing `connect()` or `submitPairingPin()` on `Dispatchers.IO`,
         * `onRemoteDied` on the native drain thread). */
        val h = handle
        teardownScope.launch {
            try {
                AirPlay2JNI.nativeAp2Disconnect(h)
                AirPlay2JNI.nativeAp2Destroy(h)
            } catch (t: Throwable) {
                Log.e(TAG, "Native teardown failed", t)
            }
        }
    }

    override fun setMetadata(title: String?, artist: String?, album: String?) {
        if (closed) return
        AirPlay2JNI.nativeAp2SetMetadata(handle, title, artist, album)
    }

    override fun setArtwork(imageData: ByteArray, contentType: String) {
        if (closed) return
        AirPlay2JNI.nativeAp2SetArtwork(handle, imageData, imageData.size)
    }

    override fun setProgress(elapsedMs: Int, durationMs: Int) {
        if (closed) return
        AirPlay2JNI.nativeAp2SetProgressMs(handle, elapsedMs.toLong(), durationMs.toLong())
    }

    val isConnected: Boolean
        get() = !closed && AirPlay2JNI.nativeAp2GetState(handle) >= AirPlay2JNI.STATE_STREAMING
}
