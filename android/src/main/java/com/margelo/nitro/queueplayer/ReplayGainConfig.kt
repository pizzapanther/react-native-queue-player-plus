package com.margelo.nitro.queueplayer

import java.util.concurrent.atomic.AtomicReference

/**
 * Process-wide store for the active [ReplayGainMode]. Written by
 * `TrackPlayer.setReplayGainMode` from the JS bridge thread; read on
 * two paths:
 * - The engines' metadata-listener path — after `onTracksChanged`
 *   parses container `Format.metadata` (or `onMetadata` parses
 *   in-stream samples), the gain push selects `track` vs `album`
 *   tags via this mode.
 * - The JS-driven `setReplayGainMode` path — `TrackPlayer` writes
 *   the new mode, then asks each active engine to
 *   `recomputeReplayGainFromConfig` so a mid-track flip lands within
 *   ~1 audio buffer without waiting for fresh metadata.
 *
 * Mirrors [VisualizationGate]'s shape — a process-wide singleton
 * fronting an atomic primitive — but holds an enum rather than a
 * boolean. `AtomicReference` covers concurrent reads / writes without
 * needing an external lock.
 *
 * Default `ReplayGainMode.OFF` so an unconfigured player applies no
 * gain.
 */
internal object ReplayGainConfig {
  private val mode = AtomicReference(ReplayGainMode.OFF)

  fun getMode(): ReplayGainMode = mode.get()

  fun setMode(value: ReplayGainMode) {
    mode.set(value)
  }
}
