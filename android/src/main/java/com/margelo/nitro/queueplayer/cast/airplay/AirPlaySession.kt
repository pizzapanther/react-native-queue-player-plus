package com.margelo.nitro.queueplayer.cast.airplay

import android.util.Log
import androidx.media3.common.Player
import androidx.media3.exoplayer.audio.AudioSink
import com.margelo.nitro.queueplayer.cast.CastSession
import com.margelo.nitro.queueplayer.cast.MetadataAwareSession
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.atomic.AtomicReference

/**
 * A live AirPlay 1 streaming session backed by libraop via [AirplayJNI].
 *
 * Lifecycle: created by [AirPlayBackend.connect], which opens the native
 * RAOP client, connects to the receiver, and hands back this session.
 * [audioSink] returns the [AirPlaySink] that ExoPlayer pushes decoded
 * PCM into. [close] tears down the native session and is safe to call
 * multiple times.
 *
 * @param handle opaque native session pointer from [AirplayJNI.nativeOpen]
 * @param host receiver IP address (for reconnect / logging)
 * @param port receiver RTSP port
 */
class AirPlaySession(
    private val context: android.content.Context,
    private val handle: Long,
    private val host: String,
    private val port: Int,
) : CastSession.PcmStream(), MetadataAwareSession, MetadataSyncTarget {

    companion object {
        private const val TAG = "AirPlaySession"
    }

    private val sink = AirPlaySink(handle)

    // Read by every sender, set by close() on whichever thread disconnected.
    @Volatile
    private var closed = false

    // Swapped on the player's looper by onActivated, read and cleared on the
    // disconnecting thread — the exchange has to be atomic or a teardown can
    // read a stale null and leave the previous sync running.
    private val metadataSync = AtomicReference<AirPlayMetadataSync?>()

    override fun audioSink(): AudioSink = sink

    override fun setVolume(value: Float) {
        if (closed) return
        // libraop volume is in dB: -30 (silent) to 0 (max).
        // Map 0.0..1.0 → -30..0 linearly.
        val dbVolume = -30f + (value.coerceIn(0f, 1f) * 30f)
        AirplayJNI.nativeSetVolume(handle, dbVolume)
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

    override fun close() {
        if (closed) return
        // Closed before the sync is torn down so an in-flight sender bails at
        // its own guard rather than reaching a handle nativeDestroy has freed.
        closed = true
        onDeactivated()
        Log.i(TAG, "Closing AirPlay session to $host:$port")
        // Release the sink BEFORE the native handle is torn down: this sets
        // the sink's volatile `released` flag so the renderer thread stops
        // feeding PCM into JNI on a handle that nativeDestroy is about to
        // free. Mirrors AirPlay2Session.close().
        sink.release()
        AirplayJNI.nativeStop(handle)
        AirplayJNI.nativeDisconnect(handle)
        AirplayJNI.nativeDestroy(handle)
    }

    override fun setMetadata(title: String?, artist: String?, album: String?) {
        if (closed) return
        AirplayJNI.nativeSetDaap(handle, title, artist, album)
    }

    override fun setArtwork(imageData: ByteArray, contentType: String) {
        if (closed) return
        AirplayJNI.nativeSetArtwork(handle, imageData, imageData.size, contentType)
    }

    override fun setProgress(elapsedMs: Int, durationMs: Int) {
        if (closed) return
        AirplayJNI.nativeSetProgressMs(handle, elapsedMs, durationMs)
    }

    /** Send a keepalive to prevent the receiver from timing out. */
    fun keepalive(): Boolean {
        if (closed) return false
        return AirplayJNI.nativeKeepalive(handle)
    }

    val isConnected: Boolean get() = !closed && AirplayJNI.nativeIsConnected(handle)
}
