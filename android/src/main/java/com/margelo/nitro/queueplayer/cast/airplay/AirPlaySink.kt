package com.margelo.nitro.queueplayer.cast.airplay

import android.media.AudioDeviceInfo
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import java.nio.ByteBuffer

/**
 * [AudioSink] that pushes decoded PCM into a libraop AirPlay 1 session
 * via JNI. Uses a ring-buffer + drain-thread architecture:
 *
 * - [handleBuffer] copies PCM bytes into a native ring buffer via
 *   [AirplayJNI.nativeFeedPcm]. Returns true when all bytes are consumed.
 * - A native drain thread (started by [AirplayJNI.nativeConnect]) pulls
 *   352-frame chunks from the ring buffer, paces them with
 *   `raopcl_accept_frames`, and sends via `raopcl_send_chunk`.
 * - [getCurrentPositionUs] computes from [AirplayJNI.nativeBytesSent]
 *   (total PCM bytes acknowledged by the RAOP layer).
 *
 * Only 16-bit signed PCM is supported (the only format libraop accepts).
 *
 * @param handle opaque native session handle from [AirplayJNI.nativeOpen]
 */
@UnstableApi
class AirPlaySink(private val handle: Long) : AudioSink {

    companion object {
        private const val TAG = "AirPlaySink"
        private const val BYTES_PER_SAMPLE_16BIT = 2
    }

    // -- Configured format --
    private var sampleRate = 0
    private var channelCount = 0
    private var bytesPerFrame = 0
    private var configured = false

    // -- Playback state --
    private var playing = false
    private var endOfStreamSignaled = false
    private var bufferCallCount = 0L
    private var startPresentationTimeUs = Long.MIN_VALUE
    private var bytesSentAtFlush = 0L

    /**
     * Set to true by [release]. Guards all JNI calls so that the renderer
     * thread cannot touch the native handle after [AirPlaySession.close]
     * destroys it. Volatile for cross-thread visibility (main thread
     * writes, ExoPlayer renderer thread reads).
     */
    @Volatile
    private var released = false

    private var listener: AudioSink.Listener? = null

