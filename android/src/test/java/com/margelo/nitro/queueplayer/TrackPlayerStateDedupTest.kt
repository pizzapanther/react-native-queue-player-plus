package com.margelo.nitro.queueplayer

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrackPlayerStateDedupTest {
  @Test
  fun `state events ignore duplicate values even with different reasons`() {
    val player = TrackPlayer()
    val seen = mutableListOf<Pair<PlayerState, StateChangeReason>>()
    val dispose = player.onStateChange { state, reason -> seen.add(state to reason) }
    assertEquals(0, seen.size)
    player.emitRemoteState(PlayerState.NONE, StateChangeReason.SYSTEM)
    player.emitRemoteState(PlayerState.PLAYING, StateChangeReason.USER)
    player.emitRemoteState(PlayerState.PLAYING, StateChangeReason.SYSTEM)
    player.emitRemoteState(PlayerState.PLAYING, StateChangeReason.INTERRUPTION)
    player.emitRemoteState(PlayerState.PAUSED, StateChangeReason.INTERRUPTION)
    player.emitRemoteState(PlayerState.PAUSED, StateChangeReason.USER)
    player.emitRemoteState(PlayerState.PLAYING, StateChangeReason.SYSTEM)
    // The local and remote paths share the same dedup state.
    player.emitStateIfChanged() // Unconfigured local engine => NONE.
    player.emitStateIfChanged()
    player.emitRemoteState(PlayerState.NONE, StateChangeReason.USER)
    assertEquals(listOf(
      PlayerState.PLAYING to StateChangeReason.USER,
      PlayerState.PAUSED to StateChangeReason.INTERRUPTION,
      PlayerState.PLAYING to StateChangeReason.SYSTEM,
      PlayerState.NONE to StateChangeReason.SYSTEM,
    ), seen)
    dispose()
    player.emitRemoteState(PlayerState.PAUSED, StateChangeReason.SYSTEM)
    assertEquals(4, seen.size)
  }
}
