package com.margelo.nitro.queueplayer

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide store for `PlayerConfig.visualizationEnabled`. Written
 * by `TrackPlayer.configureInternal` on every configure call (per the
 * universal auto-destroy contract every configure rebuilds the engine,
 * so the engine's field-init read at construction time always picks up
 * the latest flag).
 *
 * - `GaplessEngine` / `CrossfadeEngine` read `isEnabled()` at field-init
 *   to decide whether the renderers factory wires a
 *   `MultiplexingAudioBufferSink` Tee through
 *   `AudioPipelineRenderersFactory` (enabled, default) or omits it
 *   (disabled — no Tee in the AudioSink chain, no `RadixTwoFFT`
 *   allocation).
 * - `Visualizer.subscribe(...)` reads `isEnabled()` to decide whether to
 *   register the subscription or fire `onError(visualizationDisabled)`
 *   immediately.
 *
 * `AtomicBoolean` covers concurrent reads from the JS bridge thread
 * (subscribe) and writes from the main thread (configure). The pattern
 * mirrors iOS's stateLock-covered `visualizationEnabled` on
 * `AudioTapProvider`.
 */
internal object VisualizationGate {
  private val enabledFlag = AtomicBoolean(true)

  fun isEnabled(): Boolean = enabledFlag.get()

  fun setEnabled(value: Boolean) {
    enabledFlag.set(value)
  }
}