    // -- AudioSink: lifecycle / identity --

    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
    }

    // -- AudioSink: format queries --

    override fun supportsFormat(format: Format): Boolean {
        return format.sampleMimeType == MimeTypes.AUDIO_RAW &&
            format.pcmEncoding == android.media.AudioFormat.ENCODING_PCM_16BIT
    }

    override fun getFormatSupport(format: Format): Int {
        return if (supportsFormat(format)) {
            AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        } else {
            AudioSink.SINK_FORMAT_UNSUPPORTED
        }
    }

    // -- AudioSink: configuration and playback control --

    override fun configure(
        inputFormat: Format,
        specifiedBufferSize: Int,
        outputChannels: IntArray?,
    ) {
        sampleRate = inputFormat.sampleRate
        channelCount = inputFormat.channelCount
        bytesPerFrame = channelCount * BYTES_PER_SAMPLE_16BIT
        configured = true
        endOfStreamSignaled = false
        Log.i(TAG, "configure: ${sampleRate}Hz, ${channelCount}ch, pcm=${inputFormat.pcmEncoding}")
    }

    override fun play() {
        playing = true
    }

    override fun pause() {
        playing = false
        // Do NOT call nativePause here. The drain thread owns the libraop
        // state machine; calling raopcl_pause while STREAMING creates a
        // permanent deadlock in accept_frames (flushing=true + state!=FLUSHED
        // + start_ts=0 → early return forever). ExoPlayer calls pause()
        // during sink swaps, which would trigger this on every connect.
        // The ring buffer naturally drains; actual RAOP pause is handled
        // at the session level when the user explicitly pauses.
    }

    override fun flush() {
        Log.i(TAG, "flush() called, endOfStream was $endOfStreamSignaled")
        // Don't call nativeFlush here — the drain thread manages libraop
        // state. Flushing libraop while the drain thread is running would
        // cause state corruption. The ring buffer handles back-pressure.
        endOfStreamSignaled = false
        startPresentationTimeUs = Long.MIN_VALUE
        bytesSentAtFlush = AirplayJNI.nativeBytesSent(handle)
    }

    override fun reset() {
        Log.i(TAG, "reset() called")
        flush()
        configured = false
        playing = false
    }

    override fun release() {
        // Idempotent: the dedicated AirPlay engine and the session's
        // close() both release this sink. reset() calls JNI on the native
        // handle (via flush), so a second release after the handle is torn
        // down would crash — short-circuit once released.
        if (released) return
        Log.i(TAG, "release() called")
        released = true  // Volatile write — blocks JNI on renderer thread
        reset()
    }

    // -- AudioSink: data path --

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        if (released || !configured) return false

        val remaining = buffer.remaining()
        if (remaining == 0) return true

        // Copy data from the ByteBuffer into a byte[] for JNI.
        val data: ByteArray
        val offset: Int
        if (buffer.hasArray()) {
            data = buffer.array()
            offset = buffer.arrayOffset() + buffer.position()
        } else {
            data = ByteArray(remaining)
            buffer.duplicate().get(data)
            offset = 0
        }

        val consumed = AirplayJNI.nativeFeedPcm(handle, data, offset, remaining)

        if (consumed > 0) {
            buffer.position(buffer.position() + consumed)

            // Capture the first presentation timestamp as position base offset.
            // When activated mid-stream, ExoPlayer expects getCurrentPositionUs
            // to continue from where the previous sink left off. Without this,
            // StuckPlayerDetector fires after 10s of "no progress".
            if (startPresentationTimeUs == Long.MIN_VALUE) {
                startPresentationTimeUs = presentationTimeUs
                Log.i(TAG, "startPresentationTimeUs=$presentationTimeUs (${presentationTimeUs / 1_000_000}s into track)")
            }

            if (bufferCallCount++ % 1000 == 0L) {
                val bytesSent = AirplayJNI.nativeBytesSent(handle)
                Log.d(TAG, "handleBuffer #$bufferCallCount: fed $consumed bytes, bytesSent=$bytesSent")
            }
            return consumed == remaining
        }

        // Ring buffer full — back-pressure
        if (bufferCallCount++ % 2000 == 0L) {
            Log.d(TAG, "handleBuffer #$bufferCallCount: ring buffer full, back-pressure")
        }
        return false
    }

    override fun handleDiscontinuity() {
        // No special handling — libraop handles timing internally.
    }

    override fun playToEndOfStream() {
        if (released) return
        if (!endOfStreamSignaled) {
            Log.i(TAG, "playToEndOfStream: signaled, ringAvail=${AirplayJNI.nativeRingAvailable(handle)}")
        }
        endOfStreamSignaled = true
    }

    override fun isEnded(): Boolean {
        if (released) return true
        val pending = hasPendingData()
        val ended = endOfStreamSignaled && !pending
        if (ended && endOfStreamSignaled) {
            Log.i(TAG, "isEnded: true (ring drained)")
        }
        return ended
    }

    override fun hasPendingData(): Boolean {
        if (released) return false
        return AirplayJNI.nativeRingAvailable(handle) > 0
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        if (released || !configured || sampleRate == 0 || bytesPerFrame == 0) {
            return AudioSink.CURRENT_POSITION_NOT_SET
        }
        val baseUs = if (startPresentationTimeUs != Long.MIN_VALUE) startPresentationTimeUs else 0L
        val bytesSent = AirplayJNI.nativeBytesSent(handle) - bytesSentAtFlush
        val framesSent = bytesSent / bytesPerFrame
        return baseUs + framesSent * 1_000_000L / sampleRate
    }

    // -- AudioSink: playback parameters --

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        // AirPlay does not support speed/pitch adjustment.
    }

    override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters.DEFAULT

    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {}
    override fun getSkipSilenceEnabled(): Boolean = false

    // -- AudioSink: audio attributes and routing --

    override fun setAudioAttributes(audioAttributes: AudioAttributes) {}
    override fun getAudioAttributes(): AudioAttributes? = null
    override fun setAudioSessionId(audioSessionId: Int) {}
    override fun setAuxEffectInfo(auxEffectInfo: androidx.media3.common.AuxEffectInfo) {}
    override fun setPreferredDevice(audioDeviceInfo: AudioDeviceInfo?) {}

    override fun setVolume(volume: Float) {
        // Volume is handled at the session level via AirPlaySession.setVolume.
    }

    // -- AudioSink: tunneling and offload --

    override fun enableTunnelingV21() {}
    override fun disableTunneling() {}

    // -- AudioSink: misc --

    override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport {
        return AudioOffloadSupport.DEFAULT_UNSUPPORTED
    }

    override fun getAudioTrackBufferSizeUs(): Long = 0
}
