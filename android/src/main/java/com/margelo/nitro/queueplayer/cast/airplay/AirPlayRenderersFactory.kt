package com.margelo.nitro.queueplayer.cast.airplay

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink

/**
 * Renderers factory for the dedicated AirPlay engine: its audio renderer's
 * [AudioSink] IS the AirPlay session's sink ([AirPlay2Sink] / [AirPlaySink]).
 *
 * Deliberately a bare [DefaultRenderersFactory]: no `setEnableAudioFloatOutput`,
 * no audio processors, no extension-renderer preference. The AirPlay sinks
 * only accept `AUDIO_RAW` + `ENCODING_PCM_16BIT`, and the default factory
 * decodes to Int16 PCM — so the renderer negotiates a format the sink accepts
 * and the PCM reaches the receiver clean. Float output or a processor chain
 * in front of the sink corrupts the AirPlay stream, which is why this engine
 * has its own factory instead of sharing the local pipeline's.
 *
 * One sink per factory instance — the AirPlay engine is built fresh for each
 * session and released when the session ends, so the sink is never swapped at
 * runtime.
 */
@UnstableApi
internal class AirPlayRenderersFactory(
  context: Context,
  private val airplaySink: AudioSink,
) : DefaultRenderersFactory(context) {

  override fun buildAudioSink(
    context: Context,
    enableFloatOutput: Boolean,
    enableAudioTrackPlaybackParams: Boolean,
  ): AudioSink = airplaySink
}
