package com.margelo.nitro.queueplayer.cast

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide effective discovery state. Combines JS-layer intent with
 * the process-foreground state into one effective flag that backends
 * observe to start/stop their scans.
 *
 * - **Intent** — what the JS layer asked for, set by
 *   `CastManager.startDiscovery` / `stopDiscovery`.
 * - **Foregrounded** — `false` when the host process is fully
 *   backgrounded (per `androidx.lifecycle.ProcessLifecycleOwner`), `true`
 *   otherwise. Lifecycle observers in each backend feed this; pausing
 *   discovery while backgrounded keeps multicast scans off the battery.
 *
 * Effective = `intent && foregrounded`. Default state is
 * `intent=false, foregrounded=true` — processes always start foregrounded.
 */
object DiscoveryState {

  private val _intent = MutableStateFlow(false)
  private val _foregrounded = MutableStateFlow(true)
  private val _effective = MutableStateFlow(false)

  /** Read-only view; `true` when intent is on AND the process is foregrounded. */
  val effective: StateFlow<Boolean> = _effective.asStateFlow()

  @Synchronized
  fun setIntent(intent: Boolean) {
    _intent.value = intent
    recompute()
  }

  @Synchronized
  fun setForegrounded(foregrounded: Boolean) {
    _foregrounded.value = foregrounded
    recompute()
  }

  @Synchronized
  fun reset() {
    _intent.value = false
    _foregrounded.value = true
    _effective.value = false
  }

  private fun recompute() {
    _effective.value = _intent.value && _foregrounded.value
  }
}
