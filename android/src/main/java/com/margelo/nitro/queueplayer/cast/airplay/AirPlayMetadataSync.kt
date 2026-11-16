package com.margelo.nitro.queueplayer.cast.airplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.net.URL

/**
 * Syncs ExoPlayer track metadata and playback progress to an AirPlay
 * receiver through [MetadataSyncTarget].
 *
 * - On activation and on every track transition: sends metadata
 *   (title/artist/album) and kicks off an async artwork fetch.
 * - Every 5 seconds while playing: sends progress (elapsed/duration).
 * - On seek: sends progress immediately.
 * - On stop: cancels all coroutines and removes the player listener.
 *
 * One instance covers one activation — [start] then [stop]. Starting again
 * after a stop is a no-op; a new activation builds a new instance.
 */
internal class AirPlayMetadataSync(
    private val context: Context,
    private val target: MetadataSyncTarget,
    private val player: Player,
    scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "AirPlayMetadataSync"
        private const val PROGRESS_INTERVAL_MS = 5000L
        private const val MAX_ARTWORK_DIM = 600
        private const val JPEG_QUALITY = 80
    }

    /**
     * Owns every coroutine this sync launches, so [stop] can cancel them as
     * one atomic operation from any thread. Cancelling the scope needs no
     * field read, so a teardown racing the player looper cannot miss a job,
     * and a launch that loses the race starts already-cancelled rather than
     * outliving the session it pushes into.
     */
    private val syncScope =
        CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))

    // Touched only on the player's application looper.
    private var progressJob: Job? = null
    private var artworkJob: Job? = null
    private var lastArtworkUri: String? = null

    // Written by start/stop on whichever thread activated or tore the
    // session down; read on the looper and by the progress loop.
    @Volatile
    private var started = false

    // Set on stop and never cleared, so a restart cannot half-revive a spent instance: the
    // scope is already cancelled, so the listener would still push metadata
    // while the progress loop and artwork fetch stay dead.
    @Volatile
    private var stopped = false

    // ExoPlayer rejects add/removeListener + state reads off its application
    // looper. start()/stop() are driven by session activation/close, and
    // close() runs on whichever thread disconnected (the JS thread for a
    // user disconnect, the native drain thread on receiver death) — so the
    // player touches are marshalled onto the player's looper, run inline when
    // already on it.
    private val playerHandler = Handler(player.applicationLooper)

    // Both protocols log under one tag, so every line says which sender it
    // came from.
    private val targetName = target.javaClass.simpleName

    private fun onPlayerLooper(block: () -> Unit) {
        if (Looper.myLooper() == player.applicationLooper) block()
        else playerHandler.post(block)
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            sendCurrentMetadata()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                sendProgress()
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!started) return
            if (playWhenReady) {
                startProgressTimer()
            } else {
                progressJob?.cancel()
                progressJob = null
            }
        }
    }

    fun start() {
        if (started || stopped) return
        started = true
        onPlayerLooper {
            // A stop() between this post and its execution has already run its
            // own removeListener, so attaching now would leave the listener on
            // the player for its lifetime.
            if (stopped) return@onPlayerLooper
            player.addListener(listener)
            sendCurrentMetadata()
            if (started && player.playWhenReady) {
                startProgressTimer()
            }
        }
        Log.i(TAG, "Started metadata sync for $targetName")
    }

    fun stop() {
        if (stopped) return
        stopped = true
        started = false
        syncScope.cancel()
        // Listener removal has to hop to the looper, so a callback queued
        // ahead of it can still run; the `started` gate in the senders is
        // what stops those from reaching a session that is being closed.
        onPlayerLooper { player.removeListener(listener) }
        Log.i(TAG, "Stopped metadata sync for $targetName")
    }

    private fun sendCurrentMetadata() {
        if (!started) return
        val meta = player.currentMediaItem?.mediaMetadata ?: return
        val title = meta.title?.toString()
        val artist = meta.artist?.toString()
        val album = meta.albumTitle?.toString()

        Log.i(TAG, "$targetName sending metadata: title=$title, artist=$artist, album=$album")
        target.setMetadata(title, artist, album)

        // Send initial progress for the new track
        sendProgress()

        // Fetch and send artwork
        val artUri = meta.artworkUri?.toString()
        if (artUri != null && artUri != lastArtworkUri) {
            lastArtworkUri = artUri
            fetchAndSendArtwork(artUri)
        }
    }

    private fun sendProgress() {
        if (!started) return
        val position = player.currentPosition.toInt()
        val duration = player.duration.let { if (it > 0) it.toInt() else 0 }
        if (duration > 0) {
            target.setProgress(position, duration)
        }
    }

    private fun startProgressTimer() {
        progressJob?.cancel()
        progressJob = syncScope.launch {
            while (isActive) {
                delay(PROGRESS_INTERVAL_MS)
                if (player.isPlaying) {
                    sendProgress()
                }
            }
        }
    }

    private fun fetchAndSendArtwork(uriString: String) {
        artworkJob?.cancel()
        artworkJob = syncScope.launch(Dispatchers.IO) {
            try {
                val uri = Uri.parse(uriString)
                val scheme = uri.scheme ?: return@launch

                val rawBytes: ByteArray = when (scheme) {
                    "content", "android.resource" ->
                        context.contentResolver.openInputStream(uri)
                            ?.use { it.readBytes() } ?: return@launch
                    "file" ->
                        java.io.File(uri.path ?: return@launch).readBytes()
                    "http", "https" ->
                        URL(uriString).openStream().use { it.readBytes() }
                    else -> {
                        Log.w(TAG, "Unsupported artwork scheme: $scheme")
                        return@launch
                    }
                }

                // First pass: decode bounds only
                val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, boundsOpts)

                // Calculate sample size for max 600x600
                var sampleSize = 1
                while (boundsOpts.outWidth / sampleSize > MAX_ARTWORK_DIM ||
                       boundsOpts.outHeight / sampleSize > MAX_ARTWORK_DIM) {
                    sampleSize *= 2
                }

                // Second pass: decode with subsampling
                val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                val bitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOpts)
                    ?: return@launch

                // Compress to JPEG
                val baos = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos)
                bitmap.recycle()

                val bytes = baos.toByteArray()
                if (isActive && started) {
                    target.setArtwork(bytes, "image/jpeg")
                    Log.i(TAG, "$targetName sent artwork: ${bytes.size} bytes from $uriString")
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.w(TAG, "Artwork fetch failed for $uriString", e)
                }
            }
        }
    }
}
