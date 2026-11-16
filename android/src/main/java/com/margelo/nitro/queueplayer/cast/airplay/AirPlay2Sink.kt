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
 * [AudioSink] that pushes decoded PCM into a native AirPlay 2 session
 * via [AirPlay2JNI]. Same ring-buffer + drain-thread architecture as
 * [AirPlaySink], but uses the libairplay2 native library.
 *
 * @param handle opaque native session pointer from [AirPlay2JNI.nativeAp2Open]
 */
@UnstableApi
class AirPlay2Sink(private val handle: Long) : AudioSink {

    companion object {
        private const val TAG = "AirPlay2Sink"
        private const val BYTES_PER_SAMPLE_16BIT = 2
    }

    private var sampleRate = 0
    private var channelCount = 0
    private var bytesPerFrame = 0
    private var configured = false

    private var playing = false
    private var endOfStreamSignaled = false
    private var bufferCallCount = 0L
    private var startPresentationTimeUs = Long.MIN_VALUE
    private var bytesSentAtFlush = 0L

    @Volatile
    private var released = false

    private var listener: AudioSink.Listener? = null

    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
    }

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
    }

    override fun flush() {
        Log.i(TAG, "flush()")
        endOfStreamSignaled = false
        startPresentationTimeUs = Long.MIN_VALUE
        bytesSentAtFlush = AirPlay2JNI.nativeAp2GetBytesSent(handle)
    }

    override fun reset() {
        Log.i(TAG, "reset()")
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
        Log.i(TAG, "release()")
        released = true
        reset()
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        if (released || !configured) return false

        val remaining = buffer.remaining()
        if (remaining == 0) return true

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

        val consumed = AirPlay2JNI.nativeAp2FeedPcm(handle, data, offset, remaining)

        if (consumed > 0) {
            buffer.position(buffer.position() + consumed)

            if (startPresentationTimeUs == Long.MIN_VALUE) {
                startPresentationTimeUs = presentationTimeUs
                Log.i(TAG, "startPresentationTimeUs=$presentationTimeUs (${presentationTimeUs / 1_000_000}s into track)")
            }

            if (bufferCallCount++ % 1000 == 0L) {
                val bytesSent = AirPlay2JNI.nativeAp2GetBytesSent(handle)
                Log.d(TAG, "handleBuffer #$bufferCallCount: fed $consumed bytes, bytesSent=$bytesSent")
            }
            return consumed == remaining
        }

        if (bufferCallCount++ % 2000 == 0L) {
            Log.d(TAG, "handleBuffer #$bufferCallCount: ring buffer full, back-pressure")
        }
        return false
    }

    override fun handleDiscontinuity() {}

    override fun playToEndOfStream() {
        if (released) return
        if (!endOfStreamSignaled) {
            Log.i(TAG, "playToEndOfStream: signaled, ringAvail=${AirPlay2JNI.nativeAp2RingAvailable(handle)}")
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
        return AirPlay2JNI.nativeAp2RingAvailable(handle) > 0
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        if (released || !configured || sampleRate == 0 || bytesPerFrame == 0) {
            return AudioSink.CURRENT_POSITION_NOT_SET
        }
        val baseUs = if (startPresentationTimeUs != Long.MIN_VALUE) startPresentationTimeUs else 0L
        val bytesSent = AirPlay2JNI.nativeAp2GetBytesSent(handle) - bytesSentAtFlush
        val framesSent = bytesSent / bytesPerFrame
        return baseUs + framesSent * 1_000_000L / sampleRate
    }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {}
    override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters.DEFAULT
    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {}
    override fun getSkipSilenceEnabled(): Boolean = false
    override fun setAudioAttributes(audioAttributes: AudioAttributes) {}
    override fun getAudioAttributes(): AudioAttributes? = null
    override fun setAudioSessionId(audioSessionId: Int) {}
    override fun setAuxEffectInfo(auxEffectInfo: androidx.media3.common.AuxEffectInfo) {}
    override fun setPreferredDevice(audioDeviceInfo: AudioDeviceInfo?) {}
    override fun setVolume(volume: Float) {}
    override fun enableTunnelingV21() {}
    override fun disableTunneling() {}

    override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport {
        return AudioOffloadSupport.DEFAULT_UNSUPPORTED
    }

    override fun getAudioTrackBufferSizeUs(): Long = 0
}
