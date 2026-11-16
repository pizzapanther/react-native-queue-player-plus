package com.margelo.nitro.queueplayer

import android.content.Context
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

/**
 * Generic [DefaultRenderersFactory] that wires a caller-supplied
 * [AudioProcessorChain] into the produced [AudioSink].
 *
 * Uses [DefaultAudioSink.Builder.setAudioProcessorChain], which — unlike
 * `setAudioProcessors` — does NOT auto-append Sonic + silence-skipping. The
 * chain therefore owns the ENTIRE pipeline composition (ReplayGain, the
 * visualizer Tee, and the mode-gated pitch-correction stretchers); the factory
 * owns no subsystem state. The engine builds the chain and passes it in.
 *
 * Per-AudioSink chain instances are mandatory: the chain holds stateful
 * processors (the visualizer [androidx.media3.exoplayer.audio.TeeAudioProcessor]
 * and the music time-stretcher), and sharing one across two AudioSinks would
 * interleave their `flush` + buffer state. The CrossfadeEngine builds two
 * factories (one per leg); each receives its own chain.
 *
 * Float output is deliberately NOT enabled. With Media3 float output, a hi-res
 * (24-bit+) source produces a float sink, and [DefaultAudioSink.configure] then
 * DROPS the entire custom [AudioProcessorChain] — its float branch omits the
 * chain because the built-in Sonic outputs 16-bit — silently disabling playback
 * speed, pitch-correction, ReplayGain, and the visualizer on hi-res content.
 * Keeping the sink at 16-bit (Media3 downconverts hi-res) keeps the chain in the
 * pipeline for every source; [ReplayGainAudioProcessor] and
 * [SignalsmithStretchAudioProcessor] both process 16-bit PCM directly.
 */
@UnstableApi
internal class AudioPipelineRenderersFactory(
  context: Context,
  private val audioProcessorChain: AudioProcessorChain,
) : DefaultRenderersFactory(context) {

  override fun buildAudioSink(
    context: Context,
    enableFloatOutput: Boolean,
    enableAudioTrackPlaybackParams: Boolean,
  ): AudioSink {
    // `enableAudioTrackPlaybackParams` is the legacy name from
    // DefaultRenderersFactory.buildAudioSink; the DefaultAudioSink
    // Builder method that accepts it is now
    // `setEnableAudioOutputPlaybackParameters` — same boolean, broader
    // scope (controls output-level rather than AudioTrack-only
    // playback params).
    return DefaultAudioSink.Builder(context)
      .setAudioProcessorChain(audioProcessorChain)
      .setEnableFloatOutput(enableFloatOutput)
      .setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)
      .build()
  }
}
