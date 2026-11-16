package com.margelo.nitro.queueplayer

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Constructs the visualizer Tee — a single [TeeAudioProcessor] whose
 * downstream [TeeAudioProcessor.AudioBufferSink] is a
 * [MultiplexingAudioBufferSink] fanning out to every visualizer
 * subscriber the engine has accumulated in [bufferSinks].
 *
 * One builder per ExoPlayer leg. The CrossfadeEngine constructs two
 * (leading + standby); the GaplessEngine constructs one. Each
 * builder owns its own mux registry so [primeNewSink] can replay
 * the most recent flush params onto a late-attaching subscriber —
 * Media3 fires `MultiplexingAudioBufferSink.flush(rate, channels,
 * encoding)` exactly once per AudioSink lifetime, so a sink that
 * arrives after that flush would otherwise receive `handleBuffer`
 * chunks with no encoding context.
 */
@UnstableApi
internal class VisualizerTeeBuilder(
  private val bufferSinks: CopyOnWriteArrayList<TeeAudioProcessor.AudioBufferSink>,
) {

  /**
   * Lifetime invariant: one mux per AudioSink. ExoPlayer constructs a
   * single AudioSink per `ExoPlayer.Builder.build()`, so this list
   * caps at 1 (gapless leg, one builder) for the builder's lifetime.
   * Crossfade has two legs, two builders, one mux each. Entries are
   * never removed.
   */
  private val muxes = CopyOnWriteArrayList<MultiplexingAudioBufferSink>()

  /**
   * The Tee processor wired into the engine's audio-processor array.
   * Always-on for every playback regardless of subscriber count — the
   * cost is documented zero (one virtual dispatch + a buffer position
   * advance per chunk) when the multiplexing sink's fan-out list is
   * empty.
   */
  val audioProcessor: AudioProcessor

  init {
    val mux = MultiplexingAudioBufferSink(bufferSinks)
    muxes.add(mux)
    audioProcessor = TeeAudioProcessor(mux)
  }

  /**
   * Replay the most recent flush params from every mux this builder
   * has produced onto [sink]. Called by the engine immediately after
   * adding `sink` to the shared `bufferSinks` accumulator so a
   * subscriber that arrives after Media3's one-shot `flush` still
   * receives the encoding context every subsequent `handleBuffer`
   * needs.
   */
  fun primeNewSink(sink: TeeAudioProcessor.AudioBufferSink) {
    for (mux in muxes) mux.primeNewSink(sink)
  }
}
